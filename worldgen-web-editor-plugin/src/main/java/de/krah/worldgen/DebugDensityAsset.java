package de.krah.worldgen;

import com.hypixel.hytale.builtin.hytalegenerator.assets.density.DensityAsset;
import com.hypixel.hytale.builtin.hytalegenerator.density.Density;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class DebugDensityAsset extends DensityAsset {
    /**
     * When true, {@link #wrapNode} skips creating DebugDensity wrappers and
     * registering debug sources.  Set by {@link WorldGenDebugger#sampleBinarySolid}
     * so parallel sampling slices don't spam registrations or pollute live tracking.
     */
    static final ThreadLocal<Boolean> SAMPLING_MODE = ThreadLocal.withInitial(() -> false);

    /**
     * When true, density is built with {@link DebugDensity} wrappers for a one-shot trace, but
     * without registering globals ({@link WorldGenDebugger#registerDebugDensity} / {@link WorldGenDebugger#registerDebugNodeSource}).
     */
    static final ThreadLocal<Boolean> TRACING_MODE = ThreadLocal.withInitial(() -> false);

    private final DensityAsset original;
    private final String typeName;
    private String hytaleNodeId;

    public DebugDensityAsset(DensityAsset original, String typeName) {
        this.original = original;
        this.typeName = typeName;
    }

    public void setHytaleNodeId(String hytaleNodeId) {
        this.hytaleNodeId = hytaleNodeId;
    }

    public DensityAsset getOriginal() {
        return original;
    }

    private Density wrapNode(Density node, Argument argument) {
        if (node == null) return null;
        if (node instanceof DebugDensity) return node;
        if (SAMPLING_MODE.get()) return node;

        String id = this.getId();
        String nodeId = id != null ? id : typeName;
        if (!TRACING_MODE.get()) {
            WorldGenDebugger.registerDebugNodeSource(nodeId, this.original, argument);
        }
        return new DebugDensity(node, nodeId);
    }

    @Override
    @Nonnull
    public Density build(@Nonnull Argument argument) {
        return wrapNode(original.build(argument), argument);
    }

    @Override
    public Density buildWithInputs(Argument argument, Density[] inputs) {
        return wrapNode(original.buildWithInputs(argument, inputs), argument);
    }

    @Override
    public Density buildFirstInput(Argument argument) {
        return wrapNode(original.buildFirstInput(argument), argument);
    }

    @Override
    public Density buildSecondInput(Argument argument) {
        return wrapNode(original.buildSecondInput(argument), argument);
    }

    @Override
    public List<Density> buildInputs(Argument argument, boolean var2) {
        List<Density> nodes = original.buildInputs(argument, var2);
        if (nodes == null) return null;
        List<Density> wrapped = new ArrayList<>(nodes.size());
        for (Density node : nodes) {
            wrapped.add(wrapNode(node, argument));
        }
        return wrapped;
    }

    @Override
    public Density[] buildInputsArray(Argument argument) {
        Density[] nodes = original.buildInputsArray(argument);
        if (nodes == null) return null;
        Density[] wrapped = new Density[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            wrapped[i] = wrapNode(nodes[i], argument);
        }
        return wrapped;
    }

    @Override
    public DensityAsset[] inputs() {
        return original.inputs();
    }

    @Override
    public String getId() {
        if (hytaleNodeId != null && !hytaleNodeId.isEmpty()) {
            return hytaleNodeId;
        }
        return original.getId();
    }

    @Override
    public boolean isSkipped() {
        return original.isSkipped();
    }

    @Override
    public void cleanUp() {
        original.cleanUp();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        Object target = (obj instanceof DebugDensityAsset) ? ((DebugDensityAsset)obj).original : obj;
        return original.equals(target);
    }

    @Override
    public int hashCode() {
        return original.hashCode();
    }
}
