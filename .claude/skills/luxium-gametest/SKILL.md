---
name: luxium-gametest
description: Run Luxium's in-game tests (client game tests, the scripted PlaytestTour, ScreenshotTest), review screenshots cheaply with contact sheets, compare against vanilla or Iris, and delegate fix or verify work to subagents with low token cost. Use whenever a task needs the game run, screenshots checked, a visual bug reproduced or verified, flicker checked, or work split across subagents in this repo.
---

# Luxium game tests, reviews and delegation

Goal: get the same answers with far fewer tokens. Never read raw Gradle or game logs, and never
look at screenshots one by one when a contact sheet will do. If the question is only "does it
still translate and compile?", the `luxium-offline-check` skill answers it without a game run.

## Run
```bash
mod/scripts/gametest.sh <label> [ENV=value ...]
```
- It always runs with Metal API and GPU validation, and prints a ~20-line summary: exit code,
  validation count, exceptions, tour stops, Luxium warnings, flicker per clip, sheet paths.
- Everything is kept in `mod/build/gametest/<label>/`: `run.log` (grep only), `shots/`,
  `flicker.txt`, `sheets/`. Each run gets its own label, so later runs don't overwrite it.
- Exit codes: 0 ok, 1 build or test failed, 2 validation messages or a crash.
- To test a worktree, run that worktree's own `mod/scripts/gametest.sh`.

Common runs:

| What | Command |
|---|---|
| Regression (fixed scene, ~100 s) | `gametest.sh base` |
| Some tour stops | `gametest.sh zoo LUXIUM_TOUR=1 LUXIUM_TOUR_ONLY="build zoo,zoo"` |
| Vanilla for comparison | add `LUXIUM_TEST_NO_PACK=1` (never with rain: vanilla's zero-index snow draw aborts validation) |
| Pack settings | add `LUXIUM_TEST_SETTINGS="NAME=value;NAME2=value"` |
| Frame timing | add `LUXIUM_PERF=1` (best with validation off: run gradle directly) |

Tour stop names, in order (see `runTest` in PlaytestTour.java):
survival walk, build zoo, zoo, effects, survival actions, menus, lightning and fireworks, night,
day cycle, biomes (slow: about 2 min of biome searches), cave, fast flight, portal trip, window resize.
"zoo", "effects" and "survival actions" need "build zoo" first; "survival actions" needs
"survival walk" for the item kit.

## Review, cheapest first
1. Read the summary. If validation shows messages, or there's a crash or a failed stop, deal with
   that before any picture.
2. Look at `sheets/sheet-NN.png`: 9 labelled shots per image.
3. Open a full-size shot only for a suspect. To zoom in, crop with
   `sips -c <h> <w> --cropOffset <y> <x> in.png --out crop.png`.
4. Compare runs by shot name, one column per run:
   ```bash
   java -Djava.awt.headless=true mod/scripts/ContactSheet.java compare <outDir> \
     luxium=mod/build/gametest/<a>/shots vanilla=mod/build/gametest/<b>/shots [iris=<dir>] [only=<substring>]
   ```
   - Vanilla tells you whether the game itself looks that way.
   - Iris tells you whether Complementary is meant to look that way. The Iris runner is
     `iris-compare/run.sh <label> [ENV=...]` (Iris 1.11.7 for 26.3 on the game's OpenGL backend; it
     always runs the tour, so pass `LUXIUM_TOUR_ONLY`). Shots go to `iris-compare/build/shots/<label>/`.
     For now it lives only in the `playtest` worktree, until that branch is merged.
   - Iris applies Complementary's HIGH profile and its own shadow distance (32 chunks); Luxium uses
     the pack's plain defaults. Small differences in option-driven effects can come from that alone.
   - Only call something a Luxium bug when it differs from Iris, or from vanilla where the pack
     shouldn't change it.
5. Flicker: still-camera clips must stay under 0.1% (FlickerCheck). Clips with moving cameras,
   fire, nausea, portals or particles are expected to flag; read their heat maps
   (`shots/frames-*-flicker.png`) before calling anything a bug.

## Delegating to subagents
- **Model:**
  - `model: "sonnet"` for run-and-check work: reproduce, verify a fix, take comparison
    shots, build tooling.
  - The default (Opus) only for diagnosis or fixes that need deep reasoning about the render
    pipeline.
- **At most 2 game-running agents at a time.** Each game is CPU, GPU and RAM heavy, and other
  sessions may be running one too.
- **One worktree per writing agent**, so builds and the game folder don't collide.
  `bash .claude/worktrees/setup-fix-worktree.sh <name>` makes `fix-<name>` from main's latest
  commit (commit first if the agent needs uncommitted work). Two agents must never edit the same functions.
- **Briefs stay short and point; don't paste:**
  - name the files and functions, and the CLAUDE.md section or `docs/history/` file to read.
    Never "read CLAUDE.md fully".
  - state what is already known (the cause, the evidence), so the agent doesn't rediscover it.
  - require this skill's script and sheets for runs; say "never read run.log whole".
  - ask for a concise report: cause, files changed, before/after shot paths, validation count,
    tests, open items.
- **Verify before reporting to the user:** look at the agent's diff (`git -C <worktree> diff --stat`,
  then only the key hunks) and one compare sheet, not every image it lists.
