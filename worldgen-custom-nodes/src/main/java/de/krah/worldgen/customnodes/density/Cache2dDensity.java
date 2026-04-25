package de.krah.worldgen.customnodes.density;

import com.hypixel.hytale.builtin.hytalegenerator.density.Density;
import javax.annotation.Nonnull;

/**
 * Three-slot round-robin cache keyed only on {@code (x, z)}.
 *
 * <p>Intended to wrap density subgraphs whose output is independent of
 * {@code y} — e.g. {@code Noise2dDensity}, which forwards to its
 * {@code NoiseField.valueAt(x, z)} and never reads {@code position.y}. For
 * such subgraphs this cache replaces the stock
 * {@code YOverrideDensity(MultiCacheDensity(...))} or
 * {@code YOverrideDensity(CacheDensity(...))} wraps.</p>
 *
 * <h4>Why 3 slots (not 2)</h4>
 * <p>{@link com.hypixel.hytale.builtin.hytalegenerator.density.nodes.GradientWarpDensity
 * GradientWarpDensity} evaluates its {@code warpInput} at four positions per
 * context — origin, {@code (x+δ, *, z)}, {@code (x, *, z)} (a Y-perturbation
 * that aliases to origin for 2D noise), and {@code (x, *, z+δ)} — yielding
 * <em>three</em> distinct {@code (x, z)} keys per gradient cluster.
 * {@code TerrainStage} iterates {@code y} as the innermost loop, so the exact
 * same 3-key cluster repeats unchanged for every {@code y} in a column.</p>
 *
 * <p>A 2-slot round-robin cache thrashes on this pattern: the third key
 * always evicts the oldest, so when {@code y} advances we re-miss on at
 * least two of the three keys. A 3-slot cache retains all three after the
 * first {@code y}, turning every subsequent {@code y} iteration into pure
 * hits — effectively ~3× fewer leaf noise samples for GradientWarp-rooted
 * subgraphs.</p>
 *
 * <p>Profile evidence (Skylandsea sample 6): {@code Cache2dDensity.process}
 * and {@code FastNoiseLite.GetNoise} both showed 2,776 stack hits, i.e.
 * essentially every lookup was a miss. After 3 slots the GradientWarp
 * cluster reuse kicks in and the cache actually starts earning its keep.</p>
 *
 * <h4>Why not more slots</h4>
 * <p>Four slots would only help if a single subgraph produced more than 3
 * distinct {@code (x, z)} keys per context. {@code VectorWarpDensity}
 * produces exactly 1, {@code GradientWarpDensity} produces 3; nested warps
 * are rare in practice. Beyond 3 the linear compare chain starts to matter
 * again for subgraphs that only ever use 1 key (e.g. noise directly feeding
 * a slider) without adding hit rate.</p>
 *
 * <h4>Lookup cost</h4>
 * <p>At most three double compares plus a branch on the fast path — still
 * strictly cheaper than {@code CacheDensity}'s three compares over a
 * {@code Vector3d} (which also reads {@code position.y} and allocates on
 * miss) and dramatically cheaper than {@code MultiCacheDensity}'s linear
 * scan with {@code Vector3d.equals}. No allocation on any path.</p>
 *
 * <h4>Correctness contract</h4>
 * <p>The wrapped {@code input} MUST produce identical results for any two
 * {@link Density.Context}s that share {@code position.x} and
 * {@code position.z}, regardless of {@code position.y}, anchors, or any
 * other context field. Wrapping anything that reads {@code y} or anchor
 * state will silently produce wrong results.</p>
 */
public final class Cache2dDensity extends Density {

    @Nonnull
    private Density input;

    private boolean valid0;
    private double x0;
    private double z0;
    private double v0;

    private boolean valid1;
    private double x1;
    private double z1;
    private double v1;

    private boolean valid2;
    private double x2;
    private double z2;
    private double v2;

    /** Which slot to overwrite on the next miss (round-robin 0 → 1 → 2 → 0). */
    private int nextSlot;

    public Cache2dDensity(@Nonnull Density input) {
        this.input = input;
    }

    @Override
    public double process(@Nonnull Density.Context context) {
        double x = context.position.x;
        double z = context.position.z;

        if (this.valid0 && this.x0 == x && this.z0 == z) {
            return this.v0;
        }
        if (this.valid1 && this.x1 == x && this.z1 == z) {
            return this.v1;
        }
        if (this.valid2 && this.x2 == x && this.z2 == z) {
            return this.v2;
        }

        double v = this.input.process(context);
        switch (this.nextSlot) {
            case 0:
                this.x0 = x;
                this.z0 = z;
                this.v0 = v;
                this.valid0 = true;
                this.nextSlot = 1;
                break;
            case 1:
                this.x1 = x;
                this.z1 = z;
                this.v1 = v;
                this.valid1 = true;
                this.nextSlot = 2;
                break;
            default:
                this.x2 = x;
                this.z2 = z;
                this.v2 = v;
                this.valid2 = true;
                this.nextSlot = 0;
                break;
        }
        return v;
    }

    @Override
    public void setInputs(@Nonnull Density[] inputs) {
        assert inputs.length != 0;
        assert inputs[0] != null;
        this.input = inputs[0];
    }
}
