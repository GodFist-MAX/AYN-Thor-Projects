package dev.anideck.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import dev.anideck.app.ext.Host;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Downloads one queued episode: finds its video through the source, saves it to Movies/AniDeck and its subtitles next to the app. */
final class Downloader {
    private static final int PARALLEL = 4, ATTEMPTS = 4, MAX_CANDIDATES = 5;
    private static final long OFFLINE_WAIT_MS = 10 * 60_000L;

    static final class Canceled extends IOException {
        Canceled() {
            super("canceled");
        }
    }

    /** A failure worth showing as is. */
    static final class Failure extends IOException {
        Failure(String message) {
            super(message);
        }
    }

    interface Listener {
        void progress(JSONObject job, int percent, String note);
    }

    private final Context app;
    private final ContentResolver cr;
    private final Downloads dl;
    private final JSONObject job;
    private final String id;
    private final Listener listener;
    private final OkHttpClient client;
    private final Map<String, byte[]> keys = new ConcurrentHashMap<>();
    private Headers headers = new Headers.Builder().build();
    private long got, size, lastNote;
    private String quality = "";

    Downloader(Context c, JSONObject job, Listener listener) {
        app = c;
        cr = c.getContentResolver();
        dl = Downloads.get(c);
        this.job = job;
        this.listener = listener;
        id = App.str(job, "id");
        client = Host.getClient();
    }

    void run() {
        File work = dl.workDir(id);
        try {
            if (Environment.getExternalStorageDirectory().getUsableSpace() < 400L << 20) throw new Failure("Not enough space on the Thor");
            dl.update(id, true, "state", "preparing", "note", "Finding a video", "error", "");
            listener.progress(job, -1, "Finding a video");
            List<JSONObject> tried = new ArrayList<>();
            IOException last = null;
            for (JSONObject stream : candidates()) {
                check();
                tried.add(stream);
                try {
                    save(stream, work);
                    last = null;
                    break;
                } catch (Canceled | Failure e) {
                    throw e;
                } catch (IOException e) {
                    last = e;
                    Log.w(App.TAG, "download from " + App.str(stream, "title") + " failed: " + e);
                    if (got > 0) break; // it had started; another server would start over
                    dl.update(id, false, "note", "That server didn't work, trying another");
                }
            }
            if (tried.isEmpty()) throw new Failure("The source has no video for this episode");
            if (last != null) throw last;
        } catch (Canceled e) {
            Log.i(App.TAG, "download canceled");
        } catch (IOException | RuntimeException e) {
            String msg = e instanceof Failure ? e.getMessage() : describe(e);
            Log.w(App.TAG, "download failed: " + e);
            dl.update(id, true, "state", "failed", "error", msg, "note", "");
        } finally {
            Downloads.deleteTree(work);
        }
    }

    // ---- choosing the video ----

