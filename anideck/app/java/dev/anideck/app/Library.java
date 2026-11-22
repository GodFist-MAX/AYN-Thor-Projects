package dev.anideck.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The user's AniList anime lists (through an AniList login, or a public username), new-episode
 * tracking and notifications, progress updates, and title search for linking a source's shows.
 */
final class Library {
    static final String[] STATUSES = {"CURRENT", "REPEATING", "COMPLETED", "PAUSED", "PLANNING", "DROPPED"};
    private static final String MEDIA_FIELDS = "id title { userPreferred romaji english } coverImage { large color }"
            + " bannerImage episodes duration status format seasonYear nextAiringEpisode { episode airingAt }";
    private static final String LISTS = "query ($userId: Int, $userName: String) { MediaListCollection(userId: $userId,"
            + " userName: $userName, type: ANIME) { lists { isCustomList entries { status progress"
            + " score(format: POINT_10) updatedAt media { " + MEDIA_FIELDS + " } } } } }";
    private static final String VIEWER = "query { Viewer { id name avatar { medium } } }";
    private static final String USER = "query ($name: String) { User(name: $name) { id name avatar { medium } } }";
    private static final String MEDIA = "query ($id: Int) { Media(id: $id, type: ANIME) { " + MEDIA_FIELDS
            + " genres averageScore mediaListEntry { status progress } } }";
    private static final String SEARCH = "query ($q: String) { Page(perPage: 12) { media(search: $q, type: ANIME, sort: SEARCH_MATCH) { "
            + MEDIA_FIELDS.replace("title { userPreferred romaji english }", "title { userPreferred romaji english native }")
            + " synonyms mediaListEntry { status progress } } } }";
    private static final String ENTRY = "query ($userId: Int, $mediaId: Int) { MediaList(userId: $userId, mediaId: $mediaId) { status progress } }";
    private static final String SAVE = "mutation ($mediaId: Int, $progress: Int, $status: MediaListStatus) {"
            + " SaveMediaListEntry(mediaId: $mediaId, progress: $progress, status: $status) { status progress } }";
    /** Lists older than this refresh when the app opens; the background job runs every 30 minutes. */
    private static final long STALE_MS = 10 * 60_000L;

    private final App app;
    private final File file;
    /** AniList id -> episodes aired when the lists were last checked, for new-episode alerts. */
    private final SharedPreferences seen;
    private final Map<Integer, JSONObject> media = new ConcurrentHashMap<>();
    private final Set<String> rejected = ConcurrentHashMap.newKeySet();
    private final Set<String> marked = ConcurrentHashMap.newKeySet();
    private JSONObject lists;       // guarded by this: status -> [entry]
    private long updated;           // guarded by this
    private volatile boolean syncing;
    private volatile String error;

    Library(App app) {
        this.app = app;
        file = new File(app.getFilesDir(), "library.json");
        seen = app.getSharedPreferences("seen", App.MODE_PRIVATE);
        lists = emptyLists();
        try {
            JSONObject j = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONObject l = j.optJSONObject("lists");
            if (l != null) lists = l;
            updated = j.optLong("updated");
        } catch (IOException | JSONException ignored) {
        }
    }

    // ---- account ----

    String token() {
        return app.prefs.getString("token", null);
    }

    private String userName() {
        return app.prefs.getString("user", null);
    }

    private JSONObject account() {
        String name = app.prefs.getString("uname", null);
        if (name == null) return null;
        return App.obj("name", name, "avatar", app.prefs.getString("avatar", ""), "via", token() != null ? "site" : "user");
    }

    /** A token from Connect AniList: taken even if it's the one Disconnect set aside. */
    void login(String jwt) {
        if (jwt == null) return;
        rejected.remove(hash(jwt));
        app.prefs.edit().remove("ignoredToken").apply();
        offerToken(jwt);
    }

    /** A token found in the site's storage once the user logged in there. */
    void offerToken(String jwt) {
        if (jwt == null || jwt.equals(token())) return;
        String h = hash(jwt);
        if (rejected.contains(h) || h.equals(app.prefs.getString("ignoredToken", ""))) return;
        JSONObject claims = AniList.claims(jwt);
        if (claims == null) return;
        app.io.execute(() -> {
            try {
                JSONObject v = AniList.query(VIEWER, null, jwt).optJSONObject("Viewer");
                if (v == null) throw new AniList.Error(401, "no viewer");
                Log.i(App.TAG, "using the site's AniList login (client " + claims.optString("aud") + ")");
                saveAccount(v, jwt, null);
                syncNow(true);
            } catch (AniList.Error e) {
                rejected.add(h);
                Log.i(App.TAG, "the site's storage held a token AniList didn't accept (" + e.code + ")");
            } catch (IOException e) {
                Log.w(App.TAG, "could not check the site's login: " + e);
            }
        });
    }

