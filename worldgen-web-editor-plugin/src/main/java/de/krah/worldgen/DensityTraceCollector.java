package de.krah.worldgen;

import com.hypixel.hytale.math.vector.Vector3d;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-local capture of per-node density outputs during a single {@link com.hypixel.hytale.builtin.hytalegenerator.density.Density#process} evaluation.
 * Order follows call-stack / evaluation order (typically leaves before combinators).
 */
public final class DensityTraceCollector {

    public static final class TraceStep {
        public final int index;
        public final String nodeId;
        public final String nodeType;
        public final double value;
        @Nullable
        public final Double anchorSetX;
        @Nullable
        public final Double anchorSetY;
        @Nullable
        public final Double anchorSetZ;

        public TraceStep(int index, String nodeId, String nodeType, double value) {
            this(index, nodeId, nodeType, value, null, null, null);
        }

        public TraceStep(
                int index,
                String nodeId,
                String nodeType,
                double value,
                @Nullable Double anchorSetX,
                @Nullable Double anchorSetY,
                @Nullable Double anchorSetZ) {
            this.index = index;
            this.nodeId = nodeId != null ? nodeId : "";
            this.nodeType = nodeType != null ? nodeType : "";
            this.value = value;
            this.anchorSetX = anchorSetX;
            this.anchorSetY = anchorSetY;
            this.anchorSetZ = anchorSetZ;
        }
    }

    private static final ThreadLocal<List<TraceStep>> ACTIVE = new ThreadLocal<>();
    /** Previous step's {@link com.hypixel.hytale.builtin.hytalegenerator.density.Density.Context#densityAnchor} (world), for transition detection. */
    private static final ThreadLocal<double[]> LAST_ANCHOR_AT_TRACE_ENTRY = new ThreadLocal<>();

    private DensityTraceCollector() {}

    public static void begin() {
        LAST_ANCHOR_AT_TRACE_ENTRY.remove();
        ACTIVE.set(new ArrayList<>());
    }

    public static boolean isActive() {
        return ACTIVE.get() != null;
    }

    /**
     * Records one step. {@code anchorAtEntry} is {@link com.hypixel.hytale.builtin.hytalegenerator.density.Density.Context#densityAnchor}
     * at the start of that node's {@code process} (before delegate runs). A second-line anchor annotation is stored when that anchor
     * is non-null and differs from the previous traced step's entry anchor (plugin-only; no engine hooks).
     */
    public static void record(String nodeId, String nodeType, double value, @Nullable Vector3d anchorAtEntry) {
        List<TraceStep> list = ACTIVE.get();
        if (list == null) {
            return;
        }

        Double ax = null;
        Double ay = null;
        Double az = null;
        double[] last = LAST_ANCHOR_AT_TRACE_ENTRY.get();
        if (anchorAtEntry != null) {
            double x = anchorAtEntry.x;
            double y = anchorAtEntry.y;
            double z = anchorAtEntry.z;
            boolean sameAsLast = last != null && last[0] == x && last[1] == y && last[2] == z;
            if (!sameAsLast) {
                ax = x;
                ay = y;
                az = z;
                LAST_ANCHOR_AT_TRACE_ENTRY.set(new double[] {x, y, z});
            }
        } else if (last != null) {
            LAST_ANCHOR_AT_TRACE_ENTRY.remove();
        }

        if (ax != null) {
            list.add(new TraceStep(list.size(), nodeId, nodeType, value, ax, ay, az));
        } else {
            list.add(new TraceStep(list.size(), nodeId, nodeType, value));
        }
    }

    public static List<TraceStep> finish() {
        List<TraceStep> list = ACTIVE.get();
        ACTIVE.remove();
        LAST_ANCHOR_AT_TRACE_ENTRY.remove();
        return list != null ? list : Collections.emptyList();
    }
}
