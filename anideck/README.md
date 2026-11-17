# AniDeck

AniDeck lets you watch anime from [miruro.cx](https://www.miruro.cx) on the AYN Thor using both screens. The site runs on the top screen, and a controller-friendly companion runs on the bottom screen.

Package: `dev.anideck.app`

## Features

- **Top screen:** the site itself, limited to miruro.cx and AniList.
  - Navigate with the D-pad or stick and a highlight ring; A selects and B goes back.
  - Fill-screen playback, where the D-pad seeks and changes volume.
  - Optional automatic theater mode, switching to the next server when one fails, and AniList progress tracking.
- **Bottom screen tabs:**
  - Now Playing: episode controls, recently played episodes, saved shows and your Watching list
  - Up Next, Schedule, Lists (AniList and Saved), Browse and Settings
- **Controller on both screens:** Select moves the controller between screens. Start, X, Y, L1, R1, L2 and R2 control the episode from either screen.
- **AniList:** log in on the site (top screen) for full access, or enter a public username for read-only lists.
- **New-episode notifications:** checked every 30 minutes on Wi-Fi.
- **Saved shows:** keep their details and cover art on the Thor, so they show without internet. Episodes always stream with the site's own player; downloads are blocked on purpose.
- **Media session:** CrossBar's joystick lights follow it.
- **Feed for CrossBar's bottom screen:** Continue Watching, Saved Shows and Watching on AniList.
- **Problem reports:** when [Coronamon](../coronamon/README.md) is installed, renderer crashes go to his bug log, and from there to Grok Build and GitHub issues.

## Requirements

- AYN Thor (Android 13). The Thor's system WebView is version 109, so AniDeck adds `AbortSignal.any`, which the site needs.

## Build

`bash build.sh` builds `build/AniDeck.apk` without Gradle. It uses CrossBar's toolchain:
- the JDK at `~/xmb-launcher/tools/jdk` and `~/xmb-launcher/tools/android-35/android.jar` (see [CrossBar's README](../crossbar-launcher/README.md#build))
- Android build-tools 37 at `~/thor-naruto/tools/android-37.0`

The first build creates a signing key in `keys/`.

## Install

```sh
adb install -r build/AniDeck.apk
adb shell am start --display 0 -n dev.anideck.app/.MainActivity
```

Add `--ez dev true` to the start command to turn on WebView debugging for `dev/cdp.py`.

## Feed

```sh
adb shell content call --uri content://dev.anideck.app.feed --method feed
```

Only the adb shell, AniDeck itself and CrossBar may read it. Items open AniDeck at the right show and episode.

## Development

- `dev/cdp.py pages | eval "<js>" [--page bottom.html]` talks to either screen's page over WebView debugging.
- `dev/pad.sh a b select up down ...` presses the Thor's real controller buttons, using sendevent.
- `dev/hist.py` prints the top screen's back/forward history.
- `tools/make_icon.py` draws the app icon.

## History

- **2026-11-12:** First version: the site on the top screen and the bottom-screen companion (Now Playing, Up Next, Schedule, Lists, Browse, Settings), AniList lists, new-episode notifications and a media session.
- **2026-11-12:** Controller and touch on both screens: highlight navigation, Select switches screens, and episode controls from either screen.
- **2026-11-13:** No more force close when the WebView renderer crashes; both screens reload instead.
- **2026-11-13:** Recently played and saved shows on the bottom screen, and a feed for CrossBar's bottom screen.
- **2026-11-13:** Reports web renderer crashes on either screen to Coronamon.
- **2026-11-14:** Episodes can be watched on the bottom screen: when CrossBar's Now Playing moves AniDeck there, the episode fills the screen as a centered 16:9 picture with no companion, and both come back as they were on the top screen. Seeks from other apps' media controls now land and show right away, even while AniDeck is in the background.
- **2026-11-16:** Downloads. The site's Download button under the video, or Download on the bottom screen's Now Playing, now saves the episode on the Thor (Movies/AniDeck/<show>) in the language you watch in, Sub or Dub, and in the quality set in Settings: 1080p, or 480p for smaller files. When the file host has 1080p ready but 480p is still waiting in its queue after 90 seconds, AniDeck takes the 1080p file and says so. A new Downloads tab on the bottom screen shows progress and lets you play, delete, retry or cancel. Downloaded episodes play without a connection in AniDeck's own player, with the file's subtitle tracks (choose one from the bottom screen), the usual controller buttons, and your place saved. CrossBar gets a Downloaded row that opens them straight away.
