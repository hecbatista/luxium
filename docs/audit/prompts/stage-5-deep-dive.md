# Stage 5: Deep dive (heaviest tier)

Input: the FINDINGS.md rows I (the user) pasted below, each rated critical or high, or medium with low confidence, and marked "unverified". Work on ONE finding at a time.

[PASTE ROWS HERE]

For each finding:
1. Reconstruct the full call path from the frame hook (mixin/GameRendererRenderMixin.java or mixin/LevelRendererTerrainMixin.java) to the line in question, with file:line at every hop.
2. State the minimal scenario that triggers it: pack/program, world, frame order, window resize, settings, thread timing. If the scenario cannot occur, say why and downgrade.
3. Say exactly what the user should observe to confirm it: a Metal validation message, a value in a GPU frame capture (which pass, which attachment, which buffer offset), or a screenshot difference vs Iris.
4. Describe the fix in prose (no edits, no patches), and what else the fix could break.
5. Give a final verdict: CONFIRMED BY CODE / PLAUSIBLE / FALSE POSITIVE, with confidence.

Rules: you are READ-ONLY (see /AGENTS.md). Do not edit files, run builds, or run the game. Cite `file:line` (with a short quote) for every finding. If you cannot verify something from files you opened, mark it "unsure" and put it under Open questions; never guess at Iris, Metal, Metallum, Sodium or Minecraft behavior (check references/ if present). For each finding give: ID, file:line, issue, severity (critical/high/medium/low/info), your confidence (high/medium/low), and what would confirm it. Output as rows matching docs/audit/FINDINGS.md columns, followed by an Open questions list. Prefer few well-evidenced findings over many speculative ones.
