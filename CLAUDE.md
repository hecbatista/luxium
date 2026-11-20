# Luxium

GitHub repo: `hecbatista/luxium` (renamed from `metal-shaders` on 2026-11-20; GitHub redirects the old URL). The local folder is still `metal-shaders/`.

## Goal
Load and run Iris/OptiFine-format shader packs (the `shaderpacks/` format) on Minecraft's **Metal** backend provided by the Metallum mod, on Apple Silicon macOS.

Status (2026-11-20): steps 1–5 done and step 6 well along: the pack's sky, opaque terrain and deferred/composite/final chain draw in game (see the step 6 notes below). The text right below describes the early steps. `mod/` loads next to Metallum and, at startup, logs each pack in `shaderpacks/`: its shaders folder, world-to-folder map and program-file counts (`pack/PackReader.java`). Two pieces run in tests only, not yet in the game:
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

6c-2 progress (2026-11-20): `render/PackChain.java` runs the full chain after the world.
- Two halves per buffer with the flip, DRAWBUFFERS routing to multiple attachments, and promoted buffers written color-only.
- Clears per plan (default clear colors: colortex1 white, others 0; promoted ones get alpha 1).
- The "seed" pass copies vanilla's image into colortex0; depthtex0/1/2 are a copy of vanilla's depth (D32_FLOAT).
- noisetex is loaded from the pack (`texture.noise`).
- `program.<folder>/<name>.enabled` and `scale.<name>` come from the preprocessed shaders.properties. Scale goes into the full-screen projection, because Metallum always sets a full viewport.
- `pack/OptionDefaults.java` gives .properties files the pack's setting defaults (a simple version of Iris's option discovery), so `#if WORLD_BLUR == -1` switches composite3 off.
- **Debug view:** `LUXIUM_STOP_AFTER=<pass|seed> LUXIUM_SHOW=<colortexN|depthtex0>` (environment variables, inherited by the game) shows one buffer on screen. NaN or infinity shows as magenta.

Result in the game test: 8 passes compile and run (deferred1, composite, composite1, composite4 @0.4, composite5–7, final), 14 buffers, 0 validation messages, **but the screen is black**.
- Per-pass debug: the seed colortex0 is correct, and **deferred1 already writes 100% NaN** to colortex0.
- Prime suspect: the gbuffer matrices aren't supplied yet (all zero), so position reconstruction divides by zero.
- depthtex0 looks all black. Minecraft 26.x renders **reversed-Z** (far = 0), and Complementary expects the OpenGL convention.
- Fixed (2026-11-20):
  - `uniform/RenderMatrices.java` captures the matrices in `GameRendererRenderMixin`, at `RenderSystem.setProjectionMatrix` inside `GameRenderer.renderLevel`, using MixinExtras `@Local`.
    - gbufferModelView = `CameraRenderState.viewRotationMatrix`.
    - gbufferProjection = the final projection with view bobbing, passed through Iris's `MatrixUtils.undoRevZ` (zZeroToOne branch; Metallum's `DeviceInfo.isZZeroToOne` is true).
    - Each also comes inverted and as last frame's value.
  - depthtex0/1/2 are converted once per frame to `1 - depth` in an R32_FLOAT texture (Iris's DepthTransformer does `1 - read`; converting the image as Vitrail does also covers reads through helper functions).
- Result: **0% NaN at every pass** (was 100% from deferred1). The land shot shows the scene with Complementary's fog and sky look, plus a thin white streak near the top-left (unexplained).
- The underwater shot is almost black. Likely cause: the stand-ins (no gbuffers_water data). Re-check after 6d.

6d plan (research 2026-11-20; Vitrail `docs/frame.md`, `docs/internals/terrain.md`, `entities.md`). Each piece should end visible in the game test:
- **6d-1: gbuffer plumbing + sky.**
  - Cut the chain in three as Vitrail does: begin/prepare before the world, deferred after opaque features (before translucents), composite + final after the level.
  - Add a way to draw one vanilla "family" with the pack's program into colortex targets (several attachments, the game's depth), plus per-draw values (renderStage etc.).
  - Prove it on the sky (`gbuffers_skybasic`/`skytextured`).
