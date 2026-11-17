package dev.anideck.app;

import android.Manifest;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Episodes saved on the Thor. The site's Download button opens its file host's page for the episode;
 * AniDeck loads that page out of sight, where dlhost.js picks the language and quality and has the
 * host prepare the MP4, and Android's download manager saves the file to Movies/AniDeck. The list
 * (downloads.json in the app's files) feeds the bottom screen's Downloads tab, {@link OfflinePlayer}
 * and CrossBar ({@link Feed}).
 */
final class Downloads {
    private static final String FOLDER = "AniDeck";
    private static final long PREP_MS = 20 * 60_000, POLL_MS = 700;
    private static Downloads instance;

    private final Context app;
    private final File file;
    private final DownloadManager dm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Map<String, Prep> preps = new HashMap<>(); // main thread only
    private JSONArray items = new JSONArray(); // guarded by this

    /** The feed can be read before App.onCreate has run, so this doesn't depend on App.I. */
    static synchronized Downloads get(Context c) {
        if (instance == null) instance = new Downloads(c.getApplicationContext());
        return instance;
    }

    private Downloads(Context c) {
        app = c;
        file = new File(c.getFilesDir(), "downloads.json");
        dm = c.getSystemService(DownloadManager.class);
        try {
            JSONArray a = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).optJSONArray("items");
            if (a != null) items = a;
        } catch (IOException | JSONException ignored) {
        }
        // The file host's page closes with the app, so an episode it was preparing has to start over.
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r != null && "preparing".equals(App.str(r, "state"))) {
                App.put(r, "state", "failed");
                App.put(r, "error", "AniDeck closed before the file host was ready");
            }
        }
    }

    // ---- the list ----

    /** Everything, with the download manager's progress, for the bottom screen. */
    synchronized JSONObject json() {
        long used = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r == null) continue;
            if ("downloading".equals(App.str(r, "state"))) check(r);
            if ("done".equals(App.str(r, "state"))) used += r.optLong("size");
        }
        return App.obj("items", copy(items), "used", used, "free", Environment.getExternalStorageDirectory().getUsableSpace(),
                "folder", Environment.DIRECTORY_MOVIES + "/" + FOLDER);
    }

    /** Downloaded episodes, newest first, without asking the download manager (for the feed). */
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

    /** Whether any download is of this show, whose cover then stays cached. */
    synchronized boolean has(int a) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r != null && a > 0 && r.optInt("a") == a) return true;
        }
        return false;
    }

    /** The next (dir 1) or previous (-1) downloaded episode of the same show. */
    synchronized JSONObject neighbor(JSONObject cur, int dir) {
        JSONObject best = null;
        int ep = cur.optInt("ep");
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r == null || !"done".equals(App.str(r, "state")) || !sameShow(r, cur)) continue;
            int e = r.optInt("ep");
            if (dir > 0 ? e > ep && (best == null || e < best.optInt("ep")) : e < ep && (best == null || e > best.optInt("ep"))) best = r;
        }
        return best == null ? null : copy(best);
    }

    /** Where the player reads a downloaded episode: through the download manager, else the file itself. */
    Uri uri(JSONObject r) {
        long d = r.optLong("dm", -1);
        Uri u = null;
        try {
            if (d >= 0) u = dm.getUriForDownloadedFile(d);
        } catch (RuntimeException ignored) {
        }
        return u != null ? u : Uri.fromFile(new File(App.str(r, "file")));
    }

    /** Where an episode was left off, for the player, the bottom screen and CrossBar. */
    void position(String id, double t, double d) {
        synchronized (this) {
            JSONObject r = item(id);
            if (r == null) return;
            App.put(r, "t", Math.round(t));
            App.put(r, "d", Math.round(d));
            App.put(r, "at", System.currentTimeMillis());
        }
        changed();
    }

    // ---- downloading ----

    /** The site's Download button opened url for the episode on the top screen (st). Main thread. */
    void start(String url, JSONObject st) {
        String show = App.str(st, "show");
        int a = st.optInt("anilistId"), ep = st.optInt("ep");
        if (!"watch".equals(App.str(st, "page")) || show.isEmpty() || ep <= 0) {
            say("Open an episode to download it");
            return;
        }
        boolean dub = App.str(st, "lang").contains("dub"), hd = App.I.setting("dlHD");
        String id = Long.toString(System.currentTimeMillis(), 36);
        synchronized (this) {
            JSONObject old = episode(a, show, ep);
            if (old != null && !"failed".equals(App.str(old, "state"))) {
                say("Episode " + ep + ("done".equals(App.str(old, "state")) ? " is already downloaded" : " is already downloading"));
                return;
            }
            if (old != null) drop(App.str(old, "id"));
            JSONArray next = new JSONArray();
            next.put(App.obj("id", id, "a", a, "show", show, "ep", ep, "epTitle", App.str(st, "epTitle"),
                    "path", App.str(st, "path"), "lang", dub ? "dub" : "sub", "hd", hd, "state", "preparing",
                    "note", "Opening the file host", "added", System.currentTimeMillis()));
            for (int i = 0; i < items.length(); i++) next.put(items.opt(i));
            items = next;
        }
        preps.put(id, new Prep(id, url, want(dub, hd)));
        if (a > 0) Shelf.get(app).keepCover(a);
        say("Downloading episode " + ep + ". Follow it in Downloads on the bottom screen");
        changed();
    }

    /** What dlhost.js picks on the file host's page: the language watched on the site, and the quality setting. */
    private static JSONObject want(boolean dub, boolean hd) {
        return App.obj(
                "versions", arr(dub ? new String[] {"^dub", "dub"} : new String[] {"^ssub", "soft", "^sub", "^hsub", "hard"}),
                "audios", arr(dub ? new String[] {"english", "dub"} : new String[] {"japanese", "original"}),
                "qualities", arr(hd ? new String[] {"1080", "720", "480"} : new String[] {"480", "360", "720", "1080"}));
    }

    /** Cancel or Delete: stops the episode's download if it's still going, deletes its file and forgets it. Main thread. */
    void delete(String id) {
        Prep p = preps.remove(id);
        if (p != null) p.close();
        JSONObject r;
        synchronized (this) {
            r = item(id);
            if (r == null) return;
            drop(id);
        }
        long d = r.optLong("dm", -1);
        String path = App.str(r, "file");
        App.I.io.execute(() -> {
            if (d >= 0) dm.remove(d);
            if (path.isEmpty()) return;
            File f = new File(path);
            if (f.exists() && !f.delete()) Log.w(App.TAG, "could not delete " + f.getName());
            File dir = f.getParentFile();
            if (dir != null) dir.delete(); // only once the show's folder is empty
        });
        changed();
    }

    /** The file host's page gave the MP4's address: the download manager takes it from here. */
    private void enqueue(String id, String href, JSONObject got, String referer) {
        String title, name;
        int ep;
        synchronized (this) {
            JSONObject r = item(id);
            if (r == null) return;
            ep = r.optInt("ep");
            title = App.str(r, "show") + " · Episode " + ep;
            name = safe(App.str(r, "show")) + "/" + fileName(r);
        }
        long d;
        try {
            d = dm.enqueue(new DownloadManager.Request(Uri.parse(href))
                    .setTitle(title)
                    .setMimeType("video/mp4")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .addRequestHeader("User-Agent", WebSettings.getDefaultUserAgent(app))
                    .addRequestHeader("Referer", referer)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, FOLDER + "/" + name));
        } catch (RuntimeException e) {
            Log.w(App.TAG, "could not start the download: " + e);
            failed(id, "Couldn't save to Movies on this Thor");
            return;
        }
        synchronized (this) {
            JSONObject r = item(id);
            if (r == null) {
                dm.remove(d);
                return;
            }
            App.put(r, "state", "downloading");
            App.put(r, "dm", d);
            App.put(r, "note", "Waiting to start");
            for (String k : new String[] {"version", "audio", "quality"}) App.put(r, k, App.str(got, k));
            App.put(r, "file", new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER + "/" + name).getPath());
        }
        Log.i(App.TAG, "downloading " + name);
        String instead = App.str(got, "instead");
        if (!instead.isEmpty()) {
            say("Episode " + ep + " in " + instead + " was still waiting at the file host, so it's downloading in " + App.str(got, "quality"));
        }
        changed();
    }

    /** The download manager says one of its downloads ended (DownloadDone). */
    void completed(long dmId) {
        synchronized (this) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject r = items.optJSONObject(i);
                if (r != null && r.optLong("dm", -1) == dmId && "downloading".equals(App.str(r, "state"))) check(r);
            }
        }
    }

    /** The download manager's progress for one episode; the caller holds the lock. */
    private void check(JSONObject r) {
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(r.optLong("dm", -1)))) {
            if (c == null || !c.moveToFirst()) {
                fail(r, "The download was stopped outside AniDeck");
                return;
            }
            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            int reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            long size = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            App.put(r, "got", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
            if (size > 0) App.put(r, "size", size);
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                finish(r, c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)));
            } else if (status == DownloadManager.STATUS_FAILED) {
                fail(r, failure(reason));
            } else {
                App.put(r, "note", status == DownloadManager.STATUS_PAUSED ? paused(reason)
                        : status == DownloadManager.STATUS_PENDING ? "Waiting to start" : "");
            }
        }
    }

    /** The caller holds the lock. */
    private void finish(JSONObject r, String local) {
        App.put(r, "state", "done");
        App.put(r, "note", "");
        App.put(r, "got", r.optLong("size"));
        App.put(r, "finished", System.currentTimeMillis());
        String path = local == null ? null : Uri.parse(local).getPath();
        if (path != null) App.put(r, "file", path);
        String id = App.str(r, "id");
        Log.i(App.TAG, "downloaded " + App.str(r, "show") + " episode " + r.optInt("ep"));
        App.I.io.execute(() -> {
            readSubtitles(id);
            notifyDone(id);
        });
        changed();
    }

    /** The caller holds the lock. */
    private void fail(JSONObject r, String error) {
        App.put(r, "state", "failed");
        App.put(r, "error", error);
        App.put(r, "note", "");
        Log.w(App.TAG, "download failed: " + error);
        changed();
    }

    private void failed(String id, String error) {
        synchronized (this) {
            JSONObject r = item(id);
            if (r != null) fail(r, error);
        }
    }

    private void note(String id, String text) {
        synchronized (this) {
            JSONObject r = item(id);
            if (r == null || text.equals(App.str(r, "note"))) return;
            App.put(r, "note", text);
        }
        changed();
    }

    private static String failure(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_INSUFFICIENT_SPACE: return "Not enough space on the Thor";
            case DownloadManager.ERROR_CANNOT_RESUME: return "The download broke off and couldn't pick up again";
            case DownloadManager.ERROR_HTTP_DATA_ERROR: return "The connection dropped";
            case DownloadManager.ERROR_FILE_ERROR:
            case DownloadManager.ERROR_DEVICE_NOT_FOUND: return "Couldn't save the file to Movies";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS: return "There's already a file with that name";
            default:
                return reason >= 400 && reason < 600 ? "The file host turned the download down (HTTP " + reason + ")"
                        : "The file host stopped sending the episode";
        }
    }

    private static String paused(int reason) {
        switch (reason) {
            case DownloadManager.PAUSED_WAITING_FOR_NETWORK: return "Waiting for the internet";
            case DownloadManager.PAUSED_QUEUED_FOR_WIFI: return "Waiting for Wi-Fi";
            case DownloadManager.PAUSED_WAITING_TO_RETRY: return "Trying again in a moment";
            default: return "Paused";
        }
    }

    // ---- after the download ----

    /** Names of the file's subtitle tracks, for the player's subtitle choices. */
    private void readSubtitles(String id) {
        JSONObject r = get(id);
        if (r == null) return;
        JSONArray subs;
        try (ParcelFileDescriptor fd = app.getContentResolver().openFileDescriptor(uri(r), "r");
             FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
            subs = Mp4.subtitles(in.getChannel());
        } catch (IOException | RuntimeException e) {
            Log.w(App.TAG, "could not read the subtitle tracks: " + e);
            return;
        }
        synchronized (this) {
            r = item(id);
            if (r == null) return;
            App.put(r, "subs", subs);
        }
        changed();
    }

    private void notifyDone(String id) {
        JSONObject r = get(id);
        if (r == null || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent open = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_DOWNLOAD, id);
        PendingIntent pi = PendingIntent.getActivity(app, id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(app, App.CHANNEL_DOWNLOADS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(App.str(r, "show"))
                .setContentText("Episode " + r.optInt("ep") + " is downloaded. Tap to watch it")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setGroup("downloads");
        File cover = r.optInt("a") > 0 ? Shelf.get(app).cover(r.optInt("a")) : null;
        Bitmap art = cover != null && cover.isFile() ? BitmapFactory.decodeFile(cover.getPath()) : null;
        if (art != null) b.setLargeIcon(art);
        app.getSystemService(NotificationManager.class).notify("download", id.hashCode(), b.build());
    }

    // ---- the file host's page ----

    /** The file host's page for one episode, open out of sight until it gives the MP4's address. */
    private final class Prep {
        private final String id, host, script;
        private final WebView w;
        private final long until = SystemClock.uptimeMillis() + PREP_MS;
        private final Runnable poll = this::poll;
        private boolean closed;

        Prep(String id, String url, JSONObject want) {
            this.id = id;
            String h = Uri.parse(url).getHost();
            host = h == null ? "" : h.toLowerCase(Locale.ROOT);
            script = "(" + App.I.asset("www/dlhost.js") + ")(" + want + ")";
            w = new WebView(app);
            WebSettings s = w.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setBlockNetworkImage(true);
            s.setMediaPlaybackRequiresUserGesture(true);
            s.setSupportMultipleWindows(true);
            s.setJavaScriptCanOpenWindowsAutomatically(false);
            w.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message m) {
                    return false;
                }

                // Dialogs need an activity, and nobody would see them.
                @Override public boolean onJsAlert(WebView v, String u, String m, JsResult r) {
                    r.cancel();
                    return true;
                }

                @Override public boolean onJsConfirm(WebView v, String u, String m, JsResult r) {
                    r.cancel();
                    return true;
                }

                @Override public boolean onJsPrompt(WebView v, String u, String m, String d, JsPromptResult r) {
                    r.cancel();
                    return true;
                }
            });
            w.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    if (r.isForMainFrame() && ours(r.getUrl())) return false;
                    Log.i(App.TAG, "download page: blocked a jump to " + r.getUrl().getHost());
                    return true;
                }

                // Only the file host itself: no ads, and not the preview player's stream.
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                    if (ours(r.getUrl())) return null;
                    return new WebResourceResponse("text/plain", "utf-8", 204, "No Content", Map.of(), new ByteArrayInputStream(new byte[0]));
                }

                @Override public void onPageFinished(WebView v, String u) {
                    if (!closed && ours(Uri.parse(u))) v.evaluateJavascript(script, null);
                }

                @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                    CoroBugs.rendererGone(app, "download page", d.didCrash());
                    end(null, "The file host's page crashed");
                    return true;
                }
            });
            w.setDownloadListener((href, ua, disposition, mime, length) -> {
                if (mime != null && mime.startsWith("video/")) end(App.obj("href", href), null);
            });
            w.loadUrl(url, Map.of("Referer", Site.base() + "/"));
            main.postDelayed(poll, POLL_MS);
        }

        private boolean ours(Uri u) {
            String h = u == null ? null : u.getHost();
            if (h == null || host.isEmpty()) return false;
            h = h.toLowerCase(Locale.ROOT);
            return h.equals(host) || h.endsWith("." + host);
        }

        private void poll() {
            if (closed) return;
            if (SystemClock.uptimeMillis() > until) {
                end(null, "The file host took too long to prepare the episode");
                return;
            }
            w.evaluateJavascript("window.__anideckDl||null", v -> {
                if (closed) return;
                JSONObject o = null;
                try {
                    o = new JSONObject(v);
                } catch (JSONException | NullPointerException ignored) {
                }
                String stage = App.str(o, "stage");
                if ("ready".equals(stage)) {
                    end(o, null);
                } else if ("error".equals(stage)) {
                    end(null, App.str(o, "error"));
                } else {
                    String n = App.str(o, "note");
                    if ("preparing".equals(stage)) {
                        note(id, "queued".equals(n) ? "Waiting in the file host's queue"
                                : "Preparing on the file host" + (n.isEmpty() ? "" : " · " + n));
                    }
                    main.postDelayed(poll, POLL_MS);
                }
            });
        }

        private void end(JSONObject o, String error) {
            if (closed) return;
            close();
            preps.remove(id);
            String href = App.str(o, "href");
            if (error == null && !href.startsWith("https://")) error = "";
            if (error != null) failed(id, error.isEmpty() ? "The file host couldn't prepare the episode" : error);
            else enqueue(id, href, o, "https://" + host + "/");
        }

        void close() {
            closed = true;
            main.removeCallbacks(poll);
            // Not from inside one of its own callbacks.
            main.post(w::destroy);
        }
    }

    // ---- helpers; the callers hold the lock ----

    private JSONObject item(String id) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r != null && App.str(r, "id").equals(id)) return r;
        }
        return null;
    }

    private JSONObject episode(int a, String show, int ep) {
        JSONObject probe = App.obj("a", a, "show", show);
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r != null && r.optInt("ep") == ep && sameShow(r, probe)) return r;
        }
        return null;
    }

    private void drop(String id) {
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject r = items.optJSONObject(i);
            if (r != null && App.str(r, "id").equals(id)) items.remove(i);
        }
    }

    private static boolean sameShow(JSONObject x, JSONObject y) {
        int a = x.optInt("a");
        return a > 0 ? a == y.optInt("a") : App.str(x, "show").equals(App.str(y, "show"));
    }

    // ---- saving ----

    private void changed() {
        main.post(() -> {
            App a = App.I;
            if (a != null && a.bottom != null) a.toBottom("onDownloads", json().toString());
        });
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

    private static void say(String text) {
        MainActivity m = App.I.top;
        if (m != null) m.showToast(text);
    }

    /** "Episode 01 - The Journey's End.mp4" */
    private static String fileName(JSONObject r) {
        int ep = r.optInt("ep");
        String title = App.str(r, "epTitle");
        String n = String.format(Locale.ROOT, "Episode %02d", ep);
        if (!title.isEmpty() && !title.matches("(?i)episode\\s*\\d+")) n += " - " + title;
        return safe(n) + ".mp4";
    }

    /** A file or folder name that's safe on any storage. */
    private static String safe(String s) {
        String out = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim().replaceAll("^\\.+|\\.+$", "");
        if (out.length() > 80) out = out.substring(0, 80).trim();
        return out.isEmpty() ? "Episode" : out;
    }

    private static JSONArray arr(String... values) {
        JSONArray a = new JSONArray();
        for (String v : values) a.put(v);
        return a;
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
