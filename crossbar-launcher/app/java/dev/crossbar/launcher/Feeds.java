package dev.crossbar.launcher;

import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * More about an app for its page on the bottom screen, newest first:
 *  - apps that publish a feed (a content provider at "{package}.feed", like AniDeck): their own sections;
 *  - emulators: save files and save states in the folders CrossBar can read;
 *  - media apps: what they played lately (see MediaHistory).
 * A section is {title, style, items: [{title, sub, img, progress, at, cmd}]}, where style is cards, posters or rows
 * and cmd is the bottom screen's command for a tap (Companion.command).
 */
final class Feeds {
    private static final File SD = Environment.getExternalStorageDirectory();
    private static final String PATH = "[\\w-]+(/[\\w.-]+)*";
    private static final List<String> SAVE_PKGS = Arrays.asList("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold", "org.ppsspp.ppssppduo",
            "me.magnum.melonds", "me.magnum.melonds.nightly", "com.retroarch", "com.retroarch.aarch64",
            "org.azahar_emu.azahar", "io.github.lime3ds.android", "org.citra.citra_emu");
    private final App app;
    private final Map<String, String> titleIds = new ConcurrentHashMap<>();

    Feeds(App app) {
        this.app = app;
    }

    /** Apps whose page will have sections, marked in the app list so the bottom screen can lay the page out at once. */
    Set<String> withSections() {
        Set<String> s = new HashSet<>(app.mediaHistory.packages());
        try {
            for (ProviderInfo p : app.getPackageManager().queryContentProviders(null, 0, 0)) {
                if (p.exported && p.authority != null && Arrays.asList(p.authority.split(";")).contains(p.packageName + ".feed")) s.add(p.packageName);
            }
        } catch (Exception ignored) {
        }
        for (String pkg : SAVE_PKGS) {
            JSONArray a = new JSONArray();
            saves(pkg, a);
            if (a.length() > 0) s.add(pkg);
        }
        return s;
    }

    JSONObject get(String pkg) {
        JSONArray sections = new JSONArray();
        if (!appFeed(pkg, sections)) {
            JSONArray played = app.mediaHistory.items(pkg);
            if (played.length() > 0) sections.put(Util.obj("title", "Recently Played", "style", "rows", "items", played));
        }
        saves(pkg, sections);
        return Util.obj("pkg", pkg, "sections", sections);
    }

    // ---- an app's own feed ----

    private ProviderInfo provider(String pkg) {
        if (pkg == null || !pkg.matches("[\\w.]+")) return null;
        ProviderInfo pi = app.getPackageManager().resolveContentProvider(pkg + ".feed", 0);
        return pi != null && pi.exported && pkg.equals(pi.packageName) ? pi : null;
    }

    /** False when the app doesn't publish a feed. */
    private boolean appFeed(String pkg, JSONArray out) {
        if (provider(pkg) == null) return false;
        try {
            Bundle b = app.getContentResolver().call(Uri.parse("content://" + pkg + ".feed"), "feed", null, null);
            JSONArray secs = new JSONObject(b == null ? "{}" : b.getString("json", "{}")).optJSONArray("sections");
            for (int i = 0; secs != null && i < secs.length() && i < 6; i++) {
                JSONObject s = secs.optJSONObject(i);
                JSONArray items = s == null ? null : s.optJSONArray("items");
                if (items == null) continue;
                JSONArray clean = new JSONArray();
                for (int k = 0; k < items.length() && k < 24; k++) {
                    JSONObject it = items.optJSONObject(k);
                    if (it == null) continue;
                    JSONObject open = it.optJSONObject("open");
                    clean.put(Util.obj("title", clip(it.optString("title"), 120), "sub", clip(it.optString("sub"), 160),
                            "img", feedImg(pkg, it.optString("img")), "progress", Math.max(0, Math.min(1, it.optDouble("progress", 0))),
                            "at", it.optLong("at"), "cmd", Util.obj("t", "feed", "pkg", pkg, "open", open == null ? new JSONObject() : open)));
                }
                String style = s.optString("style");
                if (!style.equals("cards") && !style.equals("posters")) style = "rows";
                if (clean.length() > 0) out.put(Util.obj("title", clip(s.optString("title"), 60), "style", style, "items", clean));
            }
        } catch (Exception e) {
            Log.w(App.TAG, "feed of " + pkg + ": " + e);
        }
        return true;
    }

    private static String feedImg(String pkg, String img) {
        if (img.startsWith("https://")) return img;
        if (img.matches(PATH) && !img.contains("..")) return "/img/feed?pkg=" + Uri.encode(pkg) + "&p=" + Uri.encode(img);
        return "";
    }

