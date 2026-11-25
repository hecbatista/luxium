# Stage 3: Iris parity (standard tier)

Compare Luxium to Iris. Iris source is in references/iris (pinned commit: see .claude/skills/luxium-references/SKILL.md). If it is absent, say so and stop at "unsure". Cite BOTH sides: Iris file:line and Luxium file:line.

Scope: MetalShadersClient.java (prepareChain), pack/RenderTargetPlan.java, pack/PackDirectives.java, pack/ProgramSwitches.java, pack/StandardDefines.java, render/PackChain.java, render/TerrainTakeover.java, render/EntityTakeover.java, render/SkyTakeover.java, render/ShadowTargets.java, render/CustomTextures.java, uniform/GameUniforms.java, uniform/Formula.java, uniform/CustomUniforms.java, uniform/ShadowMatrices.java, mod/NOTICE.

Check:
1. Program stages and fallback chains (Iris ProgramId) vs MetalShadersClient firstOf/byPipeline. Which Iris programs are absent in Luxium (begin*, prepare*, shadowcomp*, clouds, damagedblock, armor glint, line, basic, dh_*)? Note prepare*/begin*/shadowcomp* are classified full-screen at ProgramTranslator.java:139 but filtered out at MetalShadersClient.java:492.
2. Pass order, ping-pong flip, DRAWBUFFERS / RENDERTARGETS routing, scale.*, size.buffer.*, flip.*, clear/clearColor, promoted buffers, mipmaps, texture.<stage>.* overrides.
3. depthtex0/1/2 and shadowtex0/1/shadowcolor contents at each stage vs Iris (hand included or not).
4. Uniforms: each name in GameUniforms and Formula vs Iris CommonUniforms / IrisExclusiveUniforms / MatrixUniforms / SystemTimeUniforms: units, ranges, smoothing, previous-frame values on frame 0, camera-relative positions, sunPathRotation, sun/moon angle.
5. Shadow: matrices, interval snapping, culling (ShadowCulling), entity/block-entity casters, translucent handling vs Iris ShadowRenderer.
6. Deliberate deviations (COLORED_LIGHTING 0 under MC_OS_MAC, OIT off, Improved Transparency off, no compute): list them and the pack features each disables.

Output a parity table (item | Iris file:line | Luxium file:line | verdict) then findings.

Rules: you are READ-ONLY (see /AGENTS.md). Do not edit files, run builds, or run the game. Cite `file:line` (with a short quote) for every finding. If you cannot verify something from files you opened, mark it "unsure" and put it under Open questions; never guess at Iris, Metal, Metallum, Sodium or Minecraft behavior (check references/ if present). For each finding give: ID, file:line, issue, severity (critical/high/medium/low/info), your confidence (high/medium/low), and what would confirm it. Output as rows matching docs/audit/FINDINGS.md columns, followed by an Open questions list. Prefer few well-evidenced findings over many speculative ones.
