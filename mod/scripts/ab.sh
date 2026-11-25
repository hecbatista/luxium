#!/bin/bash
# The quick A/B for a small render change (about 2 minutes, one game launch): PlaytestTour's "ab"
# stop flips a system property that Luxium reads every frame, on and off over several rounds in the
# benchmark's overlook view, and this prints the mean of each variant.
#
#   mod/scripts/ab.sh <label> <property> [value] [ENV=value ...]
#
# Examples:
#   mod/scripts/ab.sh mips luxium.noMipmaps            # mip chains on vs off
#   mod/scripts/ab.sh cull luxium.shadowCull box       # tight shadow frustum vs the old box
#   mod/scripts/ab.sh mips12 luxium.noMipmaps true LUXIUM_BENCH_RD=12
#
# Validation is off (like bench.sh); use gametest.sh for correctness. Run it alone on the machine.
# For milestone numbers (several scenes, render distances, LeadLight) use bench.sh / the ABBA runs
# in docs/release/benchmarks.md. Log: mod/build/bench/ab-<label>.log.
set -uo pipefail
label="${1:?usage: ab.sh <label> <property> [value] [ENV=value ...]}"
property="${2:?usage: ab.sh <label> <property> [value] [ENV=value ...]}"
shift 2
value=true
if [ $# -gt 0 ] && [[ "$1" != *=* ]]; then value="$1"; shift; fi
mod="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$mod/build/bench"
log="$mod/build/bench/ab-$label.log"
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
t0=$(date +%s)
(cd "$mod" && env -u MTL_DEBUG_LAYER -u MTL_SHADER_VALIDATION \
    LUXIUM_TOUR=1 LUXIUM_TOUR_ONLY=ab LUXIUM_AB="$property" LUXIUM_AB_VALUE="$value" "$@" \
    ./gradlew runClientGameTest > "$log" 2>&1)
status=$?
echo "== ab $label ($property: off vs $value): gradle exit $status, $(( $(date +%s) - t0 ))s, log $log"
grep -q "Description:" "$log" && { echo "CRASH:"; grep -m1 -A3 "Description:" "$log"; }
grep -m1 -E "Luxium: shaders off|can't be used" "$log"
grep -o "\[Luxium tour\] bench ab .*" "$log" | sed 's/\[Luxium tour\] bench ab //' | awk '
  { split($1, kv, "="); v = kv[2]; r = $2; sub(":", "", r)
    match($0, /avg [0-9.]+ fps/); avg = substr($0, RSTART + 4, RLENGTH - 8)
    match($0, /p99 [0-9.]+ ms/); p99 = substr($0, RSTART + 4, RLENGTH - 7)
    printf "  %-6s %-4s avg %6.1f fps  p99 %5.2f ms\n", v, r, avg, p99
    sum[v] += avg; n[v]++; psum[v] += p99 }
  END { for (v in sum) printf "mean %-6s avg %6.1f fps  p99 %5.2f ms  (%d rounds)\n", v, sum[v] / n[v], psum[v] / n[v], n[v] }'
exit $status
