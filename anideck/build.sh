#!/bin/bash
# Builds AniDeck.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Uses CrossBar's JDK and android.jar (~/xmb-launcher/tools) and the shared build-tools.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
export JAVA_HOME="$HOME/xmb-launcher/tools/jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$HOME/thor-naruto/tools/android-37.0"
AJAR="$HOME/xmb-launcher/tools/android-35/android.jar"
OUT="$ROOT/build"
KS="$ROOT/keys/anideck.jks"
KSPASS="$ROOT/keys/anideck.pass"

VERSION_CODE=$(date +%y%m%d%H)
VERSION_NAME="1.0.$(date +%m%d.%H%M)"

if [ ! -f "$KS" ]; then
  mkdir -p "$ROOT/keys"
  chmod 700 "$ROOT/keys"
  ( umask 077; openssl rand -hex 24 > "$KSPASS" )
  keytool -genkeypair -keystore "$KS" -alias anideck -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=AniDeck" -storepass:file "$KSPASS" -keypass:file "$KSPASS" >/dev/null
  chmod 600 "$KS"
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir app/res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$AJAR" \
  --manifest app/AndroidManifest.xml --java "$OUT/gen" \
  --min-sdk-version 33 --target-sdk-version 33 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -0 png -A app/assets "$OUT/res.zip" --auto-add-overlay

find app/java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 17 -encoding UTF-8 -nowarn -Xlint:-options -classpath "$AJAR" \
  -d "$OUT/classes" @"$OUT/sources.txt"

find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 33 --lib "$AJAR" --output "$OUT/dex" @"$OUT/classes.txt"
( cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex )

"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "file:$KSPASS" \
  --out "$OUT/AniDeck.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/AniDeck.apk"
ls -la "$OUT/AniDeck.apk"
echo "version $VERSION_NAME ($VERSION_CODE)"
