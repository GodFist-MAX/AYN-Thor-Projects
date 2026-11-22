#!/bin/bash
# Builds AniDeck.apk without Gradle: aapt2 -> kotlinc + javac -> d8 -> zipalign -> apksigner.
# Uses CrossBar's JDK and android.jar (~/xmb-launcher/tools), the shared build-tools, and the
# libraries and Kotlin compiler that tools/fetch_libs.sh downloads into libs/ and tools/kotlinc/.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
export JAVA_HOME="$HOME/xmb-launcher/tools/jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$HOME/thor-naruto/tools/android-37.0"
AJAR="$HOME/xmb-launcher/tools/android-35/android.jar"
KOTLINC="$ROOT/tools/kotlinc/bin/kotlinc"
OUT="$ROOT/build"
KS="$ROOT/keys/anideck.jks"
KSPASS="$ROOT/keys/anideck.pass"

VERSION_CODE=$(date +%y%m%d%H)
VERSION_NAME="2.0.$(date +%m%d.%H%M)"

if [ ! -f "$KS" ]; then
  mkdir -p "$ROOT/keys"
  chmod 700 "$ROOT/keys"
  ( umask 077; openssl rand -hex 24 > "$KSPASS" )
  keytool -genkeypair -keystore "$KS" -alias anideck -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=AniDeck" -storepass:file "$KSPASS" -keypass:file "$KSPASS" >/dev/null
  chmod 600 "$KS"
fi

if [ ! -x "$KOTLINC" ] || [ ! -d libs/jars ]; then
  tools/fetch_libs.sh
fi
LIBS=$(ls libs/jars/*.jar | tr '\n' ':')
CP="$AJAR:$LIBS$(ls libs/compile/*.jar | tr '\n' ':')"

# The libraries are dexed once and reused until they change.
STAMP=$(cat libs/jars/*.jar | shasum -a 256 | cut -c1-16)
if [ "$(cat libs/dex/.stamp 2>/dev/null)" != "$STAMP" ]; then
  rm -rf libs/dex && mkdir -p libs/dex
  "$BT/d8" --release --min-api 33 --intermediate --lib "$AJAR" --output libs/dex libs/jars/*.jar 2>&1 \
    | grep -v -E '^(Warning|Type|  )' || true
  echo "$STAMP" > libs/dex/.stamp
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/kclasses" "$OUT/dex" "$OUT/assets" "$OUT/apk/lib/arm64-v8a"
cp -R app/assets/. "$OUT/assets/"
cp -R libs/assets/. "$OUT/assets/"

"$BT/aapt2" compile --dir app/res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$AJAR" \
  --manifest app/AndroidManifest.xml --java "$OUT/gen" \
  --min-sdk-version 33 --target-sdk-version 33 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -0 png -A "$OUT/assets" "$OUT/res.zip" --auto-add-overlay

"$KOTLINC" -no-jdk -no-reflect -no-stdlib -jvm-target 17 -nowarn -classpath "$CP" \
  -d "$OUT/kclasses" app/kotlin app/java "$OUT/gen" 2>&1 | grep -v '^warning:' || true
[ -n "$(find "$OUT/kclasses" -name '*.class' -print -quit)" ] || { echo "kotlinc failed" >&2; exit 1; }

find app/java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 17 -encoding UTF-8 -nowarn -Xlint:-options -classpath "$CP:$OUT/kclasses" \
  -d "$OUT/classes" @"$OUT/sources.txt"

find "$OUT/classes" "$OUT/kclasses" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 33 --lib "$AJAR" --output "$OUT/dex" @"$OUT/classes.txt" libs/dex/*.dex
( cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes*.dex )

cp libs/jni/arm64-v8a/*.so "$OUT/apk/lib/arm64-v8a/"
cp -R libs/res/. "$OUT/apk/"
( cd "$OUT/apk" && zip -q -r "$OUT/unsigned.apk" . )

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "file:$KSPASS" \
  --out "$OUT/AniDeck.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/AniDeck.apk"
ls -la "$OUT/AniDeck.apk"
echo "version $VERSION_NAME ($VERSION_CODE)"
