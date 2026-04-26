package de.krah.nodeeditorweb;

import com.hypixel.hytale.builtin.hytalegenerator.plugin.HandleProvider;
import com.hypixel.hytale.server.core.Options;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.IWorldGenProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves filesystem paths for loading and saving biome graphs in the node editor.
 * <p>
 * Override with {@code -Dworldgen.v2.editor.defaultGraph=...} and/or
 * {@code -Dworldgen.v2.editor.biomesDir=...} when the default layout does not match your checkout.
 * <p>
 * When the usual dev paths (Gradle {@code src/main/resources/...}) are absent, biome and world-structure
 * JSON are also searched under {@code --mods} directories and {@code mods/} (pack layout:
 * {@code Server/HytaleGenerator/...} or {@code src/main/resources/Server/HytaleGenerator/...}), matching
 * how unpacked or directory-based mods are laid out on disk.
 */
public final class EditorPaths {

    public static final String PROP_DEFAULT_GRAPH = "worldgen.v2.editor.defaultGraph";
    public static final String PROP_BIOMES_DIR = "worldgen.v2.editor.biomesDir";

    /** Matches {@code "DefaultBiome": "Name"} in a WorldStructure JSON (simple enough for our files). */
    private static final Pattern DEFAULT_BIOME_PATTERN =
            Pattern.compile("\"DefaultBiome\"\\s*:\\s*\"([^\"]+)\"");

    private static final Path HYTALE_GEN_BIOMES = Path.of("Server", "HytaleGenerator", "Biomes");
    private static final Path HYTALE_GEN_WORLD_STRUCTURES =
            Path.of("Server", "HytaleGenerator", "WorldStructures");
    private static final Path SRC_MAIN_RESOURCES = Path.of("src", "main", "resources");

    private EditorPaths() {
    }

    public static Path biomesDirectory() {
        String override = System.getProperty(PROP_BIOMES_DIR);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path runParent = cwd.getFileName() != null && "run".equalsIgnoreCase(cwd.getFileName().toString())
                ? cwd.getParent()
                : cwd;
        Path localBiomes = runParent.resolve("src/main/resources/Server/HytaleGenerator/Biomes");
        if (Files.isDirectory(localBiomes)) {
            return localBiomes.normalize();
        }
        Path siblingBiomes = runParent.resolveSibling("skylandsea-plugin")
                .resolve("src/main/resources/Server/HytaleGenerator/Biomes");
        if (Files.isDirectory(siblingBiomes)) {
            return siblingBiomes.normalize();
        }
        return localBiomes.normalize();
    }

    public static Path defaultGraphPath() {
        String override = System.getProperty(PROP_DEFAULT_GRAPH);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        Path primary = biomesDirectory().resolve("Skylandsea.json");
        if (Files.isRegularFile(primary)) {
            return primary;
        }
        Path fromMods = findAssetInModPackRoots(HYTALE_GEN_BIOMES, "Skylandsea.json");
        return fromMods != null ? fromMods : primary;
    }

    public static Path resolveBiomeSavePath(String filename) {
        if (filename == null || filename.isBlank()) {
            return defaultGraphPath();
        }
        if (filename.contains("..") || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid biome file name: " + filename);
        }
        Path primary = biomesDirectory().resolve(filename);
        if (Files.isRegularFile(primary)) {
            return primary;
        }
        Path fromMods = findAssetInModPackRoots(HYTALE_GEN_BIOMES, filename);
        return fromMods != null ? fromMods : primary;
    }

    /** {@code Server/HytaleGenerator/WorldStructures} next to the biomes directory. */
    public static Path worldStructuresDirectory() {
        return biomesDirectory().resolveSibling("WorldStructures");
    }

    /**
     * Resolves the biome graph file associated with {@code worldName} via
     * {@code World → HandleProvider → WorldStructure.DefaultBiome → Biomes/<DefaultBiome>.json}.
     * Falls back to {@link #defaultGraphPath()} when the world, its provider, the structure file,
     * or its {@code DefaultBiome} field cannot be resolved.
     */
    public static Path graphPathForWorld(String worldName) {
        String biome = resolveDefaultBiomeForWorld(worldName);
        if (biome == null || biome.isBlank()) {
            return defaultGraphPath();
        }
        Path primary = biomesDirectory().resolve(biome + ".json");
        if (Files.isRegularFile(primary)) {
            return primary;
        }
        Path fromMods = findAssetInModPackRoots(HYTALE_GEN_BIOMES, biome + ".json");
        if (fromMods != null) {
            return fromMods;
        }
        return defaultGraphPath();
    }

