package de.krah.worldgen.customnodes.density;

import com.hypixel.hytale.builtin.hytalegenerator.density.Density;
import com.hypixel.hytale.builtin.hytalegenerator.pipe.Control;
import com.hypixel.hytale.builtin.hytalegenerator.positionproviders.PositionProvider;
import com.hypixel.hytale.math.vector.Vector3d;
import java.util.Arrays;
import javax.annotation.Nonnull;

/**
 * Solid density along thick 3D segments between positions that lie within {@code maxDistance} of each other
 * (Euclidean), where each segment is thickened by {@code width} on X/Z and {@code height} on Y.
 * <p>
 * <b>Endpoint inset ({@code innerRadius}):</b> before a segment is drawn, both endpoints are pulled
 * horizontally (X/Z only — Y is preserved) toward the other by {@code innerRadius} blocks. Useful for
 * keeping bridge ends out of spherical/disc islands of that radius. If the two endpoints are closer
 * horizontally than {@code 2 * innerRadius}, the pair is skipped entirely (islands already touch / overlap).
 * <p>
 * <b>Per-voxel test:</b> uses the analytical swept-AABB slab test (segment-vs-voxel-box intersection) — constant
 * time per segment per voxel. This replaces the earlier Bresenham-walk-then-compare approach that was
 * {@code O(max(|dx|,|dy|,|dz|))} per segment per sampled voxel.
 * <p>
 * <b>Runtime providers:</b> gathers positions in an axis-aligned box of ±{@code maxDistance} around each sample,
 * keeps only those within Euclidean {@code maxDistance}, and slab-tests every remaining pair with world distance
 * ≤ {@code maxDistance}.
 * <p>
 * <b>Static List / Imported→List:</b> stores the (already shifted) segment endpoints once; queries run the same
 * slab test against all segments.
 */
public class SolidLineDensity extends Density {
    private static final double SLAB_EPS = 1e-12;

    /**
     * Per-instance scratch for dynamic mode (avoids allocations on every density sample).
     * <p>
     * Single-worker-per-instance: the engine clones the density graph per
     * {@link com.hypixel.hytale.builtin.hytalegenerator.workerindexer.WorkerIndexer} worker (see
     * {@code TerrainStage.worldStructure_workerdata}), so one instance field is safe and avoids the
     * per-call {@code ThreadLocal.get()} hash lookup. Null in static mode.
     */
    private final DynamicScratch rDynamicScratch;

    private static final class DynamicScratch {
        double[] worldX = new double[64];
        double[] worldY = new double[64];
        double[] worldZ = new double[64];
        int pointCount;
        final Vector3d rMin = new Vector3d();
        final Vector3d rMax = new Vector3d();
        final PositionProvider.Context ctx = new PositionProvider.Context();

        void appendPoint(@Nonnull Vector3d v) {
            int n = this.pointCount;
            if (n >= this.worldX.length) {
                int cap = Math.max(n + 1, this.worldX.length * 2);
                this.worldX = Arrays.copyOf(this.worldX, cap);
                this.worldY = Arrays.copyOf(this.worldY, cap);
                this.worldZ = Arrays.copyOf(this.worldZ, cap);
            }
            this.worldX[n] = v.x;
            this.worldY[n] = v.y;
            this.worldZ[n] = v.z;
            this.pointCount = n + 1;
        }

        void clearPoints() {
            this.pointCount = 0;
        }
    }

    /**
     * Precomputed, shifted segment endpoints for static mode. Flattened as groups of 6 doubles:
     * {@code [ax, ay, az, bx, by, bz, ...]}. Populated once in the constructor; queries run the slab test
     * against all segments.
     */
    private final double[] staticSegments;
    private final boolean skipped;
    private final boolean dynamic;
    private final PositionProvider positionProvider;
    private final double maxDistance;
    private final int width;
    private final int height;
    private final double innerRadius;

    /**
     * Precomputed static path: every pair in {@code points} with Euclidean distance ≤ {@code maxDistance} becomes
     * a thick segment (endpoints first inset by {@code innerRadius} horizontally).
     */
    public SolidLineDensity(int[][] points, int width, int height, double maxDistance, double innerRadius, boolean skipped) {
        this.skipped = skipped;
        this.dynamic = false;
        this.positionProvider = null;
        this.maxDistance = maxDistance >= 0.0 ? maxDistance : 128.0;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.innerRadius = Math.max(0.0, innerRadius);
        this.rDynamicScratch = null;
        if (!skipped && points != null && points.length >= 2 && this.maxDistance > 0.0) {
            this.staticSegments = buildStaticSegments(points, this.maxDistance, this.innerRadius);
        } else {
            this.staticSegments = new double[0];
        }
    }

