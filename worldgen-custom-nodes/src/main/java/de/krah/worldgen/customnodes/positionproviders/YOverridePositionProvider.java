package de.krah.worldgen.customnodes.positionproviders;

import com.hypixel.hytale.builtin.hytalegenerator.bounds.Bounds3d;
import com.hypixel.hytale.builtin.hytalegenerator.pipe.Control;
import com.hypixel.hytale.builtin.hytalegenerator.pipe.Pipe;
import com.hypixel.hytale.builtin.hytalegenerator.positionproviders.PositionProvider;
import com.hypixel.hytale.math.vector.Vector3d;
import javax.annotation.Nonnull;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

/**
 * Runs a child positions provider with adjusted vertical bounds, then sets every emitted
 * position's {@code y} to a fixed value before forwarding (similar to {@code YOverride.Density}
 * fixing the sample height for PCN / island alignment).
 */
public final class YOverridePositionProvider extends PositionProvider {

    private final double fixedY;
    @Nonnull
    private final PositionProvider child;
    @Nonnull
    private final Bounds3d childBounds = new Bounds3d();
    @Nonnull
    private final PositionProvider.Context childContext = new PositionProvider.Context();
    @Nonnull
    private PositionProvider.Context outerContext = new PositionProvider.Context();
    @Nonnull
    private final Pipe.One<Vector3d> childPipe = new Pipe.One<>() {
        @Override
        public void accept(@NonNullDecl Vector3d position, @NonNullDecl Control control) {
            position.y = YOverridePositionProvider.this.fixedY;
            if (YOverridePositionProvider.this.outerContext.bounds.contains(position)) {
                YOverridePositionProvider.this.outerContext.pipe.accept(position, control);
            }
        }
    };

    public YOverridePositionProvider(double fixedY, @Nonnull PositionProvider child) {
        this.fixedY = fixedY;
        this.child = child;
    }

    @Override
    public void generate(@Nonnull Context context) {
        this.outerContext = context;
        this.childBounds.assign(context.bounds);
        // Stock SquareGrid2d only runs when min.y <= 0 and max.y > 0; widen if needed so wrapped grids still emit.
        if (this.childBounds.min.y > 0.0 || this.childBounds.max.y <= 0.0) {
            this.childBounds.min.y = Math.min(this.childBounds.min.y, 0.0);
            this.childBounds.max.y = Math.max(this.childBounds.max.y, 1.0);
        }
        this.childContext.assign(context);
        this.childContext.bounds = this.childBounds;
        this.childContext.pipe = this.childPipe;
        this.child.generate(this.childContext);
    }
}
