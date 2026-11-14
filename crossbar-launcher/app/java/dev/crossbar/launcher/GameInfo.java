package dev.crossbar.launcher;

import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Process;
import android.os.storage.StorageManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Descriptions and facts about a game for the bottom screen. Uses what the Cocoon frontend
 * scraped into its gamelists when it has an entry, otherwise the game's English Wikipedia
 * summary. Online results are cached in gameinfo.json; misses are retried after a week.
 */
final class GameInfo {
    private static final int VERSION = 1;
    private static final long RETRY_MS = 7L * 86400_000L;
    private static final File GAMELISTS = new File(Environment.getExternalStorageDirectory(), "Cocoon/gamelists");
    /** Words in ROM hack names that keep a search from finding the original game. */
    private static final Pattern HACK_WORDS = Pattern.compile(
            "(?i)\\b(redux|complete|evless|randomi[sz]er|nuzlocke|rom ?hack|hack|patched|translated|undub)\\b");
    private static final Pattern YEAR = Pattern.compile("\\b(19[7-9]\\d|20\\d\\d)\\b");
    private static final List<String> STOP = Arrays.asList("the", "a", "an", "of", "and");

    private final App app;
    private final File cacheFile;
    private final JSONObject cache;
    private Map<String, JSONObject> cocoon = new HashMap<>();
    private String cocoonStamp = "";

    GameInfo(App app) {
        this.app = app;
        cacheFile = new File(app.getFilesDir(), "gameinfo.json");
        JSONObject c = Util.readJson(cacheFile);
        cache = c == null ? new JSONObject() : c;
    }

