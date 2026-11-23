# In-game shader menus: plan for the release after the first alpha

Agreed with the user on 2026-11-23: Iris-style menus (option 3), shipped as their own release after
`0.1.0-alpha.1`. The alpha uses a settings file instead (`docs/release/alpha.md`).

## Where players open it
- **Video Settings → "Shader Packs..."**, where Iris puts it.
  - Hook: a mixin at the end of `VideoSettingsScreen.addOptions` adds a button with `this.list.addBig(AbstractWidget)` (`OptionsList.addBig`, 26.3 sources).
  - Iris instead adds a fake `OptionInstance` by redirecting `addSmall` (`mixin/gui/MixinVideoSettingsScreen`).
- **Mod Menu → Luxium → Configure** opens the same screen.
  - Mod Menu 21.0.1 (2026-11-12) supports 26.3. Add an optional `"modmenu"` entrypoint implementing `ModMenuApi.getModConfigScreenFactory`, with `compileOnly` from Terraformers' Maven.
  - Luxium must still work without Mod Menu, and without Fabric API (Mod Menu requires Fabric API; Luxium doesn't).
  - Prism Mod Menu uses the same API (to verify).
- **Key `I` by default**, as Iris 26.3 (`Iris.java:813`, `KEY_I`), rebindable in Controls. (The plan said O, from older Iris; vanilla 26.3 binds O to its new friends list, `key.friends`.)

## Screens (Iris behaviour, our own code, credited in NOTICE)
Research summary: an agent's report on Iris 26.3, `gui/` and `shaderpack/option/menu/`.

**Pack list** (Iris `ShaderPackScreen` and `ShaderPackSelectionList`):
- The first row is a shaders on/off toggle, disabled when there are no packs. Then one row per pack in `shaderpacks/`.
- Clicking a pack selects it; when shaders were off, it also turns them on.
- Buttons: Cancel (drop changes), Apply, Done (apply and close), "Open shader pack folder", and "Shader pack settings" (applies the selection first, then opens the options).

**Options screens** (`OptionMenuContainer`, `OptionMenuElementScreen`, `OptionMenuConstructor`, `ShaderPackOptionList`):
- **Grammar**, read from the *unpreprocessed* `shaders.properties`, as Iris does (`ShaderProperties.java:600-623`):
  - `screen=`, `screen.<NAME>=`, `screen.columns=`, `screen.<NAME>.columns=`; `.columns` keys are not screens.
  - `sliders=`; `profile.<NAME>=`.
  - Tokens: `<empty>`; `<profile>` (dropped when there are no profiles); `[NAME]` (a link; to a missing screen it goes to the main screen); `*` (every option not placed on any screen); option names (unknown names log a warning and become empty slots).
  - Columns: 2 by default, 3 when there are more than 18 elements. No `screen=` line means `["*"]`.
- **Profiles** (`ProfileSet.parse` and `scan`, `Profile.matches`):
  - Tokens: `!program.X`, `profile.X` (included recursively; a cycle is an error), `!OPT` (off), `OPT=val` or `OPT:val`, a bare boolean name (on).
  - The current profile is the first, in order of most constraints, whose options all match the current (applied plus pending) values; otherwise "Custom". Cycling follows that order and wraps.
- **Widgets:**
  - boolean: an On/Off value, green or red only when it differs from the default;
  - string: cycles, left click forward, right click back, blue value; shows `prefix.<OPT>` + (`value.<OPT>.<v>` or the raw value + `suffix.<OPT>`), with prefix and suffix read without format codes;
  - slider: for names in `sliders`, one step per allowed value, applied on release;
  - link: `screen.<ID>` label;
  - profile.
- **Labels:**
  - `option.<NAME>` (or the raw name), `option.<NAME>.comment` (hover text, split on ". "), `screen.<ID>`, `screen.<ID>.comment`, `profile.<name>`, `profile.comment`;
  - read from `shaders/lang/*.lang` (UTF-8 Properties), the player's language first, then `en_us`. Complementary ships `en_US.lang`, so match the file name without regard to case.
- **Interaction:**
  - a label turns yellow while its pending value differs from the applied one;
  - Shift-click resets one option to its default;
  - Shift + Reset resets the whole pack;
  - Esc goes back through the sub-screens, then to the pack list;
  - hover text appears after about 10 ticks.
- **Not in this release:** Import/Export, drag-and-drop, "Download shaders".

## Applying (needs live reload; Luxium reads settings only at load today)
- Pending values live in a queue until Apply.
- Apply writes `shaderpacks/<pack>.txt` with only the non-default values, and deletes the file when nothing differs. It's the same file Iris uses, so settings move between Iris and Luxium.
- Then the pack reloads live: re-read the options, the mesh directives and `loadPackValues`, then `PackChain.unload` and `loadWorld` for the current folder. That's about 2 s with a warm Metal cache (7g).
- Chunks are rebuilt (`LevelExtractor.allChanged`) only if the chunk vertex format changed.
- Shaders off at runtime: unload everything, rebuild chunks in the vanilla format, and restore the game's own Improved Transparency, clouds, entity shadows and bobbing.

## Slices
- **A. Groundwork:**
  - one pack resolver;
  - live reload;
  - live on/off, proven by comparing pixels with a no-pack run, including toggling off and on in play.
- **B. Pack list screen** and its three entry points.
- **C. Options screens:** parser, lang, widgets, navigation, profiles, Apply. Unit tests on Complementary's real menus (51 `screen` lines, 8 profiles): every referenced option exists, and profiles are detected correctly.
- **D. Tests and docs:**
  - game tests that open and screenshot each screen, apply a change, toggle off and on, and run with Mod Menu installed, all under validation;
  - a README section.

## Results

### Slice A: live reload and live on/off (2026-11-23, ✅)
- **One resolver.** `MetalShadersClient.choosePack` reads `config/luxium.properties` at every pack load (startup, first world, each reload); the startup-only `shadersEnabledAtStartup` is gone. `LuxiumConfig.write(shaderpacks, config, enabled, pack)` is what the screens will call (off keeps the pack's name).
- **Live reload.** `MetalShadersClient.requestReload()` (any thread) is acted on at the next frame's `onFrameExtracted`: `unloadPack` closes everything (`PackChain.unload`) and forgets the options, formulas, layout and folder; then the normal load path runs (`readMeshDirectives`, `loadPackValues`, `loadWorld`). `packActive()` and `activePackName()` are for the screens.
- **Chunk format follows the pack.** Luxium's 36-byte vertex is used only while a pack is chosen; with none, Sodium keeps its own 20-byte format (so `LUXIUM_TEST_NO_PACK` runs are now Sodium's own format too). Chunks are rebuilt when the format, `separateAo`, block ids or settings change (`meshKey`), as before.
- **Two traps found (Sodium 0.9.2, decompiled):**
  - `LevelExtractor.allChanged` only marks the geometry stale. Sodium builds its new section manager on the *next* frame (`LevelExtractor.extract` → `LevelRenderer.invalidateCompiledGeometry`, which Sodium's `LevelRendererMixin` replaces with `SodiumWorldRenderer.reload` → `initRenderer`). So the format switch is applied in `SodiumWorldRendererMixin` (HEAD of `initRenderer`, `MetalShadersClient.applyMeshFormat`), not when asking for the rebuild.
  - `ShaderChunkRenderer` keeps Sodium's terrain pipelines in two static maps (`programs`, `oitPrograms`), built once with the vertex format of whichever renderer asked first. After on → off, Sodium drew 20-byte meshes with a 36-byte pipeline: the terrain vanished (only block entities showed). `SodiumPrograms` keeps one set per format and swaps them with the format (`ShaderChunkRendererAccessor`); Iris instead always builds them with Sodium's format (`MixinShaderChunkRenderer`).
- **Improved Transparency** stays off after shaders are turned off live, as in Iris (user's choice, 2026-11-23).
- **Test:** `ScreenshotTest` `LUXIUM_TEST_TOGGLE=1`, a still scene (time frozen, no entities or water, no sky in view) turned off, on, off/on on consecutive frames, a setting changed live (`T_SATURATION=0.00` turns it grey, then the file is deleted), and off again. Pixels compared with `mod/scripts/ImageDiff.java`:
  - live off (right after, 10 s later, and after the setting changes) vs a run started with `enableShaders=false` and Improved Transparency off (`LUXIUM_TEST_NO_OIT=1`): **0 of 409,920 pixels differ** in all three;
  - shaders on after a reload vs at start: 38% of pixels differ, 4.4% by more than 8; two frames 12 ticks apart of the same still clip: 27% and 3.2%. Spread evenly, brightest on the waving leaves: Complementary's temporal noise, not the reload.
  - 0 validation messages in every run; still clip after all the toggles 0.010% (limit 0.1%); regression scene unchanged (walk clip 0.16%, the known shimmer).
- A reload takes about 2 s on a warm Metal cache (the frame stalls, as in Iris).

### Slice B: the Shader Packs screen and its three ways in (2026-11-23, ✅)
- **Screen** `gui/ShaderPackScreen` (vanilla `ObjectSelectionList`, buttons and `HeaderAndFooterLayout`; behaviour from Iris, credited in NOTICE): an on/off row (inactive with no packs, never the selected row), one row per pack (applied pack yellow, all grey while off; clicking a pack turns shaders on), "Open Shader Pack Folder..." (`Blaze3D.openPath`), Cancel / Apply / Done (Done, Esc and double-click apply). Apply writes `config/luxium.properties` (`LuxiumConfig.write`) and calls `requestReload` only when something changed. Labels in `assets/luxium/lang/en_us.json` (loaded without Fabric API: Sodium bundles Fabric's resource loader).
- **Ways in:**
  - Video Settings: `VideoSettingsScreenMixin` adds a "Shader Packs..." button right under the graphics preset (after the second `OptionsList.addBig(OptionInstance)` in `addOptions`).
  - Key **I** (`gui/LuxiumKeys`, category "Luxium"): `OptionsKeysMixin` appends it to `Options.keyMappings` at the head of `load` (so it's saved and shown in Controls, no Fabric API), `MinecraftKeysMixin` reads it at the head of `Minecraft.handleKeybinds`. O was the plan, but vanilla 26.3 already binds O to its friends list; the first test run pressed O and got `FriendsListConfirmScreen`. Iris 26.3 uses I too.
  - Mod Menu: `compat/ModMenuEntry` (`"modmenu"` entrypoint in fabric.mod.json, `getModConfigScreenFactory` → `ShaderPackScreen::new`); `compileOnly com.terraformersmc:modmenu:21.0.1` (non-transitive) from maven.terraformersmc.com. Not yet run with Mod Menu installed: that's slice D.
- `src/main` uses no Fabric API class (checked with grep); the game tests run with Fabric API, so "without Fabric API" isn't run-tested yet.
- **Test:** `LUXIUM_TEST_MENUS=1` (`ScreenshotTest.menuTest`), with real input where Fabric's test API allows: opens Video Settings and presses the button (Fabric's `clickScreenButton` doesn't search inside option lists, so the test finds it in the widget tree), turns shaders off and applies (checks the pack is unloaded and the file says `enableShaders=false`), Done back to Video Settings, then the I key in the world, picks the pack (turns shaders on), Done (screen closes, pack active, file says true), then I, off, Cancel (nothing changes). 7 shots, 0 validation messages. The toggle test still gives 0 differing pixels for live off.