    /** A picture from an app's feed provider, for /img/feed. */
    byte[] image(String pkg, String path) {
        if (provider(pkg) == null || path == null || !path.matches(PATH) || path.contains("..")) return null;
        try (InputStream in = app.getContentResolver().openInputStream(Uri.parse("content://" + pkg + ".feed/" + path))) {
            return in == null ? null : Util.readAll(in);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- emulator saves ----

    private void saves(String pkg, JSONArray out) {
        List<JSONObject> l = new ArrayList<>();
        switch (pkg) {
            case "org.ppsspp.ppsspp": case "org.ppsspp.ppssppgold": case "org.ppsspp.ppssppduo":
                ppsspp(l, new File(SD, "PPSSPP/PSP"));
                ppsspp(l, new File(SD, "PSP"));
                break;
            case "me.magnum.melonds": case "me.magnum.melonds.nightly":
                byRom(l, new File(SD, "Saves/MelonDs"));
                break;
            case "com.retroarch": case "com.retroarch.aarch64":
                for (String d : new String[] {"saves", "states"}) {
                    File dir = new File(SD, "RetroArch/" + d);
                    byRom(l, dir);
                    for (File sub : dirs(dir)) byRom(l, sub);
                }
                break;
            case "org.azahar_emu.azahar": case "io.github.lime3ds.android": case "org.citra.citra_emu":
                azahar(l, new File(SD, "Azahar"));
                break;
            default:
                return;
        }
        l.sort((a, b) -> Long.compare(b.optLong("at"), a.optLong("at")));
        JSONArray items = new JSONArray();
        for (int i = 0; i < l.size() && i < 12; i++) items.put(l.get(i));
        if (items.length() > 0) out.put(Util.obj("title", "Recent Saves", "style", "rows", "items", items));
    }

    /** PSP save data (titles from PARAM.SFO) and PPSSPP save states (with their screenshots). */
    private void ppsspp(List<JSONObject> l, File root) {
        for (File d : dirs(new File(root, "SAVEDATA"))) {
            Map<String, String> sfo = sfo(new File(d, "PARAM.SFO"));
            JSONObject g = bySerial(d.getName());
            String title = sfo.containsKey("TITLE") ? sfo.get("TITLE") : g != null ? g.optString("title") : d.getName();
            String what = sfo.containsKey("SAVEDATA_TITLE") ? sfo.get("SAVEDATA_TITLE") : "Save data";
            File icon = new File(d, "ICON0.PNG");
            l.add(item(title, what, icon.isFile() ? img(icon, 160) : gameImg(g), newest(d), g));
        }
        File[] states = new File(root, "PPSSPP_STATE").listFiles((dir, n) -> n.endsWith(".ppst"));
        if (states == null) return;
        for (File s : states) {
            // ULUS10466_1.00_0.ppst, with ULUS10466_1.00_0.jpg beside it
            String n = s.getName();
            JSONObject g = bySerial(n);
            String slot = n.replaceFirst("^.*_(\\d+)\\.ppst$", "$1");
            File shot = new File(s.getParent(), n.replaceFirst("\\.ppst$", ".jpg"));
            String sub = slot.matches("\\d+") ? "Save state, slot " + (Integer.parseInt(slot) + 1) : "Save state";
            l.add(item(g != null ? g.optString("title") : n.split("_")[0], sub, shot.isFile() ? img(shot, 240) : gameImg(g), s.lastModified(), g));
        }
    }

    /** Saves named after the ROM (melonDS, RetroArch): game.sav, game.srm, game.state1, game.ml2 … */
    private void byRom(List<JSONObject> l, File dir) {
        File[] fs = dir.listFiles(File::isFile);
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            String base = n.replaceFirst("\\.(sav|srm|dsv|state\\d*|state\\.auto|ml\\d)$", "");
            if (base.equals(n)) continue;
            boolean state = !n.matches(".*\\.(sav|srm|dsv)$");
            JSONObject g = byBase(base);
            File shot = new File(f.getPath() + ".png");
            l.add(item(g != null ? g.optString("title") : Util.cleanTitle(base), state ? "Save state" : "Save file",
                    shot.isFile() ? img(shot, 240) : gameImg(g), f.lastModified(), g));
        }
    }

    /** 3DS save data: sdmc/Nintendo 3DS/{id0}/{id1}/title/00040000/{title}/data. */
    private void azahar(List<JSONObject> l, File root) {
        for (File id0 : dirs(new File(root, "sdmc/Nintendo 3DS"))) {
            for (File id1 : dirs(id0)) {
                for (File t : dirs(new File(id1, "title/00040000"))) {
                    long at = newest(new File(t, "data"));
                    if (at == 0) continue;
                    String tid = ("00040000" + t.getName()).toUpperCase(Locale.ROOT);
                    JSONObject g = byTitleId(tid);
                    l.add(item(g != null ? g.optString("title") : "3DS game not in your library", g != null ? "Save data" : "Save data · " + tid, gameImg(g), at, g));
                }
            }
        }
    }

    // ---- matching saves to games ----

    private JSONObject bySerial(String name) {
        String s = name.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (s.length() < 9) return null;
        s = s.substring(0, 9);
        for (JSONObject g : app.games.list()) {
            JSONObject m = g.optJSONObject("meta");
            if (m != null && s.equals(m.optString("serial").replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT))) return g;
        }
        return null;
    }

