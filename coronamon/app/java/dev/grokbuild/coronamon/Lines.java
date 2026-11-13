package dev.grokbuild.coronamon;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What Coronamon says, by situation. Ships with assets/lines.json; lines the user adds in
 * /sdcard/Coronamon/lines.json (same format) are mixed in.
 */
final class Lines {
    static final class Line {
        final String src, text;

        Line(String src, String text) {
            this.src = src;
            this.text = text;
        }
    }

    static final File USER_FILE = new File(Paths.ROOT, "lines.json");

    private final Map<String, List<Line>> byKey = new HashMap<>();
    private final Map<String, ArrayDeque<String>> recent = new HashMap<>();
    private final Map<String, String> sources = new HashMap<>();
    private final Random rnd = new Random();
    private long userStamp = -1;

    void load(Context ctx) {
        byKey.clear();
        try (InputStream in = ctx.getAssets().open("lines.json")) {
            merge(new JSONObject(new String(Util.readAll(in), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.w("lines asset: " + e);
        }
        if (!USER_FILE.exists()) {
            Util.writeText(USER_FILE, "{\n  \"_help\": \"Add your own lines: each entry is [source, text]. Situations: "
                    + "greet tap poke pickup throw dizzy land pet idle quote night sleepy wake train special bask dance think "
                    + "build_start build_ok build_fail install_start install_ok install_fail update_found crash app_open game_open hop. "
                    + "{app} {version} and {msg} are filled in.\",\n  \"lines\": {\n    \"idle\": []\n  }\n}\n");
        }
        userStamp = USER_FILE.lastModified();
        JSONObject user = Util.readJson(USER_FILE);
        if (user != null) merge(user);
    }

    /** Picks up edits to the user file. */
    void refresh(Context ctx) {
        if (USER_FILE.lastModified() != userStamp) load(ctx);
    }

    private void merge(JSONObject root) {
        JSONObject src = root.optJSONObject("sources");
        if (src != null) {
            for (Iterator<String> it = src.keys(); it.hasNext(); ) {
                String k = it.next();
                sources.put(k, src.optString(k));
            }
        }
        JSONObject lines = root.optJSONObject("lines");
        if (lines == null) return;
        for (Iterator<String> it = lines.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONArray arr = lines.optJSONArray(key);
            if (arr == null) continue;
            List<Line> list = byKey.computeIfAbsent(key, k -> new ArrayList<>());
            for (int i = 0; i < arr.length(); i++) {
                Object o = arr.opt(i);
                if (o instanceof JSONArray) {
                    JSONArray a = (JSONArray) o;
                    String text = a.optString(1, "").trim();
                    if (!text.isEmpty()) list.add(new Line(a.optString(0, ""), text));
                } else if (o instanceof String && !((String) o).trim().isEmpty()) {
                    list.add(new Line("", ((String) o).trim()));
                }
            }
        }
    }

    boolean has(String key) {
        List<Line> l = byKey.get(key);
        return l != null && !l.isEmpty();
    }

    /** A random line for the situation with {app}, {version} and {msg} filled in, or null. */
    Line pick(String key, Map<String, String> vars) {
        List<Line> list = byKey.get(key);
        if (list == null || list.isEmpty()) return null;
        ArrayDeque<String> seen = recent.computeIfAbsent(key, k -> new ArrayDeque<>());
        Line pick = null;
        for (int tries = 0; tries < 8; tries++) {
            Line l = list.get(rnd.nextInt(list.size()));
            if (!seen.contains(l.text) || list.size() <= seen.size()) {
                pick = l;
                break;
            }
        }
        if (pick == null) pick = list.get(rnd.nextInt(list.size()));
        seen.addLast(pick.text);
        while (seen.size() > Math.min(4, list.size() / 2)) seen.removeFirst();
        String text = pick.text;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                text = text.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        text = text.replaceAll("\\{[a-z]+\\}", "").replaceAll("\\s+([!?.,])", "$1").replaceAll("\\s{2,}", " ").trim();
        return new Line(pick.src, text);
    }

    String sourceName(String src) {
        String s = sources.get(src == null ? "" : src);
        return s == null ? "" : s;
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, List<Line>> e : byKey.entrySet()) {
            JSONArray a = new JSONArray();
            for (Line l : e.getValue()) a.put(new JSONArray().put(l.src).put(l.text));
            Util.put(o, e.getKey(), a);
        }
        JSONObject s = new JSONObject();
        for (Map.Entry<String, String> e : sources.entrySet()) Util.put(s, e.getKey(), e.getValue());
        return Util.obj("lines", o, "sources", s);
    }
}
