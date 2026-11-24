<div align="center">

<img src="branding/icon-512.png" alt="Luxium icon: an iridescent ingot at sunset" width="128" height="128">

# Luxium

**Shader packs, on Metal.**

Run Iris / OptiFine shader packs on Minecraft's Metal backend, on Apple Silicon Macs.

![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-8a2a6e?style=flat-square) ![Fabric](https://img.shields.io/badge/loader-Fabric-3a1660?style=flat-square) ![Metal](https://img.shields.io/badge/backend-Metal-e0604a?style=flat-square&logo=apple&logoColor=white) ![Status: alpha](https://img.shields.io/badge/status-alpha-ffb35c?style=flat-square)

<img src="docs/release/promo/beach-sunset.jpg" alt="Sunset over the sea, with the sun reflecting on the water, in Complementary Unbound under Luxium" width="100%">

<sub>Complementary Unbound r5.10 under Luxium, on an M1 Max. HUD hidden; nothing edited.</sub>

</div>

---

## ✨ What is it?

Shader packs are written for OpenGL. On a Mac, the [Metallum](https://github.com/kokodio/metallum) mod replaces Minecraft's renderer with Apple's **Metal** API, which shader-pack mods like Iris don't support.

**Luxium is the missing piece.** It's a Fabric mod that sits next to Metallum and runs shader packs from your `shaderpacks/` folder as they are, without editing them. It:

- 📦 **reads the pack** the way Iris does: its folders, per-world programs and settings
- 🧩 **preprocesses** each program: `#include`, `#if` and the standard defines packs expect
- 🔁 **translates** the GLSL into a form that compiles to Metal Shading Language
- 🎛️ **supplies the uniforms** packs read: time, camera, weather, biome, held item and more
- 🌅 **runs the pack's render passes** on Metallum's Metal device: sky, terrain, water, entities, shadows, deferred and composite passes

> [!NOTE]
> Luxium is in **alpha** (0.1.0-alpha.1). It's tested on one Mac (M1 Max, macOS 27). Expect bugs, and back up your worlds.

## 📸 Screenshots

| | |
|---|---|
| <img src="docs/release/promo/jungle-shore.jpg" alt="A jungle shore at noon, with clear water"> | <img src="docs/release/promo/cherry-grove.jpg" alt="A cherry grove on a hillside in the late afternoon"> |
| <img src="docs/release/promo/savanna-sunset.jpg" alt="Savanna grass with long shadows at sunset"> | <img src="docs/release/promo/forest-morning.jpg" alt="Morning sun over a forest canopy"> |
| <img src="docs/release/promo/moonrise.jpg" alt="The moon and stars rising over the plains"> | |

All taken with [Complementary Shaders](https://www.complementary.dev/) by EminGT at its default settings, 1920×1080, render distance 12, by the scripted `promo` stop of the test tour (`mod/scripts/promo.sh <label>`).

## 🎨 Shader pack compatibility

Status as of 2026-11-23, with each pack at its default settings:

| Pack | Status | Notes |
|---|---|---|
| Complementary Unbound r5.10 | ✅ Works | The pack Luxium is built against. Compared with Iris scene by scene: sky, terrain, water, clouds, shadows, entities, block entities, particles, weather, hand, Nether and End. |
| Complementary Reimagined r5.9.3 | ✅ Loads and renders | No errors; not yet compared with Iris in detail. |
| Bliss v2.1.2 (Chocapic13 edit) | ❌ Falls back to vanilla | One program doesn't compile yet (missing `MC_HAND_DEPTH`, `texelFetch2D`). |
| Solas v3.9 | ❌ Falls back to vanilla | One program doesn't compile yet (missing `MC_RENDER_QUALITY`). |
| Other packs | ❔ Untested | They may work, fall back, or look wrong. Reports welcome. |

When a pack can't be used, Luxium doesn't crash. The game renders without shaders, shows a "Luxium: shaders off" notice, and writes one line to `logs/latest.log` saying why.

Colored lighting stays off on macOS, as it does for Complementary under Iris on a Mac (`MC_OS_MAC`).

## ⚡ Performance

First measurements, with Complementary Unbound at default settings on an M1 Max, in a 1708×960 window. Vsync is off and nothing is capped. Each figure is the average of two runs, in fps:

| Scene | Render distance | Luxium (Metal) | Iris (OpenGL) |
|---|---|---|---|
| Beach, facing the sea | 12 | **90** (1% low 76) | 81 (1% low 67) |
| Overlook of terrain | 12 | **96** (1% low 82) | 88 (1% low 70) |
| 360° spin | 12 | **95** (1% low 74) | 86 (1% low 65) |
| Beach, facing the sea | 5 | **137** (1% low 113) | 96 (1% low 68) |

Frame pacing is steadier too: Luxium's worst frame was 20 ms, against 85 ms for Iris. These are early numbers from one Mac; the method and every run are in [`docs/release/benchmarks.md`](docs/release/benchmarks.md).

## 🧰 Requirements

| | You need |
|---|---|
| **Computer** | Mac with Apple Silicon (M1 or later) |
| **Minecraft** | 26.3 |
| **Mod loader** | [Fabric Loader](https://fabricmc.net/) 0.19.5 or later |
| **Java** | 25 or later |
| **Required mods** | [Metallum](https://modrinth.com/mod/metallum-mc) 0.0.25 and [Sodium](https://modrinth.com/mod/sodium) 0.9.2 |
| **A shader pack** | An Iris / OptiFine-format pack, placed in `.minecraft/shaderpacks/` |

## 📥 Install

1. **Install Fabric** for Minecraft 26.3, with the [Fabric installer](https://fabricmc.net/use/installer/) or in a launcher like Prism or Modrinth (create a new 26.3 Fabric instance).
2. **Put three mods in your `mods` folder:**
   - [Sodium](https://modrinth.com/mod/sodium) 0.9.2 for 26.3;
   - [Metallum](https://modrinth.com/mod/metallum-mc) **0.0.25**, exactly that version: Luxium checks it at startup;
   - Luxium, `luxium-0.1.0-alpha.1.jar` from [Releases](https://github.com/hecbatista/luxium/releases).

   Fabric API isn't needed.
3. **Put a shader pack** (the `.zip`, unopened) in the `shaderpacks` folder next to `mods`. Complementary Unbound is the best-tested pack.
4. **Start the game.** Metallum switches the game to Metal by itself; there's nothing to select. Luxium uses the first pack it finds; pick another one in the Shader Packs screen (below).

Don't install Iris or OptiFine next to Luxium: they need OpenGL, and Metallum replaces it.

The first time you enter each world (Overworld, Nether, End) after installing or updating, loading takes a few extra seconds while macOS compiles the shaders. Later loads are quick.

## ⚙️ Shader menus

Open the **Shader Packs** screen in any of three ways:

- **Options → Video Settings → Luxium → Shader Packs** (in the left column of Sodium's settings screen);
- the **I** key in game (change it in Controls, under "Luxium");
- **Mod Menu → Luxium → Configure**, if you use [Mod Menu](https://modrinth.com/mod/modmenu) (optional).

On that screen:

- the top row turns shaders **on or off**;
- click a pack to use it (that also turns shaders on). The pack in use is shown in yellow;
- **Shader Pack Settings...** opens the pack's own settings menus;
- **Apply** takes effect right away, without a restart (the world pauses for a second or two while the pack reloads); **Done** applies and closes; **Cancel** drops your changes.

In a pack's settings menus, which work as they do in Iris:

- **left click** a setting for its next value and **right click** for the previous one; drag **sliders**;
- a setting's name turns **yellow** until you apply the change;
- **Shift + click** puts one setting back to its default; hold **Shift** and click **Reset** to put the whole pack back to its defaults;
- hover a setting for its description; **Esc** goes back up one menu;
- the **Profile** row switches between the pack's presets (Complementary's run from Potato to Ultra and its own "Complementary").

Settings are saved in `shaderpacks/<pack>.txt`, the same file Iris uses, so they carry over between Iris and Luxium. Only settings you changed from the pack's defaults are written there.

Press **F3** to see Luxium's status: the version, and whether shaders are on and with which pack.

<details>
<summary>Changing settings in files instead</summary>

Luxium keeps the screen's choices in `config/luxium.properties`:

```properties
enableShaders=true
shaderPack=ComplementaryUnbound_r5.10.zip
# Packs found: ComplementaryUnbound_r5.10.zip
```

`enableShaders=false` turns shaders off (the game renders as if Luxium weren't installed), and `shaderPack` is the pack to use, exactly as it's named in `shaderpacks/`. Pack settings are `NAME=value` lines in `shaderpacks/<pack>.txt`; Luxium lists every setting and its allowed values in `shaderpacks/<pack>.luxium-options.txt`. Changes made in these files apply the next time the game starts.

</details>

## 🐞 Known issues

- **Pack support:** Complementary Unbound is the only pack checked closely against Iris; see the compatibility table above. Packs that need features Luxium doesn't have yet fall back to vanilla rendering with a notice.
- **Complementary's Very High, Ultra and Complementary profiles** turn on Colored Lighting, which the pack itself doesn't support on Macs: it then shows "Important Error: Colored Lighting is not supported on Apple devices". Turn **Advanced Color Tracing** off in the pack's Performance settings, or use the High profile (the default). Iris shows the same message on a Mac.
- **Settings menus:** moving between individual settings with the keyboard isn't supported yet (use the mouse), and there's no import/export of settings.
- **Not supported yet:**
  - chunk fade-in (new chunks pop in instead of fading through the fog);
  - packs' `customTexture.*` and raw custom textures;
  - Distant Horizons.
- **Performance at long render distances:** Luxium slows down more than Iris as render distance grows. It's still ahead at 12 chunks, but the gap narrows ([details](docs/release/benchmarks.md)).
- **Brightness:** Complementary can look very slightly darker than under Iris in some scenes; the cause is being looked into.
- **First load** of each world after an install or update takes a few seconds longer (macOS compiles the shaders once).
- **Apple Silicon only.** Metal isn't available on Intel Macs through Metallum.

## 📝 Reporting a bug

Open an issue at [github.com/hecbatista/luxium/issues](https://github.com/hecbatista/luxium/issues) with:

- `logs/latest.log` from your game folder (start the game, reproduce the problem, quit, then attach the file);
- the shader pack's name and version, and your `shaderpacks/<pack>.txt` if you changed settings;
- a screenshot (F2 saves to `screenshots/`). For flickering or flashing, a short screen recording helps a lot;
- your Mac model and macOS version, and how to reproduce the problem.

If a pack falls back to vanilla, the log has a line starting `Shader pack ... can't be used:` that says why.

## 🛠️ Building from source

You need JDK 25. Luxium builds against a local Metallum jar:

```bash
git clone https://github.com/hecbatista/luxium.git && cd luxium
git clone https://github.com/kokodio/metallum.git references/metallum
(cd references/metallum && git checkout 467d312 && ./gradlew build)
cd mod && ./gradlew build        # the jar lands in mod/build/libs/
```

To build against another Metallum jar instead, such as Modrinth's, set `LUXIUM_METALLUM_JAR=/path/to/metallum.jar`.

## 💜 Credits

Luxium builds on the work of these projects:

| Project | What it gives Luxium | License |
|---|---|---|
| [**Metallum**](https://github.com/kokodio/metallum) by kokodio | The Metal backend that Luxium runs on | MIT |
| [**Iris**](https://github.com/IrisShaders/Iris) by the Iris contributors | The reference for how shader packs behave. Luxium reproduces many of its rules and tables in its own code. | LGPL-3.0 |
| [**Vitrail**](https://github.com/avpbynf/Vitrail-Shaders) by the Vitrail contributors | Prior art for running shader packs on a non-OpenGL backend (Vulkan), and the design of program-level translation | LGPL-3.0 |
| [**minecraft-metal-shaders**](https://github.com/GaelQuinta/minecraft-metal-shaders) by GaelQuinta | Ideas only, no code: the compile pool with Metal cache warm-up, and checking pipelines against the real Metal driver | Apache-2.0 |
| [**JCPP**](https://github.com/shevek/jcpp) | The C preprocessor that evaluates `#if` and `#define`, bundled unmodified | Apache-2.0 |
| **Complementary Shaders** by EminGT | The pack Luxium is tested against. It isn't included here. Get it from its official page. | Complementary License |

For exactly what was adapted from Iris and Vitrail, file by file, see [`mod/NOTICE`](mod/NOTICE).

## 📄 License

Luxium is licensed under the [GNU LGPL-3.0](LICENSE) (LGPL-3.0-only).

<div align="center">
<sub>Not affiliated with Mojang, Microsoft, Apple, Iris or OptiFine.</sub>
</div>
