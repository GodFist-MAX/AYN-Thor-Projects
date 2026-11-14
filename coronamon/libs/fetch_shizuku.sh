#!/bin/bash
# Downloads the Shizuku API 13.1.5 libraries (Apache-2.0, github.com/RikkaApps/Shizuku-API) from
# Maven Central and extracts the jars build.sh compiles against into libs/shizuku/.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p shizuku
V=13.1.5
for entry in \
  api:1f760c56493bdbc01462930e75bb325033b36fe2 \
  aidl:457504c277d796adea698b80b4e765f98dd0799a \
  provider:4135b99cae9362fe2f63380230d04a9bcba9be3f \
  shared:8c4e73ce466cd27aff10565d2ec49809c258f974; do
  name=${entry%%:*} sha=${entry#*:}
  aar="shizuku/$name-$V.aar"
  curl -fsSL -o "$aar" "https://repo1.maven.org/maven2/dev/rikka/shizuku/$name/$V/$name-$V.aar"
  [ "$(shasum "$aar" | cut -d' ' -f1)" = "$sha" ] || { echo "checksum mismatch: $aar" >&2; exit 1; }
  unzip -p "$aar" classes.jar > "shizuku/shizuku-$name.jar"
  echo "libs/shizuku/shizuku-$name.jar"
done
