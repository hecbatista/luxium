#!/usr/bin/env bash
# Downloads pinned test packs listed in testpacks.tsv, each into its own folder
# mod/run/testpacks/<slug>/, and checks each file's SHA-512. Files that are already there with the
# right hash are skipped. A pack's folder is what LUXIUM_TEST_PACKS takes, so a game test or the
# corpus scoreboard can run on that one pack.
#
#   mod/scripts/fetch-testpacks.sh                  # every pack in testpacks.tsv
#   mod/scripts/fetch-testpacks.sh bsl-shaders      # only these slugs (the first column)
#
# Source: Modrinth's public API (GET /v2/version/{id}), which gives each file's URL and hashes.
# run/ is gitignored. Never commit the packs: most of their licenses forbid redistribution.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$here/../run/testpacks"
ua="luxium-testpacks"
wanted=" $* "

found=0
while IFS=$'\t' read -r slug id version file sha; do
    [ -z "$slug" ] && continue
    if [ $# -gt 0 ] && [[ "$wanted" != *" $slug "* ]]; then
        continue
    fi
    found=$((found + 1))
    dest="$root/$slug"
    mkdir -p "$dest"
    out="$dest/$file"
    if [ -f "$out" ] && [ "$(shasum -a 512 "$out" | cut -d' ' -f1)" = "$sha" ]; then
        echo "ok        $slug $version  ($dest)"
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
    # Only this pack in its folder: an older version left there would be tested too.
    find "$dest" -maxdepth 1 -type f ! -name "$(basename "$tmp")" -delete
    mv "$tmp" "$out"
    echo "fetched   $slug $version  ($dest)"
done < <(grep -v '^#' "$here/testpacks.tsv")

if [ $# -gt 0 ] && [ "$found" -ne $# ]; then
    echo "Some slugs aren't in testpacks.tsv: $*" >&2
    exit 1
fi
