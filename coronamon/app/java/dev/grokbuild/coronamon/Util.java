package dev.grokbuild.coronamon;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Util {
    private Util() {
    }

    static final String UA = "Coronamon/1.0 (Android; AYN Thor)";

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    static boolean writeText(File f, String s) {
        File dir = f.getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(f);
    }

    static void appendLine(File f, String line) {
        File dir = f.getParentFile();
        if (dir != null) dir.mkdirs();
        try (OutputStream o = new FileOutputStream(f, true)) {
            o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    static void copy(File from, File to) throws IOException {
        File dir = to.getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = new File(to.getPath() + ".part");
        try (InputStream in = new FileInputStream(from); OutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        if (!tmp.renameTo(to)) throw new IOException("rename failed: " + to);
    }

    static JSONObject readJson(File f) {
        String s = readText(f);
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    static JSONObject parse(String s) {
        if (s == null) return new JSONObject();
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static JSONArray parseArray(String s) {
        if (s == null) return new JSONArray();
        try {
            return new JSONArray(s);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v == null ? JSONObject.NULL : v);
        } catch (JSONException ignored) {
        }
    }

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) put(o, (String) kv[i], kv[i + 1]);
        return o;
    }

    static HttpURLConnection open(String url, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setRequestProperty("User-Agent", UA);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    static String httpGet(String url, int timeout, String accept) throws IOException {
        HttpURLConnection c = open(url, timeout);
        if (accept != null) c.setRequestProperty("Accept", accept);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + url);
            try (InputStream in = c.getInputStream()) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    interface Progress {
        void at(long done, long total);
    }

    static void download(String url, File out, int timeout, Progress p) throws IOException {
        HttpURLConnection c = open(url, timeout);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long total = c.getContentLengthLong();
            File tmp = new File(out.getPath() + ".part");
            File dir = out.getParentFile();
            if (dir != null) dir.mkdirs();
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                long done = 0, last = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    done += n;
                    if (p != null && done - last > 512 * 1024) {
                        last = done;
                        p.at(done, total);
                    }
                }
            }
            if (!tmp.renameTo(out)) throw new IOException("rename failed");
        } finally {
            c.disconnect();
        }
    }

    static Bitmap toBitmap(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    static String pngDataUrl(Bitmap b) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    static String stamp(long ms) {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(ms));
    }

    static String clock(long ms) {
        return new SimpleDateFormat("MMM d HH:mm", Locale.US).format(new Date(ms));
    }

    /** Compares dotted version strings numerically, ignoring a leading v and any suffix letters. */
    static int compareVersions(String a, String b) {
        String[] x = clean(a).split("[^0-9]+"), y = clean(b).split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length && !x[i].isEmpty() ? parseLong(x[i]) : 0;
            long q = i < y.length && !y[i].isEmpty() ? parseLong(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static String clean(String v) {
        if (v == null) return "";
        v = v.trim();
        while (!v.isEmpty() && !Character.isDigit(v.charAt(0))) v = v.substring(1);
        return v;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.length() > 15 ? s.substring(0, 15) : s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }
}
