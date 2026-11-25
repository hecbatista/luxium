# What the Metallum Discord says Luxium should do better

Read on 2026-11-23: the Metallum server's #general, from July to today (searches for shader, fps, stutter, crash, menu, Photon, Distant Horizons, LeadLight), plus the #issues forum. Quotes are paraphrased unless marked. See `leadlight-comparison.md` for the LeadLight facts this builds on.

## The situation in one paragraph
The server has wanted shaders on Metal since July. kokodio, Metallum's author, says shaders are "something separate" from Metallum and will stay out of it. Their July "Iris with Metal" PoC ran BSL and MakeUp only, crashed in the Nether, and was dropped. OJ's Metallum Extra had its own built-in shaders, then gave them up for **LeadLight** (2026-10-18). People now point newcomers to LeadLight. The only reply to your 7:10 PM post was "There is one already" (ndko, 7:20 PM). OJ also says they're "not a developer", are "very busy", "only use Photon", and that other packs work "by happy accident". LeadLight's last commit was on 2026-10-22. Luxium's opening is **the dependable, maintained shader loader**, not the first one.

## What players ask for, most-asked first

### 1. Their own pack, not just Complementary
- Players test many packs. Semix55 posted a list on 10/19 for LeadLight 0.1.0:
  - work: Astralex, Bliss, BSL, Complementary Reimagined and Unbound, MakeUp UltraFast, Mellow ("best performance"), Solas, SuperDuperVanilla ("very stable");
  - fail: Pastel, Photon, ProjectLuma, RenderPearl;
  - partly: Rethinking Voxels (glitches, 10 fps).
