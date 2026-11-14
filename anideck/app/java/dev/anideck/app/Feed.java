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
 * What CrossBar shows on its bottom screen while AniDeck is selected: recently played episodes, saved shows
 * and the AniList Watching list, read from the Thor so it works offline. call("feed") returns
 * {"v":1,"sections":[{title, style, items:[{title, sub, img, progress, at, open}]}]}, where "open" holds the
 * extras for AniDeck's launch intent. Covers are at content://dev.anideck.app.feed/cover/{AniList id}.
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
        for (int i = 0; i < recent.length(); i++) {
            JSONObject e = recent.optJSONObject(i);
            if (e != null) items.put(recentItem(shelf, e));
        }
        if (items.length() > 0) sections.put(App.obj("title", "Continue Watching", "style", "cards", "items", items));

        items = new JSONArray();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject e = saved.optJSONObject(i);
            if (e == null) continue;
            int id = e.optInt("id");
            items.put(App.obj("title", App.str(e, "title"), "sub", savedLine(e), "img", img(shelf, id, App.str(e, "cover")),
                    "at", e.optLong("savedAt"), "open", open(id, lastEp(recent, id), App.str(e, "title"))));
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
                    if (e != null) items.put(watchingItem(shelf, e));
                }
                if (items.length() > 0 && !lib.isNull("account")) {
                    sections.put(App.obj("title", "Watching on AniList", "style", "posters", "items", items));
                }
            } catch (JSONException ignored) {
            }
        }
        return App.obj("v", 1, "sections", sections);
    }

    private static JSONObject recentItem(Shelf shelf, JSONObject e) {
        int id = e.optInt("id"), ep = e.optInt("ep"), count = e.optInt("epCount");
        long t = e.optLong("t"), d = e.optLong("d");
        String show = App.str(e, "show");
        boolean done = d > 0 && t > d * 0.92;
        String sub;
        int open = ep;
        double progress = d > 0 ? Math.min(1, (double) t / d) : 0;
        if (done && (count == 0 || ep < count)) {
            open = ep + 1;
            progress = 0;
            sub = "Episode " + open + " is next";
        } else if (done) {
            sub = "Finished episode " + ep;
        } else {
            long left = Math.max(1, Math.round((d - t) / 60.0));
            sub = "Episode " + ep + (d > 0 ? " · " + left + " min left" : "");
        }
        JSONObject o = App.obj("title", show, "sub", sub, "img", img(shelf, id, App.str(e, "cover")),
                "progress", progress, "at", e.optLong("at"));
        if (id > 0) App.put(o, "open", open(id, open, show));
        else App.put(o, "open", App.obj(MainActivity.EXTRA_PATH, App.str(e, "path"), MainActivity.EXTRA_EP, open));
        return o;
    }

    private static JSONObject watchingItem(Shelf shelf, JSONObject e) {
        int id = e.optInt("id"), progress = e.optInt("progress"), episodes = e.optInt("episodes"), aired = e.optInt("aired");
        int fresh = e.optInt("newCount");
        String sub = fresh > 0 ? fresh + " new · " + progress + "/" + (episodes > 0 ? episodes : "?")
                : progress + "/" + (episodes > 0 ? episodes : "?") + " watched";
        boolean caughtUp = progress >= aired && (aired > 0 || e.has("nextAt"));
        return App.obj("title", App.str(e, "title"), "sub", sub, "img", img(shelf, id, App.str(e, "cover")),
                "progress", episodes > 0 ? Math.min(1, (double) progress / episodes) : 0,
                "open", open(id, caughtUp ? -1 : progress + 1, App.str(e, "title")));
    }

    private static String savedLine(JSONObject e) {
        long next = e.optLong("nextAt");
        long now = System.currentTimeMillis() / 1000;
        if (next > now) {
            long s = next - now, days = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60;
            return "Ep " + e.optInt("nextEp") + " in " + (days > 0 ? days + "d " + h + "h" : h > 0 ? h + "h " + m + "m" : m + "m");
        }
        String format = App.str(e, "format").replace('_', ' ');
        int episodes = e.optInt("episodes");
        String line = format.equals("TV") || format.isEmpty() ? "" : format;
        if (episodes > 0) line += (line.isEmpty() ? "" : " · ") + episodes + (episodes == 1 ? " episode" : " episodes");
        if (e.optInt("year") > 0) line += (line.isEmpty() ? "" : " · ") + e.optInt("year");
        return line;
    }

    private static int lastEp(JSONArray recent, int id) {
        for (int i = 0; i < recent.length(); i++) {
            JSONObject e = recent.optJSONObject(i);
            if (e != null && e.optInt("id") == id) return e.optInt("ep");
        }
        return 0;
    }

    private static String img(Shelf shelf, int id, String remote) {
        return id > 0 && shelf.cover(id).isFile() ? "cover/" + id : remote;
    }

    private static JSONObject open(int id, int ep, String title) {
        return App.obj(MainActivity.EXTRA_OPEN, id, MainActivity.EXTRA_EP, ep, MainActivity.EXTRA_TITLE, title);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        check();
        List<String> seg = uri.getPathSegments();
        if (!"r".equals(mode) || seg.size() != 2 || !"cover".equals(seg.get(0)) || !seg.get(1).matches("\\d{1,9}")) {
            throw new FileNotFoundException(uri.toString());
        }
        File f = Shelf.get(getContext()).cover(Integer.parseInt(seg.get(1)));
        if (!f.isFile()) throw new FileNotFoundException(uri.toString());
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
