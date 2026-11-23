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
- **Key `O` by default**, as Iris, rebindable in Controls.

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
