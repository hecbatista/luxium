---
name: luxium-references
description: Where to look things up for Luxium, and which reference answers which question. Covers the Minecraft 26.3 sources, Metallum, Iris, Vitrail and GaelQuinta's minecraft-metal-shaders, with their pinned commits, layouts, licenses and token-safe search commands, plus the project's versions. Use before checking how Iris or Vitrail does something, verifying a Minecraft or Metallum API, borrowing anything from a reference (credit rules), or searching reference code.
---

# Luxium references

Everything under `references/` is read-only and git-ignored. Never edit it, and never build our
code there.

## Which reference answers what
| Question | Look in | Notes |
|---|---|---|
| Does this Minecraft class, method or field exist? What does vanilla do? | `references/minecraft-26.3-sources/` | Mojang names only. If it isn't here, it doesn't exist in 26.3. |
| How does the Metal backend do X? What does Metallum compile or bind? | `references/metallum/` | The README says "vibecoded as hell". Verify against the Minecraft sources and Apple's docs. |
| What should the pack see? (uniform values, pass order, directives, targets, ids) | `references/iris/` | The behavioural reference: packs are written against Iris. A difference from Iris is a Luxium bug. |
| How do you translate GLSL for a non-GL backend? Pack format details? | `references/vitrail/`, its `docs/` first | Closest prior art (Vulkan). Mostly AI-typed: verify. |
| Apple-GPU costs (bandwidth, compression, TBDR, MetalFX), compile pools, offline Metal checks | `references/gaelquinta-metal/` | Writes its own native shaders on mcopt and runs no packs, so its pictures can't tell you how Complementary should look. |
| Metal API facts | `apple-docs` MCP server, `man MetalValidation` | The `axiom-graphics` skill is a guide, not a source. |
| What Complementary is meant to look like | The Iris runner (`luxium-gametest` skill) | Not any of the above. |

## Pinned commits (cloned 2026-11-20, gaelquinta 2026-11-23)
| Repo | Branch | Commit | License | Notes |
|---|---|---|---|---|
| metallum (kokodio/metallum) | master | 467d312b | MIT | `mod/` builds against this local jar. It has `5bee03e fix: synchronize MetalDevice.getOrCompileFunction()`, which Modrinth's 0.0.25 lacks. |
| iris (IrisShaders/Iris) | **26.3** | 671a5c87 | LGPL-3.0 | The default branch `26.1` is wrong for us. `26.3` matches Metallum (`MINECRAFT_VERSION "26.3"`, `sodium-fabric:0.9.2+mc26.3`). `26.3-new` moved to Sodium 0.9.3-beta.5. |
| vitrail (avpbynf/Vitrail-Shaders) | main | 7a3cdcbe | LGPL-3.0 | Builds for 26.2 by default: use `-Pminecraft=26.3` (`versions/26.3.properties`). |
| gaelquinta-metal (GaelQuinta/minecraft-metal-shaders) | main | 6e0a0af | Apache-2.0 | A fork of mcopt. |
| axiom-graphics skill | main | ffafd3b3 | MIT | `.claude/skills/axiom-graphics/.source-commit` |

The Minecraft sources are decompiled from Loom's genSources jar:
`references/metallum/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-5309bbdd7f/26.3/minecraft-merged-5309bbdd7f-26.3-sources.jar`.
To regenerate it, run `./gradlew genSources` in `references/metallum`.

## Layouts
- **Minecraft:**
  - `net/minecraft/client/renderer/` is the game renderer (`LevelRenderer`, `GameRenderer`, `ShaderManager`).
  - `com/mojang/renderpearl/` is the graphics API: `api/` is what mods call, `frontend/` (`PipelineBuilder`: GLSL→SPIR-V) sits in front of the backends, `backend/` holds the GL, Vulkan and backend interfaces.
