# CrossBar

CrossBar is a home launcher for the AYN Thor. It puts a PSP/PS3-style XrossMediaBar with a PS5-style details panel on the top screen, and turns the bottom screen into a touch companion.

Package: `dev.crossbar.launcher`

## Features

- **XMB menu:** Settings, Photo, Music, Video, TV & Video, Game, Apps, Network and Friends categories, a moving light wave, and generated wallpapers for each category and console.
- **Game library:** read from `/sdcard/Roms/<system>`. Games get box art, descriptions (from Cocoon gamelists or Wikipedia) and play time.
  - Systems: PSP, PS1, PS2, DS, 3DS, Switch, Game Boy, Game Boy Color, Game Boy Advance, NES, SNES, N64, Genesis, Dreamcast, RetroArch and RPG Maker (JoiPlay).
  - You choose the emulator for each system: PPSSPP, melonDS, DraStic, Azahar, Lime3DS, Mandarine, Citra, Eden, Eden Duo, Citron, AetherSX2, ARMSX2, DuckStation, Redream or RetroArch.
- **Bottom screen:** game pages with a big Start button and stats, console and app pages, and sheets that mirror the top screen's menus. It also has a music player, a video remote, a photo grid, and a Now Playing view while a game runs.
- **Bottom-screen feeds:** app and console pages list recently played games and recent saves (PPSSPP, melonDS, RetroArch, Azahar). They also show what media apps played, and items from apps that publish a feed (see below).
- **Media:**
  - local music, with an option to keep it playing during games
  - a local video player
  - Google TV downloads
  - joystick ring lights that follow the colors of the playing artwork
- **Friends:** Steam friends (with your own Steam Web API key), Discord server widgets and Lanyard presence, and notifications from social apps.
- **Performance presets:** in Settings › Performance Settings › Frame Rate and Battery.
- **Problem reports:** when [Coronamon](../coronamon/README.md) is installed, script errors, renderer crashes and launches that fail go to his bug log, and from there to Grok Build and GitHub issues.
  - The choices are 60 FPS · Smooth (default), 60 FPS · Battery Saver, 30 FPS · Smooth and 30 FPS · Battery Saver.
  - Battery Saver stills the light wave when you leave the menu alone and checks for updates less often.
  - CrossBar goes idle while a game runs, whichever GPU driver the game uses.

## Requirements

- AYN Thor on firmware 1.0.0.377 (Android 13). Other devices aren't tested.
- A Mac with [adb](https://developer.android.com/tools/releases/platform-tools). The dev scripts expect it at `~/platform-tools/adb`.

## Build

`./build.sh` builds `build/CrossBar.apk` without Gradle: aapt2, then javac, d8, zipalign and apksigner. It expects:

- JDK 21 at `tools/jdk`, in the macOS layout (`tools/jdk/Contents/Home`), for example [Temurin](https://adoptium.net/)
- `tools/android-35/android.jar`, from Android SDK platform 35
- Android build-tools 37 at `~/thor-naruto/tools/android-37.0`; edit `BT` in `build.sh` to use another folder
- Python 3, which `tools/walls.py --index` uses to rebuild the wallpaper index

The first build creates a signing key in `keys/`. Keep it private and backed up; builds signed with another key can't update an installed copy.

## Install

```sh
adb install -r build/CrossBar.apk
adb shell cmd package set-home-activity dev.crossbar.launcher/.MainActivity
adb shell am start --display 0 -a android.intent.action.MAIN -c android.intent.category.HOME
```

In CrossBar's settings:
- Turn on **System Settings › Notification Access**. Friends and Now Playing need it.
- Use **Game Settings › Grant ROM Folder Access**, so emulators can open games straight from the ROM folder.

## Feeds from other apps

Any app can put its own sections on its CrossBar page:

1. Export a ContentProvider at the authority `<package>.feed`.
2. Answer `call("feed")` with a Bundle whose `"json"` string looks like this:

```json
{"v": 1, "sections": [{"title": "Continue Watching", "style": "cards",
  "items": [{"title": "Show", "sub": "Episode 3 · 12 min left", "img": "https://… or cover/123",
             "progress": 0.4, "at": 1700000000000, "open": {"com.example.EXTRA_ID": 42}}]}]}
```

How CrossBar uses it:
- `style` is `cards`, `posters` or `rows`.
- An `img` that isn't a URL is fetched from the provider with `openFile`.
- Tapping an item launches the app with the `open` values as intent extras (int, long, boolean or string).
- [AniDeck](../anideck/) implements this.

## Development

- `dev/dual.sh NAME [KEY | tap:X,Y | sleep:N ...]` sends keys or bottom-screen taps, then saves both screens to `/tmp/cb_NAME.jpg`.
- `dev/shot.sh NAME [KEY ...]` does the same for the top screen only.
- `dev/perf.py NAME [--keys "down up ..."]` presses the Thor's real controller and reports dropped frames on both screens, using SurfaceFlinger's frame times.
- `dev/cdp.py eval "<js>" [--page bottom]` runs JavaScript in the menu. It needs Settings › System Settings › Theme Development Mode.
- `dev/connect.sh` and `dev/devserver.py` serve the web UI from your working copy and pass data requests through to the Thor.
- `tools/walls.py <dir>` converts wallpaper images to 1920x1080 per category.
- `tools/make_icon.py` draws the app icon and logo.
- `tools/sync_googletv.sh` copies the IDs of Google TV downloads to the Thor, because Android hides that folder from apps. Only movie IDs are copied.

## History

- **2026-11-12:** First version: the XMB menu, game library and emulator launching, music, video, photos, Google TV downloads, friends and wallpapers. It became the Thor's home screen.
- **2026-11-12:** The bottom-screen companion: game pages, console and app pages, menus, music player, video remote, photo grid and the Now Playing view. Game descriptions from Wikipedia.
- **2026-11-12:** Media Settings: Music During Games, and joystick lights that match the playing media.
- **2026-11-13:** Smoother animation on both screens and the four frame-rate presets.
  - A frame pacer evens out the frames, and the light wave is drawn off the main thread.
  - Backdrops are blurred ahead of time, and pictures load at the size they're shown.
  - Worst stall before a frame dropped from about 125 ms to 18 ms.
- **2026-11-13:** Bottom-screen feeds: recently played games, emulator saves, what media apps played, and feeds from other apps. Adds Settings › Media Settings › Clear Recently Played Media.
- **2026-11-13:** Reports its own problems to Coronamon: script errors on either screen, web renderer crashes, and apps or games that fail to start.
- **2026-11-14:** Now Playing works with video apps like AniDeck, YouTube and Google TV. Tapping the bottom screen's Now Playing bar brings the app's video down to the bottom screen, where it keeps playing and the menu keeps the controller. Over a game or app it opens a video card instead, with the picture, progress, ±10 s and track controls, Watch Here, and a button that opens the app itself, which is the choice offered when an app can't show its video there. Also stops a second bottom-screen companion from piling up after updates.
