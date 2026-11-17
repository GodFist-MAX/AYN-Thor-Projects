package dev.anideck.app;

import org.json.JSONArray;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads the subtitle tracks of a downloaded MP4 from its index (the moov box). */
final class Mp4 {
    private static final int MAX_MOOV = 32 << 20;

    private Mp4() {
    }

    /** The 3GPP text tracks in file order, the order MediaPlayer lists them in: [{name, on}]. */
    static JSONArray subtitles(FileChannel ch) throws IOException {
        JSONArray out = new JSONArray();
        ByteBuffer moov = moov(ch);
        if (moov == null) return out;
        for (ByteBuffer trak : children(moov, "trak")) {
            // stsd: version and flags, entry count, then the first sample entry's size and type.
            ByteBuffer stsd = child(trak, "mdia", "minf", "stbl", "stsd");
            if (stsd == null || stsd.limit() < 16 || stsd.getInt(12) != fourcc("tx3g")) continue;
            ByteBuffer tkhd = child(trak, "tkhd");
            boolean on = tkhd != null && tkhd.limit() >= 4 && (tkhd.getInt(0) & 1) != 0;
            String name = name(child(trak, "udta", "name"));
            if (name.isEmpty()) name = language(child(trak, "mdia", "mdhd"));
            out.put(App.obj("name", name, "on", on));
        }
        return out;
    }

    private static ByteBuffer moov(FileChannel ch) throws IOException {
        long size = ch.size(), pos = 0;
        ByteBuffer head = ByteBuffer.allocate(16);
        while (pos + 8 <= size) {
            head.clear();
            int n = read(ch, head, pos);
            if (n < 8) return null;
            long len = head.getInt(0) & 0xFFFFFFFFL;
            int type = head.getInt(4), skip = 8;
            if (len == 1) {
                if (n < 16) return null;
                len = head.getLong(8);
                skip = 16;
            } else if (len == 0) {
                len = size - pos;
            }
            if (len < skip) return null;
            if (type == fourcc("moov")) {
                if (len - skip > MAX_MOOV) return null;
                ByteBuffer b = ByteBuffer.allocate((int) (len - skip));
                if (read(ch, b, pos + skip) < b.capacity()) return null;
                b.flip();
                return b;
            }
            pos += len;
        }
        return null;
    }

    private static int read(FileChannel ch, ByteBuffer b, long pos) throws IOException {
        int n = 0;
        while (b.hasRemaining()) {
            int r = ch.read(b, pos + n);
            if (r < 0) break;
            n += r;
        }
        return n;
    }

    /** The payloads of a box's children of one type; b holds the parent's payload. */
    private static List<ByteBuffer> children(ByteBuffer b, String type) {
        List<ByteBuffer> out = new ArrayList<>();
        int want = fourcc(type), pos = 0, end = b.limit();
        while (pos + 8 <= end) {
            long size = b.getInt(pos) & 0xFFFFFFFFL;
            int t = b.getInt(pos + 4), skip = 8;
            if (size == 1) {
                if (pos + 16 > end) break;
                size = b.getLong(pos + 8);
                skip = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < skip || pos + size > end) break;
            if (t == want) {
                ByteBuffer d = b.duplicate();
                d.limit((int) (pos + size)).position(pos + skip);
                out.add(d.slice());
            }
            pos += (int) size;
        }
        return out;
    }

    private static ByteBuffer child(ByteBuffer b, String... path) {
        for (String t : path) {
            List<ByteBuffer> c = children(b, t);
            if (c.isEmpty()) return null;
            b = c.get(0);
        }
        return b;
    }

    private static String name(ByteBuffer b) {
        if (b == null || b.limit() == 0) return "";
        byte[] a = new byte[b.limit()];
        b.duplicate().get(a);
        int off = 0;
        // QuickTime style puts a 16-bit length and a language before the text, ISO style a version and flags.
        if (a.length >= 4 && ((a[0] & 0xFF) << 8 | (a[1] & 0xFF)) == a.length - 4) off = 4;
        else while (off < a.length && a[off] == 0) off++;
        return new String(a, off, a.length - off, StandardCharsets.UTF_8).replace("\u0000", "").trim();
    }

    /** The track's language from mdhd ("jpn" → "Japanese"), for tracks without a name. */
    private static String language(ByteBuffer b) {
        if (b == null || b.limit() < 24) return "";
        int at = b.get(0) == 1 ? 32 : 20;
        if (b.limit() < at + 2) return "";
        int code = b.getShort(at) & 0x7FFF;
        String iso = new String(new char[] {(char) ((code >> 10 & 31) + 0x60), (char) ((code >> 5 & 31) + 0x60), (char) ((code & 31) + 0x60)});
        if (!iso.matches("[a-z]{3}") || "und".equals(iso)) return "";
        String name = Locale.forLanguageTag(iso).getDisplayLanguage(Locale.ENGLISH);
        return name.isEmpty() ? iso : name;
    }

    private static int fourcc(String s) {
        return s.charAt(0) << 24 | s.charAt(1) << 16 | s.charAt(2) << 8 | s.charAt(3);
    }
}
