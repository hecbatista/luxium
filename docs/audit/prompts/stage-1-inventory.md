# Stage 1: Inventory (light tier)

Read /AGENTS.md and docs/audit/PLAN.md section "Pipeline map". Your job is to CHECK the map, not to find bugs.

Scope (mod/src/main/java/io/github/hecbatista/metalshaders/ unless noted): MetalShadersClient.java, render/PackChain.java,
glsl/ProgramTranslator.java (signatures + javadoc only), mixin/GameRendererRenderMixin.java, mixin/LevelRendererTerrainMixin.java,
mixin/SkyRendererMixin.java, render/TerrainTakeover.java (signatures), mod/src/main/resources/luxium.mixins.json.

Tasks:
1. For each row/step of the Pipeline map, say CONFIRMED / WRONG / STALE with the real file:line. Fix any wrong citation.
2. Find every call site of TerrainTakeover.drawShadow, EntityTakeover.drawShadowCasters, SkyTakeover.* and ShadowTargets.*, and state the frame order of the shadow pass relative to sky, opaque terrain, deferred, translucents, hand, composite, final.
3. List every Mixin target (class + method string + injection point) in mixin/. For each, say whether the target exists in references/minecraft-26.3-sources, references/metallum or Sodium sources if present, else "unsure".
4. List every environment variable the code reads (grep getenv) and what it changes.
5. Name anything in the map you could not confirm.

Rules: you are READ-ONLY (see /AGENTS.md). Do not edit files, run builds, or run the game. Cite `file:line` (with a short quote) for every finding. If you cannot verify something from files you opened, mark it "unsure" and put it under Open questions; never guess at Iris, Metal, Metallum, Sodium or Minecraft behavior (check references/ if present). For each finding give: ID, file:line, issue, severity (critical/high/medium/low/info), your confidence (high/medium/low), and what would confirm it. Output as rows matching docs/audit/FINDINGS.md columns, followed by an Open questions list. Prefer few well-evidenced findings over many speculative ones.
