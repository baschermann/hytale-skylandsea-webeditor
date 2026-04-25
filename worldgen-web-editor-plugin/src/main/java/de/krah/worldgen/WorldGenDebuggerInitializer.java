package de.krah.worldgen;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.codec.AssetCodec;
import com.hypixel.hytale.assetstore.codec.AssetCodecMapCodec;
import com.hypixel.hytale.builtin.hytalegenerator.assets.AssetManager;
import com.hypixel.hytale.builtin.hytalegenerator.assets.density.DensityAsset;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.lookup.ACodecMapCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.checkerframework.checker.nullness.compatqual.NullableDecl;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class WorldGenDebuggerInitializer {

    /**
     * Injects the debugger into density asset loading only (DensityAsset.CODEC).
     * Do NOT wrap TerrainAsset or any other registry — that would corrupt world generation.
     */
    public static void injectDebugger() {
        try {
            System.out.println("[DEBUGGER] Initializing injection...");

            Class.forName(AssetManager.class.getName());

            // Only wrap DensityAsset so we can observe density nodes for the web debugger.
            // Do NOT wrap TerrainAsset or any other registry — that would affect actual world generation.
            wrapRegistry(DensityAsset.CODEC);

            // Proxy the exportedNodes map in DensityAsset to wrap imported nodes
            try {
                Field fExportedNodes = DensityAsset.class.getDeclaredField("exportedNodes");
                fExportedNodes.setAccessible(true);
                @SuppressWarnings("unchecked")
                final Map<String, Object> exportedMap = (Map<String, Object>) fExportedNodes.get(null);
                
                Map<String, Object> proxyExportedMap = new ConcurrentHashMap<String, Object>() {
                    @Override
                    public Object put(String key, Object value) {
                        wrapExported(value);
                        return super.put(key, value);
                    }
                    @Override
                    public void putAll(Map<? extends String, ?> m) {
                        for (Map.Entry<? extends String, ?> entry : m.entrySet()) {
                            put(entry.getKey(), entry.getValue());
                        }
                    }
                    private void wrapExported(Object exported) {
                        if (exported == null) return;
                        try {
                            Field fAsset = exported.getClass().getDeclaredField("asset");
                            fAsset.setAccessible(true);
                            DensityAsset asset = (DensityAsset) fAsset.get(exported);
                            if (asset != null && !(asset instanceof DebugDensityAsset)) {
                                fAsset.set(exported, new DebugDensityAsset(asset, "Imported:" + asset.getClass().getSimpleName().replace("DensityAsset", "")));
                            }
                        } catch (Exception ignored) {}
                    }
                };
                // Wrap existing entries
                for (Object value : exportedMap.values()) {
                    proxyExportedMap.put("", value); // Key doesn't matter for wrapExported
                }
                proxyExportedMap.putAll(exportedMap);
                fExportedNodes.set(null, proxyExportedMap);
                System.out.println("[DEBUGGER] Exported nodes map proxied.");
            } catch (Exception e) {
                System.err.println("[DEBUGGER] Failed to proxy exported nodes: " + e.getMessage());
            }

            System.out.println("[DEBUGGER] Injection complete.");

        } catch (Exception e) {
            System.err.println("[DEBUGGER] Injection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static <T extends com.hypixel.hytale.assetstore.JsonAsset<String>> void wrapRegistry(AssetCodecMapCodec<String, T> registry) throws Exception {
        // 1. Extract internal fields
        Field fIdCodec = AssetCodecMapCodec.class.getDeclaredField("idCodec");
        fIdCodec.setAccessible(true);
        Codec<String> idC = ((KeyedCodec<String>) fIdCodec.get(registry)).getChildCodec();

        Field fIdSetter = AssetCodecMapCodec.class.getDeclaredField("idSetter");
        fIdSetter.setAccessible(true);
        BiConsumer<T, String> idS = (BiConsumer<T, String>) fIdSetter.get(registry);

        Field fIdGetter = AssetCodecMapCodec.class.getDeclaredField("idGetter");
        fIdGetter.setAccessible(true);
        Function<T, String> idG = (Function<T, String>) fIdGetter.get(registry);

        Field fDataSetter = AssetCodecMapCodec.class.getDeclaredField("dataSetter");
        fDataSetter.setAccessible(true);
        BiConsumer<T, AssetExtraInfo.Data> dataS = (BiConsumer<T, AssetExtraInfo.Data>) fDataSetter.get(registry);

        Field fDataGetter = AssetCodecMapCodec.class.getDeclaredField("dataGetter");
        fDataGetter.setAccessible(true);
        Function<T, AssetExtraInfo.Data> dataG = (Function<T, AssetExtraInfo.Data>) fDataGetter.get(registry);

        // 2. Proxy the idToCodec map
        Field fIdToCodec = ACodecMapCodec.class.getDeclaredField("idToCodec");
        fIdToCodec.setAccessible(true);

        @SuppressWarnings("unchecked") 
        final Map<String, AssetBuilderCodec<String, T>> originalMap =
                (Map<String, AssetBuilderCodec<String, T>>) fIdToCodec.get(registry);

        Map<String, AssetBuilderCodec<String, T>> proxyMap = new ConcurrentHashMap<String, AssetBuilderCodec<String, T>>() {
            @Override
            public AssetBuilderCodec<String, T> put(String key, AssetBuilderCodec<String, T> value) {
                if (value != null && !(value instanceof CodecWrapper)) {
                    value = new CodecWrapper<>(value, key, idC, idS, idG, dataS, dataG);
                }
                return super.put(key, value);
            }

            @Override
            public void putAll(Map<? extends String, ? extends AssetBuilderCodec<String, T>> m) {
                for (Map.Entry<? extends String, ? extends AssetBuilderCodec<String, T>> entry : m.entrySet()) {
                    put(entry.getKey(), entry.getValue());
                }
            }
        };

        proxyMap.putAll(originalMap);
        fIdToCodec.set(registry, proxyMap);

        // 3. Clear and re-populate the prioritized codecs array
        Field fCodecs = ACodecMapCodec.class.getDeclaredField("codecs");
        fCodecs.setAccessible(true);
        @SuppressWarnings("rawtypes")
        AtomicReference codecsRef = (AtomicReference) fCodecs.get(registry);
        // Setting to an empty array of the correct type
        Object emptyArray = java.lang.reflect.Array.newInstance(Class.forName("com.hypixel.hytale.codec.lookup.ACodecMapCodec$CodecPriority"), 0);
        codecsRef.set(emptyArray);

        // Use reflection to call register for each entry to re-populate 'codecs' array
        Method mRegister = ACodecMapCodec.class.getDeclaredMethod("register", com.hypixel.hytale.codec.lookup.Priority.class, Object.class, Class.class, Codec.class);
        mRegister.setAccessible(true);
        
        for (Map.Entry<String, AssetBuilderCodec<String, T>> entry : proxyMap.entrySet()) {
            AssetBuilderCodec<String, T> wrapper = entry.getValue();
            mRegister.invoke(registry, com.hypixel.hytale.codec.lookup.Priority.NORMAL, entry.getKey(), wrapper.getInnerClass(), wrapper);
        }

        System.out.println("[DEBUGGER] Registry wrapped: " + registry.getClass().getSimpleName() + " types: " + proxyMap.size());
    }

    private static class CodecWrapper<T extends com.hypixel.hytale.assetstore.JsonAsset<String>> extends AssetBuilderCodec<String, T> implements AssetCodec<String, T> {
        private final AssetBuilderCodec<String, T> original;
        private final String typeName;

        public CodecWrapper(AssetBuilderCodec<String, T> original, String typeName,
                            Codec<String> idC, BiConsumer<T, String> idS,
                            Function<T, String> idG, BiConsumer<T, AssetExtraInfo.Data> dataS,
                            Function<T, AssetExtraInfo.Data> dataG) {
            super(AssetBuilderCodec.builder(original.getInnerClass(), original.getSupplier(), idC, idS, idG, dataS, dataG));
            this.original = original;
            this.typeName = typeName;
        }

        private String extractNodeId(RawJsonReader reader) {
            try {
                reader.mark();
                if (RawJsonReader.seekToKey(reader, "$NodeId")) {
                    String nid = reader.readString();
                    reader.reset();
                    return nid;
                }
                reader.reset();
            } catch (Exception ignored) {}
            return null;
        }

        private String extractNodeId(BsonValue value) {
            if (value instanceof BsonDocument) {
                BsonDocument doc = (BsonDocument) value;
                if (doc.containsKey("$NodeId")) {
                    return doc.get("$NodeId").asString().getValue();
                }
            }
            return null;
        }

        @Override
        public T decodeAndInheritJson(RawJsonReader reader, @NullableDecl T parent, ExtraInfo extraInfo) throws IOException {
            String nid = extractNodeId(reader);
            T asset = original.decodeAndInheritJson(reader, parent, extraInfo);
            return wrap(asset, nid);
        }

        @Override
        public T decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
            String nid = extractNodeId(reader);
            T asset = original.decodeJson(reader, extraInfo);
            return wrap(asset, nid);
        }

        @Override
        public T decode(BsonValue value, ExtraInfo extraInfo) {
            String nid = extractNodeId(value);
            T asset = original.decode(value, extraInfo);
            return wrap(asset, nid);
        }

        @Override
        public T decodeAndInherit(BsonDocument document, @NullableDecl T parent, ExtraInfo extraInfo) {
            String nid = extractNodeId(document);
            T asset = original.decodeAndInherit(document, parent, extraInfo);
            return wrap(asset, nid);
        }

        private T wrap(T asset, String nodeId) {
            if (asset == null) return null;
            if (asset instanceof DebugDensityAsset) {
                if (nodeId != null) ((DebugDensityAsset)asset).setHytaleNodeId(nodeId);
                return asset;
            }
            if (asset instanceof DensityAsset) {
                DebugDensityAsset wrapped = new DebugDensityAsset((DensityAsset) asset, typeName);
                if (nodeId != null) wrapped.setHytaleNodeId(nodeId);
                return (T) (Object) wrapped;
            }
            return asset;
        }

        @Override
        public void decodeAndInheritJson0(RawJsonReader reader, T t, T parent, ExtraInfo extraInfo) throws IOException {
            if (t instanceof DebugDensityAsset) {
                String nid = extractNodeId(reader);
                if (nid != null) ((DebugDensityAsset)t).setHytaleNodeId(nid);
                try {
                    Method m = com.hypixel.hytale.codec.builder.BuilderCodec.class.getDeclaredMethod("decodeAndInheritJson0", RawJsonReader.class, Object.class, Object.class, ExtraInfo.class);
                    m.setAccessible(true);
                    m.invoke(original, reader, ((DebugDensityAsset)t).getOriginal(), parent, extraInfo);
                } catch (Exception e) {
                    if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
                    throw new IOException(e);
                }
            } else {
                original.decodeAndInheritJson0(reader, t, parent, extraInfo);
            }
        }

        @Override
        public Supplier<T> getSupplier() { return original.getSupplier(); }
        @Override
        public KeyedCodec<String> getKeyCodec() { return original.getKeyCodec(); }
        @Override
        public KeyedCodec<String> getParentCodec() { return original.getParentCodec(); }
        @Override
        public AssetExtraInfo.Data getData(T t) {
            if (t instanceof DebugDensityAsset) {
                return original.getData((T) (Object) ((DebugDensityAsset)t).getOriginal());
            }
            return original.getData(t);
        }
    }
}
