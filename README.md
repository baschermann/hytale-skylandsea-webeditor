# hytale-skylandsea-editor

Hytale modding contest release: Skylandsea world/plugin, world-gen debugger web UI, and shared custom generator nodes.

## Layout

| Directory | Role |
|-----------|------|
| `skylandsea-plugin` | Hytale plugin (main content). Depends on `worldgen-custom-nodes` (compiled in). |
| `worldgen-web-editor-plugin` | Hytale plugin: HTTP API on port 15009, debugger, embeds production build of `worldgen-web-editor` in the jar. Optional dependency on `de.krah.skylandsea:SkylandseaPlugin`. |
| `worldgen-web-editor` | Vue 3 + Vite UI; dev with `npm run dev`, production output consumed by `worldgen-web-editor-plugin` Gradle `jar`. |
| `worldgen-custom-nodes` | Java library: custom density/position nodes. Used by `skylandsea-plugin` and referenced from the UI assets. |

## Prerequisites

- **JDK**: 25 (see each `gradle.properties` `java_version`).
- **Node**: `^20.19.0` or `>=22.12.0` (see `worldgen-web-editor/package.json` `engines`). `npm` on `PATH` (Windows: `npm.cmd` for Gradle).
- **Hytale**: installed so Gradle can resolve `HytaleServer.jar` under `%USERPROFILE%\AppData\Roaming\Hytale` (Windows), or set `-Phytale_home=...` / `hytale_home` in `gradle.properties` for other layouts.

## Build (release jars)

From repo root, each project has its own Gradle wrapper.

```text
worldgen-custom-nodes\gradlew.bat jar
skylandsea-plugin\gradlew.bat jar
worldgen-web-editor-plugin\gradlew.bat jar
```

(non-Windows: `./gradlew` in each directory.)

Outputs:

- `skylandsea-plugin/build/libs/skylandsea-1.0.0.jar`
- `worldgen-web-editor-plugin/build/libs/hytale-worldgen-v2-editor-web-1.0.0.jar`

`worldgen-web-editor-plugin` `jar` runs `npm ci` and `npm run build-only` in `worldgen-web-editor`, then packs `dist/` into the plugin jar under `node-editor-web/`.

## Run (dev)

Hytale server run tasks live in each plugin’s `build.gradle` (`runHytaleServer`, etc.); they need a local Hytale install and use `patchline` from that project’s `gradle.properties` (default `release`).
