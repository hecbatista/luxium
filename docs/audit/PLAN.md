# Codex audit plan: Luxium

Prepared 2026-11-25 by Claude (prep only: no source changed). Every claim cites a file I opened;
anything I could not confirm is under "Open questions" at the bottom. Rules for Codex are in
`/AGENTS.md`. Record results in `docs/audit/FINDINGS.md`. Per-stage prompts: `docs/audit/prompts/`.

Paths below are relative to `mod/src/main/java/io/github/hecbatista/metalshaders/` unless they start
with `mod/` or `docs/`. Abbreviations: PT = `glsl/ProgramTranslator.java`, PC = `render/PackChain.java`,
MSC = `MetalShadersClient.java`.

## Provenance (Step 1)

- Upstream is **not in this repo**. Metallum (pinned `467d312`, v0.0.25), Sodium (`mc26.3-0.9.2-fabric`),
  Iris, Vitrail, gaelquinta-metal and the Minecraft 26.3 sources are checkouts under `references/`
  (git-ignored: `.gitignore:1-4`; folder listing confirmed). Without them, upstream claims can't be checked.
- Everything tracked is ours: 74 commits, 2026-11-20 to 2026-11-25, all by one human author, all with a
  `Co-Authored-By: Claude` trailer (checked with `git log --format=%B | grep -c`). So "written by Claude"
  = the whole tree; there is no non-Claude baseline to diff against. Largest hand-off-risk files:
  `render/EntityTakeover.java` (1073 lines), `render/PackChain.java` (1123), `glsl/ProgramTranslator.java`
  (1237), `render/TerrainTakeover.java` (630), `pack/PackOptions.java` (754), `MetalShadersClient.java` (678).
- Borrowed code (LGPL): Iris and Vitrail, credited per file in `mod/NOTICE`. Ideas only (no code):
  gaelquinta-metal (`render/PipelineCompiler.java:22-26` javadoc).
- Not in scope for correctness audit: `pack/` option parsing, `docs/release/*` (marketing drafts),
  `leadlight-compare/`, `iris-compare/`, `branding/`, `experiments/`.

## Pipeline map (Step 1)

### 1. Load (once per pack / world folder)
| Step | Where |
|---|---|
| Config + pack choice | `MSC.onInitializeClient:49`, `choosePack:123`, `loadPackValues:332` |
| Per-world program folder, unload/reload | `MSC.loadWorld:378` (calls `PackChain.unload`) |
| Preprocess (`#include`, `#define`, options) | `pack/PackPreprocessor.java`, `IncludeExpander`, `GlslPreprocessor` (JCPP), `StandardDefines.forMac()` |
| Build the chain | `MSC.prepareChain:474`: buffer plan via `RenderTargetPlan.fold`, run list filtered to `(deferred\|composite)\d*\|final` at `MSC.java:492` |
| Translate | `PT.translate`; mode FULLSCREEN vs GBUFFERS chosen by name (`PT.java:139`); terrain mode has Sodium's header (`PT.java:373-392`); draw mode mirrors vanilla's `DynamicTransforms` (`PT.java:308`) |
| Compile | `PipelineCompiler.start/finish` (`:101,110`); GLSL source handed via `LuxiumShaderSource` (`render/LuxiumShaderSource.java:17-41`); MSL warm-up hook `mixin/metallum/MetalCrossShaderCompilerMixin.java:22-37` |
| Takeovers prepared | `SkyTakeover.prepare`, `TerrainTakeover.prepare/prepareShadow`, `EntityTakeover.prepare*` (all from `MSC.prepareChain`) |

### 2. Frame (hooks in `mixin/GameRendererRenderMixin.java` and `mixin/LevelRendererTerrainMixin.java`)
1. `GameRenderer.render` before `renderLevel`: `PackChain.beginFrame` (`GRRM:32-36`; PC:370): ensure targets,
   clear only the *current* half of each clear-enabled colortex (both halves are cleared at allocation, `PC:1066-1067`), clear coverage mask to 2.0, begin takeovers.
