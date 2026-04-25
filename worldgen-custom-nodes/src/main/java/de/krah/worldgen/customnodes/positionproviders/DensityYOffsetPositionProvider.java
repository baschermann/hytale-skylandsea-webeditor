package de.krah.worldgen.customnodes.positionproviders;

import com.hypixel.hytale.builtin.hytalegenerator.bounds.Bounds3d;
import com.hypixel.hytale.builtin.hytalegenerator.density.Density;
import com.hypixel.hytale.builtin.hytalegenerator.pipe.Control;
import com.hypixel.hytale.builtin.hytalegenerator.pipe.Pipe;
import com.hypixel.hytale.builtin.hytalegenerator.positionproviders.PositionProvider;
import com.hypixel.hytale.math.vector.Vector3d;
import javax.annotation.Nonnull;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

/**
 * For each position from a child provider, samples a density field at that point and adds
 * {@code multiplier * densityValue} to Y before forwarding (same evaluation pattern as Occurrence / FieldFunction positions).
 * <p>
 * Like {@link com.hypixel.hytale.builtin.hytalegenerator.positionproviders.Jitter3dPositionProvider}, the child is run with
 * vertically expanded bounds so sources such as PCN can emit candidates that land inside the original bounds after the offset.
 * Expansion assumes density magnitudes stay within {@value #ASSUMED_MAX_ABS_DENSITY}; stronger fields may need a smaller multiplier.
 */
public final class DensityYOffsetPositionProvider extends PositionProvider {

    /** Upper bound on |density| used only for vertical bound expansion (typical normalized noise). */
    private static final double ASSUMED_MAX_ABS_DENSITY = 1.0;

    @Nonnull
    private final Density field;
    @Nonnull
    private final PositionProvider positionProvider;
    private final double multiplier;
    @Nonnull
    private PositionProvider.Context rContext;
    @Nonnull
    private final Bounds3d rChildBounds = new Bounds3d();
    @Nonnull
    private final PositionProvider.Context rChildContext;
    @Nonnull
    private final Density.Context rDensityContext;
    @Nonnull
    private final Pipe.One<Vector3d> rChildPipe = new Pipe.One<Vector3d>() {
        @Override
        public void accept(@NonNullDecl Vector3d position, @NonNullDecl Control control) {
            DensityYOffsetPositionProvider.this.rDensityContext.position = position;
            DensityYOffsetPositionProvider.this.rDensityContext.positionsAnchor = DensityYOffsetPositionProvider.this.rContext.anchor;
            DensityYOffsetPositionProvider.this.rDensityContext.densityAnchor = DensityYOffsetPositionProvider.this.rContext.anchor;
            double v = DensityYOffsetPositionProvider.this.field.process(DensityYOffsetPositionProvider.this.rDensityContext);
            position.y += DensityYOffsetPositionProvider.this.multiplier * v;
            if (DensityYOffsetPositionProvider.this.rContext.bounds.contains(position)) {
                DensityYOffsetPositionProvider.this.rContext.pipe.accept(position, control);
            }
        }
    };

    public DensityYOffsetPositionProvider(
            @Nonnull Density field,
            @Nonnull PositionProvider positionProvider,
            double multiplier
    ) {
        this.field = field;
        this.positionProvider = positionProvider;
        this.multiplier = multiplier;
        this.rChildContext = new PositionProvider.Context();
        this.rDensityContext = new Density.Context();
        this.rContext = new PositionProvider.Context();
    }

    @Override
    public void generate(@Nonnull PositionProvider.Context context) {
        this.rContext = context;
        double maxVerticalShift = Math.abs(this.multiplier) * ASSUMED_MAX_ABS_DENSITY;
        this.rChildBounds.assign(context.bounds);
        this.rChildBounds.min.y -= maxVerticalShift;
        this.rChildBounds.max.y += maxVerticalShift;
        this.rChildContext.assign(context);
        this.rChildContext.bounds = this.rChildBounds;
        this.rChildContext.pipe = this.rChildPipe;
        this.positionProvider.generate(this.rChildContext);
    }
}
