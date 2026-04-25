package de.krah.nodeeditorweb;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Binary greedy mesher: merges adjacent visible voxel faces into quads per axis.
 * Optimized for cache locality, no string comparisons, binary solid mask, flat buffers.
 * The 6 face directions are computed in parallel (read-only solid, no locks).
 */
public final class GreedyMesher {
    /**
     * Guardrail: if a single direction exceeds this many quads, re-mesh with a coarser step.
     * This is intentionally high so normal terrain remains full-detail.
     */
    private static final int MAX_QUADS_PER_DIRECTION = 20_000_000;

    public static final class MeshResult {
        public final float originX, originY, originZ;
        public final float voxelSize;
        public final float[] positions;
        public final float[] normals;
        public final int[] indices;

        public MeshResult(float originX, float originY, float originZ, float voxelSize,
                         float[] positions, float[] normals, int[] indices) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.voxelSize = voxelSize;
            this.positions = positions;
            this.normals = normals;
            this.indices = indices;
        }
    }

    /** Per-direction output for parallel merge. */
    private static final class FaceData {
        final float[] positions;
        final float[] normals;
        final int[] indices;
        final int posLen;
        final int idxLen;

        FaceData(float[] positions, float[] normals, int[] indices, int posLen, int idxLen) {
            this.positions = positions;
            this.normals = normals;
            this.indices = indices;
            this.posLen = posLen;
            this.idxLen = idxLen;
        }
    }

    /** Internal signal used to trigger adaptive re-meshing with a larger step. */
    private static final class MeshTooComplexException extends RuntimeException {
        MeshTooComplexException(String message) {
            super(message);
        }
    }

    private static boolean isOrCausedByMeshTooComplex(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof MeshTooComplexException) return true;
            cur = cur.getCause();
        }
        return false;
    }

    private static final int NUM_DIRECTIONS = 6;
    private static final ExecutorService MESH_EXECUTOR = Executors.newFixedThreadPool(
            Math.min(NUM_DIRECTIONS, Runtime.getRuntime().availableProcessors()));

    private GreedyMesher() {}

    /**
     * Greedy mesh a 2D binary plane. Grid and used are row-major byte arrays (rows*cols).
     * Writes quads to out as (row, col, w, h) x 4 ints per quad. Returns number of quads.
     */
    private static int greedyMeshBinaryPlane(byte[] grid, int rows, int cols,
                                             int[] out, int outOffset) {
        int quads = 0;
        final int maxQuads = (out.length - outOffset) / 4;
        // used is in-place in grid: we'll overwrite as we consume
        for (int row = 0; row < rows; row++) {
            int col = 0;
            while (col < cols) {
                int idx = row * cols + col;
                if (grid[idx] != 1) {
                    col++;
                    continue;
                }
                int h = 0;
                while (col + h < cols && grid[row * cols + col + h] == 1) h++;
                int w = 1;
                while (row + w < rows) {
                    boolean ok = true;
                    for (int c = 0; c < h && ok; c++) {
                        if (grid[(row + w) * cols + col + c] != 1) ok = false;
                    }
                    if (!ok) break;
                    for (int c = 0; c < h; c++) grid[(row + w) * cols + col + c] = 0;
                    w++;
                }
                if (quads < maxQuads) {
                    int o = outOffset + quads * 4;
                    out[o] = row;
                    out[o + 1] = col;
                    out[o + 2] = w;
                    out[o + 3] = h;
                }
                quads++;
                col += h;
            }
        }
        return quads;
    }

    /**
     * Build mesh from a binary solid volume (1 = solid, 0 = air).
     * The 6 face directions are computed in parallel (no shared mutable state, no locks).
     */
    public static MeshResult buildMesh(int width, int height, int depth,
                                       int startX, int startY, int startZ,
                                       byte[] solid, float voxelSize, int step) {
        final int widthHeight = width * height;
        List<Future<FaceData>> futures = new ArrayList<>(NUM_DIRECTIONS);
        for (int dir = 0; dir < NUM_DIRECTIONS; dir++) {
            final int d = dir;
            futures.add(MESH_EXECUTOR.submit(() -> buildOneDirection(d, solid, width, height, depth, widthHeight, step, (float) startX, (float) startY, (float) startZ, voxelSize)));
        }
        return mergeMeshResults(futures, startX, startY, startZ, voxelSize * step);
    }

    /**
     * Build mesh from density volume. Binary: solid where density > 0.
     * Delegates to {@link #buildMesh(int, int, int, int, int, int, byte[], float, int)} after converting to solid.
     */
    public static MeshResult buildMesh(int width, int height, int depth,
                                       int startX, int startY, int startZ,
                                       float[] densities, float voxelSize, int step) {
        final int vol = width * height * depth;
        byte[] solid = new byte[vol];
        for (int i = 0; i < vol; i++) {
            solid[i] = (byte) (densities[i] > 0 ? 1 : 0);
        }
        return buildMesh(width, height, depth, startX, startY, startZ, solid, voxelSize, step);
    }

    /**
     * Adaptive meshing: starts at minStep and retries with coarser steps when mesh complexity
     * would become too large for practical transport/rendering.
     */
    public static MeshResult buildMeshAdaptive(int width, int height, int depth,
                                               int startX, int startY, int startZ,
                                               byte[] solid, float voxelSize,
                                               int minStep, int maxStep,
                                               long maxOutputBytes) {
        int step = Math.max(1, minStep);
        RuntimeException last = null;
        while (step <= maxStep) {
            try {
                MeshResult mesh = buildMesh(width, height, depth, startX, startY, startZ, solid, voxelSize, step);
                long outputBytes =
                        (long) mesh.positions.length * 4L +
                        (long) mesh.normals.length * 4L +
                        (long) mesh.indices.length * 4L;
                if (outputBytes <= maxOutputBytes) {
                    return mesh;
                }
                last = new MeshTooComplexException("mesh output " + outputBytes + " bytes exceeds limit " + maxOutputBytes);
            } catch (RuntimeException e) {
                if (isOrCausedByMeshTooComplex(e)) {
                    last = e;
                } else {
                    throw e;
                }
            }
            if (step >= maxStep) break;
            step = Math.min(maxStep, step * 2);
        }
        throw new RuntimeException("Adaptive meshing failed to fit complexity bounds.", last);
    }

    private static MeshResult mergeMeshResults(List<Future<FaceData>> futures, int startX, int startY, int startZ, float voxelSize) {
        List<FaceData> results = new ArrayList<>(NUM_DIRECTIONS);
        try {
            for (Future<FaceData> future : futures) {
                results.add(future.get());
            }
        } catch (Exception e) {
            if (isOrCausedByMeshTooComplex(e)) {
                throw new MeshTooComplexException("Parallel mesh exceeded complexity budget.");
            }
            throw new RuntimeException("Parallel mesh failed", e);
        }

        long totalPosLenLong = 0L;
        long totalIdxLenLong = 0L;
        for (FaceData fd : results) {
            totalPosLenLong += fd.posLen;
            totalIdxLenLong += fd.idxLen;
        }
        if (totalPosLenLong > Integer.MAX_VALUE || totalIdxLenLong > Integer.MAX_VALUE) {
            throw new MeshTooComplexException("Merged mesh arrays exceed int indexable size.");
        }
        int totalPosLen = (int) totalPosLenLong;
        int totalIdxLen = (int) totalIdxLenLong;
        float[] positions = new float[totalPosLen];
        float[] normals = new float[totalPosLen];
        int[] indices = new int[totalIdxLen];
        int posOff = 0;
        int idxOff = 0;
        int vertexOffset = 0;
        for (FaceData fd : results) {
            System.arraycopy(fd.positions, 0, positions, posOff, fd.posLen);
            System.arraycopy(fd.normals, 0, normals, posOff, fd.posLen);
            for (int i = 0; i < fd.idxLen; i++) {
                indices[idxOff + i] = fd.indices[i] + vertexOffset;
            }
            idxOff += fd.idxLen;
            int numQuads = fd.idxLen / 6;
            vertexOffset += numQuads * 4;
            posOff += fd.posLen;
        }

        return new MeshResult(startX, startY, startZ, voxelSize, positions, normals, indices);
    }

    /**
     * Build faces for one direction (0=UP, 1=DOWN, 2=LEFT, 3=RIGHT, 4=BACK, 5=FWD).
     * Called in parallel; only reads from solid, uses thread-local grid/quadBuf.
     */
    private static FaceData buildOneDirection(int direction, byte[] solid, int width, int height, int depth, int widthHeight,
                                              int step, float oX, float oY, float oZ, float v) {
        int gridRows, gridCols;
        if (direction <= 1) {
            gridRows = (depth + step - 1) / step;
            gridCols = (width + step - 1) / step;
        } else if (direction <= 3) {
            gridRows = (height + step - 1) / step;
            gridCols = (depth + step - 1) / step;
        } else {
            gridRows = (height + step - 1) / step;
            gridCols = (width + step - 1) / step;
        }
        int maxSlice = gridRows * gridCols;
        byte[] grid = new byte[maxSlice];
        int[] quadBuf = new int[maxSlice * 4];

        int posCap = 262144;
        int idxCap = 131072;
        float[] positions = new float[posCap];
        float[] normals = new float[posCap];
        int[] indices = new int[idxCap];
        int posLen = 0;
        int idxLen = 0;
        int vertexOffset = 0;
        int emittedQuads = 0;

        switch (direction) {
            case 0: // UP
                for (int slice = 0; slice < height; slice += step) {
            for (int ri = 0; ri < gridRows; ri++) {
                int z = ri * step;
                for (int ci = 0; ci < gridCols; ci++) {
                    int x = ci * step;
                    int idx = x + slice * width + z * widthHeight;
                    int idxUp = slice + 1 < height ? x + (slice + 1) * width + z * widthHeight : -1;
                    grid[ri * gridCols + ci] = (byte) ((solid[idx] != 0 && (idxUp < 0 || solid[idxUp] == 0)) ? 1 : 0);
                }
            }
            int nq = greedyMeshBinaryPlane(grid, gridRows, gridCols, quadBuf, 0);
            emittedQuads += nq;
            if (emittedQuads > MAX_QUADS_PER_DIRECTION) {
                throw new MeshTooComplexException("Too many quads in UP faces: " + emittedQuads);
            }
            if (posLen + nq * 12 > positions.length) {
                int newLen = Math.max(positions.length * 2, posLen + nq * 12);
                positions = java.util.Arrays.copyOf(positions, newLen);
                normals = java.util.Arrays.copyOf(normals, newLen);
            }
            if (idxLen + nq * 6 > indices.length) {
                indices = java.util.Arrays.copyOf(indices, Math.max(indices.length * 2, idxLen + nq * 6));
            }
            float ax = slice;
            for (int qi = 0; qi < nq; qi++) {
                int r = quadBuf[qi * 4], c = quadBuf[qi * 4 + 1], w = quadBuf[qi * 4 + 2], h = quadBuf[qi * 4 + 3];
                float u0 = c * step * v, v0 = r * step * v, u1 = (c + h) * step * v, v1 = (r + w) * step * v;
                float y0 = (ax + 1) * v;
                emitQuad(positions, normals, indices, posLen, idxLen, vertexOffset,
                        oX + u0, oY + y0, oZ + v0,
                        oX + u1, oY + y0, oZ + v0,
                        oX + u1, oY + y0, oZ + v1,
                        oX + u0, oY + y0, oZ + v1,
                        0, 1, 0);
                posLen += 12;
                idxLen += 6;
                vertexOffset += 4;
            }
        }
                break;
            case 1: // DOWN
                for (int slice = 0; slice < height; slice += step) {
            for (int ri = 0; ri < gridRows; ri++) {
                int z = ri * step;
                for (int ci = 0; ci < gridCols; ci++) {
                    int x = ci * step;
                    int idx = x + slice * width + z * widthHeight;
                    int idxDown = slice > 0 ? x + (slice - 1) * width + z * widthHeight : -1;
                    grid[ri * gridCols + ci] = (byte) ((solid[idx] != 0 && (idxDown < 0 || solid[idxDown] == 0)) ? 1 : 0);
                }
            }
            int nq = greedyMeshBinaryPlane(grid, gridRows, gridCols, quadBuf, 0);
            emittedQuads += nq;
            if (emittedQuads > MAX_QUADS_PER_DIRECTION) {
                throw new MeshTooComplexException("Too many quads in DOWN faces: " + emittedQuads);
            }
            if (posLen + nq * 12 > positions.length) {
                int newLen = Math.max(positions.length * 2, posLen + nq * 12);
                positions = java.util.Arrays.copyOf(positions, newLen);
                normals = java.util.Arrays.copyOf(normals, newLen);
            }
            if (idxLen + nq * 6 > indices.length) {
                indices = java.util.Arrays.copyOf(indices, Math.max(indices.length * 2, idxLen + nq * 6));
            }
            float ax = slice;
            for (int qi = 0; qi < nq; qi++) {
                int r = quadBuf[qi * 4], c = quadBuf[qi * 4 + 1], w = quadBuf[qi * 4 + 2], h = quadBuf[qi * 4 + 3];
                float u0 = c * step * v, v0 = r * step * v, u1 = (c + h) * step * v, v1 = (r + w) * step * v;
                float y0 = ax * v;
                emitQuad(positions, normals, indices, posLen, idxLen, vertexOffset,
                        oX + u0, oY + y0, oZ + v0,
                        oX + u0, oY + y0, oZ + v1,
                        oX + u1, oY + y0, oZ + v1,
                        oX + u1, oY + y0, oZ + v0,
                        0, -1, 0);
                posLen += 12;
                idxLen += 6;
                vertexOffset += 4;
            }
        }
                break;
            case 2: // LEFT
                for (int slice = 0; slice < width; slice += step) {
            for (int ri = 0; ri < gridRows; ri++) {
                int y = ri * step;
                for (int ci = 0; ci < gridCols; ci++) {
                    int z = ci * step;
                    int idx = slice + y * width + z * widthHeight;
                    int idxLeft = slice > 0 ? (slice - 1) + y * width + z * widthHeight : -1;
                    grid[ri * gridCols + ci] = (byte) ((solid[idx] != 0 && (idxLeft < 0 || solid[idxLeft] == 0)) ? 1 : 0);
                }
            }
            int nq = greedyMeshBinaryPlane(grid, gridRows, gridCols, quadBuf, 0);
            emittedQuads += nq;
            if (emittedQuads > MAX_QUADS_PER_DIRECTION) {
                throw new MeshTooComplexException("Too many quads in LEFT faces: " + emittedQuads);
            }
            if (posLen + nq * 12 > positions.length) {
                int newLen = Math.max(positions.length * 2, posLen + nq * 12);
                positions = java.util.Arrays.copyOf(positions, newLen);
                normals = java.util.Arrays.copyOf(normals, newLen);
            }
            if (idxLen + nq * 6 > indices.length) {
                indices = java.util.Arrays.copyOf(indices, Math.max(indices.length * 2, idxLen + nq * 6));
            }
            float ax = slice;
            for (int qi = 0; qi < nq; qi++) {
                int r = quadBuf[qi * 4], c = quadBuf[qi * 4 + 1], w = quadBuf[qi * 4 + 2], h = quadBuf[qi * 4 + 3];
                float u0 = r * step * v, v0 = c * step * v, u1 = (r + w) * step * v, v1 = (c + h) * step * v;
                float x0 = ax * v;
                emitQuad(positions, normals, indices, posLen, idxLen, vertexOffset,
                        oX + x0, oY + u0, oZ + v0,
                        oX + x0, oY + u0, oZ + v1,
                        oX + x0, oY + u1, oZ + v1,
                        oX + x0, oY + u1, oZ + v0,
                        -1, 0, 0);
                posLen += 12;
                idxLen += 6;
                vertexOffset += 4;
            }
        }
                break;
            case 3: // RIGHT
                for (int slice = 0; slice < width; slice += step) {
            for (int ri = 0; ri < gridRows; ri++) {
                int y = ri * step;
                for (int ci = 0; ci < gridCols; ci++) {
                    int z = ci * step;
                    int idx = slice + y * width + z * widthHeight;
                    int idxRight = slice + 1 < width ? (slice + 1) + y * width + z * widthHeight : -1;
                    grid[ri * gridCols + ci] = (byte) ((solid[idx] != 0 && (idxRight < 0 || solid[idxRight] == 0)) ? 1 : 0);
                }
            }
            int nq = greedyMeshBinaryPlane(grid, gridRows, gridCols, quadBuf, 0);
            emittedQuads += nq;
            if (emittedQuads > MAX_QUADS_PER_DIRECTION) {
                throw new MeshTooComplexException("Too many quads in RIGHT faces: " + emittedQuads);
            }
            if (posLen + nq * 12 > positions.length) {
                int newLen = Math.max(positions.length * 2, posLen + nq * 12);
                positions = java.util.Arrays.copyOf(positions, newLen);
                normals = java.util.Arrays.copyOf(normals, newLen);
            }
            if (idxLen + nq * 6 > indices.length) {
                indices = java.util.Arrays.copyOf(indices, Math.max(indices.length * 2, idxLen + nq * 6));
            }
            float ax = slice;
            for (int qi = 0; qi < nq; qi++) {
                int r = quadBuf[qi * 4], c = quadBuf[qi * 4 + 1], w = quadBuf[qi * 4 + 2], h = quadBuf[qi * 4 + 3];
                float u0 = (r + w) * step * v, v0 = c * step * v, u1 = r * step * v, v1 = (c + h) * step * v;
                float x0 = (ax + 1) * v;
                emitQuad(positions, normals, indices, posLen, idxLen, vertexOffset,
                        oX + x0, oY + u0, oZ + v0,
                        oX + x0, oY + u0, oZ + v1,
                        oX + x0, oY + u1, oZ + v1,
                        oX + x0, oY + u1, oZ + v0,
                        1, 0, 0);
                posLen += 12;
                idxLen += 6;
                vertexOffset += 4;
            }
        }
                break;
            case 4: // BACK
                for (int slice = 0; slice < depth; slice += step) {
            for (int ri = 0; ri < gridRows; ri++) {
                int y = ri * step;
                for (int ci = 0; ci < gridCols; ci++) {
                    int x = ci * step;
                    int idx = x + y * width + slice * widthHeight;
                    int idxBack = slice > 0 ? x + y * width + (slice - 1) * widthHeight : -1;
                    grid[ri * gridCols + ci] = (byte) ((solid[idx] != 0 && (idxBack < 0 || solid[idxBack] == 0)) ? 1 : 0);
                }
            }
            int nq = greedyMeshBinaryPlane(grid, gridRows, gridCols, quadBuf, 0);
            emittedQuads += nq;
            if (emittedQuads > MAX_QUADS_PER_DIRECTION) {
                throw new MeshTooComplexException("Too many quads in BACK faces: " + emittedQuads);
            }
            if (posLen + nq * 12 > positions.length) {
                int newLen = Math.max(positions.length * 2, posLen + nq * 12);
                positions = java.util.Arrays.copyOf(positions, newLen);
                normals = java.util.Arrays.copyOf(normals, newLen);
            }
            if (idxLen + nq * 6 > indices.length) {
                indices = java.util.Arrays.copyOf(indices, Math.max(indices.length * 2, idxLen + nq * 6));
            }
            float ax = slice;
            for (int qi = 0; qi < nq; qi++) {
                int r = quadBuf[qi * 4], c = quadBuf[qi * 4 + 1], w = quadBuf[qi * 4 + 2], h = quadBuf[qi * 4 + 3];
                float u0 = c * step * v, v0 = r * step * v, u1 = (c + h) * step * v, v1 = (r + w) * step * v;
                float z0 = ax * v;
                emitQuad(positions, normals, indices, posLen, idxLen, vertexOffset,
                        oX + u0, oY + v0, oZ + z0,
                        oX + u0, oY + v1, oZ + z0,
                        oX + u1, oY + v1, oZ + z0,
                        oX + u1, oY + v0, oZ + z0,
                        0, 0, -1);
                posLen += 12;
                idxLen += 6;
                vertexOffset += 4;
            }
        }
                break;
            default: // 5 = FWD
                for (int slice = 0; slice < depth; slice += step) {
            for (int ri = 0; ri < gridRows; ri++) {
                int y = ri * step;
                for (int ci = 0; ci < gridCols; ci++) {
                    int x = ci * step;
                    int idx = x + y * width + slice * widthHeight;
                    int idxFwd = slice + 1 < depth ? x + y * width + (slice + 1) * widthHeight : -1;
                    grid[ri * gridCols + ci] = (byte) ((solid[idx] != 0 && (idxFwd < 0 || solid[idxFwd] == 0)) ? 1 : 0);
                }
            }
            int nq = greedyMeshBinaryPlane(grid, gridRows, gridCols, quadBuf, 0);
            emittedQuads += nq;
            if (emittedQuads > MAX_QUADS_PER_DIRECTION) {
                throw new MeshTooComplexException("Too many quads in FWD faces: " + emittedQuads);
            }
            if (posLen + nq * 12 > positions.length) {
                int newLen = Math.max(positions.length * 2, posLen + nq * 12);
                positions = java.util.Arrays.copyOf(positions, newLen);
                normals = java.util.Arrays.copyOf(normals, newLen);
            }
            if (idxLen + nq * 6 > indices.length) {
                indices = java.util.Arrays.copyOf(indices, Math.max(indices.length * 2, idxLen + nq * 6));
            }
            float ax = slice;
            for (int qi = 0; qi < nq; qi++) {
                int r = quadBuf[qi * 4], c = quadBuf[qi * 4 + 1], w = quadBuf[qi * 4 + 2], h = quadBuf[qi * 4 + 3];
                float u0 = (c + h) * step * v, v0 = r * step * v, u1 = c * step * v, v1 = (r + w) * step * v;
                float z0 = (ax + 1) * v;
                emitQuad(positions, normals, indices, posLen, idxLen, vertexOffset,
                        oX + u0, oY + v0, oZ + z0,
                        oX + u0, oY + v1, oZ + z0,
                        oX + u1, oY + v1, oZ + z0,
                        oX + u1, oY + v0, oZ + z0,
                        0, 0, 1);
                posLen += 12;
                idxLen += 6;
                vertexOffset += 4;
            }
        }
        }

        return new FaceData(
                java.util.Arrays.copyOf(positions, posLen),
                java.util.Arrays.copyOf(normals, posLen),
                java.util.Arrays.copyOf(indices, idxLen),
                posLen, idxLen);
    }

    private static void emitQuad(float[] pos, float[] nrm, int[] idx,
                                  int posLen, int idxLen, int base,
                                  float x0, float y0, float z0,
                                  float x1, float y1, float z1,
                                  float x2, float y2, float z2,
                                  float x3, float y3, float z3,
                                  float nx, float ny, float nz) {
        pos[posLen] = x0; pos[posLen + 1] = y0; pos[posLen + 2] = z0;
        pos[posLen + 3] = x1; pos[posLen + 4] = y1; pos[posLen + 5] = z1;
        pos[posLen + 6] = x2; pos[posLen + 7] = y2; pos[posLen + 8] = z2;
        pos[posLen + 9] = x3; pos[posLen + 10] = y3; pos[posLen + 11] = z3;
        nrm[posLen] = nx; nrm[posLen + 1] = ny; nrm[posLen + 2] = nz;
        nrm[posLen + 3] = nx; nrm[posLen + 4] = ny; nrm[posLen + 5] = nz;
        nrm[posLen + 6] = nx; nrm[posLen + 7] = ny; nrm[posLen + 8] = nz;
        nrm[posLen + 9] = nx; nrm[posLen + 10] = ny; nrm[posLen + 11] = nz;
        idx[idxLen] = base; idx[idxLen + 1] = base + 1; idx[idxLen + 2] = base + 2;
        idx[idxLen + 3] = base; idx[idxLen + 4] = base + 2; idx[idxLen + 5] = base + 3;
    }
}
