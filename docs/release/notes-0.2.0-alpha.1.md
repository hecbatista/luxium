**Second alpha: in-game shader menus, eight more packs, and much faster at short render distances.** Luxium runs Iris/OptiFine shader packs on Minecraft's Metal backend ([Metallum](https://modrinth.com/mod/metallum-mc)) on Apple Silicon Macs. Expect bugs and back up your worlds.

## What's new
### Shader menus
- A **Shader Packs** screen: from Video Settings (Sodium's page too), the **I** key, or Mod Menu's Configure button.
- Pick a pack and turn shaders on or off **without restarting**; the pack reloads live.
- The pack's own **option screens** (profiles, sliders, sub-pages), saved to `shaderpacks/<pack>.txt` as Iris does.
- Luxium's status on the F3 screen.

### More shader packs
Now load and render (each compared with Iris on the zoo and beach test scenes unless noted):
Complementary Reimagined r5.10, Bliss v2.1.2, Solas v3.9 (Iris can't run it on macOS, so not compared), Miniature 2.19, Mellow 3.5 (a little darker than Iris), MakeUp UltraFast 9.6a, Photon v1.4, BSL v10.1.8.
Packs that need compute shaders, custom images or storage buffers (Rethinking Voxels) are refused up front and fall back to vanilla with a notice. See the [compatibility table](https://github.com/hecbatista/luxium#-shader-pack-compatibility).

### Faster
- Tight shadow culling (the camera's view swept toward the sun, as Iris does): +7% at render distance 5, +25–30% at 12.
- Pack mipmaps (`colortexNMipmapEnabled`) through Metal's blit mip generation; also fixes bloom shimmer.
- Scaled passes draw only their scaled corner; fewer depth copies; no shadow-depth copy; half-size reflection buffers (`size.buffer.*`).
- Complementary Unbound, render distance 5, M1 Max, 1708×960: about **157 fps at the beach and 193 fps on the overlook** (0.1.0: 137 at the beach).

### Fixes
- Complementary's glowing sign text keeps the pack's colours.
- From a code audit: `gl_FragDepth` writes are converted like the rest of depth, uniform arrays and `gl_Fog` are filled, both halves of cleared buffers are cleared each frame (as Iris does), passes without `DRAWBUFFERS` write buffer 0, and many failure paths now clean up instead of leaking or leaving the chain half-built.

## Install
1. Fabric Loader 0.19.5 or later for **Minecraft 26.3**.
2. In `mods/`:
   - [Sodium](https://modrinth.com/mod/sodium) 0.9.2;
   - [Metallum](https://modrinth.com/mod/metallum-mc) **0.0.25** (exactly that version);
   - `luxium-0.2.0-alpha.1.jar` (below). Fabric API isn't needed. [Mod Menu](https://modrinth.com/mod/modmenu) is optional.
3. Shader pack zips in `shaderpacks/`. Complementary Unbound r5.10 is the best-tested pack.
4. Start the game, then pick a pack in Video Settings → Shader Packs. Don't install Iris or OptiFine alongside.

Upgrading from 0.1.0: replace the jar. `config/luxium.properties` and your pack settings files carry over.

## Known issues
- Settings screens: no keyboard navigation between settings yet, and no import/export.
- Not supported yet: chunk fade-in, Distant Horizons, compute-based pack features.
- Complementary's Very High and higher profiles turn on Colored Lighting, which the pack doesn't support on Macs (Iris shows the same error). Use High, or turn Advanced Color Tracing off.
- The first load of each world after an install or update takes a few extra seconds while macOS compiles shaders.

Report bugs at [Issues](https://github.com/hecbatista/luxium/issues), with `logs/latest.log`, the pack and its settings, a screenshot or recording, and your Mac model and macOS version.

**SHA-256** `luxium-0.2.0-alpha.1.jar`: `58efa1190a5187b4cf2ae30a9b282c97925ea010c6ecada87e401794bf4b2235`

License: LGPL-3.0-only.