- **Metallum:**
  - `src/main/java/com/metallum/{render,mixin,compat}`.
  - In `render/`: `MetalDevice` (functions and depth states), `MetalCrossShaderCompiler` (SPIR-V→MSL), `MetalCompiledRenderPipeline`, `MetalGpuTexture`.
  - Mixin configs in `src/main/resources/`.
- **Iris:** `common/src/main/java/net/irisshaders/iris/`:
  - `shaderpack/` loads and parses packs;
  - `pipeline/` is the render pipeline (`IrisRenderingPipeline`);
  - `uniforms/`, `shadows/`, `targets/`, `samplers/`;
  - `gl/` holds the OpenGL wrappers;
  - `mixin/` holds the game hooks.
- **Vitrail:**
  - `common/src/main/java/dev/vitrail/` (`pack/`, `glsl/`, `render/`, `uniform/`).
  - Read `docs/` first: `translation.md`, `frame.md`, `pack-format.md`, `render-scale.md`, `sky-and-shadows.md`, `internals/`.
- **gaelquinta-metal:**
  - `docs/research/apple-gpu.md`: Apple-GPU costs measured on an M4.
  - `docs/research/iris-internals.md`: Iris on 26.3, and a translation spike.
  - `docs/research/critique.md`: why they didn't run packs.
  - `docs/dev/OPTIMIZATION_LOG.md`, `TAAU.md`, `VL_FLICKER.md`.
  - `tools/shade/*`: offline Metal checks (`pipecheck.m`, `layoutcheck.m`, `compilebench.m`).
  - `bench/HARNESS.md`: benchmarking rules.
  - The compile pool is `metal/src/main/java/mcopt/metal/shade/ShadeCompile.java`; the native side is under `metal/src/main/native/`.

## Searching without flooding the context
- **Our code and Minecraft's:** use `graphify query "<question>" --budget 1500` first (an AST graph of `mod/src` plus the Minecraft sources). Refresh it with `mod/scripts/graph-update.sh`. The graph doesn't cover Iris, Vitrail, Metallum or gaelquinta.
- **grep in reference trees:** always restrict to source files and quote the glob, or zsh rejects it:
  `grep -rn --include='*.java' "class LevelRenderer" references/minecraft-26.3-sources`.
  A bare `grep -rn` there also matches `graphify-out/graph.json` (64 MB on one line).
- **Narrow the path:** for Iris, grep `references/iris/common/src/main/java/net/irisshaders/iris/<area>`, not the repo root.
- **Long docs:** list the headings first (`grep -n '^#' file.md`), then read only the section you need.
- **Broad questions across a reference** ("how does Iris do X end to end"): delegate to a Sonnet Explore or general-purpose subagent. Name the paths above, and ask for file:line citations and a short report.

## Borrowing: credit rules
- **Iris, Vitrail (LGPL-3.0):** credit any code, table, grammar or formula file by file in `mod/NOTICE`: the source project and class, what was adapted, and what changed. Follow `references/vitrail/NOTICE`'s format.
- **Metallum, Axiom (MIT):** keep their copyright notice if any code is copied.
- **gaelquinta-metal (Apache-2.0):** if code is copied, keep its license and NOTICE text, and credit it in `mod/NOTICE`. Ideas need no notice, but say where they came from in the code comment, as `PipelineCompiler` does. Its NOTICE also lists MIT parts taken from Photon and others: check there before borrowing from those files.
- **Complementary:** never commit any of its files; the mod must run it unmodified.

## Versions (from `references/metallum/gradle.properties` and `build.gradle`)
- Minecraft 26.3, Fabric Loader 0.19.5, Loom 1.16-SNAPSHOT (resolved to 1.16.3), Sodium `mc26.3-0.9.2`, metal-jvm 0.1.0.
- Java 25 (`options.release = 25`); the local JDK is Temurin 25.0.5 (`/usr/libexec/java_home -v 25`).
- Gradle 9.4.1 via the wrapper.
