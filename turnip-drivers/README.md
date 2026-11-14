# Thor Turnip v39 drivers

These are Mesa Turnip Vulkan drivers built for the AYN Thor's Adreno 740 (Snapdragon 8 Gen 2, Android 13), packaged as AdrenoTools zips. They follow StevenMXZ's [Adreno-Tools-Drivers](https://github.com/StevenMXZ/Adreno-Tools-Drivers) v39 recipe.

| Zip | Name in apps | What it is |
|---|---|---|
| [`Thor_Standard_Turnip_v39.zip`](releases/Thor_Standard_Turnip_v39.zip) | Thor Standard - Turnip v39 | The v39 A7xx driver, built for the Thor |
| [`Thor_MeshShaders_Turnip_v39.zip`](releases/Thor_MeshShaders_Turnip_v39.zip) | Thor Mesh Shaders - Turnip v39 | v39 "Patched": adds `VK_EXT_mesh_shader` (emulated with compute) and wave32 subgroups (minSubgroupSize 32) |

## Install

Import the zip in any app that loads AdrenoTools custom drivers, such as emulators and ports with a "GPU driver" option. The standard system driver is the safe fallback, so switch back to it if a game misbehaves.

## What's Thor-specific

- **Mesa source:** commit `2648b2059f` (Mesa 27.0.0-devel), with the v39 A7xx recipe:
  - the revert of `a70d2af5`
  - the source edits, which [`thor_recipe.py`](thor_recipe.py) ports from GNU sed to Python so they run on macOS
- **Toolchain:** compiled against Android API 33 (the Thor's Android 13), so the driver can't need newer system libraries. It uses `-Dplatform-sdk-version=36`, so Mesa still exposes the same 173 device extensions as v39.
- **CPU flags:** `-march=armv8.6-a+crypto+fp16+fp16fml -mtune=cortex-a715`. All eight Thor cores support ARMv8.6 (no SVE).
- **Recipe patches:** v39's UBWC "OneUI" patch isn't used, because upstream Mesa already matches the A740's Vulkan blob (V@0676.53). Patches 0003–0006 target A8xx only.

## Tested on the Thor (2026-11-11)

- Both drivers pass the [`test/vktest.c`](test/vktest.c) checks, as does the official v39 A7xx build. The checks cover device info, compute, subgroup operations at the default, minimum and maximum sizes, a triangle draw, and mesh and task+mesh draws.
- The Mesh Shaders build reports 174 extensions.
- The Naruto Storm Generations native port runs at a steady 30 FPS on both, with no driver errors.

## Build

```sh
VARIANT=thor ./build_thor_turnip.sh           # Thor Standard
VARIANT=thor-patched ./build_thor_turnip.sh   # Thor Mesh Shaders
PACKAGE_ONLY=1 VARIANT=thor ./build_thor_turnip.sh   # re-zip without recompiling
```

Each variant builds in about 2 minutes on an M4 Mac. The script expects, next to itself:

- `mesa/`: a clone of https://gitlab.freedesktop.org/mesa/mesa. The script makes a worktree at the pinned commit for each variant.
- `upstream/rel/`: the StevenMXZ recipe at commit `1112e4ed33`. The patched variant applies `patches/patchsv1/0001` and `0002` from it.
- `android-ndk-r29/`: the Android NDK r29 (macOS).
- `venv/`: a Python 3.12 virtualenv with meson, ninja, mako, pyyaml and packaging.
- `tools/bison/`: bison 3.8; macOS's bison 2.3 is too old.
- `tools/glslang/`: glslangValidator, which Turnip's ray-tracing shaders need.

The output zip and its `meta.json` go to `out/<variant>/`.

## Test harness

`test/vktest.c` loads a driver straight through its Android HAL entry point, the way AdrenoTools does, and runs the checks above. It doesn't need root.

```sh
$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android33-clang \
  -I mesa/include -I mesa/include/android_stub test/vktest.c -ldl -o test/vktest
adb push test/vktest libvulkan_freedreno.so /data/local/tmp/
adb shell 'cd /data/local/tmp && ./vktest ./libvulkan_freedreno.so'
```

The shaders live in `test/shaders/`. They're compiled into `test/shaders.h` with `glslangValidator --target-env vulkan1.3 --vn spv_<name>`.

## License

Mesa is MIT-licensed. The recipe and patches are StevenMXZ's.

## History

- **2026-11-11:** Built the Standard and Mesh Shaders variants. They were renamed the same day so the two are easy to tell apart in file pickers, which cut names off after about 16 characters.
