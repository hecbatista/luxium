# Steps 1–5: skeleton, pack reader, preprocessor, translator, uniforms

Moved out of CLAUDE.md on 2026-11-23 to keep every session small. Read only what a task needs; grep first.

Status (2026-11-20): steps 1–6 done; step 7 (polish) in progress, see "Step 7" below. The pack draws the sky, terrain, water, entities, block entities, particles, weather, the hand and the shadow map, through its own programs (see the step 6 notes below), with the user's pack settings applied. The text right below describes the early steps. `mod/` loads next to Metallum and, at startup, logs each pack in `shaderpacks/`: its shaders folder, world-to-folder map and program-file counts (`pack/PackReader.java`). Two pieces run in tests only, not yet in the game:
- The preprocessor: `pack/IncludeExpander.java`, then `pack/GlslPreprocessor.java` (using JCPP), with `pack/StandardDefines.java`.
- The translator: `glsl/GlslTokenizer.java` → `glsl/Declarations.java` → `glsl/ProgramTranslator.java`, our own code with no glsl-transformer.

Rendering isn't touched.

Roadmap: 1 mod skeleton ✅ · 2 pack reader ✅ · 3 preprocessor ✅ · 4 translator ✅ · 5 uniforms ✅ · 6 render pipeline ✅ (gaps listed in the 6d-4 leftovers and the 6e handoff) · 7 polish (in progress).


Step 4 result: `TranslateRealPackTest` (the scoreboard) translates every vertex+fragment program, then runs glslang for Vulkan 1.2 with only `--auto-map-bindings` (no `-R`, no `--aml`), spirv-cross with Metallum's MSL options, and `xcrun metal`. On Complementary that's **90/90** programs: 30 per world, excluding dh_/clrwl_/voxy programs that need mods we don't have. A reflection check (`spirv-cross --reflect`) found 0 vertex/fragment interface mismatches. Outputs go to `mod/build/translated/<pack>/`, plus `scoreboard.txt`.

Step 5 progress (2026-11-20):
- `glsl/Std140Layout.java` computes byte offsets in `LuxiumUniforms`. `Std140LayoutTest` checks it against glslang via `spirv-cross --reflect`: all 7,815 members in 90 programs match, and so does every block size.
- Complementary reads 92 block values:
  - about 65 built-ins. Each maps to an Iris file in `iris/.../uniforms/` (WorldTime, SystemTime, Celestial, Camera, Common, Viewport, Fog, IrisExclusive, IdMap, Matrix…). Check every Minecraft method in the 26.3 sources: e.g. `Level.getDefaultClockTime()` replaces day time, and moon phase comes from `Camera.attributeProbe()` + `EnvironmentAttributes.MOON_PHASE`.
  - about 25 custom formulas in `shaders.properties` (`uniform.float.x = smooth(...)`). Iris evaluates these with its own `kroppeb.stareval` (part of Iris, LGPL). We'll write a small evaluator and credit the behavior.
  - Per-draw values (entityId, renderStage, the luxium_* matrices…) are set during rendering, so they belong to step 6.
