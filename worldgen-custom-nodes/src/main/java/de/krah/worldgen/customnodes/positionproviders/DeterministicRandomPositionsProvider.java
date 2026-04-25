package de.krah.worldgen.customnodes.positionproviders;

import com.hypixel.hytale.builtin.hytalegenerator.noise.FastNoiseLite;
import com.hypixel.hytale.builtin.hytalegenerator.pipe.Control;
import com.hypixel.hytale.builtin.hytalegenerator.positionproviders.PositionProvider;
import com.hypixel.hytale.math.vector.Vector3d;
import javax.annotation.Nonnull;

/**
 * One world position per XZ repeat cell: geometric center of the cell, optionally offset by deterministic
 * mesh-style jitter ({@link FastNoiseLite#pointFor(int, double, double, double, double)} on lattice
 * {@code (cx, baseY, cz)} with amplitude {@code 1.0}, scaled per axis by {@code Jitter*}).
 * <p>
 * Order: increasing {@code cz}, then {@code cx}. Same seed and cell indices always yield the same point.
 * <p>
 * <b>Thread safety:</b> single-worker-per-instance. The engine clones the whole density / position-provider
 * graph per {@link com.hypixel.hytale.builtin.hytalegenerator.workerindexer.WorkerIndexer} worker (see
 * {@code TerrainStage.worldStructure_workerdata}), so reusable scratch buffers live as instance fields here
 * (matching {@code Jitter3dPositionProvider}, {@code DensityYOffsetPositionProvider}). {@link #MESH_JITTER}
 * is genuinely shared across workers but its {@code pointFor(seed, ...)} is stateless w.r.t. instance fields.
 */
public final class DeterministicRandomPositionsProvider extends PositionProvider {

    private static final FastNoiseLite MESH_JITTER = new FastNoiseLite();

    /** Reused for {@code accept}; consumers must copy coordinates before returning (e.g. floored int buffer). */
    private final Vector3d rEmit = new Vector3d();

    private final Control rControl = new Control();

    private final int originX;
    private final int originZ;
    private final int repeatX;
    private final int repeatZ;
    private final int baseY;
    private final int seed;
    private final double jitterX;
    private final double jitterY;
    private final double jitterZ;

    public DeterministicRandomPositionsProvider(
            int originX,
            int originZ,
            int repeatX,
            int repeatZ,
            int baseY,
            int seed,
            double jitterX,
            double jitterY,
            double jitterZ
    ) {
        this.originX = originX;
        this.originZ = originZ;
        this.repeatX = repeatX;
        this.repeatZ = repeatZ;
        this.baseY = baseY;
        this.seed = seed;
        this.jitterX = jitterX;
        this.jitterY = jitterY;
        this.jitterZ = jitterZ;
    }

    @Override
    public void generate(@Nonnull PositionProvider.Context context) {
        double minX = context.bounds.min.x;
        double minY = context.bounds.min.y;
        double minZ = context.bounds.min.z;
        double maxX = context.bounds.max.x;
        double maxY = context.bounds.max.y;
        double maxZ = context.bounds.max.z;

        if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
            return;
        }

        if (this.jitterY <= 0.0) {
            if (this.baseY < minY || this.baseY >= maxY) {
                return;
            }
        } else {
            double yLo = (double) this.baseY - this.jitterY;
            double yHi = (double) this.baseY + this.jitterY;
            if (yHi <= minY || yLo >= maxY) {
                return;
            }
        }

        int minBx = (int) Math.floor(minX);
        int maxBx = (int) Math.ceil(maxX) - 1;
        int minBz = (int) Math.floor(minZ);
        int maxBz = (int) Math.ceil(maxZ) - 1;

        if (maxBx < minBx || maxBz < minBz) {
            return;
        }

        int cx0 = Math.floorDiv(minBx - this.originX, this.repeatX);
        int cx1 = Math.floorDiv(maxBx - this.originX, this.repeatX);
        int cz0 = Math.floorDiv(minBz - this.originZ, this.repeatZ);
        int cz1 = Math.floorDiv(maxBz - this.originZ, this.repeatZ);

        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                int ox = this.originX + cx * this.repeatX;
                int oz = this.originZ + cz * this.repeatZ;
                int x1 = ox;
                int z1 = oz;
                int x2 = ox + this.repeatX - 1;
                int z2 = oz + this.repeatZ - 1;
                if (x2 < minBx || x1 > maxBx || z2 < minBz || z1 > maxBz) {
                    continue;
                }
                double lx = ox + this.repeatX * 0.5;
                double lz = oz + this.repeatZ * 0.5;
                if (this.jitterX <= 0.0 && this.jitterY <= 0.0 && this.jitterZ <= 0.0) {
                    emit(context, lx, this.baseY, lz);
                } else {
                    Vector3d p = MESH_JITTER.pointFor(this.seed, 1.0, cx, this.baseY, cz);
                    double dx = (p.x - cx) * this.jitterX;
                    double dy = (p.y - this.baseY) * this.jitterY;
                    double dz = (p.z - cz) * this.jitterZ;
                    emit(context, lx + dx, this.baseY + dy, lz + dz);
                }
            }
        }
    }

    private void emit(@Nonnull PositionProvider.Context context, double x, double y, double z) {
        this.rEmit.x = x;
        this.rEmit.y = y;
        this.rEmit.z = z;
        this.rControl.reset();
        context.pipe.accept(this.rEmit, this.rControl);
    }
}
