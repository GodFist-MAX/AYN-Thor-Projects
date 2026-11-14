package dev.crossbar.launcher;

import android.graphics.Bitmap;
import android.media.MediaMetadata;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * What media apps played lately (title, artist or channel, artwork), from their media sessions, for each
 * app's page on the bottom screen. Kept in files/media-history.json with the artwork in files/media-art.
 */
final class MediaHistory {
    private static final int PER_APP = 12;
    private final App app;
    private final File file, artDir;
    private JSONObject byPkg = new JSONObject(); // package → [{title, sub, at, art}], newest first; guarded by this
    private boolean saveQueued;

    MediaHistory(App app) {
        this.app = app;
        file = new File(app.getFilesDir(), "media-history.json");
        artDir = new File(app.getFilesDir(), "media-art");
        String text = Util.readText(file);
        if (text != null) {
            try {
                byPkg = new JSONObject(text);
            } catch (JSONException ignored) {
            }
        }
    }

    /** A session is playing: what it plays goes to the front of the app's list. */
    synchronized void playing(String pkg, MediaMetadata md) {
        if (md == null) return;
        String title = text(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title.isEmpty()) return;
        String sub = text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (sub.isEmpty()) sub = text(md, MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        JSONArray l = byPkg.optJSONArray(pkg);
        JSONObject top = l == null ? null : l.optJSONObject(0);
        long now = System.currentTimeMillis();
        if (top != null && title.equals(top.optString("title"))) {
            if (now - top.optLong("at") > 60_000) {
                Util.put(top, "at", now);
                save();
            }
            return;
        }
        String key = Util.sha1(pkg + "\n" + title + "\n" + sub).substring(0, 16);
        JSONArray out = new JSONArray();
        out.put(Util.obj("title", title, "sub", sub, "at", now, "art", key));
        for (int i = 0; l != null && i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            if (o == null) continue;
            if (out.length() < PER_APP && !title.equals(o.optString("title"))) out.put(o);
            else if (!key.equals(o.optString("art"))) new File(artDir, o.optString("art") + ".jpg").delete();
        }
        Util.put(byPkg, pkg, out);
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        Bitmap art = b;
        if (art != null) {
            app.io.execute(() -> {
                float s = Math.min(1f, 256f / Math.max(art.getWidth(), art.getHeight()));
                Bitmap small = s < 1 ? Bitmap.createScaledBitmap(art, Math.round(art.getWidth() * s), Math.round(art.getHeight() * s), true) : art;
                Util.writeBytes(new File(artDir, key + ".jpg"), Util.jpeg(small, 86));
            });
        }
        save();
    }

    /** Settings › Media Settings › Clear Recently Played Media. */
    synchronized void clear() {
        byPkg = new JSONObject();
        File[] art = artDir.listFiles();
        if (art != null) for (File f : art) f.delete();
        save();
    }

    synchronized Set<String> packages() {
        Set<String> s = new HashSet<>();
        for (Iterator<String> it = byPkg.keys(); it.hasNext(); ) {
            String pkg = it.next();
            JSONArray l = byPkg.optJSONArray(pkg);
            if (l != null && l.length() > 0) s.add(pkg);
        }
        return s;
    }

    synchronized JSONArray items(String pkg) {
        JSONArray l = byPkg.optJSONArray(pkg), out = new JSONArray();
        for (int i = 0; l != null && i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            if (o == null) continue;
            File art = new File(artDir, o.optString("art") + ".jpg");
            out.put(Util.obj("title", o.optString("title"), "sub", o.optString("sub"), "at", o.optLong("at"),
                    "img", art.isFile() ? Util.fileUrl(art.getAbsolutePath()) : "/img/app?pkg=" + Util.enc(pkg),
                    "cmd", Util.obj("t", "launch", "pkg", pkg)));
        }
        return out;
    }

    private void save() {
        if (saveQueued) return;
        saveQueued = true;
        app.main.postDelayed(() -> {
            String text;
            synchronized (this) {
                saveQueued = false;
                text = byPkg.toString();
            }
            app.io.execute(() -> Util.writeText(file, text));
        }, 5000);
    }

    private static String text(MediaMetadata md, String key, String fallback) {
        CharSequence s = md.getText(key);
        if (s == null || s.length() == 0) s = md.getText(fallback);
        return s == null ? "" : s.toString().trim();
    }
}
