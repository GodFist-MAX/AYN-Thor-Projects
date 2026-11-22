# AYN Thor Projects

Software I made for my AYN Thor with [Grok Build](https://x.ai/cli). The Thor is a two-screen Android 13 handheld with a Snapdragon 8 Gen 2 and an Adreno 740 GPU.

| Project | What it is |
|---|---|
| [CrossBar](crossbar-launcher/) | A PSP/PS3 XMB-style home launcher for both screens, with a game library, emulator launching, and 30/60 FPS presets |
| [AniDeck](anideck/) | A two-screen anime app built on Aniyomi extensions, with its own player, offline downloads, controller support and AniList lists |
| [Coronamon](coronamon/) | An on-screen companion that installs, updates and watches over the apps made for the Thor |
| [Turnip drivers](turnip-drivers/) | Thor-tuned Mesa Turnip v39 Vulkan drivers, packaged for AdrenoTools |
| [Naruto port English toolkit](naruto-port-english/) | Translates the Naruto Storm Generations port's launcher to English |
| [Thor Finder](thor-finder/) | Shows the Thor's storage in macOS Finder over USB |
| [Thor tools](thor-tools/) | Small helpers for scripting the Thor over adb |

## Timeline

Commits are in the order the projects were last updated, and each is dated to when its files last changed. Each project's README has its own history.

- **2026-11-11:** Turnip drivers, the Naruto port English build, Thor Finder
- **2026-11-12:** CrossBar (with its bottom screen and joystick lights), AniDeck, Coronamon
- **2026-11-13:** CrossBar performance presets and bottom-screen feeds; AniDeck crash recovery, recently played and saved shows; Coronamon bug log, with new bugs sent to Grok Build and GitHub issues; CrossBar problem reports to Coronamon; AniDeck renderer crash reports to Coronamon
- **2026-11-14:** CrossBar Now Playing for video apps; AniDeck on the bottom screen
- **2026-11-15:** checks in with Grok Build each time the Thor is plugged in and hands new bugs to automatic fix runs
- **2026-11-16:** Coronamon: adds a Grok Build page to the Den with buttons to check status, pause or resume automatic runs, turn them off or on, stop the cu…; Downloads: save episodes on the Thor and watch them offline; Updates install on the Thor again
- **2026-11-18:** Coronamon gets a Grok brain: chat in the Den's Talk tab, plus fresh lines every day
- **2026-11-21:** CrossBar: video › Folders now shows the real folder tree, with internal storage and the SD card merged (show and season folders, episodes i…; AniDeck 2.0: a native app on Aniyomi extensions, with its own player and offline downloads; AniDeck: Re:ANIME (reanime.cz) as a recommended source, ready-made MKV downloads, sound in separate-audio downloads, and intro marks offline; no more gray haze over the bottom screen
- **2026-11-22:** Recently watched videos for Coronamon's TV; Played downloads for Coronamon's TV

## Building

The Android apps build without Gradle (aapt2, javac, d8, zipalign, apksigner) from a single `build.sh`. Each README lists the toolchain it expects.

- Signing keys aren't in this repository. Each build script creates a new key in `keys/` the first time it runs.
- An APK signed with a different key can't update an installed copy; uninstall it first, which clears that app's data.

## What isn't here

- Games, ROMs, BIOS files, keys, firmware and saves.
- Emulators, other people's apps and their APKs.
- Toolchains (JDK, Android SDK, NDK and build-tools), the Mesa source tree, and third-party libraries. Each README says where to get them.
