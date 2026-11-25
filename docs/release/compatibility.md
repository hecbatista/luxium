# Pack compatibility log

One entry per pack tested, newest first. Packs are pinned in `mod/scripts/testpacks.tsv` and tested
one at a time with the flow in `mod/scripts/PACK-TESTING.md`:
1. offline scoreboard (`./gradlew packCorpus`);
2. game test on the tour stops "build zoo,zoo,beach" with Metal API and GPU validation;
3. the same stops under Iris 1.11.7 (OpenGL) for comparison.

Earlier results (Complementary Unbound r5.10, Complementary Reimagined r5.9.3, Bliss v2.1.2,
Solas v3.9) are in `docs/release/alpha.md`, "Fails safe".

## Photon v1.4 (2026-11-25): renders, matches Iris on the zoo and beach stops

- Offline: **165/165** reach Metal (128/171 before; the 6 `gbuffers_voxels`/`shadow_voxels`
  programs belong to the Photonics mod, which Iris doesn't know, and are now left out like other
  mod programs).
- In game: 0 validation messages, no crash; 70 formulas load, 0 dropped (60 and 10 before).
- What was wrong, in the order found:
  - Didn't compile: a pack function named `length_squared` (ambiguous with Metal's); struct
    varyings (`flat out OverworldFogParameters fog_params`) took one location, so the next one
    overlapped; `in vec4 vaColor` declared twice; deferred4 reads 17 textures (Luxium allowed 16).
  - The game refuses struct varyings and 3D samplers outright. Struct varyings are now split into
    one varying per member (`glsl/StructVaryings.java`). Raw 3D textures (`texture.deferred.depthtex0
    = scattering.dat TEXTURE_3D RGB16F 32 64 32 ...`: the atmosphere LUT and two cloud noises) are
    loaded as 2D atlases of bordered slices and the pack's 3D lookups rewritten to blend two
    slices (`glsl/VolumeTextures.java`, `render/CustomTextures.java`). As in Iris, such a texture
    binds only where a program declares that sampler as 3D (it gets its own name): Photon's
    `composite1` reads `colortex0` as the scene while `composite` reads it as 3D noise, which first
    gave an all-red picture.
  - Metal allows 16 samplers per function, counting every sampler argument and every built-in
    sampler it samples with. A program with more than 16 textures now gets built-in samplers
    only, so only the ones it filters with count (`mixin/metallum/MetalSamplerSlotsMixin.java`,
    `MetalRenderPassSamplerMixin.java`); deferred4 filters 9 of its 17.
  - `vec3` fragment outputs: Metal won't build a pipeline that writes fewer components than the
    target has; they're widened to vec4 (`glsl/FragmentOutputs.java`). An output declared past the
    pass's RENDERTARGETS writes nowhere, as in OpenGL (it collided with the coverage output).
  - The default `SH_SKYLIGHT` turns on a compute pass (`deferred4_a.csh`). Luxium can't run
    compute, so an on/off option whose only job is switching on a compute program is now switched
    off, and the pack takes its own path without it (`pack/PackOptions.java`, logged).
  - Formulas: Iris's `biome_category`, `temperature`, `rainfall` uniforms and the `CAT_*`/`PPT_*`
    defines were missing (`uniform/BiomeIds.java`).
  - Clouds missing toward the horizon: `size.buffer.<buffer>` wasn't read, so the half- and
    quarter-size cloud and fog buffers were full size. Targets are now allocated at that size
    (Iris's TextureScaleOverride rules) unless geometry writes them.
- Under Iris: **Iris 1.11.7 can't run Photon's defaults on this Mac** ("Implementation limit of 16
  active fragment shader samplers exceeded, fragment shader uses 17"), and it can't run compute on
  macOS's OpenGL either. Both runtimes were compared with `CLOUD_SHADOWS=false` and
  `SH_SKYLIGHT=false` (`mod/run/testpacks-iris/photon/photon_v1.4.zip.txt`), which Iris runs.
- Side by side (`mod/build/compare/photon/`): sky, clouds (the same shapes straight up), sun light,
  shadows, water, terrain, entities, block entities, glowing sign text, End portal and gateway,
  nether portal and translucents match.
- Differences left:
  - Iris shows a little more haze toward the horizon and a bright fog bank by the zoo; cloud
    undersides are a little greyer under Iris. Cause not found; Photon's volumetric fog pass gives
    almost no fog at noon on both.
  - The beach-sprint shot: Luxium is further along the sprint (the runner timing difference seen
    with BSL and Bliss). Mob poses differ by timing.
- Not compared: night, caves, rain/snow, the Nether and the End; `CLOUD_SHADOWS` on (Luxium runs
  it, Iris on macOS can't).
- Effect on Complementary Unbound: still 90/90; the zoo stops are unchanged (`mod/build/compare/cu-photon/`).

## Bliss v2.1.2, Chocapic13 edit (2026-11-23): works, matches Iris on the zoo and beach stops

- Offline: 244/244 programs preprocess; **103/103** reach Metal (48/112 before; the scoreboard now
  leaves out the 9 programs shaders.properties switches off by default, as the game does).
- In game: 0 validation messages, no crash. 62 formulas load (14 before).
- What was wrong, in the order found:
  - Didn't compile. Iris macros were missing: `MC_HAND_DEPTH` (0.125), `MC_NORMAL_MAP` and
    `MC_SPECULAR_MAP` (Bliss declares `FlatNormals` only under `MC_NORMAL_MAP`), and `MC_RENDER_QUALITY`.
    Names legal in OpenGL GLSL but not after translation: a parameter named `sampler` (a Vulkan
    type), a function `bias()` (clashes with `metal::bias`), and `float tanh(float)` under
    `#version 120` (a built-in from 1.30 on). A global `vec3 sunVec` in one stage collided with
    the uniform `sunVec` of the other.
  - Water and hand-water wouldn't link: an unused fragment input (`Flashing`) that the vertex
    stage never writes. These inputs are now dropped.
  - Every frame was black. `sunPosition`, `moonPosition`, `shadowLightPosition` and `upPosition`
    weren't supplied (Iris's CelestialUniforms; Complementary never needed them), and 48 formulas
    were dropped: vector types (`uniform.vec3.sunVec = vec3(...)`), `.0`-`.3` components, matrix
    elements (`gbufferProjection.1.1`), the frame's matrices as inputs, `thunderStrength`,
    `currentPlayerHealth`, and the literal `1000000000000000000`.
  - No water near the camera: `alphaTest.gbuffers_water=false` wasn't read as "off", so the
    near water (alpha 1/255) was discarded.
  - End portals and gateways were invisible, and sign text was blank: the game's own draws land
    in a buffer Bliss never reads. They're now drawn by the pack, as Iris does (the End portal as an
    entity_solid draw for gbuffers_block; text by gbuffers_entities_translucent, or
    gbuffers_block_translucent for a block entity). Text submits now keep the ids of the block
    entity or entity that made them, so a sign's text gets the sign's blockEntityId.
  - Glass, ice, slime and honey cast no shadows: the `shadow` sampler was always the map without
    translucents. As in Iris it's now `shadowtex0`, unless the program also reads `watershadow`.
  - The `normals` and `specular` samplers read Iris's defaults (flat normal, zero specular)
    instead of opaque black.
- Side by side with Iris (`mod/build/compare/bliss/`): sky, clouds, sun light, shadows (tinted
  ones too), water, terrain, entities, block entities, signs, End portal and gateway, nether
  portal and translucents match.
- Differences left:
  - The beach-sprint shot: Luxium is further along the sprint (over water, where Iris is still on
    the sand runway). The same runner timing difference as BSL; not rendering.
  - Mob poses and particle positions differ by timing.
  - Flicker on still-camera clips is about twice Iris's on the zoo (0.12% vs 0.06%) and the
    portal (0.14% vs 0.06%). The heat maps show only moving mobs, fire and portal particles; the
    still beach is 0.02% (Iris 0.005%).
- Not compared yet: night, caves, rain/snow, the Nether and the End; TAA quality in motion.
- Effect on Complementary Unbound (same stops against Iris, 0 validation messages, formulas
  unchanged; `mod/build/compare/cu/`):
  - Better: the End portal and gateway now show Complementary's own starfield, as under Iris
    (it was the game's, dimmer).
  - **Worse, open:** glowing sign text is pale blue-white, where Iris (and Luxium before this
    change, when the game drew text) shows lime; normal sign text matches. The text is now served
    by gbuffers_block_translucent with the sign's id, the light pair (240, 240) and the lightmap
    matrix are Iris's, so Complementary's `lmCoord.x > 0.999` glowing branch should be taken; why
    it doesn't look so isn't known yet. Next step: dump the text variant
    (`LUXIUM_DUMP_SHADERS`) and compare it with Iris's patched TEXT_BE program (pipecheck).
- Not supported: Bliss's optional features that need compute shaders and custom images (its
  floodfill light, `IS_LPV_ENABLED`, off by default); its translucent-entities option
  (`TRANSLUCENT_ENTITIES`, off by default) doesn't compile under Iris either (`entityId` undeclared).

## Solas v3.9 (2026-11-23): renders; no Iris reference possible on macOS

- Offline: 163/163 programs preprocess; **65/65** reach Metal (62/65 before: 3 ×
  `'MC_RENDER_QUALITY' : undeclared identifier`, its sharpen filter in `final`).
- Fixes:
  - `pack/StandardDefines.java`: `MC_RENDER_QUALITY` and `MC_SHADOW_QUALITY`, 1.0 as Iris's
    StandardMacros.
  - `render/CustomTextures.java`, `PackChain.input`: `customTexture.<sampler>=<png>` (Solas:
    `milkyWayTex`), bound by name in every program after the render targets, as Iris's
    `IrisSamplers.addCustomTextures`. Raw and `minecraft:` forms are still skipped.
  - `uniform/GameUniforms.java`, `mixin/FogRendererMixin.java`, `UniformWriter`: `gl_Fog`
    (color, density, start, end, scale), `fogStart`, `fogEnd`, `fogDensity`, from FogData's
    environmental start/end as Iris and Sodium take them. `gl_Fog` used to be declared but
    left at zero; Solas's "modded fog" then saw `gl_Fog.end = 0` and fogged every pixel fully
    (first run: daytime scenes solid pale white, night near-black). Complementary reads
    `gl_Fog` only for lava fog and the End's dragon check, which now get Iris's values too.
  - `render/EntityTakeover.java`: the albedo rename no longer maps `texture` → `Sampler0`. It
    renamed every identifier, so `texture()` calls became `Sampler0()` and Solas's shadow
    entity variants didn't compile (`'Sampler0' : can't use function syntax on variable`).
    NameFixes already turns a sampler named `texture` into `gtexture`.
- In game (`gametest.sh pack-solas2`, stops build zoo, zoo, beach, night): loads from the
  root `shaders/` folder, 29 formulas loaded, 0 dropped, **0 validation messages**, every
  program and variant compiles. Unit tests 112 pass; Complementary Unbound still 90/90.
- Compared with vanilla on the same stops (`mod/build/compare/solas/`): geometry, entities,
  block entities, signs, portals and translucents all there; sun shadows, volumetric clouds,
  coloured block light in the night hut, reflective water, and the Milky Way texture with
  the pack's round moon at night (offset from vanilla's by `sunPathRotation=-40`).
- Flicker: still clips pass (zoo 0.089%, night 0.032% / 0.043%, beach still 0.0005%, sky
  0%). The beach sprint (moving camera) and the Nether portal block (animated) flag, as
  expected; vanilla flags on the same moving clips.
- **No Iris comparison:** Iris 1.11.7 can't run Solas on macOS.
  - Default settings: Iris's `shaders.properties` preprocessing treats the bare
    `#define VX_SUPPORT` as an on option, so it creates the voxel images, and
    `GlImage.clear` → `glClearTexImage` (GL 4.4) fails on Apple's GL 4.1; Iris disables
    shaders. (Solas's GLSL turns VX_SUPPORT off under `MC_OS_MAC`, but the properties don't see that.)
  - With `VX_SUPPORT=false` in the pack settings: Apple's GL compiler crashes (SIGSEGV in
    `libGLProgrammability`, `glpLLVMGetFunctionGlobalVariableUse`) linking the deferred programs.