2. Camera matrices captured at `RenderSystem.setProjectionMatrix` (`GRRM:143-152` to `MSC.captureMatrices:316`
   to `uniform/RenderMatrices.capture:29`). View bobbing is moved from projection into model-view
   (`GRRM:72-136`).
3. Sky: pack's sky programs (`mixin/SkyRendererMixin.java:79` calls `PackChain.skyDrawn`; `render/SkyTakeover`).
4. Shadow: `TerrainTakeover.drawShadow:212` (terrain via light-view lists `ShadowCulling.withLightLists`, then
   `EntityTakeover.drawShadowCasters` at `TerrainTakeover.java:255`); targets in `render/ShadowTargets`.
   **Corrected (Codex Stage 1, S1-11):** the sole caller is `drawOpaqueTimed` (`TerrainTakeover.java:494`), which runs from
   the Main-pass creation wrapper, immediately before opaque terrain. Shadow order: opaque terrain, `drawShadowCasters`,
   publish opaque depth (shadowtex1), optional translucent shadow terrain (`:259-262`), publish full depth (shadowtex0).
5. Opaque world: `LevelRendererTerrainMixin.java:55-72` swaps terrain for pack gbuffers (`TerrainTakeover.drawOpaque`),
   then wraps the game's Main pass in `LevelPass` (`render/LevelPass.java`) so pack entity passes can interleave.
6. "After opaque": `LevelRendererTerrainMixin.java:104` calls `PackChain.drawDeferred` (PC:419): depth converted
   (`convertDepth` PC:585), copy to `depthPre` (depthtex1/2), seed colortex0 from game image (`seed` PC:596),
   run deferred* via `runPasses` (PC:613).
7. Translucent terrain + water: `TerrainTakeover.drawTranslucent` and a "catch" target (`PackChain.catchTarget:693`,
   `composeCatch:719`; `LevelRendererTerrainMixin.java:106-121`).
8. `renderLevel`, **just before `render3dHud`** (not at the end; Codex S1-15): `PackChain.afterLevel` (`GRRM:43-52`; PC:455) re-converts depth (depthtex0 incl. translucents).
9. Hand (**two branches**, Codex S1-16): if the pack hand is inactive, composite* and final run *before* the vanilla hand (`GRRM:47-51`). If active, as follows: pack's hand drawn by `EntityTakeover`; `PackChain.afterHand` (`GRRM:59-64`; PC:491) merges hand depth
   (`mergeHand` PC:531), runs composite* then final into `target.getColorTextureView()` (PC:643-645).
   Fallback hook `GRRM:67-70` if render3dHud did not run.

### 3. Binding and uniforms
- One std140 block `LuxiumUniforms` per program (`PT.java:35,934-`). Offsets: `glsl/Std140Layout.of:44`. Values:
  `MSC.value:304` -> `uniform/GameUniforms`, `RenderMatrices`, `CustomUniforms`/`Formula` -> bytes via
  `uniform/UniformWriter.write:33`. Missing names get the pack default or zero (`UniformWriter.java:41-48`).
- Samplers and the block bound **by name** (`PC:170-175` layout; `PC:657-663` draw). Gbuffer programs resolve
  inputs lazily per draw (history note `docs/history/step-6.md:133`).
- Colortex: two halves per buffer, written half = `1 - current`, flipped after each pass (`PC:669-671`).
  Formats come from `RenderTargetPlan`; "promoted" buffers written color-only (`PC:164-165`).

### 4. Depth/origin conventions as implemented
Reversed-Z in the game; OpenGL-style in packs; conversions at `PT.java:754-773` (frag), `:1010-1015`, `:1045-1052`
(vertex epilogue), `PC:585` (depthtex), `RenderMatrices.java:46`. Shadow map forward-Z. Origin: see AGENTS.md.

## Stages

Scope lists are exhaustive for that stage; read other files only to resolve a reference.

### Stage 1: Inventory (light tier)
- **Files:** `AGENTS.md`, this file's "Pipeline map", `MSC`, `PC`, `PT` (signatures and javadoc only),
  `mixin/GameRendererRenderMixin.java`, `mixin/LevelRendererTerrainMixin.java`, `luxium.mixins.json`.
