#!/usr/bin/env bash
# Runs the client game test (ScreenshotTest) once per pack, with Metal API and GPU validation on,
# and keeps each pack's screenshots and log, because the test's game folder is cleared every run.
#
#   mod/scripts/screenshot-packs.sh              # every pack in run/shaderpacks/ and run/testpacks/
#   mod/scripts/screenshot-packs.sh bsl photon   # only these (any unique part of the name)
#
# Results: build/pack-screenshots/<pack>/ (the .png files, game.log, gradle.log), plus
# build/pack-screenshots/summary.md. One run takes about 30 s.
set -uo pipefail
mod="$(cd "$(dirname "$0")/.." && pwd)"
cd "$mod"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25)}"
out="$mod/build/pack-screenshots"
mkdir -p "$out"

if [ $# -gt 0 ]; then
    packs=("$@")
else
    packs=()
    for f in run/shaderpacks/*.zip run/testpacks/*.zip; do
        [ -e "$f" ] && packs+=("$(basename "$f" .zip)")
    done
fi

summary="$out/summary.md"
{
    echo "# In-game screenshots per pack"
    echo
    echo "| Pack | Result | Loaded | Validation messages | Luxium/Metallum errors (first) | Shots |"
    echo "|---|---|---|---|---|---|"
} > "$summary"

for pack in "${packs[@]}"; do
    # Never run next to another game (another checkout's runClient or game test): they'd share
    # the GPU, which skews frame timing and readback measurements in both.
    while pgrep -f 'java.*devlaunchinjector' > /dev/null; do
        echo "    another game is running; waiting..."
        sleep 15
    done
    dir="$out/$pack"
    rm -rf "$dir"
    mkdir -p "$dir"
    echo "=== $pack"
    MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1 LUXIUM_PACK="$pack" \
        ./gradlew runClientGameTest > "$dir/gradle.log" 2>&1
    status=$?
    cp build/run/clientGameTest/screenshots/*.png "$dir/" 2>/dev/null
    cp build/run/clientGameTest/logs/latest.log "$dir/game.log" 2>/dev/null

    result=$([ $status -eq 0 ] && echo pass || echo "FAIL ($status)")
    loaded=$(grep -m1 -o 'Loading pack .*' "$dir/game.log" 2>/dev/null | sed 's/Loading pack //')
    validation=$(grep -c -iE 'validation.*(error|failed)|-\[MTL.*assert|failed assertion' "$dir/gradle.log")
    # Only Luxium's and Metallum's errors: the rest is offline-player network noise (Realms etc.).
    ours='/ERROR\] \((metalshaders|metallum)\)'
    errors=$(grep -cE "$ours" "$dir/game.log" 2>/dev/null || true)
    first=$(grep -m1 -E "$ours" "$dir/game.log" 2>/dev/null | sed -E 's/^.*\((metalshaders|metallum)\) //' | cut -c1-120 | tr '|' '/')
    [ -n "$first" ] && errors="$errors: $first"
    shots=$(ls "$dir"/*.png 2>/dev/null | wc -l | tr -d ' ')
    if ! grep -q 'Metal API Validation Enabled' "$dir/gradle.log"; then
        validation="validation was off"
    fi
    echo "| $pack | $result | ${loaded:-?} | $validation | $errors | $shots |" >> "$summary"
    echo "    $result, loaded ${loaded:-?}, validation messages: $validation, log errors: $errors, shots: $shots"
done

echo
cat "$summary"