    /**
     * Query-time evaluation: gather points in ±{@code maxDistance}, then slab-test every pair within
     * {@code maxDistance} (endpoints inset by {@code innerRadius} horizontally).
     */
    public SolidLineDensity(
            @Nonnull PositionProvider positionProvider,
            double maxDistance,
            int width,
            int height,
            double innerRadius,
            boolean skipped
    ) {
        this.skipped = skipped;
        this.dynamic = true;
        this.positionProvider = positionProvider;
        this.maxDistance = maxDistance >= 0.0 ? maxDistance : 128.0;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.innerRadius = Math.max(0.0, innerRadius);
        this.rDynamicScratch = new DynamicScratch();
        this.staticSegments = null;
    }

    @Override
    public double process(@Nonnull Density.Context context) {
        if (this.skipped) {
            return 0.0;
        }
        int px = (int) Math.floor(context.position.x);
        int py = (int) Math.floor(context.position.y);
        int pz = (int) Math.floor(context.position.z);
        int xMinOff = -((this.width - 1) / 2);
        int xMaxOff = this.width / 2;
        int yMinOff = -((this.height - 1) / 2);
        int yMaxOff = this.height / 2;
        if (this.dynamic) {
            return processDynamic(px, py, pz, context.position, xMinOff, xMaxOff, yMinOff, yMaxOff);
        }
        return processStatic(px, py, pz, xMinOff, xMaxOff, yMinOff, yMaxOff);
    }

    private double processStatic(int px, int py, int pz, int xMinOff, int xMaxOff, int yMinOff, int yMaxOff) {
        double[] seg = this.staticSegments;
        int len = seg.length;
        for (int i = 0; i < len; i += 6) {
            if (segmentIntersectsVoxelBox(
                    px, py, pz,
                    seg[i], seg[i + 1], seg[i + 2],
                    seg[i + 3], seg[i + 4], seg[i + 5],
                    xMinOff, xMaxOff, yMinOff, yMaxOff)) {
                return 1.0;
            }
        }
        return 0.0;
    }

    private double processDynamic(
            int px, int py, int pz, @Nonnull Vector3d position,
            int xMinOff, int xMaxOff, int yMinOff, int yMaxOff
    ) {
        if (this.maxDistance <= 0.0) {
            return 0.0;
        }
        DynamicScratch s = this.rDynamicScratch;
        s.clearPoints();
        s.rMin.assign(position).subtract(this.maxDistance);
        s.rMax.assign(position).add(this.maxDistance);
        s.ctx.bounds.assign(s.rMin, s.rMax);
        s.ctx.pipe = (Vector3d pos, Control ctl) -> s.appendPoint(pos);
        this.positionProvider.generate(s.ctx);
        int n = compactWithinRadius(s, position, this.maxDistance);
        if (n < 2) {
            return 0.0;
        }
        double rSq = this.maxDistance * this.maxDistance;
        double innerR = this.innerRadius;
        double minHorizSq = (2.0 * innerR) * (2.0 * innerR);
        double[] wx = s.worldX;
        double[] wy = s.worldY;
        double[] wz = s.worldZ;
        for (int i = 0; i < n; i++) {
            double ax = wx[i];
            double ay = wy[i];
            double az = wz[i];
            for (int j = i + 1; j < n; j++) {
                double bx = wx[j];
                double by = wy[j];
                double bz = wz[j];
                double dx = ax - bx;
                double dy = ay - by;
                double dz = az - bz;
                if (dx * dx + dy * dy + dz * dz > rSq) {
                    continue;
                }
                double sAx, sAy, sAz, sBx, sBy, sBz;
                if (innerR > 0.0) {
                    double dhx = bx - ax;
                    double dhz = bz - az;
                    double hSq = dhx * dhx + dhz * dhz;
                    if (hSq <= minHorizSq) {
                        continue;
                    }
                    double hDist = Math.sqrt(hSq);
                    double nx = dhx / hDist;
                    double nz = dhz / hDist;
                    double off = innerR;
                    sAx = ax + nx * off;
                    sAy = ay;
                    sAz = az + nz * off;
                    sBx = bx - nx * off;
                    sBy = by;
                    sBz = bz - nz * off;
                } else {
                    sAx = ax; sAy = ay; sAz = az;
                    sBx = bx; sBy = by; sBz = bz;
                }
                if (segmentIntersectsVoxelBox(
                        px, py, pz,
                        sAx, sAy, sAz, sBx, sBy, sBz,
                        xMinOff, xMaxOff, yMinOff, yMaxOff)) {
                    return 1.0;
                }
            }
        }
        return 0.0;
    }

    /**
     * Drops gathered points farther than {@code radius} from {@code origin} (Euclidean). The initial box query can
     * include cube corners beyond that sphere.
     *
     * @return new point count
     */
    private static int compactWithinRadius(@Nonnull DynamicScratch s, @Nonnull Vector3d origin, double radius) {
        double rSq = radius * radius;
        int n = s.pointCount;
        int w = 0;
        for (int r = 0; r < n; r++) {
            double dx = s.worldX[r] - origin.x;
            double dy = s.worldY[r] - origin.y;
            double dz = s.worldZ[r] - origin.z;
            if (dx * dx + dy * dy + dz * dz > rSq) {
                continue;
            }
            if (w != r) {
                s.worldX[w] = s.worldX[r];
                s.worldY[w] = s.worldY[r];
                s.worldZ[w] = s.worldZ[r];
            }
            w++;
        }
        s.pointCount = w;
        return w;
    }

