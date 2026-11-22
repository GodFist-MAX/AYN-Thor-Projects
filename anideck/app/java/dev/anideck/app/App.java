package dev.anideck.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import dev.anideck.app.ext.Extensions;
import dev.anideck.app.ext.Host;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class App extends Application {
    static final String TAG = "AniDeck";
    static final String CHANNEL_EPISODES = "episodes";
    static final String CHANNEL_DOWNLOADS = "downloads";
    static final String CHANNEL_PROGRESS = "progress";
    static App I;

    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newFixedThreadPool(2);
    /** Source calls from the pages (catalogs, episodes, servers); each can wait on the network for a while. */
    final ExecutorService exec = new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    final Pad pad = new Pad();
    SharedPreferences prefs;
    Net net;
    Library library;
    volatile MainActivity top;
    volatile BottomActivity bottom;
    volatile boolean topVisible, bottomFocus, pageCrashed;
    /** The player's state and episode list, as last sent to the bottom screen. */
    volatile String state = "{}", episodes = "[]";

    /** On/off settings and their defaults. */
    static final Map<String, Boolean> SWITCHES = Map.of(
            "autoNext", true, "autoSkip", false, "autoServer", true, "autoTrack", true, "notify", true, "showNsfw", false);
    /** Settings with a value, and their defaults: download quality (max height), subtitle and audio languages, source languages. */
    static final Map<String, String> CHOICES = Map.of("dlQuality", "1080", "subLang", "en", "audioLang", "ja", "langs", "en");

    @Override public void onCreate() {
        super.onCreate();
        I = this;
        prefs = getSharedPreferences("anideck", MODE_PRIVATE);
        ((ThreadPoolExecutor) exec).allowCoreThreadTimeOut(true);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_EPISODES, "New episodes", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("A new episode of a show on your Watching list is out");
        nm.createNotificationChannel(ch);
        ch = new NotificationChannel(CHANNEL_DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("An episode finished downloading");
        nm.createNotificationChannel(ch);
        ch = new NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Episodes downloading now");
        nm.createNotificationChannel(ch);
        net = new Net(this);
        library = new Library(this);
        PackageInfo pi = packageInfo();
        Host.init(this, pi == null ? 0 : (int) pi.getLongVersionCode(), pi == null ? "" : pi.versionName);
        Extensions.setOnChanged(() -> {
            toTop("sources", "{}");
            toBottom("onSources", "{}");
        });
        Extensions.loadAllAsync();
        SyncJob.schedule(this);
    }

    private PackageInfo packageInfo() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    String versionName() {
        PackageInfo pi = packageInfo();
        return pi == null ? "" : pi.versionName;
    }

    boolean setting(String key) {
        Boolean d = SWITCHES.get(key);
        return prefs.getBoolean(key, d != null && d);
    }

    String choice(String key) {
        String d = CHOICES.get(key);
        return prefs.getString(key, d == null ? "" : d);
    }

    JSONObject settings() {
        JSONObject j = new JSONObject();
        for (String k : SWITCHES.keySet()) put(j, k, setting(k));
        for (String k : CHOICES.keySet()) put(j, k, choice(k));
        return j;
    }

    /** A setting from either screen; unknown keys and wrong types are ignored. */
    void set(String key, Object value) {
        if (SWITCHES.containsKey(key) && value instanceof Boolean) prefs.edit().putBoolean(key, (Boolean) value).apply();
        else if (CHOICES.containsKey(key) && value instanceof String && ((String) value).length() < 200) prefs.edit().putString(key, (String) value).apply();
        else return;
        String j = settings().toString();
        toBottom("onSettings", j);
        toTop("settings", j);
    }

    /** Calls a function of the bottom screen's page (window.deck) with a JSON argument. */
    void toBottom(String fn, String json) {
        BottomActivity b = bottom;
        if (b != null) b.js("window.deck&&deck." + fn + "(" + json + ")");
    }

    /** An event for the top screen's page (window.app.on). */
    void toTop(String event, String json) {
        MainActivity m = top;
        if (m != null) m.toTop(event, json);
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
