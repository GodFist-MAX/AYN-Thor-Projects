package dev.anideck.app;

import dev.anideck.app.ext.Catalog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What both screens' pages ask the app for, as JSON in and {"ok": ...} or {"error": "..."} out. Sources,
 * catalogs, episodes and videos go to {@link Catalog}; the history, saved shows, downloads, settings and
 * AniList are answered here. Called off the main thread.
 */
final class Api {
    private Api() {
    }

    static String call(String method, String args) {
        JSONObject a;
        try {
            a = args == null || args.isEmpty() ? new JSONObject() : new JSONObject(args);
        } catch (JSONException e) {
            return error("Bad request");
        }
        try {
            Object r = app(method, a);
            if (r == NOT_MINE) return Catalog.call(method, a.toString());
            return App.obj("ok", r == null ? JSONObject.NULL : r).toString();
        } catch (IOException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            return error(Catalog.describe(e));
        }
    }

    /** A Catalog call from Java: the "ok" object, or IOException with the message to show. */
    static JSONObject ext(String method, JSONObject args) throws IOException {
        try {
            JSONObject r = new JSONObject(Catalog.call(method, args.toString()));
            if (r.has("error")) throw new IOException(App.str(r, "error"));
            JSONObject ok = r.optJSONObject("ok");
            if (ok == null) throw new IOException("No answer");
            return ok;
        } catch (JSONException e) {
            throw new IOException("Bad answer");
        }
    }

    private static final Object NOT_MINE = new Object();

    private static Object app(String method, JSONObject a) throws IOException {
        App app = App.I;
        Shelf shelf = Shelf.get(app);
        Downloads dl = Downloads.get(app);
        switch (method) {
            case "shelf": return shelf.json();
            case "show": {
                String key = App.str(a, "key");
                JSONObject s = shelf.show(key);
                return App.obj("show", s == null ? JSONObject.NULL : s, "downloads", dl.forShow(key),
                        "cover", shelf.cover(key).isFile() ? Shelf.coverName(key) : JSONObject.NULL);
            }
            case "remember":
                shelf.remember(App.str(a, "source"), App.str(a, "sourceName"), a.optJSONObject("anime"));
                return null;
            case "save":
                shelf.save(App.str(a, "key"), a.optBoolean("on"));
                return shelf.json();
            case "mark":
                shelf.mark(App.str(a, "key"), a.optJSONArray("urls") == null ? new JSONArray() : a.optJSONArray("urls"), a.optBoolean("watched"));
                return shelf.show(App.str(a, "key"));
            case "forget":
                shelf.forget(App.str(a, "key"));
                return shelf.json();
            case "link":
                shelf.link(App.str(a, "key"), a.optInt("a"));
                return shelf.show(App.str(a, "key"));
            case "clearHistory":
                shelf.clearRecent();
                return shelf.json();
            case "downloads": return dl.json();
            case "download": {
                JSONArray eps = a.optJSONArray("episodes");
                if (eps == null || eps.length() == 0) throw new IOException("No episodes picked");
                int n = dl.add(App.str(a, "source"), App.str(a, "sourceName"), a.optJSONObject("anime"), eps, a.optInt("a"), null);
                return App.obj("added", n);
            }
            case "deleteDownload":
                dl.delete(App.str(a, "id"));
                return dl.json();
            case "retryDownload":
                dl.retry(App.str(a, "id"));
                return dl.json();
            case "settings": return app.settings();
            case "set":
                app.set(App.str(a, "key"), a.opt("value"));
                return app.settings();
            case "library":
                try {
                    return new JSONObject(app.library.json());
                } catch (JSONException e) {
                    throw new IOException("The AniList lists aren't loaded");
                }
            case "syncLibrary":
                app.library.sync(true);
                return null;
            case "entry": {
                JSONObject d = app.library.details(a.optInt("a"));
                if (d == null) throw new IOException("Couldn't load that show from AniList");
                return d;
            }
            case "anilistSearch": return app.library.search(App.str(a, "q"));
            case "anilistMatch": return match(a);
            default: return NOT_MINE;
        }
    }

    // ---- AniList matching ----

    /**
     * Finds a show's AniList entry by its title on the source: {a, title, sure} when one stands out,
     * plus the candidates to pick from. Links the show when sure.
     */
    private static JSONObject match(JSONObject a) throws IOException {
        String title = App.str(a, "title"), key = App.str(a, "key");
        JSONArray found = App.I.library.search(clean(title));
        if (found.length() == 0 && !clean(title).equals(title)) found = App.I.library.search(title);
        double best = 0;
        JSONObject pick = null;
        for (int i = 0; i < found.length(); i++) {
            JSONObject m = found.optJSONObject(i);
            double s = score(title, m);
            App.put(m, "score", Math.round(s * 100) / 100.0);
            if (s > best) {
                best = s;
                pick = m;
            }
        }
        boolean sure = pick != null && best >= 0.9;
        if (sure && !key.isEmpty()) Shelf.get(App.I).link(key, pick.optInt("id"));
        return App.obj("a", sure ? pick.optInt("id") : 0, "title", sure ? App.str(pick, "title") : "", "sure", sure, "candidates", found);
    }

    static double score(String title, JSONObject m) {
        String want = norm(clean(title));
        if (want.isEmpty()) return 0;
        double best = 0;
        Set<String> names = new HashSet<>();
        for (String k : new String[] {"title", "romaji", "english", "native"}) names.add(App.str(m, k));
        JSONArray syn = m.optJSONArray("synonyms");
        for (int i = 0; syn != null && i < syn.length(); i++) names.add(syn.optString(i));
        for (String n : names) {
            String have = norm(n);
            if (have.isEmpty()) continue;
            double s = have.equals(want) ? 1 : dice(want, have);
            // "Show 2" and "Show" are different seasons however alike they look.
            if (!numbers(have).equals(numbers(want))) s = Math.min(s, 0.8);
            best = Math.max(best, s);
        }
        return best;
    }

    private static Set<String> numbers(String s) {
        Set<String> out = new HashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(s);
        while (m.find()) out.add(m.group().replaceFirst("^0+(?=\\d)", ""));
        return out;
    }

    /** "Frieren: Beyond Journey's End (Dub)" → "Frieren: Beyond Journey's End" */
    static String clean(String title) {
        return title.replaceAll("(?i)[(\\[](dub|sub|dubbed|subbed|uncensored|uncut|tv|eng(lish)? dub)[)\\]]", "")
                .replaceAll("(?i)\\s+(dub|dubbed)$", "").trim();
    }

    private static String norm(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        n = n.replace("&", " and ").replaceAll("\\b(the|season|part|cour)\\b", " ");
        n = n.replaceAll("\\b(\\d+)(st|nd|rd|th)\\b", "$1");
        return n.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** Sørensen–Dice similarity of the two strings' letter pairs. */
    private static double dice(String x, String y) {
        if (x.length() < 2 || y.length() < 2) return x.equals(y) ? 1 : 0;
        java.util.Map<String, Integer> pairs = new java.util.HashMap<>();
        for (int i = 0; i < x.length() - 1; i++) pairs.merge(x.substring(i, i + 2), 1, Integer::sum);
        int hits = 0;
        for (int i = 0; i < y.length() - 1; i++) {
            String p = y.substring(i, i + 2);
            Integer c = pairs.get(p);
            if (c != null && c > 0) {
                hits++;
                pairs.put(p, c - 1);
            }
        }
        return 2.0 * hits / (x.length() - 1 + y.length() - 1);
    }

    private static String error(String message) {
        return App.obj("error", message == null || message.isEmpty() ? "Something went wrong" : message).toString();
    }
}