    /**
     * Biome name for {@code worldName} (via its {@code WorldStructure.DefaultBiome}), or {@code null} when
     * unknown. Safe to call before universe is ready — returns {@code null} in that case.
     */
    public static String resolveDefaultBiomeForWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        Universe universe;
        try {
            universe = Universe.get();
        } catch (Throwable t) {
            return null;
        }
        if (universe == null) {
            return null;
        }
        World world = universe.getWorld(worldName);
        if (world == null) {
            return null;
        }
        IWorldGenProvider provider = world.getWorldConfig().getWorldGenProvider();
        if (!(provider instanceof HandleProvider)) {
            return null;
        }
        String structureName = ((HandleProvider) provider).getWorldStructureName();
        if (structureName == null || structureName.isBlank()) {
            return null;
        }
        Path structurePath = resolveWorldStructureFile(structureName);
        if (structurePath == null || !Files.isRegularFile(structurePath)) {
            return null;
        }
        try {
            String body = Files.readString(structurePath, StandardCharsets.UTF_8);
            Matcher m = DEFAULT_BIOME_PATTERN.matcher(body);
            if (m.find()) {
                return m.group(1);
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static Path resolveWorldStructureFile(String structureName) {
        String file = structureName + ".json";
        Path primary = worldStructuresDirectory().resolve(file);
        if (Files.isRegularFile(primary)) {
            return primary;
        }
        return findAssetInModPackRoots(HYTALE_GEN_WORLD_STRUCTURES, file);
    }

    private static List<Path> modsDirectoriesFromCli() {
        try {
            List<Path> dirs = Options.getOptionSet().valuesOf(Options.MODS_DIRECTORIES);
            if (dirs == null || dirs.isEmpty()) {
                return List.of();
            }
            List<Path> out = new ArrayList<>(dirs.size());
            for (Path p : dirs) {
                if (p != null) {
                    out.add(p.toAbsolutePath().normalize());
                }
            }
            return out;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /**
     * Pack roots under each mods directory: the directory itself if it looks like a mod asset tree, plus
     * immediate subdirectories that do (e.g. {@code mods/MyPack/Server/HytaleGenerator/Biomes}).
     */
    private static List<Path> modPackRootsInSearchOrder() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (Path modsTree : modsDirectoriesFromCli()) {
            collectPackRootsFromModsTree(modsTree, roots);
        }
        Path cwdMods = Paths.get(System.getProperty("user.dir"))
                .resolve(PluginManager.MODS_PATH)
                .toAbsolutePath()
                .normalize();
        collectPackRootsFromModsTree(cwdMods, roots);
        return new ArrayList<>(roots);
    }

    private static void collectPackRootsFromModsTree(Path modsTree, LinkedHashSet<Path> out) {
        if (modsTree == null || !Files.isDirectory(modsTree)) {
            return;
        }
        if (directoryLooksLikeModPackRoot(modsTree)) {
            out.add(modsTree);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsTree)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                if (directoryLooksLikeModPackRoot(child)) {
                    out.add(child.toAbsolutePath().normalize());
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean directoryLooksLikeModPackRoot(Path root) {
        try {
            if (Files.isDirectory(root.resolve(HYTALE_GEN_BIOMES))) {
                return true;
            }
            return Files.isDirectory(root.resolve(SRC_MAIN_RESOURCES).resolve(HYTALE_GEN_BIOMES));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * {@code relativeTail} is e.g. {@code Server/HytaleGenerator/Biomes/X.json}.
     */
    private static Path resolveAssetFileUnderPackRoot(Path packRoot, Path relativeTail) {
        Path direct = packRoot.resolve(relativeTail);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path viaSrcMain = packRoot.resolve(SRC_MAIN_RESOURCES).resolve(relativeTail);
        if (Files.isRegularFile(viaSrcMain)) {
            return viaSrcMain;
        }
        return null;
    }

    private static Path findAssetInModPackRoots(Path hytaleGenSubdir, String fileName) {
        Path tail = hytaleGenSubdir.resolve(fileName);
        for (Path packRoot : modPackRootsInSearchOrder()) {
            Path found = resolveAssetFileUnderPackRoot(packRoot, tail);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