    private JSONObject byBase(String base) {
        for (JSONObject g : app.games.list()) if (base.equals(Util.baseName(g.optString("file")))) return g;
        return null;
    }

    private JSONObject byTitleId(String tid) {
        for (JSONObject g : app.games.list()) if ("n3ds".equals(g.optString("platform")) && tid.equals(titleId(g))) return g;
        return null;
    }

    /** The title id in a 3DS ROM's header: NCSD (.3ds, .cci) at 0x108, NCCH (.cxi) at 0x118. */
    private String titleId(JSONObject g) {
        String path = g.optString("path");
        String tid = titleIds.get(path);
        if (tid != null) return tid;
        tid = "";
        try (RandomAccessFile r = new RandomAccessFile(path, "r")) {
            byte[] h = new byte[0x20];
            r.seek(0x100);
            r.readFully(h);
            String magic = new String(h, 0, 4, StandardCharsets.US_ASCII);
            int at = magic.equals("NCSD") ? 0x08 : magic.equals("NCCH") ? 0x18 : -1;
            if (at >= 0) tid = String.format(Locale.ROOT, "%08X%08X", le32(h, at + 4), le32(h, at));
        } catch (IOException ignored) {
        }
        titleIds.put(path, tid);
        return tid;
    }

    private static JSONObject item(String title, String sub, String img, long at, JSONObject game) {
        JSONObject o = Util.obj("title", title, "sub", sub, "img", img, "at", at);
        if (game != null) Util.put(o, "cmd", Util.obj("t", "game", "id", game.optString("id")));
        JSONObject a = game == null ? null : game.optJSONObject("art");
        String px = a == null ? "" : a.optString("pixelicon");
        if (!px.isEmpty() && img.equals(img(new File(px), 160))) Util.put(o, "pixel", true);
        return o;
    }

    private static String gameImg(JSONObject g) {
        JSONObject a = g == null ? null : g.optJSONObject("art");
        if (a == null) return "";
        for (String k : new String[] {"icon0", "sqicon", "cocoon_icon", "boxart", "cocoon_boxart", "pixelicon"}) {
            if (!a.optString(k).isEmpty()) return img(new File(a.optString(k)), 160);
        }
        return "";
    }

    private static String img(File f, int w) {
        return Util.fileUrl(f.getAbsolutePath()) + "&w=" + w;
    }

    // ---- files ----

    private static File[] dirs(File d) {
        File[] a = d.listFiles(File::isDirectory);
        return a == null ? new File[0] : a;
    }

    private static long newest(File d) {
        File[] fs = d.listFiles();
        long t = 0;
        if (fs != null) for (File f : fs) t = Math.max(t, f.isDirectory() ? newest(f) : f.lastModified());
        return t;
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** The text fields of a PSP PARAM.SFO (TITLE, SAVEDATA_TITLE, SAVEDATA_DETAIL …). */
    static Map<String, String> sfo(File f) {
        Map<String, String> out = new HashMap<>();
        if (!f.isFile() || f.length() > 64 * 1024) return out;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[(int) r.length()];
            r.readFully(b);
            if (b.length < 20 || b[0] != 0 || b[1] != 'P' || b[2] != 'S' || b[3] != 'F') return out;
            int keys = le32(b, 8), data = le32(b, 12), count = le32(b, 16);
            for (int i = 0; i < count && 20 + i * 16 + 16 <= b.length; i++) {
                int e = 20 + i * 16;
                int keyOff = (b[e] & 0xff) | (b[e + 1] & 0xff) << 8, fmt = (b[e + 2] & 0xff) | (b[e + 3] & 0xff) << 8;
                int len = le32(b, e + 4), off = le32(b, e + 12);
                if (fmt != 0x0204 || keys + keyOff >= b.length || data + off + len > b.length) continue;
                int k = keys + keyOff, ke = k;
                while (ke < b.length && b[ke] != 0) ke++;
                String key = new String(b, k, ke - k, StandardCharsets.US_ASCII);
                String val = new String(b, data + off, len, StandardCharsets.UTF_8).replace("\0", "").trim();
                if (!val.isEmpty()) out.put(key, val.replaceAll("\\s+", " "));
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static int le32(byte[] b, int i) {
        return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff) << 16 | (b[i + 3] & 0xff) << 24;
    }
}