    /** Shows a public AniList profile's lists (read-only). */
    void useName(String name) {
        if (name == null || name.trim().isEmpty()) return;
        String n = name.trim();
        app.io.execute(() -> {
            try {
                JSONObject u = AniList.query(USER, App.obj("name", n), null).optJSONObject("User");
                if (u == null) throw new AniList.Error(404, "not found");
                saveAccount(u, null, u.optString("name"));
                syncNow(true);
            } catch (AniList.Error e) {
                error = e.code == 404 ? "AniList has no user named " + n : e.getMessage();
                push();
            } catch (IOException e) {
                error = "Couldn't reach AniList";
                push();
            }
        });
    }

    private void saveAccount(JSONObject user, String token, String name) {
        boolean sameUser = app.prefs.getInt("uid", 0) == user.optInt("id");
        JSONObject av = user.optJSONObject("avatar");
        app.prefs.edit()
                .putString("token", token)
                .putString("user", name)
                .putInt("uid", user.optInt("id"))
                .putString("uname", user.optString("name"))
                .putString("avatar", App.str(av, "medium"))
                .remove("ignoredToken")
                .apply();
        if (!sameUser) {
            synchronized (this) {
                lists = emptyLists();
                updated = 0;
            }
            seen.edit().clear().apply();
        }
        error = null;
        push();
    }

    /** Forgets the account. A site login that is still active isn't picked up again until it changes. */
    void signOut() {
        String t = token();
        SharedPreferences.Editor ed = app.prefs.edit().remove("token").remove("user").remove("uid").remove("uname").remove("avatar");
        if (t != null) ed.putString("ignoredToken", hash(t));
        ed.apply();
        synchronized (this) {
            lists = emptyLists();
            updated = 0;
        }
        file.delete();
        seen.edit().clear().apply();
        error = null;
        push();
    }

    // ---- lists ----

    synchronized String json() {
        JSONObject a = account();
        return App.obj("account", a == null ? JSONObject.NULL : a, "lists", lists, "updated", updated,
                "syncing", syncing, "error", error == null ? JSONObject.NULL : error, "canTrack", token() != null).toString();
    }

    private void push() {
        String j = json();
        app.toBottom("onLibrary", j);
        app.toTop("library", j);
    }

    void syncIfStale() {
        sync(false);
    }

    void sync(boolean force) {
        app.io.execute(() -> syncNow(force));
    }

    /** Refreshes the lists. False means "try again later" (offline, AniList busy or down). */
    boolean syncNow(boolean force) {
        String token = token(), name = userName();
        if (token == null && name == null) return true;
        if (!app.net.online()) return false;
        synchronized (this) {
            if (syncing || (!force && System.currentTimeMillis() - updated < STALE_MS)) return true;
            syncing = true;
        }
        push();
        try {
            JSONObject vars = token != null ? App.obj("userId", app.prefs.getInt("uid", 0)) : App.obj("userName", name);
            JSONObject data = AniList.query(LISTS, vars, token);
            JSONObject fresh = build(data == null ? null : data.optJSONObject("MediaListCollection"));
            synchronized (this) {
                lists = fresh;
                updated = System.currentTimeMillis();
            }
            error = null;
            save();
            checkNew();
            return true;
        } catch (AniList.Error e) {
            if (token != null && (e.code == 400 || e.code == 401) && e.getMessage().toLowerCase().contains("token")) {
                signOut();
                error = "Your AniList login expired. Log in again in Settings.";
                return true;
            }
            error = e.getMessage();
            return e.code != 429 && e.code < 500;
        } catch (IOException e) {
            error = "Couldn't reach AniList";
            return false;
        } finally {
            syncing = false;
            push();
        }
    }

    private static JSONObject emptyLists() {
        JSONObject out = new JSONObject();
        for (String s : STATUSES) App.put(out, s, new JSONArray());
        return out;
    }

