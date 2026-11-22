package dev.anideck.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Set;

/**
 * What CrossBar shows on its bottom screen while AniDeck is selected: shows to continue, downloaded
 * episodes, saved shows and the AniList Watching list, read from the Thor so it works offline. call("feed")
 * returns {"v":1,"sections":[{title, style, items:[{title, sub, img, progress, at, open}]}]}, where "open" holds
 * the extras for AniDeck's launch intent. Covers are at content://dev.anideck.app.feed/cover/{name}.
 * Only CrossBar (and adb, for testing) may read it.
 */
public final class Feed extends ContentProvider {
    private static final Set<String> CALLERS = Set.of("dev.crossbar.launcher");
    private static final int SHELL_UID = 2000;

    @Override
    public boolean onCreate() {
        return true;
    }

    private void check() {
        int uid = Binder.getCallingUid();
        if (uid == SHELL_UID || uid == Process.myUid()) return;
        String[] pkgs = getContext().getPackageManager().getPackagesForUid(uid);
        if (pkgs != null) for (String p : pkgs) if (CALLERS.contains(p)) return;
        throw new SecurityException("AniDeck's feed is only for CrossBar");
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        check();
        if (!"feed".equals(method)) return null;
        Bundle b = new Bundle();
        b.putString("json", feed().toString());
        return b;
    }

    private JSONObject feed() {
        Shelf shelf = Shelf.get(getContext());
        JSONObject j = shelf.json();
        JSONArray recent = j.optJSONArray("recent"), saved = j.optJSONArray("saved"), sections = new JSONArray();

        JSONArray items = new JSONArray();
        for (int i = 0; i < recent.length() && items.length() < 20; i++) {
            JSONObject e = recent.optJSONObject(i);
            if (e != null) items.put(recentItem(e));
        }
        if (items.length() > 0) sections.put(App.obj("title", "Continue Watching", "style", "cards", "items", items));

        items = new JSONArray();
        JSONArray downloads = Downloads.get(getContext()).finished();
        for (int i = 0; i < downloads.length() && items.length() < 20; i++) {
            JSONObject e = downloads.optJSONObject(i);
            if (e != null) items.put(downloadItem(shelf, e));
        }
        if (items.length() > 0) sections.put(App.obj("title", "Downloaded", "style", "cards", "items", items));

        items = new JSONArray();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject e = saved.optJSONObject(i);
            if (e == null) continue;
            String source = App.str(e, "sourceName");
            items.put(App.obj("title", App.str(e, "title"), "sub", source.isEmpty() ? "Saved show" : source, "img", img(e),
                    "at", e.optLong("savedAt"), "open", App.obj(MainActivity.EXTRA_SHOW, App.str(e, "key"))));
        }
        if (items.length() > 0) sections.put(App.obj("title", "Saved Shows", "style", "posters", "items", items));

