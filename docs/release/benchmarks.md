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
- No per-pass GPU timing yet: the game's API doesn't expose it (7f).
