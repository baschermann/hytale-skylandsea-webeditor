package de.krah.worldgen.customnodes;

import com.hypixel.hytale.builtin.hytalegenerator.assets.density.DensityAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.density.positions.returntypes.ReturnTypeAsset;
import de.krah.worldgen.customnodes.density.FastDensityReturnTypeAsset;
import de.krah.worldgen.customnodes.density.FastSimplexNoise2dDensityAsset;
import de.krah.worldgen.customnodes.density.SolidLineDensityAsset;

/**
 * Registers plugin-provided custom density node codecs.
 * Keep all future custom density node registrations centralized here.
 *
 * <p>Some registrations here intentionally overwrite engine defaults (identified
 * by the same {@code "Type"} id). This is supported because the underlying
 * {@code idToCodec} map is a {@link java.util.concurrent.ConcurrentHashMap}
 * whose {@code put} unconditionally replaces existing entries, and plugin
 * {@link com.hypixel.hytale.server.core.plugin.JavaPlugin#setup() setup()} runs
 * after the engine's {@code AssetManager} boot so our values take precedence
 * for subsequent biome decode / build calls.</p>
 */
public final class CustomDensityNodeRegistry {
    private static boolean registered;

    private CustomDensityNodeRegistry() {
    }

    public static synchronized void registerAll() {
        if (registered) {
            return;
        }

        DensityAsset.CODEC.register("SolidLine", SolidLineDensityAsset.class, SolidLineDensityAsset.CODEC);

        // Performance overrides: swap the engine's auto-MultiCache(capacity=3) wraps for
        // CacheDensity(capacity=1). See Fast*Asset javadocs for the full rationale. These
        // replace the built-in registrations under the same "Type" id, so existing biome
        // JSON needs no changes.
        DensityAsset.CODEC.register(
                "SimplexNoise2D",
                FastSimplexNoise2dDensityAsset.class,
                FastSimplexNoise2dDensityAsset.CODEC);
        ReturnTypeAsset.CODEC.register(
                "Density",
                FastDensityReturnTypeAsset.class,
                FastDensityReturnTypeAsset.CODEC);

        registered = true;
    }
}
