package dev.crossbar.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Util {
    private Util() {}

    static final String UA = "CrossBarLauncher/1.0 (Android; AYN Thor)";
    private static final Pattern ARTICLE = Pattern.compile("^(.*?), (The|A|An)( - .*)?$");
    private static final Pattern TAG = Pattern.compile("[(\\[]([^)\\]]*)[)\\]]");

    static String randomHex(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return hex(b);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    static String sha1(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }

    static void skipFully(InputStream in, long n) throws IOException {
        byte[] scratch = null;
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (scratch == null) scratch = new byte[8192];
                int r = in.read(scratch, 0, (int) Math.min(scratch.length, n));
                if (r < 0) throw new EOFException();
                s = r;
            }
            n -= s;
        }
    }

    static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    static boolean writeText(File f, String s) {
        return writeBytes(f, s.getBytes(StandardCharsets.UTF_8));
    }

    static boolean writeBytes(File f, byte[] b) {
        File dir = f.getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(b);
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(f);
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

    static String pathDecode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static String queryDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    static String fileUrl(String path) {
        return "/img/file?p=" + enc(path);
    }

    static HttpURLConnection open(String url, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setRequestProperty("User-Agent", UA);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    static String httpGet(String url, int timeout) throws IOException {
        return httpGet(url, timeout, UA);
    }

    static String httpGet(String url, int timeout, String userAgent) throws IOException {
        HttpURLConnection c = open(url, timeout);
        c.setRequestProperty("User-Agent", userAgent);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    /** 1 = downloaded, 0 = not found, -1 = network or server error. */
    static int download(String url, File out, int timeout) {
        HttpURLConnection c = null;
        try {
            c = open(url, timeout);
            int code = c.getResponseCode();
            if (code == 404 || code == 403 || code == 410) return 0;
            if (code != 200) return -1;
            File tmp = new File(out.getPath() + ".part");
            File dir = out.getParentFile();
            if (dir != null) dir.mkdirs();
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            if (tmp.length() == 0) {
                tmp.delete();
                return 0;
            }
            return tmp.renameTo(out) ? 1 : -1;
        } catch (IOException e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static Bitmap toBitmap(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    static byte[] png(Bitmap b) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return bo.toByteArray();
    }

    static byte[] jpeg(Bitmap b, int q) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, q, bo);
        return bo.toByteArray();
    }

    static byte[] webp(Bitmap b, int q) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, bo);
        return bo.toByteArray();
    }

    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String mime(String name) {
        switch (ext(name)) {
            case "html": case "htm": return "text/html; charset=utf-8";
            case "js": case "mjs": return "application/javascript; charset=utf-8";
            case "css": return "text/css; charset=utf-8";
            case "json": return "application/json; charset=utf-8";
            case "txt": return "text/plain; charset=utf-8";
            case "svg": return "image/svg+xml";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            case "woff2": return "font/woff2";
            case "woff": return "font/woff";
            case "ttf": return "font/ttf";
            case "otf": return "font/otf";
            case "mp3": return "audio/mpeg";
            case "ogg": case "oga": case "opus": return "audio/ogg";
            case "wav": return "audio/wav";
            case "flac": return "audio/flac";
            case "m4a": case "aac": return "audio/mp4";
            case "mp4": case "m4v": return "video/mp4";
            case "webm": return "video/webm";
            case "mkv": return "video/x-matroska";
            case "mov": return "video/quicktime";
            case "3gp": return "video/3gpp";
            case "avi": return "video/x-msvideo";
            default: return "application/octet-stream";
        }
    }

    static boolean isImage(String name) {
        return mime(name).startsWith("image/");
    }

    static String cleanTitle(String base) {
        String s = base.replaceAll("\\([^)]*\\)", " ").replaceAll("\\[[^]]*]", " ").replace('_', ' ');
        s = s.replaceAll("\\s+", " ").trim();
        Matcher m = ARTICLE.matcher(s);
        if (m.matches()) s = m.group(2) + " " + m.group(1) + (m.group(3) != null ? m.group(3) : "");
        return s.isEmpty() ? base : s;
    }

    static String tags(String base) {
        Matcher m = TAG.matcher(base);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(m.group(1).trim());
        }
        return sb.toString();
    }

    static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
        }
    }

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) put(o, (String) kv[i], kv[i + 1]);
        return o;
    }

    static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static long le32(byte[] b, int o) {
        return (b[o] & 0xffL) | (b[o + 1] & 0xffL) << 8 | (b[o + 2] & 0xffL) << 16 | (b[o + 3] & 0xffL) << 24;
    }

    static int le16(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
    }

    static long le64(byte[] b, int o) {
        return le32(b, o) | le32(b, o + 4) << 32;
    }

    static String ascii(byte[] b, int o, int len) {
        int end = o;
        while (end < o + len && b[end] != 0) end++;
        return new String(b, o, end - o, StandardCharsets.ISO_8859_1).trim();
    }

    static String utf16(byte[] b, int o, int len) {
        int end = o;
        while (end + 1 < o + len && (b[end] != 0 || b[end + 1] != 0)) end += 2;
        return new String(b, o, end - o, StandardCharsets.UTF_16LE).trim();
    }
}
