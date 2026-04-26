package de.krah.worldgenv2.editorweb;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.builtin.hytalegenerator.assets.biomes.BiomeAsset;
import com.hypixel.hytale.builtin.hytalegenerator.assets.density.DensityAsset;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import de.krah.nodeeditorweb.DensityUpdateService;
import de.krah.nodeeditorweb.EditorWebRuntimeConfig;
import de.krah.nodeeditorweb.ViewportRefreshService;
import de.krah.nodeeditorweb.Webserver;
import de.krah.worldgen.WorldGenDebugger;
import de.krah.worldgen.customnodes.CustomDensityNodeRegistry;
import de.krah.worldgen.customnodes.CustomPositionProviderRegistry;

import javax.annotation.Nonnull;

/**
 * HTTP node editor, collaboration, and world generation debugger (density instrumentation).
 *
 * <p>Registers {@code worldgen-custom-nodes} codecs so biome JSON decodes when Skylandsea is deployed as an
 * asset-only folder (no Java {@code Main}). If {@code SkylandseaPlugin} is also a loaded jar and calls the same
 * {@code registerAll()} methods, {@link com.hypixel.hytale.assetstore.codec.AssetCodecMapCodec} replaces entries
 * for the same {@code Type} id — fine when both mods ship the same custom-node version; avoid mixing mismatched
 * builds of {@code worldgen-custom-nodes}.</p>
 */
public class HytaleWorldGenV2EditorWebPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private EditorWebRuntimeConfig runtimeConfig;

    public HytaleWorldGenV2EditorWebPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        LOGGER.atInfo().log("Hello from " + this.getName() + " version " + this.getManifest().getVersion().toString());
    }

    @Override
    protected void setup() {
        CustomDensityNodeRegistry.registerAll();
        CustomPositionProviderRegistry.registerAll();

        runtimeConfig = EditorWebRuntimeConfig.loadOrCreate(getDataDirectory(), LOGGER);
        Webserver.init(runtimeConfig);
        LOGGER.atInfo().log(
                "Editor runtime config loaded from %s (port=%d, allowNonLocalhost=%s)",
                runtimeConfig.path(), runtimeConfig.port(), runtimeConfig.allowNonLocalhost());

        new WorldGenDebugger(this);

        getEventRegistry().register(PlayerConnectEvent.class, event ->
                event.getPlayerRef().sendMessage(Message.raw(
                        "[WorldGen WebEditor] Access via http://localhost:15009/"
                                + " | External access is " + (Webserver.isExternalAccessEnabled() ? "enabled" : "disabled")
                )));

        getEventRegistry().register(LoadedAssetsEvent.class, BiomeAsset.class,
                (LoadedAssetsEvent<String, BiomeAsset, DefaultAssetMap<String, BiomeAsset>> event) -> {
                    ViewportRefreshService.onAssetsReloaded();
                    String activeWorld = ViewportRefreshService.getActiveConfig() != null
                            ? ViewportRefreshService.getActiveConfig().worldName
                            : null;
                    if (!event.isInitial()
                            && Webserver.defaultGraphEditorMayBeStale(event.getLoadedAssets().keySet(), activeWorld)) {
                        DensityUpdateService.notifyGraphReloadFromServer();
                    }
                });
        getEventRegistry().register(LoadedAssetsEvent.class, DensityAsset.class,
                (LoadedAssetsEvent<String, DensityAsset, DefaultAssetMap<String, DensityAsset>> event) ->
                        ViewportRefreshService.onAssetsReloaded());
    }
}
