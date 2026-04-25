package de.krah.worldgen.customnodes.positionproviders;

import com.hypixel.hytale.builtin.hytalegenerator.bounds.Bounds3d;
import com.hypixel.hytale.builtin.hytalegenerator.density.nodes.positions.distancefunctions.DistanceFunction;
import com.hypixel.hytale.builtin.hytalegenerator.pipe.Control;
import com.hypixel.hytale.builtin.hytalegenerator.pipe.Pipe;
import com.hypixel.hytale.builtin.hytalegenerator.positionproviders.PositionProvider;
import com.hypixel.hytale.math.vector.Vector3d;
import javax.annotation.Nonnull;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

/**
 * Emits the closest source position to the current query-center.
 *
 * <p>When called with finite query bounds (the normal chunk-generation path) the sample point is
 * the center of those bounds and child emissions farther than {@code maxDistance} from the sample
 * are filtered out.
 *
 * <p>When called with unbounded (non-finite) query bounds — as happens during world-level spawn
 * enumeration (see {@code HytaleGenerator.getSpawnPositions}) — naively forwarding those bounds
 * would make deterministic-lattice children (e.g. {@code DeterministicRandomPositions}) iterate
 * the entire world and hang. In that case we:
 * <ul>
 *   <li>use {@code context.anchor} (if set) or the world origin as the sample point;</li>
 *   <li>pass the child provider a finite search cube ({@link #UNBOUNDED_SEARCH_HALF_EXTENT}) around
 *       the sample point so it can terminate;</li>
 *   <li>ignore the {@code maxDistance} filter so we still return <em>a</em> closest candidate for
 *       spawn selection, even if it lies far from the sample point.</li>
 * </ul>
 */
public final class ClosestPositionProvider extends PositionProvider {
    /**
     * Half-extent of the finite search cube used when the query bounds are unbounded.
     * Sized so that any sanely-spaced deterministic island lattice will have many candidates
     * inside the window, while keeping the iteration cheap (one-shot, at world init).
     */
    private static final double UNBOUNDED_SEARCH_HALF_EXTENT = 8192.0;

    @Nonnull
    private final PositionProvider positions;
    @Nonnull
    private final DistanceFunction distanceFunction;
    private final double maxDistanceRaw;
    @Nonnull
    private final Vector3d samplePoint = new Vector3d();
    @Nonnull
    private final Vector3d localPoint = new Vector3d();
    @Nonnull
    private final Vector3d closestPoint = new Vector3d();
    @Nonnull
    private final PositionProvider.Context childContext = new PositionProvider.Context();
    @Nonnull
    private final Control reusableControl = new Control();
    @Nonnull
    private PositionProvider.Context currentContext = new PositionProvider.Context();
    private boolean hasClosestPoint;
    private double closestDistance;
    private boolean unboundedQuery;

    @Nonnull
    private final Pipe.One<Vector3d> childPipe = new Pipe.One<Vector3d>() {
        @Override
        public void accept(@NonNullDecl Vector3d providedPoint, @NonNullDecl Control control) {
            ClosestPositionProvider.this.localPoint.x = providedPoint.x - ClosestPositionProvider.this.samplePoint.x;
            ClosestPositionProvider.this.localPoint.y = providedPoint.y - ClosestPositionProvider.this.samplePoint.y;
            ClosestPositionProvider.this.localPoint.z = providedPoint.z - ClosestPositionProvider.this.samplePoint.z;
            double distance = ClosestPositionProvider.this.distanceFunction.getDistance(ClosestPositionProvider.this.localPoint);
            if (!ClosestPositionProvider.this.unboundedQuery && distance > ClosestPositionProvider.this.maxDistanceRaw) {
                return;
            }
            if (!ClosestPositionProvider.this.hasClosestPoint || distance < ClosestPositionProvider.this.closestDistance) {
                ClosestPositionProvider.this.closestDistance = distance;
                ClosestPositionProvider.this.closestPoint.assign(providedPoint);
                ClosestPositionProvider.this.hasClosestPoint = true;
            }
        }
    };

    public ClosestPositionProvider(@Nonnull PositionProvider positions, @Nonnull DistanceFunction distanceFunction, double maxDistance) {
        this.positions = positions;
        this.distanceFunction = distanceFunction;
        this.maxDistanceRaw = maxDistance * maxDistance;
    }

    @Override
    public void generate(@Nonnull Context context) {
        this.currentContext = context;

        final double minX = context.bounds.min.x;
        final double minY = context.bounds.min.y;
        final double minZ = context.bounds.min.z;
        final double maxX = context.bounds.max.x;
        final double maxY = context.bounds.max.y;
        final double maxZ = context.bounds.max.z;
        this.unboundedQuery = !(Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ));

        final double sx;
        final double sy;
        final double sz;
        if (this.unboundedQuery) {
            final Vector3d anchor = context.anchor;
            if (anchor != null) {
                sx = anchor.x;
                sy = anchor.y;
                sz = anchor.z;
            } else {
                sx = 0.0;
                sy = 0.0;
                sz = 0.0;
            }
        } else {
            sx = (minX + maxX) * 0.5;
            sy = (minY + maxY) * 0.5;
            sz = (minZ + maxZ) * 0.5;
        }
        this.samplePoint.assign(sx, sy, sz);
        this.hasClosestPoint = false;
        this.closestDistance = Double.MAX_VALUE;

        this.childContext.assign(context);
        this.childContext.pipe = this.childPipe;
        if (this.unboundedQuery) {
            final double r = UNBOUNDED_SEARCH_HALF_EXTENT;
            this.childContext.bounds = new Bounds3d(
                    new Vector3d(sx - r, sy - r, sz - r),
                    new Vector3d(sx + r, sy + r, sz + r));
        }
        this.positions.generate(this.childContext);

        if (!this.hasClosestPoint) {
            return;
        }
        if (!this.unboundedQuery && !this.currentContext.bounds.contains(this.closestPoint)) {
            return;
        }
        this.reusableControl.reset();
        this.currentContext.pipe.accept(this.closestPoint, this.reusableControl);
    }
}
