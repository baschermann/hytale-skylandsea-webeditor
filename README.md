# Skylandsᴇᴀ + Node web editor

Created for the Hytale New Worlds modding contest and learning experience for WorldGen v2.

This project comes with a custom WorldGen v2 node editor including comments, descriptions and explanations.

Feel free to use this as a starting point for your projects and learn how the world generation works.
I know that it's not the most impressive in terms of artistic vision, but I simply ran out of time as the technical side kicked my ass and I focused on understand how the world gen works. I'm talking about you, density. 

ToDo Image of Island and Editor

# Usage of AI

All the code is created with AI due to time constraints. Boycott it or not, I don't care. I'm a professional software developer and see AI as a tool which has it's uses, especially in prototyping.
- My time is severely limited due to my day job
- Hytale server source has not yet been released. Decompiled code is not fun to read & there are no official APIs. As far as I have seen, a lot is also to change as many systems need to be reworked, which afaik, is what the Hytale team is currently doing. So investing a large portion of my time into it makes no sense. But the java part is lightweight anyways, except for that one custom node.
- My last website is 9 years ago. Properly learning how to do one is a project in itself. But I see no reason for creating a GUI other than web nowadays. Except if you want me to do it in AWT or Swing? ;) 
- I'm not even sure what I wanted/needed in the beginning

# How to run

## Hytale ready jars can be found on CurseForge



TODO

## Prerequisites

### Java
I personally use IDEA IntelliJ. The community edition is completely fine. See official setup: https://hytalemodding.dev/en/docs/guides/plugin/setting-up-env

Used for
- `skylandsea-plugin`
- `worldgen-web-editor-plugin`
- `worldgen-custom-nodes`

### Node + NPM + Vue
I personally use WebStorm. Again, the community edition is fine. How to set it up? No idea. I'm fighting this thing every day. Why is everything so complicated and why is the dependency file 7000 lines long? I just want a simple website :(. There is a readme in the project from the vue guys.

- **Node**: `^20.19.0` or `>=22.12.0` (see `worldgen-web-editor/package.json` `engines`). `npm` on `PATH` (Windows: `npm.cmd` for Gradle).

Used for
- `worldgen-web-editor`

### Hytale
I'm on Windows OS.

- **Hytale**: Resolved and used by Gradle. `HytaleServer.jar` under `%USERPROFILE%\AppData\Roaming\Hytale` (Windows), or set `-Phytale_home=...` / `hytale_home` in `gradle.properties` for other OS

## Overview and setup

### Single folder structure
Clone the repo into a single folder, as the modules are referencing each other by going up one folder via /../

| Directory |                                                                                                                                                                                                     |
|-----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `skylandsea-plugin` | Hytale java plugin. Depends on `worldgen-custom-nodes` which are packaged into the jar.                                                                                                                 |
| `worldgen-web-editor-plugin` | Hytale java plugin. Webserver served on port 15009 (configurable, see below). Embeds production build of `worldgen-web-editor` in the jar. Optional dependency on `de.krah.skylandsea:SkylandseaPlugin`. |
| `worldgen-web-editor` | Node Web editor - Vue 3 + Vite UI                                                                                                                                                                       |
| `worldgen-custom-nodes` | Java library: Custom WorldGen v2 nodes. Used by `skylandsea-plugin` and referenced from the web editor.                                                                                                 |


### Build Hytale ready release jars

Use the `build` gradle task in `skylandsea-plugin` and `worldgen-web-editor-plugin` to create the respective java Hytale plugin jars.

### Run development

The `skylandsea-plugin` should auto-build run configurations from Gradle. When opened first time in IntelliJ, let it sync with gradle. On the top click on the drop down menu and there should be two entries.

**HytaleServer (Skylandsea):** Is the pure Skylandsᴇᴀ plugin. It auto starts a Hytale server which you can connect to via localhost. Make sure to use the run configuration and not the gradle run task, as you need to auth the server. If you use the gradle task, the console won't take commands. See ´Authentication´ on the official Hytale docs: https://support.hytale.com/hc/en-us/articles/45326769420827-Hytale-Server-Manual

**HytaleServer + WorldGenDebugger (Skylandsea):** Starts a hytale server with both the Skylandsᴇᴀ and the `worldgen-web-editor-plugin` (references by directory). **Note:** Start the web editor from the `worldgen-web-editor` folder inside WebStorm and have it both run at the same time.
