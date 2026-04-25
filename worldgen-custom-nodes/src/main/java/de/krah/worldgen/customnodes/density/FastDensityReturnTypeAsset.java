package de.krah.worldgen.customnodes.density;

import com.hypixel.hytale.builtin.hytalegenerator.assets.density.ConstantDensityAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.density.DensityAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.density.positions.returntypes.DensityReturnTypeAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.density.positions.returntypes.ReturnTypeAsset;
import com.hypixel.hytale.builtin.hytalegenerator.density.Density;
import com.hypixel.hytale.builtin.hytalegenerator.density.nodes.CacheDensity;
import com.hypixel.hytale.builtin.hytalegenerator.density.nodes.positions.returntypes.DensityReturnType;
import com.hypixel.hytale.builtin.hytalegenerator.density.nodes.positions.returntypes.ReturnType;
import com.hypixel.hytale.builtin.hytalegenerator.referencebundle.ReferenceBundle;
import com.hypixel.hytale.builtin.hytalegenerator.rng.SeedBox;
import com.hypixel.hytale.builtin.hytalegenerator.workerindexer.WorkerIndexer;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.math.Range;
import java.lang.reflect.Field;
import java.util.HashMap;
import javax.annotation.Nonnull;

/**
 * Drop-in replacement for the built-in {@code "Density"} PCN return type.
 *
 * <p>The stock {@link DensityReturnTypeAsset} always wraps {@code ChoiceDensity} in
 * {@code MultiCacheDensity(capacity = 3)} before handing it to {@link DensityReturnType}.
 * That cache dominated {@code MultiCacheDensity$Cache.find()} hotspots whenever the
 * choice was trivial (e.g. {@code Constant 0}).</p>
 *
 * <p>This asset:</p>
 * <ul>
 *   <li>skips the cache entirely when {@code ChoiceDensity} is a {@link ConstantDensityAsset}
 *       (caching a field read is pure overhead);</li>
 *   <li>otherwise wraps with a single-slot {@link CacheDensity} (O(1) 3-double compare)
 *       instead of a linear-scan {@code MultiCacheDensity}.</li>
 * </ul>
 *
 * <p>The JSON schema ({@code ChoiceDensity} / {@code Delimiters} / {@code DefaultValue})
 * is identical to the built-in so existing biomes require no edits. Delimiter entries
 * are re-used via reflection against the engine's {@code DensityReturnTypeAsset.DelimiterAsset}
 * so the delimiter JSON decoding remains exactly as-is.</p>
 */
public class FastDensityReturnTypeAsset extends ReturnTypeAsset {

    @Nonnull
    public static final BuilderCodec<FastDensityReturnTypeAsset> CODEC = BuilderCodec.builder(
                    FastDensityReturnTypeAsset.class,
                    FastDensityReturnTypeAsset::new,
                    ReturnTypeAsset.ABSTRACT_CODEC)
            .append(new KeyedCodec<>("ChoiceDensity", DensityAsset.CODEC, true),
                    (t, k) -> t.choiceDensityAsset = k,
                    t -> t.choiceDensityAsset)
            .add()
            .append(new KeyedCodec<>("Delimiters",
                            new ArrayCodec<>(DensityReturnTypeAsset.DelimiterAsset.CODEC,
                                    DensityReturnTypeAsset.DelimiterAsset[]::new),
                            true),
                    (t, k) -> t.delimiterAssets = k,
                    t -> t.delimiterAssets)
            .add()
            .append(new KeyedCodec<>("DefaultValue", Codec.DOUBLE, false),
                    (t, k) -> t.defaultValue = k,
                    t -> t.defaultValue)
            .add()
            .build();

    private static final Field DELIMITER_FROM_FIELD;
    private static final Field DELIMITER_TO_FIELD;
    private static final Field DELIMITER_DENSITY_FIELD;

    static {
        try {
            DELIMITER_FROM_FIELD = DensityReturnTypeAsset.DelimiterAsset.class.getDeclaredField("from");
            DELIMITER_TO_FIELD = DensityReturnTypeAsset.DelimiterAsset.class.getDeclaredField("to");
            DELIMITER_DENSITY_FIELD = DensityReturnTypeAsset.DelimiterAsset.class.getDeclaredField("densityAsset");
            DELIMITER_FROM_FIELD.setAccessible(true);
            DELIMITER_TO_FIELD.setAccessible(true);
            DELIMITER_DENSITY_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(
                    "FastDensityReturnTypeAsset is incompatible with the current HytaleServer.jar: "
                            + "DensityReturnTypeAsset.DelimiterAsset shape changed. Original error: " + e);
        }
    }

    private DensityAsset choiceDensityAsset = new ConstantDensityAsset();
    private DensityReturnTypeAsset.DelimiterAsset[] delimiterAssets = new DensityReturnTypeAsset.DelimiterAsset[0];
    private double defaultValue = 0.0;

    public FastDensityReturnTypeAsset() {
    }

    @Nonnull
    @Override
    public ReturnType build(@Nonnull SeedBox parentSeed,
                            @Nonnull ReferenceBundle referenceBundle,
                            @Nonnull WorkerIndexer.Id workerId) {
        DensityAsset.Argument densityArgument = new DensityAsset.Argument(parentSeed, referenceBundle, workerId);
        Density choiceDensity = this.choiceDensityAsset.build(densityArgument);

        Density cachedChoice = (this.choiceDensityAsset instanceof ConstantDensityAsset)
                ? choiceDensity
                : new CacheDensity(choiceDensity);

        HashMap<Range, Density> delimiterMap = new HashMap<>(this.delimiterAssets.length);
        for (DensityReturnTypeAsset.DelimiterAsset delimiter : this.delimiterAssets) {
            double from;
            double to;
            DensityAsset densityAsset;
            try {
                from = DELIMITER_FROM_FIELD.getDouble(delimiter);
                to = DELIMITER_TO_FIELD.getDouble(delimiter);
                densityAsset = (DensityAsset) DELIMITER_DENSITY_FIELD.get(delimiter);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                        "Failed to read delimiter fields from DensityReturnTypeAsset.DelimiterAsset; "
                                + "HytaleServer.jar likely changed.", e);
            }
            delimiterMap.put(new Range((float) from, (float) to), densityAsset.build(densityArgument));
        }

        return new DensityReturnType(cachedChoice, delimiterMap, true, this.defaultValue);
    }
}
