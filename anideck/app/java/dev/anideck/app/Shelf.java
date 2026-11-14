package dev.anideck.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recently played episodes and the shows saved with "Save show", kept on the Thor with their covers so
 * they still show without a connection: on AniDeck's bottom screen and, through {@link Feed}, in CrossBar.
 */
final class Shelf {
    private static final int MAX_RECENT = 20, MAX_SAVED = 100;
    private static final long WRITE_EVERY_MS = 15_000, RECHECK_MS = 6 * 3600_000L;
    /** Details copied from AniList into a saved show. */
    private static final String[] DETAILS = {"title", "english", "cover", "color", "episodes", "aired", "mstatus",
            "format", "year", "duration", "nextEp", "nextAt"};
    private static Shelf instance;

    private final File file, covers;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Integer> fetching = ConcurrentHashMap.newKeySet();
    private JSONArray recent = new JSONArray(), saved = new JSONArray(); // guarded by this
    private String current = "", cleared = "";
    private long lastWrite;
    private boolean writeQueued;

    /** The provider can be called before App.onCreate has run, so this doesn't depend on App.I. */
    static synchronized Shelf get(Context c) {
        if (instance == null) instance = new Shelf(c.getApplicationContext());
        return instance;
    }

    private Shelf(Context c) {
        file = new File(c.getFilesDir(), "shelf.json");
        covers = new File(c.getFilesDir(), "covers");
        try {
            JSONObject j = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONArray r = j.optJSONArray("recent"), s = j.optJSONArray("saved");
            if (r != null) recent = r;
            if (s != null) saved = s;
        } catch (IOException | JSONException ignored) {
        }
    }

    File cover(int id) {
        return new File(covers, id + ".jpg");
    }

    synchronized JSONObject json() {
        return App.obj("recent", copy(recent), "saved", copy(saved));
    }

    synchronized boolean isSaved(int id) {
        return indexOf(saved, id, "") >= 0;
    }

    // ---- recently played ----

    /** Each state from the top screen: the episode being watched goes to the front of the list. */
    void played(JSONObject st) {
        JSONObject p = st.optJSONObject("player");
        String show = App.str(st, "show");
        int ep = st.optInt("ep"), id = st.optInt("anilistId");
        // A page that was opened but never played doesn't count.
        if (p == null || ep <= 0 || show.isEmpty() || p.optDouble("t", 0) < 3 || !"watch".equals(App.str(st, "page"))) return;
        String key = (id > 0 ? id : show) + ":" + ep;
        boolean moved;
        synchronized (this) {
            current = key;
            // After "Clear recently played" the open episode only comes back once it plays again.
            if (key.equals(cleared) && !"playing".equals(App.str(p, "state"))) return;
            cleared = "";
            JSONObject top = recent.optJSONObject(0);
            boolean same = top != null && (id > 0 ? top.optInt("id") == id : show.equals(App.str(top, "show")));
            moved = !same || top.optInt("ep") != ep;
            JSONObject e = same ? top : new JSONObject();
            App.put(e, "id", id);
            App.put(e, "show", show);
            App.put(e, "ep", ep);
            App.put(e, "epTitle", App.str(st, "epTitle"));
            App.put(e, "epCount", st.optInt("epCount"));
            App.put(e, "t", Math.round(p.optDouble("t", 0)));
            App.put(e, "d", Math.round(p.optDouble("d", 0)));
            App.put(e, "at", System.currentTimeMillis());
            App.put(e, "path", App.str(st, "path"));
            if (!same) {
                remove(recent, id, show);
                recent = prepend(recent, e, MAX_RECENT);
            }
        }
        if (moved) {
            if (id > 0) fetch(id, false);
            changed();
        }
        write(moved);
    }

    /** Settings → "Clear recently played"; covers of saved shows stay. */
    void clearRecent() {
        synchronized (this) {
            for (int i = 0; i < recent.length(); i++) {
                JSONObject e = recent.optJSONObject(i);
                int id = e == null ? 0 : e.optInt("id");
                if (id > 0 && indexOf(saved, id, "") < 0) cover(id).delete();
            }
            recent = new JSONArray();
            cleared = current;
        }
        changed();
        write(true);
    }

