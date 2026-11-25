#!/usr/bin/env bash
# Runs the game test once per test pack, each pack alone (LUXIUM_TEST_PACKS=run/testpacks/<slug>),
# through scripts/gametest.sh, so validation is always on and every run keeps its shots, sheets and
# log under build/gametest/pack-<slug>/.
#
#   mod/scripts/screenshot-packs.sh bsl-shaders photon-shader   # these slugs (folders in run/testpacks/)
#   mod/scripts/screenshot-packs.sh                             # every fetched pack
#
# The default stops are "build zoo,zoo,beach"; set TOUR_STOPS to change them. The regression scene
# is avoided on purpose: a pack that falls back renders vanilla, and vanilla's rain draw trips
# validation. Waits while any other game runs, so it never shares the GPU with another test.
# Results table: build/gametest/packs-summary.md.
set -uo pipefail
mod="$(cd "$(dirname "$0")/.." && pwd)"
stops="${TOUR_STOPS:-build zoo,zoo,beach}"
if [ $# -gt 0 ]; then
    slugs=("$@")
else
    slugs=()
    for d in "$mod"/run/testpacks/*/; do [ -d "$d" ] && slugs+=("$(basename "$d")"); done
fi
summary="$mod/build/gametest/packs-summary.md"
mkdir -p "$(dirname "$summary")"
{
    echo "| Pack | Exit | Validation messages | Pack result |"
    echo "|---|---|---|---|"
} > "$summary"

for slug in "${slugs[@]}"; do
    dir="$mod/run/testpacks/$slug"
    if [ ! -d "$dir" ]; then echo "no $dir (run scripts/fetch-testpacks.sh $slug)" >&2; continue; fi
    while pgrep -f 'java.*devlaunchinjector' > /dev/null; do
        echo "    another game is running; waiting..."
        sleep 15
    done
    echo "=== $slug"
    "$mod/scripts/gametest.sh" "pack-$slug" LUXIUM_TOUR=1 "LUXIUM_TOUR_ONLY=$stops" "LUXIUM_TEST_PACKS=$dir"
    status=$?
    log="$mod/build/gametest/pack-$slug/run.log"
    mtl=$(grep -c "failed assertion\|^-\[MTL" "$log" 2>/dev/null)
    result=$(grep -m1 -o "Shader pack .* can't be used: [^.]*" "$log" 2>/dev/null | tr '|' '/')
    [ -z "$result" ] && result="loaded"
    echo "| $slug | $status | $mtl | $result |" >> "$summary"
done
echo; cat "$summary"
