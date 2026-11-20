# metal-shaders

## Goal
Load and run Iris/OptiFine-format shader packs (the `shaderpacks/` format) on Minecraft's **Metal** backend provided by the Metallum mod, on Apple Silicon macOS.

Status (2026-11-20): steps 1–2 of 7 done. `mod/` loads next to Metallum and, at startup, logs each pack in `shaderpacks/`: its shaders folder, world-to-folder map and program-file counts (`pack/PackReader.java`). It doesn't read shader code or touch rendering yet.

Roadmap: 1 mod skeleton ✅ · 2 pack reader ✅ · 3 preprocessor (`#include`, `#if`, settings, `MC_OS_MAC`) · 4 translator (GLSL → Vulkan-style GLSL; goal: every Complementary program compiles to MSL outside the game) · 5 uniforms · 6 render pipeline · 7 polish. Tests: `./gradlew test` in `mod/` (JUnit, no game needed).

Known gap: `dimension.properties` is read as plain properties. Iris preprocesses it first (`ShaderPack.java:126`), so `#if` lines in it are ignored until step 3.

Target pack: **Complementary Unbound r5.10**. Its license (Complementary License Agreement 1.8) forbids redistributing or modifying it, so never commit any of its files. The mod must run it unmodified. Under Iris's `MC_OS_MAC` define, the pack forces `COLORED_LIGHTING 0`, which turns off its compute, SSBO and 3D-image features.

## Layout
```
metal-shaders/
├── CLAUDE.md                  this file
├── .mcp.json                  project-scoped MCP server: apple-docs
├── .claude/skills/axiom-graphics/   Metal/GLSL→MSL reference skill (copied from CharlesWiltgen/Axiom, MIT)
├── mod/                       OUR mod (Fabric, id `metalshaders`, package io.github.hecbatista.metalshaders)
├── experiments/               small out-of-game tests (01-glsl-to-metal: GLSL → SPIR-V → MSL → metallib)
└── references/                read-only reference code; do not edit, build our own code elsewhere
    ├── metallum/              kokodio/metallum: the Metal backend we build on (MIT)
    ├── iris/                  IrisShaders/Iris: the reference shader-pack implementation, OpenGL (LGPL-3.0)
    ├── vitrail/               avpbynf/Vitrail-Shaders: runs OptiFine packs on the Vulkan backend (LGPL-3.0)
    └── minecraft-26.3-sources/  decompiled Minecraft 26.3 (Mojang names), extracted from Loom's genSources jar
```

### Pinned references (as cloned 2026-11-20)
| Repo | Branch | Commit | Why this branch |
|---|---|---|---|
| metallum | master | 467d312b | default branch |
| iris | **26.3** | 671a5c87 | Iris's default branch is `26.1`, which is wrong for us. `26.3` matches Metallum: Iris `build.gradle.kts` has `MINECRAFT_VERSION "26.3"` and `sodium-fabric:0.9.2+mc26.3`. (`26.3-new` exists but moved to Sodium 0.9.3-beta.5.) |
| vitrail | main | 7a3cdcbe | builds for 26.2 by default; use `-Pminecraft=26.3` (see `versions/26.3.properties`) |
| axiom-graphics skill | main | ffafd3b3 | recorded in `.claude/skills/axiom-graphics/.source-commit` |

### Versions (from `references/metallum/gradle.properties` and `build.gradle`)
- Minecraft 26.3, Fabric Loader 0.19.5, Loom 1.16-SNAPSHOT (resolved to 1.16.3), Sodium `mc26.3-0.9.2`, metal-jvm 0.1.0
- Java 25 (`options.release = 25`); local JDK is Temurin 25.0.5 (`/usr/libexec/java_home -v 25`)
- Gradle 9.4.1 via the wrapper

### Where things are in each repo
- **Metallum:** `src/main/java/com/metallum/{render,mixin,compat}`; mixin configs in `src/main/resources/` (`metallum.mixins.json`, plus `.sodium`, `.distanthorizons`, `.shine` variants).
- **Iris:** `common/src/main/java/net/irisshaders/iris/`. `shaderpack/` handles pack loading and parsing, `pipeline/` the render pipeline, `uniforms/`, `gl/` OpenGL wrappers, `shadows/`.
- **Vitrail:** `common/src/main/java/dev/vitrail/` (`pack/`, `glsl/`, `render/`, `uniform/`). Read `docs/` first: `translation.md`, `internals/`, `pack-format.md`, `why.md`, `compatibility.md`. Vitrail solves the same problem for Vulkan that we're solving for Metal, so it's the closest prior art.

## Rules

