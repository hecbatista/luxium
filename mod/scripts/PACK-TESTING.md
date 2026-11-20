# Testing many shader packs

Complementary Unbound stays the main pack, in `run/shaderpacks/`. The other popular packs live
in `run/testpacks/`, so they never change which pack a normal run or test loads.

## 1. Get the packs
```bash
scripts/fetch-testpacks.sh
```
Downloads the packs pinned in `testpacks.tsv` (Modrinth version id + SHA-512) into
`run/testpacks/` (about 19 MB). `run/` is gitignored; never commit the packs, since most of their
licenses forbid it. To add a pack, add a line to `testpacks.tsv` with the version id and hash
from `https://api.modrinth.com/v2/project/<slug>/version`.

## 2. Scoreboard without the game
```bash
./gradlew packCorpus                    # every pack, about a minute
LUXIUM_PACK=bsl ./gradlew packCorpus    # one pack
```
Writes `build/corpus/scoreboard.md`, with one row per pack: preprocess, translate, glslang,
spirv-cross, `xcrun metal`, whether the game's entry point (world0/gbuffers_terrain) exists, the
world0 buffer plan, and programs we don't translate (.csh/.gsh). `build/corpus/<pack>.txt` has
every program's result. Plain `./gradlew test` skips it.

## 3. In the game
```bash
scripts/screenshot-packs.sh             # every pack, about 30 s each
scripts/screenshot-packs.sh bsl photon  # some packs
```
Runs `ScreenshotTest` once per pack with `LUXIUM_PACK` set and Metal API + GPU validation on.
Each pack's shots and logs go to `build/pack-screenshots/<pack>/`, and the results table to
`build/pack-screenshots/summary.md`. The script waits while any other game is running, so it
doesn't share the GPU with another session's test.

## Picking a pack by hand
`LUXIUM_PACK=<name>` (a unique part of the file name, any case) picks the pack in `runClient`
too, but there it only sees `run/shaderpacks/`. The game test also copies in `run/testpacks/`. A name that matches nothing, or matches several packs, is an error in the log, not a silent
fallback. Without it, the first pack by name loads, as before.
