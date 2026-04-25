package de.krah.worldgen;

import com.hypixel.hytale.builtin.hytalegenerator.density.Density;
import com.hypixel.hytale.builtin.hytalegenerator.assets.density.DensityAsset;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.builtin.hytalegenerator.workerindexer.WorkerIndexer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;

import javax.annotation.Nonnull;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Debugger for the node web editor: observes density nodes and exposes metrics/samples via the backend.
 * Only wraps DensityAsset; TerrainAsset and other registries are left untouched.
 * Sends one update to the website only after chunk generation has settled (debounced).
 */
public class WorldGenDebugger {

    private final JavaPlugin plugin;
    private static final java.util.Map<String, DebugDensity> debugDensities = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, DebugNodeSource> debugNodeSources = new java.util.concurrent.ConcurrentHashMap<>();

    /** Shared pool for parallel density sampling across all requests. */
    private static final ForkJoinPool SAMPLING_POOL = new ForkJoinPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()));

    /** Reflective constructor for creating unique WorkerIndexer.Id instances per sampling slice. */
    private static final Constructor<WorkerIndexer.Id> WORKER_ID_CTOR;
    static {
        Constructor<WorkerIndexer.Id> ctor = null;
        try {
            ctor = WorkerIndexer.Id.class.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
        } catch (Exception e) {
            System.err.println("[DensityField] Cannot reflect WorkerIndexer.Id constructor: " + e.getMessage());
        }
        WORKER_ID_CTOR = ctor;
    }

    /** Debounce: send update only after this many ms without a new chunk event. */
    private static final long CHUNK_UPDATE_DEBOUNCE_MS = 400;

    private static final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "WorldGenDebugger-debounce");
        t.setDaemon(true);
        return t;
    });

    private static volatile ScheduledFuture<?> pendingSend;

    /**
     * Monotonic timestamps used by {@link de.krah.nodeeditorweb.ViewportRefreshService} to decide whether
     * its post-refresh fallback SSE tick is needed (skip when the chunk-event debounce already covered it).
     */
    private static volatile long lastChunkEventAtMs;
    private static volatile long lastMetricsSentAtMs;

    public static long getLastChunkEventAtMs() {
        return lastChunkEventAtMs;
    }

    public static long getLastMetricsSentAtMs() {
        return lastMetricsSentAtMs;
    }

    private static final class DebugNodeSource {
        final DensityAsset asset;
        final DensityAsset.Argument argumentTemplate;

        DebugNodeSource(DensityAsset asset, DensityAsset.Argument argumentTemplate) {
            this.asset = asset;
            this.argumentTemplate = argumentTemplate;
        }
    }

    public WorldGenDebugger(final JavaPlugin plugin) {
        this.plugin = plugin;
        WorldGenDebuggerInitializer.injectDebugger();
        registerEvents();
    }

    private void registerEvents() {
        plugin.getEventRegistry().registerGlobal(EventPriority.LAST, ChunkPreLoadProcessEvent.class, WorldGenDebugger::updateGenerationMetrics);
    }

    private static void updateGenerationMetrics(@Nonnull ChunkPreLoadProcessEvent event) {
        if (!event.isNewlyGenerated() || debugDensities.isEmpty()) return;
        lastChunkEventAtMs = System.currentTimeMillis();
        ScheduledFuture<?> prev = pendingSend;
        if (prev != null) prev.cancel(false);
        pendingSend = debounceExecutor.schedule(WorldGenDebugger::sendUpdateAfterChunksSettled, CHUNK_UPDATE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /** Called once after chunk generation has settled (no new chunk event for DEBOUNCE_MS). */
    private static void sendUpdateAfterChunksSettled() {
        pendingSend = null;
        if (debugDensities.isEmpty()) return;
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (java.util.Map.Entry<String, DebugDensity> entry : debugDensities.entrySet()) {
            DebugDensity dd = entry.getValue();
            double min = dd.getMin();
            double max = dd.getMax();
            if (!Double.isFinite(min) || !Double.isFinite(max)) continue;
            if (!first) json.append(",");
            json.append(String.format(java.util.Locale.US, "{\"id\":\"%s\",\"min\":%f,\"max\":%f}",
                entry.getKey().replace("\"", "\\\""), min, max));
            first = false;
        }
        json.append("]");
        if (!first) {
            de.krah.nodeeditorweb.DensityUpdateService.notifyChunkGenerationComplete(json.toString());
            lastMetricsSentAtMs = System.currentTimeMillis();
        }
        // Clear min/max so the next generation wave does not mix with this batch (ChunkPreLoadProcessEvent runs after worldgen).
        resetAllMetrics();
    }

    public static void registerDebugDensity(final DebugDensity debugDensity) {
        DebugDensity prev = debugDensities.put(debugDensity.getNodeId(), debugDensity);
        if (prev == null) {
            System.out.println("[DEBUGGER] Registering debug density: " + debugDensity.getNodeId());
        }
    }

    public static void registerDebugNodeSource(String nodeId, DensityAsset asset, DensityAsset.Argument argument) {
        if (nodeId == null || nodeId.isBlank() || asset == null || argument == null) return;
        DensityAsset.Argument copy = new DensityAsset.Argument(argument);
        // Use dedicated worker id so exported single-instance nodes do not collide with live worldgen instances.
        copy.workerId = WorkerIndexer.Id.UNKNOWN;
        debugNodeSources.put(nodeId, new DebugNodeSource(asset, copy));
    }

    public static void resetAllMetrics() {
        debugDensities.values().forEach(DebugDensity::reset);
    }

    /**
     * Returns the registered {@link DebugDensity} for the given node id, or {@code null} if none is registered.
     */
    public static DebugDensity getDebugDensity(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        return debugDensities.get(nodeId);
    }

    /**
     * Returns current metrics for all registered debug densities as JSON array string,
     * or null if none. Used to send initial state to newly connected SSE clients.
     */
    public static String getCurrentMetricsJson() {
        if (debugDensities.isEmpty()) return null;
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (java.util.Map.Entry<String, DebugDensity> entry : debugDensities.entrySet()) {
            DebugDensity dd = entry.getValue();
            double min = dd.getMin();
            double max = dd.getMax();
            if (!Double.isFinite(min) || !Double.isFinite(max)) continue;
            if (!first) json.append(",");
            json.append(String.format(java.util.Locale.US, "{\"id\":\"%s\",\"min\":%f,\"max\":%f}",
                entry.getKey().replace("\"", "\\\""), min, max));
            first = false;
        }
        if (first) return null; // no finite metrics
        json.append("]");
        return json.toString();
    }

    /**
     * JSON snapshot for REST/MCP clients: {@link de.krah.nodeeditorweb.DensityUpdateService} debug data version,
     * all registered density node ids, and current finite min/max metrics (same array shape as SSE updates).
     */
    public static String getDebugSnapshotJson() {
        long version = de.krah.nodeeditorweb.DensityUpdateService.getDebugDataVersion();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\":").append(version);
        sb.append(",\"registeredNodeIds\":[");
        boolean firstId = true;
        for (String id : debugDensities.keySet()) {
            if (!firstId) {
                sb.append(',');
            }
            firstId = false;
            sb.append(jsonEscapeString(id));
        }
        sb.append("],\"nodeMetrics\":");
        String m = getCurrentMetricsJson();
        if (m == null) {
            sb.append("[]");
        } else {
            sb.append(m);
        }
        sb.append('}');
        return sb.toString();
    }

    private static WorkerIndexer.Id createSamplingWorkerId(int sliceIndex) {
        if (WORKER_ID_CTOR != null) {
            try {
                return WORKER_ID_CTOR.newInstance(-(sliceIndex + 2));
            } catch (Exception ignored) {}
        }
        return WorkerIndexer.Id.UNKNOWN;
    }

    /**
     * Samples solid/air (1/0) for the mesh path, parallelized across Z-slices.
     * Each slice builds its own isolated Density instance with a unique worker ID
     * so single-instance exported nodes are not shared across threads.
     * Debug wrapping is suppressed during sampling to avoid registration spam.
     * @param threshold density values strictly greater than this are considered solid
     * @return byte[] of length width*height*depth (1 = solid, 0 = air), or {@code null} if the node is unknown
     */
    public static byte[] sampleBinarySolid(String nodeId, int width, int height, int depth, int startX, int startY, int startZ, double threshold) {
        DebugNodeSource source = debugNodeSources.get(nodeId);
        if (source == null) {
            System.err.println("[DensityField] No isolated debug source registered for node '" + nodeId + "'.");
            return null;
        }

        byte[] solid = new byte[width * height * depth];
        int sliceCount = Math.min(depth, SAMPLING_POOL.getParallelism());
        int sliceSize = (depth + sliceCount - 1) / sliceCount;

        CompletableFuture<?>[] futures = new CompletableFuture<?>[sliceCount];
        for (int s = 0; s < sliceCount; s++) {
            final int zStart = s * sliceSize;
            final int zEnd = Math.min(zStart + sliceSize, depth);
            final int sliceIdx = s;
            if (zStart >= depth) {
                futures[s] = CompletableFuture.completedFuture(null);
                continue;
            }
            futures[s] = CompletableFuture.runAsync(() -> {
                DebugDensityAsset.SAMPLING_MODE.set(true);
                try {
                    Density density;
                    try {
                        DensityAsset.Argument arg = new DensityAsset.Argument(source.argumentTemplate);
                        arg.workerId = createSamplingWorkerId(sliceIdx);
                        density = source.asset.build(arg);
                    } catch (Exception e) {
                        throw new CompletionException("Failed to build density for sampling slice " + sliceIdx, e);
                    }
                    Density.Context ctx = new Density.Context();
                    Vector3d pos = ctx.position;
                    for (int z = zStart; z < zEnd; z++) {
                        int planeOffset = z * height * width;
                        for (int y = 0; y < height; y++) {
                            int rowOffset = planeOffset + y * width;
                            for (int x = 0; x < width; x++) {
                                pos.assign(startX + x, startY + y, startZ + z);
                                double v = density.process(ctx);
                                solid[rowOffset + x] = (byte) (v > threshold && Float.isFinite((float) v) ? 1 : 0);
                            }
                        }
                    }
                } finally {
                    DebugDensityAsset.SAMPLING_MODE.set(false);
                }
            }, SAMPLING_POOL);
        }

        try {
            CompletableFuture.allOf(futures).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            StringWriter sw = new StringWriter();
            cause.printStackTrace(new PrintWriter(sw));
            System.err.println("[DensityField] Parallel sampling failed for '" + nodeId + "':\n" + sw);
            return null;
        }
        return solid;
    }

    private static String jsonEscapeString(@Nonnull String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    b.append("\\\\");
                    break;
                case '"':
                    b.append("\\\"");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    b.append(c);
            }
        }
        b.append('"');
        return b.toString();
    }

    private static String jsonNumber(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "null";
        }
        return String.format(Locale.US, "%.17g", v);
    }

    /**
     * Evaluates density at one world position and returns JSON describing each wrapped node's {@code process} result
     * (evaluation order — typically inputs before combinators). Returns {@code null} if the node id is unknown.
     */
    public static String traceDensityAtJson(String nodeId, double x, double y, double z) {
        DebugNodeSource source = debugNodeSources.get(nodeId);
        if (source == null) {
            return null;
        }
        DebugDensityAsset.TRACING_MODE.set(true);
        DensityTraceCollector.begin();
        try {
            DensityAsset.Argument arg = new DensityAsset.Argument(source.argumentTemplate);
            arg.workerId = WorkerIndexer.Id.UNKNOWN;
            Density density = source.asset.build(arg);
            Density.Context ctx = new Density.Context();
            ctx.position.assign(x, y, z);
            double finalValue = density.process(ctx);
            List<DensityTraceCollector.TraceStep> steps = DensityTraceCollector.finish();

            String outputNodeType = density instanceof DebugDensity
                    ? ((DebugDensity) density).getDelegateClassSimpleName()
                    : density.getClass().getSimpleName();

            StringBuilder sb = new StringBuilder();
            sb.append("{\"x\":").append(jsonNumber(x))
                    .append(",\"y\":").append(jsonNumber(y))
                    .append(",\"z\":").append(jsonNumber(z))
                    .append(",\"finalValue\":").append(jsonNumber(finalValue))
                    .append(",\"outputNodeType\":").append(jsonEscapeString(outputNodeType))
                    .append(",\"steps\":[");
            for (int i = 0; i < steps.size(); i++) {
                DensityTraceCollector.TraceStep s = steps.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"index\":").append(s.index)
                        .append(",\"nodeId\":").append(jsonEscapeString(s.nodeId))
                        .append(",\"nodeType\":").append(jsonEscapeString(s.nodeType))
                        .append(",\"value\":").append(jsonNumber(s.value));
                if (s.anchorSetX != null && s.anchorSetY != null && s.anchorSetZ != null) {
                    sb.append(",\"anchorSet\":{\"x\":").append(jsonNumber(s.anchorSetX))
                            .append(",\"y\":").append(jsonNumber(s.anchorSetY))
                            .append(",\"z\":").append(jsonNumber(s.anchorSetZ))
                            .append('}');
                }
                sb.append('}');
            }
            sb.append("]}");
            return sb.toString();
        } finally {
            if (DensityTraceCollector.isActive()) {
                DensityTraceCollector.finish();
            }
            DebugDensityAsset.TRACING_MODE.set(false);
        }
    }
}
