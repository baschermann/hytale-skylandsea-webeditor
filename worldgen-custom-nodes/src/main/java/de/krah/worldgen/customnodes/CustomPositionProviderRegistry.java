package de.krah.worldgen.customnodes;

import com.hypixel.hytale.builtin.hytalegenerator.assets.positionproviders.PositionProviderAsset;
import de.krah.worldgen.customnodes.positionproviders.ClosestPositionProviderAsset;
import de.krah.worldgen.customnodes.positionproviders.DensityYOffsetPositionProviderAsset;
import de.krah.worldgen.customnodes.positionproviders.DeterministicRandomPositionsProviderAsset;
import de.krah.worldgen.customnodes.positionproviders.YOverridePositionProviderAsset;

/**
 * Registers plugin-provided custom position provider codecs.
 */
public final class CustomPositionProviderRegistry {
    private static boolean registered;

    private CustomPositionProviderRegistry() {
    }

    public static synchronized void registerAll() {
        if (registered) {
            return;
        }
        PositionProviderAsset.CODEC.register(
                "DeterministicRandomPositions",
                DeterministicRandomPositionsProviderAsset.class,
                DeterministicRandomPositionsProviderAsset.CODEC);
        PositionProviderAsset.CODEC.register(
                "RepeatingCellDiagonal",
                DeterministicRandomPositionsProviderAsset.class,
                DeterministicRandomPositionsProviderAsset.CODEC);
        PositionProviderAsset.CODEC.register(
                "DensityYOffset",
                DensityYOffsetPositionProviderAsset.class,
                DensityYOffsetPositionProviderAsset.CODEC);
        PositionProviderAsset.CODEC.register(
                "ClosestPosition",
                ClosestPositionProviderAsset.class,
                ClosestPositionProviderAsset.CODEC);
        PositionProviderAsset.CODEC.register(
                "YOverridePositions",
                YOverridePositionProviderAsset.class,
                YOverridePositionProviderAsset.CODEC);
        registered = true;
    }
}