- **Check:** each row of the Pipeline map is true (file, line, behavior). List every wrong or stale citation.
  Find the actual call site of `TerrainTakeover.drawShadow`. List every mixin target and say whether the target
  string exists in `references/minecraft-26.3-sources` / Sodium / Metallum. Do not report bugs yet.
- **Output:** corrected map + list of unverified claims.

### Stage 2: Translation correctness (standard tier)
- **Files:** `glsl/ProgramTranslator.java` (whole), `glsl/Std140Layout.java`, `glsl/Declarations.java`,
  `glsl/GlslTokenizer.java`, `uniform/UniformWriter.java`, `uniform/RenderMatrices.java`,
  `uniform/ShadowMatrices.java`, `render/PackChain.java:130-290,585-612,613-690,1029-1100`,
  `render/ShadowTargets.java`, tests `mod/src/test/.../Std140LayoutTest.java`, `UniformWriterTest.java`,
  `ProgramTranslatorTest.java`, `TerrainTranslationTest.java`.
- **Check:**
  1. Clip-space depth: each of the three epilogues (`PT:1015`, `:1051`, shadow `:1014/1048`, hand `:1050`) maps
     OpenGL -w..w to the right range, incl. `w <= 0`; `gl_FragCoord.z` rewrite (`:754-773`) applies only to the
     stages it should; `depthPipeline` really yields `1 - d` (find its shader in `PC` ~`:290-355`).
  2. `RenderMatrices.undoRevZ` algebra for the 0..1 branch vs the else-branch; JOML row/column naming
     (`m02` = column 0, row 2?) must be proven from JOML, not assumed. Also `ShadowMatrices.projection` uses
     `setOrthoSymmetric(..., false)`: confirm what the flag selects.
  3. Texture origin / flips: fullscreen triangle UVs (`PT.java:107-125` `fsProjection`), `gl_FragCoord`
     usage, `scale.<program>` handling, shadow map UV, `texelFetch` coordinates, hand merge pass. Compare with
     the claim in `docs/history/step-6.md:235`.
  4. std140: `Std140Layout.info` for mat2/mat3/matCxR, `bool`, arrays of vec3/float/int, `LuxiumFog` struct,
     members after a vec3; `UniformWriter` matches those offsets (mat3 padding, `bool` as int, arrays only mat4,
     `LuxiumFog` always "missing"). Does the translated GLSL declare members in the same order the layout
     assumes? Does SPIRV-Cross/MSL reflect the same offsets? (Dumps via `LUXIUM_DUMP_SHADERS` can't be run by
     you; reason from source.)
  5. Binding indices vs host: names in `Sampler`/uniform lists vs `setUniform` names in `PC`, `TerrainTakeover.bind:575`,
     `EntityTakeover`, `SkyTakeover`; Sodium `u_Globals`/push-constant mirror (`PT:373-392`) vs Sodium 0.9.2's
     real layout in `references/` if present; DynamicTransforms (`PT:308-315`) vs Minecraft 26.3's
     `dynamictransforms.glsl`.
- **Output:** findings with the GLSL/MSL text or arithmetic that proves each.

### Stage 3: Iris parity (standard tier)
- **Files:** `MSC.prepareChain:474-632`, `pack/RenderTargetPlan.java`, `pack/PackDirectives.java`, `pack/ProgramSwitches.java`,
  `pack/StandardDefines.java`, `render/PackChain.java`, `render/TerrainTakeover.java`, `render/EntityTakeover.java`,
  `render/SkyTakeover.java`, `render/ShadowTargets.java`, `render/CustomTextures.java`, `uniform/GameUniforms.java`,
  `uniform/Formula.java`, `uniform/CustomUniforms.java`, `uniform/ShadowMatrices.java`, `mod/NOTICE`,
  references: `references/iris` (pinned commit in skill `luxium-references`, `.claude/skills/luxium-references/`).
