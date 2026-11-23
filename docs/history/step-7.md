# Step 7: polish (7a onward)

Moved out of CLAUDE.md on 2026-11-23 to keep every session small. New slices are appended here, not to CLAUDE.md.

Step 7 (polish), agreed order with the user (2026-11-20): pack settings → Nether/End → off-screen shadow casters → improved transparency (OIT) → small 6d-4 leftovers → performance → housekeeping. Check in with the user before each slice. **Every slice's final pass includes the frame-sequence flicker check** (user request 2026-11-20; see "Automated in-game screenshots").
- **7a result (2026-11-20, ✅): pack settings the user changes.**
  - `pack/PackOptions.java` follows Iris's option rules (credited in NOTICE):
    - Value options are `#define NAME value // [allowed]`. On/off options are `#define NAME` / `//#define NAME`, and count only if some file in the same include group tests the name with a bare `#ifdef`/`#ifndef`. That rule covers `const bool` options too.
    - `const` options are limited to Iris's directive names.
    - Include groups are the weakly connected parts of the include graph, which starts at the program names Iris knows. So `clrwl_*` and `voxy_*` aren't starts.
    - A name with two different defaults is dropped as ambiguous.
  - The settings file is Iris's: `shaderpacks/<pack file name>.txt` (e.g. `ComplementaryUnbound_r5.10.zip.txt`), `NAME=value` lines, read once when the pack loads (restart the game after editing). Luxium only reads it; Iris also rewrites it.
    - On/off options take `true`/`false`. Value options take any value, as in Iris.
    - Unknown names and bad values are logged as "Pack settings ignored".
  - Applying: each file's lines are edited before include expansion (`PackPreprocessor.preprocessProgram(..., options)`, an `IncludeExpander.FileSource` wrapper). `.properties` files get `PackOptions.propertyDefines`: options that are on, then the game's defines, then every value option (Iris's order).
  - Every start writes `shaderpacks/<pack>.luxium-options.txt`: every option with its current value, default, allowed values, and the pack's English names from `lang/en_US.lang`.
  - Complementary: 407 options (172 on/off, 235 with values), none ambiguous. The `.properties` files test only two names that aren't options (`IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE`, `DISTANT_HORIZONS`); the old reader didn't define them either.
  - Game test: run twice.
    - Defaults: picture unchanged.
    - `LUXIUM_TEST_SETTINGS="T_SATURATION=0.00;NOT_A_SETTING=1"`: the whole picture is grey (land and underwater), and the unknown name is logged as ignored.
    - 0 validation messages, 86 unit tests pass (new: `PackOptionsTest`), scoreboard 90/90.
    - Flicker check: land worst 0.012% of pixels (edges of the animated mobs), underwater 0.009%. A fake one-frame glitch is caught.
  - Not done: profiles (`profile.*`), an in-game settings screen, and reloading without a restart. A reload needs every `prepare` (PackChain, takeovers, shadow targets) to free and rebuild its GPU state.
- **7b result (2026-11-20, ✅): the Nether and the End.**
  - Each world loads its own folder (`MetalShadersClient.loadWorld`), checked every frame in `onFrameExtracted`. Iris's rules (`PackInfo.worldKey` + `folderFor`):
    - A world named in `dimension.properties` uses its folder.
    - Otherwise the End's sky means the End's folder and the Overworld's sky the Overworld's.
    - Otherwise the folder of `*`, else the shaders/ root.
  - Switching unloads the last world first (`PackChain.unload` → `SkyTakeover`/`TerrainTakeover`/`EntityTakeover`/`ShadowTargets.unload`: close every pipeline, ring buffer and texture, forget programs). Closing right away is safe because Metallum's command buffers retain what queued frames use (`commandQueue.commandBuffer()`, retained references); the resize path already relied on that.
  - Pack-wide values (settings, item/entity ids, formulas, preprocessed shaders.properties) load once in `loadPackValues`. Only the world folder's programs reload.
  - Load time per world: about 1.8 s when macOS's Metal shader cache is warm, about 9 s the first time a folder's shaders are ever compiled.
  - `program.<folder>/<name>.enabled` (`pack/ProgramSwitches.java`, Iris's BooleanParser rules) now applies to every program, not only the composites. A switched-off program counts as missing, so the fallbacks pick another.
    - Example: `SHADOW_QUALITY=-1` switches off `shadow` in every world.
    - Under that setting the empty sky below the horizon turns dark, because Complementary also turns off its light shafts (`LIGHTSHAFTS_ACTIVE` needs `SHADOW_QUALITY > -1` in the Overworld). That's the pack's own behaviour, not a bug.
  - The End sky: vanilla's END_SKY pipeline (POSITION_TEX_COLOR, quads) is drawn with `gbuffers_skytextured` (Iris IrisPipelines); End sky and End flash are stage CUSTOM_SKY (3). `SkyRendererMixin` now also wraps `DynamicGpuData.writeTransform(Matrix4f)` (the End sky's transform, white).
  - Sky coverage fix: the coverage mask is cleared to "nothing" at frame start, and `PackChain.skyDrawn` marks the background as the pack's only when the pack's sky pass actually opens. Before, a frame where the game skips the sky drew a black background. The game skips it with no sky in the world (Nether), a boss's world fog (the dragon), lava or powder snow fog, or blindness/darkness.
  - Game test:
    - Shot 6: a carved Nether room (lava, glowstone, soul fire) in the pack's orange haze.
    - Shot 7: an End platform at (0, 72, −205) under the pack's End sky, outside the dragon's 192-block arena (`EnderDragonFight.validPlayer`).
    - Shot 8: back in the Overworld (world0 reloaded), matching shot 1.
    - 0 validation messages; 91 unit tests pass (new `WorldFoldersTest`); scoreboard 90/90.
    - Flicker: land 0.012%, underwater 0.030%, Nether 0.033% (all on the animated soul fire), End 0%.
    - With `SHADOW_QUALITY=-1`: every world's shadow program is switched off and the floor loses the glass grid's shadow.
  - Gaps:
    - The world folder's programs compile on the render thread while the loading screen is up.
    - Only one world's programs are kept: going back compiles again (warm cache: about 1.8 s).
    - Nether/End weather, clouds and DH/voxy programs aren't checked.

- **7c result (2026-11-20, ✅): shadows from casters the camera can't see.**
  - The shadow pass's terrain now comes from the light's own Sodium render list, as Iris does on the same Sodium 0.9.2 (`render/ShadowCulling.withLightLists`, around `TerrainTakeover.drawShadowTerrain`):
    - Each region gets a second render list and batch map (`mixin/sodium/RenderRegionMixin` + `sodium/ShadowRegion`), swapped in for the shadow draw. Every place Sodium empties a batch empties all maps, including the three in `RenderRegionManager.uploadResults` (`RenderRegionManagerMixin`, `require = 3`).
    - Sodium's `renderOutOfGraph` walk fills the light's lists (`RenderSectionManagerAccessor` invoker), then `SodiumWorldRenderer.prepareChunkRendering` fills its batches.
    - Afterwards the camera's `renderLists`, `taskLists` and `renderTree` are put back and its batches prepared again. `prepare` writes the shared `shouldDraw` array, and `renderTree` also drives entity culling.
  - The walk's frustum is the shadow map's box (`ShadowCulling.LightBox`): a section's corners go through `shadowProjection × shadowModelView` (camera-relative, as the shadow programs use them) and must overlap the unit cube. `intersectAab` returns −2/−1/0 as Sodium's tree walk reads JOML. Iris's default "advanced" frustum is tighter; the box never drops a caster.
  - Debug: `LUXIUM_SHADOW_CAMERA_LIST=1` keeps the camera's list (the old behaviour). The log line "Shadow terrain: N regions seen by the light, M by the camera" appears every 5 s.
  - Game test shot 9:
    - The scene is a floor at y = 160 around x = 100, with a stone-brick tower at x 118–121 behind the camera, at time 1500.
    - The camera looks west and down. The tower's long morning shadow lies across the floor; with `LUXIUM_SHADOW_CAMERA_LIST=1` the floor has none.
    - Go there before the `fill`s: a fill only works in loaded chunks (the first try filled nothing).
    - 0 validation messages; 94 unit tests pass (new `ShadowCullingTest`); scoreboard 90/90.
    - Flicker: land 0.014%, underwater 0.019%, Nether 0.045% (soul fire), End 0%, shot 9 sequence 0%.
  - Still open:
    - Entity casters are still only the camera-visible ones (the level's prepared solid features).
    - Sections never built because the camera hasn't been near them have no mesh to cast with. Sodium builds from the camera's task lists; the light's task lists are dropped, as in Iris.
    - Sodium is now declared in `fabric.mod.json` (`"sodium": "~0.9.2"`, user-approved 2026-11-20): Luxium's terrain and shadows are drawn through Sodium and its mixins target 0.9.2's internals.

- **7d result (2026-11-20, ✅): Improved Transparency (the game's OIT path).**
  - Following Iris (`MixinDisableFabulousGraphics`), the option is switched off while a pack is in use, and the graphics preset becomes Custom. The pack does its own transparency, and Iris never takes over `LevelRenderer.executeOit` either.
  - `mixin/LevelExtractorMixin` checks at the start of `LevelExtractor.allChanged` (runs whenever the option changes, from the menu or the debug key) and `onResourceManagerReload`. `MetalShadersClient.keepImprovedTransparencyOff` also runs once when the pack loads. "A pack is in use" means `shaderpacks/` has one (`packPresent`, read at startup and at load).
  - Game test shot 10 turns the option on from the client, asserts it's off again 40 ticks later (`computeOnClient`), and matches shot 1's view. Log: "Improved Transparency switched off".
  - 0 validation messages; 94 unit tests pass; scoreboard 90/90; flicker check passes (land 0.010%, underwater 0.025%, Nether 0.027%, End and shot 9 0%).

- **7e result (2026-11-20, ✅): small leftovers (current_player, currentRenderedItemId, translucent particles).**
  - `render/DrawIds.enterEntity(EntityRenderState)` follows Iris (MixinEntityRenderDispatcher): the camera's own player (`AvatarRenderState.id` = `getCameraEntity()` id) uses `minecraft:current_player` if the pack lists it (Complementary: 50017), and a converting zombie villager uses `minecraft:zombie_villager_converting`.
  - `currentRenderedItemId` follows Iris's item chain:
    - `ItemModelResolverMixin` (appendItemLayers) notes the item on the render state (`ItemStackRenderStateMixin`).
    - `ItemLayerRenderStateMixin` makes its id current while a layer is submitted (`DrawIds.enterItem`/`leaveItem`, nested inside an entity's ids).
    - `ItemFeatureRendererSubmitMixin` keeps the ids on the record.
    - `ItemFeatureRendererMixin` applies them while `prepareSubmit` builds the draws (`DrawIds.buildWith`, read in `notePrepared`).
    - A block item uses its block's block.properties id and sets blockEntityId 1; other items use item.properties (model component name first). Special-renderer items (shields, chests) go through the usual `submitModel` path with the item's ids current.
  - Game test log: hand sword `item_cutout (gbuffers_hand): currentRenderedItemId 45012`; shot 5b (third person) `entity_translucent: entityId 50017` and its sword `entityId 50017, currentRenderedItemId 45012`.
  - Shot 3 now spawns campfire smoke (the game's TRANSLUCENT particle layer). Log: `pipeline/translucent_particle -> gbuffers_particles_translucent`.
  - 0 validation messages; 95 unit tests pass; scoreboard 90/90; flicker check passes.
  - Not done:
    - One value per draw: different items merged into one draw (same texture atlas and pipeline, e.g. several dropped items) share the first one's id. Iris and Vitrail put ids on each vertex; doing that means a longer ENTITY vertex format, like LuxiumChunkVertex for terrain.
    - The first-person arm has no entityId (it isn't drawn through EntityRenderDispatcher.submit; Iris's isn't either).
    - `gbuffers_line` (block outlines) is still the game's (visible through the catch). Complementary's line program builds thick lines from `gl_VertexID` and Iris's core-profile names (`vaPosition`, `vaNormal`, `projectionMatrix`), which the translator doesn't supply yet.

- **7f result (2026-11-20, ✅): performance, measured first.**
  - `render/PerfStats` (`LUXIUM_PERF=1`) records frame time (between two `GameRenderer.extract`s, so it includes waiting for the GPU) and Luxium's CPU time per step. The game test then measures 5 s at land/underwater/Nether/End (`Perf [label]: ...` in the log), with the frame cap, vsync and the AFK limiter (`inactivityFpsLimit` = MINIMIZED, else 30 fps after a minute) off. `LUXIUM_TEST_NO_PACK=1` runs the test without a pack.
  - GPU time can't be measured through the game's API: Metallum's "timestamps" (`MetalDevice.getTimestampNow`) are CPU clock readings.
  - The test runs usually reach the display's 120 Hz cap (8.33 ms) with and without the pack, probably vsync when the window isn't in front. The one uncapped run reached about 270 fps with the pack at 854×480.
  - Findings:
    - Luxium's CPU work is about 1 ms per frame: shadow 0.25, opaque terrain 0.3, deferred 0.1, composites+final 0.25, game values 0.05.
    - No frame over 50 ms while holding still.
    - First-use pipeline compiles froze the game for 10–100 ms each (gbuffers_entities/hand ones about 85 ms), and again after every world switch.
  - Fix 1, pre-compiling at world load (`EntityTakeover.prewarm`, called from `loadWorld`):
    - The list is `PREWARM`, keyed by family and made of `RenderPipelines` *constants*. Names aren't unique: there are three `pipeline/item_cutout`, and `RenderPipelines.requiredPipelines()` is a map by name that keeps only the last.
    - It covers the arm and held item, common mobs, chests, armour, dropped items, particles and rain: 23 compiles, about 1.3 s, so world load is about 3 s.
    - Compiling everything a family could serve was 136 pipelines and 7–15 s per world (most of it OIT pipelines that are never drawn), so that was dropped.
    - Left at first use: spider eyes and beacon beams (11–21 ms).
    - Each compile re-runs GLSL→SPIR-V in the game's `PipelineBuilder` (no cache) plus Metallum's backend. Compiling on a background thread isn't safe on the public Metallum 0.0.25 (the local build's `synchronize getOrCompileFunction` fix).
  - Fix 2: Sodium's block-face culling is kept off only while the light's batches are filled (`ShadowCulling.keepAllFaces`; also for the camera's batches under `LUXIUM_SHADOW_CAMERA_LIST`). The camera's batches cull faces again, so it draws fewer. Pictures unchanged (shots 1 and 9).
  - 0 validation messages; 95 unit tests pass; flicker check passes (land 0.013%, underwater 0.018%, Nether 0.039% soul fire, End and shot 9 0%).
  - Not done:
    - Keeping several worlds' programs (Iris keeps one pipeline per world; returning to a world recompiles, about 3 s).
    - Caching SPIR-V across pipelines.
    - Measuring GPU cost (needs an uncapped, focused window or Xcode's Metal tools).

- **After 7f (2026-11-21, from the "Luxium step 7: polish" session; uncommitted until the 2026-11-23 consolidation): flashes, grey horizon, sun.**
  - The user reported occasional flashing in normal play, a sun that moved oddly, a grey strip at the horizon, and grey streaks flickering on sand.
  - Tools added:
    - `render/FlashProbe.java`, the flash recorder (bursts of screenshots while playing);
    - walk, turn and creep modes in `ScreenshotTest` (`LUXIUM_TEST_WALK_ONLY`, `LUXIUM_TEST_BOX_ONLY`);
    - a moving-camera mode in `FlickerCheck` (`--tiles`).
  - **Grey horizon strip: fixed.** Two things Iris does that Luxium didn't:
    - Iris clears the starting image to the game's fog colour every frame. Luxium cleared it to black.
    - Iris draws an extra horizon shape around the player before the sky, so the pack also shades the sky below the horizon line.
    - Luxium now does both (`SkyTakeover`, `SkyRendererMixin`, `PackChain`) and supplies the real `fogColor` (`mixin/FogRendererMixin`, `GameUniforms`); it was always 0 before. The ocean fades into the sky, and the grey "walls" around high test platforms are gone.
  - **Sun: one real difference fixed.** Complementary tilts the sun's path by `sunPathRotation` (−40°). Iris applies that tilt to the sun, moon and stars it draws; Luxium tilted only the lighting and shadows, so the visible disc wasn't where the light came from. It's unconfirmed whether this was the "crazy" movement the user saw.
  - **Sand and grass flicker: narrowed down, not fixed.**
    - Shadow edges shift between frames, but the shadow map itself is stable while turning.
    - Prime suspect: the pack's TAA, which smooths a per-frame shadow-edge pattern using the previous frame. With TAA off, the flicker in the glass-box test disappears.
    - Still open: whether Luxium feeds TAA something slightly wrong (previous-frame matrices, camera position), or Complementary looks the same under Iris. Use the Iris runner (`iris-compare/`) to settle it.
  - Checks at the time: 0 validation messages, 95 unit tests, scoreboard 90/90, the five flicker clips pass.

- **Playtest and fixes (2026-11-21 to 23, consolidated into main on 2026-11-23).**
  - **The playtest.** `PlaytestTour` played a generated world under validation (70 shots, 26 flicker clips), and every suspect was compared against the same tour without the pack. Confirmed bugs and their fixes:
    - **Crash with many entity types** (commit 5f712fc): per-draw uniform buffers were shared across program variants of different block sizes. The `finally` that closes the window hid the real exception behind "debug groups left open". Each variant now owns its buffers, and `inWindow`/`closeWindowAfter` keep the original failure.
    - **Enchantment glint** (5f712fc): `TextureMat` is the glint scroll in vanilla GLINT pipelines (applied to the item's texture only under `APPLY_TEXTURE_MATRIX`). Glint draws now use `mat4(1.0)`, plus the pack's inline glint (`IRIS_INLINE_GLINT`). Left open: the separate `pipeline/glint` pass (trimmed armor, patterned shields) isn't served yet. Glint pipelines aren't in `PREWARM` yet.
    - **Invisible nether portals** (2af8033): gbuffer fragment stages read `gl_FragCoord.z` reversed. Non-shadow fragment stages now read `1 - z`, as Iris's DepthTransformer does. This also corrects forward fog and every distance-based term in the gbuffers. Left open: `gl_FragDepth` writes aren't inverted, and composites aren't rewritten.
    - **White banners, invisible held maps, glowing bell and enchanting book** (366c78a): `banner_pattern` is served by new translucent block/entity families; the map's text pipeline by `HAND_TEXT`; block-entity ids are kept per submit and draws split where ids change. Left open: maps in the hand use a default normal; sign and other text submits still get ids by render type.
  - **Not bugs** (same in vanilla): dark triangles on sand steps, the inventory preview's black background and helmet top, the blocky fire overlay, invisible mining cracks at that stage.
  - **Still to check with the Iris runner:** blindness/darkness (water and glowing mobs stayed visible; the depth fix probably covers it), underwater looking up (dark sky with stars at noon, neon seagrass, white blotches on the sea floor), vertical smears in fog near the horizon, and the sand flicker (TAA).
  - The grey band seen from high platforms was fixed separately, in c6b989c.
  - **Check after consolidation** (2026-11-23, `gametest.sh base` + `tour`, against `iris-compare` on the same stops):
    - 0 validation messages; still-camera regression clips pass.
    - Against Iris, the portal, glinted items in frames and hand, banners, block-entity glow, held map and blindness now match.
    - **New difference: Iris shows Complementary's clouds; Luxium shows none** (Luxium skips `CloudRenderer` because the pack says `clouds=off`, and the pack's own clouds don't appear). Next to investigate.
    - The End portal's starfield is a little dimmer than under Iris.

- **7g result (2026-11-23, ✅): compile pool and Metal cache warm-up.** Ideas from GaelQuinta/minecraft-metal-shaders (Apache-2.0, cloned read-only to `references/gaelquinta-metal`; `ShadeCompile`, `mccompile.m`); no code taken.
  - Measured first (`PipelineCompiler` times every compile in two halves):
    - **cross** = `GpuDevice.compilePipeline`'s future: GLSL→SPIR-V (shaderc) in the game's `PipelineBuilder` plus SPIR-V→MSL in Metallum.
    - **metal** = `Pending.finishCompile`: Metallum's MTLLibrary (behind the lock in `getOrCompileFunction`) and pipeline state.
    - Warm Metal cache: cross is ~95% (entity program: cross 84 ms, metal 3 ms). Our translation is ~0 ms (cached per variant).
    - Cold Metal cache: metal dominates (7–8 s per world without validation, 22–30 s with GPU validation, about 1.5–2 s per program under validation).
  - Fix 1, the pool (`render/PipelineCompiler`): 8 daemon workers (cores − 2). Vanilla runs the cross half on its background executor (`ShaderManager.compilePipelines`), so off-thread is the API's intended use. `finishCompile` stays on the render thread, so the 7f concern about public Metallum 0.0.25 (`getOrCompileFunction` not synchronized) doesn't apply.
    - Batched: the composite passes (`PackChain.prepare`), terrain and shadow terrain (`TerrainTakeover.prepare`/`prepareShadow`, `start`/`finish`) and entity prewarm (`EntityTakeover.prewarm`, `prepare`/`done`). First-use compiles still run on the render thread (`compileNow`).
  - Fix 2, Metal warm-up (`mixin/metallum/MetalCrossShaderCompilerMixin` + `MetallumCompiledAccessor`): when Metallum returns the MSL for a `metalshaders:` pipeline on a pool thread, the worker compiles it into a throwaway MTLLibrary (`newLibraryWithSource(source, null)`, Metallum's options). Metal's disk cache then makes the render thread's compile a hit. It also sets `shouldMaximizeConcurrentCompilation` (M1 Max: 10 at once). `LUXIUM_NO_METAL_WARM=1` turns it off. metal-jvm is `compileOnly` (nested in Metallum's jar at run time).
  - Results, Complementary, per world on first visit:

    | Case | Before | Pool | Pool + warm-up |
    |---|---|---|---|
    | Warm cache, validation on | 3.0–3.9 s (prewarm 1.2–1.4 s) | 1.8–2.4 s (prewarm 0.22–0.31 s) | ~1.7 s |
    | Cold cache, no validation | — | 8.7–10.9 s | 5.0–6.8 s |
    | Cold cache, validation on | — | 22–30 s | 17–25 s |
  - Left on the render thread when cold: building pipeline states (AIR→GPU), about 0.9 s chain + 2.2 s entities per world without validation, and first-use compiles (spider eyes, beacons: 0.1–0.2 s cold, 10–20 ms warm). Warming pipeline states would need Metallum's descriptor building off-thread.
  - How to test cold: Metal's cache for the dev game is `$(getconf DARWIN_USER_CACHE_DIR)net.java.openjdk.java/com.apple.metal/` (validation uses its own `*_ShaderValidation` folders). Move it aside, run, then delete the new one and move it back. The game tests otherwise always run warm.
  - Checks: 0 validation messages (warm and cold runs), 98 unit tests, pictures unchanged (before/after compare sheet), flicker unchanged (walk clip 0.14–0.16% before and after, the known open shimmer; others pass).

- **After 7g (2026-11-23): Luxium vs Iris on the real Metal driver (pipecheck).** The idea is from gaelquinta-metal's `tools/shade/pipecheck.m`: build real pipeline states outside the game. Our version is our own code.
  - Tools:
    - `mod/scripts/pipecheck/pipecheck.m` (batch; one JSON line per pair: links or not, colour outputs, used textures and buffers, vertex inputs);
    - `compare.py` (Iris GLSL → `glslangValidator -V -R --amb --aml` → spirv-cross with Metallum's options → pipecheck, beside Luxium's own dumped MSL);
    - `IRIS_DUMP=1` in `iris-compare/run.sh` (Iris's `enableDebugOptions`, which makes its `ShaderPrinter` write `patched_shaders/`);
    - `LUXIUM_DUMP_SHADERS` on the Luxium side.
  - Result, Complementary r5.10, overworld: 26 programs compared (terrain, shadow terrain, entities, hand, particles, weather, block entities, beacon, spider eyes, entity shadows, deferred, composites, final). All 26 link on the real driver on both sides. Colour outputs agree everywhere (Luxium adds its coverage mask). Textures agree except in 4 programs.
  - **Differences found:**
    - **`IRIS_FEATURE_ENTITY_TRANSLUCENT` not claimed (strong lead, unverified in pictures).** Luxium does draw translucent entities and blocks with the pack's separate `gbuffers_entities_translucent` program, which is what the flag announces. Without the flag, Complementary's opaque `gbuffers_entities`/`gbuffers_block` take the fallback: `translucentMult = mix(0.666, color.rgb * (1 - a⁴), a)`, written as `1 - translucentMult` to colortex3. For opaque entities Iris writes 0 there; Luxium writes 1. The composites read colortex3. Next step: claim it in `StandardDefines` and compare entity shots with Iris.
    - **`IRIS_FEATURE_FADE_VARIABLE` not claimed: no chunk fade-in.** Iris supplies `mc_chunkFade` from Sodium's per-section time buffer (`u_SectionTimeInfo`, the only texture-set difference in the three Sodium terrain programs). Complementary uses it, under the flag, to fade new chunks in through fog (`mainFog.glsl`, `gbuffers_terrain`, `gbuffers_water`, `deferred1` via colortex6.b). Under Luxium new chunks pop in. Supporting it needs Sodium's section fade data.
    - **`IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE` not claimed.** Iris on a Mac claims it. Complementary uses it only in `shaders.properties`, to shrink colortex1 and colortex7 to `REFLECTION_RES`. Luxium keeps them full size: slower, not wrong.
    - Spider eyes: Iris's version samples the entity overlay (hurt tint); Luxium's can't, because the game's `eyes` pipeline declares no `Sampler1`. Negligible.
  - Ruled out: weather's alpha test (`1e-4` in Luxium vs `ONE_TENTH_ALPHA` in Iris's ShaderKey). The pack sets `alphaTest.gbuffers_weather=GREATER 0.0001`, so Luxium is right.
  - Iris's log in that run also shows `RuntimeException: Unknown variable: BIOME_ICE_CAVES` (an Iris custom-uniform problem, not ours).
