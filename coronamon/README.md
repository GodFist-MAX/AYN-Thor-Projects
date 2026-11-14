# Coronamon

Coronamon, the fire Digimon, lives on the Thor's screens as an overlay and looks after the apps made for it with Grok Build.

Package: `dev.grokbuild.coronamon`

## What he does

- **Lives on both screens:** he's drawn in code, with no sprites. He walks, hops between screens, sleeps at night (23:00–07:00), and reacts to taps, petting, dragging and flinging.
- **Stays out of the way:** for games, emulators and videos he hops to the other screen. When both screens are busy, he becomes a see-through ghost at the edge that doesn't block touches.
- **Keeps a registry:** every app built with Grok Build or installed over adb, with versions and history.
- **Installs and updates apps:** as their installer of record, through Shizuku. He keeps the last three APKs of each in `/sdcard/Coronamon/apks/`, and checks GitHub releases every 12 hours.
- **Watches for crashes and ANRs:** he saves reports to `/sdcard/Coronamon/reports/<package>/`.
- **Keeps a bug log:** crashes, freezes and native crashes of the apps built with Grok Build (and of other apps while they're on screen), plus problems those apps report themselves, such as script errors, renderer crashes and launches that fail. A repeat counts against the same bug. A bug marked fixed that shows up again in the fixed version or later reopens.
- **Report a problem:** from the Den or his notification. He takes screenshots of both screens and the logs of the apps on them; you pick the app, what went wrong, and whether it may go to GitHub.
- **The Den:** an in-app page with the registry, history, crash reports, the bug log and settings.
- **Reacts to builds and installs** run through the `coro` command-line tool on the Mac.
- **Speech:** his lines are in `app/assets/lines.json`. Add your own in `/sdcard/Coronamon/lines.json`.

## coro (the Mac side)

`coro` is a Python 3 script (standard library only) that talks to the app over adb. It uses `~/platform-tools/adb`, or the path in the `ADB` environment variable.

```text
coro status                          what he's doing, permissions, screens
coro install APK [--label L] [--project DIR] [--notes N] [--fixes BUGS] [--no-publish]
                                     install or update an app with Coronamon as its installer,
                                     then publish the update here (see below)
coro wrap LABEL -- CMD...            run a build while he bakes; reports success or failure
coro register PKG [--github OWNER/REPO] [--update-mode ask|auto|notify|off] [--avoid yes|no]
coro registry | history [N] | reports [PKG] | check [PKG] | update PKG
coro say TEXT | act MOVE | moves | hide [SECS] | show | summon top|bottom | den
coro shot [top|bottom|both] [--hide]
coro setup                           grant permissions, start Shizuku, wake him
```

Run `coro --help` for everything.

## Bugs, GitHub and Grok Build

`corolink.py`, loaded by `coro`, connects the bug log to this repository and to Grok Build.

- **To Grok Build:** while the Thor is plugged into the Mac, a watcher (`coro watch`) pulls new bugs into `~/thor-coronamon/bugs/`. The Grok Build hook shows a summary when a session starts and gives the model the details once, after its next tool call. `coro grok-hook install` writes the hook to `~/.grok/hooks/coronamon.json`.
- **To GitHub issues:** each new bug in an app built here opens an issue, labeled `from-thor`, the kind of bug and the project. Repeats, regressions and fixes add comments. A fix closes the issue, and the issue reopens if the bug comes back. The issue holds the crash's signature and a short excerpt, with paths, accounts, addresses, tokens and the device serial removed. Screenshots and full logs stay on the Mac. A problem you report yourself goes to GitHub only if you choose that.
- **Updates:** `coro install APK --notes "..."` commits the project's changed files here as "Update <app> to <version>", listing the files and adding "Fixes #N" for the bugs it fixes (`--fixes`). It adds a line to the project's History and pushes. Without internet, the commit waits and is pushed later.

```text
coro sync [--dry-run] [--no-github]  pull new bugs, open or update issues, push waiting commits
coro bugs [--all] | coro bug ID [fixed [VERSION] | open | ignore | forget]
coro bug-report PKG "TEXT" [--category C] [--github]
coro publish PROJECT|--all [--notes N] [--version V] [--fixes BUGS] [--dry-run]
coro github status|on|off|push       coro watch [--status|--stop]
coro grok-hook install
```

`github.json` maps each project's source files to its folder in this repository and sets which bugs become issues. `coro github off` stops all posting and pushing; bugs still go to Grok Build.

Apps report their own problems through the provider `content://dev.grokbuild.coronamon.bugs` (method `report`, with `kind`, `title` and `detail` extras). Only apps in his registry that were built with Grok Build can use it. The title may be posted publicly; the detail only goes to Grok Build.

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
- **2026-11-13:** Bug log: crashes, freezes and the problems apps report themselves, plus Report a problem with screenshots and logs. When the Thor is plugged into the Mac, new bugs go to Grok Build and to GitHub issues here, and coro install publishes each update to this repository.
