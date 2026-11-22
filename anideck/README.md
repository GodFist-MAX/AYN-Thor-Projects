# AniDeck

AniDeck is a two-screen anime app for the AYN Thor. It finds, streams and downloads shows through [Aniyomi](https://github.com/aniyomiorg/aniyomi) extensions from the [Yūzōnō repo](https://github.com/yuzono/anime-extensions), plays them in its own video player, and keeps downloaded episodes for watching offline. The catalog and player run on the top screen, and a controller-friendly companion runs on the bottom screen.

Package: `dev.anideck.app`

## Features

- **Sources:** the Extensions tab installs Aniyomi extensions from the Yūzōnō repo without a system install prompt, checks that each is signed by the repo, and updates or removes them. Each source's own settings are there too. AniDeck doesn't come with any extensions; Re:ANIME (reanime.cz) and AniZone are marked Recommended, since they browse, stream and download well on the Thor.
- **Top screen:** Home (Continue watching, Watching on AniList, and the latest and popular shows on each source), Browse with the source's filters, Search across your sources, Library (history, saved shows and AniList lists), Downloads, Extensions and Settings.
  - Show pages have the details, the episode list (oldest or newest first), Resume, Save, Download and the AniList link.
  - Navigate with the D-pad or stick and a highlight; A selects and B goes back.
- **Player (Media3 ExoPlayer):** picks a server, quality, subtitles and audio in your languages, with menus to change them and the speed. It skips intros and recaps on sources that mark them (otherwise X skips 85 seconds), goes to the next or previous episode, and resumes where you left off.
- **Downloads:** episodes save to `Movies/AniDeck/<show>/Episode NN - Title.mp4` (or `.mkv`), with their subtitles and intro marks, in the quality set in Settings (360p up to the best available; 1080p by default). When a server offers a ready-made file, like Re:ANIME's Download servers, AniDeck saves that file with all its audio and subtitle tracks. Otherwise HLS streams are joined into an MP4, taking the audio in your language when the source sends audio separately. The queue runs in the background with a progress notification. Downloaded episodes play without a connection, and Next at the last downloaded episode carries on streaming when you're online.
- **Offline:** Home, show pages and Downloads show what's on the Thor when there's no connection, and load the rest when it comes back.
- **Bottom screen tabs:**
  - Now Playing: episode controls, server, subtitle, audio and speed pills, the episode strip, Continue watching and your Watching list
  - Up Next, Schedule, Lists, Browse, Downloads and Settings
- **Controller on both screens:** Select moves the controller between screens. Start, X, Y, L1, R1, L2 and R2 control the episode from either screen.
- **AniList:** log in on the top screen for full access, or enter a public username for read-only lists. Shows link to their AniList entries (by the AniList id in the cover art, for sources that use AniList's covers, else by title), and your progress moves forward at 85% of an episode.
- **New-episode notifications:** checked every 30 minutes on Wi-Fi.
- **Saved shows:** keep their details and cover art on the Thor, so they show without internet.
- **Media session:** CrossBar's joystick lights follow it.
- **Feed for CrossBar's bottom screen:** Continue Watching, Downloaded, Saved Shows and Watching on AniList.
- **Problem reports:** when [Coronamon](../coronamon/README.md) is installed, renderer crashes go to his bug log, and from there to Grok Build and GitHub issues.

## Requirements

- AYN Thor (Android 13). The screens' pages are written for the Thor's system WebView, version 109.
- A network that can reach the sources you install. Some sites are blocked on some networks; try another source.

## Build

`bash build.sh` builds `build/AniDeck.apk` without Gradle (aapt2, kotlinc and javac, d8, zipalign, apksigner). It uses CrossBar's toolchain:
- the JDK at `~/xmb-launcher/tools/jdk` and `~/xmb-launcher/tools/android-35/android.jar` (see [CrossBar's README](../crossbar-launcher/README.md#build))
- Android build-tools 37 at `~/thor-naruto/tools/android-37.0`

The first build runs `tools/fetch_libs.sh`. It downloads the Kotlin compiler and the libraries AniDeck bundles (Media3, OkHttp, Jsoup, kotlinx coroutines and serialization, Injekt, QuickJS, NanoHTTPD and Guava) from Maven Central, Google's Maven repository and JitPack, checks each file against a pinned SHA-256, and unpacks them into `libs/` and `tools/kotlinc/`. Neither folder is in this repository.

The first build also creates a signing key in `keys/`.

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

Only the adb shell, AniDeck itself and CrossBar may read it. Items open AniDeck at the right show and episode; Downloaded items play the download.

## Development

- `dev/cdp.py pages | eval "<js>" [--page bottom.html] | shot <png>` talks to either screen's page over WebView debugging.
- `dev/pad.sh a b select up down ...` presses the Thor's real controller buttons, using sendevent.
- `dev/hist.py` prints the top screen's page stack.
- `tools/fetch_libs.sh` downloads the compiler and libraries; `build.sh` runs it when they're missing.
- `tools/make_icon.py` draws the app icon.

## Credits

- Extensions come from [Yūzōnō's anime extensions](https://github.com/yuzono/anime-extensions). AniDeck installs them from the repo's index and runs them itself; none are included here.
- The extension API in `app/kotlin/eu/kanade/tachiyomi/` is adapted from [Aniyomi](https://github.com/aniyomiorg/aniyomi) under the Apache License 2.0, a copy of which is in that folder's `LICENSE` file.

## History

- **2026-11-12:** First version: the site on the top screen and the bottom-screen companion (Now Playing, Up Next, Schedule, Lists, Browse, Settings), AniList lists, new-episode notifications and a media session.
- **2026-11-12:** Controller and touch on both screens: highlight navigation, Select switches screens, and episode controls from either screen.
- **2026-11-13:** No more force close when the WebView renderer crashes; both screens reload instead.
- **2026-11-13:** Recently played and saved shows on the bottom screen, and a feed for CrossBar's bottom screen.
- **2026-11-13:** Reports web renderer crashes on either screen to Coronamon.
- **2026-11-14:** Episodes can be watched on the bottom screen: when CrossBar's Now Playing moves AniDeck there, the episode fills the screen as a centered 16:9 picture with no companion, and both come back as they were on the top screen. Seeks from other apps' media controls now land and show right away, even while AniDeck is in the background.
- **2026-11-16:** Downloads. The site's Download button under the video, or Download on the bottom screen's Now Playing, now saves the episode on the Thor (Movies/AniDeck/<show>) in the language you watch in, Sub or Dub, and in the quality set in Settings: 1080p, or 480p for smaller files. When the file host has 1080p ready but 480p is still waiting in its queue after 90 seconds, AniDeck takes the 1080p file and says so. A new Downloads tab on the bottom screen shows progress and lets you play, delete, retry or cancel. Downloaded episodes play without a connection in AniDeck's own player, with the file's subtitle tracks (choose one from the bottom screen), the usual controller buttons, and your place saved. CrossBar gets a Downloaded row that opens them straight away.
- **2026-11-21:** AniDeck 2.0 is a native app. Shows now come from Aniyomi extensions in the Yūzōnō repo (install them in the new Extensions tab) instead of the miruro.cx site: browse each source with its filters, search across your sources, and open show pages with the full episode list, all on the top screen. Episodes play in AniDeck's own player (Media3 ExoPlayer) with server, quality, subtitle, audio and speed choices, intros skipped where the source marks them, next and previous episode, and resume. Downloads save with their subtitles to Movies/AniDeck/<show> at the quality set in Settings (360p up to the best available), play with no connection, and Next at the last downloaded episode carries on streaming when you're online. Home, show pages and Downloads work offline. The bottom screen, the controller on both screens, AniList lists and progress, new-episode notifications and CrossBar's feed carry over, along with your history and saved shows.
- **2026-11-21:** Re:ANIME (reanime.cz) works alongside the other sources, and Extensions marks it and AniZone as Recommended. Downloads take a server's ready-made file when it has one, such as Re:ANIME's 1080p MKV with Japanese and English audio and all its subtitles. HLS downloads now include the audio in your language when a source sends audio separately; before, those saved without sound. AAC audio from MPEG-TS streams is now saved the way MP4 players expect, which could leave a download stuck on Loading. Downloaded episodes keep the source's intro and ending marks, so X and auto-skip work offline, and shows from sources that use AniList's cover art link to their exact AniList entry.
