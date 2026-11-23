# Announcement drafts

Drafts only; nothing here is posted. Wave 1 (now) asks for testers. Wave 2 (the broad push: r/Minecraft, YouTube, Show HN) waits for the in-game menus release.

## Wave 1: r/macgaming

**Title:** I made a mod that runs Minecraft shader packs on Metal (Apple Silicon). Looking for alpha testers.

**Post** (attach the hero shot and the Luxium-vs-Iris side-by-side):

> Shader packs like Complementary need OpenGL, and on a Mac that means Apple's old, deprecated OpenGL driver. [Metallum](https://modrinth.com/mod/metallum-mc) already moves Minecraft itself onto Metal, but Iris doesn't work on top of it, so you had to choose: Metal or shaders.
>
> **Luxium** is a Fabric mod that runs Iris/OptiFine-format shader packs on Metallum's Metal backend, unmodified. It translates each pack's GLSL to Metal Shading Language when the game starts.
>
> On my M1 Max with Complementary Unbound (default settings, 1708×960, render distance 12), it's about 10% faster than Iris on OpenGL (90 vs 81 fps at the beach), and the worst frame was 20 ms against 85 ms.
>
> **It's an alpha, tested on one Mac.** What I need most is people on other chips (M2, M3, M4, base/Pro/Max) and other macOS versions to try it and tell me what breaks:
>
> - Minecraft 26.3, Fabric, Sodium 0.9.2, Metallum 0.0.25
> - Complementary Unbound r5.10 is the best-tested pack; Reimagined also loads. Bliss and Solas don't yet.
> - No in-game shader menu yet (next release). Settings live in a text file.
>
> Download and install steps: https://github.com/hecbatista/luxium/releases
> Bug reports: https://github.com/hecbatista/luxium/issues (your `latest.log` and Mac model help a lot)
>
> Screenshots: Complementary Shaders by EminGT, running under Luxium.

## Wave 1: r/feedthebeast

Same as above, with the title **[Fabric 26.3] Luxium: Iris/OptiFine shader packs on Metallum's Metal backend (alpha)** and one more technical paragraph after the second one:

> Under the hood: it reads the pack the way Iris does (per-world programs, `shaders.properties`, settings), preprocesses with JCPP, rewrites the GLSL so it survives glslang → SPIR-V → SPIRV-Cross → MSL, lays out the uniforms as std140 blocks, and drives the pack's gbuffer, shadow, deferred and composite passes through Metallum's device. Packs that need something it doesn't support yet fall back to vanilla with a notice instead of crashing.

## Message to Metallum's author (GitHub discussion or Discord, your call)

> Hi kokodio, thanks for Metallum! I built Luxium, a Fabric mod that runs Iris/OptiFine shader packs on top of Metallum's Metal backend: https://github.com/hecbatista/luxium. It's an alpha (0.1.0-alpha.1, built against 0.0.25 and checked against your Modrinth jar). If you think it's useful for your users, a link from Metallum's README or Modrinth page would mean a lot. Happy to hear about anything I'm doing that gets in the way of your plans for Metallum's internals, too: Luxium mixes into `MetalCrossShaderCompiler` and its `Compiled` record.