    JSONObject forGame(JSONObject g) {
        JSONObject info = Util.copy(cocoonEntry(g.optString("file")));
        if (!info.optString("desc").isEmpty() || !app.store.bool("onlineGameInfo")) return info;
        Platforms.Platform p = Platforms.byId(g.optString("platform"));
        JSONObject meta = g.optJSONObject("meta");
        String longTitle = meta == null ? "" : meta.optString("longTitle");
        String title = longTitle.isEmpty() ? g.optString("title") : longTitle;
        JSONObject online = online("game:" + g.optString("id"), title, p == null ? "" : p.name);
        for (Iterator<String> it = online.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!info.has(k)) Util.put(info, k, online.opt(k));
        }
        return info;
    }

    /** An Android app's version, install dates and storage use, plus its Wikipedia summary when a title is given. */
    JSONObject forApp(String pkg, String label) {
        JSONObject info = label == null || label.isEmpty() || !app.store.bool("onlineGameInfo")
                ? new JSONObject() : Util.copy(online("app:" + pkg, label, ""));
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(pkg, 0);
            Util.put(info, "version", pi.versionName);
            Util.put(info, "installed", pi.firstInstallTime);
            Util.put(info, "updated", pi.lastUpdateTime);
            long size;
            try {
                StorageStats st = app.getSystemService(StorageStatsManager.class)
                        .queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle());
                size = st.getAppBytes() + st.getDataBytes();
            } catch (Exception e) {
                // Without usage access only the APK size is known.
                size = new File(pi.applicationInfo.sourceDir).length();
            }
            Util.put(info, "size", size);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return info;
    }

    // ---------- Wikipedia ----------
    private synchronized JSONObject online(String key, String title, String platform) {
        JSONObject c = cache.optJSONObject(key);
        long now = System.currentTimeMillis();
        if (c != null && c.optInt("v") == VERSION && (c.has("desc") || now - c.optLong("at") < RETRY_MS)) return c;
        JSONObject r;
        try {
            r = wikipedia(title, platform);
        } catch (Exception e) {
            // Offline or a server error: try again next time instead of caching a miss.
            Log.w(App.TAG, "game info lookup failed: " + e.getClass().getSimpleName());
            return c == null ? new JSONObject() : c;
        }
        Util.put(r, "v", VERSION);
        Util.put(r, "at", now);
        Util.put(cache, key, r);
        Util.writeText(cacheFile, cache.toString());
        return r;
    }

    private static JSONObject wikipedia(String title, String platform) throws Exception {
        String name = HACK_WORDS.matcher(title).replaceAll(" ").replaceAll("\\s+", " ").trim();
        String q = name + (platform.isEmpty() ? "" : " " + platform) + " video game";
        JSONObject s = new JSONObject(Util.httpGet("https://en.wikipedia.org/w/api.php?action=query&list=search"
                + "&format=json&srlimit=5&srsearch=" + Util.enc(q), 8000));
        JSONObject query = s.optJSONObject("query");
        JSONArray hits = query == null ? null : query.optJSONArray("search");
        List<String> want = words(name);
        JSONObject series = null;
        for (int i = 0; hits != null && i < hits.length(); i++) {
            String t = hits.optJSONObject(i).optString("title");
            if (!words(t).containsAll(want.subList(0, Math.min(3, want.size())))) continue;
            JSONObject sum = new JSONObject(Util.httpGet("https://en.wikipedia.org/api/rest_v1/page/summary/"
                    + Util.enc(t.replace(' ', '_')), 8000));
            if (!"standard".equals(sum.optString("type"))) continue;
            String d = sum.optString("description").toLowerCase(Locale.ROOT);
            if (!d.contains("game")) continue;
            if (d.contains("series") || d.contains("franchise")) {
                if (series == null) series = result(sum);
                continue;
            }
            return result(sum);
        }
        return series != null ? series : new JSONObject();
    }

    private static JSONObject result(JSONObject sum) {
        String text = sum.optString("extract").trim();
        if (text.length() > 900) {
            int cut = text.lastIndexOf(". ", 900);
            text = cut > 200 ? text.substring(0, cut + 1) : text.substring(0, 900) + "…";
        }
        JSONObject r = Util.obj("desc", text, "source", "Wikipedia", "wiki", sum.optString("title"));
        Matcher y = YEAR.matcher(sum.optString("description"));
        if (y.find()) Util.put(r, "year", y.group(1));
        JSONObject th = sum.optJSONObject("thumbnail");
        if (th != null && th.optString("source").startsWith("https://")) Util.put(r, "thumb", th.optString("source"));
        return r;
    }

    /** Lowercase words without accents or punctuation, minus filler words. */
    private static List<String> words(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String w : n.split("[^a-z0-9]+")) if (!w.isEmpty() && !STOP.contains(w)) out.add(w);
        return out;
    }

    // ---------- Cocoon gamelists ----------
    private synchronized JSONObject cocoonEntry(String file) {
        File[] dirs = GAMELISTS.listFiles();
        StringBuilder stamp = new StringBuilder();
        if (dirs != null) {
            for (File d : dirs) {
                File f = new File(d, "gamelist.xml");
                if (f.isFile()) stamp.append(f.getPath()).append(f.lastModified()).append(';');
            }
        }
        if (!stamp.toString().equals(cocoonStamp)) {
            cocoonStamp = stamp.toString();
            Map<String, JSONObject> m = new HashMap<>();
            if (dirs != null) for (File d : dirs) parse(new File(d, "gamelist.xml"), m);
            cocoon = m;
        }
        JSONObject e = cocoon.get(file);
        return e == null ? new JSONObject() : e;
    }

    /** EmulationStation-style gamelist.xml, keyed by ROM file name. */
    private static void parse(File f, Map<String, JSONObject> out) {
        if (!f.isFile()) return;
        try (InputStream in = new FileInputStream(f)) {
            XmlPullParserFactory pf = XmlPullParserFactory.newInstance();
            pf.setNamespaceAware(false); // Cocoon writes "cocoon:" tags without declaring the prefix
            XmlPullParser p = pf.newPullParser();
            p.setInput(in, "UTF-8");
            JSONObject cur = null;
            String path = null;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = p.getName();
                    if (p.getDepth() == 2 && "game".equals(tag)) {
                        cur = new JSONObject();
                        path = null;
                    } else if (cur != null && p.getDepth() == 3) {
                        switch (tag) {
                            case "path": path = p.nextText().trim(); break;
                            case "desc": case "developer": case "publisher": case "players":
                                putText(cur, tag, p.nextText()); break;
                            case "genre": putText(cur, tag, p.nextText().replace(";", ",")); break;
                            case "rating":
                                try {
                                    Util.put(cur, "rating", Double.parseDouble(p.nextText().trim()));
                                } catch (NumberFormatException ignored) {
                                }
                                break;
                            case "releasedate": {
                                String v = p.nextText().trim();
                                if (v.matches("\\d{4}.*")) Util.put(cur, "year", v.substring(0, 4));
                                break;
                            }
                            default: break;
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && p.getDepth() == 2 && "game".equals(p.getName())) {
                    if (cur != null && path != null && cur.length() > 0) {
                        Util.put(cur, "source", "Cocoon");
                        out.put(new File(path).getName(), cur);
                    }
                    cur = null;
                }
            }
        } catch (Exception e) {
            Log.w(App.TAG, "could not read a Cocoon gamelist: " + e.getClass().getSimpleName());
        }
    }

    private static void putText(JSONObject o, String k, String v) {
        String t = v == null ? "" : v.trim();
        if (!t.isEmpty()) Util.put(o, k, t);
    }
}
