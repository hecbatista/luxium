# Benchmarks: Luxium vs Iris

## Method (2026-11-23)
- **Machine:** MacBook Pro, Apple M1 Max, macOS 27.2.
- **Pack:** Complementary Unbound r5.10, default settings.
- **Luxium:** 0.1.0-alpha.1 on Metallum (local build 467d312; shots match Modrinth's 0.0.25) and Sodium 0.9.2. Metal validation **off**.
- **Iris:** 1.11.7 for 26.3 on the game's OpenGL backend (`iris-compare/`) with Sodium.
- **Same test code for both:** `PlaytestTour`'s `benchmark` stop (`LUXIUM_BENCH=1`, `LUXIUM_TOUR_ONLY=benchmark`).
  - Fixed seed (20261121), noon, clear weather, spectator camera.
  - Vsync off, no frame limit, no AFK limiter, window 1708×960 (framebuffer).
  - Render distance from `LUXIUM_BENCH_RD`, set before the world is created (the test framework's default is 5).
  - With any other distance, `waitForChunksRender` never completes (it timed out even right after world creation), so benchmark mode waits a fixed 20 s per scene instead.
- **Frame times:** a HUD element that draws nothing timestamps every rendered frame (Fabric API `HudElementRegistry`, no Luxium code), so intervals include presenting and pacing.
  - Per scene: 3 s warm-up, then 10 s measured.
  - Reported: average fps, median frame time, 1% low (the 99th-percentile frame time as fps), worst frame.
- **Scenes:**
  - *beach*: on the seed's beach facing the sea, holding still (water, reflections, clouds, sky);
  - *overlook*: 30 blocks above spawn looking down over terrain;
  - *spin*: the same spot turning 360° over the 10 s.
- **Order:** ABBA (Luxium, Iris, Iris, Luxium), each run alone on the machine. Commands are in this file's history; logs in `mod/build/bench/` and `iris-compare/build/logs/`.

## Results

### Render distance 5 (test default)
Average fps / 1% low fps; each cell is run 1 and run 2.

| Scene | Luxium | Iris | Luxium ÷ Iris (avg / 1% low) |
|---|---|---|---|
| beach | 136.7 / 113.9 · 138.0 / 112.6 | 96.1 / 67.3 · 95.9 / 68.2 | 1.43× / 1.67× |
| overlook | 163.4 / 134.5 · 164.3 / 134.7 | 111.8 / 80.7 · 110.3 / 71.5 | 1.47× / 1.77× |
| spin | 161.8 / 124.5 · 162.7 / 122.9 | 108.7 / 78.8 · 109.9 / 72.1 | 1.48× / 1.64× |

Median frame times: Luxium 6.0–7.3 ms, Iris 8.6–10.2 ms. Worst frames: Luxium 9.6–17.8 ms, Iris 14.7–19.9 ms.

### Render distance 12 (`LUXIUM_BENCH_RD=12`)

| Scene | Luxium | Iris | Luxium ÷ Iris (avg / 1% low) |
|---|---|---|---|
| beach | 89.7 / 75.5 · 89.8 / 76.6 | 83.5 / 67.8 · 78.8 / 65.5 | 1.11× / 1.14× |
| overlook | 95.4 / 82.0 · 95.9 / 82.3 | 88.9 / 72.8 · 87.6 / 67.1 | 1.08× / 1.17× |
| spin | 94.6 / 74.3 · 95.5 / 74.2 | 85.2 / 64.0 · 86.5 / 66.2 | 1.11× / 1.14× |

- Worst frames: Luxium 13.4–19.5 ms; Iris 15.6–85.2 ms (one 85 ms spike while spinning).
- **Scaling:** going from 5 to 12 chunks, Luxium loses about 35% of its frame rate (beach 137 → 90 fps) and Iris about 20% (96 → 81). Luxium's per-chunk cost is the next performance target: terrain draws or the shadow pass, which re-draws the light's render list (7c). Measure before changing anything.

## Caveats
- One machine. Results on base M-series chips and at full Retina resolution may differ.
- The two don't do exactly the same work:
  - Luxium keeps colortex1 and colortex7 at full size, where Iris (claiming `BLOCK_EMISSION_ATTRIBUTE`) shrinks them to `REFLECTION_RES`. That's more work for Luxium.
  - Luxium doesn't do chunk fade-in, which costs next to nothing.
  - Otherwise the pictures match (step 7 comparisons).
- Iris runs on OpenGL, which macOS implements on top of Metal; Luxium talks to Metal directly through Metallum.
- No per-pass GPU timing through the game's API (7f). Metal System Trace gives it; see below.

# Luxium vs LeadLight 0.2.0 (2026-11-23)

## Method
- **Benchmark:** the same `benchmark` stop and machine as above, with Complementary Unbound r5.10 at defaults.
  - **Both mods on Modrinth's Metallum 0.0.25** (`leadlight-compare/libs/metallum-0.0.25.jar`, SHA-512 checked).
  - LeadLight 0.2.0 `+mc26.3` from its GitHub release, run by `leadlight-compare/run.sh`.
  - Luxium on main (6ec6e8f), run by `mod/scripts/bench.sh`.
  - ABBA order. Other sessions were paused by a lock file (`mod/build/bench/benchmark-running`), and no run was overlapped by another game. The "another game is running" warnings in the logs come from a too-broad `pgrep` matching other sessions' wait loops.
- **Profile:** a 5 s Metal System Trace (`xcrun xctrace record --template 'Metal System Trace' --attach <pid>`) of the overlook scene at render distance 5, with `LUXIUM_GPU_LABELS=1` (passes `--renderDebugLabels`, so Metallum labels its encoders).
  - GPU time per pass comes from joining `metal-gpu-intervals` (GPU durations) with `metal-application-intervals` (encoder → pass label) on `encoder-id`. The scripts are in this session's scratchpad: `gpu_sum.py`, `exact.py`.

## Results
Average fps; each cell is run 1 · run 2. The machine got slower over each group of four runs (heat or background load), so the spread is wide.

| Render distance | Scene | Luxium | LeadLight |
|---|---|---|---|
| 5 | beach | 125 · 92 | 153 · 114 |
| 5 | overlook | 152 · 114 | 186 · 135 |
| 5 | spin | 149 · 118 | 186 · 123 |
| 12 | beach | 78 · 76 | 60* · 114 |
| 12 | overlook | 87 · 84 | 62* · 118 |
| 12 | spin | 87 · 86 | 61* · 124 |

\*A median frame of ~10 ms with 1% lows of 24 fps: frame-pacing trouble in that run, not GPU load.

**LeadLight is faster:** about 20–25% at render distance 5 and up to 40% at 12. Luxium's frame times are steadier at 12.

## GPU time per pass (overlook, render distance 5, ms per frame)

| Pass | Luxium | LeadLight | Luxium − LeadLight |
|---|---|---|---|
| Shadow map (Luxium: + its shadow depth copy) | 1.00 + 0.10 | 0.40 | **+0.70** |
| Terrain, opaque + translucent | 0.89 + 0.40 | 1.19 | +0.10 |
| composite1 | 0.91 | 0.86 | +0.05 |
| deferred1 | 0.69 | 0.67 | +0.02 |
| composite6 / 7 / 5 | 0.32 / 0.16 / 0.11 | 0.28 / 0.17 / 0.11 | +0.04 |
| **composite4** (the pack scales it to 0.4) | 0.24 | 0.07 | **+0.17** |
| composite | 0.11 | 0.05 | +0.06 |
| sky | 0.17 | 0.16 | +0.01 |
| Luxium-only passes: merge hand depth, depth to OpenGL convention, compose translucent catch, seed colortex0 | 0.17 | — | **+0.17** |
| **All GPU work** | **5.80** | **4.66** | **+1.14** |

Frame time was 7.0 ms against 6.0 ms, so the extra GPU work explains the whole gap.

- **Shadow pass:** Luxium does 2.8× LeadLight's vertex work there (0.48 against 0.17 ms), so it draws about 2.8× the geometry. That's the 7c light box, with face culling off, against LeadLight's camera frustum swept along the light, cut at the shadow distance. The gap should grow with render distance.
- **Reflection buffers:** `colortex1` and `colortex7` at full size cost little here. deferred1 and composite1 are within 5% of LeadLight's.
- **composite4:** Luxium does pass `scale 0.4` to the translator (`PackChain`), yet costs 3.4× more. Check how the scaled pass covers its target (whole-target load/store, viewport).

## After the tight shadow frustum (branch `fix-shadow-cull`, 2026-11-23)
`render/ShadowFrustum` is Iris's advanced shadow frustum: the camera frustum swept toward the light, and also inside the shadow map's box. It's chosen as Iris chooses it (`ShadowCulling.configure`). `LUXIUM_SHADOW_CULL=box` restores the old box for A/B runs in the same build.

- **Correctness:** game test under validation, 0 messages; every shot matches the box build, including shot 9 (a tower behind the camera casting into view); flicker unchanged; 112 unit tests pass; 90/90 on the scoreboard.
- **Light's terrain walk:** 4–6 regions where the box took 8–10.

Benchmark in the order T B LL LL B T (T = tight, B = box, same worktree build), each render distance alone. Average fps, beach / overlook / spin, mean of the two runs:

| Render distance | Luxium, tight | Luxium, box | LeadLight 0.2.0 |
|---|---|---|---|
| 5 | 145 / 173 / 166 | 132 / 162 / 157 | 152 / 186 / 186 |
| 12 | 107 / 114 / 114 | 83 / 91 / 91 | 113 / 116 / 122 (run 2 only*) |

- The tight frustum is about +7% at render distance 5 and +25–30% at 12, against the box in the same build.
- Against LeadLight: about 5–10% behind at render distance 5, and 1–6% behind at 12 (it was up to 40% behind).
- \*LeadLight's first render-distance-12 run fell to about 39 fps in every scene (worst frame about 54 ms), as in the earlier benchmark (60 fps with 1% lows of 24). That's twice out of two first runs at that distance. Luxium had no such run.
- The runner's "CONTAMINATED" marks on the Luxium runs are false: its check counted any game started from `.claude/worktrees`, and this Luxium build runs from one.
- Next GPU targets from the profile: composite4 (+0.17 ms) and Luxium's helper passes (+0.17 ms per frame).

## Mip chains and the scaled-pass scissor (commits 91e5b85, c5b5cd4 on `fix-shadow-cull`, 2026-11-23)
- **Helper passes aren't a gap.** LeadLight has its own (depth window, scene seed, feature layer, pending colour clears), about 0.2 ms against Luxium's 0.17.
- **`colortexNMipmapEnabled`** (Complementary: composite4 on colortex0, composite on colortex5) is now honoured.
  - First version: mip levels on the pack's buffers, drawn one render pass per level. Quick A/B: −7% (159 against 170 fps), because of about 22 passes a frame plus Metallum clearing each level.
  - Now: a separate chain texture per buffer, with a blit copy of level 0, then Metal's blit `generateMipmaps` (`render/MetalMips`). A/B: no measurable cost (176.4 against 177.6 fps).
  - Fixes the bloom shimmer at the beach: still clip 0.16% → 0.00%, jump 0.18% → 0.02%.
- **Scaled passes:** the render area is the scaled corner, as Iris's viewport. The full-screen triangle used to cover twice the corner. composite4: 0.22 → 0.05 ms of GPU (LeadLight 0.07).
- **GPU time per frame** (overlook, render distance 5): **4.90 ms**, against 5.80 before these fixes and LeadLight's 4.66.
- **Milestone ABBA (L LL LL L)** was noisy, with load up to 7.6 and one Luxium run at render distance 12 at 52–58 fps in two scenes. Best runs, fps beach / overlook / spin:

  | Render distance | Luxium | LeadLight |
  |---|---|---|
  | 5 | 145 / 176 / 155 | 155 / 188 / 189 |
  | 12 | 110 / 119 / 120 | 108 / 103 / 120 |

  So Luxium is about even at render distance 12 and 6–10% behind at 5. LeadLight again had large hitches: 660 ms and 1013 ms worst frames. Rerun on a quiet machine before quoting these numbers anywhere.
