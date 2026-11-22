#!/bin/bash
# Downloads the libraries AniDeck bundles (the Aniyomi extension runtime and the video player) and
# the Kotlin compiler, checks each file against a pinned SHA-256, and unpacks them into libs/ and
# tools/kotlinc/. Both folders stay out of git and out of the published repo.
set -euo pipefail
cd "$(dirname "$0")/.."

MC=https://repo1.maven.org/maven2
GM=https://dl.google.com/dl/android/maven2
JP=https://jitpack.io
KOTLIN_ZIP=https://github.com/JetBrains/kotlin/releases/download/v2.3.21/kotlin-compiler-2.3.21.zip
KOTLIN_SHA=a8cfc1d62cd4d0de4d04f42575e40135bd620588c17d568a20eb9c7c259af14f

# sha256, then "run" (bundled in the app) or "compile" (only needed to compile), then the url.
ARTIFACTS="
6f64eac736db9434dd6925b4a518b9d1d17177652320c37916cf9ba3ce7d7d7a run $MC/org/jetbrains/kotlin/kotlin-stdlib/2.3.21/kotlin-stdlib-2.3.21.jar
5ca175b38df331fd64155b35cd8cae1251fa9ee369709b36d42e0a288ccce3fd run $MC/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar
e713f1f874244115a07571065cffa0f24f5e78300e9720fea16de3af1d75fd41 run $MC/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.10.2/kotlinx-coroutines-android-1.10.2.jar
f0adde45864144475385cf4aa7e0b7feb27f61fcf9472665ed98cc971b06b1eb run $MC/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.7.3/kotlinx-serialization-core-jvm-1.7.3.jar
b1e9138499ed8d20375edda3f2b1c95f3103a258eff6af9edc5ea07100f2b29c run $MC/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.7.3/kotlinx-serialization-json-jvm-1.7.3.jar
7c85fb07055583a2e58dfdcee9dc19fef7255a323285707f82a0adc84f2ee84d run $MC/org/jetbrains/kotlinx/kotlinx-serialization-protobuf-jvm/1.7.3/kotlinx-serialization-protobuf-jvm-1.7.3.jar
11b77a0e46126d92b4ef1470e0b448a261726728135f8f3ad015a5f4aa3cf266 run $MC/org/jetbrains/kotlinx/kotlinx-serialization-json-okio-jvm/1.7.3/kotlinx-serialization-json-okio-jvm-1.7.3.jar
f1a78812ebeef4d45eee0dbeb4f27347210f564d2b8ee411f012ce8573b0a658 run $MC/com/squareup/okhttp3/okhttp-android/5.3.2/okhttp-android-5.3.2.aar
2196b993cd34dbbd919e7e01f57a4781b58bee80f86106163e287c20343a96a7 run $MC/com/squareup/okio/okio-jvm/3.16.4/okio-jvm-3.16.4.jar
cfd3298d8720cddfb0545109ccc6ea0ef39eff7e9c40a10ad95c93a65f01c916 run $MC/org/jsoup/jsoup/1.22.1/jsoup-1.22.1.jar
b20a0addc8dd091b22f16c730000204030734231678d2b88538be0ac579510e2 run $MC/app/cash/quickjs/quickjs-android/0.9.2/quickjs-android-0.9.2.aar
de864c47818157141a24c9acb36df0c47d7bf15b7ff48c90610f3eb4e5df0e58 run $MC/org/nanohttpd/nanohttpd/2.3.1/nanohttpd-2.3.1.jar
2c3e41d1b380f2044d257947a3aa82dabf3ae4b978622745254aa18b6cf89ab0 run $MC/com/google/guava/guava/33.3.1-android/guava-33.3.1-android.jar
8a8f81cf9b359e3f6dfa691a1e776985c061ef2f223c9b2c80753e1b458e8064 run $MC/com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar
b21f5df2e1f9b4c99747e4eae6e1488892fa90e429f800ae039a515208f85dfe run $JP/com/github/null2264/injekt/injekt-core/4135455a2a/injekt-core-4135455a2a.jar
62eae13a5bc2ce4c859ff1796bac68741b96870fa88fa5e5f1d9852429d51f96 run $JP/com/github/null2264/injekt/injekt-api/4135455a2a/injekt-api-4135455a2a.jar
f51d361d17b30b09cfb9e2aebf301d113f99437cbf462a066092795fb60eddd2 run $GM/androidx/startup/startup-runtime/1.2.0/startup-runtime-1.2.0.aar
4810315a798690b5d3bb40c92bcd41c045fd2c36bbfc50b27cbac3d6a0eda190 run $GM/androidx/concurrent/concurrent-futures/1.2.0/concurrent-futures-1.2.0.jar
1804105e9e05fdd8f760413bad5de498c381aa329f4f9d94c851bc891ac654c6 run $GM/androidx/exifinterface/exifinterface/1.3.6/exifinterface-1.3.6.aar
50813cf219c7c93ed8a32a750891ca6d1280729ecf7a90c49567adacd67ef31b run $GM/androidx/media3/media3-common/1.11.1/media3-common-1.11.1.aar
930fca2f018f06f1d08c12599f8c97f31eddbd728edee5de06eb53a882f8fc54 run $GM/androidx/media3/media3-container/1.11.1/media3-container-1.11.1.aar
520e6f814e42a7d428db187ac073b453c0fcba0f4f5f4edd1cf33fafd2cfb740 run $GM/androidx/media3/media3-database/1.11.1/media3-database-1.11.1.aar
490fd23c7562a6a1183d63bf854b291ae3a77dce5a151450aa1937148c80c60b run $GM/androidx/media3/media3-datasource/1.11.1/media3-datasource-1.11.1.aar
c3bcd704959026e0d644b0260f9e682a2e26e7357c2cf82d990bcb323f800d49 run $GM/androidx/media3/media3-decoder/1.11.1/media3-decoder-1.11.1.aar
6b2d58d23a0c53425249d8b7c945600019d61a8b404c8fb8cd9ca860c5738508 run $GM/androidx/media3/media3-extractor/1.11.1/media3-extractor-1.11.1.aar
588592baec110837c33ce0c82bb76464203e6b0621177bbe5d88779001a6f1ed run $GM/androidx/media3/media3-exoplayer/1.11.1/media3-exoplayer-1.11.1.aar
bd7aeac8dc60b3ee926eddbe05e29f27dc53c81514cae3271b2edab693f34262 run $GM/androidx/media3/media3-exoplayer-hls/1.11.1/media3-exoplayer-hls-1.11.1.aar
eda5ca5879d1016b2f6cb7a79fd8bef864017a3e62e8ece8c25b3a595373920c run $GM/androidx/media3/media3-exoplayer-dash/1.11.1/media3-exoplayer-dash-1.11.1.aar
e34237025865cc24168fcc9653d52ce66ad92d2cfdd2068e751a919846b76908 run $GM/androidx/media3/media3-datasource-okhttp/1.11.1/media3-datasource-okhttp-1.11.1.aar
1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab compile $MC/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar
1e343917ebf27ba96fe4dc52b1cad7fd32b738fbc6355bb6cd5b3b305d7212d0 compile $GM/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar
"

