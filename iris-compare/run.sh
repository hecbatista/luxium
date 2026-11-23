#!/bin/bash
# Runs the PlaytestTour under Iris (OpenGL, no Luxium/Metallum) and copies the screenshots.
# Usage: run.sh <label> [ENV=value...]
#   e.g. run.sh zoo LUXIUM_TOUR=1 LUXIUM_TOUR_ONLY="build zoo,zoo"
# LUXIUM_TOUR=1 is always set. Shots go to build/shots/<label>/, the full log to build/logs/<label>.log.
set -u
cd "$(dirname "$0")"
[ $# -ge 1 ] || { echo "usage: $0 <label> [ENV=value...]"; exit 2; }
label=$1; shift
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export LUXIUM_TOUR=1
for kv in "$@"; do export "$kv"; done
mkdir -p build/logs build/shots
log=build/logs/$label.log
shots=build/shots/$label
./gradlew runClientGameTest > "$log" 2>&1
code=$?
rm -rf "$shots"; mkdir -p "$shots"
src=build/run/clientGameTest/screenshots
[ -d "$src" ] && find "$src" -maxdepth 1 -name '*.png' -exec cp {} "$shots"/ \;
n=$(ls "$shots" | wc -l | tr -d ' ')
echo "exit=$code shots=$n dir=$PWD/$shots log=$PWD/$log"
grep -m1 "Iris pack in use" "$log"
grep -E "Exception|FAILED|Caused by" "$log" | sort | uniq -c | head -5
