# Modrinth listing (draft)

Everything needed to create the project at modrinth.com/dashboard/projects. Nothing here is posted yet.

## Project fields

| Field | Value |
|---|---|
| Name | Luxium |
| Slug | `luxium` (free as of 2026-11-23) |
| Summary (≤ 256 chars) | Run Iris / OptiFine shader packs on Apple Silicon Macs, on Minecraft's Metal backend (Metallum). Packs run unmodified. Alpha. |
| Project type | Mod |
| Categories | Decoration (primary), Utility. Pick on the form; these are the closest fits Modrinth offers. |
| Environment | Client: required. Server: unsupported. |
| License | LGPL-3.0-only |
| Icon | `branding/icon-512.png` |
| Source | https://github.com/hecbatista/luxium |
| Issues | https://github.com/hecbatista/luxium/issues |

## Version 0.1.0-alpha.1

| Field | Value |
|---|---|
| Version number | `0.1.0-alpha.1` |
| Channel | Alpha |
| Loader | Fabric |
| Game version | 26.3 |
| File | `luxium-0.1.0-alpha.1.jar` from the GitHub release (SHA-256 `5a8b015f2e7a869d6c8750f3b43a0c50f56588c807c7c6a7b4f7e0f2751d4586`, from the GitHub release) |
| Dependencies | Metallum (`metallum-mc`) **required**, version 0.0.25 · Sodium (`sodium`) **required**, version 0.9.2 · Iris (`iris`) **incompatible** |
| Changelog | First public alpha. Runs Complementary Unbound r5.10 in all three worlds; see the description for what works and what doesn't yet. |

## Gallery

Upload in this order. The first one marked *featured* is the card image in search results.

| # | File | Title | Description |
|---|---|---|---|
| 1 (featured) | `docs/release/promo/beach-sunset.jpg` | Sunset at sea | Complementary Unbound r5.10 under Luxium, on an M1 Max |
| 2 | `docs/release/promo/jungle-shore.jpg` | Jungle shore | Water, reflections and shadows at noon |
| 3 | `docs/release/promo/cherry-grove.jpg` | Cherry grove | Late afternoon on a hillside |
| 4 | `docs/release/promo/savanna-sunset.jpg` | Savanna sunset | Long shadows through the grass |
| 5 | `docs/release/promo/forest-morning.jpg` | Forest morning | Morning sun over the canopy |
| 6 | `docs/release/promo/moonrise.jpg` | Moonrise | Stars and the moon over the plains |
| 7 | `docs/release/promo/vs-iris-beach-sunset.jpg` | Luxium (Metal) vs Iris (OpenGL) | Same world, seed, time and camera. Iris applies the pack's HIGH profile, Luxium its plain defaults. |

All shots: 1920×1080, render distance 12, HUD hidden, not edited. They come from the tour's `promo` stop (`PlaytestTour.promo`), taken with `mod/scripts/promo.sh <label>` (validation off), so they can be retaken after any change.

## Description (paste everything below the line)

---

**Shader packs, on Metal.**

On a Mac, [Metallum](https://modrinth.com/mod/metallum-mc) replaces Minecraft's renderer with Apple's Metal API. That makes the game fast on Apple Silicon, but shader-pack mods like Iris need OpenGL, so they stop working.

**Luxium brings them back.** It's a Fabric mod that sits next to Metallum and runs shader packs from your `shaderpacks/` folder as they are, with no editing and no conversion step.

> ⚠️ **Alpha.** Tested on one Mac so far (M1 Max, macOS 27). Expect bugs and back up your worlds. Reports from other Macs are the most useful thing you can send.

### ⚡ Faster than Iris on the same Mac

Complementary Unbound at default settings, M1 Max, 1708×960, vsync off, render distance 12:

| Scene | Luxium (Metal) | Iris (OpenGL) |
|---|---|---|
| Beach, facing the sea | **90 fps** | 81 fps |
| Overlook of terrain | **96 fps** | 88 fps |
| 360° spin | **95 fps** | 86 fps |

Frame pacing is steadier too: the worst frame was 20 ms with Luxium and 85 ms with Iris. [Method and every run](https://github.com/hecbatista/luxium/blob/main/docs/release/benchmarks.md).

### 🎨 Packs

| Pack | Status |
|---|---|
| Complementary Unbound r5.10 | ✅ Works. Compared with Iris scene by scene, in the Overworld, Nether and End. |
| Complementary Reimagined r5.9.3 | ✅ Loads and renders |
| Bliss, Solas | ❌ Not yet (falls back to vanilla) |
| Others | ❔ Untested. Please report what you find! |

If a pack can't be used, Luxium never crashes: the game runs without shaders and shows a "Luxium: shaders off" notice.

### 📥 Install

1. A Fabric instance for **Minecraft 26.3** (Prism, the Modrinth App, or the Fabric installer).
2. Add **Sodium 0.9.2**, **Metallum 0.0.25** (exactly that version) and **Luxium**. Fabric API isn't needed.
3. Put a shader pack `.zip` in `shaderpacks/`. Complementary Unbound is the best tested.
4. Start the game. Don't install Iris or OptiFine alongside.

The first visit to each world takes a few extra seconds while macOS compiles the shaders. After that, loads are quick.

### ⚙️ Settings

No in-game shader menu yet (it's coming in the next release). Choose the pack in `config/luxium.properties`; pack settings go in `shaderpacks/<pack>.txt`, the same file Iris uses, so your Iris settings carry over.

### 🐞 Known issues

- No in-game shader menu yet; changes need a restart.
- No chunk fade-in, no `customTexture` support, no Distant Horizons.
- Slows down more than Iris at very long render distances.
- Apple Silicon only.

Found a bug? Open an issue on [GitHub](https://github.com/hecbatista/luxium/issues) with your `logs/latest.log`, the pack and its version, a screenshot, and your Mac model.

### 💜 Credits

Built on [Metallum](https://modrinth.com/mod/metallum-mc) by kokodio. [Iris](https://modrinth.com/mod/iris) is the reference for how shader packs behave, and [Vitrail](https://github.com/avpbynf/Vitrail-Shaders) is prior art for packs on a non-OpenGL backend. Full credits and the file-by-file notice are on [GitHub](https://github.com/hecbatista/luxium).

Screenshots use [Complementary Shaders](https://www.complementary.dev/) by EminGT. The pack isn't included; get it from its official page.

<sub>Not affiliated with Mojang, Microsoft, Apple, Iris or OptiFine. Not an official Minecraft product.</sub>