CACHE=libs/cache
mkdir -p "$CACHE"

fetch() {  # sha256 url -> path of the verified download
  local sha=$1 url=$2 out="$CACHE/$(basename "$2")"
  if [ ! -f "$out" ] || ! echo "$sha  $out" | shasum -a 256 -c --status; then
    echo "downloading $(basename "$url")" >&2
    curl -fsSL --retry 3 -o "$out.part" "$url"
    mv "$out.part" "$out"
  fi
  if ! echo "$sha  $out" | shasum -a 256 -c --status; then
    rm -f "$out"
    echo "checksum mismatch for $url" >&2
    exit 1
  fi
  echo "$out"
}

rm -rf libs/jars libs/compile libs/jni libs/assets libs/res libs/dex
mkdir -p libs/jars libs/compile libs/jni libs/assets libs/res
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "$ARTIFACTS" | while read -r sha kind url; do
  [ -z "$sha" ] && continue
  file=$(fetch "$sha" "$url")
  name=$(basename "$url"); name=${name%.*}
  if [ "$kind" = compile ]; then
    cp "$file" "libs/compile/$name.jar"
    continue
  fi
  if [[ $file == *.aar ]]; then
    rm -rf "$TMP/aar" && mkdir -p "$TMP/aar"
    unzip -q -o "$file" -d "$TMP/aar"
    cp "$TMP/aar/classes.jar" "libs/jars/$name.jar"
    [ -d "$TMP/aar/jni/arm64-v8a" ] && { mkdir -p libs/jni/arm64-v8a; cp "$TMP/aar/jni/arm64-v8a/"*.so libs/jni/arm64-v8a/; }
    [ -d "$TMP/aar/assets" ] && cp -R "$TMP/aar/assets/." libs/assets/
  else
    cp "$file" "libs/jars/$name.jar"
  fi
done

# Java resources the runtime looks up by name: service registrations and Kotlin's builtins metadata.
for jar in libs/jars/*.jar; do
  unzip -q -o "$jar" 'META-INF/services/*' 'kotlin/*.kotlin_builtins' 'kotlin/*/*.kotlin_builtins' 'kotlin/*/*/*.kotlin_builtins' -d libs/res 2>/dev/null || true
done

if [ ! -x tools/kotlinc/bin/kotlinc ]; then
  zip=$(fetch "$KOTLIN_SHA" "$KOTLIN_ZIP")
  rm -rf tools/kotlinc
  unzip -q -o "$zip" -d tools
fi
echo "libraries ready: $(ls libs/jars | wc -l | tr -d ' ') jars, kotlinc $(tools/kotlinc/bin/kotlinc -version 2>&1 | grep -o '[0-9][0-9.]*' | head -1)"
