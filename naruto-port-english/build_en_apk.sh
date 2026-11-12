#!/bin/bash
# Build an English copy of the Naruto Storm Generations port APK: translate the launcher's
# string literals (translations.py), reassemble classes.dex, swap it into the original APK,
# align and sign with the local key so later builds install over this one.
#   ./build_en_apk.sh [original.apk]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$HERE/../tools"
export JAVA_HOME="$TOOLS/jre/Contents/Home"
JAVA="$JAVA_HOME/bin/java"
BT="$TOOLS/android-37.0"
ORIG="${1:-$HERE/installed-base.apk}"
VERSION=$("$BT/aapt2" dump badging "$ORIG" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
OUT="$HERE/NarutoStormGenerations-v$VERSION-EN.apk"
KS="$HERE/naruto-en.keystore"
KS_PASS="$HERE/naruto-en.keystore.pass"

cd "$HERE"
rm -rf decoded translated build
"$JAVA" -jar "$TOOLS/apktool.jar" d -q -r -f "$ORIG" -o decoded
python3 apply_translations.py
"$JAVA" -jar "$TOOLS/apktool.jar" b -q translated -o build/apktool-out.apk
python3 repack.py "$ORIG" translated/build/apk/classes.dex build/unaligned.apk
"$BT/zipalign" -f -P 16 4 build/unaligned.apk build/aligned.apk
"$BT/zipalign" -c -P 16 4 build/aligned.apk

if [ ! -f "$KS" ]; then
  umask 077
  python3 -c 'import secrets; print(secrets.token_urlsafe(24))' > "$KS_PASS"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass:file "$KS_PASS" -keypass:file "$KS_PASS" \
    -alias naruto-en -keyalg RSA -keysize 3072 -validity 10000 \
    -dname "CN=Naruto Storm Generations port (English personal build)"
fi
"$JAVA" -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass "file:$KS_PASS" --ks-key-alias naruto-en \
  --out "$OUT" build/aligned.apk
"$JAVA" -jar "$BT/lib/apksigner.jar" verify --verbose "$OUT" | grep -E "^Verifies|scheme"
echo "Built $OUT"
shasum -a 256 "$OUT"
