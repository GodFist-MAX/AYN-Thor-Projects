#!/bin/bash
# Renders the puppet on the Mac with Java2D. Usage: preview/preview.sh sheet /tmp/out.png [cellPx]
#                                                   preview/preview.sh strip /tmp/out.png ACTION [secs] [frames]
set -euo pipefail
cd "$(dirname "$0")/.."
J="$HOME/xmb-launcher/tools/jdk/Contents/Home/bin"
OUT=build/preview
rm -rf "$OUT" && mkdir -p "$OUT"
SRC=app/java/dev/grokbuild/coronamon
"$J/javac" -encoding UTF-8 -nowarn -d "$OUT" \
  "$SRC/Gfx.java" "$SRC/Pose.java" "$SRC/Puppet.java" $(ls "$SRC"/Actor.java "$SRC"/Moves.java "$SRC"/Room.java 2>/dev/null) preview/*.java
"$J/java" -Djava.awt.headless=true -cp "$OUT" dev.grokbuild.coronamon.Preview "$@"
