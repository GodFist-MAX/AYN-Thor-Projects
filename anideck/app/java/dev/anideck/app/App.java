package dev.anideck.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class App extends Application {
    static final String TAG = "AniDeck";
    static final String CHANNEL_EPISODES = "episodes";
    static App I;

    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newFixedThreadPool(2);
    final Pad pad = new Pad();
    SharedPreferences prefs;
    Net net;
    Library library;
    volatile MainActivity top;
    volatile BottomActivity bottom;
    volatile boolean topVisible, bottomFocus, pageCrashed;
    /** The top screen's page and player state, as last reported by inject.js. */
    volatile String state = "{}";

    @Override public void onCreate() {
        super.onCreate();
        I = this;
        prefs = getSharedPreferences("anideck", MODE_PRIVATE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_EPISODES, "New episodes", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("A new episode of a show on your Watching list is out");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        net = new Net(this);
        library = new Library(this);
        SyncJob.schedule(this);
    }

    /** On/off settings; all of them start on. */
    static final String[] SWITCHES = {"autoTheater", "autoServer", "autoTrack", "notify"};

    boolean setting(String key) { return prefs.getBoolean(key, true); }

    JSONObject settings() {
        JSONObject j = new JSONObject();
        put(j, "site", Site.base());
        for (String k : SWITCHES) put(j, k, setting(k));
        return j;
    }

    /** The settings inject.js acts on. */
    JSONObject pagePrefs() {
        return obj("autoTheater", setting("autoTheater"), "autoServer", setting("autoServer"));
    }

    /** Calls a function of the bottom screen's page (window.deck) with a JSON argument. */
    void toBottom(String fn, String json) {
        BottomActivity b = bottom;
        if (b != null) b.js("window.deck&&deck." + fn + "(" + json + ")");
    }

    String asset(String path) {
        try (InputStream in = getAssets().open(path)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static JSONObject obj(Object... keysAndValues) {
        JSONObject j = new JSONObject();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) put(j, (String) keysAndValues[i], keysAndValues[i + 1]);
        return j;
    }

    static void put(JSONObject j, String key, Object value) {
        try {
            j.put(key, value);
        } catch (JSONException ignored) {
        }
    }

    /** A string field, or "" when it is missing or JSON null (optString would give "null"). */
    static String str(JSONObject j, String key) {
        if (j == null || j.isNull(key)) return "";
        return j.optString(key, "");
    }
}
