package dev.grokbuild.coronamon;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A small WebSocket client (RFC 6455) over TLS: enough for xAI's streaming speech-to-text, which
 * takes binary audio frames and answers with JSON text frames. Android has no WebSocket client of
 * its own, and the build has no libraries for one.
 */
final class Ws implements Closeable {
    interface Listener {
        void text(String msg);

        /** The server closed the connection (code from its close frame) or it dropped (code -1). */
        void closed(int code, String reason);
    }

    private static final int MAX_FRAME = 4 << 20;

    private final SSLSocket sock;
    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom rnd = new SecureRandom();
    private volatile boolean closing;

    private Ws(SSLSocket sock, InputStream in, OutputStream out) {
        this.sock = sock;
        this.in = in;
        this.out = out;
    }

    /**
     * Connects to wss://host{path} with the key in an Authorization header, and starts a thread that
     * reads frames into the listener. A refused upgrade throws {@link Grok.Failure} with the HTTP status.
     */
    static Ws connect(String host, String path, String bearer, int timeoutMs, Listener l) throws IOException {
        Socket raw = new Socket();
        SSLSocket s = null;
        try {
            raw.connect(new InetSocketAddress(host, 443), timeoutMs);
            s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, 443, true);
            SSLParameters p = s.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            p.setServerNames(Collections.singletonList(new SNIHostName(host)));
            s.setSSLParameters(p);
            s.setSoTimeout(timeoutMs);
            s.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) {
                throw new SSLPeerUnverifiedException("certificate isn't for " + host);
            }
            InputStream in = new BufferedInputStream(s.getInputStream(), 16384);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 16384);
            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Authorization: Bearer " + bearer + "\r\n"
                    + "User-Agent: " + Util.UA + "\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();
            String head = readHead(in);
            int code = status(head);
            if (code != 101) {
                String body = "";
                try {
                    s.setSoTimeout(2000);
                    byte[] b = new byte[4096];
                    int n = in.read(b);
                    if (n > 0) body = new String(b, 0, n, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                }
                throw new Grok.Failure(code, Grok.explain(code, body));
            }
            String accept = header(head, "sec-websocket-accept");
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            String want = Base64.encodeToString(sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                    .getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
            if (!want.equals(accept)) throw new IOException("the server didn't speak WebSocket");
            s.setSoTimeout(30_000);
            Ws ws = new Ws(s, in, out);
            Thread t = new Thread(() -> ws.readLoop(l), "ws-read");
            t.setDaemon(true);
            t.start();
            return ws;
        } catch (IOException e) {
            closeQuietly(s != null ? s : raw);
            throw e;
        } catch (Exception e) {
            closeQuietly(s != null ? s : raw);
            throw new IOException(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    void sendText(String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        send(0x1, b, 0, b.length);
    }

    void sendBinary(byte[] b, int off, int len) throws IOException {
        send(0x2, b, off, len);
    }

    /** Sends a close frame and drops the connection. */
    @Override
    public void close() {
        if (closing) return;
        closing = true;
        try {
            send(0x8, new byte[]{0x03, (byte) 0xE8}, 0, 2);
        } catch (IOException ignored) {
        }
        closeQuietly(sock);
    }

    private synchronized void send(int op, byte[] data, int off, int len) throws IOException {
        byte[] head = new byte[14];
        int n = 0;
        head[n++] = (byte) (0x80 | op);
        if (len < 126) {
            head[n++] = (byte) (0x80 | len);
        } else if (len < 65536) {
            head[n++] = (byte) (0x80 | 126);
            head[n++] = (byte) (len >>> 8);
            head[n++] = (byte) len;
        } else {
            head[n++] = (byte) (0x80 | 127);
            for (int i = 7; i >= 0; i--) head[n++] = i >= 4 ? 0 : (byte) (len >>> (8 * i));
        }
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        System.arraycopy(mask, 0, head, n, 4);
        n += 4;
        byte[] body = new byte[len];
        for (int i = 0; i < len; i++) body[i] = (byte) (data[off + i] ^ mask[i & 3]);
        out.write(head, 0, n);
        out.write(body);
        out.flush();
    }

    private void readLoop(Listener l) {
        ByteArrayOutputStream text = null;
        try {
            while (true) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) throw new EOFException("connection closed");
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = (byteOf() << 8) | byteOf();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | byteOf();
                }
                if (len < 0 || len > MAX_FRAME) throw new IOException("frame too big");
                byte[] mask = (b1 & 0x80) != 0 ? bytes(4) : null;
                byte[] data = bytes((int) len);
                if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
                switch (op) {
                    case 0x0:
                        if (text == null) break;
                        text.write(data);
                        if (fin) {
                            l.text(new String(text.toByteArray(), StandardCharsets.UTF_8));
                            text = null;
                        }
                        break;
                    case 0x1:
                        if (fin) {
                            l.text(new String(data, StandardCharsets.UTF_8));
                        } else {
                            text = new ByteArrayOutputStream();
                            text.write(data);
                        }
                        break;
                    case 0x8: {
                        int code = data.length >= 2 ? ((data[0] & 0xFF) << 8) | (data[1] & 0xFF) : 1005;
                        String reason = data.length > 2 ? new String(data, 2, data.length - 2, StandardCharsets.UTF_8) : "";
                        if (!closing) {
                            closing = true;
                            try {
                                send(0x8, data, 0, Math.min(2, data.length));
                            } catch (IOException ignored) {
                            }
                        }
                        closeQuietly(sock);
                        l.closed(code, reason);
                        return;
                    }
                    case 0x9:
                        send(0xA, data, 0, data.length);
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            boolean asked = closing;
            closing = true;
            closeQuietly(sock);
            l.closed(asked ? 1000 : -1, asked ? "" : String.valueOf(e.getMessage()));
        }
    }

    private int byteOf() throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("connection closed");
        return b;
    }

    private byte[] bytes(int n) throws IOException {
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) throw new EOFException("connection closed");
            got += r;
        }
        return b;
    }

    /** The status line and headers, up to the blank line. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int c = in.read();
            if (c < 0) throw new EOFException("no reply to the WebSocket upgrade");
            b.write(c);
            if (b.size() > 16384) throw new IOException("reply headers too long");
            state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3)) ? state + 1
                    : c == '\r' ? 1 : 0;
        }
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static int status(String head) {
        String[] first = head.split("\r\n", 2)[0].split(" ");
        try {
            return first.length > 1 ? Integer.parseInt(first[1]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String header(String head, String name) {
        for (String line : head.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().toLowerCase(Locale.US).equals(name)) return line.substring(c + 1).trim();
        }
        return null;
    }

    private static void closeQuietly(Socket s) {
        try {
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
    }
}
