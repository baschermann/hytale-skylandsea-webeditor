package de.krah.nodeeditorweb;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/**
 * Runtime config for the worldgen web editor plugin.
 * Stored outside the jar so users can edit it after installation.
 */
public final class EditorWebRuntimeConfig {

    private static final int DEFAULT_PORT = 15009;
    private static final boolean DEFAULT_ALLOW_NON_LOCALHOST = false;

    private static final String FILE_NAME = "editor-web.properties";
    private static final String KEY_PORT = "port";
    private static final String KEY_ALLOW_NON_LOCALHOST = "allowNonLocalhost";

    private final int port;
    private final boolean allowNonLocalhost;
    private final Path path;

    EditorWebRuntimeConfig(int port, boolean allowNonLocalhost, Path path) {
        this.port = port;
        this.allowNonLocalhost = allowNonLocalhost;
        this.path = path;
    }

    @Nonnull
    public static EditorWebRuntimeConfig loadOrCreate(@Nonnull Path dataDirectory, @Nonnull HytaleLogger logger) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(logger, "logger");

        Path path = dataDirectory.resolve(FILE_NAME);
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            logger.atWarning().withCause(e).log("Failed to create plugin data directory at %s; using defaults", dataDirectory);
            return new EditorWebRuntimeConfig(DEFAULT_PORT, DEFAULT_ALLOW_NON_LOCALHOST, path);
        }

        if (!Files.exists(path)) {
            EditorWebRuntimeConfig defaults = new EditorWebRuntimeConfig(
                    DEFAULT_PORT,
                    DEFAULT_ALLOW_NON_LOCALHOST,
                    path
            );
            defaults.writeDefaults(logger);
            return defaults;
        }

        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException e) {
            logger.atWarning().withCause(e).log("Failed to read %s; using defaults", path);
            return new EditorWebRuntimeConfig(DEFAULT_PORT, DEFAULT_ALLOW_NON_LOCALHOST, path);
        }

        int port = parsePort(p.getProperty(KEY_PORT), logger, path);
        boolean allowNonLocalhost = parseBoolean(p.getProperty(KEY_ALLOW_NON_LOCALHOST));
        return new EditorWebRuntimeConfig(port, allowNonLocalhost, path);
    }

    private static int parsePort(String value, HytaleLogger logger, Path path) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1 || parsed > 65535) {
                logger.atWarning().log("Invalid port '%s' in %s; using default %d", value, path, DEFAULT_PORT);
                return DEFAULT_PORT;
            }
            return parsed;
        } catch (NumberFormatException e) {
            logger.atWarning().log("Invalid port '%s' in %s; using default %d", value, path, DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }

    private static boolean parseBoolean(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_ALLOW_NON_LOCALHOST;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private void writeDefaults(HytaleLogger logger) {
        Properties p = new Properties();
        p.setProperty(KEY_PORT, Integer.toString(this.port));
        p.setProperty(KEY_ALLOW_NON_LOCALHOST, Boolean.toString(this.allowNonLocalhost));
        try (Writer writer = Files.newBufferedWriter(this.path, StandardCharsets.UTF_8)) {
            p.store(writer, "Worldgen web editor runtime config");
        } catch (IOException e) {
            logger.atWarning().withCause(e).log("Failed to write default config at %s", this.path);
        }
    }

    public int port() {
        return port;
    }

    public boolean allowNonLocalhost() {
        return allowNonLocalhost;
    }

    @Nonnull
    public Path path() {
        return path;
    }
}