### Names: Mojang official only
- Minecraft 26.x ships **unobfuscated**. This was verified: the genSources jar has 7,302 files and no obfuscated names like `class_1234` or `a.java`.
- Use Mojang's official names only. **Never Yarn names** (`class_XXXX`, `method_XXXX`, or Yarn-style names from old tutorials).
- **Before using any Minecraft class, method or field, find it in `references/minecraft-26.3-sources/`** (for example `grep -rn "class LevelRenderer" references/minecraft-26.3-sources`). If you can't find it there, it doesn't exist in 26.3.
- The original sources jar is at `references/metallum/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-5309bbdd7f/26.3/minecraft-merged-5309bbdd7f-26.3-sources.jar`. To regenerate it, run `./gradlew genSources` in `references/metallum`.

### Verify, don't trust
- Never guess versions, URLs, class names or APIs. Read the actual file or doc and cite it.
- **Metallum's README describes the project as "vibecoded as hell".** Treat its code as something to verify against the Minecraft sources and Apple's Metal docs, not as ground truth.
- Vitrail's Modrinth page says AI tools did most of its typing too. Same rule: verify before copying any of its behavior.
- For Metal APIs, check Apple's docs (the `apple-docs` MCP server) or `man MetalValidation`. The `axiom-graphics` skill is a guide, not a primary source.

### Licensing
- **Iris and Vitrail are LGPL-3.0.** Any code, tables, grammars or formulas borrowed from them must be credited **file by file**: the source project and class, what was adapted, and what changed. Follow the format of `references/vitrail/NOTICE` (it credits Iris this way). Keep a `NOTICE` file in our own code once anything is borrowed.
- Metallum and the Axiom skill are MIT; keep their copyright notice if any code is copied.

## Running and testing
Always test with Metal's API validation and GPU shader validation on. To run **our mod** (with Metallum and Sodium):
```bash
cd references/metallum && ./gradlew build   # once: mod/ uses this local jar (commit 467d312)
cd ../../mod
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1 ./gradlew runClient
```
- Why the local Metallum jar and not Modrinth's `metallum-mc` 0.0.25 (2026-10-10): the local clone has two more commits, including `5bee03e fix: synchronize MetalDevice.getOrCompileFunction()`, a thread-safety fix in shader compiling.
- A healthy start of our mod logs `(metalshaders) Metal Shaders loaded. Metallum version: 0.0.25` (confirmed 2026-11-20: title screen normal, zero validation messages). The game directory is `mod/run/`, so packs go in `mod/run/shaderpacks/`.
- Closing the game with `kill` makes Gradle print `BUILD FAILED ... exit value 143`. 143 means SIGTERM, so this is expected and not an error.
- To run plain Metallum without our mod, use the same command from `references/metallum`.
- Source: `man MetalValidation` (`/usr/share/man/man1/MetalValidation.1`). Any non-zero `MTL_DEBUG_LAYER` enables API validation; any non-zero `MTL_SHADER_VALIDATION` enables GPU validation. More `MTL_DEBUG_LAYER_*` and `MTL_SHADER_VALIDATION_*` options are documented there.
- **Confirmed working (2026-11-20).** The game process got both variables even though the long-running Gradle daemon didn't have them (checked with `ps eww <pid>`), so `--no-daemon` isn't needed. Metal printed `Metal API Validation Enabled` and `Metal GPU Validation Enabled` at startup. If those two lines are missing from a run, validation is off.
- Baseline (2026-11-20): unmodified Metallum, run under API and GPU validation, reached the title screen, created and loaded a Creative world, flew around for about 40 seconds, and saved and quit. There were **zero Metal validation messages** and the world rendered normally (user-confirmed). Any validation failure after our changes is therefore ours.
- `Requested post effect does not exist: minecraft:end_of_frame` is a **vanilla** warning, not Metallum's. `GameRenderer.update()` (`GameRenderer.java:433`) requests `END_OF_FRAME_POST_EFFECT` every frame, and the vanilla client jar ships no `post_effect/end_of_frame.json`. This full-screen hook may be relevant to the shader work later.
- To check whether the game is running, use `pgrep -f 'java.*devlaunchinjector'`. A bare `pgrep -f devlaunchinjector` also matches any script that mentions the word.
- Ignore an HTTP 429 from `sessionserver.mojang.com` at startup: that's Mojang rate-limiting the offline dev player's profile lookup.
- A healthy start logs `(metallum) Metal device: Apple M1 Max` and `Using graphics backend Metal`. The `ClassNotFoundException` warnings for Distant Horizons and `com/bloom/...ShineRenderBackend` are Metallum's optional compatibility hooks and are harmless.

## Tools available
- JDK: Temurin 25.0.5
- `xcrun metal` (Metal Toolchain 27B47, compiler 32023.922)
- `glslangValidator` 16.6.0 (GLSL → SPIR-V, and GLSL validation)
- `spirv-cross` 1.4.363.0 (SPIR-V → MSL and other targets)
- MCP: `apple-docs` (kimsungwhee/apple-docs-mcp, project scope in `.mcp.json`)
- Skill: `axiom-graphics` (see `skills/metal-migration-ref.md` for GLSL→MSL tables, `skills/metal-migration-diag.md` for black screens and frame capture)

## Constraints
- The disk was about 13 GB free at setup. Check `df -h ~` before large downloads or builds.
