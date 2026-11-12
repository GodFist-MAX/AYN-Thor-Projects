#!/bin/bash
# Build Turnip for the AYN Thor (Adreno 740, Snapdragon 8 Gen 2, Android 13) from the
# StevenMXZ Adreno-Tools-Drivers v39 recipe, packaged as an AdrenoTools zip.
#   VARIANT=thor ./build_thor_turnip.sh
#   VARIANT=thor-patched ./build_thor_turnip.sh
#   PACKAGE_ONLY=1 VARIANT=thor ./build_thor_turnip.sh   # re-zip an existing build without recompiling
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
VARIANT="${VARIANT:-thor}"
MESA_COMMIT=2648b2059f1a412d64a8700dbbb75bbb4a768b0e
REVERT_COMMIT=a70d2af590db192f87b3af01f83a68b450edb4c3
RECIPE="$ROOT/upstream/rel"
# Compile against the Thor's Android 13 NDK API so nothing newer can be linked.
NDK_API=33
# Mesa only advertises a KHR extension on Android when this is at least the extension's gate
# (maintenance5-7, line_rasterization, pipeline_binary... need 35-36); v39 uses 36.
SDK_LEVEL=36
NDK="$ROOT/android-ndk-r29/toolchains/llvm/prebuilt/darwin-x86_64/bin"
SRC="$ROOT/src-$VARIANT"
OUT="$ROOT/out/$VARIANT"
# All eight Thor cores report the ARMv8.6 feature set; SVE is disabled on this SoC.
CPU_FLAGS="-march=armv8.6-a+crypto+fp16+fp16fml -mtune=cortex-a715"
WARN_FLAGS="-D__ANDROID__ -Wno-error -Wno-error=gnu-empty-initializer -Wno-gnu-empty-initializer -Wno-deprecated-declarations -Wno-incompatible-pointer-types-discards-qualifiers -Wno-incompatible-pointer-types"

export PATH="$ROOT/venv/bin:$ROOT/tools/bison/bin:$ROOT/tools/glslang/bin:$PATH"

log() { printf '\033[0;32m%s\033[0m\n' "$*"; }
die() { printf '\033[0;31m%s\033[0m\n' "$*" >&2; exit 1; }
meson_list() { local f out=""; for f in "$@"; do out="$out'$f', "; done; printf '%s' "${out%, }"; }

case "$VARIANT" in
  # File pickers cut names off after about 16 characters, so the variant comes right after "Thor".
  thor)
    NAME="Thor Standard - Turnip v39"
    ZIP_NAME="Thor_Standard_Turnip_v39.zip"
    DESC="AYN Thor build of StevenMXZ Turnip v39 (A7xx), tuned for Snapdragon 8 Gen 2 and Android 13"
    ;;
  thor-patched)
    NAME="Thor Mesh Shaders - Turnip v39"
    ZIP_NAME="Thor_MeshShaders_Turnip_v39.zip"
    DESC="AYN Thor build of StevenMXZ Turnip v39 Patched (A7xx): adds VK_EXT_mesh_shader emulation and wave32 subgroups"
    ;;
  *) die "VARIANT must be thor or thor-patched" ;;
esac

LIB="$OUT/install/lib/libvulkan_freedreno.so"

package() {
  local vk_header
  vk_header=$(awk '/#define VK_HEADER_VERSION /{print $3}' "$SRC/include/vulkan/vulkan_core.h")
  cat > "$OUT/meta.json" <<EOF
{
  "schemaVersion": 1,
  "name": "$NAME",
  "description": "$DESC. Mesa 27.0.0-devel ${MESA_COMMIT:0:10}",
  "author": "StevenMXZ recipe, Thor build",
  "packageVersion": "1",
  "vendor": "Mesa",
  "driverVersion": "Vulkan 1.4.$vk_header",
  "minApi": $NDK_API,
  "libraryName": "libvulkan_freedreno.so"
}
EOF
  python3 -m json.tool "$OUT/meta.json" > /dev/null
  rm -f "$OUT"/*.zip
  zip -9 -q -j "$OUT/$ZIP_NAME" "$LIB" "$OUT/meta.json"
  log "Packaged $OUT/$ZIP_NAME"
  shasum -a 256 "$LIB" "$OUT/$ZIP_NAME"
}

if [ "${PACKAGE_ONLY:-0}" = 1 ]; then
  [ -f "$LIB" ] || die "no existing build in $OUT; run without PACKAGE_ONLY first"
  package
  exit
fi

log "Mesa $MESA_COMMIT -> $SRC"
git -C "$ROOT/mesa" worktree remove --force "$SRC" 2>/dev/null || true
rm -rf "$SRC" "$OUT"
git -C "$ROOT/mesa" worktree prune
git -C "$ROOT/mesa" worktree add -q --detach "$SRC" "$MESA_COMMIT"
cd "$SRC"

log "v39 A7xx recipe: revert $REVERT_COMMIT (D32S8 EARLY_Z_LATE_Z workaround)"
git revert --no-commit "$REVERT_COMMIT"
python3 "$ROOT/thor_recipe.py"

if [ "$VARIANT" = thor-patched ]; then
  for p in "$RECIPE"/patches/patchsv1/0001-*.patch "$RECIPE"/patches/patchsv1/0002-*.patch; do
    log "v39 Patched: $(basename "$p")"
    git apply --check "$p" || die "does not apply cleanly: $p"
    git apply "$p"
  done
fi

mkdir -p "$OUT"
# shellcheck disable=SC2086
cat > "$OUT/cross.txt" <<EOF
[binaries]
ar = '$NDK/llvm-ar'
c = ['$NDK/aarch64-linux-android$NDK_API-clang']
cpp = ['$NDK/aarch64-linux-android$NDK_API-clang++', '-fno-exceptions', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '--start-no-unused-arguments', '-static-libstdc++', '--end-no-unused-arguments']
c_ld = '$NDK/ld.lld'
cpp_ld = '$NDK/ld.lld'
strip = '$NDK/llvm-strip'
pkg-config = 'false'

[built-in options]
c_args = [$(meson_list $WARN_FLAGS $CPU_FLAGS)]
cpp_args = [$(meson_list $WARN_FLAGS $CPU_FLAGS)]
c_link_args = ['-fuse-ld=lld']
cpp_link_args = ['-fuse-ld=lld']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
EOF

cat > "$OUT/native.txt" <<EOF
[binaries]
c = '/usr/bin/clang'
cpp = '/usr/bin/clang++'
ar = '/usr/bin/ar'
strip = '/usr/bin/strip'
EOF

log "meson setup (NDK API $NDK_API, platform SDK $SDK_LEVEL, $CPU_FLAGS)"
meson setup "$OUT/build" \
  --cross-file "$OUT/cross.txt" \
  --native-file "$OUT/native.txt" \
  --prefix "$OUT/install" \
  -Dbuildtype=release \
  -Dstrip=true \
  -Dplatforms=android \
  -Dvideo-codecs= \
  -Dplatform-sdk-version=$SDK_LEVEL \
  -Dandroid-stub=true \
  -Dgallium-drivers= \
  -Dvulkan-drivers=freedreno \
  -Dvulkan-beta=true \
  -Dfreedreno-kmds=kgsl \
  -Degl=disabled \
  -Dandroid-libbacktrace=disabled

log "ninja"
ninja -C "$OUT/build" install

[ -f "$LIB" ] || die "libvulkan_freedreno.so was not built"
package
