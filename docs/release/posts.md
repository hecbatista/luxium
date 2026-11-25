# Announcement drafts

Drafts only; nothing here is posted. Wave 1 (now) asks for testers. Wave 2 (the broad push: r/Minecraft, YouTube, Show HN) waits for the in-game menus release.

## Wave 1: r/macgaming

**Title:** I made a mod that runs Minecraft shader packs on Metal (Apple Silicon). Looking for alpha testers.

**Post** (attach the hero shot and the Luxium-vs-Iris side-by-side):

> Shader packs like Complementary need OpenGL, and on a Mac that means Apple's old, deprecated OpenGL driver. [Metallum](https://modrinth.com/mod/metallum-mc) already moves Minecraft itself onto Metal, but Iris doesn't work on top of it.
>
> **Luxium** is a Fabric mod that runs Iris/OptiFine-format shader packs on Metallum's Metal backend, unmodified. It translates each pack's GLSL to Metal Shading Language when the game starts. (If you've seen [LeadLight](https://github.com/ohjey/leadlight): same goal, different approach. LeadLight is a Vitrail port; Luxium is written from scratch, runs on stock Metallum and Sodium with no Fabric API, and is tested with Metal's validation layer on.)
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

## Reply in Metallum's #general (to ndko, 2026-11-23)

Context: hec posted "working on a shader loader that works alongside metallum…" at 7:10 PM, and ndko replied "There is one already" (meaning LeadLight) at 7:20 PM. The server likes LeadLight and OJ, who is active there, and is wary of AI-made mods. So:
- credit LeadLight first;
- give checkable facts, not "better";
- end with a question so it starts a conversation.

Send it as a reply to ndko's message, with `docs/release/promo/beach-sunset.jpg` attached.

**Version A** (send now; no LeadLight numbers yet):

```markdown
yep, LeadLight is cool, not trying to replace it 🙂 luxium's a different take on the same idea:
- written from scratch (not a Vitrail port), runs on stock Metallum 0.0.25 + Sodium, no Fabric API
- tested with Metal's API + GPU validation on, zero errors is the bar
- checked shot by shot against Iris: Complementary Unbound r5.10 matches in the Overworld, Nether and End
- ~10% faster than Iris on my M1 Max at 12 chunks, worst frame 20 ms vs 85 ms

it's an early alpha and only Complementary is solid right now. Bliss and Solas are next, then an in-game shader menu
https://github.com/hecbatista/luxium

which packs would you guys want working first?
```

**Version B line** (swap in for the "faster than Iris" bullet once `benchmarks.md` has the LeadLight runs). Use only the one that matches the numbers:
- if Luxium is ahead: `- on my M1 Max it's ~N% faster than LeadLight 0.2.0 with Complementary Unbound (same Metallum jar, same test), with steadier frame times`
- if it's close: `- about the same fps as LeadLight 0.2.0 with Complementary Unbound on my M1 Max, with steadier frame times`
- if LeadLight is ahead: leave LeadLight out and keep the Iris bullet. Don't post a comparison Luxium loses without a fix on the way.

Use a Version B line only when the 1% lows and worst frames in `benchmarks.md` back up "steadier frame times". Version A's Iris numbers are already published.

## Message to Metallum's author

> Update 2026-11-23: the first contact already happened, as hec's post in #general. Send this as a follow-up or DM to kokodio, and drop the opening "Hi everyone" framing.

**Where:** Metallum's Discord server, https://discord.gg/bXeC9N9Kqw (linked from its Modrinth page). The GitHub repo has issues but no Discussions, so Discord is the friendlier place for a "hi, I built something on your mod" message. Join, read the rules, and post in a showcase or addons channel if there is one, otherwise the general channel. A DM to the author is fine after that, but not as the first contact. Attach `docs/release/promo/beach-sunset.jpg`.

**Discord message** (copy the block):

```markdown
Hi kokodio and everyone! I built **Luxium**, a Fabric mod that runs Iris/OptiFine shader packs on top of Metallum on Apple Silicon Macs. Packs run unmodified: it translates their GLSL to Metal at load and drives their passes through Metallum's device.

It runs Complementary Unbound r5.10 in all three worlds, and on my M1 Max it's about 10% faster than Iris on OpenGL, with much steadier frame times (worst frame 20 ms vs 85 ms).

It's an alpha (0.1.0-alpha.1), built against Metallum 0.0.25: https://github.com/hecbatista/luxium (the Modrinth page is in review).

Thanks for Metallum, none of this would exist without it. Two things:
1. If you think it's useful, a link from Metallum's README or Modrinth page would help Mac players find it.
2. Luxium mixes into `MetalCrossShaderCompiler.compile` and reads the MSL from its package-private `Compiled` record, to warm Metal's shader cache on a background thread (it saved 7–8 s of render-thread compiling per world with Complementary). It doesn't change what Metallum compiles or draws. If you plan to change those classes, a heads-up would be great, or I'm happy to send a PR for a small public hook (say, a callback with the compiled MSL) so Luxium doesn't need mixins there.

Testers on other Macs (M2/M3/M4) are very welcome too!
```

**If you'd rather use GitHub:** open an issue at https://github.com/kokodio/metallum/issues titled *Luxium: shader packs on top of Metallum (and a question about MetalCrossShaderCompiler)*, with the same text minus the last line.
