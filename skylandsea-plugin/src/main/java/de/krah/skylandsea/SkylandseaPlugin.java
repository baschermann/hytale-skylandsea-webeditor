package de.krah.skylandsea;

import com.hypixel.hytale.builtin.hytalegenerator.plugin.HandleProvider;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.IWorldGenProvider;
import de.krah.worldgen.customnodes.CustomDensityNodeRegistry;
import de.krah.worldgen.customnodes.CustomPositionProviderRegistry;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asset pack for the Skylandsea map. Registers custom HytaleGenerator density and position codecs,
 * and ensures the "skylandsea" world is the default one, generated with HytaleGenerator using
 * the {@code Skylandsea_Terrain_Default} world structure.
 */
public class SkylandseaPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String SKYLANDSEA_WORLD_NAME = "skylandsea";
    private static final String SKYLANDSEA_GENERATOR_TYPE = HandleProvider.ID; // "HytaleGenerator"
    private static final String SKYLANDSEA_WORLD_STRUCTURE = "Skylandsea_Terrain_Default";
    private static final String SKYLANDSEA_DISPLAY_NAME = "Skylandsea";

    public SkylandseaPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        LOGGER.atInfo().log("Hello from " + this.getName() + " version " + this.getManifest().getVersion().toString());
    }

    @Override
    protected void setup() {
        CustomDensityNodeRegistry.registerAll();
        CustomPositionProviderRegistry.registerAll();

        // Point the server at our world so Universe.getDefaultWorld() resolves to skylandsea.
        // This also makes the server persist "skylandsea" into the generated config.json on first run.
        HytaleServer.get().getConfig().getDefaults().setWorld(SKYLANDSEA_WORLD_NAME);

        // Pre-create the world config on disk so that Universe.start() loads it via loadWorldFromStart()
        // with the correct HytaleGenerator worldgen provider, instead of falling back to the one-arg
        // addWorld(name) path (which would use the core "Hytale" worldgen provider).
        // Plugin setup() runs before Universe.start(), so writing config.json here is safe.
        ensureSkylandseaWorldConfigOnDisk();
    }

    private void ensureSkylandseaWorldConfigOnDisk() {
        final Universe universe = Universe.get();
        if (universe == null) {
            LOGGER.atSevere().log("Universe singleton unavailable during setup; cannot prepare '%s' world",
                    SKYLANDSEA_WORLD_NAME);
            return;
        }

        final Path worldsPath = universe.getWorldsPath();
        if (worldsPath == null) {
            LOGGER.atSevere().log("Universe worldsPath unavailable; cannot prepare '%s' world",
                    SKYLANDSEA_WORLD_NAME);
            return;
        }

        final Path worldDir = worldsPath.resolve(SKYLANDSEA_WORLD_NAME);
        final Path configJson = worldDir.resolve("config.json");
        final Path configBson = worldDir.resolve("config.bson"); // legacy format

        // If a config already exists, don't stomp on it - the user (or a previous run) owns it.
        // We do warn if it's using the wrong generator so a broken state is visible.
        if (Files.exists(configJson) || Files.exists(configBson)) {
            verifyExistingConfigGenerator(configJson);
            return;
        }

        try {
            Files.createDirectories(worldDir);
        } catch (IOException e) {
            LOGGER.atSevere().withCause(e).log("Failed to create '%s' world directory at %s",
                    SKYLANDSEA_WORLD_NAME, worldDir);
            return;
        }

        final IWorldGenProvider provider = buildSkylandseaWorldGenProvider();
        if (provider == null) {
            return;
        }

        final WorldConfig worldConfig = new WorldConfig();
        worldConfig.setWorldGenProvider(provider);
        worldConfig.setDisplayName(SKYLANDSEA_DISPLAY_NAME);
        worldConfig.markChanged();

        try {
            WorldConfig.save(configJson, worldConfig).join();
            LOGGER.atInfo().log("Pre-created '%s' world config at %s using %s / %s",
                    SKYLANDSEA_WORLD_NAME, configJson, SKYLANDSEA_GENERATOR_TYPE, SKYLANDSEA_WORLD_STRUCTURE);
        } catch (Exception e) {
            LOGGER.atSevere().withCause(e).log("Failed to write '%s' world config to %s",
                    SKYLANDSEA_WORLD_NAME, configJson);
        }
    }

    private IWorldGenProvider buildSkylandseaWorldGenProvider() {
        final BuilderCodec<? extends IWorldGenProvider> providerCodec =
                IWorldGenProvider.CODEC.getCodecFor(SKYLANDSEA_GENERATOR_TYPE);
        if (providerCodec == null) {
            LOGGER.atSevere().log("Worldgen provider '%s' is not registered; cannot configure '%s'",
                    SKYLANDSEA_GENERATOR_TYPE, SKYLANDSEA_WORLD_NAME);
            return null;
        }

        final IWorldGenProvider provider = providerCodec.getDefaultValue();
        if (provider instanceof HandleProvider handle) {
            handle.setWorldStructureName(SKYLANDSEA_WORLD_STRUCTURE);
        } else {
            LOGGER.atWarning().log("Provider for '%s' is not a HandleProvider (got %s); "
                            + "world structure '%s' could not be applied",
                    SKYLANDSEA_GENERATOR_TYPE, provider.getClass().getName(), SKYLANDSEA_WORLD_STRUCTURE);
        }
        return provider;
    }

    // Matches the first "Type": "<id>" occurring inside the WorldGen block of a WorldConfig JSON.
    // Plain text scan is used instead of WorldConfig.load(), because loading runs the full codec
    // validation chain (e.g. GameplayConfig "Default" asset lookup), and at plugin setup() those
    // asset stores haven't been populated yet — which would fail the validation with a misleading
    // error even when the config on disk is perfectly fine.
    private static final Pattern WORLDGEN_TYPE_PATTERN = Pattern.compile(
            "\"WorldGen\"\\s*:\\s*\\{[^}]*?\"Type\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.DOTALL);
    private static final Pattern WORLD_STRUCTURE_NAME_PATTERN = Pattern.compile(
            "\"WorldStructureName\"\\s*:\\s*\"([^\"]+)\"");

    private void verifyExistingConfigGenerator(@Nonnull Path configJson) {
        try {
            if (!Files.exists(configJson)) {
                return;
            }
            final String content = Files.readString(configJson, StandardCharsets.UTF_8);

            final Matcher typeMatcher = WORLDGEN_TYPE_PATTERN.matcher(content);
            if (!typeMatcher.find()) {
                LOGGER.atInfo().log("World '%s' config at %s does not declare a WorldGen.Type; skipping verification",
                        SKYLANDSEA_WORLD_NAME, configJson);
                return;
            }
            final String existingType = typeMatcher.group(1);

            if (!SKYLANDSEA_GENERATOR_TYPE.equals(existingType)) {
                LOGGER.atWarning().log(
                        "World '%s' already exists on disk using worldgen '%s' (expected '%s'). "
                                + "Delete the world folder at %s to regenerate it with Skylandsea.",
                        SKYLANDSEA_WORLD_NAME, existingType, SKYLANDSEA_GENERATOR_TYPE,
                        configJson.getParent());
                return;
            }

            final Matcher structureMatcher = WORLD_STRUCTURE_NAME_PATTERN.matcher(content);
            final String existingStructure = structureMatcher.find() ? structureMatcher.group(1) : null;
            if (existingStructure != null && !SKYLANDSEA_WORLD_STRUCTURE.equals(existingStructure)) {
                LOGGER.atWarning().log(
                        "World '%s' uses HytaleGenerator but world structure is '%s' (expected '%s'). "
                                + "Delete the world folder at %s to regenerate it with Skylandsea.",
                        SKYLANDSEA_WORLD_NAME, existingStructure, SKYLANDSEA_WORLD_STRUCTURE,
                        configJson.getParent());
            } else {
                LOGGER.atInfo().log("World '%s' already configured with %s / %s",
                        SKYLANDSEA_WORLD_NAME, SKYLANDSEA_GENERATOR_TYPE, SKYLANDSEA_WORLD_STRUCTURE);
            }
        } catch (IOException e) {
            LOGGER.atWarning().withCause(e).log("Failed to read existing world config at %s", configJson);
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Failed to verify existing world config at %s", configJson);
        }
    }
}
