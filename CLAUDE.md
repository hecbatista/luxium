# Luxium

GitHub repo: `hecbatista/luxium` (renamed from `metal-shaders` on 2026-11-20; GitHub redirects the old URL). The local folder is still `metal-shaders/`.

## Goal
Load and run Iris/OptiFine-format shader packs (the `shaderpacks/` format) on Minecraft's **Metal** backend provided by the Metallum mod, on Apple Silicon macOS.

Status (2026-11-20): steps 1–4 of 7 done; nothing draws yet. `mod/` loads next to Metallum and, at startup, logs each pack in `shaderpacks/`: its shaders folder, world-to-folder map and program-file counts (`pack/PackReader.java`). Two pieces run in tests only, not yet in the game:
- The preprocessor: `pack/IncludeExpander.java`, then `pack/GlslPreprocessor.java` (using JCPP), with `pack/StandardDefines.java`.
- The translator: `glsl/GlslTokenizer.java` → `glsl/Declarations.java` → `glsl/ProgramTranslator.java`, our own code with no glsl-transformer.

Rendering isn't touched.

Roadmap: 1 mod skeleton ✅ · 2 pack reader ✅ · 3 preprocessor ✅ (pack's default settings only) · 4 translator ✅ v1 (compiles; correctness comes in step 6) · 5 uniforms ✅ (render-time values come with step 6) · 6 render pipeline · 7 polish.

Step 6 plan (research 2026-11-20). Each sub-step ends with something visible in the game:
- **6a: first pixels.** Our own tiny full-screen pass after the world draws: a mixin right after `this.renderLevel()` in `GameRenderer.render()` (`GameRenderer.java:497`, before the depth clear). This proves the whole chain: our GLSL → `FrontendGpuDevice.compilePipeline(RenderPipeline, ShaderSource, executor)` → `PipelineBuilder` (shaderc) → `MetalDevice.compilePipeline` (Metallum, `MetalDevice.java:130`) → screen.
- **6b: the pack's `final` program.** Translated, with `colortex0` = a copy of the world image and `LuxiumUniforms` filled each frame by `UniformWriter`.
- **6c: render targets and the deferred/composite chain.** Covers colortex formats from the pack, DRAWBUFFERS routing, ping-pong, depthtex copies and noisetex.
- **6d: gbuffers.** The pack's terrain/entity/sky programs replace vanilla's, including Sodium's terrain path (see Vitrail `docs/internals/terrain.md`), plus the gbuffer matrices.
- **6e: shadow pass.**
- **6f: correctness gaps** from step 4.

6a result (2026-11-20, ✅): `render/TestPass.java` draws a see-through gradient after the world, through `mixin/GameRendererRenderMixin` (after `renderLevel()`). `render/LuxiumShaderSource.java` feeds our GLSL to `RenderSystem.getDevice().compilePipeline(...)`.
- In game (validation on, 0 messages) it compiled through shaderc → Metallum, and the user saw **blue at the top, red at the bottom**, under the GUI.
- So the vertical axis keeps the OpenGL convention end to end (`gl_Position.y = +1` is the top), matching Vitrail's note. `gl_FragCoord`'s origin is still untested.
- TestPass is always on until 6b replaces it.

6b result (2026-11-20, ✅ with one gap): `render/FinalPass.java` runs Complementary's translated `final` program after the world draws, replacing TestPass.
- Translator **FULLSCREEN mode** (Iris CompositeTransformer rules): gl_Vertex/gl_MultiTexCoord0 come from `gl_VertexIndex`, color is white, matrices are identity plus a 0..1 projection, and there are no vertex inputs. `ProgramTranslator.modeFor(name)` picks the mode.
- The world image is copied to `colortex0`/`colortex3`, the 1x1 noise texture is neutral, depth/shadow textures are white, and the rest are black.
- `LuxiumUniforms` comes from a `MappableRingBuffer` filled by `UniformWriter` every frame.
- Two bugs found and fixed in game:
  - The game's reflection lists **every declared** sampler (`SPIRVModule.reflect` uses `spvc_compiler_create_shader_resources`), and each gets a Metal slot. With 24 declared, Metallum's MSL failed with "'sampler' attribute parameter is out of bounds: must be between 0 and 15". Fix: the translator drops sampler declarations a stage never mentions, and refuses more than 16 per stage. Complementary's maximum is 14 (composite1).
  - Placeholder textures were uploaded while a render pass was open, which raised "Close the existing render pass before performing additional commands". Fix: resolve every input before opening the pass.
- Result: 0 validation messages. A readback measurement (every 5 s, logged as "Final pass measurement") shows about 98% of pixels changed by about 8/255 per channel, consistent with IMAGE_SHARPENING 5.
- The underwater distortion is real but only about ±1 px (0.0007 × screen width), too small to see or to separate in that measurement. The user saw a faint shimmer.
- Gap: the uniform byte layout isn't yet proven value-by-value on the GPU; composite passes (6c) depend on it heavily.
- Every texture the pipeline lists must be bound before drawing (`FrontendRenderPass.java:486-490`).

6c plan:
- 6c-1 ✅ buffer plan.
- 6c-2: run the chain (two halves per buffer, the flip rule, DRAWBUFFERS routing, depth copies, noisetex from the pack, `program.X.enabled`, and `scale.composite4 = 0.4`).
- 6c-3: wire final to the chain.

Until 6d (gbuffers) the chain runs on vanilla's image instead of the pack's gbuffer data, so the picture will look partly wrong.

6c-1 result (2026-11-20):
- `pack/RenderTargetPlan.java` folds `const` buffer directives in Iris's order (`PackDirectives.foldOrder`; final counts with the gbuffers).
- Complementary world0 has 14 buffers. Formats: colortex0 RG11B10_FLOAT; 1 RGBA8_SNORM (from RGB8_SNORM); 2 RGBA16_FLOAT (from RGB16F); 3, 5, 12, 19 RGBA8; 4 RGBA8_SNORM; 6 RGBA8 (from RGB8); 7, 8 RGBA16_FLOAT; 13, 18 R8; 14 RGBA16_UNORM (from RGB16).
- Not cleared each frame: 1, 2, 4, 5, 7, 14, 18, 19.
- Iris allows 32 buffers (`IrisLimits.MAX_COLOR_BUFFERS`).
- Metal has no 3-channel pixel formats, so promoted buffers must be cleared with alpha 1 and written color-only (write mask) to read like OpenGL.
- Complementary's chain in world0: deferred1 (0,5,4) → composite (7,1) → composite1 (0,5) → [composite3 (0), off when WORLD_BLUR == -1] → composite4 (3, at 0.4 scale) → composite5 (3) → composite6 (3,2) → composite7 (3, FXAA) → final.

Facts for step 6:
- **Full-screen passes:** vanilla `PostPass` uses `renderPass.draw(3, 1, 0, 0)` with no vertex buffer. Pack composite vertex shaders expect a quad (`gl_Vertex`, `gl_MultiTexCoord0`), so we need a quad vertex buffer or a special vertex prologue.
- **Binding:** uniform blocks and textures are bound **by name** (`RenderPass.setUniform(name, buffer)` / `(name, view, sampler)`). The pipeline declares them via `RenderPipeline.Builder.withBindGroupLayout`. Shaders are referenced by `Identifier` (`withVertexShader`/`withFragmentShader`) and resolved through the `ShaderSource` given to `compilePipeline`.
- **Multiple render targets:** `withColorTargetState(index, …)` in the API; Metallum handles lists of color attachments (`MetalCommandEncoder.java:271`).
- **Vitrail's 26.3 seam:** `common/src/mc26.3/java/dev/vitrail/render/GraphicsApi.java` and `AheadCompiler.java` (`device.compilePipeline(pipeline, source, Runnable::run).join().finishCompile()`). Its Vulkan-only mixins are for compute and storage images, which we don't need with `MC_OS_MAC`.
- Vitrail `docs/internals/game-graphics-api.md` warns:
  - Precompile every pipeline with its real source before the first pass, or it silently compiles with no source.
  - Scene depth is cleared a few lines after the seam, so copy it there.
  - Detect resizes by comparing sizes every frame, not with the resize event.

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

Known gaps: user-changed pack settings aren't applied yet; only the defaults written in the pack are used. `shaders.properties` and `dimension.properties` aren't preprocessed yet (Iris does it at `ShaderPack.java:126` and in `PropertiesPreprocessor`), so `#if` lines in them are ignored. Credits for borrowed Iris material are in `mod/NOTICE`. The repo has **no LICENSE yet**, and the LGPL material in NOTICE affects which license fits.

Target pack: **Complementary Unbound r5.10**. Its license (Complementary License Agreement 1.8) forbids redistributing or modifying it, so never commit any of its files. The mod must run it unmodified. Under Iris's `MC_OS_MAC` define, the pack forces `COLORED_LIGHTING 0`, which turns off its compute, SSBO and 3D-image features.

## Layout
```
metal-shaders/
├── CLAUDE.md                  this file
├── .mcp.json                  project-scoped MCP server: apple-docs
├── .claude/skills/axiom-graphics/   Metal/GLSL→MSL reference skill (copied from CharlesWiltgen/Axiom, MIT)
├── mod/                       OUR mod, display name **Luxium** (Fabric, id `metalshaders`, package io.github.hecbatista.metalshaders)
├── branding/                  Luxium icon (`make_icon.py` draws it and copies icon.png into the mod)
├── experiments/               small out-of-game tests (01-glsl-to-metal: GLSL → SPIR-V → MSL → metallib)
└── references/                read-only reference code; do not edit, build our own code elsewhere
    ├── metallum/              kokodio/metallum: the Metal backend we build on (MIT)
    ├── iris/                  IrisShaders/Iris: the reference shader-pack implementation, OpenGL (LGPL-3.0)
    ├── vitrail/               avpbynf/Vitrail-Shaders: runs OptiFine packs on the Vulkan backend (LGPL-3.0)
    └── minecraft-26.3-sources/  decompiled Minecraft 26.3 (Mojang names), extracted from Loom's genSources jar
```

### Pinned references (as cloned 2026-11-20)
| Repo | Branch | Commit | Why this branch |
|---|---|---|---|
| metallum | master | 467d312b | default branch |
| iris | **26.3** | 671a5c87 | Iris's default branch is `26.1`, which is wrong for us. `26.3` matches Metallum: Iris `build.gradle.kts` has `MINECRAFT_VERSION "26.3"` and `sodium-fabric:0.9.2+mc26.3`. (`26.3-new` exists but moved to Sodium 0.9.3-beta.5.) |
| vitrail | main | 7a3cdcbe | builds for 26.2 by default; use `-Pminecraft=26.3` (see `versions/26.3.properties`) |
| axiom-graphics skill | main | ffafd3b3 | recorded in `.claude/skills/axiom-graphics/.source-commit` |

### Versions (from `references/metallum/gradle.properties` and `build.gradle`)
- Minecraft 26.3, Fabric Loader 0.19.5, Loom 1.16-SNAPSHOT (resolved to 1.16.3), Sodium `mc26.3-0.9.2`, metal-jvm 0.1.0
- Java 25 (`options.release = 25`); local JDK is Temurin 25.0.5 (`/usr/libexec/java_home -v 25`)
- Gradle 9.4.1 via the wrapper

### Where things are in each repo
- **Metallum:** `src/main/java/com/metallum/{render,mixin,compat}`; mixin configs in `src/main/resources/` (`metallum.mixins.json`, plus `.sodium`, `.distanthorizons`, `.shine` variants).
- **Iris:** `common/src/main/java/net/irisshaders/iris/`. `shaderpack/` handles pack loading and parsing, `pipeline/` the render pipeline, `uniforms/`, `gl/` OpenGL wrappers, `shadows/`.
- **Vitrail:** `common/src/main/java/dev/vitrail/` (`pack/`, `glsl/`, `render/`, `uniform/`). Read `docs/` first: `translation.md`, `internals/`, `pack-format.md`, `why.md`, `compatibility.md`. Vitrail solves the same problem for Vulkan that we're solving for Metal, so it's the closest prior art.

## Rules

### Names: Mojang official only
- Minecraft 26.x ships **unobfuscated**. This was verified: the genSources jar has 7,302 files and no obfuscated names like `class_1234` or `a.java`.
- Use Mojang's official names only. **Never Yarn names** (`class_XXXX`, `method_XXXX`, or Yarn-style names from old tutorials).
- **Before using any Minecraft class, method or field, find it in `references/minecraft-26.3-sources/`** (for example `grep -rn "class LevelRenderer" references/minecraft-26.3-sources`). If you can't find it there, it doesn't exist in 26.3.
- The original sources jar is at `references/metallum/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-5309bbdd7f/26.3/minecraft-merged-5309bbdd7f-26.3-sources.jar`. To regenerate it, run `./gradlew genSources` in `references/metallum`.

### Verify, don't trust
- Never guess versions, URLs, class names or APIs. Read the actual file or doc and cite it.
- **Metallum's README describes the project as "vibecoded as hell".** Treat its code as something to verify against the Minecraft sources and Apple's Metal docs, not as ground truth.
- Vitrail's Modrinth page says AI tools did most of its typing too. Same rule: verify before copying any of its behavior.
- For Metal APIs, check Apple's docs (the `apple-docs` MCP server) or `man MetalValidation`. The `axiom-graphics` skill is a guide, not a primary source.

### Licensing
- **Iris and Vitrail are LGPL-3.0.** Any code, tables, grammars or formulas borrowed from them must be credited **file by file**: the source project and class, what was adapted, and what changed. Follow the format of `references/vitrail/NOTICE` (it credits Iris this way). Keep a `NOTICE` file in our own code once anything is borrowed.
- Metallum and the Axiom skill are MIT; keep their copyright notice if any code is copied.

## Automated in-game screenshots (client game tests)
Claude can test the game without the user. Fabric API's client game tests (`fabric-client-gametest-api-v1`, Fabric API `0.166.2+26.3`, gametest source set only, not a mod dependency) start the game, build a scene with commands, take screenshots and quit:
```bash
cd mod
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1 ./gradlew runClientGameTest
```
- Test: `mod/src/gametest/java/io/github/hecbatista/metalshaders/test/ScreenshotTest.java`.
- Screenshots land in `mod/build/run/clientGameTest/screenshots/` (854×480); view them with the Read tool. The run takes about 25 s.
- The test's game folder is cleared every run, so the test copies `mod/run/shaderpacks/*` into it first.
- Spectator mode and a hidden HUD keep shots clean. In Survival the player falls between teleports.
- `eula = true` in `mod/build.gradle`: the user agreed to the Minecraft EULA for this on 2026-11-20.
- API, read from the jar: `context.worldBuilder().create()`, `singleplayer.getServer().runCommand(...)`, `context.waitTicks(n)`, `context.takeScreenshot(name)`, `context.runOnClient(mc -> ...)`, `assertScreenshotEquals(...)`.

## Running and testing
Always test with Metal's API validation and GPU shader validation on. To run **our mod** (with Metallum and Sodium):
```bash
cd references/metallum && ./gradlew build   # once: mod/ uses this local jar (commit 467d312)
cd ../../mod
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1 ./gradlew runClient
```
- Why the local Metallum jar and not Modrinth's `metallum-mc` 0.0.25 (2026-10-10): the local clone has two more commits, including `5bee03e fix: synchronize MetalDevice.getOrCompileFunction()`, a thread-safety fix in shader compiling.
- A healthy start of our mod logs `(metalshaders) Luxium loaded. Metallum version: 0.0.25` (the log said "Metal Shaders loaded" before the 2026-11-20 rename; confirmed 2026-11-20: title screen normal, zero validation messages). The game directory is `mod/run/`, so packs go in `mod/run/shaderpacks/`.
- Closing the game with `kill` makes Gradle print `BUILD FAILED ... exit value 143`. 143 means SIGTERM, so this is expected and not an error.
- To run plain Metallum without our mod, use the same command from `references/metallum`.
- Source: `man MetalValidation` (`/usr/share/man/man1/MetalValidation.1`). Any non-zero `MTL_DEBUG_LAYER` enables API validation; any non-zero `MTL_SHADER_VALIDATION` enables GPU validation. More `MTL_DEBUG_LAYER_*` and `MTL_SHADER_VALIDATION_*` options are documented there.
- **Confirmed working (2026-11-20).** The game process got both variables even though the long-running Gradle daemon didn't have them (checked with `ps eww <pid>`), so `--no-daemon` isn't needed. Metal printed `Metal API Validation Enabled` and `Metal GPU Validation Enabled` at startup. If those two lines are missing from a run, validation is off.
- Baseline (2026-11-20): unmodified Metallum, run under API and GPU validation, reached the title screen, created and loaded a Creative world, flew around for about 40 seconds, and saved and quit. There were **zero Metal validation messages** and the world rendered normally (user-confirmed). Any validation failure after our changes is therefore ours.
- `Requested post effect does not exist: minecraft:end_of_frame` is a **vanilla** warning, not Metallum's. `GameRenderer.update()` (`GameRenderer.java:433`) requests `END_OF_FRAME_POST_EFFECT` every frame, and the vanilla client jar ships no `post_effect/end_of_frame.json`. This full-screen hook may be relevant to the shader work later.
- To check whether the game is running, use `pgrep -f 'java.*devlaunchinjector'`. A bare `pgrep -f devlaunchinjector` also matches any script that mentions the word.
- Ignore an HTTP 429 from `sessionserver.mojang.com` at startup: that's Mojang rate-limiting the offline dev player's profile lookup.
- A healthy start logs `(metallum) Metal device: Apple M1 Max` and `Using graphics backend Metal`. The `ClassNotFoundException` warnings for Distant Horizons and `com/bloom/...ShineRenderBackend` are Metallum's optional compatibility hooks and are harmless.

## Tools available
- JDK: Temurin 25.0.5
- `xcrun metal` (Metal Toolchain 27B47, compiler 32023.922)
- `glslangValidator` 16.6.0 (GLSL → SPIR-V, and GLSL validation)
- `spirv-cross` 1.4.363.0 (SPIR-V → MSL and other targets)
- MCP: `apple-docs` (kimsungwhee/apple-docs-mcp, project scope in `.mcp.json`)
- Skill: `axiom-graphics` (see `skills/metal-migration-ref.md` for GLSL→MSL tables, `skills/metal-migration-diag.md` for black screens and frame capture)

## Constraints
- The disk was about 13 GB free at setup. Check `df -h ~` before large downloads or builds.
