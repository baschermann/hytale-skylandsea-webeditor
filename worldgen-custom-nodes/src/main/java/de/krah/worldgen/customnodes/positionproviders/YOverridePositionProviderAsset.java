package de.krah.worldgen.customnodes.positionproviders;

import com.hypixel.hytale.builtin.hytalegenerator.assets.positionproviders.ListPositionProviderAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.positionproviders.PositionProviderAsset;
import com.hypixel.hytale.builtin.hytalegenerator.positionproviders.EmptyPositionProvider;
import com.hypixel.hytale.builtin.hytalegenerator.positionproviders.PositionProvider;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import javax.annotation.Nonnull;

public final class YOverridePositionProviderAsset extends PositionProviderAsset {

    @Nonnull
    public static final BuilderCodec<YOverridePositionProviderAsset> CODEC = BuilderCodec.builder(
                    YOverridePositionProviderAsset.class,
                    YOverridePositionProviderAsset::new,
                    PositionProviderAsset.ABSTRACT_CODEC)
            .append(
                    new KeyedCodec<>("Positions", PositionProviderAsset.CODEC, true),
                    (asset, v) -> asset.positionsAsset = v,
                    asset -> asset.positionsAsset)
            .add()
            .append(new KeyedCodec<>("Value", Codec.DOUBLE, true), (asset, v) -> asset.value = v, asset -> asset.value)
            .add()
            .build();

    private PositionProviderAsset positionsAsset = new ListPositionProviderAsset();
    private double value;

    public YOverridePositionProviderAsset() {}

    @Nonnull
    @Override
    public PositionProvider build(@Nonnull Argument argument) {
        if (super.skip()) {
            return EmptyPositionProvider.INSTANCE;
        }
        PositionProvider child = this.positionsAsset.build(argument);
        return new YOverridePositionProvider(this.value, child);
    }

    @Override
    public void cleanUp() {
        this.positionsAsset.cleanUp();
    }
}
