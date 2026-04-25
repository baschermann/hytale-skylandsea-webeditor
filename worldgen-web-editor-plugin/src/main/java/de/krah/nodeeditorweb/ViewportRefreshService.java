package de.krah.nodeeditorweb;

import de.krah.worldgen.WorldGenDebugger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Stores the active viewport configuration set from the web UI and re-runs
 * the viewport refresh whenever assets are reloaded (e.g. after saving a graph).
 * This mirrors what ViewportCommand does with AssetManager.registerReloadListener.
 */
public class ViewportRefreshService {

    private static volatile ViewportConfig activeConfig;

    /**
     * Fallback SSE tick scheduled when a refresh completes with no chunk events (full cache hit).
     * {@link WorldGenDebugger}'s own debounce triggers first when real chunks were regenerated.
     */
    private static final long POST_REFRESH_NOTIFY_DELAY_MS = 500;

    private static final ScheduledExecutorService POST_REFRESH_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ViewportRefresh-notify");
                t.setDaemon(true);
                return t;
            });

    private static volatile ScheduledFuture<?> pendingPostRefreshNotify;

    public static final class ViewportConfig {
        public final String worldName;
        public final int centerX;
        public final int centerZ;
        public final int radius;

        public ViewportConfig(String worldName, int centerX, int centerZ, int radius) {
            this.worldName = worldName;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
        }
    }

    public static ViewportConfig getActiveConfig() {
        return activeConfig;
    }

    public static void setActiveConfig(ViewportConfig config) {
        activeConfig = config;
    }

    public static void clearActiveConfig() {
        activeConfig = null;
    }

    /**
     * Called when assets are reloaded (e.g. after saving a file).
     * If a viewport is configured, triggers chunk regeneration.
     */
    public static void onAssetsReloaded() {
        ViewportConfig config = activeConfig;
        if (config == null) return;
        System.out.println("[Viewport] Assets reloaded, refreshing active viewport...");
        refreshViewport(config, null);
    }

    /**
     * Runs chunk generation for the given config. If resultFuture is provided,
     * it is completed when done; otherwise fire-and-forget.
     */
    public static void refreshViewport(ViewportConfig config, CompletableFuture<Void> resultFuture) {
        Universe universe = Universe.get();
        if (universe == null) {
            if (resultFuture != null) resultFuture.completeExceptionally(new IllegalStateException("Universe not available yet."));
            return;
        }

        World world;
        if (config.worldName != null && !config.worldName.isBlank()) {
            world = universe.getWorld(config.worldName);
        } else {
            world = universe.getDefaultWorld();
            if (world == null) {
                var worlds = universe.getWorlds();
                if (!worlds.isEmpty()) world = worlds.values().iterator().next();
            }
        }

        if (world == null) {
            if (resultFuture != null) resultFuture.completeExceptionally(new IllegalStateException("No world available."));
            return;
        }

        final World targetWorld = world;
        final long refreshStartMs = System.currentTimeMillis();
        targetWorld.execute(() -> {
            try {
                WorldGenDebugger.resetAllMetrics();
                DensityUpdateService.bumpDebugDataVersion();
                int minCX = config.centerX - config.radius;
                int maxCX = config.centerX + config.radius;
                int minCZ = config.centerZ - config.radius;
                int maxCZ = config.centerZ + config.radius;
                int count = (maxCX - minCX + 1) * (maxCZ - minCZ + 1);
                CompletableFuture<?>[] futures = new CompletableFuture[count];
                int i = 0;
                ChunkStore chunkStore = targetWorld.getChunkStore();
                for (int x = minCX; x <= maxCX; x++) {
                    for (int z = minCZ; z <= maxCZ; z++) {
                        long chunkIndex = ChunkUtil.indexChunk(x, z);
                        futures[i++] = chunkStore.getChunkReferenceAsync(chunkIndex, 9);
                    }
                }
                System.out.println("[Viewport] Refreshing " + count + " chunks around (" + config.centerX + ", " + config.centerZ + ") radius=" + config.radius + " in world '" + targetWorld.getName() + "'");
                CompletableFuture.allOf(futures).whenComplete((v, e) -> {
                    if (e != null) {
                        System.err.println("[Viewport] Refresh failed: " + e.getMessage());
                        if (resultFuture != null) resultFuture.completeExceptionally(e);
                    } else {
                        System.out.println("[Viewport] Refresh complete.");
                        scheduleFallbackClientNotify(refreshStartMs);
                        if (resultFuture != null) resultFuture.complete(null);
                    }
                });
            } catch (Exception e) {
                System.err.println("[Viewport] Error starting refresh: " + e.getMessage());
                if (resultFuture != null) resultFuture.completeExceptionally(e);
            }
        });
    }

    /**
     * Ensures the website sees a fresh tick even on full cache hit (no {@code ChunkPreLoadProcessEvent} fires).
     * If {@link WorldGenDebugger}'s own debounce already sent metrics in the meantime it's a cheap no-op
     * because metrics were reset at the start of this refresh and {@code getCurrentMetricsJson()} returns
     * {@code null} again.
     */
    private static void scheduleFallbackClientNotify(long refreshStartMs) {
        ScheduledFuture<?> prev = pendingPostRefreshNotify;
        if (prev != null) prev.cancel(false);
        pendingPostRefreshNotify = POST_REFRESH_EXECUTOR.schedule(() -> {
            pendingPostRefreshNotify = null;
            // Skip if WorldGenDebugger already sent metrics for this refresh wave (its own debounce handled it).
            if (WorldGenDebugger.getLastMetricsSentAtMs() >= refreshStartMs) {
                return;
            }
            String metricsJson = WorldGenDebugger.getCurrentMetricsJson();
            DensityUpdateService.notifyChunkGenerationComplete(metricsJson != null ? metricsJson : "[]");
        }, POST_REFRESH_NOTIFY_DELAY_MS, TimeUnit.MILLISECONDS);
    }
}