    private static JSONObject build(JSONObject collection) {
        JSONObject out = emptyLists();
        JSONArray ls = collection == null ? null : collection.optJSONArray("lists");
        for (int i = 0; ls != null && i < ls.length(); i++) {
            JSONObject l = ls.optJSONObject(i);
            if (l == null || l.optBoolean("isCustomList")) continue;
            JSONArray es = l.optJSONArray("entries");
            for (int k = 0; es != null && k < es.length(); k++) {
                JSONObject e = es.optJSONObject(k);
                JSONObject c = e == null ? null : compact(e.optJSONObject("media"), e);
                JSONArray dst = c == null ? null : out.optJSONArray(App.str(c, "status"));
                if (dst != null) dst.put(c);
            }
        }
        for (String s : STATUSES) sortByUpdated(out, s);
        return out;
    }

    /** The fields the bottom screen uses, from a Media object and the user's list entry (if any). */
    private static JSONObject compact(JSONObject m, JSONObject entry) {
        if (m == null) return null;
        JSONObject t = m.optJSONObject("title"), cover = m.optJSONObject("coverImage"), next = m.optJSONObject("nextAiringEpisode");
        int episodes = m.optInt("episodes", 0), progress = entry == null ? 0 : entry.optInt("progress", 0);
        String mstatus = App.str(m, "status");
        int aired;
        if (next != null) aired = Math.max(0, next.optInt("episode", 1) - 1);
        else if ("NOT_YET_RELEASED".equals(mstatus)) aired = 0;
        else aired = episodes > 0 ? episodes : progress;
        String title = App.str(t, "userPreferred");
        JSONObject o = App.obj("id", m.optInt("id"), "title", title.isEmpty() ? App.str(t, "romaji") : title,
                "romaji", App.str(t, "romaji"), "english", App.str(t, "english"),
                "cover", App.str(cover, "large"), "color", App.str(cover, "color"), "banner", App.str(m, "bannerImage"),
                "episodes", episodes, "progress", progress, "aired", aired, "newCount", Math.max(0, aired - progress),
                "mstatus", mstatus, "format", App.str(m, "format"), "year", m.optInt("seasonYear"),
                "duration", m.optInt("duration"));
        if (next != null) {
            App.put(o, "nextEp", next.optInt("episode"));
            App.put(o, "nextAt", next.optLong("airingAt"));
        }
        if (entry != null) {
            App.put(o, "status", App.str(entry, "status"));
            App.put(o, "updatedAt", entry.optLong("updatedAt"));
            App.put(o, "score", entry.optDouble("score", 0));
        }
        return o;
    }