- Requested by name: Photon (OJ's own pack), ProjectLuma, Bliss's water.
- **Luxium today:** only Complementary works. Bliss and Solas fall back to vanilla over two missing defines (`MC_HAND_DEPTH`, `MC_RENDER_QUALITY`) and `texelFetch2D`, which looks cheap to fix. This is the gap players will notice first.
- **Do:** run the luxium-offline-check scoreboard over that whole list, fix the cheap failures first (missing defines and legacy function names), and publish a compatibility table that lists more packs than LeadLight's README does.

### 2. Smooth frames, not just high fps
- This server talks more about frame *intervals*, stutter and 1% lows than about average fps:
  - "4k fps with mcopt but the frame intervals are cooked";
  - Metallum on 26.3 "feels way smoother because the frame intervals are more consistent";
  - freezes every ~30 s;
  - on manual fps caps: "has its own issues with stutters".
- The shader-specific complaints are about frame rate: Chemicalvishy said their fps "lowk tanks with shaders in leadlight". wyndchyme asked how shaders can run at only 90 fps when the GPU is at about 20% (a CPU or sync bound).
- Thermals come up too: people cap fps to drop from ~95 °C to ~60 °C.
- **Do:** make frame pacing Luxium's headline:
  - report 1% and 0.1% lows and frame-interval spread in the benchmark (we log p99 and worst already);
  - add a GPU-busy reading (needs Metallum's command-buffer timestamps, already a follow-up in `alpha.md`);
  - first-load hitches: the compile pool and Metal cache warm-up (7g) already help, so say so.

### 3. An in-game pack picker and settings
- People ask for screenshots of the menu UI. fieryflux's Metallum Extra fork was adding "a shader menu, per pack loading, pack options screen".
- Complaints that shaders make the game "too dark" are settings problems that need a menu to fix.
- LeadLight has all of this (inherited from Vitrail).
- **Do:** `menus-plan.md` is the right next release. Include a profile switch (LOW/MEDIUM/HIGH) and, if cheap, live reload (LeadLight's `R` key).

### 4. Distant Horizons
- It's asked about whenever shaders come up ("Is this compatible with Distant Horizons?").
- OJ plays Photon, BSL and Complementary with DH at a locked 60.
- LeadLight supports it; on 26.3, Metallum 0.0.25 itself fixes DH's backend check.
- **Do:** the second feature after menus. Most of the work is the pack's `dh_*` programs; DH's own Metal path is Metallum's.

### 5. Easy install
- Repeated confusion about what to install:
  - "so i install iris and a shader and it will work?";
  - "do you have a modrinth pack?";
  - "Is there a channel for shaders compatible w this?";
  - Iris in the mods folder crashes the game.
- A `visualsforMAC.mrpack` and a "macOS Metal" Modrinth modpack circulate.
- **LeadLight is not on Modrinth.** Modrinth's `leadlight` is an unrelated stained-glass mod.
- **Do:**
  - publish on Modrinth (the draft is ready), making Luxium the shader loader players can find there;
  - publish a one-click Modrinth modpack (Fabric + Sodium + Metallum 0.0.25 + Luxium + Complementary link);
  - detect Iris or OptiFine in the mods folder and show a clear message instead of a crash.

### 6. Colored lighting and the "pretty" packs
- Metallum Extra's built-in look (colored light, soft shadows, reflective water) is loved enough that fieryflux rebuilt it as the "oh shiny!" pack.
- Players expect colored lighting on Metal. Luxium defines `MC_OS_MAC`, which turns it off in Complementary. LeadLight doesn't define it, and has the compute and image support behind that.
- **Do:** after menus and DH, implement compute (`.csh`) and `image.*`, then add a setting to drop `MC_OS_MAC`. Until then, say in the README why colored lighting is off.

### 7. Resolution and upscaling
- "resolution matters so much with shaders"; 1440p is heavy on M-series.
- Users mention Sodium Extras' "reduced macOS resolution", a "Metal Upscaler" (NPU upscaling) mod, and ask about MetalFX.
- LeadLight has a render-scale setting.
- **Do:** a render-scale option is cheap (draw the pack at N%, upscale in `final`) and fits Retina Macs well. MetalFX spatial upscaling would be a real differentiator later; check `MTLFXSpatialScaler` in apple-docs first.

### 8. Mod-heavy setups
- People run 150–350 mods, Lunar Client, Essential, Polytone, Shine, ETF/EMF, ChatPlus.
- Many crash reports come from mods calling OpenGL under Metal.
- **Do:** test Luxium with a typical optimisation set (Sodium Extra, ImmediatelyFast, EntityCulling, ETF/EMF with Fresh Animations, Continuity). LeadLight already lists ETF/EMF/Continuity fixes.

### 9. Trust
- People are openly wary of "vibecoded" mods. kokodio pulled their PoC because "too much AI was used for it" (their words).
- Crash reports get passed around as mclo.gs links.
- **Do:**
  - lead with what can be checked: zero Metal validation messages, unit tests, side-by-side shots against Iris, published benchmark method;
  - keep the README's honest known-issues list;
  - add the GitHub issue form (`alpha.md` already has it as a todo) so reports come with a log.

## Lower priority, by this evidence
- **26.2 support:** LeadLight's 26.3 jar has 41 downloads against 13 for 26.2, and talk has moved to 26.3.
- **Voxy:** reported not to work on Mac.
- **NeoForge:** never asked for in this server.

## Suggested order
1. Pack breadth: the cheap Bliss and Solas fixes, then BSL, Photon, MakeUp and Mellow on the scoreboard.
2. Modrinth listing and a modpack, with an honest "how Luxium differs from LeadLight" line.
3. In-game menus (already planned), with profiles and render scale.
4. Frame-pacing numbers in the benchmark, and GPU timing.
5. Distant Horizons.
6. Compute and custom images, then colored lighting.

## Replying in #general
A short, friendly answer to "There is one already" would help. Credit LeadLight, and say what Luxium does differently:
- written from scratch, not a Vitrail fork;
- zero Metal validation errors;
- needs no Fabric API;
- side-by-side shots against Iris;
- benchmarks (once the Luxium-vs-LeadLight numbers are in);
- in-game menus next.

Avoid "first" or "better". Ask what packs people want.
