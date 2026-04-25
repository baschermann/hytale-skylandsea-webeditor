package de.krah.worldgen.customnodes.density.noise;

import com.auburn.FastNoiseLite;
import com.hypixel.hytale.builtin.hytalegenerator.noise.NoiseField;

/**
 * {@link NoiseField} adapter that delegates 2D sampling to
 * {@link FastNoiseLite} configured for OpenSimplex2 FBm.
 *
 * <p>Used as a drop-in replacement for the engine's {@code SimplexNoiseField}
 * inside {@code de.krah.worldgen.customnodes.density.FastSimplexNoise2dDensityAsset}.
 * OpenSimplex2 is ~2× faster than the classical Gustavson simplex the engine
 * ships with, and produces fewer directional artifacts.</p>
 *
 * <h4>Parameter mapping (identical semantics to the engine's SimplexNoise2D JSON)</h4>
 * <ul>
 *   <li>{@code Scale}       → {@code SetFrequency(1 / scale)} — scale is a
 *       divisor on inputs, frequency is a multiplier, so the two are reciprocals.</li>
 *   <li>{@code Octaves}     → {@code SetFractalOctaves}.</li>
 *   <li>{@code Lacunarity}  → {@code SetFractalLacunarity} (per-octave frequency multiplier).</li>
 *   <li>{@code Persistence} → {@code SetFractalGain} (per-octave amplitude multiplier).</li>
 *   <li>{@code Seed}        → {@code SetSeed}.</li>
 * </ul>
 *
 * <p>The fBm normalizer {@code FastNoiseLite} computes internally
 * ({@code mFractalBounding = 1 / Σ gain^i}) matches the engine's
 * {@code SimplexNoiseField.normalizer} exactly — so value ranges are
 * comparable to the previous implementation.</p>
 *
 * <h4>NOT seed-compatible with the engine's SimplexNoise2D</h4>
 * <p>OpenSimplex2 uses a different gradient table, different hashing, and
 * different per-octave decorrelation than classical simplex. Using the same
 * JSON {@code Seed} produces different (but visually equivalent) terrain.
 * Fine for new worlds; do not use for worlds you need to regenerate identically.</p>
 *
 * <h4>2D only</h4>
 * <p>Only {@link #valueAt(double, double)} is implemented. This adapter is
 * installed only under the {@code "SimplexNoise2D"} asset type, so the other
 * overloads will never be invoked in practice — they throw to make accidental
 * misuse loud rather than silently returning zero.</p>
 */
public final class FastNoiseLiteNoiseField extends NoiseField {

    private final FastNoiseLite noise;

    public FastNoiseLiteNoiseField(int seed,
                                   double scale,
                                   int octaves,
                                   double lacunarity,
                                   double persistence) {
        this.noise = new FastNoiseLite(seed);
        this.noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.noise.SetFrequency((float) (1.0 / scale));
        this.noise.SetFractalType(FastNoiseLite.FractalType.FBm);
        this.noise.SetFractalLacunarity((float) lacunarity);
        this.noise.SetFractalGain((float) persistence);
        // Set octaves last: triggers CalculateFractalBounding() with final gain value.
        this.noise.SetFractalOctaves(octaves);
    }

    @Override
    public double valueAt(double x, double z) {
        return this.noise.GetNoise((float) x, (float) z);
    }

    @Override
    public double valueAt(double x) {
        throw new UnsupportedOperationException(
                "FastNoiseLiteNoiseField is 2D-only; 1D valueAt is not supported.");
    }

    @Override
    public double valueAt(double x, double y, double z) {
        throw new UnsupportedOperationException(
                "FastNoiseLiteNoiseField is 2D-only; 3D valueAt is not supported.");
    }

    @Override
    public double valueAt(double x, double y, double z, double w) {
        throw new UnsupportedOperationException(
                "FastNoiseLiteNoiseField is 2D-only; 4D valueAt is not supported.");
    }
}