    // ---- saved shows ----

    /** "Save show": AniList details and the cover are fetched right away so they're there offline. */
    void save(int id, String title) {
        if (id <= 0) return;
        synchronized (this) {
            if (indexOf(saved, id, "") >= 0) return;
            saved = prepend(saved, App.obj("id", id, "title", title, "savedAt", System.currentTimeMillis()), MAX_SAVED);
        }
        changed();
        write(true);
        fetch(id, true);
    }

    void unsave(int id) {
        boolean keepCover;
        synchronized (this) {
            int i = indexOf(saved, id, "");
            if (i < 0) return;
            saved.remove(i);
            keepCover = indexOf(recent, id, "") >= 0;
        }
        if (!keepCover) cover(id).delete();
        changed();
        write(true);
    }

    /** Background sync: airing details of saved shows go stale (next episode, episodes aired). */
    void refresh() {
        long now = System.currentTimeMillis();
        int n = 0;
        synchronized (this) {
            for (int i = 0; i < saved.length() && n < 10; i++) {
                JSONObject e = saved.optJSONObject(i);
                if (e == null || now - e.optLong("checked") < RECHECK_MS) continue;
                fetch(e.optInt("id"), true);
                n++;
            }
        }
    }

    // ---- covers and details ----

    /** Caches the cover, and with details=true copies AniList details into the saved show. */
    private void fetch(int id, boolean details) {
        if (id <= 0 || (!details && cover(id).isFile()) || !fetching.add(id)) return;
        App.I.io.execute(() -> {
            try {
                JSONObject d = App.I.library.details(id);
                if (d == null) return;
                if (!cover(id).isFile()) {
                    Bitmap b = Library.bitmap(App.str(d, "cover"), 360);
                    if (b != null) writeCover(id, b);
                }
                synchronized (this) {
                    for (int i = 0; i < recent.length(); i++) {
                        JSONObject e = recent.optJSONObject(i);
                        if (e == null || e.optInt("id") != id) continue;
                        App.put(e, "cover", App.str(d, "cover"));
                        App.put(e, "color", App.str(d, "color"));
                    }
                    int i = indexOf(saved, id, "");
                    if (details && i >= 0) {
                        JSONObject e = saved.optJSONObject(i);
                        for (String k : DETAILS) {
                            if (d.has(k) && !d.isNull(k)) App.put(e, k, d.opt(k));
                            else e.remove(k);
                        }
                        App.put(e, "checked", System.currentTimeMillis());
                    }
                }
                changed();
                write(true);
            } finally {
                fetching.remove(id);
            }
        });
    }

    private void writeCover(int id, Bitmap b) {
        covers.mkdirs();
        File tmp = new File(covers, id + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.JPEG, 85, o);
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(cover(id))) tmp.delete();
    }

    // ---- saving ----

    private void changed() {
        main.post(() -> App.I.toBottom("onShelf", json().toString()));
    }

    /** Positions change every second while playing; they're written at most every 15 s. */
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
        App.I.io.execute(() -> {
            String text;
            synchronized (this) {
                text = App.obj("recent", recent, "saved", saved).toString();
            }
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

    // ---- JSON helpers ----

    private static int indexOf(JSONArray a, int id, String show) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null) continue;
            if (id > 0 ? e.optInt("id") == id : show.equals(App.str(e, "show"))) return i;
        }
        return -1;
    }

    private static void remove(JSONArray a, int id, String show) {
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject e = a.optJSONObject(i);
            if (e != null && (id > 0 && e.optInt("id") == id || show.equals(App.str(e, "show")))) a.remove(i);
        }
    }

    private static JSONArray prepend(JSONArray a, JSONObject first, int max) {
        JSONArray out = new JSONArray();
        out.put(first);
        for (int i = 0; i < a.length() && out.length() < max; i++) out.put(a.opt(i));
        return out;
    }

    private static JSONArray copy(JSONArray a) {
        try {
            return new JSONArray(a.toString());
        } catch (JSONException e) {
            return new JSONArray();
        }
    }
}
