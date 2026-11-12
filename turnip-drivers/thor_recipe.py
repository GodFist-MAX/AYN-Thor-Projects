#!/usr/bin/env python3
"""Source edits of the StevenMXZ v39 A7xx recipe (build_turnip.sh at 1112e4ed33), ported from
GNU sed to Python so they behave the same on macOS. Run from the Mesa source root."""
import pathlib
import re
import sys


def edit(path, fn, label, quiet_noop=False):
    p = pathlib.Path(path)
    if not p.exists():
        print(f"missing  {label} ({path})")
        return False
    old = p.read_text()
    new = fn(old)
    if new != old:
        p.write_text(new)
        print(f"changed  {label} ({path})")
    elif not quiet_noop:
        print(f"no-op    {label} ({path})")
    return new != old


def a7xx_gen1_no_early_preamble(text):
    anchor = re.search(r"^a7xx_gen1 = GPUProps\(\n", text, re.M)
    if not anchor:
        sys.exit("a7xx_gen1 = GPUProps( anchor not found")
    window = "\n".join(text[anchor.end():].splitlines()[:20])
    if "has_early_preamble = False" in window:
        return text
    text = text[:anchor.end()] + "        has_early_preamble = False,\n" + text[anchor.end():]
    compile(text, "freedreno_devices.py", "exec")
    return text


def clean_driver_names(text):
    text = re.sub(r'"Turnip Adreno \(TM\) %s[^"]*"', '"Turnip Adreno (TM) %s%.0s"', text)
    text = text.replace('"turnip Mesa driver (whitebelyash branch)"', '"Turnip"')
    return text.replace('"turnip Mesa driver"', '"Turnip"')


def main():
    edit("src/freedreno/common/freedreno_devices.py", a7xx_gen1_no_early_preamble,
         "a7xx_gen1: has_early_preamble = False")

    renamed = [f for f in sorted(pathlib.Path("src/freedreno/vulkan").rglob("*.c*"))
               if edit(f, clean_driver_names, "driver name cleanup", quiet_noop=True)]
    if not renamed:
        print("no-op    driver name cleanup (src/freedreno/vulkan)")

    # Android/NDK r29 fixes.
    edit("include/android_stub/cutils/native_handle.h",
         lambda s: s.replace("typedef const native_handle_t* buffer_handle_t;", "typedef void* buffer_handle_t;"),
         "buffer_handle_t as void*")
    edit("src/util/u_gralloc/u_gralloc_fallback.c",
         lambda s: s.replace(", hnd->handle", ", (void *)hnd->handle"),
         "cast hnd->handle")
    edit("src/vulkan/runtime/vk_android.c",
         lambda s: re.sub(r"([a-z_]+)->handle->", r"((const native_handle_t *)\1->handle)->", s),
         "cast ->handle-> to native_handle_t")
    edit("meson.build",
         lambda s: "".join(l for l in s.splitlines(True) if "-Werror=gnu-empty-initializer" not in l),
         "drop -Werror=gnu-empty-initializer")

    kgsl = pathlib.Path("src/freedreno/vulkan/tu_knl_kgsl.cc").read_text()
    for needle, what in [("kgsl_syncobj_merge(struct tu_device *device", "1da50a1b syncobj merge"),
                         ("return kgsl_poll_timestamp(device, context_id, timestamp);", "e984ef29 zero-timeout poll")]:
        if needle not in kgsl:
            sys.exit(f"Mesa is missing MR !44838 KGSL fix {what}")
    print("verified MR !44838 KGSL fixes are present")


if __name__ == "__main__":
    main()
