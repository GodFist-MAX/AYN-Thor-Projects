package dev.anideck.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import dev.anideck.app.ext.Images;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shows AniDeck knows on this Thor: what was played (with each episode's position), saved shows and
 * their AniList links, with covers kept here so they show offline on both screens and, through
 * {@link Feed}, in CrossBar. A show's key is its source id and URL; shows carried over from the site
 * version have only an AniList id (key "anilist|id") until they're opened and found on a source.
 */
final class Shelf {
    private static final int MAX_SHOWS = 400, MAX_RECENT = 30;
    private static final long WRITE_EVERY_MS = 15_000;
    /** A finished episode: the player marks it at 85%, the lists and CrossBar treat it as done from here. */
    static final double DONE = 0.85;
    private static Shelf instance;

    private final Context app;
    private final File file, covers;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> fetching = ConcurrentHashMap.newKeySet();
    private JSONObject shows = new JSONObject(); // guarded by this: key -> show
    private long lastWrite;
    private boolean writeQueued;

    /** The feed can be read before App.onCreate has run, so this doesn't depend on App.I. */
    static synchronized Shelf get(Context c) {
        if (instance == null) instance = new Shelf(c.getApplicationContext());
        return instance;
    }

    private Shelf(Context c) {
        app = c;
        file = new File(c.getFilesDir(), "shows.json");
        covers = new File(c.getFilesDir(), "covers");
        try {
            JSONObject j = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONObject s = j.optJSONObject("shows");
            if (s != null) shows = s;
        } catch (IOException | JSONException e) {
            migrate();
        }
    }

    static String key(String source, String url) {
        return source + "|" + url;
    }

    static String aniListKey(int id) {
        return "anilist|" + id;
    }

    /** The name of a show's cover file, also its address on the bottom screen and in CrossBar's feed. */
    static String coverName(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    File cover(String key) {
        return coverFile(coverName(key));
    }

    /** null unless name is a cover name (so it can't leave the folder). */
    File coverFile(String name) {
        return name != null && name.matches("[0-9a-f]{40}") ? new File(covers, name + ".jpg") : null;
    }

    // ---- reading ----

    synchronized JSONObject show(String key) {
        JSONObject s = shows.optJSONObject(key);
        return s == null ? null : copy(s);
    }

    /** Recently played (newest first) and saved shows, without their episode lists, for both screens and the feed. */
    synchronized JSONObject json() {
        List<JSONObject> recent = new ArrayList<>(), saved = new ArrayList<>();
        for (Iterator<String> it = shows.keys(); it.hasNext(); ) {
            JSONObject s = shows.optJSONObject(it.next());
            if (s == null) continue;
            if (s.optLong("at") > 0 && s.optJSONObject("last") != null) recent.add(s);
            if (s.optBoolean("saved")) saved.add(s);
        }
        recent.sort((x, y) -> Long.compare(y.optLong("at"), x.optLong("at")));
        saved.sort((x, y) -> Long.compare(y.optLong("savedAt"), x.optLong("savedAt")));
        JSONArray r = new JSONArray(), sv = new JSONArray();
        for (int i = 0; i < recent.size() && i < MAX_RECENT; i++) r.put(brief(recent.get(i)));
        for (JSONObject s : saved) sv.put(brief(s));
        return App.obj("recent", r, "saved", sv);
    }

    private JSONObject brief(JSONObject s) {
        String key = App.str(s, "key");
        JSONObject o = App.obj("key", key, "source", App.str(s, "source"), "url", App.str(s, "url"), "title", App.str(s, "title"),
                "a", s.optInt("a"), "saved", s.optBoolean("saved"), "at", s.optLong("at"), "savedAt", s.optLong("savedAt"),
                "count", s.optInt("count"), "coverUrl", App.str(s, "cover"), "sourceName", App.str(s, "sourceName"));
        if (cover(key).isFile()) App.put(o, "cover", coverName(key));
        JSONObject last = s.optJSONObject("last");
        if (last != null) App.put(o, "last", copy(last));
        App.put(o, "next", next(s));
        return o;
    }

    /** What CrossBar and Continue Watching open: the last episode, or the one after it once it's finished. */
    private static JSONObject next(JSONObject s) {
        JSONObject last = s.optJSONObject("last");
        if (last == null) return null;
        double t = last.optDouble("t", 0), d = last.optDouble("d", 0);
        boolean done = d > 0 && t >= d * DONE;
        return App.obj("resume", !done, "num", last.optDouble("num", -1), "i", last.optInt("i", -1));
    }

    /** The show linked to an AniList id, most recently played first. */
    synchronized String keyFor(int anilistId) {
        String best = null;
        long bestAt = -1;
        for (Iterator<String> it = shows.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject s = shows.optJSONObject(k);
            if (s == null || s.optInt("a") != anilistId || App.str(s, "source").isEmpty()) continue;
            long at = Math.max(s.optLong("at"), s.optLong("savedAt"));
            if (at > bestAt) {
                best = k;
                bestAt = at;
            }
        }
        return best;
    }

    // ---- changes ----

    /** A show was opened or played: its title, cover and details are kept, and its cover cached. */
    void remember(String source, String sourceName, JSONObject anime) {
        String url = App.str(anime, "url");
        if (source.isEmpty() || url.isEmpty()) return;
        String key = key(source, url);
        synchronized (this) {
            JSONObject s = touch(key, source, url);
            App.put(s, "sourceName", sourceName);
            String title = App.str(anime, "title");
            if (!title.isEmpty()) App.put(s, "title", title);
            String cover = App.str(anime, "thumbnail_url");
            if (!cover.isEmpty()) App.put(s, "cover", cover);
            App.put(s, "anime", slim(anime));
        }
        keepCover(key);
        changed(false);
    }

    /** The player's position in an episode; marks it watched at 85%. Returns true the first time it's finished. */
    boolean progress(String key, JSONObject ep, int index, int count, double t, double d) {
        String url = App.str(ep, "url");
        if (url.isEmpty() || t < 3) return false;
        boolean finished, moved;
        synchronized (this) {
            JSONObject s = shows.optJSONObject(key);
            if (s == null) return false;
            JSONObject last = s.optJSONObject("last");
            moved = last == null || !url.equals(App.str(last, "url")) || s.optLong("at") == 0;
            JSONObject eps = s.optJSONObject("eps");
            if (eps == null) App.put(s, "eps", eps = new JSONObject());
            JSONObject e = eps.optJSONObject(url);
            if (e == null) App.put(eps, url, e = new JSONObject());
            boolean was = e.optBoolean("w");
            finished = !was && d > 0 && t >= d * DONE;
            App.put(e, "t", Math.round(t));
            App.put(e, "d", Math.round(d));
            if (finished) App.put(e, "w", true);
            long now = System.currentTimeMillis();
            App.put(e, "at", now);
            App.put(s, "last", App.obj("url", url, "name", App.str(ep, "name"), "num", ep.optDouble("episode_number", -1),
                    "i", index, "t", Math.round(t), "d", Math.round(d), "at", now));
            App.put(s, "at", now);
            if (count > 0) App.put(s, "count", count);
        }
        changed(!moved && !finished);
        return finished;
    }

    /** Marks episodes watched or not from the show page. */
    void mark(String key, JSONArray urls, boolean watched) {
        synchronized (this) {
            JSONObject s = shows.optJSONObject(key);
            if (s == null) return;
            JSONObject eps = s.optJSONObject("eps");
            if (eps == null) App.put(s, "eps", eps = new JSONObject());
            for (int i = 0; i < urls.length(); i++) {
                String u = urls.optString(i);
                JSONObject e = eps.optJSONObject(u);
                if (e == null) App.put(eps, u, e = new JSONObject());
                App.put(e, "w", watched);
                if (!watched) {
                    e.remove("t");
                    e.remove("d");
                }
            }
        }
        changed(false);
    }

    void save(String key, boolean on) {
        synchronized (this) {
            JSONObject s = shows.optJSONObject(key);
            if (s == null || s.optBoolean("saved") == on) return;
            App.put(s, "saved", on);
            App.put(s, "savedAt", on ? System.currentTimeMillis() : 0);
        }
        keepCover(key);
        changed(false);
    }

    /** Links a show to its AniList entry; a placeholder for the same entry from the site version merges into it. */
    void link(String key, int anilistId) {
        synchronized (this) {
            JSONObject s = shows.optJSONObject(key);
            if (s == null) return;
            App.put(s, "a", anilistId);
            JSONObject old = anilistId > 0 ? shows.optJSONObject(aniListKey(anilistId)) : null;
            if (old != null && !key.equals(aniListKey(anilistId))) {
                if (old.optBoolean("saved") && !s.optBoolean("saved")) {
                    App.put(s, "saved", true);
                    App.put(s, "savedAt", old.optLong("savedAt"));
                }
                // Where the site version left off, until this show has a place of its own.
                if (s.optJSONObject("last") == null && old.optJSONObject("last") != null) {
                    App.put(s, "last", old.optJSONObject("last"));
                    App.put(s, "at", old.optLong("at"));
                    if (s.optInt("count") == 0 && old.optInt("count") > 0) App.put(s, "count", old.optInt("count"));
                }
                shows.remove(aniListKey(anilistId));
                File f = cover(aniListKey(anilistId));
                if (f.isFile() && !cover(key).isFile()) f.renameTo(cover(key));
                else f.delete();
            }
        }
        changed(false);
    }

    /** Settings → "Clear history": positions and recently played go, saved shows and AniList links stay. */
    void clearRecent() {
        synchronized (this) {
            for (Iterator<String> it = shows.keys(); it.hasNext(); ) {
                JSONObject s = shows.optJSONObject(it.next());
                if (s == null) continue;
                s.remove("last");
                s.remove("eps");
                App.put(s, "at", 0);
            }
            prune();
        }
        changed(false);
    }

    /** Removes one show from Continue Watching (its positions stay on the show page). */
    void forget(String key) {
        synchronized (this) {
            JSONObject s = shows.optJSONObject(key);
            if (s == null) return;
            App.put(s, "at", 0);
            prune();
        }
        changed(false);
    }

    // ---- covers ----

    /** Fetches a show's cover once, through its source (some need the source's headers). */
    void keepCover(String key) {
        JSONObject s = show(key);
        if (s == null || cover(key).isFile() || !fetching.add(key)) return;
        String url = App.str(s, "cover"), source = App.str(s, "source");
        if (url.isEmpty()) {
            fetching.remove(key);
            return;
        }
        App.I.io.execute(() -> {
            try {
                covers.mkdirs();
                boolean got = Images.keep(source.isEmpty() ? "0" : source, url, cover(key));
                if (got) changed(false);
            } finally {
                fetching.remove(key);
            }
        });
    }

    // ---- helpers; the callers hold the lock ----

    private JSONObject touch(String key, String source, String url) {
        JSONObject s = shows.optJSONObject(key);
        if (s != null) return s;
        s = App.obj("key", key, "source", source, "url", url, "added", System.currentTimeMillis());
        App.put(shows, key, s);
        prune();
        return s;
    }

    /** Drops the oldest shows that aren't saved, linked to downloads or recently played. */
    private void prune() {
        if (shows.length() <= MAX_SHOWS) return;
        List<JSONObject> all = new ArrayList<>();
        for (Iterator<String> it = shows.keys(); it.hasNext(); ) {
            JSONObject s = shows.optJSONObject(it.next());
            if (s != null && !s.optBoolean("saved")) all.add(s);
        }
        all.sort((x, y) -> Long.compare(Math.max(x.optLong("at"), x.optLong("added")), Math.max(y.optLong("at"), y.optLong("added"))));
        Downloads dl = Downloads.get(app);
        for (JSONObject s : all) {
            if (shows.length() <= MAX_SHOWS) break;
            String k = App.str(s, "key");
            if (dl.hasShow(k)) continue;
            shows.remove(k);
            cover(k).delete();
        }
    }

    /** The details worth keeping offline (not the description's full length or the extension's internals). */
    private static JSONObject slim(JSONObject anime) {
        JSONObject o = new JSONObject();
        for (String k : new String[] {"url", "title", "thumbnail_url", "genre", "status", "author", "artist", "description"}) {
            if (!anime.has(k) || anime.isNull(k)) continue;
            Object v = anime.opt(k);
            if ("description".equals(k) && v instanceof String && ((String) v).length() > 1200) v = ((String) v).substring(0, 1200) + "…";
            App.put(o, k, v);
        }
        return o;
    }

    /** shelf.json from the site version: shows by AniList id, opened by finding them on a source. */
    private void migrate() {
        File old = new File(app.getFilesDir(), "shelf.json");
        if (!old.isFile()) return;
        try {
            JSONObject j = new JSONObject(new String(Files.readAllBytes(old.toPath()), StandardCharsets.UTF_8));
            JSONArray recent = j.optJSONArray("recent"), saved = j.optJSONArray("saved");
            for (int pass = 0; pass < 2; pass++) {
                JSONArray a = pass == 0 ? recent : saved;
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject e = a.optJSONObject(i);
                    int id = e == null ? 0 : e.optInt("id");
                    if (id <= 0) continue;
                    String key = aniListKey(id);
                    JSONObject s = shows.optJSONObject(key);
                    if (s == null) {
                        s = App.obj("key", key, "source", "", "url", "", "a", id, "added", System.currentTimeMillis());
                        App.put(shows, key, s);
                    }
                    String title = pass == 0 ? App.str(e, "show") : App.str(e, "title");
                    if (!title.isEmpty()) App.put(s, "title", title);
                    if (!App.str(e, "cover").isEmpty()) App.put(s, "cover", App.str(e, "cover"));
                    if (pass == 0) {
                        App.put(s, "at", e.optLong("at"));
                        App.put(s, "count", e.optInt("epCount"));
                        App.put(s, "last", App.obj("url", "", "name", App.str(e, "epTitle"), "num", e.optInt("ep"), "i", -1,
                                "t", e.optLong("t"), "d", e.optLong("d"), "at", e.optLong("at")));
                    } else {
                        App.put(s, "saved", true);
                        App.put(s, "savedAt", e.optLong("savedAt"));
                    }
                    File f = new File(covers, id + ".jpg");
                    if (f.isFile()) f.renameTo(cover(key));
                }
            }
        } catch (IOException | JSONException ignored) {
        }
        write(true);
    }

    // ---- saving ----

    private void changed(boolean positionOnly) {
        if (!positionOnly) main.post(() -> {
            App a = App.I;
            if (a == null) return;
            String j = json().toString();
            a.toBottom("onShelf", j);
            a.toTop("shelf", j);
        });
        write(!positionOnly);
    }

    /** Positions change every few seconds while playing; they're written at most every 15 s. */
    private void write(boolean now) {
        synchronized (this) {
            long wait = lastWrite + WRITE_EVERY_MS - System.currentTimeMillis();
            if (!now && wait > 0) {
                if (!writeQueued) {
                    writeQueued = true;
                    main.postDelayed(() -> write(true), wait);
                }
                return;
            }
            writeQueued = false;
            lastWrite = System.currentTimeMillis();
        }
        Runnable w = () -> {
            String text;
            synchronized (this) {
                text = App.obj("v", 2, "shows", shows).toString();
            }
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(file)) tmp.delete();
        };
        if (App.I != null) App.I.io.execute(w);
        else w.run();
    }

    private static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
