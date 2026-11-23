# Luxium

GitHub repo: `hecbatista/luxium` (renamed from `metal-shaders` on 2026-11-20; GitHub redirects the old URL). The local folder is still `metal-shaders/`.

## Goal
Load and run Iris/OptiFine-format shader packs (the `shaderpacks/` format) on Minecraft's **Metal** backend provided by the Metallum mod, on Apple Silicon macOS.

Status (2026-11-23): steps 1–7 done; step 8, the first public alpha: **0.1.0-alpha.1 published** as a GitHub pre-release; **benchmarking in progress** (first Luxium-vs-Iris results in `docs/release/benchmarks.md`). Next release: in-game menus (`docs/release/menus-plan.md`). The pack draws the sky, terrain, water, entities, block entities, particles, weather, the hand and the shadow map through its own programs, with the user's pack settings, in all three worlds. The user's first play sessions found no remaining visible differences from Iris in normal play.

Roadmap: 1 mod skeleton ✅ · 2 pack reader ✅ · 3 preprocessor ✅ · 4 translator ✅ · 5 uniforms ✅ · 6 render pipeline ✅ · 7 polish ✅ (7a–7i) · 8 alpha release (in progress: benchmarking; checklist in `docs/release/alpha.md`).

Target pack: **Complementary Unbound r5.10**. Its license (Complementary License Agreement 1.8) forbids redistributing or modifying it, so never commit any of its files. The mod must run it unmodified. Under Iris's `MC_OS_MAC` define, the pack forces `COLORED_LIGHTING 0`, which turns off its compute, SSBO and 3D-image features.

## History and design notes: read on demand, never whole
This file stays short because every session and every subagent loads it. The details of each step (what was built, the traps found, game-test evidence, known gaps) live in `docs/history/`. **Grep there before re-investigating anything.**

| File | Covers |
|---|---|
| `docs/history/steps-1-5.md` | pack reader, preprocessor (JCPP), translator (no glsl-transformer: it's AGPL), std140 layout, uniforms and pack formulas, test and scoreboard results, translator gaps |
| `docs/history/step-6.md` | 6a first pixels, 6b final, 6c render targets and chain, 6d gbuffers (sky, Sodium terrain, water and the catch, entities, ids, particles, weather, hand, spider eyes and beams), 6e shadows, 6f correctness, plus "Facts for step 6" (binding by name, full-screen passes, Vitrail's 26.3 seam) |
| `docs/history/step-7.md` | 7a pack settings, 7b Nether/End, 7c off-screen shadow casters, 7d OIT off, 7e ids, 7f performance, 7g compile pool and Metal cache warm-up, 7h custom textures, 7i view bobbing and entity shadows |
| `docs/release/alpha.md` | step 8: the alpha release checklist, benchmark plan, known issues for the README |
| `docs/history/testing.md` | full notes on the client game tests; running the game by hand; validation facts and harmless startup warnings |

When a slice is finished, append its result to the right `docs/history/` file, and add only a one-line pointer here if it changes how to work.

## How to work here
- Step 7 and 8 slices: check in with the user before each one. Every slice's final pass includes the frame-sequence flicker check (user request 2026-11-20).
- Project skills (`.claude/skills/`). Load the one that fits before starting:
  - **`luxium-gametest`**: run the game (`mod/scripts/gametest.sh`, always under validation), review contact sheets, compare with vanilla or Iris, flicker check, delegate to subagents (Sonnet for run-and-check, at most 2 game-running agents, one worktree each).
  - **`luxium-offline-check`**: everything without the game. Unit tests through `mod/scripts/test.sh` (never a bare `./gradlew test`: it floods the output), the translation scoreboard, compiling one shader by hand, `LUXIUM_DUMP_SHADERS`.
  - **`luxium-references`**: which reference answers what (Minecraft sources, Metallum, Iris, Vitrail, gaelquinta-metal), pinned commits, layouts, token-safe grep, credit rules, versions.
  - **`axiom-graphics`**: Metal and GLSL→MSL reference tables.
- Finding code: `graphify query "<question>" --budget 1500` answers from a free AST graph of `mod/src` and the Minecraft sources. Use it before reading big files; refresh it with `mod/scripts/graph-update.sh`.
- Known gaps:
  - Pack settings are read only at load (no profiles, no in-game screen).
  - `dimension.properties` isn't preprocessed.

## Layout
```
metal-shaders/
├── CLAUDE.md, .mcp.json       this file; project MCP server apple-docs
├── .claude/skills/            the skills above
├── mod/                       OUR mod **Luxium** (Fabric, id `luxium` since 2026-11-23, Java package still io.github.hecbatista.metalshaders; LGPL-3.0-only)
│   └── scripts/               gametest.sh, test.sh, ContactSheet.java, FlickerCheck.java, check-preprocessed.sh, graph-update.sh
├── docs/history/              per-step notes (table above)
├── branding/                  Luxium icon (`make_icon.py`)
├── experiments/               small out-of-game tests (GLSL → SPIR-V → MSL → metallib)
└── references/                read-only, git-ignored: minecraft-26.3-sources, metallum, iris, vitrail, gaelquinta-metal (see luxium-references)
```

## Rules

### Names: Mojang official only
- Minecraft 26.x ships **unobfuscated** (verified: the genSources jar has 7,302 files and no `class_1234`-style names).
- Use Mojang's official names only. **Never Yarn names** (`class_XXXX`, `method_XXXX`, or Yarn-style names from old tutorials).
- **Before using any Minecraft class, method or field, find it in `references/minecraft-26.3-sources/`** (for example `grep -rn --include='*.java' "class LevelRenderer" references/minecraft-26.3-sources`). If you can't find it there, it doesn't exist in 26.3.

### Verify, don't trust
- Never guess versions, URLs, class names or APIs. Read the actual file or doc and cite it.
- Metallum's README calls it "vibecoded as hell", and Vitrail's Modrinth page says AI tools did most of its typing. Verify their behavior against the Minecraft sources and Apple's docs before copying it.
- For Metal APIs, check Apple's docs (the `apple-docs` MCP server) or `man MetalValidation`. The `axiom-graphics` skill is a guide, not a primary source.

### Licensing
- **Iris and Vitrail are LGPL-3.0.** Any code, tables, grammars or formulas borrowed from them must be credited **file by file** in `mod/NOTICE`: the source project and class, what was adapted, and what changed (format of `references/vitrail/NOTICE`).
- Metallum and the Axiom skill are MIT; gaelquinta-metal is Apache-2.0. Keep their notices if any code is copied.

## Running and testing
- Always run the game with Metal's API and GPU validation on (`MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1`; `gametest.sh` sets both). Startup must print `Metal API Validation Enabled` and `Metal GPU Validation Enabled`. The 2026-11-20 baseline (plain Metallum) had zero validation messages, so any message is ours.
- `mod/` builds against the local Metallum jar: run `cd references/metallum && ./gradlew build` once.
- Is the game running? Use `pgrep -f 'java.*devlaunchinjector'`. A bare `devlaunchinjector` also matches scripts.
- `eula = true` in `mod/build.gradle`: the user agreed to the Minecraft EULA for the tests on 2026-11-20.
- Running by hand, healthy-start log lines and harmless warnings: `docs/history/testing.md`.

## Constraints
- The disk was about 13 GB free at setup (15 GB on 2026-11-23). Check `df -h ~` before large downloads or builds.
