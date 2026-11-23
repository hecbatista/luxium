#!/bin/bash
# Runs a client game test quietly and prints a short summary instead of the full log.
#
#   mod/scripts/gametest.sh <label> [ENV=value ...]
#
# Examples (from anywhere):
#   mod/scripts/gametest.sh baseline
#   mod/scripts/gametest.sh zoo LUXIUM_TOUR=1 LUXIUM_TOUR_ONLY="build zoo,zoo"
#   mod/scripts/gametest.sh zoo-vanilla LUXIUM_TOUR=1 LUXIUM_TOUR_ONLY="build zoo,zoo" LUXIUM_TEST_NO_PACK=1
#
# Always runs with Metal API + GPU validation. Keeps what the run produced (the game folder is
# wiped by the next run) under mod/build/gametest/<label>/:
#   run.log           the full log (grep it; don't read it whole)
#   shots/            top-level screenshots (frame clips stay in the game folder)
#   flicker.txt       FlickerCheck output for every frames-* clip, with heat maps in shots/
#   sheets/           3x3 contact sheets of shots/ (look at these first)
# Compare runs with: java mod/scripts/ContactSheet.java compare <out> luxium=<dir> vanilla=<dir> [iris=<dir>]
# Run it from a worktree's copy to test that worktree. Exit code: 0 ok, 1 build/test failed,
# 2 validation messages or exceptions found.
set -uo pipefail
label="${1:?usage: gametest.sh <label> [ENV=value ...]}"; shift
mod="$(cd "$(dirname "$0")/.." && pwd)"
out="$mod/build/gametest/$label"
rm -rf "$out"; mkdir -p "$out/shots"
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
t0=$(date +%s)
(cd "$mod" && env "$@" MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1 ./gradlew runClientGameTest > "$out/run.log" 2>&1)
status=$?
secs=$(( $(date +%s) - t0 ))
shots="$mod/build/run/clientGameTest/screenshots"
cp "$shots"/*.png "$out/shots/" 2>/dev/null
log="$out/run.log"

echo "== $label: gradle exit $status, ${secs}s, $(ls "$out/shots" | wc -l | tr -d ' ') shots"
enabled=$(grep -c "Validation Enabled" "$log")
mtl=$(grep -c "failed assertion\|^-\[MTL" "$log")
echo "validation: $enabled/2 enabled, $mtl messages"
grep -m3 -A2 "failed assertion" "$log" | cut -c1-200
exc=$(grep -E "Exception|Error:|Caused by" "$log" \
  | grep -vE "ClassNotFoundException|Realms|SignedJWT|Anisotropic|Block-attached|sessionserver|429" \
  | sed -E 's/^\[[0-9:]+\] //' | cut -c1-180 | sort | uniq -c | sort -rn | head -8)
[ -n "$exc" ] && { echo "exceptions:"; echo "$exc"; }
grep -q "Description:" "$log" && { echo "CRASH:"; grep -m1 -A3 "Description:" "$log"; }
stops=$(grep -o "\[Luxium tour\] .*" "$log" | sed 's/\[Luxium tour\] //' | grep -E "stop done|failed|not found" | sed -E 's/== stop done: //')
[ -n "$stops" ] && { echo "tour:"; echo "$stops" | tr '\n' ';' | sed 's/;/; /g'; echo; }
warn=$(grep -E "WARN\] \(metalshaders\)|ERROR\] \(metalshaders\)" "$log" | sed -E 's/^\[[0-9:]+\] //' | cut -c1-160 | sort | uniq -c | sort -rn | head -6)
[ -n "$warn" ] && { echo "luxium warnings:"; echo "$warn"; }

clips=$(find "$shots" -maxdepth 1 -type d -name 'frames-*' 2>/dev/null | sort)
if [ -n "$clips" ]; then
  "$JAVA_HOME/bin/java" "$mod/scripts/FlickerCheck.java" $clips > "$out/flicker.txt" 2>&1
  cp "$shots"/frames-*-flicker.png "$out/shots/" 2>/dev/null
  echo "flicker (worst frame per clip):"
  sed -E 's/^(.*\/)?(frames-[^:]*):.*worst ([0-9.]+%).*-> (.*)$/  \2 \3 \4/' "$out/flicker.txt" | grep -E "^  frames" | tr '\n' ';' | sed 's/;  /; /g'; echo
fi
"$JAVA_HOME/bin/java" -Djava.awt.headless=true "$mod/scripts/ContactSheet.java" grid "$out/sheets" "$out/shots" > /dev/null 2>&1 \
  && echo "sheets: $out/sheets ($(ls "$out/sheets" | wc -l | tr -d ' '))"

[ $status -ne 0 ] && exit 1
[ "$mtl" -gt 0 ] || grep -q "Description:" "$log" && exit 2
exit 0
