package de.krah.worldgen.customnodes.positionproviders;

import com.hypixel.hytale.builtin.hytalegenerator.assets.density.positions.distancefunctions.DistanceFunctionAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.density.positions.distancefunctions.EuclideanDistanceFunctionAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.positionproviders.ListPositionProviderAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.positionproviders.PositionProviderAsset;
import com.hypixel.hytale.builtin.hytalegenerator.positionproviders.EmptyPositionProvider;
import com.hypixel.hytale.builtin.hytalegenerator.positionproviders.PositionProvider;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.validation.Validators;
import javax.annotation.Nonnull;

public final class ClosestPositionProviderAsset extends PositionProviderAsset {

    @Nonnull
    public static final BuilderCodec<ClosestPositionProviderAsset> CODEC = BuilderCodec.builder(
                    ClosestPositionProviderAsset.class,
                    ClosestPositionProviderAsset::new,
                    PositionProviderAsset.ABSTRACT_CODEC)
            .append(new KeyedCodec<>("Positions", PositionProviderAsset.CODEC, true), (asset, v) -> asset.positionsAsset = v, asset -> asset.positionsAsset)
            .add()
            .append(
                    new KeyedCodec<>("DistanceFunction", DistanceFunctionAsset.CODEC, true),
                    (asset, v) -> asset.distanceFunctionAsset = v,
                    asset -> asset.distanceFunctionAsset)
            .add()
            .<Double>append(new KeyedCodec<>("MaxDistance", Codec.DOUBLE, true), (asset, v) -> asset.maxDistance = v, asset -> asset.maxDistance)
            .addValidator(Validators.greaterThanOrEqual(0.0))
            .add()
            .build();

    private PositionProviderAsset positionsAsset = new ListPositionProviderAsset();
    private DistanceFunctionAsset distanceFunctionAsset = new EuclideanDistanceFunctionAsset();
    private double maxDistance = 100.0;

    @Nonnull
    @Override
    public PositionProvider build(@Nonnull Argument argument) {
        if (super.skip()) {
            return EmptyPositionProvider.INSTANCE;
        }
        PositionProvider positions = this.positionsAsset.build(argument);
        return new ClosestPositionProvider(
                positions,
                this.distanceFunctionAsset.build(argument.parentSeed, this.maxDistance),
                this.maxDistance);
    }

    @Override
    public void cleanUp() {
        this.positionsAsset.cleanUp();
    }
}
