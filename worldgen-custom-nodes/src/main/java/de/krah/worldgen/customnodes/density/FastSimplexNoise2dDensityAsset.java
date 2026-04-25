package de.krah.worldgen.customnodes.density;

import com.hypixel.hytale.builtin.hytalegenerator.assets.density.DensityAsset;
import com.hypixel.hytale.builtin.hytalegenerator.density.Density;
import com.hypixel.hytale.builtin.hytalegenerator.density.nodes.ConstantValueDensity;
import com.hypixel.hytale.builtin.hytalegenerator.density.nodes.Noise2dDensity;
import com.hypixel.hytale.builtin.hytalegenerator.rng.SeedBox;
import de.krah.worldgen.customnodes.density.noise.FastNoiseLiteNoiseField;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.validation.Validators;
import javax.annotation.Nonnull;

/**
 * Drop-in replacement for the built-in {@code SimplexNoise2D} density asset.
 *
 * <p>Two independent wins over the stock asset:</p>
 *
 * <h4>1. Cache shape (see {@link Cache2dDensity})</h4>
 * <p>Stock: {@code YOverrideDensity(MultiCacheDensity(Noise2dDensity, capacity=3))}
 * — a 3-slot linear-scan 3D-keyed cache with an outer wrap that pins {@code y=0}.
 * This asset: {@code Cache2dDensity(Noise2dDensity)} — a 2-slot round-robin
 * cache keyed solely on {@code (x, z)}. Strictly fewer compares per lookup,
 * no {@code Vector3d} work, and correct because {@link Noise2dDensity} ignores
 * {@code y}.</p>
 *
 * <h4>2. Noise algorithm (see {@link FastNoiseLiteNoiseField})</h4>
 * <p>Stock: engine's classical Gustavson simplex in {@code SimplexNoiseField}.
 * This asset: {@code FastNoiseLite} configured for OpenSimplex2 FBm, roughly
 * 2× faster per sample with equivalent visual character but fewer directional
 * artifacts.</p>
 *
 * <h4>Compatibility</h4>
 * <p>JSON schema ({@code Lacunarity} / {@code Persistence} / {@code Scale} /
 * {@code Octaves} / {@code Seed}) is identical to the built-in, so existing
 * biomes require no edits. <strong>World output is NOT seed-identical</strong>
 * to the stock asset — the same JSON seed will produce different (visually
 * equivalent) terrain because OpenSimplex2 uses a different gradient table and
 * per-octave decorrelation than the engine's classical simplex. Fine for fresh
 * worlds; do not switch on a world you need to regenerate bit-identically.</p>
 */
public class FastSimplexNoise2dDensityAsset extends DensityAsset {

    @Nonnull
    public static final BuilderCodec<FastSimplexNoise2dDensityAsset> CODEC = BuilderCodec.builder(
                    FastSimplexNoise2dDensityAsset.class,
                    FastSimplexNoise2dDensityAsset::new,
                    DensityAsset.ABSTRACT_CODEC)
            .append(new KeyedCodec<>("Lacunarity", Codec.DOUBLE, true),
                    (asset, lacunarity) -> asset.lacunarity = lacunarity,
                    asset -> asset.lacunarity)
            .addValidator(Validators.greaterThan(0.0))
            .add()
            .<Double>append(new KeyedCodec<>("Persistence", Codec.DOUBLE, true),
                    (asset, persistence) -> asset.persistence = persistence,
                    asset -> asset.persistence)
            .addValidator(Validators.greaterThan(0.0))
            .add()
            .<Double>append(new KeyedCodec<>("Scale", Codec.DOUBLE, true),
                    (asset, scale) -> asset.scale = scale,
                    asset -> asset.scale)
            .addValidator(Validators.greaterThan(0.0))
            .add()
            .<Integer>append(new KeyedCodec<>("Octaves", Codec.INTEGER, true),
                    (asset, octaves) -> asset.octaves = octaves,
                    asset -> asset.octaves)
            .addValidator(Validators.greaterThan(0))
            .add()
            .append(new KeyedCodec<>("Seed", Codec.STRING, true),
                    (asset, seed) -> asset.seedKey = seed,
                    asset -> asset.seedKey)
            .add()
            .build();

    private double lacunarity = 1.0;
    private double persistence = 1.0;
    private double scale = 1.0;
    private int octaves = 1;
    private String seedKey = "A";

    public FastSimplexNoise2dDensityAsset() {
    }

    @Nonnull
    @Override
    public Density build(@Nonnull DensityAsset.Argument argument) {
        if (this.isSkipped()) {
            return new ConstantValueDensity(0.0);
        }
        SeedBox childSeed = argument.parentSeed.child(this.seedKey);
        int seed = childSeed.createSupplier().get().intValue();
        FastNoiseLiteNoiseField noise = new FastNoiseLiteNoiseField(
                seed, this.scale, this.octaves, this.lacunarity, this.persistence);
        Noise2dDensity noiseDensity = new Noise2dDensity(noise);
        return new Cache2dDensity(noiseDensity);
    }

    @Override
    public void cleanUp() {
        this.cleanUpInputs();
    }
}
