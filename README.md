<div align="center">

<img src="branding/icon-512.png" alt="Luxium icon: an iridescent ingot at sunset" width="128" height="128">

# Luxium

**Shader packs, on Metal.**

Run Iris / OptiFine shader packs on Minecraft's Metal backend, on Apple Silicon Macs.

![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-8a2a6e?style=flat-square) ![Fabric](https://img.shields.io/badge/loader-Fabric-3a1660?style=flat-square) ![Metal](https://img.shields.io/badge/backend-Metal-e0604a?style=flat-square&logo=apple&logoColor=white) ![Status: alpha](https://img.shields.io/badge/status-alpha-ffb35c?style=flat-square)

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
> Luxium is in **alpha** (0.1.0-alpha.1, not released yet). It's tested on one Mac (M1 Max, macOS 27). Expect bugs, and back up your worlds.

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

Benchmarks are in progress.

## 🧰 Requirements

| | You need |
|---|---|
| **Computer** | Mac with Apple Silicon (M1 or later) |
| **Minecraft** | 26.3 |
| **Mod loader** | [Fabric Loader](https://fabricmc.net/) 0.19.5 or later |
| **Java** | 25 or later |
| **Required mods** | [Metallum](https://modrinth.com/mod/metallum-mc) 0.0.25 and [Sodium](https://modrinth.com/mod/sodium) 0.9.2 |
| **A shader pack** | An Iris / OptiFine-format pack, placed in `.minecraft/shaderpacks/` |

## ⚙️ Choosing a pack and turning shaders off

At its first start, Luxium writes `config/luxium.properties`:

```properties
enableShaders=true
shaderPack=ComplementaryUnbound_r5.10.zip
# Packs found: ComplementaryUnbound_r5.10.zip
```

- `enableShaders=false` turns shaders off: the game renders as if Luxium weren't installed.
- `shaderPack` is the pack to use, exactly as it's named in `shaderpacks/`.
- Restart the game after changing either.

Pack settings go in `shaderpacks/<pack>.txt`, the same file Iris uses, so settings carry over from Iris. Luxium lists every setting and its allowed values in `shaderpacks/<pack>.luxium-options.txt`. In-game shader menus are planned for the release after the alpha.

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