- **Check (cite the Iris file and line for each):**
  1. Program stages: Iris's `ProgramId` fallbacks vs `byPipeline`/`firstOf` chains (`MSC.java:~560-626`);
     missing stages Iris runs: `begin*`, `prepare*`, `shadowcomp*` (classified at `PT:139`, filtered out at `MSC:492`),
     `gbuffers_clouds`, `gbuffers_skytextured` use, `gbuffers_damagedblock`, `gbuffers_armor_glint`, `gbuffers_line`,
     `dh_*`. Say which are absent and whether Complementary uses them.
  2. Pass order and flip rules vs Iris `CompositeRenderer`/`FinalPassRenderer`; `scale.*`, `size.buffer.*`,
     `flip.*`, `bufferObject.*`, `texture.<stage>.*` handling.
  3. Render targets: default formats, `clear`/`clearColor`, promoted buffers, mipmapping, `shadowcolor*`,
     `depthtex0/1/2` content at each stage (incl. hand), `shadowtex0/1`.
  4. Uniforms: every name in `GameUniforms` vs Iris `CommonUniforms`/`IrisExclusiveUniforms`/`MatrixUniforms`;
     units, ranges, smoothing (`eyeBrightnessSmooth`, `centerDepthSmooth`), `frameTimeCounter`, `sunAngle`, `shadowAngle`,
     `previous*` matrices (first-frame value), `cameraPosition` precision.
  5. Documented deviations: `COLORED_LIGHTING 0` under `MC_OS_MAC`, OIT off, Improved Transparency off.
- **Output:** a parity table: item, Iris behavior (file:line), Luxium behavior (file:line), verdict.

### Stage 4: Resource and lifetime safety (standard tier)
- **Files:** `render/PackChain.java` (`unload`, `ensureTargets`, `prepare`, `discard`, pass UBO ring usage), `render/TerrainTakeover.java`,
  `render/EntityTakeover.java`, `render/SkyTakeover.java`, `render/ShadowTargets.java`, `render/CustomTextures.java`,
  `render/LevelPass.java`, `render/RoutingPass.java`, `render/DropPass.java`, `render/PipelineCompiler.java`,
  `render/FlashProbe.java`, `render/PerfStats.java`, `mixin/sodium/*`, `mixin/metallum/*`, `MSC.loadWorld`.
- **Check:**
  1. Ownership: every `createTexture`/`createTextureView`/pipeline/ring buffer has one owner and one `close`
     on every path (resize, world switch, failure, `fail()` PC:1114, `discard`).
  2. Reuse across frames: `MappableRingBuffer.rotate()` placement (one rotate per pass vs many draws per pass);
     per-draw buffers shared across program variants (`docs/history/step-7.md:129` records one such crash);
     views captured in static maps across a resize; last-frame textures (`previous*`, colortex that "keep contents").
  3. Command buffers: `device.createCommandEncoder()` called many times per frame (PC:428-520, 531-600); ordering vs
     the game's own encoder; textures read in a pass they are attached to; `copyTextureToTexture` format/size
     compatibility; `LevelPass.suspend()` reopening with correct load actions (never clear).
  4. Threading: pool threads touching shared mutable state (`PipelineCompiler` counters, static `Map`s in takeovers);
     `warmMetal` device usage off-thread (`PipelineCompiler.java:182-220`); `volatile` use; reload while a compile is in flight.
  5. Exception safety: `try`/`finally` around render passes and debug groups; behavior after `fail()` (game continues unshaded?).
- **Output:** per finding, the interleaving or call sequence that breaks it.

### Stage 5: Deep dive (heaviest tier)
- **Scope:** only findings from stages 2-4 rated critical/high, or medium with low Stage-2-4 confidence, that I
  (the user) mark `unverified` in FINDINGS.md. One finding at a time.
- **Check:** reconstruct the full call path, write the minimal failing scenario (inputs, state, frame order), say
  what evidence in a Metal capture or validation log would confirm it, and propose a fix as prose (no edits).

### Stage 6: Ground-truth verification (done by the user, not a model)
- Metal API + Shader Validation: the game already runs with `MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1`
  (`mod/scripts/gametest.sh`); repeat from Xcode scheme with validation on and GPU Frame Capture enabled.
- GPU frame capture: one frame per world (Overworld, Nether, End); inspect each pass (shadow, gbuffers_*, deferred*,
  composite*, final), attachments, depth values, bound textures and buffer contents vs the UBO layout.
