# Automated in-game screenshots: full notes

Moved out of CLAUDE.md on 2026-11-23 to keep every session small. Day-to-day use: the luxium-gametest skill.

## Automated in-game screenshots (client game tests)
Claude can test the game without the user. Fabric API's client game tests (`fabric-client-gametest-api-v1`, Fabric API `0.166.2+26.3`, gametest source set only, not a mod dependency) start the game, build a scene with commands, take screenshots and quit:
```bash
cd mod
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1 ./gradlew runClientGameTest
```
- Test: `mod/src/gametest/java/io/github/hecbatista/metalshaders/test/ScreenshotTest.java`. Shots: 1 land, 3 hurt pig (with campfire smoke), 2 underwater, 4 rain, 5 hand, 5b third person, 6 Nether, 7 End, 8 back in the Overworld, 9 a hidden tower's shadow, 10 Improved Transparency switched back off. A full run takes about 100 s.
- Screenshots land in `mod/build/run/clientGameTest/screenshots/` (854×480); view them with the Read tool.
- The test's game folder is cleared every run, so the test copies `mod/run/shaderpacks/*` into it first.
- Spectator mode and a hidden HUD keep shots clean. In Survival the player falls between teleports.
- `eula = true` in `mod/build.gradle`: the user agreed to the Minecraft EULA for this on 2026-11-20.
- Frame sequences for flicker: 24 consecutive frames each (one per tick, still camera): `screenshots/frames-land/` and `frames-underwater/` after shot 1, `frames-nether/` and `frames-end/` after shots 6 and 7, `frames-offscreen/` after shot 9. Check them with:
  ```bash
  D=build/run/clientGameTest/screenshots; java scripts/FlickerCheck.java $D/frames-land $D/frames-underwater $D/frames-nether $D/frames-end $D/frames-offscreen
  ```
  - It flags pixels that jump for one frame and come back (A→B→A, change > 40/255, neighbours within 12/255). It fails (exit 1) above 0.1% of a frame's pixels.
  - It writes `<dir>-flicker.png` (heat map) and `<dir>.gif` (a looping 20 fps clip to watch). No ffmpeg on this Mac.
  - Baseline 2026-11-20: land 0.012%, underwater 0.03%, Nether 0.033% (soul fire), End 0%.
- `LUXIUM_TEST_SETTINGS="NAME=value;NAME2=value2"` (environment variable) makes the test write the pack's settings file before the world loads.
- `LUXIUM_PERF=1` (best without the validation variables, which slow everything down) logs `Perf [land|underwater|nether|end]: ...` frame-time summaries; `LUXIUM_TEST_NO_PACK=1` runs without a pack, for comparison.
- API, read from the jar: `context.worldBuilder().create()`, `singleplayer.getServer().runCommand(...)`, `context.waitTicks(n)`, `context.takeScreenshot(name)`, `context.runOnClient(mc -> ...)`, `assertScreenshotEquals(...)`.

## Running the game by hand, and validation facts
Moved out of CLAUDE.md on 2026-11-23. Always run with Metal's API validation and GPU shader validation on:
```bash
cd references/metallum && ./gradlew build   # once: mod/ uses this local jar (commit 467d312)
cd ../../mod
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1 ./gradlew runClient
```
- Why the local Metallum jar and not Modrinth's `metallum-mc` 0.0.25 (2026-10-10): the local clone has two more commits, including `5bee03e fix: synchronize MetalDevice.getOrCompileFunction()`, a thread-safety fix in shader compiling.
- A healthy start of our mod logs `(luxium) Luxium loaded. Metallum version: 0.0.25` (the tag was `(metalshaders)` until the mod id became `luxium` on 2026-11-23, and the text "Metal Shaders loaded" before the 2026-11-20 rename; confirmed 2026-11-20: title screen normal, zero validation messages). The game directory is `mod/run/`, so packs go in `mod/run/shaderpacks/`.
- A healthy start also logs `(metallum) Metal device: Apple M1 Max` and `Using graphics backend Metal`. The `ClassNotFoundException` warnings for Distant Horizons and `com/bloom/...ShineRenderBackend` are Metallum's optional compatibility hooks and are harmless.
- Closing the game with `kill` makes Gradle print `BUILD FAILED ... exit value 143`. 143 means SIGTERM, so this is expected and not an error.
- To run plain Metallum without our mod, use the same command from `references/metallum`.
- Source: `man MetalValidation` (`/usr/share/man/man1/MetalValidation.1`). Any non-zero `MTL_DEBUG_LAYER` enables API validation; any non-zero `MTL_SHADER_VALIDATION` enables GPU validation. More `MTL_DEBUG_LAYER_*` and `MTL_SHADER_VALIDATION_*` options are documented there.
- **Confirmed working (2026-11-20).** The game process got both variables even though the long-running Gradle daemon didn't have them (checked with `ps eww <pid>`), so `--no-daemon` isn't needed. Metal printed `Metal API Validation Enabled` and `Metal GPU Validation Enabled` at startup. If those two lines are missing from a run, validation is off.
- Baseline (2026-11-20): unmodified Metallum, run under API and GPU validation, reached the title screen, created and loaded a Creative world, flew around for about 40 seconds, and saved and quit. There were **zero Metal validation messages** and the world rendered normally (user-confirmed). Any validation failure after our changes is therefore ours.
- `Requested post effect does not exist: minecraft:end_of_frame` is a **vanilla** warning, not Metallum's. `GameRenderer.update()` (`GameRenderer.java:433`) requests `END_OF_FRAME_POST_EFFECT` every frame, and the vanilla client jar ships no `post_effect/end_of_frame.json`. This full-screen hook may be relevant to the shader work later.
- Ignore an HTTP 429 from `sessionserver.mojang.com` at startup: that's Mojang rate-limiting the offline dev player's profile lookup.
- GPU shader validation makes cold shader compiles several times slower (7g: 22–30 s per world cold with validation, 8.7–10.9 s without). Time things without it; check correctness with it.
