# leadlight-compare

Runs Luxium's `PlaytestTour`, the shared file in `mod/src/gametest`, under **LeadLight** instead of Luxium. LeadLight is [ohjey/leadlight](https://github.com/ohjey/leadlight), a Vitrail fork on Metallum's Metal backend. The project is for benchmarks and side-by-side shots; see `docs/release/leadlight-comparison.md`.

`libs/` is git-ignored. Download these two jars into it:

| File | From | Check |
|---|---|---|
| `leadlight-0.2.0+mc26.3.jar` (1,921,662 bytes) | `gh release download v0.2.0 -R ohjey/leadlight -p 'leadlight-0.2.0+mc26.3.jar'` | SHA-256 `9b91bd9e5fb31e2a4c37a307e02d1b1530e77a52ff71540d7b45e3b5925c5c9a` (GitHub's asset digest) |
| `metallum-0.0.25.jar` (1,341,858 bytes) | Modrinth `metallum-mc` 0.0.25, `https://cdn.modrinth.com/data/w79ASAJD/versions/mBC9xcTy/metallum-0.0.25.jar` | SHA-512 from Modrinth's API, `5bc7fda9…1c7b66` |

Run:

```bash
leadlight-compare/run.sh rd12-LL1 LUXIUM_BENCH=1 LUXIUM_TOUR_ONLY=benchmark LUXIUM_BENCH_RD=12
```

Validation is off unless you pass `MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1`. `LeadlightSetup` writes `leadlight/pack.txt` naming the first pack in `mod/run/shaderpacks/`, with no settings file (so the pack's defaults), and reloads LeadLight before the tour starts.
