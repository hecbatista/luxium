---
name: luxium-offline-check
description: Check Luxium changes without starting the game, and cheaply. Covers running the unit tests through the quiet wrapper, the step 4 scoreboard that pushes every Complementary program through glslang, spirv-cross and Apple's Metal compiler, the preprocessed-GLSL check, compiling one program by hand, and dumping the exact GLSL and MSL the game compiles. Use for any change to the pack reader, preprocessor, translator, std140 layout, uniforms or formulas, for a shader compile error, or before deciding a game run is needed.
---

# Luxium offline checks

Goal: answer "does it still translate and compile?" in seconds, without a 100 s game run or
thousands of lines of Gradle output. Start the game (the `luxium-gametest` skill) only for what
needs pixels, timing or validation.

## Unit tests: always through the wrapper
```bash
mod/scripts/test.sh                      # all (~30 s)
mod/scripts/test.sh Std140LayoutTest     # one class; any --tests pattern, several allowed
```
- Never run `./gradlew test` directly. The test task prints every test's stdout
  (`showStandardStreams`), which is thousands of lines.
- The wrapper prints javac errors, pass/fail/skip counts, each failure's first lines, and the
  scoreboard line. The full log is `mod/build/test-run.log`: grep it, don't read it whole.
- "test task up to date" means nothing changed since the last run; the counts are from then.

| Area changed | Test classes (`mod/src/test/java/io/github/hecbatista/metalshaders/`) |
|---|---|
| Pack files, zips, world folders | `pack/PackReaderTest`, `WorldFoldersTest`, `RealPackTest` |
| `#include`, `#if`, defines | `pack/IncludeExpanderTest`, `GlslPreprocessorTest` |
| Pack options and settings | `pack/PackOptionsTest` |
| block.properties / item ids | `pack/BlockIdsTest`, `ItemIdsTest` |
| Render targets, formats | `pack/RenderTargetPlanTest` |
| Translator | `glsl/ProgramTranslatorTest`, `GlslTokenizerTest`, `SkyTranslationTest`, `TerrainTranslationTest` |
| Uniform buffer layout | `glsl/Std140LayoutTest` (also compiles a check shader into `build/std140-check/`) |
| Uniforms and pack formulas | `uniform/FormulaTest`, `RealPackFormulasTest`, `UniformWriterTest`, `CameraTrackerTest` |
| Shadow culling | `render/ShadowCullingTest` |
| Everything end to end | `glsl/TranslateRealPackTest` (the scoreboard, below) |

The `Real*` tests and the scoreboard read whatever pack is in `mod/run/shaderpacks/`, and skip
when there's none. Never commit pack files: the license forbids it.

