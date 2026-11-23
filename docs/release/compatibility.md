# Pack compatibility log

One entry per pack tested, newest first. Packs are pinned in `mod/scripts/testpacks.tsv` and tested
one at a time with the flow in `mod/scripts/PACK-TESTING.md`:
1. offline scoreboard (`./gradlew packCorpus`);
2. game test on the tour stops "build zoo,zoo,beach" with Metal API and GPU validation;
3. the same stops under Iris 1.11.7 (OpenGL) for comparison.

Earlier results (Complementary Unbound r5.10, Complementary Reimagined r5.9.3, Bliss v2.1.2,
Solas v3.9) are in `docs/release/alpha.md`, "Fails safe".

## BSL v10.1.8 (2026-11-23): falls back to vanilla

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