    private static void sortByUpdated(JSONObject lists, String status) {
        JSONArray a = lists.optJSONArray(status);
        if (a == null || a.length() < 2) return;
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) l.add(a.optJSONObject(i));
        l.sort((x, y) -> Long.compare(y.optLong("updatedAt"), x.optLong("updatedAt")));
        App.put(lists, status, new JSONArray(l));
    }

    synchronized JSONObject entry(int id) {
        for (String s : STATUSES) {
            JSONArray a = lists.optJSONArray(s);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && o.optInt("id") == id) return o;
            }
        }
        return null;
    }

    private void save() {
        String text;
        synchronized (this) {
            text = App.obj("lists", lists, "updated", updated).toString();
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(file)) tmp.delete();
    }

    // ---- new episodes ----

    private void checkNew() {
        boolean first = !seen.getBoolean("_init", false);
        SharedPreferences.Editor ed = seen.edit();
        List<JSONObject> fresh = new ArrayList<>();
        synchronized (this) {
            for (String s : new String[] {"CURRENT", "REPEATING"}) {
                JSONArray a = lists.optJSONArray(s);
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject e = a.optJSONObject(i);
                    String key = String.valueOf(e.optInt("id"));
                    int aired = e.optInt("aired"), before = seen.getInt(key, -1);
                    if (aired != before) ed.putInt(key, aired);
                    if (!first && before >= 0 && aired > before && aired > e.optInt("progress")) fresh.add(e);
                }
            }
        }
        ed.putBoolean("_init", true).apply();
        if (app.setting("notify")) for (JSONObject e : fresh) notifyEpisode(e);
    }

    private void notifyEpisode(JSONObject e) {
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        int id = e.optInt("id"), aired = e.optInt("aired"), progress = e.optInt("progress");
        int unwatched = aired - progress;
        Intent open = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN, id).putExtra(MainActivity.EXTRA_EP, progress + 1)
                .putExtra(MainActivity.EXTRA_TITLE, App.str(e, "romaji"));
        PendingIntent pi = PendingIntent.getActivity(app, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(app, App.CHANNEL_EPISODES)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(App.str(e, "title"))
                .setContentText("Episode " + aired + " is out" + (unwatched > 1 ? " · " + unwatched + " to watch" : ""))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setGroup("episodes");
        Bitmap cover = bitmap(App.str(e, "cover"), 256);
        if (cover != null) b.setLargeIcon(cover);
        app.getSystemService(NotificationManager.class).notify(id, b.build());
    }

    // ---- progress ----

    /** The player passed 85% of an episode: move the AniList progress forward (never back). */
    void watched(int id, int ep) {
        if (!app.setting("autoTrack") || token() == null || id <= 0 || ep <= 0 || !marked.add(id + ":" + ep)) return;
        app.io.execute(() -> {
            String token = token();
            if (token == null) return;
            try {
                JSONObject cur = null;
                try {
                    JSONObject d = AniList.query(ENTRY, App.obj("userId", app.prefs.getInt("uid", 0), "mediaId", id), token);
                    cur = d == null ? null : d.optJSONObject("MediaList");
                } catch (AniList.Error e) {
                    if (e.code != 404) throw e;
                }
                int progress = cur == null ? 0 : cur.optInt("progress");
                String status = cur == null ? "" : App.str(cur, "status");
                if (ep <= progress) return;
                JSONObject info = details(id);
                int total = info == null ? 0 : info.optInt("episodes");
                String next = total > 0 && ep >= total ? "COMPLETED" : "REPEATING".equals(status) ? "REPEATING" : "CURRENT";
                AniList.query(SAVE, App.obj("mediaId", id, "progress", ep, "status", next), token);
                Log.i(App.TAG, "AniList progress for " + id + " is now " + ep + " (" + next + ")");
                app.toBottom("toast", JSONObject.quote("Marked episode " + ep + " watched on AniList"));
                syncNow(true);
            } catch (IOException e) {
                marked.remove(id + ":" + ep);
                Log.w(App.TAG, "could not update AniList progress: " + e.getMessage());
            }
        });
    }

    // ---- show details ----

    /** Sends a show's details to the bottom screen (for shows that aren't on the user's lists). */
    void media(int id) {
        JSONObject cached = media.get(id);
        if (cached != null) {
            app.toBottom("onMedia", cached.toString());
            return;
        }
        app.io.execute(() -> {
            JSONObject o = details(id);
            if (o != null) app.toBottom("onMedia", o.toString());
        });
    }

    /** List entry or AniList details for a show; blocks for the network on a cache miss. */
    JSONObject details(int id) {
        JSONObject e = entry(id);
        if (e != null) return e;
        JSONObject cached = media.get(id);
        if (cached != null) return cached;
        try {
            JSONObject d = AniList.query(MEDIA, App.obj("id", id), token());
            JSONObject m = d == null ? null : d.optJSONObject("Media");
            JSONObject o = compact(m, m == null ? null : m.optJSONObject("mediaListEntry"));
            if (o != null) media.put(id, o);
            return o;
        } catch (IOException ex) {
            Log.w(App.TAG, "details for " + id + ": " + ex.getMessage());
            return null;
        }
    }

    // ---- search ----

    /** AniList's matches for a title, with every name it goes by, for linking a source's show to its entry. */
    JSONArray search(String q) throws IOException {
        JSONArray out = new JSONArray();
        if (q == null || q.trim().isEmpty()) return out;
        JSONObject d = AniList.query(SEARCH, App.obj("q", q.trim()), token());
        JSONObject page = d == null ? null : d.optJSONObject("Page");
        JSONArray ms = page == null ? null : page.optJSONArray("media");
        for (int i = 0; ms != null && i < ms.length(); i++) {
            JSONObject m = ms.optJSONObject(i);
            JSONObject o = compact(m, m == null ? null : m.optJSONObject("mediaListEntry"));
            if (o == null) continue;
            JSONObject t = m.optJSONObject("title");
            App.put(o, "native", App.str(t, "native"));
            App.put(o, "synonyms", m.optJSONArray("synonyms") == null ? new JSONArray() : m.optJSONArray("synonyms"));
            media.put(o.optInt("id"), o);
            out.put(o);
        }
        return out;
    }

    // ---- helpers ----

    static Bitmap bitmap(String url, int maxPx) {
        if (url == null || !url.startsWith("https://")) return null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            byte[] data;
            try (InputStream in = c.getInputStream()) {
                data = App.readAll(in);
            } finally {
                c.disconnect();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = 1;
            while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= maxPx) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String hash(String s) {
        return Integer.toHexString(s.hashCode()) + ":" + s.length();
    }
}
