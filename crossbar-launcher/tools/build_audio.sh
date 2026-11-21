#!/bin/bash
# Builds libcbaudio.so, the native player's sound decoder: a trimmed FFmpeg (MKV/MP4/TS demuxers and
# audio decoders only) linked with app/jni/cbaudio.c. Android's own MKV extractor drops E-AC3, DTS and
# TrueHD tracks on the Thor's firmware, and it has no decoders for them either.
#   tools/build_audio.sh           build FFmpeg once (cached), then the wrapper
#   tools/build_audio.sh --clean   start over
# Output: tools/ffmpeg-android/lib/arm64-v8a/libcbaudio.so (third-party code: never published).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
DIR="$ROOT/tools/ffmpeg-android"
VER=7.1.5
SHA=de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f
NDK=${NDK:-$HOME/thor-turnip/android-ndk-r29}
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
CC="$TC/bin/aarch64-linux-android33-clang"
SRC="$DIR/ffmpeg-$VER"
OUT="$DIR/out"
LIB="$DIR/lib/arm64-v8a/libcbaudio.so"

SELF="$ROOT/tools/build_audio.sh"

[ "${1:-}" = "--clean" ] && rm -rf "$SRC" "$OUT" "$DIR/lib"
if [ -f "$LIB" ] && [ "$LIB" -nt app/jni/cbaudio.c ] && [ "$LIB" -nt "$SELF" ]; then
  ls -la "$LIB"
  exit 0
fi
[ -x "$CC" ] || { echo "NDK not found at $NDK (set NDK=...)"; exit 1; }
mkdir -p "$DIR"

if [ ! -f "$OUT/lib/libavcodec.a" ]; then
  TAR="$DIR/ffmpeg-$VER.tar.xz"
  [ -f "$TAR" ] || curl -sSfL -o "$TAR" "https://ffmpeg.org/releases/ffmpeg-$VER.tar.xz"
  echo "$SHA  $TAR" | shasum -a 256 -c - >/dev/null || { echo "checksum mismatch for $TAR"; exit 1; }
  rm -rf "$SRC" && tar -xf "$TAR" -C "$DIR"
  cd "$SRC"
  ./configure \
    --prefix="$OUT" --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile \
    --cc="$CC" --cxx="$CC++" --ld="$CC" \
    --ar="$TC/bin/llvm-ar" --nm="$TC/bin/llvm-nm" --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip" \
    --sysroot="$TC/sysroot" --pkg-config=false \
    --enable-pic --enable-static --disable-shared \
    --disable-programs --disable-doc --disable-debug --disable-everything --disable-autodetect \
    --disable-avdevice --disable-avfilter --disable-swscale --disable-postproc --disable-network \
    --enable-avformat --enable-avcodec --enable-swresample --enable-zlib \
    --enable-demuxer=matroska,mov,mpegts \
    --enable-decoder=aac,aac_latm,ac3,eac3,dca,truehd,mlp,mp2float,mp3float,opus,vorbis,flac,alac \
    --enable-decoder=pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_f32le,pcm_bluray,pcm_dvd \
    --enable-parser=aac,aac_latm,ac3,dca,mlp,mpegaudio,opus,vorbis,flac \
    --extra-cflags="-O2 -fPIC -ffunction-sections -fdata-sections" >"$DIR/configure.log" 2>&1 \
    || { tail -20 "$DIR/configure.log"; exit 1; }
  make -j"$(sysctl -n hw.ncpu)" >"$DIR/make.log" 2>&1 || { tail -30 "$DIR/make.log"; exit 1; }
  make install >>"$DIR/make.log" 2>&1
  cd "$ROOT"
fi

mkdir -p "$(dirname "$LIB")"
"$CC" -shared -fPIC -O2 -Wall -Werror=implicit-function-declaration -I"$OUT/include" \
  app/jni/cbaudio.c -o "$LIB" -L"$OUT/lib" -lavformat -lavcodec -lswresample -lavutil -lz -lm -llog \
  -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--no-undefined -Wl,-z,max-page-size=16384
"$TC/bin/llvm-strip" --strip-unneeded "$LIB"
ls -la "$LIB"
