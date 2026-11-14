# AYN Thor Projects

Software I made for my AYN Thor with [Grok Build](https://x.ai/cli). The Thor is a two-screen Android 13 handheld with a Snapdragon 8 Gen 2 and an Adreno 740 GPU.

| Project | What it is |
|---|---|
| [CrossBar](crossbar-launcher/) | A PSP/PS3 XMB-style home launcher for both screens, with a game library, emulator launching, and 30/60 FPS presets |
| [AniDeck](anideck/) | A two-screen app for watching anime on miruro.cx, with controller support and AniList lists |
| [Coronamon](coronamon/) | An on-screen companion that installs, updates and watches over the apps made for the Thor |
| [Turnip drivers](turnip-drivers/) | Thor-tuned Mesa Turnip v39 Vulkan drivers, packaged for AdrenoTools |
| [Naruto port English toolkit](naruto-port-english/) | Translates the Naruto Storm Generations port's launcher to English |
| [Thor Finder](thor-finder/) | Shows the Thor's storage in macOS Finder over USB |
| [Thor tools](thor-tools/) | Small helpers for scripting the Thor over adb |

## Timeline

Commits are in the order the projects were last updated, and each is dated to when its files last changed. Each project's README has its own history.

- **2026-11-11:** Turnip drivers, the Naruto port English build, Thor Finder
- **2026-11-12:** CrossBar (with its bottom screen and joystick lights), AniDeck, Coronamon
- **2026-11-13:** CrossBar performance presets and bottom-screen feeds; AniDeck crash recovery, recently played and saved shows; Coronamon bug log, with new bugs sent to Grok Build and GitHub issues; CrossBar problem reports to Coronamon

## Building

The Android apps build without Gradle (aapt2, javac, d8, zipalign, apksigner) from a single `build.sh`. Each README lists the toolchain it expects.

- Signing keys aren't in this repository. Each build script creates a new key in `keys/` the first time it runs.
- An APK signed with a different key can't update an installed copy; uninstall it first, which clears that app's data.

## What isn't here

- Games, ROMs, BIOS files, keys, firmware and saves.
- Emulators, other people's apps and their APKs.
- Toolchains (JDK, Android SDK, NDK and build-tools), the Mesa source tree, and third-party libraries. Each README says where to get them.
