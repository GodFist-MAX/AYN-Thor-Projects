package dev.crossbar.launcher;

import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Finds games under the ROM folder and collects their artwork. */
final class GameScanner {
    private static final String[] COCOON_KINDS = {"icon", "hero", "logo", "screenshot_title", "screenshot_gameplay", "boxart", "cover", "fanart"};
    private static final String[][] LIBRETRO_KINDS = {{"Named_Boxarts", "boxart"}, {"Named_Snaps", "snap"}, {"Named_Titles", "titlescreen"}};

    private final App app;
    private final File artRoot;
    private final File indexFile;
    private final Object scanLock = new Object();
    private final Map<String, JSONObject> byId = new ConcurrentHashMap<>();
    private volatile List<JSONObject> games = new ArrayList<>();
    private volatile boolean fetching;

    GameScanner(App app) {
        this.app = app;
        artRoot = new File(app.getFilesDir(), "art");
        indexFile = new File(app.getFilesDir(), "games.json");
        JSONObject idx = Util.readJson(indexFile);
        if (idx != null) {
            JSONArray arr = idx.optJSONArray("games");
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject g = arr.optJSONObject(i);
                if (g == null) continue;
                list.add(g);
                byId.put(g.optString("id"), g);
            }
            games = list;
        }
    }

    JSONObject byId(String id) {
        return byId.get(id);
    }

    List<JSONObject> list() {
        return games;
    }

    void scan(boolean force) {
        synchronized (scanLock) {
            Map<String, JSONObject> old = new HashMap<>(byId);
            List<JSONObject> found = new ArrayList<>();
            File root = new File(app.store.str("romRoot"));
            File[] dirs = root.listFiles();
            if (dirs != null) {
                Arrays.sort(dirs);
                for (File d : dirs) {
                    if (!d.isDirectory()) continue;
                    Platforms.Platform p = Platforms.byFolder(d.getName());
                    if (p == null || p.exts.isEmpty()) continue;
                    walk(d, p, 0, found, old, force);
                }
            }
            Collections.sort(found, (a, b) -> {
                int pa = platformOrder(a.optString("platform")), pb = platformOrder(b.optString("platform"));
                if (pa != pb) return pa - pb;
                return a.optString("title").compareToIgnoreCase(b.optString("title"));
            });
            byId.clear();
            for (JSONObject g : found) byId.put(g.optString("id"), g);
            games = found;
            save();
            Log.i(App.TAG, "scan: " + found.size() + " games");
        }
        if (app.store.bool("downloadArt")) fetchOnlineArtAsync(force);
    }

    private static int platformOrder(String id) {
        for (int i = 0; i < Platforms.ALL.length; i++) if (Platforms.ALL[i].id.equals(id)) return i;
        return 999;
    }

    private void walk(File dir, Platforms.Platform p, int depth, List<JSONObject> out, Map<String, JSONObject> old, boolean force) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        boolean hasM3u = false, hasCue = false;
        for (File f : fs) {
            String e = Util.ext(f.getName());
            if (e.equals("m3u")) hasM3u = true;
            if (e.equals("cue") || e.equals("gdi")) hasCue = true;
        }
        for (File f : fs) {
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth < 2) walk(f, p, depth + 1, out, old, force);
                continue;
            }
            String ext = Util.ext(name);
            if (!p.exts.contains(ext)) continue;
            if (hasM3u && !ext.equals("m3u")) continue;
            if (hasCue && (ext.equals("bin") || ext.equals("img") || ext.equals("raw"))) continue;
            String id = Util.sha1(f.getAbsolutePath()).substring(0, 12);
            JSONObject prev = old.get(id);
            if (!force && prev != null && prev.optLong("mtime") == f.lastModified() && prev.optLong("size") == f.length()) {
                cocoonArt(prev, p, f);
                out.add(prev);
            } else {
                out.add(build(id, f, p));
            }
        }
    }

    private JSONObject build(String id, File f, Platforms.Platform p) {
        String base = Util.baseName(f.getName());
        JSONObject g = Util.obj("id", id, "platform", p.id, "path", f.getAbsolutePath(), "file", f.getName(),
                "title", Util.cleanTitle(base), "tags", Util.tags(base), "size", f.length(), "mtime", f.lastModified());
        JSONObject art = new JSONObject();
        JSONObject meta = new JSONObject();
        File dir = new File(artRoot, id);
        dir.mkdirs();
        try {
            Art.extract(p.id, f, dir, art, meta);
        } catch (Throwable t) {
            Log.w(App.TAG, "art extract failed for " + f.getName() + ": " + t);
        }
        // keep previously downloaded online art
        for (String[] k : LIBRETRO_KINDS) {
            File prev = new File(dir, k[1] + ".png");
            if (prev.isFile()) Util.put(art, k[1], prev.getAbsolutePath());
        }
        Util.put(g, "art", art);
        Util.put(g, "meta", meta);
        cocoonArt(g, p, f);
        return g;
    }

    /** Artwork the Cocoon frontend already downloaded for this ROM. */
    private void cocoonArt(JSONObject g, Platforms.Platform p, File f) {
        JSONObject art = g.optJSONObject("art");
        if (art == null) {
            art = new JSONObject();
            Util.put(g, "art", art);
        }
        for (Iterator<String> it = art.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.startsWith("cocoon_") && !new File(art.optString(k)).isFile()) it.remove();
        }
        File media = new File(Environment.getExternalStorageDirectory(), "Cocoon/downloaded_media");
        String base = Util.baseName(f.getName());
        for (String pid : p.cocoonIds) {
            File pd = new File(media, pid);
            if (!pd.isDirectory()) continue;
            for (String kind : COCOON_KINDS) {
                File[] fs = new File(pd, kind).listFiles();
                if (fs == null) continue;
                File best = null;
                for (File x : fs) {
                    String n = x.getName();
                    if (!Util.isImage(n)) continue;
                    if (n.startsWith(base + "__") || Util.baseName(n).equals(base)) {
                        if (best == null || x.lastModified() > best.lastModified()) best = x;
                    }
                }
                if (best != null) Util.put(art, "cocoon_" + kind, best.getAbsolutePath());
            }
        }
    }

    private void save() {
        JSONArray arr = new JSONArray();
        for (JSONObject g : games) arr.put(g);
        Util.writeText(indexFile, Util.obj("games", arr).toString());
    }

    void fetchOnlineArtAsync(boolean retry) {
        if (fetching) return;
        fetching = true;
        app.io.execute(() -> {
            try {
                fetchOnlineArt(retry);
            } finally {
                fetching = false;
            }
        });
    }

    /** Box art, title screens and gameplay snaps from the libretro thumbnail library. */
    private void fetchOnlineArt(boolean retry) {
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (JSONObject g : new ArrayList<>(games)) {
            Platforms.Platform p = Platforms.byId(g.optString("platform"));
            if (p == null || p.libretro == null) continue;
            JSONObject art = g.optJSONObject("art");
            if (art == null) continue;
            if (!retry && now - g.optLong("netTried") < 7L * 86400_000L) continue;
            File dir = new File(artRoot, g.optString("id"));
            boolean networkDown = false;
            for (String[] kind : LIBRETRO_KINDS) {
                if (art.has(kind[1])) continue;
                File out = new File(dir, kind[1] + ".png");
                search:
                for (String name : candidates(g)) {
                    for (String sys : p.libretro) {
                        String url = "https://thumbnails.libretro.com/" + Util.enc(sys) + "/" + kind[0] + "/" + Util.enc(sanitize(name)) + ".png";
                        int r = Util.download(url, out, 15000);
                        if (r == 1) {
                            Util.put(art, kind[1], out.getAbsolutePath());
                            changed = true;
                            break search;
                        }
                        if (r < 0) {
                            networkDown = true;
                            break search;
                        }
                    }
                }
                if (networkDown) break;
            }
            if (networkDown) break;
            Util.put(g, "netTried", now);
            changed = true;
        }
        if (changed) {
            synchronized (scanLock) {
                save();
            }
        }
    }

    private static List<String> candidates(JSONObject g) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String base = Util.baseName(g.optString("file"));
        out.add(base);
        String s = base;
        while (s.endsWith(")") || s.endsWith("]")) {
            int open = Math.max(s.lastIndexOf(" ("), s.lastIndexOf(" ["));
            if (open <= 0) break;
            s = s.substring(0, open).trim();
            if (s.contains("(")) out.add(s);
        }
        String title = Util.cleanTitle(base);
        String tags = g.optString("tags").toLowerCase(Locale.ROOT);
        if (tags.contains("japan")) out.add(title + " (Japan)");
        out.add(title + " (USA)");
        out.add(title + " (Europe)");
        out.add(title + " (USA, Europe)");
        List<String> list = new ArrayList<>(out);
        return list.size() > 6 ? list.subList(0, 6) : list;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[&*/:`<>?\\\\|\"]", "_");
    }
}