## The scoreboard: every program through the real chain
`mod/scripts/test.sh TranslateRealPackTest`, for every vertex+fragment program of every world
folder:
1. preprocess and translate;
2. `glslangValidator -V --target-env vulkan1.2 --auto-map-bindings` (the game's options);
3. `spirv-cross --msl --msl-version 40000 --msl-decoration-binding --msl-texture-buffer-native`
   (plus `--flip-vert-y` for vertex shaders; Metallum's options);
4. `xcrun metal -std=metal4.0 -c`.

- Output goes to `mod/build/translated/<pack>/<world>/<program>.{vert,frag}` plus `.spv`,
  `.metal` and `.air`.
- `scoreboard.txt` has one line per program: `6-ok`, or the step it stopped at
  (`1-translate`, `2-glslang-frag`, `3-spirv-cross-vert`, `4-metal-frag`).
- Complementary r5.10: 90/90 `6-ok` as of 2026-11-23. Fewer is a regression. Find it with
  `grep -v 6-ok mod/build/translated/*/scoreboard.txt`.
- It uses the generic `ProgramTranslator.translate`. The game also compiles per-draw variants
  (`translateForDraw`, `translateForTerrain`: alpha test, overlay, glint, shadow). For those,
  use the dump below.

## Preprocessed GLSL as OpenGL
After the tests, run `mod/scripts/check-preprocessed.sh`. It runs glslangValidator in OpenGL mode
over `build/preprocessed/`. That checks the preprocessor, not the translator. The expected
failures are listed and explained at the top of the script (177 pass, 42 fail as of 2026-11-21).

## Compile one program by hand
To iterate on one shader without rerunning everything, start from its scoreboard files:
```bash
cd mod/build/translated/ComplementaryUnbound_r5.10/world0
glslangValidator -V --target-env vulkan1.2 --auto-map-bindings -S frag composite1.frag -o /tmp/c.spv
spirv-cross /tmp/c.spv --msl --msl-version 40000 --msl-decoration-binding --msl-texture-buffer-native --output /tmp/c.metal
xcrun metal -std=metal4.0 -c /tmp/c.metal -o /tmp/c.air
```
Use the scratchpad instead of `/tmp` when you have one. Read only the first error line:
`... 2>&1 | grep -m3 error`.

## The exact sources the game compiles
`LUXIUM_DUMP_SHADERS=<absolute dir>` in any game run makes every Luxium pipeline write
`<dir>/<world folder>/<pipeline>.{vert,frag}.glsl` (what we hand the game) and `.metal` (what
Metallum produced from it), variants included:
```bash
mod/scripts/gametest.sh dump LUXIUM_DUMP_SHADERS=$PWD/mod/build/shader-dump
```
- Names follow the pipeline, for example `gbuffers_entities_pipeline_entity_cutout.frag.metal`
  (program, then the vanilla pipeline it stands in for), `pack_composite1.frag.metal`,
  `terrain_solid.vert.glsl`, `shadow_pipeline_entity_solid.vert.metal`. About 180 files per world.
- Diff a dumped `.metal` against the scoreboard's for the same program to see what a variant
  changes. Compile it alone with the `xcrun metal` line above.

## Compare with Iris on the real Metal driver (pipecheck)
`mod/scripts/pipecheck/` holds `pipecheck.m`, which builds a real MTLRenderPipelineState for each
vertex+fragment MSL pair. It reports whether the pair links, its colour outputs, and the textures
and buffers each stage actually uses (Metal's reflection data). `compare.py` runs it on both
runtimes' versions of each program:
```bash
mod/scripts/gametest.sh dump LUXIUM_DUMP_SHADERS=$PWD/mod/build/shader-dump
iris-compare/run.sh dump LUXIUM_TOUR_ONLY="survival walk" IRIS_DUMP=1        # Iris writes patched_shaders/
python3 -I mod/scripts/pipecheck/compare.py mod/build/shader-dump/world0 \
  iris-compare/build/run/clientGameTest/patched_shaders mod/build/pipecheck
```
- Iris's patched GLSL is OpenGL GLSL. `compare.py` takes it to MSL the way the game's Metal path
  would: `glslangValidator -V -R --amb --aml` (relaxed Vulkan rules accept loose uniforms), then
  spirv-cross with Metallum's options.
- Read `mod/build/pipecheck/report.txt`: grep `DIFF`. Expected, not a bug: Luxium has one extra
  colour output (the coverage mask), and the vertex inputs differ by design (Iris feeds its
  extended formats, Luxium derives them from vanilla's).
- The Iris→Luxium program mapping is `PAIRS` in `compare.py` (Iris ShaderKey ↔ Luxium pipeline,
  same program and alpha test). Add pairs as Luxium takes over more programs.
- Results and findings: `docs/history/step-7.md` (after 7g).

## Compile cost
Every compile logs its halves. At world load:
`World <id>: chain compiles, N pipelines: cross X ms (summed over 8 workers, of which Metal warm-up W ms), metal Y ms`.
A first-use compile logs `Entity takeover: ... (translate a ms + cross b ms + metal c ms)`.
See `docs/history/step-7.md` (7g), which also covers how to measure with a cold Metal cache.

## Tools on this Mac
- `glslangValidator` 16.6.0
- `spirv-cross` 1.4.363.0
- `xcrun metal` (Metal Toolchain 27B47, compiler 32023.922)
- JDK Temurin 25.0.5 (`/usr/libexec/java_home -v 25`)

For the GLSL→MSL mapping tables, see the `axiom-graphics` skill, `skills/metal-migration-ref.md`.
