package dev.anideck.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

/** Just enough of HLS (RFC 8216) to download a stream: master playlists, media playlists, AES-128 keys and init segments. */
final class Hls {
    private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");

    static final class Variant {
        String url, audio, resolution;
        long bandwidth;
        int height;
    }

    static final class Rendition {
        String type, group, url, language, name;
        boolean isDefault;
    }

    static final class Key {
        String method, url;
        byte[] iv;
    }

    static final class Segment {
        String url;
        double duration;
        long sequence, offset = -1, length = -1;
        Key key;
        /** The init section (EXT-X-MAP) this segment needs, or null. */
        Segment map;
    }

    static final class Playlist {
        final List<Variant> variants = new ArrayList<>();
        final List<Rendition> renditions = new ArrayList<>();
        final List<Segment> segments = new ArrayList<>();

        boolean isMaster() {
            return !variants.isEmpty();
        }

        double duration() {
            double d = 0;
            for (Segment s : segments) d += s.duration;
            return d;
        }
    }

    private Hls() {
    }

    static boolean looksLike(String text) {
        return text != null && text.trim().startsWith("#EXTM3U");
    }

    static Playlist parse(String text, String base) {
        Playlist p = new Playlist();
        HttpUrl baseUrl = HttpUrl.parse(base);
        Variant pending = null;
        Key key = null;
        Segment map = null;
        double duration = 0;
        long sequence = 0, nextOffset = 0, length = -1, offset = -1;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                Map<String, String> a = attrs(line);
                pending = new Variant();
                pending.bandwidth = parseLong(a.get("BANDWIDTH"));
                pending.audio = a.get("AUDIO");
                pending.resolution = a.get("RESOLUTION");
                if (pending.resolution != null) {
                    String[] wh = pending.resolution.toLowerCase(Locale.ROOT).split("x");
                    if (wh.length == 2) pending.height = (int) parseLong(wh[1]);
                }
            } else if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> a = attrs(line);
                Rendition r = new Rendition();
                r.type = a.get("TYPE");
                r.group = a.get("GROUP-ID");
                r.language = a.get("LANGUAGE");
                r.name = a.get("NAME");
                r.isDefault = "YES".equals(a.get("DEFAULT"));
                String u = a.get("URI");
                r.url = u == null ? null : resolve(baseUrl, u);
                p.renditions.add(r);
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                sequence = parseLong(line.substring(line.indexOf(':') + 1));
            } else if (line.startsWith("#EXT-X-KEY:")) {
                Map<String, String> a = attrs(line);
                String method = a.get("METHOD");
                if (method == null || "NONE".equals(method)) {
                    key = null;
                } else {
                    key = new Key();
                    key.method = method;
                    String u = a.get("URI");
                    key.url = u == null ? null : resolve(baseUrl, u);
                    String iv = a.get("IV");
                    if (iv != null) key.iv = hex(iv);
                }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Map<String, String> a = attrs(line);
                map = new Segment();
                map.url = resolve(baseUrl, a.get("URI"));
                String br = a.get("BYTERANGE");
                if (br != null) {
                    long[] r = range(br, 0);
                    map.length = r[0];
                    map.offset = r[1];
                }
            } else if (line.startsWith("#EXTINF:")) {
                String v = line.substring(8);
                int comma = v.indexOf(',');
                duration = parseDouble(comma >= 0 ? v.substring(0, comma) : v);
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                long[] r = range(line.substring(17), nextOffset);
                length = r[0];
                offset = r[1];
            } else if (!line.startsWith("#")) {
                String url = resolve(baseUrl, line);
                if (pending != null) {
                    pending.url = url;
                    p.variants.add(pending);
                    pending = null;
                    continue;
                }
                Segment s = new Segment();
                s.url = url;
                s.duration = duration;
                s.sequence = sequence++;
                s.key = key;
                s.map = map;
                if (length >= 0) {
                    s.length = length;
                    s.offset = offset;
                    nextOffset = offset + length;
                }
                p.segments.add(s);
                duration = 0;
                length = offset = -1;
            }
        }
        return p;
    }

    /** The variant closest to the wanted height without going over it; the smallest one if all are bigger. */
    static Variant pick(List<Variant> variants, int maxHeight) {
        Variant best = null, smallest = null;
        for (Variant v : variants) {
            int h = v.height > 0 ? v.height : (int) (v.bandwidth / 4000);
            if (smallest == null || score(v) < score(smallest)) smallest = v;
            if (maxHeight > 0 && h > maxHeight) continue;
            if (best == null || score(v) > score(best)) best = v;
        }
        return best != null ? best : smallest;
    }

    private static long score(Variant v) {
        return (long) v.height * 100_000_000L + v.bandwidth;
    }

    /** The audio rendition a variant plays with, if its audio is a separate playlist: in the language wanted, else the default. */
    static Rendition audioFor(Playlist master, Variant v, String want) {
        if (v.audio == null) return null;
        Rendition best = null;
        for (Rendition r : master.renditions) {
            if (!"AUDIO".equals(r.type) || !v.audio.equals(r.group) || r.url == null) continue;
            if (speaks(r.language, want) || speaks(r.name, want)) return r;
            if (best == null || (r.isDefault && !best.isDefault)) best = r;
        }
        return best;
    }

    /** Whether an audio track labeled "ja", "jpn", "Japanese" (or, for Japanese, "Native"/"Original"/"Raw") is in the language wanted. */
    static boolean speaks(String label, String want) {
        if (label == null || want == null || want.isEmpty()) return false;
        String n = label.toLowerCase(Locale.ROOT).trim();
        Locale l = new Locale(want);
        String iso3;
        try {
            iso3 = l.getISO3Language();
        } catch (java.util.MissingResourceException e) {
            iso3 = want;
        }
        if (n.equals(want) || n.startsWith(want + "-") || n.equals(iso3)) return true;
        if ("jpn".equals(iso3) && n.matches("(native|original|raw)\\b.*")) return true;
        String name = l.getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT);
        return !name.isEmpty() && !name.equals(want) && n.startsWith(name);
    }

    private static Map<String, String> attrs(String line) {
        Map<String, String> out = new HashMap<>();
        Matcher m = ATTR.matcher(line.substring(line.indexOf(':') + 1));
        while (m.find()) {
            String v = m.group(2);
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
            out.put(m.group(1), v);
        }
        return out;
    }

    /** "length[@offset]" */
    private static long[] range(String s, long next) {
        String[] p = s.trim().split("@");
        long len = parseLong(p[0]);
        return new long[] {len, p.length > 1 ? parseLong(p[1]) : next};
    }

    private static String resolve(HttpUrl base, String ref) {
        if (ref == null) return null;
        if (base == null) return ref;
        HttpUrl u = base.resolve(ref);
        return u == null ? ref : u.toString();
    }

    private static byte[] hex(String s) {
        String h = s.startsWith("0x") || s.startsWith("0X") ? s.substring(2) : s;
        if (h.length() % 2 == 1) h = "0" + h;
        if (h.length() > 32) h = h.substring(h.length() - 32);
        byte[] out = new byte[16];
        int n = h.length() / 2;
        for (int i = 0; i < n && i < 16; i++) {
            out[16 - n + i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
