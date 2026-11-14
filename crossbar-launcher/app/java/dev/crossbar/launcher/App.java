package dev.crossbar.launcher;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class App extends Application {
    static final String TAG = "CrossBar";
    static App I;

    final ExecutorService io = Executors.newFixedThreadPool(4);
    final Handler main = new Handler(Looper.getMainLooper());
    Store store;
    AppsRepo apps;
    GameScanner games;
    MediaRepo media;
    Social social;
    GameInfo gameInfo;
    MediaHistory mediaHistory;
    Feeds feeds;
    LocalServer server;
    Lights lights;

    volatile MainActivity top;
    volatile BottomActivity bottom;
    /** Whether the top-screen menu is visible, as opposed to covered by a game or app. */
    volatile boolean topVisible;
    /** Whether the bottom display may hold the system-wide input focus (it started, or was touched). */
    volatile boolean bottomFocus;
    /** The latest view model the top screen sent, for a bottom screen that starts later. */
    volatile String bottomState = "";
    /** What CrossBar last opened on the top screen, while the menu is still covered: a game or emulator, or another app. */
    volatile boolean gameTop, appTop;
    /** A game or emulator CrossBar opened on the bottom screen, while the companion is still covered. */
    volatile boolean gameBottom;
    /** The top-screen menu is paused with the screen on: something covers it. */
    volatile boolean topAway;

    boolean gameRunning() {
        return gameTop || gameBottom;
    }

    /**
     * Whether CrossBar music plays without audio focus, so that a game asking for focus doesn't fade it
     * out. Besides games CrossBar opened, that's whenever the menu is covered by something it didn't open
     * (a game picked from Recents, say); other media still stops the music then, through its media session.
     */
    boolean musicMixes() {
        return store.bool("musicDuringGames") && (gameRunning() || (topAway && !appTop));
    }

    /** Call just before opening a game or emulator, or another app, on the top or the bottom screen. */
    void opening(boolean onTop, boolean game) {
        if (onTop) {
            gameTop = game;
            appTop = !game;
        } else if (game) {
            gameBottom = true;
        }
        if (game && !store.bool("musicDuringGames") && MusicService.playing()) MusicService.command(this, "pause", null);
        MusicService.mixChanged();
        lights.refresh();
    }

    /** The menu on that screen is back in front, or what it was opening failed to start. */
    void back(boolean onTop) {
        if (onTop) {
            gameTop = appTop = topAway = false;
        } else {
            gameBottom = false;
        }
        MusicService.mixChanged();
        lights.refresh();
    }

    void topPaused() {
        if (!getSystemService(PowerManager.class).isInteractive()) return;
        topAway = true;
        MusicService.mixChanged();
        lights.refresh();
    }

    void settingsChanged() {
        MusicService.mixChanged();
        lights.refresh();
        main.post(() -> {
            MainActivity m = top;
            if (m != null) m.applyPerf();
            BottomActivity b = bottom;
            if (b != null) b.applyPerf();
        });
    }

    /** Frame rate of both menu screens, from Settings › Performance (smooth60, battery60, smooth30, battery30). */
    int menuFps() {
        return store.str("perfPreset").endsWith("30") ? 30 : 60;
    }

    void toBottom(String json) {
        bottomState = json;
        BottomActivity b = bottom;
        if (b != null) b.push(json);
    }

    void toTop(String json) {
        MainActivity m = top;
        if (m != null) m.js("window.CB&&CB.fromBottom(" + json + ")");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        I = this;
        store = new Store(this);
        apps = new AppsRepo(this);
        games = new GameScanner(this);
        media = new MediaRepo(this);
        social = new Social(this);
        gameInfo = new GameInfo(this);
        mediaHistory = new MediaHistory(this);
        feeds = new Feeds(this);
        lights = new Lights(this);
        server = new LocalServer(this);
        try {
            server.start();
        } catch (Exception e) {
            Log.e(TAG, "local server failed to start", e);
        }
        io.execute(() -> games.scan(false));
    }
}
