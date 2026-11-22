package dev.anideck.app;

import android.app.DownloadManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Episodes saved on the Thor, in Movies/AniDeck/(show), with their subtitles in the app's files. The list
 * (downloads.json) feeds both screens, the {@link Player} and CrossBar ({@link Feed}); {@link DownloadService}
 * works through the queue with {@link Downloader}. Records from the site version (saved by Android's download
 * manager) stay playable.
 */
final class Downloads {
    static final String FOLDER = "AniDeck";
    private static Downloads instance;

    private final Context app;
    private final File file;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Main thread only.
    private boolean pushQueued;
    private long lastPush;
    private final Runnable push = () -> {
        pushQueued = false;
        lastPush = SystemClock.uptimeMillis();
        App a = App.I;
        if (a == null) return;
        String j = json().toString();
        if (a.bottom != null) a.toBottom("onDownloads", j);
        a.toTop("downloads", j);
    };
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Set<String> canceled = ConcurrentHashMap.newKeySet();
    private JSONArray items = new JSONArray(); // guarded by this, newest first

    /** The feed can be read before App.onCreate has run, so this doesn't depend on App.I. */
    static synchronized Downloads get(Context c) {
        if (instance == null) instance = new Downloads(c.getApplicationContext());
        return instance;
    }

    private Downloads(Context c) {
        app = c;
        file = new File(c.getFilesDir(), "downloads.json");
        try {
            JSONArray a = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).optJSONArray("items");
            if (a != null) items = a;
        } catch (IOException | JSONException ignored) {
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r == null) continue;
            String st = App.str(r, "state");
            boolean old = r.optInt("v") < 2;
            // The site version's file host pages and download manager jobs don't carry over.
            if (old && !"done".equals(st) && !"failed".equals(st)) {
                App.put(r, "state", "failed");
                App.put(r, "error", "This download was started by an older AniDeck. Download the episode again");
            } else if (!old && ("preparing".equals(st) || "downloading".equals(st))) {
                // AniDeck stopped mid-download: it starts over when the queue runs again.
                App.put(r, "state", "queued");
                App.put(r, "note", "");
            }
        }
    }

    // ---- the list ----

    /** Every download, each with its show's cover on the Thor (a Shelf cover name) when there is one. */
    synchronized JSONObject json() {
        long used = 0;
        JSONArray out = copy(items);
        Map<String, String> covers = new HashMap<>();
        Shelf shelf = Shelf.get(app);
        for (int i = 0; i < out.length(); i++) {
            JSONObject r = out.optJSONObject(i);
            if (r == null) continue;
            if ("done".equals(App.str(r, "state"))) used += r.optLong("size");
            String key = App.str(r, "key");
            if (key.isEmpty() && r.optInt("a") > 0) key = Shelf.aniListKey(r.optInt("a"));
            if (key.isEmpty()) continue;
            String cover = covers.computeIfAbsent(key, k -> shelf.cover(k).isFile() ? Shelf.coverName(k) : "");
            if (!cover.isEmpty()) App.put(r, "cover", cover);
        }
        return App.obj("items", out, "used", used, "free", Environment.getExternalStorageDirectory().getUsableSpace(),
                "folder", Environment.DIRECTORY_MOVIES + "/" + FOLDER);
    }

    /** Downloaded episodes, newest first (for the feed). */
    synchronized JSONArray finished() {
        JSONArray out = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r != null && "done".equals(App.str(r, "state"))) out.put(copy(r));
        }
        return out;
    }

    synchronized JSONObject get(String id) {
        JSONObject r = item(id);
        return r == null ? null : copy(r);
    }

    synchronized boolean hasShow(String key) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r != null && key.equals(App.str(r, "key"))) return true;
        }
        return false;
    }

    /** The download of one episode, if there is one. */
    synchronized JSONObject find(String key, String epUrl) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r != null && key.equals(App.str(r, "key")) && epUrl.equals(App.str(r.optJSONObject("episode"), "url"))) return copy(r);
        }
        return null;
    }

    /** A show's downloads by episode URL, for its page: {url: {id, state, pct}}. */
    synchronized JSONObject forShow(String key) {
        JSONObject out = new JSONObject();
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r == null || !key.equals(App.str(r, "key"))) continue;
            long size = r.optLong("size"), got = r.optLong("got");
            App.put(out, App.str(r.optJSONObject("episode"), "url"), App.obj("id", App.str(r, "id"), "state", App.str(r, "state"),
                    "pct", size > 0 ? Math.min(100, Math.round(got * 100.0 / size)) : 0));
        }
        return out;
    }

    /** The next (dir 1) or previous (-1) downloaded episode of the same show. */
    synchronized JSONObject neighbor(JSONObject cur, int dir) {
        JSONObject best = null;
        double ep = cur.optDouble("ep", 0);
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r == null || !"done".equals(App.str(r, "state")) || !sameShow(r, cur)) continue;
            double e = r.optDouble("ep", 0);
            if (dir > 0 ? e > ep && (best == null || e < best.optDouble("ep", 0)) : e < ep && (best == null || e > best.optDouble("ep", 0))) best = r;
        }
        return best == null ? null : copy(best);
    }

    /** The downloaded episodes of a record's show, in episode order. */
    synchronized List<JSONObject> showDownloads(JSONObject cur) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r != null && "done".equals(App.str(r, "state")) && sameShow(r, cur)) out.add(copy(r));
        }
        out.sort((x, y) -> Double.compare(x.optDouble("ep", 0), y.optDouble("ep", 0)));
        return out;
    }

    /** Where the player reads a downloaded episode. */
    Uri uri(JSONObject r) {
        String u = App.str(r, "uri");
        if (!u.isEmpty()) return Uri.parse(u);
        long d = r.optLong("dm", -1);
        Uri out = null;
        try {
            if (d >= 0) out = app.getSystemService(DownloadManager.class).getUriForDownloadedFile(d);
        } catch (RuntimeException ignored) {
        }
        return out != null ? out : Uri.fromFile(new File(App.str(r, "file")));
    }

    /** Where an episode was left off, for the player, both screens and CrossBar. */
    void position(String id, double t, double d) {
        synchronized (this) {
            JSONObject r = item(id);
            if (r == null) return;
            App.put(r, "t", Math.round(t));
            App.put(r, "d", Math.round(d));
            App.put(r, "at", System.currentTimeMillis());
        }
        changed(false);
    }

    // ---- queueing ----

    /**
     * Queues episodes of a show; ones already downloaded or queued are skipped. pick: the server and
     * quality playing now ({hoster, video} names), or null for the source's preferred video.
     */
    int add(String source, String sourceName, JSONObject anime, JSONArray episodes, int anilistId, JSONObject pick) {
        String key = Shelf.key(source, App.str(anime, "url"));
        int added = 0;
        synchronized (this) {
            JSONArray next = new JSONArray();
            for (int i = 0; i < episodes.length(); i++) {
                JSONObject ep = episodes.optJSONObject(i);
                if (ep == null) continue;
                JSONObject old = itemFor(key, App.str(ep, "url"));
                if (old != null && !"failed".equals(App.str(old, "state"))) continue;
                if (old != null) drop(App.str(old, "id"));
                String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(i, 36);
                double num = ep.optDouble("episode_number", -1);
                JSONObject r = App.obj("v", 2, "id", id, "key", key, "source", source, "sourceName", sourceName,
                        "anime", App.obj("url", App.str(anime, "url"), "title", App.str(anime, "title"), "thumbnail_url", App.str(anime, "thumbnail_url")),
                        "episode", ep, "show", App.str(anime, "title"), "ep", num >= 0 ? num : i + 1, "epTitle", App.str(ep, "name"),
                        "a", anilistId, "state", "queued", "added", System.currentTimeMillis());
                if (pick != null) App.put(r, "pick", pick);
                next.put(r);
                added++;
            }
            for (int i = 0; i < items.length(); i++) next.put(items.opt(i));
            items = next;
        }
        if (added > 0) {
            Shelf.get(app).remember(source, sourceName, anime);
            changed(true);
            run();
        }
        return added;
    }

    /** Retry on a failed download (or one that stopped): back in the queue. */
    void retry(String id) {
        synchronized (this) {
            JSONObject r = item(id);
            if (r == null || r.optInt("v") < 2 || "done".equals(App.str(r, "state"))) return;
            App.put(r, "state", "queued");
            App.put(r, "error", "");
            App.put(r, "note", "");
            App.put(r, "got", 0);
            App.put(r, "size", 0);
        }
        changed(true);
        run();
    }

    /** Cancel or Delete: stops the episode's download if it's going, deletes its files and forgets it. */
    void delete(String id) {
        JSONObject r;
        synchronized (this) {
            r = item(id);
            if (r == null) return;
            drop(id);
        }
        canceled.add(id);
        app.getSystemService(NotificationManager.class).cancel("download", id.hashCode());
        App.I.io.execute(() -> removeFiles(r));
        changed(true);
    }

    private void removeFiles(JSONObject r) {
        String uri = App.str(r, "uri");
        try {
            if (!uri.isEmpty()) {
                app.getContentResolver().delete(Uri.parse(uri), null, null);
            } else {
                long d = r.optLong("dm", -1);
                if (d >= 0) app.getSystemService(DownloadManager.class).remove(d);
                String path = App.str(r, "file");
                File f = path.isEmpty() ? null : new File(path);
                if (f != null && f.exists() && !f.delete()) Log.w(App.TAG, "could not delete " + f.getName());
            }
            dropEmptyFolder(App.str(r, "file"));
        } catch (RuntimeException e) {
            Log.w(App.TAG, "could not delete a download: " + e);
        }
        deleteTree(subsDir(App.str(r, "id")));
        deleteTree(workDir(App.str(r, "id")));
    }

    /** Starts the download service if anything is waiting. */
    void run() {
        if (next() == null) return;
        try {
            app.startForegroundService(new Intent(app, DownloadService.class));
        } catch (RuntimeException e) {
            Log.w(App.TAG, "could not start downloading: " + e);
        }
    }

    // ---- for the download service ----

    /** The oldest queued download. */
    synchronized JSONObject next() {
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject r = items.optJSONObject(i);
            if (r != null && "queued".equals(App.str(r, "state"))) return copy(r);
        }
        return null;
    }

    synchronized int queued() {
        int n = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            String st = r == null ? "" : App.str(r, "state");
            if ("queued".equals(st) || "preparing".equals(st) || "downloading".equals(st)) n++;
        }
        return n;
    }

    boolean isCanceled(String id) {
        return canceled.contains(id);
    }

    /** Fields the downloader sets as it goes; progress-only updates aren't written to disk each time. */
    void update(String id, boolean persist, Object... keysAndValues) {
        synchronized (this) {
            JSONObject r = item(id);
            if (r == null) return;
            for (int i = 0; i + 1 < keysAndValues.length; i += 2) App.put(r, (String) keysAndValues[i], keysAndValues[i + 1]);
        }
        changed(persist);
    }

    File subsDir(String id) {
        return new File(app.getFilesDir(), "subs/" + id.replaceAll("[^\\w-]", ""));
    }

    File workDir(String id) {
        return new File(app.getCacheDir(), "dl/" + id.replaceAll("[^\\w-]", ""));
    }

    // ---- helpers; the callers hold the lock ----

    private JSONObject item(String id) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r != null && App.str(r, "id").equals(id)) return r;
        }
        return null;
    }

    private JSONObject itemFor(String key, String epUrl) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r != null && key.equals(App.str(r, "key")) && epUrl.equals(App.str(r.optJSONObject("episode"), "url"))) return r;
        }
        return null;
    }

    private void drop(String id) {
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject r = items.optJSONObject(i);
            if (r != null && App.str(r, "id").equals(id)) items.remove(i);
        }
    }

    static boolean sameShow(JSONObject x, JSONObject y) {
        String k = App.str(x, "key");
        if (!k.isEmpty() || !App.str(y, "key").isEmpty()) return k.equals(App.str(y, "key"));
        int a = x.optInt("a");
        return a > 0 ? a == y.optInt("a") : App.str(x, "show").equals(App.str(y, "show"));
    }

    // ---- saving ----

    /** Progress changes many times a second: the screens get it at most twice a second, other changes at once. */
    private void changed(boolean persist) {
        main.post(() -> {
            long wait = persist ? 0 : Math.max(0, lastPush + 500 - SystemClock.uptimeMillis());
            if (pushQueued && wait > 0) return;
            main.removeCallbacks(push);
            pushQueued = true;
            main.postDelayed(push, wait);
        });
        if (!persist) return;
        String text;
        synchronized (this) {
            text = App.obj("items", items).toString();
        }
        writer.execute(() -> {
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(file)) tmp.delete();
        });
    }

    /** "Episode 01 - The Journey's End" */
    static String fileName(JSONObject r) {
        double ep = r.optDouble("ep", 0);
        String num = ep == Math.floor(ep) ? String.format(Locale.ROOT, "%02d", (long) ep) : String.valueOf(ep);
        String title = VideoPlayer.cleanTitle(App.str(r, "epTitle"));
        String n = "Episode " + num;
        if (!title.isEmpty() && !title.matches("(?i).*episode\\s*[\\d.]+\\s*") && !title.matches("[\\d.\\s]+")) n += " - " + title;
        return safe(n);
    }

    /** A file or folder name that's safe on any storage. */
    static String safe(String s) {
        String out = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim().replaceAll("^\\.+|\\.+$", "");
        if (out.length() > 80) out = out.substring(0, 80).trim();
        return out.isEmpty() ? "Episode" : out;
    }

    /** A show's folder in Movies/AniDeck, once its last episode is gone (a folder with files in it won't delete). */
    private static void dropEmptyFolder(String file) {
        File dir = file.isEmpty() ? null : new File(file).getParentFile();
        if (dir != null && dir.getParentFile() != null && "AniDeck".equals(dir.getParentFile().getName())) dir.delete();
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static JSONArray copy(JSONArray a) {
        try {
            return new JSONArray(a.toString());
        } catch (JSONException e) {
            return new JSONArray();
        }
    }
}
