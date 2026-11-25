# Stage 2: Translation correctness (standard tier)

Context: the game is reversed-Z (0..1, far=0) on Metal; packs expect OpenGL depth. Read /AGENTS.md "Conventions and gotchas".

Scope: glsl/ProgramTranslator.java (all), glsl/Std140Layout.java, glsl/Declarations.java, glsl/GlslTokenizer.java,
uniform/UniformWriter.java, uniform/RenderMatrices.java, uniform/ShadowMatrices.java, render/PackChain.java (depth pipeline and
convertDepth, mergeHand, seed, runPasses, ensureTargets), render/ShadowTargets.java, and tests Std140LayoutTest, UniformWriterTest,
ProgramTranslatorTest, TerrainTranslationTest under mod/src/test.

Check, with arithmetic or generated-GLSL evidence:
1. Clip-space depth: every vertex epilogue (scene, shadow, hand squeeze), the gl_FragCoord.z rewrite (which stages? any missed path, e.g. gl_FragDepth writes, discard, early-z?), and the depth-conversion pass. Verify z' maps OpenGL [-w,w] to Metal [0,w] reversed, and shadow forward depth stays 0 = nearest.
2. RenderMatrices.undoRevZ and ShadowMatrices: prove JOML m02/m12/m22/m32 indexing, and what setOrthoSymmetric's last argument means. Matrix upload order in UniformWriter (column-major) and mat3 padding.
3. Texture origin and flips: fullscreen triangle and fsProjection (scale.<program>), gl_FragCoord users, texelFetch, shadow map UV, hand merge, noisetex. Compare against the claim at docs/history/step-6.md:235; say which pass types it does NOT cover.
4. std140: Std140Layout vs the GLSL spec for mat2/mat3/matCxR, bool, int/float arrays, vec3 packing, structs (LuxiumFog). Does UniformWriter write exactly what Std140Layout lays out (including arrays, bvec/ivec, LuxiumFog)? Is member declaration order in the generated block identical to the order passed to Std140Layout.of?
5. Binding: every sampler/uniform name declared by translated programs vs every setUniform name on the host side (PackChain, TerrainTakeover.bind, EntityTakeover, SkyTakeover). Also Sodium u_Globals / push-constant mirror and DynamicTransforms vs the 26.3 and Sodium sources in references/ if present.

Rules: you are READ-ONLY (see /AGENTS.md). Do not edit files, run builds, or run the game. Cite `file:line` (with a short quote) for every finding. If you cannot verify something from files you opened, mark it "unsure" and put it under Open questions; never guess at Iris, Metal, Metallum, Sodium or Minecraft behavior (check references/ if present). For each finding give: ID, file:line, issue, severity (critical/high/medium/low/info), your confidence (high/medium/low), and what would confirm it. Output as rows matching docs/audit/FINDINGS.md columns, followed by an Open questions list. Prefer few well-evidenced findings over many speculative ones.
