package de.krah.worldgen.customnodes.density;

import com.hypixel.hytale.builtin.hytalegenerator.assets.density.DensityAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.positionproviders.CachedPositionProviderAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.positionproviders.ImportedPositionProviderAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.positionproviders.ListPositionProviderAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.positionproviders.PositionProviderAsset;
import com.hypixel.hytale.builtin.hytalegenerator.density.Density;
import com.hypixel.hytale.builtin.hytalegenerator.positionproviders.PositionProvider;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.lang.reflect.Field;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SolidLineDensityAsset extends DensityAsset {
    @Nonnull
    public static final BuilderCodec<SolidLineDensityAsset> CODEC = BuilderCodec.builder(
                    SolidLineDensityAsset.class, SolidLineDensityAsset::new, DensityAsset.ABSTRACT_CODEC)
            .append(
                    new KeyedCodec<>("Positions", PositionProviderAsset.CODEC, true),
                    (t, k) -> t.positionProviderAsset = k,
                    t -> t.positionProviderAsset)
            .add()
            .append(new KeyedCodec<>("Width", Codec.INTEGER, true), (t, k) -> t.width = k, t -> t.width)
            .add()
            .append(new KeyedCodec<>("Height", Codec.INTEGER, true), (t, k) -> t.height = k, t -> t.height)
            .add()
            .append(new KeyedCodec<>("MaxDistance", Codec.DOUBLE, false), (t, k) -> t.maxDistance = k, t -> t.maxDistance)
            .add()
            .append(new KeyedCodec<>("InnerRadius", Codec.DOUBLE, true), (t, k) -> t.innerRadius = k, t -> t.innerRadius)
            .add()
            .build();

    private PositionProviderAsset positionProviderAsset = new ListPositionProviderAsset();
    private int width = 3;
    private int height = 3;
    private double maxDistance = 128.0;
    private double innerRadius = 0.0;

    @Nonnull
    @Override
    public Density build(@Nonnull DensityAsset.Argument argument) {
        if (this.isSkipped()) {
            return new SolidLineDensity(new int[0][0], 1, 1, 0.0, 0.0, true);
        }
        int w = Math.max(1, this.width);
        int h = Math.max(1, this.height);
        double md = this.maxDistance >= 0.0 ? this.maxDistance : 128.0;
        double ir = Math.max(0.0, this.innerRadius);

        int[][] staticPoints = tryExtractListPolyline(this.positionProviderAsset);
        if (staticPoints != null && staticPoints.length >= 2) {
            return new SolidLineDensity(staticPoints, w, h, md, ir, false);
        }

        PositionProvider provider = this.positionProviderAsset.build(
                new PositionProviderAsset.Argument(argument.parentSeed, argument.referenceBundle, argument.workerId));
        return new SolidLineDensity(provider, md, w, h, ir, false);
    }

    /**
     * When the positions graph is only {@code List} and {@code Imported} wrappers, returns integer voxel points.
     * Otherwise {@code null} (runtime position query path).
     */
    @Nullable
    private static int[][] tryExtractListPolyline(@Nullable PositionProviderAsset asset) {
        if (asset == null) {
            return null;
        }
        if (asset.skip()) {
            return new int[0][0];
        }
        try {
            if (asset instanceof ImportedPositionProviderAsset) {
                Field nameField = ImportedPositionProviderAsset.class.getDeclaredField("name");
                nameField.setAccessible(true);
                String name = (String) nameField.get(asset);
                if (name == null || name.isEmpty()) {
                    return null;
                }
                PositionProviderAsset resolved = PositionProviderAsset.getExportedAsset(name);
                return tryExtractListPolyline(resolved);
            }
            if (asset instanceof CachedPositionProviderAsset) {
                Field childField = CachedPositionProviderAsset.class.getDeclaredField("childAsset");
                childField.setAccessible(true);
                return tryExtractListPolyline((PositionProviderAsset) childField.get(asset));
            }
            if (asset instanceof ListPositionProviderAsset listAsset) {
                Field positionsField = ListPositionProviderAsset.class.getDeclaredField("positions");
                positionsField.setAccessible(true);
                Object[] arr = (Object[]) positionsField.get(listAsset);
                if (arr == null || arr.length < 2) {
                    return null;
                }
                int[][] out = new int[arr.length][3];
                for (int i = 0; i < arr.length; i++) {
                    Object pa = arr[i];
                    Field xf = pa.getClass().getDeclaredField("x");
                    Field yf = pa.getClass().getDeclaredField("y");
                    Field zf = pa.getClass().getDeclaredField("z");
                    xf.setAccessible(true);
                    yf.setAccessible(true);
                    zf.setAccessible(true);
                    out[i][0] = ((Number) xf.get(pa)).intValue();
                    out[i][1] = ((Number) yf.get(pa)).intValue();
                    out[i][2] = ((Number) zf.get(pa)).intValue();
                }
                return out;
            }
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
        return null;
    }
}