- Game values v1:
  - `uniform/GameUniforms.java` updates once per frame from `mixin/GameRendererMixin` (TAIL of `GameRenderer.extract`). `uniform/UniformWriter.java` packs the values into std140 bytes; missing names get the pack's default or zero and are reported.
  - In game (2026-11-20, validation on, 0 messages): the logged values matched F3 (camera = feet + 1.62, 1708×960, frameTime ≈ 1/fps, worldTime frozen while paused, sunAngle follows vanilla's eased curve).
- Pack formulas (2026-11-20): `pack/PropertiesPreprocessor.java` runs `shaders.properties` through JCPP with the game defines plus `BIOME_*`. `uniform/Formula.java` (parser + typed evaluator, bounded) and `uniform/CustomUniforms.java` (ordering, cycles, dropping) evaluate the formulas once per frame.
  - Complementary: 27 formulas loaded, 4 dropped. `BIOME_ICE_CAVES` doesn't exist in 26.3; `endFlashIntensity` isn't supplied yet.
  - In game they reacted correctly: water, tree shade, walking, frame timing. Coverage rose to **55 of 88**.
  - The remaining 33 are mostly matrices and per-draw values (step 6).
  - Biome ids come from `mixin/BiomesMixin` in Iris's order (FOREST = 8).
  - Wetness half-lives come from `pack/PackDirectives.java` (Complementary: 300 from shadow + composite6).
- Step 5 done (2026-11-20). Added: eyePosition, relativeEyePosition, playerLookVector, cloudHeight, isElytraFlying, heavyFog, lightningBoltPosition, endFlashIntensity, plus heldItemId/heldBlockLightValue for both hands (`pack/ItemIds.java` reads item.properties; 244 items).
  - Checked in game: torch = 44002 with light 14, empty hand = air = 40008.
  - Formulas: 30 of 31 load; only `BIOME_ICE_CAVES` is dropped, as in Iris.
  - Coverage is **64 of 88**, plus `velocity` (Iris 26.3 never supplies it: `HardcodedCustomUniforms` is never called, so we deliberately leave it 0).
  - The 23 still missing are all render-time values for step 6: gbuffer*/shadow* matrices, luxium_* per-draw matrices, entityId/blockEntityId/currentRenderedItemId/entityColor/renderStage/atlasSize, plus fogColor, darknessLightFactor and endFlashPosition, which Iris captures while drawing.
- Earlier coverage for world0/gbuffers_terrain was **34 of 88** values. Still missing: the ~25 pack formulas, matrices (gbuffer*, shadow*, luxium_*), per-draw values, and smaller ones (fogColor, wetness, eyePosition, relativeEyePosition, playerLookVector, velocity, atlasSize, heldItem*, lightningBoltPosition, endFlash*, darknessLightFactor).

Translator v1 only makes programs **compile**. These things are known to matter for drawing correctly and aren't handled yet:
- `gl_FragCoord` origin: GL is bottom-left, Vulkan/Metal are top-left.
- Reversed-Z depth: Iris's `iris_undoRevZ`, and Vitrail converting the depth image.
- `texture()` on samplers without mips: Vitrail pins these to level 0.
- `invariant gl_Position` against Metal fast-math.
- `packUnorm*` on Apple Silicon: Vitrail found it wrong through MoltenVK.
- `gl_VertexID` → `gl_VertexIndex`: this adds base vertex.
- `gl_TextureMatrix` is assumed to be `mat4[8]`.
- .csh/.gsh programs aren't translated.
- Placeholder names (`luxium_ModelViewMatrix`, `luxium_Vertex`…) need real values in steps 5–6.

Tests: `./gradlew test` in `mod/` (JUnit, no game needed). `RealPackTest` preprocesses every pack in `mod/run/shaderpacks/` into `mod/build/preprocessed/`, and then `mod/scripts/check-preprocessed.sh` validates that output with glslangValidator. Result on Complementary (2026-11-20): 219/219 programs preprocess in about 2 s, and 189 pass glslang. All 30 failures are explained in the script header (they need Distant Horizons, Colorwheel, colored lighting, or Iris-injected built-ins).

Step 4 findings (2026-11-20):
- **glsl-transformer, the GLSL parser Iris uses, is AGPL-3.0.** Its README says software that uses it "must itself be licensed as AGPLv3" (Iris has a special exception). Don't add it unless the user chooses AGPL.
- Baseline: `experiments/02-translation-baseline/run.sh` adds crude `#define` stand-ins for the old built-ins. With them, 59/60 world0 programs reach SPIR-V and 59/59 compile to Metal. That run cheats with glslang `-R` (lets loose uniforms through) and `--aml` (numbers each stage's in/out slots separately). The real translator still has to:
  - put loose uniforms in one block per program
  - give in/out slots that match between the two stages by name
  - map fragment outputs from DRAWBUFFERS/RENDERTARGETS
  - add Iris's injected names (`projectionMatrix`, `modelViewMatrix`, `vaPosition`…), which `gbuffers_line` needs
- The highest sampler index is composite1's 14, just under Metal's 16-per-stage limit.

Known gaps: pack settings are read only at load (no profiles, no in-game screen). `dimension.properties` isn't preprocessed yet (Iris does it at `ShaderPack.java:126`), so `#if` lines in it are ignored; the other .properties files are. Credits for borrowed Iris material are in `mod/NOTICE`. The repo has **no LICENSE yet**, and the LGPL material in NOTICE affects which license fits.

Target pack: **Complementary Unbound r5.10**. Its license (Complementary License Agreement 1.8) forbids redistributing or modifying it, so never commit any of its files. The mod must run it unmodified. Under Iris's `MC_OS_MAC` define, the pack forces `COLORED_LIGHTING 0`, which turns off its compute, SSBO and 3D-image features.
