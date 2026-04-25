package de.krah.worldgen.customnodes.positionproviders;

import com.hypixel.hytale.builtin.hytalegenerator.assets.density.ConstantDensityAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.density.DensityAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.positionproviders.ListPositionProviderAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.positionproviders.PositionProviderAsset;
import com.hypixel.hytale.builtin.hytalegenerator.density.Density;
import com.hypixel.hytale.builtin.hytalegenerator.positionproviders.EmptyPositionProvider;
import com.hypixel.hytale.builtin.hytalegenerator.positionproviders.PositionProvider;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import javax.annotation.Nonnull;

public final class DensityYOffsetPositionProviderAsset extends PositionProviderAsset {

    @Nonnull
    public static final BuilderCodec<DensityYOffsetPositionProviderAsset> CODEC = BuilderCodec.builder(
                    DensityYOffsetPositionProviderAsset.class,
                    DensityYOffsetPositionProviderAsset::new,
                    PositionProviderAsset.ABSTRACT_CODEC)
            .append(new KeyedCodec<>("FieldFunction", DensityAsset.CODEC, true), (asset, v) -> asset.densityAsset = v, asset -> asset.densityAsset)
            .add()
            .append(
                    new KeyedCodec<>("Positions", PositionProviderAsset.CODEC, true),
                    (asset, v) -> asset.positionProviderAsset = v,
                    asset -> asset.positionProviderAsset)
            .add()
            .append(new KeyedCodec<>("Multiplier", Codec.DOUBLE, true), (asset, v) -> asset.multiplier = v, asset -> asset.multiplier)
            .add()
            .build();

    private DensityAsset densityAsset = new ConstantDensityAsset();
    private PositionProviderAsset positionProviderAsset = new ListPositionProviderAsset();
    private double multiplier = 1.0;

    public DensityYOffsetPositionProviderAsset() {
    }

    @Nonnull
    @Override
    public PositionProvider build(@Nonnull PositionProviderAsset.Argument argument) {
        if (super.skip()) {
            return EmptyPositionProvider.INSTANCE;
        }
        Density functionTree = this.densityAsset.build(DensityAsset.from(argument));
        PositionProvider positionProvider = this.positionProviderAsset.build(argument);
        return new DensityYOffsetPositionProvider(functionTree, positionProvider, this.multiplier);
    }

    @Override
    public void cleanUp() {
        this.densityAsset.cleanUp();
        this.positionProviderAsset.cleanUp();
    }
}
