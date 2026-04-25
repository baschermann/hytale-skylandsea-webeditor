package de.krah.worldgen.customnodes.positionproviders;

import com.hypixel.hytale.builtin.hytalegenerator.assets.positionproviders.PositionProviderAsset;
import com.hypixel.hytale.builtin.hytalegenerator.positionproviders.EmptyPositionProvider;
import com.hypixel.hytale.builtin.hytalegenerator.positionproviders.PositionProvider;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import javax.annotation.Nonnull;

public class DeterministicRandomPositionsProviderAsset extends PositionProviderAsset {
    @Nonnull
    public static final BuilderCodec<DeterministicRandomPositionsProviderAsset> CODEC = BuilderCodec.builder(
                    DeterministicRandomPositionsProviderAsset.class,
                    DeterministicRandomPositionsProviderAsset::new,
                    PositionProviderAsset.ABSTRACT_CODEC)
            .append(new KeyedCodec<>("RepeatX", Codec.INTEGER, true), (t, v) -> t.repeatX = v, t -> t.repeatX)
            .add()
            .append(new KeyedCodec<>("RepeatZ", Codec.INTEGER, true), (t, v) -> t.repeatZ = v, t -> t.repeatZ)
            .add()
            .append(new KeyedCodec<>("BaseY", Codec.INTEGER, true), (t, v) -> t.baseY = v, t -> t.baseY)
            .add()
            .append(new KeyedCodec<>("OriginX", Codec.INTEGER, false), (t, v) -> t.originX = v, t -> t.originX)
            .add()
            .append(new KeyedCodec<>("OriginZ", Codec.INTEGER, false), (t, v) -> t.originZ = v, t -> t.originZ)
            .add()
            .append(new KeyedCodec<>("JitterX", Codec.DOUBLE, true), (t, v) -> t.jitterX = v, t -> t.jitterX)
            .add()
            .append(new KeyedCodec<>("JitterY", Codec.DOUBLE, true), (t, v) -> t.jitterY = v, t -> t.jitterY)
            .add()
            .append(new KeyedCodec<>("JitterZ", Codec.DOUBLE, true), (t, v) -> t.jitterZ = v, t -> t.jitterZ)
            .add()
            .append(new KeyedCodec<>("Seed", Codec.STRING, true), (t, v) -> t.seedKey = v, t -> t.seedKey)
            .add()
            .build();

    private int repeatX = 32;
    private int repeatZ = 32;
    private int baseY;
    private int originX;
    private int originZ;
    private double jitterX = 0.0;
    private double jitterY = 0.0;
    private double jitterZ = 0.0;
    private String seedKey = "A";

    public DeterministicRandomPositionsProviderAsset() {
    }

    @Nonnull
    @Override
    public PositionProvider build(@Nonnull PositionProviderAsset.Argument argument) {
        if (super.skip()) {
            return EmptyPositionProvider.INSTANCE;
        }
        int rx = Math.max(1, this.repeatX);
        int rz = Math.max(1, this.repeatZ);
        double jx = this.jitterX >= 0.0 ? this.jitterX : 0.0;
        double jy = this.jitterY >= 0.0 ? this.jitterY : 0.0;
        double jz = this.jitterZ >= 0.0 ? this.jitterZ : 0.0;
        int seed = argument.parentSeed.child(this.seedKey).createSupplier().get();
        return new DeterministicRandomPositionsProvider(
                this.originX, this.originZ, rx, rz, this.baseY, seed, jx, jy, jz);
    }
}
