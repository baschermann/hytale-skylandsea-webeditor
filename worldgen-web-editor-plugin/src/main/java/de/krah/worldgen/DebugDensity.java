package de.krah.worldgen;

import com.hypixel.hytale.builtin.hytalegenerator.density.Density;
import com.hypixel.hytale.math.vector.Vector3d;

import javax.annotation.Nonnull;

public class DebugDensity extends Density {
    private final Density delegate;
    private final String nodeId;
    private final java.util.concurrent.atomic.DoubleAccumulator min =
            new java.util.concurrent.atomic.DoubleAccumulator(Double::min, Double.POSITIVE_INFINITY);
    private final java.util.concurrent.atomic.DoubleAccumulator max =
            new java.util.concurrent.atomic.DoubleAccumulator(Double::max, Double.NEGATIVE_INFINITY);

    public DebugDensity(Density delegate, String nodeId) {
        this.delegate = delegate;
        this.nodeId = nodeId;
        if (!DebugDensityAsset.TRACING_MODE.get()) {
            WorldGenDebugger.registerDebugDensity(this);
        }
    }

    @Override
    public double process(@Nonnull Context context) {
        Vector3d anchorAtEntry = context.densityAnchor;
        double result = delegate.process(context);
        if (DensityTraceCollector.isActive()) {
            DensityTraceCollector.record(nodeId, delegate.getClass().getSimpleName(), result, anchorAtEntry);
        }
        min.accumulate(result);
        max.accumulate(result);
        return result;
    }

    @Override
    public void setInputs(Density[] inputs) {
        delegate.setInputs(inputs);
    }

    public void reset() {
        min.reset();
        max.reset();
    }

    public double getMin() {
        return min.get();
    }

    public double getMax() {
        return max.get();
    }

    public String getNodeId() {
        return nodeId;
    }

    /** Same label as trace steps: inner {@link Density} class simple name. */
    public String getDelegateClassSimpleName() {
        return delegate.getClass().getSimpleName();
    }

    @Override
    public String toString() {
        return String.valueOf(getMin()) + " " + String.valueOf(getMax()) + " " + delegate.getClass().getSimpleName();
    }
}
