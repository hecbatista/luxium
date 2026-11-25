# Luxium vs LeadLight

Written 2026-11-23 from LeadLight's README, release notes and source on GitHub ([ohjey/leadlight](https://github.com/ohjey/leadlight), last commit 2026-10-22, v0.2.0), and from Luxium's own docs. Nothing here comes from running LeadLight: every LeadLight claim is its README's, not a test of ours.

Found in the Metallum Discord's #general, where LeadLight is the shader-pack mod people already recommend ("If you are doing it with metallum use mod leadlight instead of iris").

## At a glance

| | **Luxium** 0.1.0-alpha.1 | **LeadLight** 0.2.0 |
|---|---|---|
| Released | 2026-11-23, GitHub pre-release | 2026-10-19 (0.1.0); 0.2.0 on 2026-10-23 |
| Where | GitHub (Modrinth listing drafted) | GitHub only: 61 jar downloads across 3 releases. Modrinth's `leadlight` is an unrelated stained-glass mod by secret_online. |
| Author | hecbatista | OhJey (also makes Metallum Extra) |
| Origin | Written from scratch; Iris and Vitrail as references, credited file by file | A fork of Vitrail 0.13.0-beta (Vulkan) with the GPU layer swapped to Metal |
| Size | 82 Java files, ~0.6 MB | 559 Java files, ~5.3 MB (mostly Vitrail) |
| License | LGPL-3.0-only | LGPL-3.0-only |
| Minecraft | 26.3 | 26.2 and 26.3, one jar each |
| Metallum | exactly 0.0.25 | exactly 0.0.24 (26.2) / 0.0.25 (26.3) |
| Other deps | Sodium 0.9.2; **no Fabric API** | Sodium 0.9.x, Fabric API |
| Hooks into Metallum | 2 mixins, on `MetalCrossShaderCompiler` and its `Compiled` record | Many: up to 8 colour targets per pass on 26.2, per-stage sampler slots, samplers past 16, 1D/3D textures, MSL 3.1, thread-safe function cache, pipeline eviction |
| Picking a pack | `config/luxium.properties`, restart | In-game page under Video Settings; `I` opens it, `K` toggles shaders, `R` reloads |
| Pack settings | `shaderpacks/<pack>.txt` (Iris's file), restart; option list written beside it | Vitrail's in-game settings screen; same Iris file |

## Rendering features

| Feature | Luxium | LeadLight |
|---|---|---|
| Gbuffers, shadows, deferred/composite, final | ✅ | ✅ |
| Overworld, Nether, End | ✅ | ✅ |
| **Compute shaders** (`.csh`) | ❌ not translated | ✅ cross-compiled to MSL kernels |
| Custom images / SSBOs (`image.*`, `bufferObject.*`) | ❌ | ✅ |
| Geometry shaders (`.gsh`) | ❌ | Pass-through ones folded away; others refused |
| **`MC_OS_MAC`** | **Defined**, as Iris does on a Mac | **Not defined, on purpose** (`EngineDefines.osSymbol`, inherited from Vitrail) |
| Complementary's coloured lighting | Off (because of `MC_OS_MAC`) | On, by its README; tested "with coloured lighting" |
| Distant Horizons | ❌ | ✅ |
| Continuity / CTM + PBR resource packs | untested | ✅ tested with Patrix |
| ETF / EMF / Fresh Animations | untested | ✅ tested, with emissive-layer fixes |
| `customTexture.*` | ❌ (alpha known issue) | presumably, via Vitrail (not checked) |
| Chunk fade-in | ❌ | not checked |
| Pipelines compiled ahead of first draw | ✅ compile pool + Metal cache warm-up (7g) | ✅ on 26.3; on 26.2 hitches on first draw |
| GPU timing per pass | ❌ | ❌ (same reason: Metallum has no timestamps) |

`MC_OS_MAC` is the most important design difference. Iris defines it because Macs run on Apple's old OpenGL driver, and Complementary reacts by turning off its coloured lighting, image stores, per-buffer rain blending and samplers in `composite1`. LeadLight's comment argues that none of those limits apply on Metal, so it defines no OS symbol at all. The cost of that choice is that a pack can't tell it's on a Mac. Luxium followed Iris so the picture would match Iris on a Mac. LeadLight's choice gives more features, but only because it has compute and storage images to back them.

## Packs each one claims

| Pack | Luxium | LeadLight (README "Tested") |
|---|---|---|
| Complementary Unbound r5.10 | ✅ compared with Iris scene by scene | not listed |
| Complementary Reimagined r5.9.3 | loads and renders | ✅ 26.2 and 26.3, default and coloured lighting |
| Photon | untested | ✅ v1.3b (26.2), 1.4 (26.3) |
| BSL 10.1.8 | untested | ✅ |
| Bliss 2.1.2 | ❌ falls back (`MC_HAND_DEPTH`, `texelFetch2D`) | ✅ |
| Solas | ❌ v3.9 falls back (`MC_RENDER_QUALITY`) | ✅ 3.7b (26.2) |
| Noble, Sildur's Vibrant, MakeUp UltraFast | untested | ✅ (26.2; Noble and Bliss on 26.3 too) |

LeadLight's 26.3 testing is narrower than its 26.2 testing: Overworld by day only, with five packs.

## Where Luxium is ahead, or could be

- **Measured performance.** Luxium is 1.1× faster than Iris at 12 chunks and 1.4× at 5 chunks, with better worst frames (`benchmarks.md`). LeadLight publishes no numbers, and one Discord user said their "fps lowk tanks with shaders in leadlight". A Luxium-vs-LeadLight run on the same tour would settle it. It's the strongest claim Luxium could make, and it isn't made yet.
- **Validation-clean.** Luxium is developed under Metal API and GPU validation, with zero messages as the bar. LeadLight's README doesn't mention validation.
- **Lighter dependencies.** It needs no Fabric API and hooks only Metallum's shader compiler, so a Metallum update is less likely to break it.
- **Complementary Unbound r5.10.** Luxium is checked against Iris on the newest Complementary. LeadLight lists only Reimagined r5.9.3.
- **Active development.** Luxium is being worked on now. LeadLight's last commit was 2026-10-22.

## Where LeadLight is ahead

- Compute shaders, custom images and SSBOs. Most of the pack list depends on them: Photon's lighting, Complementary's floodfill, Noble.
- Many more packs working, on both 26.2 and 26.3.
- An in-game pack picker and settings screen. Luxium's is planned for the next release (`menus-plan.md`).
- Works with Distant Horizons, Continuity and ETF/EMF.
- A month's head start, and the Metallum community already recommending it. It isn't on Modrinth, so a Luxium Modrinth listing would be the first shader loader for Metallum there.

## What this means for the release copy

- Don't call Luxium the first, or the only, way to run shader packs on Metallum, in `modrinth.md`, `posts.md` or the README. The What-is-it line "shader-pack mods like Iris don't support [Metal]" is still true, but readers in the Metallum server will know about LeadLight.
- Lead with what can be checked: validation-clean, measured faster than Iris, light dependencies. If a benchmark shows Luxium is faster than LeadLight, that becomes the headline.
- Saying how Luxium differs from LeadLight will probably be needed, given that the 7:10 PM post in #general invited questions.

## Why LeadLight may be faster (source reading, 2026-11-23)

The first, contaminated render-distance-5 runs had LeadLight 30–40% ahead. The design differences found in its source, most likely first (paths under `src/main/java/dev/leadlight/`):
1. **Reflection buffers at half size.**
   - LeadLight honours `size.buffer.*` (`pack/model/TargetSize.java`) and claims `IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE` (`EngineDefines.java:187-193`), so Complementary draws `colortex1` and `colortex7` at `REFLECTION_RES` = 0.5.
   - Luxium does neither. Its reflection passes cover 4× the pixels (already noted in `docs/history/step-7.md`).
2. **A tighter shadow cull.**
   - LeadLight's comments measure the shadow terrain pass at 4.12 ms of a 7.05 ms frame (`render/ShadowAmortisation.java:16-19`).
   - It culls with the camera frustum swept along the light, plus a cube at the pack's shadow distance (`render/ShadowCullPlan.java`, `sodium/ShadowCull.java`).
   - Luxium's light frustum is the whole shadow-map box (7c: "never drops a caster"), with face culling off. That fits Luxium losing more speed than Iris as render distance grows.
3. **Fewer render passes.**
   - It keeps one Metal pass open across solid, cutout, sky and entities (`render/GeometryHold.java`).
   - It does every owed clear at the head of the frame in one batch (`render/ColorTargets.java:660-682`; they measured 2 to 6 passes saved per frame).
4. **One uniform ring buffer for the whole composite chain**, written once per frame (`render/PackChain.java:2522-2545, 3059`). Luxium's CPU time is about 1 ms a frame (7f), so this matters less.

Not a cause at defaults: shadow-map reuse across frames (`ShadowAmortisation`) is off by default. `COLORED_LIGHTING` defaults to 0 under both mods.

## To settle by running LeadLight (not done)

1. Benchmark: Luxium and LeadLight 0.2.0 (`+mc26.3`), both on Modrinth's Metallum 0.0.25, with the same `PlaytestTour` benchmark stop. Complementary Unbound r5.10 at default settings, render distance 5 and 12. LeadLight would need its Fabric API.
2. The same shots under Metal validation, to see whether LeadLight is validation-clean.
3. The picture difference that `MC_OS_MAC` makes: LeadLight with coloured lighting vs Luxium without.
