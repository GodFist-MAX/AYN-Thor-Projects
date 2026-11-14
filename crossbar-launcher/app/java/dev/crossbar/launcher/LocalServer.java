package dev.crossbar.launcher;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP/1.1 server on 127.0.0.1 that feeds the launcher UI. Everything except the
 * static UI files needs the per-process token, so other apps on the device can't read
 * the user's library, media or social data through it.
 */
final class LocalServer {
    final App app;
    final String token = Util.randomHex(16);
    final Api api;
    volatile int port;

    private ServerSocket ss;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cb-http");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, byte[]> assetCache = new ConcurrentHashMap<>();

    LocalServer(App app) {
        this.app = app;
        api = new Api(app);
    }

    void start() throws IOException {
        ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64);
        port = ss.getLocalPort();
        Thread t = new Thread(this::acceptLoop, "cb-http-accept");
        t.setDaemon(true);
        t.start();
        Log.i(App.TAG, "local server on port " + port);
    }

    String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private void acceptLoop() {
        while (!ss.isClosed()) {
            try {
                Socket s = ss.accept();
                pool.execute(() -> serve(s));
            } catch (IOException e) {
                if (ss.isClosed()) return;
            }
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(30000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream(), 8192);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            while (true) {
                Req req = Req.read(in);
                if (req == null) break;
                Resp resp;
                try {
                    resp = route(req);
                } catch (Throwable t) {
                    Log.w(App.TAG, "route failed: " + req.path, t);
                    resp = Resp.text(500, String.valueOf(t));
                }
                boolean keep = resp.write(out, req, req.keepAlive());
                out.flush();
                if (!keep) break;
            }
        } catch (IOException ignored) {
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean authorized(Req r) {
        if (token.equals(r.query.get("t"))) return true;
        if (token.equals(r.headers.get("x-token"))) return true;
        String c = r.headers.get("cookie");
        return c != null && c.contains("cbt=" + token);
    }

    private Resp route(Req r) throws Exception {
        String p = r.path;
        if (p.equals("/") || p.equals("/app") || p.equals("/app/")) {
            return Resp.redirect("/app/index.html" + (r.rawQuery != null ? "?" + r.rawQuery : ""));
        }
        if (p.startsWith("/app/")) {
            Resp res = asset(p.substring(5));
            if (token.equals(r.query.get("t"))) {
                res.header("Set-Cookie", "cbt=" + token + "; Path=/; HttpOnly; SameSite=Strict");
            }
            return res;
        }
        if (!authorized(r)) return Resp.text(403, "forbidden");
        return api.handle(r);
    }

    private Resp asset(String rel) {
        if (rel.isEmpty() || rel.contains("..")) return Resp.text(400, "bad path");
        File dev = new File(Store.EXT_DIR, "dev-www/" + rel);
        if (App.I.store.bool("devMode") && new File(Store.EXT_DIR, "dev-www/index.html").isFile() && dev.isFile()) {
            Resp res = Resp.file(dev, Util.mime(rel));
            res.header("Cache-Control", "no-cache");
            return res;
        }
        byte[] b = assetCache.get(rel);
        if (b == null) {
            try (InputStream is = app.getAssets().open("www/" + rel)) {
                b = Util.readAll(is);
            } catch (IOException e) {
                return Resp.text(404, "not found");
            }
            if (b.length < 384 * 1024) assetCache.put(rel, b);
        }
        Resp res = Resp.bytes(200, Util.mime(rel), b);
        res.header("Cache-Control", rel.matches(".*\\.(html|js|css|json)$") ? "no-cache" : "max-age=604800");
        return res;
    }

    static final class Req {
        String method;
        String path;
        String rawQuery;
        String version;
        final Map<String, String> headers = new HashMap<>();
        final Map<String, String> query = new HashMap<>();
        byte[] body = new byte[0];

        static Req read(InputStream in) throws IOException {
            String line = readLine(in);
            while (line != null && line.isEmpty()) line = readLine(in);
            if (line == null) return null;
            String[] parts = line.split(" ");
            if (parts.length < 2) return null;
            Req r = new Req();
            r.method = parts[0].toUpperCase(Locale.ROOT);
            r.version = parts.length > 2 ? parts[2] : "HTTP/1.0";
            String target = parts[1];
            int q = target.indexOf('?');
            r.path = Util.pathDecode(q >= 0 ? target.substring(0, q) : target);
            r.rawQuery = q >= 0 ? target.substring(q + 1) : null;
            if (r.rawQuery != null) {
                for (String kv : r.rawQuery.split("&")) {
                    if (kv.isEmpty()) continue;
                    int e = kv.indexOf('=');
                    String k = Util.queryDecode(e >= 0 ? kv.substring(0, e) : kv);
                    String v = e >= 0 ? Util.queryDecode(kv.substring(e + 1)) : "";
                    r.query.put(k, v);
                }
            }
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
            }
            String cl = r.headers.get("content-length");
            if (cl != null) {
                int n = Integer.parseInt(cl.trim());
                if (n > 0 && n < 8_000_000) {
                    r.body = new byte[n];
                    Util.readFully(in, r.body);
                }
            }
            return r;
        }

        boolean keepAlive() {
            String c = headers.get("connection");
            if ("HTTP/1.1".equals(version)) return c == null || !c.equalsIgnoreCase("close");
            return c != null && c.equalsIgnoreCase("keep-alive");
        }

        String q(String k) {
            String v = query.get(k);
            return v == null ? "" : v;
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') return sb.toString();
                if (c != '\r') sb.append((char) c);
                if (sb.length() > 16384) throw new IOException("header line too long");
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
    }

    interface Opener {
        InputStream open(long offset) throws IOException;
    }

    static final class Resp {
        int status = 200;
        String type;
        byte[] bytes;
        InputStream stream;
        long length = -1;
        final LinkedHashMap<String, String> headers = new LinkedHashMap<>();

        static Resp bytes(int status, String type, byte[] b) {
            Resp r = new Resp();
            r.status = status;
            r.type = type;
            r.bytes = b;
            return r;
        }

        static Resp text(int status, String s) {
            return bytes(status, "text/plain; charset=utf-8", s.getBytes(StandardCharsets.UTF_8));
        }

        static Resp json(Object o) {
            Resp r = bytes(200, "application/json; charset=utf-8", String.valueOf(o).getBytes(StandardCharsets.UTF_8));
            r.header("Cache-Control", "no-store");
            return r;
        }

        static Resp redirect(String location) {
            Resp r = text(302, "");
            r.header("Location", location);
            return r;
        }

        static Resp stream(InputStream in, long len, String type) {
            Resp r = new Resp();
            r.stream = in;
            r.length = len;
            r.type = type;
            return r;
        }

        static Resp file(File f, String type) {
            try {
                return stream(new FileInputStream(f), f.length(), type);
            } catch (IOException e) {
                return text(404, "not found");
            }
        }

        /** Serves a byte range of a seekable source; used for audio and video so seeking works. */
        static Resp ranged(Req r, long total, String type, Opener opener) throws IOException {
            long start = 0;
            long end = total - 1;
            boolean partial = false;
            String range = r.headers.get("range");
            if (range != null && range.startsWith("bytes=") && total > 0) {
                String spec = range.substring(6).split(",")[0].trim();
                int dash = spec.indexOf('-');
                if (dash >= 0) {
                    String a = spec.substring(0, dash).trim();
                    String b = spec.substring(dash + 1).trim();
                    if (a.isEmpty()) {
                        start = Math.max(0, total - Long.parseLong(b));
                    } else {
                        start = Long.parseLong(a);
                        if (!b.isEmpty()) end = Math.min(Long.parseLong(b), total - 1);
                    }
                    if (start > end || start >= total) {
                        Resp x = text(416, "range not satisfiable");
                        x.header("Content-Range", "bytes */" + total);
                        return x;
                    }
                    partial = true;
                }
            }
            long len = end - start + 1;
            Resp res = stream(new Limited(opener.open(start), len), len, type);
            res.status = partial ? 206 : 200;
            res.header("Accept-Ranges", "bytes");
            if (partial) res.header("Content-Range", "bytes " + start + "-" + end + "/" + total);
            return res;
        }

        Resp header(String k, String v) {
            headers.put(k, v);
            return this;
        }

        /** Writes the response; returns whether the connection can be kept alive. */
        boolean write(OutputStream out, Req req, boolean keep) throws IOException {
            try {
                long len = bytes != null ? bytes.length : length;
                if (len < 0) keep = false;
                StringBuilder sb = new StringBuilder(256);
                sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
                if (type != null) sb.append("Content-Type: ").append(type).append("\r\n");
                if (len >= 0) sb.append("Content-Length: ").append(len).append("\r\n");
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
                sb.append("Connection: ").append(keep ? "keep-alive" : "close").append("\r\n\r\n");
                out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
                if (!"HEAD".equals(req.method)) {
                    if (bytes != null) {
                        out.write(bytes);
                    } else if (stream != null) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = stream.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
                return keep;
            } finally {
                closeQuietly(stream);
            }
        }

        private static void closeQuietly(Closeable c) {
            if (c == null) return;
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }

        private static String reason(int s) {
            switch (s) {
                case 200: return "OK";
                case 206: return "Partial Content";
                case 302: return "Found";
                case 400: return "Bad Request";
                case 403: return "Forbidden";
                case 404: return "Not Found";
                case 416: return "Range Not Satisfiable";
                default: return s >= 500 ? "Server Error" : "OK";
            }
        }
    }

    static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long len) {
            super(in);
            left = len;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }
}
