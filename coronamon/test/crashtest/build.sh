#!/bin/bash
# Builds build/test/CrashTest.apk (dev.grokbuild.cototest), a throwaway app for testing installs and crash reports.
# Usage: test/crashtest/build.sh [versionName]
set -euo pipefail
cd "$(dirname "$0")/../.."
export JAVA_HOME="$HOME/xmb-launcher/tools/jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$HOME/thor-naruto/tools/android-37.0"
AJAR="$HOME/xmb-launcher/tools/android-35/android.jar"
OUT=build/test
SRC=test/crashtest
VERSION_NAME="${1:-1.0}"
VERSION_CODE=$(( ($(date +%s) - 1767225600) / 60 ))
mkdir -p "$OUT"
rm -rf "$OUT/classes" "$OUT/dex" && mkdir -p "$OUT/classes" "$OUT/dex"
KS="$OUT/test.jks"
if [ ! -f "$KS" ]; then
  ( umask 077; openssl rand -hex 16 > "$OUT/test.pass" )
  keytool -genkeypair -keystore "$KS" -alias test -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=Coronamon crash test" -storepass:file "$OUT/test.pass" -keypass:file "$OUT/test.pass" >/dev/null 2>&1
fi
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$AJAR" --manifest "$SRC/AndroidManifest.xml" \
  --min-sdk-version 33 --target-sdk-version 33 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
javac --release 17 -nowarn -classpath "$AJAR" -d "$OUT/classes" "$SRC/Boom.java"
"$BT/d8" --release --min-api 33 --lib "$AJAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
( cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex )
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "file:$OUT/test.pass" --out "$OUT/CrashTest.apk" "$OUT/aligned.apk"
echo "$OUT/CrashTest.apk $VERSION_NAME ($VERSION_CODE)"
