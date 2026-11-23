#!/bin/bash
# Refreshes the code graph that agents query with `graphify query "<question>" --budget 1500`.
# AST only: free, no model tokens, ~15 s. Run it after code changes (the Minecraft sources never
# change, so only mod/src is re-extracted, then both graphs are merged into graphify-out/graph.json).
#   mod/scripts/graph-update.sh            refresh from the main checkout's mod/src
#   mod/scripts/graph-update.sh --full     also rebuild the Minecraft-sources graph (~4 min)
set -euo pipefail
cd "$(dirname "$0")/../.."
mc=references/minecraft-26.3-sources
if [ "${1:-}" = "--full" ] || [ ! -f "$mc/graphify-out/graph.json" ]; then
  graphify update "$mc" 2>&1 | grep -E "Rebuilt|error" || true
fi
graphify update mod/src 2>&1 | grep -E "Rebuilt|error" || true
graphify merge-graphs mod/src/graphify-out/graph.json "$mc/graphify-out/graph.json" --out graphify-out/graph.json 2>&1 | grep -E "Merged|error"
