package de.krah.nodeeditorweb;

import com.hypixel.hytale.logger.HytaleLogger;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class Webserver {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static volatile EditorWebRuntimeConfig runtimeConfig =
            new EditorWebRuntimeConfig(15009, false, Path.of("editor-web.properties"));
    /**
     * Upper bound for one mesh payload (positions + normals + indices).
     * Keep this close to ByteBuffer's int capacity so we preserve detail and only
     * fall back to coarser meshing when serialization would actually overflow.
     */
    private static final long MAX_MESH_RESPONSE_BYTES = (long) Integer.MAX_VALUE - (8L * 1024L * 1024L);
    /** For large debug regions, adaptive meshing can increase step up to this value. */
    private static final int MAX_ADAPTIVE_MESH_STEP = 16;

    /** Called when chunk generation completes so the website gets fresh data. No-op if mesh cache is not used. */
    public static void clearChunkMeshCache() {
        // Mesh cache cleared here when chunk gen completes; add cache clear when ChunkMeshCache is in use
    }

    private static String graphFileStemFor(Path graphPath) {
        String name = graphPath.getFileName().toString();
        if (name.endsWith(".json")) {
            return name.substring(0, name.length() - 5);
        }
        return name;
    }

    /**
     * True if a biome reload event includes the asset that corresponds to the editor's currently active
     * graph file (resolved via {@link EditorPaths#graphPathForWorld(String)}; falls back to
     * {@link EditorPaths#defaultGraphPath()} when {@code worldName} is blank). Keys may be {@code Id} or
     * {@code Pack:Id}.
     */
    public static boolean defaultGraphEditorMayBeStale(java.util.Collection<String> loadedBiomeKeys, String worldName) {
        if (loadedBiomeKeys == null || loadedBiomeKeys.isEmpty()) {
            return false;
        }
        Path active = (worldName == null || worldName.isBlank())
                ? EditorPaths.defaultGraphPath()
                : EditorPaths.graphPathForWorld(worldName);
        String stem = graphFileStemFor(active);
        for (String k : loadedBiomeKeys) {
            if (k == null || k.isBlank()) {
                continue;
            }
            if (stem.equals(k)) {
                return true;
            }
            int idx = k.lastIndexOf(':');
            String id = idx >= 0 ? k.substring(idx + 1).trim() : k;
            if (stem.equals(id)) {
                return true;
            }
        }
        return false;
    }

    static void main(String[] args) throws Exception {
        init();
    }

    public static void init() {
        init(runtimeConfig);
    }

    public static void init(EditorWebRuntimeConfig config) {
        runtimeConfig = config;
        new Thread(() -> {
            try {
                Tomcat tomcat = new Tomcat();
                tomcat.setPort(runtimeConfig.port());
                if (!runtimeConfig.allowNonLocalhost()) {
                    tomcat.setHostname("127.0.0.1");
                }
                org.apache.catalina.connector.Connector connector = tomcat.getConnector();
                connector.setAsyncTimeout(1200000); // 20 mins
                connector.setProperty("maxThreads", "50");

                // Setup context
                String baseDir = new File(".").getAbsolutePath();
                Context context = tomcat.addContext("", baseDir);

                // Spring context setup
                AnnotationConfigWebApplicationContext springContext = new AnnotationConfigWebApplicationContext();
                springContext.setServletContext(context.getServletContext());
                springContext.setClassLoader(Webserver.class.getClassLoader());
                springContext.register(AppConfig.class);
                springContext.register(DensityUpdateService.class);
                springContext.register(NodeEditorCollaborationService.class);
                springContext.refresh();

                // Register DispatcherServlet
                DispatcherServlet dispatcherServlet = new DispatcherServlet(springContext);
                org.apache.catalina.Wrapper wrapper = Tomcat.addServlet(context, "dispatcher", dispatcherServlet);
                wrapper.setLoadOnStartup(1);
                wrapper.setAsyncSupported(true);
                context.addServletMappingDecoded("/", "dispatcher");

                String bindTarget = runtimeConfig.allowNonLocalhost() ? "all interfaces" : "127.0.0.1 only";
                System.out.println("[Webserver] Starting on " + bindTarget + " (port " + runtimeConfig.port() + ")...");
                tomcat.start();
                String publicBase = editorUrlForPlayers();
                LOGGER.atInfo().log(
                        "Node editor HTTP server is serving at %s (port %d, %s)",
                        publicBase,
                        runtimeConfig.port(),
                        bindTarget);
                System.out.println("[Webserver] Running at " + publicBase + " (port " + runtimeConfig.port() + ")");

                // Keep-alive only (no test payload – client would refetch on every message)
                Thread keepAliveThread = new Thread(() -> {
                    while (true) {
                        try {
                            Thread.sleep(3000);
                            DensityUpdateService.sendHeartbeat();
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }, "webserver-keepalive");
                keepAliveThread.setDaemon(true);
                keepAliveThread.start();

                tomcat.getServer().await();
            } catch (LifecycleException e) {
                System.err.println("[Webserver] Failed to start: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }).start();
    }

    public static String editorUrlForPlayers() {
        if (runtimeConfig.allowNonLocalhost()) {
            return "http://<server-ip>:" + runtimeConfig.port() + "/";
        }
        return "http://localhost:" + runtimeConfig.port() + "/";
    }

    public static boolean isExternalAccessEnabled() {
        return runtimeConfig.allowNonLocalhost();
    }

    /** Thread pool for CPU-heavy density sampling + meshing so requests run in parallel. */
    private static final java.util.concurrent.ExecutorService DENSITY_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(4, Runtime.getRuntime().availableProcessors()),
                    r -> {
                        Thread t = new Thread(r, "density-worker");
                        t.setDaemon(true);
                        return t;
                    });

    @Configuration
    @EnableWebMvc
    @RestController
    @org.springframework.web.bind.annotation.CrossOrigin
    public static class AppConfig implements WebMvcConfigurer {

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/assets/**")
                    .addResourceLocations("classpath:/node-editor-web/assets/");
            registry.addResourceHandler("/favicon.ico")
                    .addResourceLocations("classpath:/node-editor-web/");
        }

        /**
         * Serves the Vite production bundle (copied into the jar under {@code classpath:/node-editor-web/}).
         */
        @GetMapping(value = {"/", "/density-viewer", "/density-viewer/**"}, produces = MediaType.TEXT_HTML_VALUE)
        public ResponseEntity<Resource> nodeEditorSpa() {
            Resource index = new ClassPathResource("node-editor-web/index.html", Webserver.class.getClassLoader());
            if (!index.exists()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(index);
        }

        @Override
        public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
            configurer.setDefaultTimeout(600_000);
            configurer.setTaskExecutor(
                    new org.springframework.scheduling.concurrent.ConcurrentTaskExecutor(DENSITY_EXECUTOR));
        }

        @GetMapping("/density-updates")
        public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribe() {
            return DensityUpdateService.subscribe();
        }

        /**
         * Live collaboration for the node editor (presence, cursor, graph). Clients open EventSource with
         * {@code sessionId} (UUID), then POST {@code /nodeeditor/collab/profile} and {@code /nodeeditor/collab/publish}.
         */
        @GetMapping("/nodeeditor/collab/stream")
        public org.springframework.web.servlet.mvc.method.annotation.SseEmitter collabStream(
                @RequestParam("sessionId") String sessionId
        ) {
            if (!NodeEditorCollaborationService.isValidSessionId(sessionId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid sessionId");
            }
            return NodeEditorCollaborationService.subscribe(sessionId);
        }

        @PostMapping("/nodeeditor/collab/profile")
        public ResponseEntity<?> collabProfile(
                @RequestParam("sessionId") String sessionId,
                @RequestParam(value = "name", required = false) String name,
                @RequestParam(value = "color", required = false) String color
        ) {
            if (!NodeEditorCollaborationService.isValidSessionId(sessionId)) {
                return ResponseEntity.badRequest().body(java.util.Map.of("error", "Invalid sessionId"));
            }
            NodeEditorCollaborationService.updateProfile(sessionId, name, color);
            return ResponseEntity.ok(java.util.Map.of("status", "ok"));
        }

        @PostMapping(value = "/nodeeditor/collab/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
        public ResponseEntity<?> collabPublish(
                @RequestParam("sessionId") String sessionId,
                @RequestBody byte[] body
        ) {
            if (!NodeEditorCollaborationService.isValidSessionId(sessionId)) {
                return ResponseEntity.badRequest().body(java.util.Map.of("error", "Invalid sessionId"));
            }
            String result = NodeEditorCollaborationService.publish(sessionId, body);
            if ("ok".equals(result)) {
                return ResponseEntity.ok(java.util.Map.of("status", "ok"));
            }
            return ResponseEntity.status(400).body(java.util.Map.of("error", result));
        }

        /**
         * Pull model for debug state: version (for HTTP cache validation), registered density node ids,
         * and current per-node min/max metrics. Same metrics as pushed on SSE after chunk generation.
         */
        @GetMapping(value = "/debug/density-snapshot", produces = MediaType.APPLICATION_JSON_VALUE)
        public ResponseEntity<String> getDensityDebugSnapshot() {
            return ResponseEntity.ok(de.krah.worldgen.WorldGenDebugger.getDebugSnapshotJson());
        }

        @PostMapping("/density-updates/clear")
        public ResponseEntity<?> clearMetrics() {
            de.krah.worldgen.WorldGenDebugger.resetAllMetrics();
            DensityUpdateService.bumpDebugDataVersion();
            return ResponseEntity.ok(java.util.Map.of("status", "success"));
        }

        @GetMapping(value = "/nodeeditor/default-graph", produces = MediaType.APPLICATION_JSON_VALUE)
        public ResponseEntity<?> loadDefaultGraph(
                @RequestParam(value = "worldName", required = false) String worldName
        ) {
            try {
                Path graphPath = (worldName == null || worldName.isBlank())
                        ? EditorPaths.defaultGraphPath()
                        : EditorPaths.graphPathForWorld(worldName);
                if (!Files.isRegularFile(graphPath)) {
                    EditorGraphLoadNotifier.biomeGraphFileNotFound(graphPath, worldName);
                    return ResponseEntity.notFound().build();
                }
                return ResponseEntity.ok().body(Files.readString(graphPath, StandardCharsets.UTF_8));
            } catch (Exception e) {
                return ResponseEntity.status(500).body("{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}");
            }
        }

        @PostMapping(value = "/nodeeditor/save", consumes = MediaType.APPLICATION_JSON_VALUE)
        public ResponseEntity<?> saveGraph(
                @RequestParam(value = "path", required = false) String file,
                @RequestParam(value = "worldName", required = false) String worldName,
                @RequestBody String body
        ) {
            try {
                Path p;
                if (file != null && !file.isBlank()) {
                    p = EditorPaths.resolveBiomeSavePath(file);
                } else if (worldName != null && !worldName.isBlank()) {
                    p = EditorPaths.graphPathForWorld(worldName);
                } else {
                    p = EditorPaths.defaultGraphPath();
                }
                Files.write(p, body.getBytes(StandardCharsets.UTF_8));
                System.out.println("[Webserver] Graph saved to " + p.toAbsolutePath());
                return ResponseEntity.ok(java.util.Map.of("status", "ok"));
            } catch (Exception e) {
                return ResponseEntity.status(500).body(java.util.Map.of("error", e.getMessage()));
            }
        }

        @GetMapping("/worlds")
        public ResponseEntity<?> listWorlds() {
            try {
                com.hypixel.hytale.server.core.universe.Universe universe =
                        com.hypixel.hytale.server.core.universe.Universe.get();
                if (universe == null) {
                    return ResponseEntity.status(503).body(java.util.Map.of("error", "Universe not available yet."));
                }
                java.util.Map<String, com.hypixel.hytale.server.core.universe.world.World> worlds = universe.getWorlds();
                com.hypixel.hytale.server.core.universe.world.World defaultWorld = universe.getDefaultWorld();
                String defaultName = defaultWorld != null ? defaultWorld.getName() : null;

                java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, com.hypixel.hytale.server.core.universe.world.World> entry : worlds.entrySet()) {
                    java.util.Map<String, Object> w = new java.util.LinkedHashMap<>();
                    w.put("name", entry.getValue().getName());
                    w.put("isDefault", entry.getValue().getName().equals(defaultName));
                    result.add(w);
                }
                return ResponseEntity.ok(result);
            } catch (Exception e) {
                return ResponseEntity.status(500).body(java.util.Map.of("error", e.getMessage()));
            }
        }

        @GetMapping("/viewport")
        public ResponseEntity<?> getViewport() {
            ViewportRefreshService.ViewportConfig config = ViewportRefreshService.getActiveConfig();
            if (config == null) {
                return ResponseEntity.ok(java.util.Map.of("active", false));
            }
            return ResponseEntity.ok(java.util.Map.of(
                    "active", true,
                    "worldName", config.worldName != null ? config.worldName : "",
                    "centerX", config.centerX,
                    "centerZ", config.centerZ,
                    "radius", config.radius));
        }

        @org.springframework.web.bind.annotation.DeleteMapping("/viewport")
        public ResponseEntity<?> clearViewport() {
            ViewportRefreshService.clearActiveConfig();
            System.out.println("[Viewport] Cleared active viewport.");
            return ResponseEntity.ok(java.util.Map.of("status", "ok"));
        }

        @PostMapping("/viewport/refresh")
        public ResponseEntity<?> refreshViewport(
                @RequestParam(value = "worldName", required = false) String worldName,
                @RequestParam(value = "centerX", defaultValue = "0") int centerX,
                @RequestParam(value = "centerZ", defaultValue = "0") int centerZ,
                @RequestParam(value = "radius", defaultValue = "3") int radius
        ) {
            radius = Math.max(1, Math.min(radius, 32));
            ViewportRefreshService.ViewportConfig config =
                    new ViewportRefreshService.ViewportConfig(worldName, centerX, centerZ, radius);
            ViewportRefreshService.setActiveConfig(config);

            try {
                int totalChunks = (2 * radius + 1) * (2 * radius + 1);
                java.util.concurrent.CompletableFuture<Void> result = new java.util.concurrent.CompletableFuture<>();
                ViewportRefreshService.refreshViewport(config, result);
                result.orTimeout(120, java.util.concurrent.TimeUnit.SECONDS).join();

                return ResponseEntity.ok(java.util.Map.of(
                        "status", "ok",
                        "chunks", totalChunks,
                        "worldName", worldName != null ? worldName : "",
                        "centerX", centerX,
                        "centerZ", centerZ,
                        "radius", radius));
            } catch (Exception e) {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                return ResponseEntity.status(500).body(java.util.Map.of("error", "Viewport refresh failed: " + msg));
            }
        }

        /**
         * Returns a sampled 3D density field for a given node id.
         *
         * The response has the shape:
         * {
         *   "dims": { "width": n, "height": n, "depth": n },
         *   "origin": { "x": 0, "y": 0, "z": 0 },
         *   "voxelSize": 1.0,
         *   "densities": [ ... float values ... ]
         * }
         */
        @GetMapping("/density-nodes/{id}/field")
        public java.util.concurrent.Callable<ResponseEntity<?>> getDensityField(
                @PathVariable("id") String nodeId,
                @RequestParam(value = "width", defaultValue = "128") int width,
                @RequestParam(value = "depth", defaultValue = "128") int depth,
                @RequestParam(value = "x", defaultValue = "-64") int startX,
                @RequestParam(value = "z", defaultValue = "-64") int startZ,
                @RequestParam(value = "threshold", defaultValue = "0") double threshold,
                @RequestParam(value = "format", required = false) String format,
                @RequestParam(value = "ifVersion", required = false) Long ifVersion
        ) {
            final int startY = com.hypixel.hytale.math.util.ChunkUtil.MIN_Y;
            final int height = com.hypixel.hytale.math.util.ChunkUtil.HEIGHT;
            String reqId = java.util.UUID.randomUUID().toString().substring(0, 8);
            long tRequest = System.currentTimeMillis();
            System.out.println("[DensityField] [" + reqId + "] request start nodeId=" + nodeId + " format=" + format + " " + width + "x" + height + "x" + depth);

            long currentVersion = DensityUpdateService.getDebugDataVersion();
            if (ifVersion != null && ifVersion.longValue() == currentVersion) {
                System.out.println("[DensityField] [" + reqId + "] 304 not modified in " + (System.currentTimeMillis() - tRequest) + " ms");
                return () -> ResponseEntity.status(304).build();
            }

            if (format == null || !format.equalsIgnoreCase("mesh")) {
                return () -> ResponseEntity.status(400).body(java.util.Map.of(
                        "error", "Only format=mesh is supported. Pass format=mesh for the 3D mesh."
                ));
            }

            return () -> {
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.set("X-Density-Version", String.valueOf(currentVersion));

                long t0Solid = System.currentTimeMillis();
                byte[] solid = de.krah.worldgen.WorldGenDebugger.sampleBinarySolid(nodeId, width, height, depth, startX, startY, startZ, threshold);
                long solidMs = System.currentTimeMillis() - t0Solid;
                System.out.println("[DensityField] [" + reqId + "] sampleBinarySolid in " + solidMs + " ms (total since request: " + (System.currentTimeMillis() - tRequest) + " ms)");
                if (solid == null) {
                    return ResponseEntity.status(404).body(java.util.Map.of(
                            "error", "No debug density registered for node id '" + nodeId + "'."));
                }
                int step = 1;
                long t0Mesh = System.currentTimeMillis();
                GreedyMesher.MeshResult mesh = GreedyMesher.buildMeshAdaptive(
                        width, height, depth, startX, startY, startZ,
                        solid, 1.0f,
                        step, MAX_ADAPTIVE_MESH_STEP,
                        MAX_MESH_RESPONSE_BYTES);
                long meshMs = System.currentTimeMillis() - t0Mesh;
                System.out.println("[DensityField] [" + reqId + "] GreedyMesher.buildMesh in " + meshMs + " ms (voxelSize=" + mesh.voxelSize + ", total since request: " + (System.currentTimeMillis() - tRequest) + " ms)");
                int nv = mesh.positions.length / 3;
                int ni = mesh.indices.length;
                long sizeLong = (4L * 4L) + 4L + 4L + ((long) nv * 3L * 4L) + ((long) nv * 3L * 4L) + ((long) ni * 4L);
                if (sizeLong > Integer.MAX_VALUE || sizeLong <= 0L) {
                    throw new IllegalStateException("Serialized mesh size out of bounds: " + sizeLong + " bytes");
                }
                int size = (int) sizeLong;
                long t0Ser = System.currentTimeMillis();
                ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
                buf.putFloat(mesh.originX);
                buf.putFloat(mesh.originY);
                buf.putFloat(mesh.originZ);
                buf.putFloat(mesh.voxelSize);
                buf.putInt(nv);
                buf.putInt(ni);
                for (float v : mesh.positions) buf.putFloat(v);
                for (float v : mesh.normals) buf.putFloat(v);
                for (int i : mesh.indices) buf.putInt(i);
                byte[] body = buf.array();
                long serMs = System.currentTimeMillis() - t0Ser;
                long totalMs = System.currentTimeMillis() - tRequest;
                System.out.println("[DensityField] [" + reqId + "] serialize " + (body.length / 1024) + " KB in " + serMs + " ms | total request: " + totalMs + " ms");
                return ResponseEntity.ok().headers(headers).contentType(MediaType.APPLICATION_OCTET_STREAM).body(body);
            };
        }

        /**
         * Traces {@code process()} for the density rooted at {@code id} at one world position (each wrapped node one row).
         */
        @GetMapping(value = "/density-nodes/{id}/trace", produces = MediaType.APPLICATION_JSON_VALUE)
        public ResponseEntity<String> traceDensityAt(
                @PathVariable("id") String nodeId,
                @RequestParam("x") double x,
                @RequestParam("y") double y,
                @RequestParam("z") double z
        ) {
            String json = de.krah.worldgen.WorldGenDebugger.traceDensityAtJson(nodeId, x, y, z);
            if (json == null) {
                return ResponseEntity.status(404)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"No debug density registered for this node.\"}");
            }
            return ResponseEntity.ok(json);
        }
    }
}
