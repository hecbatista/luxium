#!/usr/bin/env bash
# Downloads the pinned test packs listed in testpacks.tsv into mod/run/testpacks/ and checks each
# file's SHA-512. Files that are already there with the right hash are skipped.
#
#   mod/scripts/fetch-testpacks.sh
#
# Source: Modrinth's public API (GET /v2/version/{id}), which gives each file's URL and hashes.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
dest="$here/../run/testpacks"
mkdir -p "$dest"
ua="luxium-testpacks"

grep -v '^#' "$here/testpacks.tsv" | while IFS=$'\t' read -r slug id version file sha; do
    [ -z "$slug" ] && continue
    out="$dest/$file"
    if [ -f "$out" ] && [ "$(shasum -a 512 "$out" | cut -d' ' -f1)" = "$sha" ]; then
        echo "ok        $slug $version"
        continue
    fi
    url="$(curl -fsS -A "$ua" "https://api.modrinth.com/v2/version/$id" \
        | python3 -I -c 'import json, sys
v = json.load(sys.stdin)
print(next(f["url"] for f in v["files"] if f["hashes"]["sha512"] == sys.argv[1]))' "$sha")"
    tmp="$out.part"
    curl -fsSL -A "$ua" -o "$tmp" "$url"
    got="$(shasum -a 512 "$tmp" | cut -d' ' -f1)"
    if [ "$got" != "$sha" ]; then
        rm -f "$tmp"
        echo "HASH MISMATCH for $slug $version ($file)" >&2
        exit 1
    fi
    mv "$tmp" "$out"
    echo "fetched   $slug $version"
done
