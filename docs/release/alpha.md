# First public alpha: checklist

Started 2026-11-23, after 7i. Goal: a jar that other Apple Silicon players can install to run
Complementary Unbound on Metal, with honest notes on what works and what doesn't.
**Status: 0.1.0-alpha.1 published 2026-11-23 (GitHub pre-release); benchmarking continues.**

Facts as of 2026-11-23:
- `hecbatista/luxium` is already **public**. LICENSE (LGPL-3.0-only, the text Iris ships) was added 2026-11-23; it reaches GitHub with the next push.
- `main` is 35+ commits ahead of `origin/main`; nothing is pushed.
- `./gradlew build` gives `mod/build/libs/luxium-0.1.0-alpha.1.jar` (about 460 KB) with LICENSE, NOTICE and JCPP (jar-in-jar).
- Tested only on an M1 Max, macOS 27.2, with Complementary Unbound r5.10 at its default settings.
- We build against Metallum `467d312` (0.0.25 plus `5bee03e`, the getOrCompileFunction lock). Players get Modrinth's `metallum-mc` 0.0.25 (2026-10-10, without that commit).

## Must, before anyone else gets the jar
- [x] **License**: LGPL-3.0-only (user's decision, 2026-11-23). `LICENSE` at the repo root and in the jar, `"license"` in `fabric.mod.json`. Still to do: the header line in README.
- [x] **Runs on the Metallum players can download** (2026-11-23):
  - Modrinth's `metallum-mc` 0.0.25 (`metallum-0.0.25.jar`, 1,341,858 bytes, SHA-512 checked) differs from our local build in one class, `MetalDevice`, and only by the `synchronized` on `getOrCompileFunction` (javap). Luxium's Metallum mixins target `MetalCrossShaderCompiler` and its `Compiled` record, which are byte-identical, and Luxium never calls `getOrCompileFunction` off the render thread (7g).
  - Game tests on it (`LUXIUM_METALLUM_JAR=<jar>` in `mod/build.gradle` swaps the jar; `lsof` confirmed the game used it):
    - regression and the zoo and beach tour stops: 0 validation messages, no new exceptions;
    - still clips pass; the moving sprint clip (5.7%) and the animated portal clip (0.25%) flag as on the local build;
    - shots match the local-Metallum run within run-to-run noise (mean |Δ| 1.0–2.9).
  - `fabric.mod.json` now requires `"metallum": "0.0.25"`, so a newer Metallum gets a clear dependency message instead of a mixin crash. Re-test and widen it when Metallum updates.
- [x] **Choosing the pack and switching it off, by settings file** (2026-11-23; the in-game menus come in the next release: `docs/release/menus-plan.md`):
  - `config/luxium.properties` (`pack/LuxiumConfig`), with Iris's key names: `enableShaders=true|false` and `shaderPack=<file or folder in shaderpacks/>`.
    - It's written with the values in use and a "Packs found" list. An empty `shaderPack` takes the first pack and writes its name in, so packs added later don't change the choice.
    - A missing name means no pack and a warning naming the packs found (as Iris).
  - `enableShaders` is read once at startup and decides Sodium's chunk vertex format for the session (`ChunkMeshFormatsMixin`), so on/off needs a restart.
  - One resolver (`MetalShadersClient.choosePack`) replaced the two `findPacks().getFirst()` lookups.
  - Tests:
    - `LuxiumConfigTest` (5 tests);
    - game tests with `LUXIUM_TEST_CONFIG` on the zoo and beach stops: off and missing-pack shots match a no-pack run (mean |Δ| 0.0–1.0, the sky identical), the named pack is used, 0 validation messages.
    - Vanilla's own rain/snow draw still aborts validation without a pack (known, 6d-4), so the regression scene isn't used for these.
- [x] **Fails safe** (2026-11-23). Seven packs on the zoo and beach tour stops (`LUXIUM_TEST_PACKS=<dir>` points the test at a pack folder of its own), all with 0 validation messages and no crash:

  | Pack | Result |
  |---|---|
  | `Broken.zip` (random bytes) | vanilla; one error line and a notice |
  | `NotAPack.zip` (no `shaders/`) | vanilla; one error line and a notice |
  | `BadProgram` (folder pack, programs don't compile) | vanilla; one error line and a notice |
  | `Minimal` (folder pack, only a grayscale `final`) | **works**: vanilla gbuffers, the pack's final pass |
  | Complementary Reimagined r5.9.3 | **works** fully, no errors |
  | Bliss v2.1.2 (Chocapic13 edit) | vanilla: `deferred1` doesn't compile (`MC_HAND_DEPTH` undefined, `texelFetch2D` unknown). **Works since 2026-11-23** (branch `claude/bliss-fix-ac0f91`; `docs/release/compatibility.md`) |
  | Solas v3.9 | vanilla: `final` doesn't compile (`MC_RENDER_QUALITY` undefined; `customTexture.milkyWayTex` unsupported) |

  - A pack that can't be used now gives one plain error line: "Shader pack X can't be used: <why>. The game renders without shaders. If this pack should work, please report it with logs/latest.log (issues link)."
  - It also shows an on-screen notice, "Luxium: shaders off — X couldn't be loaded" (`MetalShadersClient.packUnusable`, once per pack; called from pack reading, mesh directives and `PackChain.fail`).
  - A working pack never triggers it: regression run, 0 lines.
  - Bliss and Solas need only missing Iris macros and an OptiFine function alias: a cheap compatibility follow-up.
- [x] **Version and metadata**: `0.1.0-alpha.1`; mod id renamed `metalshaders` → `luxium` before any release (user's decision; the Java package stays `io.github.hecbatista.metalshaders`). The log tag is now `(luxium)`, and `fabric.mod.json` has license and issues links.
- [x] **README for players** (2026-11-23), merged into the existing styled README:
  - what it is; pack compatibility; performance (`docs/release/benchmarks.md`); requirements;
  - install (Fabric, Sodium 0.9.2, Metallum exactly 0.0.25, Luxium; no Fabric API; no Iris or OptiFine alongside; first-load note);
  - choosing a pack and turning shaders off; pack settings;
  - known issues; how to report a bug (log, pack and settings, F2 screenshots or a recording, Mac model);
  - building from source; credits; license.
- [x] **Push and pre-release** (2026-11-23, user's go): `main` pushed (`9ad6e93`), tag `v0.1.0-alpha.1`, [GitHub pre-release](https://github.com/hecbatista/luxium/releases/tag/v0.1.0-alpha.1) with `luxium-0.1.0-alpha.1.jar` (467,919 bytes, SHA-256 `5a8b015f…4586`, downloaded back and checked). Smoke test of the released jar (2026-11-23): a throwaway Loom project with no Luxium sources, only the jar downloaded from the release, Modrinth's Metallum 0.0.25 and Modrinth's Sodium.
  - Fabric listed `luxium 0.1.0-alpha.1` with `\-- org_anarres_jcpp 1.4.14` nested, `metallum 0.0.25` and `sodium 0.9.2+mc26.3`.
  - Under Metal validation (0 messages), the zoo and beach tour stops ran with Complementary Unbound, and the shots match Iris's.
  - Not covered: a real launcher's install flow (Prism or Modrinth App) and a world on disk; worth a player-style check by the user.

## Benchmarking (first results 2026-11-23: `docs/release/benchmarks.md`; more scenes and machines still to come)
What we can say honestly, measured the same way each time:
- **FPS and frame time, Luxium vs Iris** on the same Mac, scene, resolution and pack settings: average, 1% low, worst frame.
  - Validation off, window in front, vsync and frame cap off (7f: the tests otherwise sit at the 120 Hz cap).
  - Each run alone on the machine, with ABBA ordering (from gaelquinta-metal `bench/HARNESS.md`: background work moves results by 0.5–0.8 ms).
  - Scenes: the tour's beach, zoo, cave and fast flight, plus a fixed 360° spin.
- **World load time**, warm and cold Metal cache (7g has the method and the first numbers).
- **Known limit:** GPU time per pass isn't measurable through the game's API (7f). Real GPU timing needs `MTLCommandBuffer` GPUStartTime/GPUEndTime (a Metallum hook), which is a follow-up, not a blocker.
- Results go in `docs/history/step-7.md` (or a `docs/release/benchmarks.md`) and a short table in the README.

## Known issues to list in the README
- Tested: Complementary Unbound r5.10 (fully compared with Iris) and Complementary Reimagined r5.9.3 (loads and renders; not compared). Bliss and Solas currently fall back to vanilla with a notice. Other packs may fall back or look wrong.
- Pack settings are read only at load: no in-game settings screen or profiles. Edit `shaderpacks/<pack>.txt` and restart.
- Not supported yet:
  - chunk fade-in (`IRIS_FEATURE_FADE_VARIABLE`);
  - `customTexture.*` and raw custom textures (Complementary needs them only with world-space reflections, off by default);
  - `dimension.properties` preprocessing;
  - Distant Horizons.
- Iris renders Complementary about 2–6/255 brighter in the zoo shots (cause open; likely Iris's HIGH profile vs default settings).
- The first world load after installing (cold Metal shader cache) takes 5–7 s per world; later loads about 1.7 s.
- Apple Silicon only (Metal). Colored lighting stays off, as Complementary does on macOS (`MC_OS_MAC`).

## Next release (after the alpha)
- In-game menus: a Shader Packs screen and the pack's own options screens, opened from Video Settings, a key (`O`) and Mod Menu, with live Apply. Plan: `docs/release/menus-plan.md`.

## Should, soon after
- [ ] Real GPU timing (the Metallum hook above) for the benchmark table.
- [ ] Modrinth listing (needs the license, icon (`branding/`), screenshots and description).
- [ ] Crash and bug report template (GitHub issue form).

## 0.2.0-alpha.1 (2026-11-25, user's go)
- `release-0.2.0` = main + `fix-shadow-cull` (perf) + `claude/shader-menus-9e7bd0` + `claude/pack-breadth-341c46` (with bliss and solas) + Codex's audit fixes from the main checkout. Pushed to `main` (`dfd06d3`), tag `v0.2.0-alpha.1`, [pre-release](https://github.com/hecbatista/luxium/releases/tag/v0.2.0-alpha.1) with `luxium-0.2.0-alpha.1.jar` (618,498 bytes, SHA-256 `58efa119…2235`, downloaded back and checked).
- Merge trap: the entity shadow family registered shadowcolor0/1 as colortex geometry targets; with pack-breadth's size.buffer rule that kept colortex1 full size beside half-size colortex7 and Complementary's composite failed. Fixed in EntityTakeover.
- Checks under validation, 0 messages: 151 unit tests, scoreboard 90/90, base scene, tour (walk, zoo, effects, menus, night, cave), menus + live toggle + Mod Menu, base scene on Photon, MakeUp, Mellow, Miniature, Bliss, Solas, BSL, Reimagined. Smoke test of the jar alone (Modrinth's Metallum 0.0.25 and Sodium): zoo stops, 0 messages.
- Quick A/B: clearing both halves of cleared buffers (audit S3-04, as Iris) costs 0.6%; kept.
- `discord-feedback.md` is kept out of the public repo (it paraphrases named Discord members).

## 0.2.0-alpha.2 (2026-11-25): Sodium 0.9.3
- Reported by the user from the Modrinth App (Sodium 0.9.3, BSL): terrain red/pink, and magenta-black in the test. Sodium 0.9.3 added `vec3 u_CameraOffset` (the camera's fraction) after `u_FogColor` in `u_Globals` and moved the fraction out of the region offset; Luxium's terrain header had 0.9.2's layout, so `u_TexCoordShrink` and the rest were read 16 bytes off.
- Fix: `ProgramTranslator.setSodiumCameraOffset`, chosen at startup from the loaded Sodium's version (`MetalShadersClient.sodiumHasCameraOffset`); the 0.9.3 header adds the field and subtracts it. Built against 0.9.2, runs on both.
- Checks under validation, 0 messages: Sodium 0.9.3 with BSL, Complementary Unbound, menus and live toggle; 0.9.2 base scene; 152 unit tests; jar smoke test with 0.9.3 and BSL. SHA-256 `df7c7260…3389`.
