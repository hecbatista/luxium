# Testing many shader packs

Complementary Unbound stays the main pack, in `run/shaderpacks/`. Other packs live one per folder
in `run/testpacks/<slug>/`, so they never change which pack a normal run or test loads. Each folder
is what the game tests' `LUXIUM_TEST_PACKS` switch takes. Results per pack go in the README's
compatibility table and `docs/release/compatibility.md`.

## 1. Get a pack
```bash
scripts/fetch-testpacks.sh bsl-shaders     # one pack (slug = first column of testpacks.tsv)
scripts/fetch-testpacks.sh                 # all of them
```
Downloads the packs pinned in `testpacks.tsv` (Modrinth version id + SHA-512) into
`run/testpacks/<slug>/`. `run/` is gitignored; never commit the packs, since most of their
licenses forbid it. To add or update a pack, put the version id and hash from
`https://api.modrinth.com/v2/project/<slug>/version` in `testpacks.tsv`.

## 2. Scoreboard without the game
```bash
./gradlew packCorpus                                                # every pack
LUXIUM_TEST_PACKS=run/testpacks/bsl-shaders ./gradlew packCorpus    # one pack
```
Writes `build/corpus/scoreboard.md`, with one row per pack: preprocess, translate, glslang,
spirv-cross, `xcrun metal`, whether the game's entry point (world0/gbuffers_terrain) exists, the
world0 buffer plan, and programs we don't translate (.csh/.gsh). `build/corpus/<pack>.txt` has
every program's result. Plain `./gradlew test` skips it.

## 3. In the game, then under Iris
```bash
scripts/gametest.sh pack-bsl LUXIUM_TOUR=1 "LUXIUM_TOUR_ONLY=build zoo,zoo,beach" LUXIUM_TEST_PACKS=$PWD/run/testpacks/bsl-shaders
../iris-compare/run.sh pack-bsl "LUXIUM_TOUR_ONLY=build zoo,zoo,beach" LUXIUM_TEST_PACKS=$PWD/run/testpacks/bsl-shaders
java -Djava.awt.headless=true scripts/ContactSheet.java compare build/compare/bsl \
  luxium=build/gametest/pack-bsl/shots iris=../iris-compare/build/shots/pack-bsl
```
`LUXIUM_TEST_PACKS` must be an absolute path (the game runs in `build/run/clientGameTest/`).
`scripts/screenshot-packs.sh [slug ...]` runs the first line for several packs in a row and writes
`build/gametest/packs-summary.md`. A pack that can't be used logs
`Shader pack <name> can't be used: <why>` and the game renders vanilla; avoid the regression
scene for those, since vanilla's rain draw trips validation.

## Picking a pack by hand
In `runClient`, the pack is picked by `config/luxium.properties` (`shaderPack=<file or folder name>`);
in game tests, by `LUXIUM_TEST_CONFIG="shaderPack=<name>"`.
