#!/bin/bash
# Takes the promo screenshots (PlaytestTour's "promo" stop) for the README and Modrinth.
#
#   mod/scripts/promo.sh <label> [ENV=value ...]
#
# Examples:
#   mod/scripts/promo.sh promo
#   mod/scripts/promo.sh sunset LUXIUM_PROMO_SCENES=beach-sunset,savanna-sunset
#
# Unlike gametest.sh, this runs WITHOUT Metal API and GPU validation: the shots are for
# promotion, not for checking a change, and validation slows every frame (the promo stop took
# about 20 minutes with it). Check changes with gametest.sh, never with this.
# Output in mod/build/promo/<label>/: run.log (grep it; don't read it whole), shots/, sheets/.
# For Iris shots of the same scenes, pass the same variables to iris-compare/run.sh.
set -uo pipefail
label="${1:?usage: promo.sh <label> [ENV=value ...]}"; shift
mod="$(cd "$(dirname "$0")/.." && pwd)"
out="$mod/build/promo/$label"
rm -rf "$out"; mkdir -p "$out/shots"
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
t0=$(date +%s)
# Defaults first, so the caller's ENV=value arguments win. -u drops validation even if the
# calling shell has it set.
(cd "$mod" && env -u MTL_DEBUG_LAYER -u MTL_SHADER_VALIDATION \
    LUXIUM_TOUR=1 LUXIUM_PROMO=1 LUXIUM_TOUR_ONLY=promo LUXIUM_BENCH_RD=12 "$@" \
    ./gradlew runClientGameTest > "$out/run.log" 2>&1)
status=$?
secs=$(( $(date +%s) - t0 ))
cp "$mod/build/run/clientGameTest/screenshots"/*promo-*.png "$out/shots/" 2>/dev/null
log="$out/run.log"

echo "== promo $label: gradle exit $status, ${secs}s, $(ls "$out/shots" | wc -l | tr -d ' ') shots (validation off)"
grep -q "Description:" "$log" && { echo "CRASH:"; grep -m1 -A3 "Description:" "$log"; }
grep -o "\[Luxium tour\] .*" "$log" | sed 's/\[Luxium tour\] //' | grep -E "stop done|failed|not found|skipped"
grep -m1 -E "Luxium: shaders off|can't be used" "$log"
"$JAVA_HOME/bin/java" -Xmx2g -Djava.awt.headless=true "$mod/scripts/ContactSheet.java" grid "$out/sheets" "$out/shots" > /dev/null 2>&1 \
  && echo "sheets: $out/sheets ($(ls "$out/sheets" | wc -l | tr -d ' '))"
[ $status -ne 0 ] && exit 1
grep -q "Description:" "$log" && exit 2
exit 0