        // The library is only there once the app has started (the provider can run first).
        App app = App.I;
        if (app != null && app.library != null) {
            try {
                JSONObject lib = new JSONObject(app.library.json());
                JSONArray cur = lib.optJSONObject("lists") == null ? null : lib.optJSONObject("lists").optJSONArray("CURRENT");
                items = new JSONArray();
                for (int i = 0; cur != null && i < cur.length() && items.length() < 20; i++) {
                    JSONObject e = cur.optJSONObject(i);
                    if (e != null) items.put(watchingItem(e));
                }
                if (items.length() > 0 && !lib.isNull("account")) {
                    sections.put(App.obj("title", "Watching on AniList", "style", "posters", "items", items));
                }
            } catch (JSONException ignored) {
            }
        }
        return App.obj("v", 1, "sections", sections);
    }

    /** Resumes the episode, or plays the next one once it was watched to the end. */
    private static JSONObject recentItem(JSONObject e) {
        JSONObject last = e.optJSONObject("last");
        double num = last == null ? -1 : last.optDouble("num", -1);
        long t = last == null ? 0 : last.optLong("t"), d = last == null ? 0 : last.optLong("d");
        int count = e.optInt("count");
        boolean done = d > 0 && t >= d * Shelf.DONE;
        String ep = num >= 0 ? "Episode " + VideoPlayer.numLabel(num) : App.str(last, "name");
        String sub;
        double progress = d > 0 ? Math.min(1, (double) t / d) : 0;
        int index = last == null ? -1 : last.optInt("i", -1);
        if (done && (count == 0 || index < 0 || index + 1 < count)) {
            progress = 0;
            sub = num >= 0 ? "Episode " + VideoPlayer.numLabel(num + 1) + " is next" : "Next episode";
        } else if (done) {
            sub = "Finished " + ep.toLowerCase();
        } else {
            long left = Math.max(1, Math.round((d - t) / 60.0));
            sub = ep + (d > 0 ? " · " + left + " min left" : "");
        }
        return App.obj("title", App.str(e, "title"), "sub", sub, "img", img(e), "progress", progress, "at", e.optLong("at"),
                "open", App.obj(MainActivity.EXTRA_SHOW, App.str(e, "key"), MainActivity.EXTRA_RESUME, true));
    }

    /** Opens straight into the player, so it works without a connection. */
    private static JSONObject downloadItem(Shelf shelf, JSONObject e) {
        long t = e.optLong("t"), d = e.optLong("d");
        String sub = "Episode " + VideoPlayer.numLabel(e.optDouble("ep"));
        if (d > 0 && t >= d * Shelf.DONE) sub += " · watched";
        else if (d > 0 && t > 5) sub += " · " + Math.max(1, Math.round((d - t) / 60.0)) + " min left";
        else if (!App.str(e, "quality").isEmpty()) sub += " · " + App.str(e, "quality");
        String key = App.str(e, "key");
        if (key.isEmpty() && e.optInt("a") > 0) key = Shelf.aniListKey(e.optInt("a"));
        String img = !key.isEmpty() && shelf.cover(key).isFile() ? "cover/" + Shelf.coverName(key) : "";
        return App.obj("title", App.str(e, "show"), "sub", sub, "img", img,
                "progress", d > 0 ? Math.min(1, (double) t / d) : 0, "at", Math.max(e.optLong("at"), e.optLong("finished")),
                "open", App.obj(MainActivity.EXTRA_DOWNLOAD, App.str(e, "id")));
    }

    private static JSONObject watchingItem(JSONObject e) {
        int id = e.optInt("id"), progress = e.optInt("progress"), episodes = e.optInt("episodes"), aired = e.optInt("aired");
        int fresh = e.optInt("newCount");
        String sub = fresh > 0 ? fresh + " new · " + progress + "/" + (episodes > 0 ? episodes : "?")
                : progress + "/" + (episodes > 0 ? episodes : "?") + " watched";
        boolean caughtUp = progress >= aired && (aired > 0 || e.has("nextAt"));
        return App.obj("title", App.str(e, "title"), "sub", sub, "img", App.str(e, "cover"),
                "progress", episodes > 0 ? Math.min(1, (double) progress / episodes) : 0,
                "open", App.obj(MainActivity.EXTRA_OPEN, id, MainActivity.EXTRA_EP, caughtUp ? 0 : progress + 1,
                        MainActivity.EXTRA_TITLE, App.str(e, "romaji").isEmpty() ? App.str(e, "title") : App.str(e, "romaji")));
    }

    /** The cover kept on the Thor, else the source's (CrossBar loads https addresses itself). */
    private static String img(JSONObject e) {
        String name = App.str(e, "cover"), remote = App.str(e, "coverUrl");
        if (!name.isEmpty()) return "cover/" + name;
        return remote.startsWith("https://") ? remote : "";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        check();
        List<String> seg = uri.getPathSegments();
        File f = "r".equals(mode) && seg.size() == 2 && "cover".equals(seg.get(0)) ? Shelf.get(getContext()).coverFile(seg.get(1)) : null;
        if (f == null || !f.isFile()) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
