**Fix for Sodium 0.9.3.** With Sodium 0.9.3 (the current Modrinth version), 0.2.0-alpha.1 drew terrain with the wrong textures: magenta and black, or red and pink tints. Sodium 0.9.3 added a camera offset to its chunk uniforms, which moved the fields Luxium reads. Luxium now checks which Sodium is installed and supports both 0.9.2 and 0.9.3.

Everything else is as in [0.2.0-alpha.1](https://github.com/hecbatista/luxium/releases/tag/v0.2.0-alpha.1): in-game shader menus, eight more shader packs, and faster rendering.

## Install
1. Fabric Loader 0.19.5 or later for **Minecraft 26.3**.
2. In `mods/`:
   - [Sodium](https://modrinth.com/mod/sodium) 0.9.2 or 0.9.3;
   - [Metallum](https://modrinth.com/mod/metallum-mc) **0.0.25** (exactly that version);
   - `luxium-0.2.0-alpha.2.jar` (below), replacing any older Luxium jar. Fabric API isn't needed. [Mod Menu](https://modrinth.com/mod/modmenu) is optional.
3. Shader pack zips in `shaderpacks/`, then pick one in Video Settings → Shader Packs.

Report bugs at [Issues](https://github.com/hecbatista/luxium/issues), with `logs/latest.log`, the pack, and your Mac model and macOS version.

**SHA-256** `luxium-0.2.0-alpha.2.jar`: `df7c7260174af60132119e96469b7ed1064ce79d74ea785ce172998b0f6f3389`

License: LGPL-3.0-only.