- Differences and open items:
  - Solas has no `gbuffers_skybasic`/`gbuffers_skytextured`; Iris falls back to
    `gbuffers_basic`/`gbuffers_textured`, Luxium's sky takeover doesn't (it also takes only
    colortex0, and `gbuffers_textured` writes 0 and 1). No visible effect in the Overworld:
    `deferred1` replaces every sky pixel with its own sky. The Nether and End aren't checked.
  - Night vision brightens the scene only a little (Solas adds a small ambient term, by design).
  - The End gateway beam shows in vanilla's end-portal shot, not in Luxium's; the beam lasts
    ~10 s after placing and Luxium reached that stop 6 s later, so likely timing. Unverified.
  - The beach-sprint shot ends over water (vanilla: on the sand), the tour-speed difference
    seen with BSL.
  - Not compared: caves, rain/snow, the Nether and the End.

## Complementary Reimagined r5.10 (2026-11-23): works

- Offline: 219/219 programs preprocess; **90/90** reach Metal.
- In game: loads, 0 validation messages, no crash. 30 formulas loaded, 1 dropped
  (`inIceCaves`: `BIOME_ICE_CAVES` doesn't exist in 26.3; Iris drops it too).
- Side by side with Iris on the zoo and beach stops: sky, clouds, water, terrain, entities,
  block entities, signs and frames, glass and portals match.
- Differences:
  - The End portal and End gateway starfield is clearly dimmer than under Iris (fewer, fainter
    stars). Already seen with Unbound (`docs/history/step-7.md`, "The End portal's starfield is a
    little dimmer"); not specific to this pack.
  - Mob poses and the beach-sprint position differ by timing between the two runners.

## BSL v10.1.8, after the formula fix (2026-11-23): renders, matches Iris

- Fixes: `uniform/Formula.java` gains Iris's remaining scalar functions (`frac`, `fmod`,
  `remainder`, `sign`, `tan`/`asin`/`acos`/`atan`/`atan2`, `torad`/`todeg`/`radians`/`degrees`,
  `exp2`/`exp10`/`log2`/`log10`, `mix`, `edge`, `between`, 3-argument `equals`).
  `uniform/GameUniforms.java` supplies `eyeBrightnessSmooth` (Iris's smoothing, default half-life
  10; the pack's `eyeBrightnessHalflife` directive isn't read yet) and `bedrockLevel`.
- BSL: 27 formulas loaded, **0 dropped** (8 before); every block value supplied except the
  matrices that are filled in per draw.
- Game test: 0 validation messages, no crash. Side by side with Iris, the zoo and beach shots now
  match: midday sky and light, shadows, water, clouds, entities, block entities, portals and
  glass. Unit tests 111 pass; Complementary Unbound still 90/90.
- The beach-sprint shot differs (Luxium over water, Iris on the sand runway) because the
  Luxium player is about 10 ticks further along the sprint (Luxium frame 20 ≈ Iris frame 30),
  not because sand is missing. A tour timing difference between the two runners, not rendering.
- Not compared yet: night, caves, rain/snow, the Nether and the End.

## BSL v10.1.8, after the translator fixes (2026-11-23): renders, light wrong

- Fixes (`glsl/NameFixes.java`, `ProgramTranslator.FUNCTION_RENAMES`): a sampler named `texture`
  becomes `gtexture` (Iris's rule); C++/Metal words used as names (`new`, `and`, `fast`,
  `float2`...) become `luxium_<name>`, with uniform values still looked up by the pack's name;
  Iris's remaining legacy texture functions (`texture2DGradARB`, `texelFetch2D`, `textureSize2D`...).
- Offline: **85/85**. Complementary Unbound still 90/90; unit tests 109 pass.
- In game: loads, 0 validation messages, no crash; shadows, water, clouds, entities, block
  entities, portals and translucents all draw.
- Wrong: the whole scene is lit like sunset (orange sky and clouds, dim warm light) where Iris
  shows midday. Cause: BSL's `timeAngle` chain in shaders.properties uses `frac()`, which
  `uniform/Formula.java` doesn't have, so 8 formulas are dropped and `timeAngle` and
  `timeBrightness` stay 0. Also unsupplied: `eyeBrightnessSmooth`, `bedrockLevel`.
- Unclear: in the beach-sprint shot the sand blocks in front of the player are missing under
  Luxium; recheck after the light fix.

## BSL v10.1.8, first run (2026-11-23): falls back to vanilla

- Offline: 185/185 programs preprocess; **38/85** vertex+fragment pairs reach Metal.
  - 44 × glslang: `'texture' : can't use function syntax on variable`. Every gbuffers fragment
    program declares `uniform sampler2D texture;` (the old OptiFine name for the albedo atlas), so
    the GLSL 4.x function `texture()` is hidden by the variable.
  - 3 × `xcrun metal`: `deferred` has a local `bool new = ...`; `new` is a C++ keyword, so MSL
    rejects it (`expected unqualified-id`). spirv-cross passes the name through.
  - Hidden behind those: `gbuffers_water` calls `texture2DGradARB` (ARB_shader_texture_lod), which
    the translator doesn't rename yet.
- In game: `Shader pack BSL_v10.1.8.zip can't be used: its program deferred: the device returned
  no pipeline`; Metallum logs the `new` error. Vanilla rendering with the notice, **0 validation
  messages**, no crash.
- Under Iris: renders as BSL (blue-grey tint, soft shadows, volumetric haze).
- Hand check: renaming `texture` → `gtexture` (as Iris's `CommonTransformer` does), `new` →
  another name, and `texture2DGradARB` → `textureGrad` makes every failing world0 program compile
  through glslang, spirv-cross and `xcrun metal`. Luxium's albedo binding already accepts
  `gtexture`. Whether it then renders correctly is untested.