- Screenshot compare vs Iris/OpenGL, same pack, fixed seed and camera. Repo has `mod/scripts/ContactSheet.java compare`
  (`gametest.sh:15`) and `iris-compare/`. Use identical settings; Luxium forces `COLORED_LIGHTING 0` on Mac.
- Feed results into FINDINGS.md: set `reproduced` or `false positive`.

## Open questions and things I could not confidently explain

1. ~~Where `drawShadow` is called~~ Resolved by Codex Stage 1 (see step 4 of the Frame map).
2. **`prepare*`, `begin*`, `shadowcomp*` programs.** Translator treats them as full-screen (`PT.java:139`), loader skips them (`MSC.java:492`).
   Whether this is intentional (Complementary lacks them) is undocumented in files I read.
3. **`LuxiumFog` struct** is declared in `Std140Layout.STRUCTS` but `UniformWriter` always reports it missing ("not supplied yet",
   `UniformWriter.java:60-62`). Unknown whether any generated program still contains it.
4. **`scale.<program>`**: implemented by baking a scaled projection into the vertex shader (`PT.java:107-125`) while the render area in
   `runPasses` uses full `width x height` (`PC:653-654`). I did not confirm that the viewport/attachment size is consistent for scaled passes.
5. **Texture resize lifetime**: `ensureTargets` closes old colortex textures (`PC:1029-1050`) relying on Metallum retaining resources in
   recorded command buffers (comment `PC:236`). Not verified against Metallum source.
6. **Origin claim** (`docs/history/step-6.md:235`) comes from one debug measurement of the fullscreen seed pass; I did not verify it
   for gbuffers (terrain/entity) passes, shadow sampling, or `texelFetch`.
7. **Mixin target strings** (obfuscation-free names, `lambda$addMainPass$0`, `lambda`-index fragility) are tied to exact Minecraft 26.3,
   Sodium 0.9.2 and Metallum `467d312` builds; I did not verify them against `references/`. README says Modrinth Metallum 0.0.25 is also
   required (`b7a2529`); whether that equals `467d312` is not shown in files I read.
8. **Uniform formulas** (`uniform/Formula.java`, 537 lines) and block/biome id tables: not read by me; no claim made.
9. I did not run any tests, builds or the game. I did not read `EntityTakeover.java` (1073 lines), `TerrainTakeover.java`, `SkyTakeover.java`,
   `PackOptions.java`, `GameUniforms.java` beyond signatures and greps, so their descriptions come from names, comments and `docs/history`.
10. Test coverage: unit tests cover reader, preprocessor, translator, std140, formulas, culling; there are no GPU-side tests except
    the scripted client game tests (`mod/src/gametest/.../PlaytestTour.java`, `ScreenshotTest.java`), which I did not open.

## Stage 1 result (Codex, 2026-11-25)

Codex confirmed the Load and Binding rows and the 36 mixin classes' Minecraft/Metallum targets. Corrections applied above:
frame-start clear is current-half only; `drawShadow` caller found; `afterLevel` runs before `render3dHud`; composite/final precede
the vanilla hand when the pack hand is inactive. Also: entity inputs resolve when the entity window opens, not per draw
(`EntityTakeover.java:822-829`; the `step-6.md:133` note is terrain-specific).

Still open after Stage 1:
- **Sodium mixins (S1-51..59) unverified**: no Sodium source for `mc26.3-0.9.2-fabric` was available, only a binary jar. Includes
  `require = 3` on `RenderRegionManager.uploadResults` (`mixin/sodium/RenderRegionManagerMixin.java:17-20`). Stage 2/4 must not assume these are right.
- Translator bodies (`modeFor` regex, emitted std140 block at `PT:934`, depth rewrites) were outside Stage 1's scope: Stage 2.
- Origin convention verified only for the seed pass. Mixin injection at runtime is unverified (needs a game run).

## Stage 2 result (Codex, 2026-11-25)