    /**
     * Builds the static segment endpoint array. Each pair of input points within {@code maxDistance} contributes
     * one segment; endpoints are first inset horizontally toward each other by {@code innerRadius} (Y preserved).
     * Pairs whose horizontal distance is ≤ {@code 2 * innerRadius} are dropped.
     */
    private static double[] buildStaticSegments(int[][] pts, double maxDistance, double innerRadius) {
        double rSq = maxDistance * maxDistance;
        double minHorizSq = (2.0 * innerRadius) * (2.0 * innerRadius);
        int n = pts.length;
        double[] buf = new double[Math.max(6, n * 6)];
        int count = 0;
        for (int i = 0; i < n; i++) {
            double ax = pts[i][0];
            double ay = pts[i][1];
            double az = pts[i][2];
            for (int j = i + 1; j < n; j++) {
                double bx = pts[j][0];
                double by = pts[j][1];
                double bz = pts[j][2];
                double dx = ax - bx;
                double dy = ay - by;
                double dz = az - bz;
                if (dx * dx + dy * dy + dz * dz > rSq) {
                    continue;
                }
                double sAx, sAy, sAz, sBx, sBy, sBz;
                if (innerRadius > 0.0) {
                    double dhx = bx - ax;
                    double dhz = bz - az;
                    double hSq = dhx * dhx + dhz * dhz;
                    if (hSq <= minHorizSq) {
                        continue;
                    }
                    double hDist = Math.sqrt(hSq);
                    double nx = dhx / hDist;
                    double nz = dhz / hDist;
                    sAx = ax + nx * innerRadius;
                    sAy = ay;
                    sAz = az + nz * innerRadius;
                    sBx = bx - nx * innerRadius;
                    sBy = by;
                    sBz = bz - nz * innerRadius;
                } else {
                    sAx = ax; sAy = ay; sAz = az;
                    sBx = bx; sBy = by; sBz = bz;
                }
                if (count + 6 > buf.length) {
                    buf = Arrays.copyOf(buf, buf.length * 2);
                }
                buf[count++] = sAx;
                buf[count++] = sAy;
                buf[count++] = sAz;
                buf[count++] = sBx;
                buf[count++] = sBy;
                buf[count++] = sBz;
            }
        }
        return count == buf.length ? buf : Arrays.copyOf(buf, count);
    }

    /**
     * O(1) slab test: does the continuous segment A→B intersect the axis-aligned voxel-expansion box around
     * voxel P?
     * <p>
     * The expansion box for voxel P is {@code [P.x - hMaxOff, P.x - hMinOff + 1]} on X/Z (width governs both
     * horizontal axes) and {@code [P.y - vMaxOff, P.y - vMinOff + 1]} on Y. Derived from "voxel P is solid iff
     * some point on the continuous line falls inside voxel (P - o) for some offset {@code o} in the expansion
     * range". This is the analytical equivalent of the old Bresenham-walk-then-expand check without the
     * per-step loop.
     */
    private static boolean segmentIntersectsVoxelBox(
            int px, int py, int pz,
            double ax, double ay, double az,
            double bx, double by, double bz,
            int hMinOff, int hMaxOff, int vMinOff, int vMaxOff
    ) {
        double boxMinX = px - hMaxOff;
        double boxMaxX = px - hMinOff + 1.0;
        double boxMinY = py - vMaxOff;
        double boxMaxY = py - vMinOff + 1.0;
        double boxMinZ = pz - hMaxOff;
        double boxMaxZ = pz - hMinOff + 1.0;

        double tMin = 0.0;
        double tMax = 1.0;

        double dx = bx - ax;
        if (dx > -SLAB_EPS && dx < SLAB_EPS) {
            if (ax < boxMinX || ax > boxMaxX) return false;
        } else {
            double inv = 1.0 / dx;
            double t1 = (boxMinX - ax) * inv;
            double t2 = (boxMaxX - ax) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }

        double dy = by - ay;
        if (dy > -SLAB_EPS && dy < SLAB_EPS) {
            if (ay < boxMinY || ay > boxMaxY) return false;
        } else {
            double inv = 1.0 / dy;
            double t1 = (boxMinY - ay) * inv;
            double t2 = (boxMaxY - ay) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }

        double dz = bz - az;
        if (dz > -SLAB_EPS && dz < SLAB_EPS) {
            if (az < boxMinZ || az > boxMaxZ) return false;
        } else {
            double inv = 1.0 / dz;
            double t1 = (boxMinZ - az) * inv;
            double t2 = (boxMaxZ - az) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }

        return true;
    }
}
