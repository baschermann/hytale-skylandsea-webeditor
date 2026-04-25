package de.krah.nodeeditorweb;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class DensityUpdateService {
    private static final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** Version (timestamp) of debug data; used for cache validation. Bumped on startup and whenever debug metrics are sent. */
    private static volatile long debugDataVersion = System.currentTimeMillis();

    public static long getDebugDataVersion() {
        return debugDataVersion;
    }

    /** Call when debug data is reset (e.g. clear metrics) so cached chunks are invalidated. */
    public static void bumpDebugDataVersion() {
        debugDataVersion = System.currentTimeMillis();
    }

    public static SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);
        System.out.println("[Webserver] New subscription request. Active emitters: " + emitters.size());

        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            System.out.println("[Webserver] Connection completed. Active emitters: " + emitters.size());
        });
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            System.out.println("[Webserver] Connection timeout. Active emitters: " + emitters.size());
        });
        emitter.onError((e) -> {
            emitters.remove(emitter);
            System.out.println("[Webserver] Connection error: " + e.getMessage() + ". Active emitters: " + emitters.size());
        });

        try {
            // Send initial warmup DATA instead of comment
            emitter.send(SseEmitter.event().name("ping").data("connected"));
            System.out.println("[Webserver] Initial warmup sent");
            // Send current debug metrics so client has data on connect/reload
            String currentMetrics = de.krah.worldgen.WorldGenDebugger.getCurrentMetricsJson();
            if (currentMetrics != null && !currentMetrics.equals("[]")) {
                emitter.send(SseEmitter.event().data(currentMetrics));
                System.out.println("[Webserver] Sent current metrics to new subscriber");
            }
        } catch (IOException e) {
            System.err.println("[Webserver] Failed to send initial data: " + e.getMessage());
            emitters.remove(emitter);
        }

        return emitter;
    }

    public static void sendUpdate(Object data) {
        if (emitters.isEmpty()) return;

        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(data));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }
        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
            System.out.println("[Webserver] Removed " + deadEmitters.size() + " dead emitters during update. Remaining: " + emitters.size());
        }
    }

    /**
     * Called when chunk generation has finished (debounced). Sends the metrics to the website,
     * bumps the debug data version, and clears the chunk mesh cache so the next /field request
     * returns fresh data instead of stale cached meshes.
     */
    public static void notifyChunkGenerationComplete(String metricsJson) {
        sendUpdate(metricsJson);
        bumpDebugDataVersion();
        Webserver.clearChunkMeshCache();
    }

    /**
     * Emitted when the server reloads the biome asset that backs the node editor default graph, so the client
     * can refetch {@code GET /nodeeditor/default-graph}. Uses event name {@code graph-reload} (not the default
     * {@code message} handler) so it does not collide with density metrics JSON.
     */
    public static void notifyGraphReloadFromServer() {
        if (emitters.isEmpty()) {
            return;
        }
        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("graph-reload").data("{}"));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }
        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
            System.out.println("[Webserver] Removed " + deadEmitters.size() + " dead emitters during graph-reload. Remaining: " + emitters.size());
        }
    }

    public static void sendHeartbeat() {
        if (emitters.isEmpty()) return;

        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }
        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
            System.out.println("[Webserver] Removed " + deadEmitters.size() + " dead emitters during heartbeat. Remaining: " + emitters.size());
        }
    }
}
