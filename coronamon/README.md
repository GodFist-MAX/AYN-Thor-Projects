# Coronamon

Coronamon, the fire Digimon, lives on the Thor's screens as an overlay and looks after the apps made for it with Grok Build.

Package: `dev.grokbuild.coronamon`

## What he does

- **Lives on both screens:** he's drawn in code, with no sprites. He walks, hops between screens, sleeps at night (23:00–07:00), and reacts to taps, petting, dragging and flinging.
- **Stays out of the way:** for games, emulators and videos he hops to the other screen. When both screens are busy, he becomes a see-through ghost at the edge that doesn't block touches.
- **Keeps a registry:** every app built with Grok Build or installed over adb, with versions and history.
- **Installs and updates apps:** as their installer of record, through Shizuku. He keeps the last three APKs of each in `/sdcard/Coronamon/apks/`, and checks GitHub releases every 12 hours.
- **Watches for crashes and ANRs:** he saves reports to `/sdcard/Coronamon/reports/<package>/`.
- **The Den:** an in-app page with the registry, history, crash reports and settings.
- **Reacts to builds and installs** run through the `coro` command-line tool on the Mac.
- **Speech:** his lines are in `app/assets/lines.json`. Add your own in `/sdcard/Coronamon/lines.json`.

## coro (the Mac side)

`coro` is a Python 3 script (standard library only) that talks to the app over adb. It uses `~/platform-tools/adb`, or the path in the `ADB` environment variable.

```text
coro status                          what he's doing, permissions, screens
coro install APK [--label L] [--project DIR]
                                     install or update an app with Coronamon as its installer
coro wrap LABEL -- CMD...            run a build while he bakes; reports success or failure
coro register PKG [--github OWNER/REPO] [--update-mode ask|auto|notify|off] [--avoid yes|no]
coro registry | history [N] | reports [PKG] | check [PKG] | update PKG
coro say TEXT | act MOVE | moves | hide [SECS] | show | summon top|bottom | den
coro shot [top|bottom|both] [--hide]
coro setup                           grant permissions, start Shizuku, wake him
```

Run `coro --help` for everything.

## Build

1. Run `libs/fetch_shizuku.sh` once. It downloads the Shizuku API 13.1.5 libraries (Apache-2.0) from Maven Central into `libs/shizuku/`.
2. Run `./build.sh` to build `build/Coronamon.apk` without Gradle.

It uses CrossBar's JDK and `android.jar` from `~/xmb-launcher/tools` (see [CrossBar's README](../crossbar-launcher/README.md#build)), and Android build-tools 37 at `~/thor-naruto/tools/android-37.0`. The first build creates a signing key in `keys/`.

## Install and set up

```sh
coro install build/Coronamon.apk --label Coronamon
coro setup
```

`coro setup` grants these over adb:
- the overlay, all-files, usage-stats and install permissions
- log and dumpsys access, and notifications

Silent installs and updates on the device also need [Shizuku](https://github.com/RikkaApps/Shizuku). Grant it from the Den's settings.

## Other tools

- `preview/preview.sh` renders his art and animations on the Mac with Java AWT: sprite sheets, single moves, the app icon, and how far each move reaches.
- `test/crashtest/build.sh` builds a throwaway app that crashes on command, for testing installs and crash reports.

## Credits

Coronamon is a Digimon (© Bandai, Toei Animation). This is a fan project. Speech lines tagged `ts` quote Digimon Story: Time Stranger; the other lines are original.

## History

- **2026-11-12:** First version: the overlay on both screens, the app registry, installs and updates, crash reports, GitHub update checks, the Den, and the `coro` tool.
- **2026-11-12:** Art redesigned twice the same evening to look like his Digimon Story: Time Stranger model. The head and mane were traced from the in-game silhouette, with a new face and bigger fists.
- **2026-11-12:** Silent installs and updates through Shizuku.
