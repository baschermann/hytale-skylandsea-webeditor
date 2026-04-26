package de.krah.nodeeditorweb;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.nio.file.Path;

/**
 * Warns operators in-game (and in logs) when the node editor cannot resolve a biome graph file on disk,
 * so misconfigured mod paths are easier to spot than a silent HTTP 404.
 */
public final class EditorGraphLoadNotifier {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Avoid spamming chat when the website polls {@code /nodeeditor/default-graph} repeatedly. */
    private static final long CHAT_DEBOUNCE_MS = 45_000L;
    private static volatile long lastChatWarnAtMs;
    private static volatile String lastChatWarnSignature;

    private EditorGraphLoadNotifier() {
    }

    /**
     * Sends one debounced in-game chat line (to all online players) and logs a warning when the resolved
     * graph path is not a readable file.
     */
    public static void biomeGraphFileNotFound(Path resolvedPath, String worldName) {
        Path abs = resolvedPath != null ? resolvedPath.toAbsolutePath().normalize() : Path.of("?");
        String worldBit = (worldName == null || worldName.isBlank()) ? "default graph" : ("world \"" + worldName + "\"");
        String signature = abs + "|" + worldBit;
        long now = System.currentTimeMillis();
        synchronized (EditorGraphLoadNotifier.class) {
            if (now - lastChatWarnAtMs < CHAT_DEBOUNCE_MS && signature.equals(lastChatWarnSignature)) {
                return;
            }
            lastChatWarnAtMs = now;
            lastChatWarnSignature = signature;
        }

        String hint = "[WorldGen WebEditor] No biome graph JSON. Unpack jar as folder inside mods/ (must contain Server/HytaleGenerator/Biomes)";

        LOGGER.atWarning().log("%s", hint);

        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            Message message = Message.raw(hint);
            for (PlayerRef player : universe.getPlayers()) {
                player.sendMessage(message);
            }
        } catch (Throwable ignored) {
        }
    }
}