    /**
     * Videos closest to the download quality setting first, on the server that was playing when Download was
     * pressed (else the one with the source's preferred video), then the other servers.
     */
    private Iterable<JSONObject> candidates() throws IOException {
        JSONObject args = App.obj("source", App.str(job, "source"), "episode", job.optJSONObject("episode"));
        JSONObject best;
        try {
            best = Api.ext("best", args);
        } catch (IOException e) {
            throw new Failure(e.getMessage());
        }
        JSONArray hosters = best.optJSONArray("hosters");
        JSONObject stream = best.optJSONObject("stream");
        if (stream == null) throw new Failure(App.str(best, "error").isEmpty() ? "None of the servers gave a video" : App.str(best, "error"));
        JSONObject pick = job.optJSONObject("pick");
        String bestKey = App.str(stream, "key");
        int n = hosters == null ? 0 : hosters.length();
        int home = -1;
        for (int i = 0; pick != null && home < 0 && i < n; i++) {
            if (App.str(pick, "hoster").equals(App.str(hosters.optJSONObject(i), "name"))) home = i;
        }
        for (int i = 0; home < 0 && i < n; i++) if (hasVideo(hosters.optJSONObject(i), bestKey)) home = i;
        int max = maxHeight();
        // Resolved streams, or {hoster key, video key} pairs resolved when their turn comes.
        List<Object> out = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                if ((pass == 0) != (i == home)) continue;
                JSONObject h = hosters.optJSONObject(i);
                JSONArray vs = h == null ? null : h.optJSONArray("videos");
                List<JSONObject> sorted = new ArrayList<>();
                for (int k = 0; vs != null && k < vs.length(); k++) if (vs.optJSONObject(k) != null) sorted.add(vs.optJSONObject(k));
                // Stable, so videos that fit equally well keep the source's order.
                sorted.sort((x, y) -> fit(y, max) - fit(x, max));
                for (JSONObject v : sorted) {
                    String vk = App.str(v, "key");
                    out.add(vk.equals(bestKey) ? stream : new String[] {App.str(h, "key"), vk});
                }
            }
        }
        if (!out.contains(stream)) out.add(0, stream);
        return () -> new Iterator<JSONObject>() {
            int i = 0, used = 0;
            JSONObject next;

            @Override public boolean hasNext() {
                while (next == null && i < out.size() && used < MAX_CANDIDATES) {
                    Object o = out.get(i++);
                    if (o instanceof JSONObject) next = (JSONObject) o;
                    else try {
                        next = Api.ext("resolve", App.obj("video", ((String[]) o)[1]));
                    } catch (IOException e) {
                        Log.w(App.TAG, "resolve for download: " + e.getMessage());
                    }
                    if (next != null && App.str(next, "url").isEmpty()) next = null;
                    if (next != null) used++;
                }
                return next != null;
            }

            @Override public JSONObject next() {
                hasNext();
                JSONObject n = next;
                next = null;
                return n;
            }
        };
    }

    private static boolean hasVideo(JSONObject hoster, String key) {
        JSONArray vs = hoster == null ? null : hoster.optJSONArray("videos");
        for (int k = 0; vs != null && k < vs.length(); k++) if (key.equals(App.str(vs.optJSONObject(k), "key"))) return true;
        return false;
    }

    /** Highest for the tallest video within the setting, then unlabeled ones, then the smallest of those above it. */
    private static int fit(JSONObject video, int max) {
        int h = height(video);
        if (h <= 0) return 50000;
        return h <= max ? 100000 + h : 50000 - h;
    }

    private static int height(JSONObject video) {
        int r = video.optInt("resolution");
        if (r > 0) return r;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3,4})p").matcher(App.str(video, "title"));
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ---- saving ----

    private void save(JSONObject stream, File work) throws IOException {
        Headers.Builder hb = new Headers.Builder();
        JSONObject hs = stream.optJSONObject("headers");
        if (hs != null) for (Iterator<String> it = hs.keys(); it.hasNext(); ) {
            String k = it.next();
            try {
                hb.set(k, hs.optString(k));
            } catch (IllegalArgumentException ignored) {
                // A header OkHttp won't send (odd characters); the video may still work without it.
            }
        }
        headers = hb.build();
        got = size = 0;
        quality = label(stream);
        String url = App.str(stream, "url");
        Uri out;
        if (url.toLowerCase(Locale.ROOT).contains(".m3u8") || isHlsResponse(url)) out = saveHls(url, work);
        else out = saveDirect(url);
        if (out == null) return;
        JSONArray subs = saveSubtitles(stream.optJSONArray("subtitles"));
        String path = pathOf(out);
        long bytes = sizeOf(out);
        dl.update(id, true, "state", "done", "note", "", "uri", out.toString(), "file", path == null ? "" : path,
                "size", bytes, "got", bytes, "subs", subs, "quality", quality, "server", App.str(stream, "title"),
                "finished", System.currentTimeMillis());
        Log.i(App.TAG, "downloaded " + App.str(job, "show") + " episode " + job.optDouble("ep") + " (" + quality + ")");
        listener.progress(job, 100, "Done");
    }

    /** Sources sometimes hide HLS behind an address without .m3u8. */
    private boolean isHlsResponse(String url) throws IOException {
        Request r = new Request.Builder().url(url).headers(headers).header("Range", "bytes=0-1023").build();
        try (Response resp = call(r)) {
            String type = resp.header("Content-Type", "");
            if (type != null && type.toLowerCase(Locale.ROOT).contains("mpegurl")) return true;
            ResponseBody b = resp.body();
            byte[] head = new byte[16];
            int n = b.byteStream().read(head);
            return n > 0 && new String(head, 0, n, StandardCharsets.ISO_8859_1).trim().startsWith("#EXTM3U");
        }
    }

    private Uri saveHls(String url, File work) throws IOException {
        String[] text = fetchText(url);
        if (!Hls.looksLike(text[0])) throw new IOException("not a playlist");
        Hls.Playlist p = Hls.parse(text[0], text[1]);
        Hls.Playlist audio = null;
        if (p.isMaster()) {
            Hls.Variant v = Hls.pick(p.variants, maxHeight());
            if (v.height > 0) quality = v.height + "p";
            Hls.Rendition a = Hls.audioFor(p, v);
            String[] vt = fetchText(v.url);
            Hls.Playlist media = Hls.parse(vt[0], vt[1]);
            if (a != null) {
                String[] at = fetchText(a.url);
                audio = Hls.parse(at[0], at[1]);
            }
            p = media;
        }
        if (p.segments.isEmpty()) throw new IOException("empty playlist");
        for (Hls.Segment s : p.segments) {
            if (s.key != null && !"AES-128".equals(s.key.method)) throw new Failure("This video is copy-protected, so it can't be downloaded. Try another server");
        }
        work.mkdirs();
        boolean fmp4 = p.segments.get(0).map != null;
        File video = new File(work, fmp4 ? "video.mp4" : "video.ts");
        int total = p.segments.size() + (audio == null ? 0 : audio.segments.size());
        fetchSegments(p.segments, video, 0, total);
        List<File> inputs = new ArrayList<>(Arrays.asList(video));
        if (audio != null && !audio.segments.isEmpty()) {
            File a = new File(work, audio.segments.get(0).map != null ? "audio.mp4" : "audio.ts");
            fetchSegments(audio.segments, a, p.segments.size(), total);
            inputs.add(a);
        }
        check();
        note("Saving the episode", 99);
        String name = Downloads.fileName(job);
        Uri out = create(name + ".mp4", "video/mp4");
        try (ParcelFileDescriptor fd = cr.openFileDescriptor(out, "rw")) {
            if (fd == null) throw new IOException("no file");
            Remux.toMp4(inputs, fd.getFileDescriptor(), f -> !dl.isCanceled(id));
        } catch (IOException e) {
            delete(out);
            check();
            // Android's MP4 muxer doesn't take every codec; the stream itself plays in AniDeck as it is.
            if (inputs.size() > 1 || fmp4) throw e;
            Log.w(App.TAG, "keeping the TS: " + e.getMessage());
            out = create(name + ".ts", "video/mp2t");
            try (InputStream in = new FileInputStream(video); OutputStream o = cr.openOutputStream(out)) {
                if (o == null) throw new IOException("no file");
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            } catch (IOException e2) {
                delete(out);
                throw e2;
            }
        }
        publish(out);
        return out;
    }

    private void fetchSegments(List<Hls.Segment> segs, File file, int doneBefore, int total) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        Deque<Future<byte[]>> window = new ArrayDeque<>();
        int submitted = 0;
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(file), 1 << 20)) {
            Hls.Segment lastMap = null;
            for (int i = 0; i < segs.size(); i++) {
                while (submitted < segs.size() && submitted < i + PARALLEL) {
                    Hls.Segment s = segs.get(submitted++);
                    window.add(pool.submit(() -> segment(s)));
                }
                byte[] data;
                try {
                    data = window.poll().get();
                } catch (ExecutionException e) {
                    Throwable c = e.getCause();
                    if (c instanceof IOException) throw (IOException) c;
                    throw new IOException(c);
                } catch (InterruptedException e) {
                    throw new Canceled();
                }
                Hls.Segment s = segs.get(i);
                if (s.map != null && s.map != lastMap) {
                    o.write(fetchBytes(s.map.url, s.map.offset, s.map.length));
                    lastMap = s.map;
                }
                o.write(data);
                got += data.length;
                int done = doneBefore + i + 1;
                size = Math.max(got, got * total / Math.max(1, done));
                int pct = (int) Math.min(98, done * 100L / total);
                if (SystemClock.uptimeMillis() - lastNote > 900 || i == segs.size() - 1) {
                    lastNote = SystemClock.uptimeMillis();
                    dl.update(id, false, "state", "downloading", "note", "", "got", got, "size", size, "quality", quality);
                    listener.progress(job, pct, null);
                }
                check();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private byte[] segment(Hls.Segment s) throws IOException {
        byte[] data = fetchBytes(s.url, s.offset, s.length);
        if (s.key == null) return data;
        byte[] key = keys.get(s.key.url);
        if (key == null) {
            key = fetchBytes(s.key.url, -1, -1);
            if (key.length != 16) throw new IOException("bad key");
            keys.put(s.key.url, key);
        }
        byte[] iv = s.key.iv;
        if (iv == null) {
            iv = new byte[16];
            for (int i = 0; i < 8; i++) iv[15 - i] = (byte) (s.sequence >>> (8 * i));
        }
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IOException("decrypt: " + e.getMessage());
        }
    }

    private Uri saveDirect(String url) throws IOException {
        String path = Uri.parse(url).getPath();
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        String ext = lower.endsWith(".mkv") ? ".mkv" : lower.endsWith(".webm") ? ".webm" : ".mp4";
        String mime = ".mkv".equals(ext) ? "video/x-matroska" : ".webm".equals(ext) ? "video/webm" : "video/mp4";
        Uri out = null;
        int failures = 0;
        try {
            while (true) {
                Request.Builder rb = new Request.Builder().url(url).headers(headers);
                if (got > 0) rb.header("Range", "bytes=" + got + "-");
                try (Response resp = call(rb.build())) {
                    if (got > 0 && resp.code() != 206) throw new IOException("the server can't continue the download");
                    ResponseBody body = resp.body();
                    if (out == null) {
                        String type = resp.header("Content-Type", "");
                        if (type != null && type.startsWith("text/")) throw new IOException("got a web page instead of a video");
                        if (type != null && type.contains("matroska")) {
                            ext = ".mkv";
                            mime = "video/x-matroska";
                        } else if (type != null && type.contains("webm")) {
                            ext = ".webm";
                            mime = "video/webm";
                        }
                        out = create(Downloads.fileName(job) + ext, mime);
                        long len = body.contentLength();
                        size = len > 0 ? len : 0;
                    }
                    try (ParcelFileDescriptor fd = cr.openFileDescriptor(out, "rw");
                         FileOutputStream fo = new FileOutputStream(fd.getFileDescriptor());
                         InputStream in = body.byteStream()) {
                        FileChannel ch = fo.getChannel();
                        ch.position(got);
                        byte[] buf = new byte[1 << 16];
                        for (int n; (n = in.read(buf)) > 0; ) {
                            fo.write(buf, 0, n);
                            got += n;
                            if (SystemClock.uptimeMillis() - lastNote > 900) {
                                lastNote = SystemClock.uptimeMillis();
                                dl.update(id, false, "state", "downloading", "note", "", "got", got, "size", Math.max(size, got), "quality", quality);
                                listener.progress(job, size > 0 ? (int) Math.min(98, got * 100 / size) : -1, null);
                                check();
                            }
                        }
                    }
                    if (size > 0 && got < size) throw new IOException("the download broke off");
                    break;
                } catch (Canceled | Failure e) {
                    throw e;
                } catch (IOException e) {
                    if (got == 0 || ++failures > ATTEMPTS) throw e;
                    waitForNet();
                }
            }
            publish(out);
            return out;
        } catch (IOException | RuntimeException e) {
            if (out != null) delete(out);
            throw e;
        }
    }

    /** Each subtitle track as a file of its own: {name, lang, path, mime}. */
    private JSONArray saveSubtitles(JSONArray tracks) {
        JSONArray out = new JSONArray();
        if (tracks == null || tracks.length() == 0) return out;
        File dir = dl.subsDir(id);
        dir.mkdirs();
        for (int i = 0; i < tracks.length() && i < 24; i++) {
            JSONObject t = tracks.optJSONObject(i);
            String url = App.str(t, "url"), lang = App.str(t, "lang");
            if (url.isEmpty()) continue;
            try {
                byte[] data = fetchBytes(url, -1, -1);
                String head = new String(data, 0, Math.min(data.length, 512), StandardCharsets.UTF_8).trim().replace("\uFEFF", "");
                String ext, mime;
                if (head.startsWith("WEBVTT")) {
                    ext = "vtt";
                    mime = "text/vtt";
                } else if (head.startsWith("[Script Info]") || head.contains("[V4+ Styles]")) {
                    ext = "ass";
                    mime = "text/x-ssa";
                } else {
                    ext = "srt";
                    mime = "application/x-subrip";
                }
                File f = new File(dir, i + "." + ext);
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(data);
                }
                out.put(App.obj("name", lang.isEmpty() ? "Subtitles " + (i + 1) : lang, "lang", lang, "path", f.getPath(), "mime", mime));
            } catch (IOException e) {
                Log.w(App.TAG, "subtitle " + lang + ": " + e.getMessage());
            }
        }
        return out;
    }

    // ---- network ----

    /** {text, final URL} */
    private String[] fetchText(String url) throws IOException {
        Request r = new Request.Builder().url(url).headers(headers).build();
        for (int attempt = 1; ; attempt++) {
            try (Response resp = call(r)) {
                return new String[] {resp.body().string(), resp.request().url().toString()};
            } catch (Canceled | Failure e) {
                throw e;
            } catch (IOException e) {
                if (attempt >= ATTEMPTS || isHttp4xx(e)) throw e;
                waitForNet();
            }
        }
    }

    private byte[] fetchBytes(String url, long offset, long length) throws IOException {
        Request.Builder rb = new Request.Builder().url(url).headers(headers);
        if (offset >= 0 && length > 0) rb.header("Range", "bytes=" + offset + "-" + (offset + length - 1));
        Request r = rb.build();
        for (int attempt = 1; ; attempt++) {
            check();
            try (Response resp = call(r)) {
                return resp.body().bytes();
            } catch (Canceled | Failure e) {
                throw e;
            } catch (IOException e) {
                if (attempt >= ATTEMPTS || isHttp4xx(e)) throw e;
                waitForNet();
                SystemClock.sleep(attempt * 1500L);
            }
        }
    }

    private Response call(Request r) throws IOException {
        Response resp = client.newCall(r).execute();
        if (!resp.isSuccessful()) {
            int code = resp.code();
            resp.close();
            throw new IOException("HTTP " + code);
        }
        return resp;
    }

    private static boolean isHttp4xx(IOException e) {
        String m = e.getMessage();
        return m != null && m.matches("HTTP 4\\d\\d") && !"HTTP 429".equals(m) && !"HTTP 408".equals(m);
    }

    /** A dropped connection waits for Wi-Fi to come back instead of failing the download. */
    private void waitForNet() throws IOException {
        long until = SystemClock.uptimeMillis() + OFFLINE_WAIT_MS;
        boolean said = false;
        while (App.I != null && !App.I.net.online()) {
            check();
            if (SystemClock.uptimeMillis() > until) throw new Failure("The internet connection dropped");
            if (!said) {
                said = true;
                dl.update(id, false, "note", "Waiting for the internet");
                listener.progress(job, -1, "Waiting for the internet");
            }
            SystemClock.sleep(2000);
        }
        if (said) dl.update(id, false, "note", "");
    }

    private void check() throws Canceled {
        if (dl.isCanceled(id) || Thread.currentThread().isInterrupted()) throw new Canceled();
    }

    // ---- MediaStore ----

    private Uri create(String name, String mime) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + Downloads.FOLDER + "/"
                + Downloads.safe(App.str(job, "show")) + "/");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u;
        try {
            u = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        } catch (RuntimeException e) {
            throw new Failure("Couldn't save to Movies on this Thor");
        }
        if (u == null) throw new Failure("Couldn't save to Movies on this Thor");
        return u;
    }

    private void publish(Uri u) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(u, v, null, null);
    }

    private void delete(Uri u) {
        try {
            cr.delete(u, null, null);
        } catch (RuntimeException ignored) {
        }
    }

    private String pathOf(Uri u) {
        try (Cursor c = cr.query(u, new String[] {MediaStore.MediaColumns.DATA}, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private long sizeOf(Uri u) {
        try (ParcelFileDescriptor fd = cr.openFileDescriptor(u, "r")) {
            return fd == null ? got : fd.getStatSize();
        } catch (IOException | RuntimeException e) {
            return got;
        }
    }

    // ---- helpers ----

    private void note(String text, int pct) {
        dl.update(id, false, "note", text);
        listener.progress(job, pct, text);
    }

    private static int maxHeight() {
        String q = App.I.prefs.getString("dlQuality", "1080");
        try {
            return Integer.parseInt(q);
        } catch (NumberFormatException e) {
            return 1080;
        }
    }

    private static String label(JSONObject stream) {
        int h = height(stream);
        return h > 0 ? h + "p" : "";
    }

    private static String describe(Throwable e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.startsWith("HTTP 403") || m.startsWith("HTTP 401")) return "The video server refused the download. Try again later";
        if (m.startsWith("HTTP 404") || m.startsWith("HTTP 410")) return "The video isn't on the server anymore. Try again later";
        if (m.startsWith("HTTP ")) return "The video server had a problem (" + m.substring(5) + ")";
        if (e instanceof java.net.UnknownHostException) return "Couldn't reach the video server";
        if (e instanceof java.net.SocketTimeoutException) return "The video server stopped answering";
        if (e instanceof javax.net.ssl.SSLException) return "Couldn't make a secure connection to the video server";
        if (m.startsWith("remux")) return "The episode downloaded but couldn't be saved as MP4";
        return "The download failed" + (m.isEmpty() ? "" : " (" + m + ")");
    }
}
