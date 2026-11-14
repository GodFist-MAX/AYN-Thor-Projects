# Naruto port English toolkit

This toolkit translates the launcher and in-app menus of the [Naruto Shippuden: Ultimate Ninja Storm Generations native port](https://github.com/Masterllgod/naruto-storm-generations-port-nativo) (v0.4.16, package `com.naruto.stormgenerations`) from Portuguese to English, then rebuilds and signs the APK.

No APKs or game data are included. You need the original port APK, and the port itself needs your own NTSC-U Xbox 360 copy of the game.

## How it works

`./build_en_apk.sh [original.apk]` runs these steps:

1. `apktool d -r` decodes the app's code (resources are left alone; every UI string is in `classes.dex`).
2. `apply_translations.py` copies the decoded code and replaces the Portuguese string literals using [`translations.py`](translations.py), 226 entries translated by hand. It reports:
   - **NOT FOUND:** table entries that aren't in the code
   - **CHECK:** translated strings that the code also uses as keys or compares, which need a look by hand
   - **LEFT:** Portuguese-looking strings that remain
3. `apktool b` reassembles the code. Then `repack.py` swaps only `classes.dex` into the original APK, keeping every entry's order and compression.
4. `zipalign -P 16 4` aligns the APK, and `apksigner` signs it with a key the script creates on first run (`naruto-en.keystore`, kept out of git).

For a new port version, add entries to `translations.py` until all three reports are clean. `dex_strings.py` prints every string in a DEX file to help find new text, and `smali_strings.py` holds the shared string helpers.

## Requirements

The script looks for its tools in `../tools`:
- `jre/`: Temurin JRE 21, macOS layout
- `apktool.jar`: apktool 3.0.3
- `android-37.0/`: Android build-tools 37, with aapt2, zipalign and `lib/apksigner.jar`

## Switching between the official and English builds

The English build is signed with your own key, so neither build installs over the other. Switching means uninstalling, which clears the app's settings, its game folder choice, imported GPU drivers, and its `Android/data` folder.

1. Back up the save first: `Android/data/com.naruto.stormgenerations/files/data/content/storm_g.s/STORM_G.S`.
2. After reinstalling, launch the app once so it creates its folders, then copy the save back.

## History

- **2026-11-11:** English build of v0.4.16: 226 strings, with the in-game terms JUTSU, OUGI and CHAKRA kept as they are.
