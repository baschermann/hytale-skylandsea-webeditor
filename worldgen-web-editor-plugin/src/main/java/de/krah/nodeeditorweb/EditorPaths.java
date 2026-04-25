package de.krah.nodeeditorweb;

import com.hypixel.hytale.builtin.hytalegenerator.plugin.HandleProvider;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.IWorldGenProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves filesystem paths for loading and saving biome graphs in the node editor.
 * <p>
 * Override with {@code -Dworldgen.v2.editor.defaultGraph=...} and/or
 * {@code -Dworldgen.v2.editor.biomesDir=...} when the default layout does not match your checkout.
 */
public final class EditorPaths {

    public static final String PROP_DEFAULT_GRAPH = "worldgen.v2.editor.defaultGraph";
    public static final String PROP_BIOMES_DIR = "worldgen.v2.editor.biomesDir";

    /** Matches {@code "DefaultBiome": "Name"} in a WorldStructure JSON (simple enough for our files). */
    private static final Pattern DEFAULT_BIOME_PATTERN =
            Pattern.compile("\"DefaultBiome\"\\s*:\\s*\"([^\"]+)\"");

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
        return biomesDirectory().resolve("Skylandsea.json");
    }

    public static Path resolveBiomeSavePath(String filename) {
        if (filename == null || filename.isBlank()) {
            return defaultGraphPath();
        }
        if (filename.contains("..") || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid biome file name: " + filename);
        }
        return biomesDirectory().resolve(filename);
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
        Path p = biomesDirectory().resolve(biome + ".json");
        if (!Files.isRegularFile(p)) {
            return defaultGraphPath();
        }
        return p;
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
        Path structurePath = worldStructuresDirectory().resolve(structureName + ".json");
        if (!Files.isRegularFile(structurePath)) {
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
}