Checked and correct (S2-01, 02, 04, 07, 09, 11, 12): vertex depth endpoints for scene, shadow and hand; fragment depth reads and depth-texture
conversion; JOML indexing in `undoRevZ` and the `setOrthoSymmetric` flag; std140 offsets for supported types; sampler name agreement between
translator and all four hosts; DynamicTransforms and Sodium `u_Globals`/push layouts against shipped assets.
Defects recorded in FINDINGS.md: S2-03, S2-05, S2-08, S2-10. I spot-checked S2-03 (no `gl_FragDepth` handling in `glsl/`), S2-05 (projection code)
and the Complementary zip (no `gl_FragDepth`; `scale.composite4 = 0.4` present).
Correction to the repo's own notes: the origin result in `docs/history/step-6.md:235` credits "Metallum flips the viewport"; Codex found a
`FLIP_VERTEX_Y` shader-compiler option and a full-size viewport instead (S2-06), so the attribution is imprecise. Origin is still measured only for the seed pass.
Stage 3 carry-over: S2-05 (scale handling) overlaps Iris parity; check it against `CompositeRenderer` there.

## Stage 3 result (Codex, 2026-11-25)

Iris checkout matches the `mod/NOTICE:9` pin (`671a5c87`). Parity table and findings S3-01..09 are in FINDINGS.md; the full table is in the Codex
transcript (not saved here). Close to Iris: shadow matrices, time counters, camera and view uniforms, sun/shadow angle, fog/rain/wetness, player state.
Gaps that matter most for Complementary (my check of the zip): S3-01 (hand/depth snapshots, medium), S3-03 (`size.buffer.colortex1/7`), S3-04 (clear halves),
S3-06 (shadow aliases/casters), S2-05 (scale). Not exercised by Complementary: S3-02 (no begin/prepare/shadowcomp or optional gbuffers programs), S3-08.
Candidates for Stage 5: S2-05, S3-01, S3-04.

## Stage 4 result (Codex, 2026-11-25)

S4-01..09 recorded. Ownership, ring-buffer rotation, command-encoder sharing, pass suspension and the compile-pool handoff were traced and found sound on the normal paths
(S4-10..18, all `info`, not copied to FINDINGS.md). Both high findings are failure-path bugs: normal play never reaches them, but when a chain failure does happen the result is
persistent (S4-01: missing water) or inconsistent (S4-02: half-built chain reporting ready). I confirmed S4-01's control flow by reading the code.
Unverified premises: the claim "command buffers retain their references" (`PackChain.java:235-237`) and pipeline release (`MetalCompiledRenderPipeline.java:316-325` releases directly) still need
Metallum/Metal evidence; Sodium batch invalidation is unchecked (no Sodium source). Stage 5 candidates: S4-01, S4-02, S2-05, S3-01, S3-04.

## Stage 5 result (Codex, 2026-11-25)

The pasted prompt still contained the `[PASTE ROWS HERE]` placeholder, so Codex picked the first high/unverified row (S4-01) on its own. S4-01 is CONFIRMED BY CODE with a full call path
(also see its FINDINGS note). New side finding S5-01 (uniform type mismatch throws and disables the chain). **Not yet deep-dived:** S4-02, S2-05, S3-01, S3-04. Re-run
`prompts/stage-5-deep-dive.md` with the placeholder replaced by those rows.

## Fix pass (Claude, 2026-11-25)

Fixed (see FINDINGS.md notes): S4-01, 02, 03, 04, 05, 06, 07, 09, S5-01, S2-03, S3-04; partly S2-08, S3-03. Verified only by compiling and `mod/scripts/test.sh`
(105 tests, scoreboard 90/90 `6-ok`); **no game run, no Metal validation, no flicker check yet**. Mixin and render-pass changes (S4-01, S4-07) especially need a game test.
Deliberately not changed: S2-05 (scissor origin on Metallum unknown: a wrong corner would clip the real region), S3-01 (needs a second depth snapshot and ordering changes),
S3-08 (Iris's own 3-argument `smooth` overloads are ambiguous; Complementary uses the 4-argument form), S2-10, S3-02, S3-05, S3-06, S3-07, S3-09, S4-08 (feature work or
needs evidence that the pack is affected).
