#!/bin/bash
# Writes the IDs of movies downloaded in Google TV on the Thor to /sdcard/CrossBar/googletv.json.
# Android keeps Google TV's folder private from other apps, but adb can list it. Only movie IDs are
# written; the per-account folder names (derived from the Google account) are never printed or stored.
set -euo pipefail
export ANDROID_SERIAL=${ANDROID_SERIAL:-62dff1b0}
A=~/platform-tools/adb
FILES=/sdcard/Android/data/com.google.android.videos/files

LIST=$($A shell "for d in $FILES/Movies/*/ $FILES/Download/*/; do [ -d \"\$d\" ] && ls \"\$d\"; done; true" 2>/dev/null | tr -d '\r' || true)
# Keep suffixes such as ".P": they are part of the id Google Play knows the movie by.
IDS=$(printf '%s\n' "$LIST" | { grep -oE '^yt:movie:[A-Za-z0-9_-]{6,}(\.[A-Za-z0-9]{1,4})?$' || true; } | sed 's/^yt:movie://' | sort -u)
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT
printf '%s\n' "$IDS" | python3 -c 'import json, sys; print(json.dumps({"movies": sys.stdin.read().split()}))' > "$TMP"
$A shell mkdir -p /sdcard/CrossBar
$A push "$TMP" /sdcard/CrossBar/googletv.json >/dev/null 2>&1 || { echo "Could not copy googletv.json to the Thor" >&2; exit 1; }
echo "Synced $(printf '%s\n' "$IDS" | grep -c . || true) Google TV download(s) to /sdcard/CrossBar/googletv.json"