- 6d-1 design (Vitrail `frame.md` "The seed", `sky-and-shadows.md` "The sky"):
  - Pack gbuffer passes draw **during** the world into colortex current halves, with the **game's depth** attached. So the buffer clears move to **frame start**, before `renderLevel`.
  - Each pack gbuffer program also writes its fragment depth into a **coverage mask** (R32_FLOAT, cleared to 2.0 = "nothing"). The sky writes the far plane (reversed-Z far = 0).
  - The **seed** then fills colortex0 with the game's image only where the game drew in front of (or instead of) the pack: `mask` vs world depth, equal within a few ULP. Apple GPUs differ by about 1 ULP on sloped surfaces, in a 32-px tile pattern.
  - Sky facts:
    - 8 pieces in 4 vertex formats; declare exactly the bound format's elements.
    - `renderStage` tells the pieces apart.
    - The sun/moon/stars/band carry their own model-view (the day rotation), separate from the camera's.
    - The sky pipeline has **no depth state**; the disc is a triangle fan.
    - It's all 8 pieces or none, and a boss bar asking for world fog (the ender dragon) skips the sky entirely.
  - Order: plumbing first (no visible change = regression check), then the sky.
  - Plumbing ✅ (2026-11-20):
    - `PackChain.beginFrame` (from `GameRendererRenderMixin`, before `renderLevel`) clears the buffers and the coverage mask.
    - The seed discards where `mask` matches the raw world depth within 4 ULP (`floatBitsToInt`).
    - Game test: picture identical to before, 0 validation messages.
    - Next: the sky.
  - Sky ✅ (2026-11-20):
    - `render/SkyTakeover.java` + `mixin/SkyRendererMixin.java` (MixinExtras `@WrapOperation`) open vanilla's sky pass on colortex0.
    - Vanilla's SKY/STARS/SUNRISE_SUNSET pipelines are swapped for gbuffers_skybasic and CELESTIAL for gbuffers_skytextured. Each is compiled with `ProgramTranslator.translateForFormat` for that pipeline's vertex format, and copies the pipeline's topology, blend and depth state.
    - The per-piece model-view and color come from `DynamicGpuData.writeTransform`, and renderStage from the piece's debug label. The sun/moon texture (Sampler0) is bound under the pack's albedo names. The End isn't taken over.
    - Format mode adds a vertex epilogue `z' = (w - z) / 2` (OpenGL clip depth onto the shared reversed-Z buffer).
    - `UniformWriter` writes mat4 arrays (luxium_TextureMatrix).
  - **Big bug found and fixed:** the chain ran after `GameRenderer.renderLevel()`, but `render3dHud` clears the depth (`GameRenderer.java:689`) before drawing the hand. So the chain always saw depth 0 (CPU readback: 100% zeros), and composites treated everything as sky. The chain now runs before `render3dHud`. This also fixed the near-black underwater shot.
  - Debug: `LUXIUM_SHOW=rawdepth|maindepth`, `LUXIUM_SHOW_SCALE=<n>`, `LUXIUM_DEPTH_PROBE=1` (CPU readback of the game's depth).
  - Result: land shows the scene under Complementary's sky gradient (the white streak is gone), underwater is visible and tinted, and there are 0 validation messages.
- **6d-2 result (2026-11-20, ✅): solid and cutout terrain drawn by the pack's `gbuffers_terrain` through Sodium.**
  - Sodium facts (0.9.2, decompiled with the Vineflower jar in the Gradle cache: `find ~/.gradle/caches -name 'vineflower*.jar'`):
    - On Metal, Sodium's `DrawBackend` is `OPENGL` (it only picks the VK backends for `VulkanDevice`), so draws go through `GLDrawContext` → `RenderPass.multiDrawIndexed`.
    - The pass is handed in: 26.3 opens one "Main" pass in `LevelRenderer.addMainPass`'s lambda (`lambda$addMainPass$0`), and `executeSolid` draws the opaque terrain first.
    - `DefaultChunkRenderer.render` always calls `pass.pushConstants` (20 bytes: region offset, time, region id). Its GLSL takes the `#ifdef VULKAN` push-constant branch (glslang defines VULKAN), and Metallum supports push constants.
    - 26.3's `PipelineBuilder` binds vertex inputs **by element name**, with the location from the shader. So a shader may declare a subset of the format, and Vitrail's 26.2 "silent shift" trap doesn't apply.
  - Pieces:
    - `sodium/LuxiumChunkVertex.java` (36 bytes = Sodium's 20, written the same way, + block id, mid-texcoord, normal, tangent) is used **all session** via `mixin/sodium/ChunkMeshFormatsMixin` (also covers ArenaAggregator's stride).
    - `ProgramTranslator.translateForTerrain` (Iris SodiumTransformer decode; alpha test per pass; extra `luxium_Coverage` output = `gl_FragCoord.z`).
    - `render/TerrainTakeover.java` + `mixin/LevelRendererTerrainMixin` (draws Sodium's OPAQUE group into its own pass on colortex0/6/4 + coverage + the game's depth, just before "Main" opens, then skips the game's opaque call) + `mixin/sodium/DefaultChunkRendererMixin` (swaps the pipeline, binds the UBO and textures after `setPipeline`).
    - Block ids: `pack/BlockIds.java` + `sodium/BlockStateIds.java` + `mixin/sodium/BlockRendererMixin` (thread-local id per `renderModel`). Complementary: 418 ids, 35,551 states (stone=10080, oak_leaves=10009).
    - `separateAo` and block ids are read at startup and again at pack load; if they changed, `mc.levelExtractor.allChanged()` rebuilds the chunks (needed in the game test, which copies the pack in after startup).
    - `sampler2DShadow` now goes through the "arithmetic road" (plain sampler2D + `luxium_shadowCompare`, four gathered texels LEQUAL), because the API has no compare samplers.
    - Fixed: `PropertiesPreprocessor` treated `# If you want…` as `#if`; it now uses Iris's exact directive test.
  - Game test (scene now has a leaves block for the cutout pass): both passes draw, **0 validation messages**.
    - With `LUXIUM_NO_TERRAIN=1` (new debug switch) the stone is flat grey; with the pack it's warm and sunlit, and the leaves get Complementary's bright, waving foliage.
    - `LUXIUM_SHOW=colortex4` shows correct world-space normals.
    - Unit tests pass, including `TerrainTranslationTest` (Complementary terrain → glslang → spirv-cross → `xcrun metal`); scoreboard 90/90.
  - Known gaps:
    - `oldLighting=false` isn't applied: Sodium still bakes the per-face shade into AO (tops are 1.0, so the stone floor test can't see it).
    - Tag entries (`%tag`) in block.properties are skipped.
    - `at_midBlock` is zero.
    - `fogColor` is still 0.
    - The view-bob split isn't done.
- **6d-3 result (2026-11-20, ✅): water and translucent terrain drawn by the pack's `gbuffers_water`.**
  - The chain is cut in two around the world (Vitrail's "after opaque" / "after level"):
    - `mixin/LevelRendererTerrainMixin.luxium$afterOpaque` wraps `executeClassicTransparency` (LevelRenderer.java:461). It closes the game's "Main" pass (closing is idempotent), then:
      - `PackChain.drawDeferred`: converts depth (also kept as depthtex1/2 = before translucents), seeds colortex0, runs the deferred passes.
      - `TerrainTakeover.drawTranslucent`: Sodium's TRANSLUCENT group with `gbuffers_water`.
      - Then opens a pass on the **translucent catch** (an RGBA8 image cleared to transparent, with the game's depth) for the rest of the game's translucent phase (translucent features, clouds, weather, world border).
      - `PackChain.composeCatch` then composes it onto colortex0 (premultiplied "over"; the game's TRANSLUCENT blend onto a clear image leaves premultiplied colour).
    - `PackChain.draw` (before render3dHud) re-converts depthtex0 (now with translucents) and runs composite + final. If the cut wasn't reached (e.g. improved transparency / OIT), it runs the whole chain as before.
  - Water pipeline:
    - Writes colortex0 and colortex3, with no coverage output (`ProgramTranslator.Terrain(alpha, coverage)`).
    - Blend comes from `blend.gbuffers_water[.<buffer>]`, else vanilla TRANSLUCENT. Alpha test from `alphaTest.gbuffers_water` (Complementary: > 0.0001).
  - Block ids: fluids via `mixin/sodium/DefaultFluidRendererMixin` (fluid bit set). Translucent quads the sorter keeps carry the id on `ChunkVertexEncoder.Vertex` (`mixin/sodium/ChunkVertexMixin` + `sodium/LuxiumVertexData`). Complementary: water = 32000, glass = 32008.
  - **Bug found and fixed:** gbuffer programs' input textures were resolved at frame start. The water then read the half of colortex5 from *before* deferred1's flip, and Complementary's gbuffers_water discards where `gaux2.a` (cloud depth) is nearer, so every water/glass pixel vanished. Inputs are now resolved right before each terrain pass opens.
  - Debug additions:
    - `LUXIUM_SHOW=depthdiff`: |depthtex0 − depthtex1|, ×`LUXIUM_SHOW_SCALE` (default 1000).
    - `LUXIUM_SHOW=<buffer>:alt`: the other half; after a pass that wrote the buffer, that's what the pass read.
  - Game test: the glass box and the water are the pack's; the stone floor shows through the water and the gold/log blocks through the glass. 0 validation messages. Unit tests pass (72), scoreboard 90/90.
  - Known gaps:
    - Game features drawn after the cut but outside `executeClassicTransparency` (entity outlines, see-through text, always-on-top gizmos) land in the game's image and are overwritten by final.
    - Translucent features drawn before translucent terrain in vanilla now depth-test against the water (hidden behind it, not seen through it).
    - The caught layer is the game's finished sRGB colour composed onto the pack's colortex0.
    - Improved transparency (OIT) isn't taken over.
    - Water inputs that the chain hasn't written this frame (gaux4 = colortex7) read last frame's values, as under Iris.
- **6d-4 handoff (start here next session): entities, block entities, hand, particles, weather, clouds.**
  - Read Vitrail `docs/internals/entities.md` ("the door is a group of draws") and frame.md "The seed".
  - Solid entities are drawn by `featureFrame.executeSolid(renderPass)` inside "Main", right after the (skipped) opaque terrain call in `executeSolid`. Translucent ones are drawn in `executeClassicTransparency` (now drawn onto the catch).
  - They need their own vertex format (`DefaultVertexFormat.ENTITY`, DefaultVertexFormat.java:40; elements by name, through `ProgramTranslator.translateForFormat`), plus `entityId`/`blockEntityId`/`entityColor` per draw.
  - Entities drawn by the pack must write the coverage mask too, so the seed keeps them.
- **6e: shadow pass.**
- Metallum's only Sodium mixin picks the graphics API (`metallum.sodium.mixins.json`: `SodiumPreferredGraphicsApiMixin`). Sodium already draws through the game's RenderPearl API, so Vitrail's Sodium-level hooks should apply on Metal too.

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
