package dev.crossbar.launcher;

import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Movies downloaded in the Google TV app. Android keeps other apps' Android/data folders
 * private, so besides a direct look (which works only where the platform allows it) the
 * list also comes from /sdcard/CrossBar/googletv.json, which the USB sync script writes.
 * The video files are DRM-protected, so they always open in Google TV itself.
 */
final class GoogleTv {
    static final String PKG = "com.google.android.videos";
    private static final String TAG = "CrossBar";
    private static final long RETRY_MS = 86400_000L;
    private static final int META_VERSION = 5;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{6,}(\\.[A-Za-z0-9]{1,4})?");
    // Google Play only includes the Open Graph tags for browser user agents.
    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
    private static volatile boolean fetching;

    private GoogleTv() {}

    static JSONArray downloads(App app) {
        Set<String> ids = new LinkedHashSet<>();
        File files = new File(Environment.getExternalStorageDirectory(), "Android/data/" + PKG + "/files");
        for (String dir : new String[] {"Movies", "Download"}) {
            File[] accounts = new File(files, dir).listFiles();
            if (accounts == null) continue;
            for (File a : accounts) {
                File[] movies = a.listFiles();
                if (movies != null) for (File m : movies) addId(ids, m.getName());
            }
        }
        JSONObject synced = Util.readJson(new File(Store.EXT_DIR, "googletv.json"));
        if (synced != null) {
            JSONArray arr = synced.optJSONArray("movies");
            for (int i = 0; arr != null && i < arr.length(); i++) addId(ids, arr.optString(i));
        }
        File cacheFile = new File(app.getFilesDir(), "gtv_meta.json");
        JSONObject cache = Util.readJson(cacheFile);
        if (cache == null) cache = new JSONObject();
        JSONArray out = new JSONArray();
        boolean missing = false;
        long now = System.currentTimeMillis();
        for (String id : ids) {
            JSONObject m = cache.optJSONObject(id);
            String title = m == null ? "" : m.optString("title", "");
            String poster = m == null ? "" : m.optString("poster", "");
            if (stale(m, now)) missing = true;
            boolean portrait = !poster.isEmpty();
            String thumbs = "https://i.ytimg.com/vi/" + videoId(id);
            out.put(Util.obj("id", id, "title", title, "portrait", portrait,
                    "poster", portrait ? poster : thumbs + "/hqdefault.jpg",
                    "hero", portrait ? poster : thumbs + "/maxresdefault.jpg",
                    "url", "https://play.google.com/store/movies/details?id=" + id));
        }
        if (missing && !fetching) {
            fetching = true;
            final JSONObject c = cache;
            app.io.execute(() -> {
                try {
                    for (String id : ids) {
                        if (!stale(c.optJSONObject(id), System.currentTimeMillis())) continue;
                        Util.put(c, id, fetchMeta(id));
                    }
                    Util.writeText(cacheFile, c.toString());
                } finally {
                    fetching = false;
                }
            });
        }
        return out;
    }

    private static boolean stale(JSONObject m, long now) {
        if (m == null || m.optInt("v") < META_VERSION) return true;
        return m.optString("title", "").isEmpty() && now - m.optLong("t") > RETRY_MS;
    }

    /** Title and poster from the movie's Google Play page, falling back to YouTube's oEmbed title. */
    private static JSONObject fetchMeta(String id) {
        JSONObject m = Util.obj("title", "", "poster", "", "t", System.currentTimeMillis(), "v", META_VERSION);
        try {
            String html = Util.httpGet("https://play.google.com/store/movies/details?id=" + Util.enc(id) + "&hl=en&gl=US", 15000, BROWSER_UA);
            String title = og(html, "og:title");
            if (title != null) Util.put(m, "title", title.replaceAll("\\s+-\\s+Movies on Google Play$", "").trim());
            String image = og(html, "og:image");
            if (image != null && image.startsWith("https://")) Util.put(m, "poster", image);
            if (title == null) Log.w(TAG, "google play page without og:title (" + html.length() + " chars)");
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            Log.w(TAG, "google play lookup failed: " + (msg.matches("HTTP \\d+") ? msg : e.getClass().getSimpleName()));
        }
        if (m.optString("title", "").isEmpty()) {
            try {
                String url = "https://www.youtube.com/oembed?format=json&url=" + Util.enc("https://www.youtube.com/watch?v=" + videoId(id));
                Util.put(m, "title", new JSONObject(Util.httpGet(url, 10000)).optString("title", ""));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static String og(String html, String prop) {
        String p = Pattern.quote(prop);
        Matcher mm = Pattern.compile("<meta[^>]+property=\"" + p + "\"[^>]+content=\"([^\"]*)\"").matcher(html);
        if (!mm.find()) {
            mm = Pattern.compile("<meta[^>]+content=\"([^\"]*)\"[^>]+property=\"" + p + "\"").matcher(html);
            if (!mm.find()) return null;
        }
        return unescape(mm.group(1));
    }

    private static String unescape(String s) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    /** Store ids can carry a suffix such as ".P"; Google Play only knows the movie by the full id. */
    private static void addId(Set<String> ids, String name) {
        if (name == null) return;
        String id = name.startsWith("yt:movie:") ? name.substring(9) : name;
        if (ID.matcher(id).matches()) ids.add(id);
    }

    /** The YouTube video id, which is what i.ytimg.com thumbnails use. */
    private static String videoId(String id) {
        int dot = id.indexOf('.');
        return dot > 0 ? id.substring(0, dot) : id;
    }
}
