#!/bin/bash
# Runs PlaytestTour's benchmark stop under Luxium (docs/release/benchmarks.md) and prints the
# "bench" lines.
#
#   mod/scripts/bench.sh <label> [ENV=value ...]
#
# Examples:
#   mod/scripts/bench.sh rd5-L1
#   mod/scripts/bench.sh rd12-L1 LUXIUM_BENCH_RD=12
#   mod/scripts/bench.sh rd12-L1 LUXIUM_BENCH_RD=12 LUXIUM_METALLUM_JAR=$PWD/leadlight-compare/libs/metallum-0.0.25.jar
#
# Like promo.sh, this runs WITHOUT Metal validation, which slows every frame. Never use it to
# check a change: that is gametest.sh. Run it alone on the machine (no other game running).
# The same stop runs under Iris (iris-compare/run.sh) and LeadLight (leadlight-compare/run.sh)
# with LUXIUM_BENCH=1 LUXIUM_TOUR_ONLY=benchmark.
# Log in mod/build/bench/<label>.log (grep it; don't read it whole).
set -uo pipefail
label="${1:?usage: bench.sh <label> [ENV=value ...]}"; shift
mod="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$mod/build/bench"
log="$mod/build/bench/$label.log"
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
if pgrep -f 'java.*devlaunchinjector' > /dev/null; then
  echo "warning: another game is running; the numbers will be off" >&2
fi
(cd "$mod" && env -u MTL_DEBUG_LAYER -u MTL_SHADER_VALIDATION \
    LUXIUM_TOUR=1 LUXIUM_BENCH=1 LUXIUM_TOUR_ONLY=benchmark "$@" \
    ./gradlew runClientGameTest > "$log" 2>&1)
status=$?
echo "== bench $label: gradle exit $status, log $log"
grep -q "Description:" "$log" && { echo "CRASH:"; grep -m1 -A3 "Description:" "$log"; }
grep -m1 -E "Luxium: shaders off|can't be used" "$log"
grep -o "\[Luxium tour\] bench .*" "$log" | sed 's/\[Luxium tour\] //'
exit $status
