package dev.crossbar.launcher;

import android.net.Uri;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;

/** Settings and play history, persisted as JSON in app-private storage. */
final class Store {
    static final File EXT_DIR = new File(Environment.getExternalStorageDirectory(), "CrossBar");

    private final File settingsFile;
    private final File historyFile;
    private JSONObject settings;
    private JSONObject history;
    private String pendingKey;
    private long pendingStart;

    Store(App app) {
        settingsFile = new File(app.getFilesDir(), "settings.json");
        historyFile = new File(app.getFilesDir(), "history.json");
        settings = Util.readJson(settingsFile);
        if (settings == null) settings = new JSONObject();
        history = Util.readJson(historyFile);
        if (history == null) history = new JSONObject();
        if (!settings.has("musicDuringGames") && settings.has("pauseMusicOnLaunch"))
            Util.put(settings, "musicDuringGames", !settings.optBoolean("pauseMusicOnLaunch", true));
        settings.remove("pauseMusicOnLaunch");
        JSONObject d = defaults();
        for (Iterator<String> it = d.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!settings.has(k)) Util.put(settings, k, d.opt(k));
        }
        mergeExternalConfig(false);
    }

    static JSONObject defaults() {
        return Util.obj(
                "bgMode", "wallpapers",
                "waveColor", "auto",
                "waveOverlay", true,
                "rotateSec", 15,
                "heroArt", true,
                "sounds", true,
                "soundVol", 0.6,
                "clock24", false,
                "showDate", true,
                "downloadArt", true,
                "onlineGameInfo", true,
                "musicDuringGames", false,
                "mediaLights", false,
                "musicAllFolders", false,
                "customBg", "",
                "romRoot", new File(Environment.getExternalStorageDirectory(), "Roms").getAbsolutePath(),
                "romTree", "",
                "userName", "Player",
                "steamApiKey", "",
                "steamId", "",
                "discordServers", "",
                "discordUserIds", "",
                "youtubeChannels", "",
                "players", new JSONObject(),
                "perfPreset", "smooth60",
                "devMode", false);
    }

    /** Settings that config.json on shared storage may not change, since any app with file access can write it. */
    private static boolean localOnly(String k) {
        return k.startsWith("_") || k.equals("devMode");
    }

    /** Merges /sdcard/CrossBar/config.json when it is new or changed. */
    synchronized boolean mergeExternalConfig(boolean force) {
        File f = new File(EXT_DIR, "config.json");
        if (!f.isFile()) return false;
        long mtime = f.lastModified();
        if (!force && settings.optLong("_configMtime") == mtime) return false;
        JSONObject c = Util.readJson(f);
        if (c == null) return false;
        for (Iterator<String> it = c.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!localOnly(k)) Util.put(settings, k, c.opt(k));
        }
        Util.put(settings, "_configMtime", mtime);
        save();
        return true;
    }

    synchronized JSONObject settings() {
        return Util.copy(settings);
    }

    synchronized void update(JSONObject patch) {
        for (Iterator<String> it = patch.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!k.startsWith("_")) Util.put(settings, k, patch.opt(k));
        }
        save();
    }

    synchronized void put(String k, Object v) {
        Util.put(settings, k, v);
        save();
    }

    synchronized String str(String k) {
        return settings.optString(k, "");
    }

    synchronized boolean bool(String k) {
        return settings.optBoolean(k);
    }

    synchronized String preferredPlayer(String platform) {
        JSONObject p = settings.optJSONObject("players");
        return p == null ? "" : p.optString(platform, "");
    }

    Uri romTree() {
        String s = str("romTree");
        return s.isEmpty() ? null : Uri.parse(s);
    }

    private void save() {
        Util.writeText(settingsFile, settings.toString());
    }

    synchronized void launched(String key) {
        long now = System.currentTimeMillis();
        record(key, now);
        pendingKey = key;
        pendingStart = now;
        Util.writeText(historyFile, history.toString());
    }

    /** Records a launch without timing it: apps on the bottom screen run while the menu stays up. */
    synchronized void opened(String key) {
        record(key, System.currentTimeMillis());
        Util.writeText(historyFile, history.toString());
    }

    private void record(String key, long now) {
        JSONObject h = history.optJSONObject(key);
        if (h == null) h = new JSONObject();
        Util.put(h, "last", now);
        Util.put(h, "count", h.optInt("count") + 1);
        Util.put(history, key, h);
    }

    synchronized void returned() {
        if (pendingKey == null) return;
        long dt = System.currentTimeMillis() - pendingStart;
        JSONObject h = history.optJSONObject(pendingKey);
        if (h != null && dt > 5000 && dt < 12L * 3600_000L) {
            Util.put(h, "time", h.optLong("time") + dt);
            Util.writeText(historyFile, history.toString());
        }
        pendingKey = null;
    }

    synchronized JSONObject history() {
        return Util.copy(history);
    }
}
