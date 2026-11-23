# Pack compatibility log

One entry per pack tested, newest first. Packs are pinned in `mod/scripts/testpacks.tsv` and tested
one at a time with the flow in `mod/scripts/PACK-TESTING.md`:
1. offline scoreboard (`./gradlew packCorpus`);
2. game test on the tour stops "build zoo,zoo,beach" with Metal API and GPU validation;
3. the same stops under Iris 1.11.7 (OpenGL) for comparison.

Earlier results (Complementary Unbound r5.10, Complementary Reimagined r5.9.3, Bliss v2.1.2,
Solas v3.9) are in `docs/release/alpha.md`, "Fails safe".

## Complementary Reimagined r5.10 (2026-11-23): works

- Offline: 219/219 programs preprocess; **90/90** reach Metal.
- In game: loads, 0 validation messages, no crash. 30 formulas loaded, 1 dropped
  (`inIceCaves`: `BIOME_ICE_CAVES` doesn't exist in 26.3; Iris drops it too).
- Side by side with Iris on the zoo and beach stops: sky, clouds, water, terrain, entities,
  block entities, signs and frames, glass and portals match.
- Differences:
  - The End portal and End gateway starfield is clearly dimmer than under Iris (fewer, fainter
    stars). Already seen with Unbound (`docs/history/step-7.md`, "The End portal's starfield is a
    little dimmer"); not specific to this pack.
  - Mob poses and the beach-sprint position differ by timing between the two runners.

## BSL v10.1.8, after the formula fix (2026-11-23): renders, matches Iris

- Fixes: `uniform/Formula.java` gains Iris's remaining scalar functions (`frac`, `fmod`,
  `remainder`, `sign`, `tan`/`asin`/`acos`/`atan`/`atan2`, `torad`/`todeg`/`radians`/`degrees`,
  `exp2`/`exp10`/`log2`/`log10`, `mix`, `edge`, `between`, 3-argument `equals`).
  `uniform/GameUniforms.java` supplies `eyeBrightnessSmooth` (Iris's smoothing, default half-life
  10; the pack's `eyeBrightnessHalflife` directive isn't read yet) and `bedrockLevel`.
- BSL: 27 formulas loaded, **0 dropped** (8 before); every block value supplied except the
  matrices that are filled in per draw.
- Game test: 0 validation messages, no crash. Side by side with Iris, the zoo and beach shots now
  match: midday sky and light, shadows, water, clouds, entities, block entities, portals and
  glass. Unit tests 111 pass; Complementary Unbound still 90/90.
- The beach-sprint shot differs (Luxium over water, Iris on the sand runway) because the
  Luxium player is about 10 ticks further along the sprint (Luxium frame 20 ≈ Iris frame 30),
  not because sand is missing. A tour timing difference between the two runners, not rendering.
- Not compared yet: night, caves, rain/snow, the Nether and the End.

## BSL v10.1.8, after the translator fixes (2026-11-23): renders, light wrong

- Fixes (`glsl/NameFixes.java`, `ProgramTranslator.FUNCTION_RENAMES`): a sampler named `texture`
  becomes `gtexture` (Iris's rule); C++/Metal words used as names (`new`, `and`, `fast`,
  `float2`...) become `luxium_<name>`, with uniform values still looked up by the pack's name;
  Iris's remaining legacy texture functions (`texture2DGradARB`, `texelFetch2D`, `textureSize2D`...).
- Offline: **85/85**. Complementary Unbound still 90/90; unit tests 109 pass.
- In game: loads, 0 validation messages, no crash; shadows, water, clouds, entities, block
  entities, portals and translucents all draw.
- Wrong: the whole scene is lit like sunset (orange sky and clouds, dim warm light) where Iris
  shows midday. Cause: BSL's `timeAngle` chain in shaders.properties uses `frac()`, which
  `uniform/Formula.java` doesn't have, so 8 formulas are dropped and `timeAngle` and
  `timeBrightness` stay 0. Also unsupplied: `eyeBrightnessSmooth`, `bedrockLevel`.
- Unclear: in the beach-sprint shot the sand blocks in front of the player are missing under
  Luxium; recheck after the light fix.

## BSL v10.1.8, first run (2026-11-23): falls back to vanilla

- Offline: 185/185 programs preprocess; **38/85** vertex+fragment pairs reach Metal.
  - 44 × glslang: `'texture' : can't use function syntax on variable`. Every gbuffers fragment
    program declares `uniform sampler2D texture;` (the old OptiFine name for the albedo atlas), so
    the GLSL 4.x function `texture()` is hidden by the variable.
  - 3 × `xcrun metal`: `deferred` has a local `bool new = ...`; `new` is a C++ keyword, so MSL
    rejects it (`expected unqualified-id`). spirv-cross passes the name through.
  - Hidden behind those: `gbuffers_water` calls `texture2DGradARB` (ARB_shader_texture_lod), which
    the translator doesn't rename yet.
- In game: `Shader pack BSL_v10.1.8.zip can't be used: its program deferred: the device returned
  no pipeline`; Metallum logs the `new` error. Vanilla rendering with the notice, **0 validation
  messages**, no crash.
- Under Iris: renders as BSL (blue-grey tint, soft shadows, volumetric haze).
- Hand check: renaming `texture` → `gtexture` (as Iris's `CommonTransformer` does), `new` →
  another name, and `texture2DGradARB` → `textureGrad` makes every failing world0 program compile
  through glslang, spirv-cross and `xcrun metal`. Luxium's albedo binding already accepts
  `gtexture`. Whether it then renders correctly is untested.