- Agents leave changes uncommitted. Merging worktree branches needs the user's go-ahead.

## Finding code: query the graph before reading big files
A code graph of `mod/src` plus the Minecraft 26.3 sources (AST only, free) is in `graphify-out/graph.json`.
- For how and where questions, run it before opening large files like `EntityTakeover.java` or
  `ProgramTranslator.java`, or before grepping through Minecraft's renderer:
  `graphify query "<question>" --budget 1500`. It answers with nodes, file:line and calls; `--dfs`
  traces one path.
- Refresh it after code changes: `mod/scripts/graph-update.sh` (~15 s).
- It reflects the main checkout. In a worktree, line numbers may differ, so confirm in the file.
- For one known symbol, `grep` is still cheaper.
- Put this in subagent briefs too.

## Debug switches (environment variables)
The full meanings are in `docs/history/` (grep the name).
- `LUXIUM_STOP_AFTER=<pass|seed>` + `LUXIUM_SHOW=<colortexN|depthtex0|rawdepth|maindepth|depthdiff|fragcoord|shadowtex0|<buf>:alt>`,
  `LUXIUM_SHOW_SCALE=<n>`, `LUXIUM_DEPTH_PROBE=1`
- `LUXIUM_NO_TERRAIN=1`, `LUXIUM_NO_ENTITIES=1`, `LUXIUM_SHADOW_CAMERA_LIST=1`
- `LUXIUM_DUMP_SHADERS=<absolute dir>` (write every pipeline's GLSL and MSL), `LUXIUM_NO_METAL_WARM=1`
  (7g Metal cache warm-up off, for timings). Game tests always run on a warm Metal cache; for a
  cold-cache timing see 7g in `docs/history/step-7.md`.
- Test only: `LUXIUM_TOUR=1`, `LUXIUM_TOUR_ONLY`, `LUXIUM_TEST_NO_PACK=1`, `LUXIUM_TEST_SETTINGS`,
  `LUXIUM_TEST_CONFIG="enableShaders=false;shaderPack=X"` (writes config/luxium.properties before Luxium reads it),
  `LUXIUM_TEST_TOGGLE=1` (shaders off and on, and a setting changed, live in a still scene; add `LUXIUM_TEST_NO_OIT=1`
  and `LUXIUM_TEST_CONFIG="enableShaders=false"` for the off-from-start reference, then compare with
  `java mod/scripts/ImageDiff.java a.png b.png [diff.png]`: live off must match it exactly),
  `LUXIUM_TEST_NO_BOB=1`, `LUXIUM_METALLUM_JAR=<jar>` (run against another Metallum, e.g. Modrinth's),
  `LUXIUM_TEST_PACKS=<dir>` (the test copies its packs from that folder instead of mod/run/shaderpacks),
  `LUXIUM_TEST_WALK_ONLY=1`, `LUXIUM_TEST_BOX_ONLY=1`, `LUXIUM_PERF=1`
