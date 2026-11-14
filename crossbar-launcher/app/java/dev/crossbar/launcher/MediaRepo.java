package dev.crossbar.launcher;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONObject;

/** Music, videos and photos from the device's media library. */
final class MediaRepo {
    private final App app;
    private final ContentResolver cr;
    private final LruCache<String, byte[]> thumbs = new LruCache<String, byte[]>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };

    MediaRepo(App app) {
        this.app = app;
        cr = app.getContentResolver();
    }

    static Uri collection(String type) {
        switch (type) {
            case "audio": return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            case "video": return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            default: return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }
    }

    static Uri uri(String type, long id) {
        return ContentUris.withAppendedId(collection(type), id);
    }

    private static boolean musicFolder(String rel) {
        if (rel == null) return false;
        return rel.startsWith("Music/") || rel.equals("Download/") || rel.startsWith("Podcasts/")
                || rel.startsWith("Audiobooks/") || rel.startsWith("Recordings/") || rel.startsWith("Documents/Music/");
    }

    JSONArray songs() {
        JSONArray out = new JSONArray();
        boolean all = app.store.bool("musicAllFolders");
        String[] proj = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.DATE_ADDED};
        String sel = MediaStore.Audio.Media.DURATION + ">=30000";
        try (Cursor c = cr.query(collection("audio"), proj, sel, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE")) {
            while (c != null && c.moveToNext()) {
                String rel = c.getString(6);
                if (!all && !musicFolder(rel)) continue;
                String artist = c.getString(2);
                if (artist == null || artist.equals("<unknown>")) artist = "Unknown Artist";
                String album = c.getString(3);
                if (album == null || album.isEmpty()) album = "Unknown Album";
                out.put(Util.obj("id", c.getLong(0), "title", c.getString(1), "artist", artist, "album", album,
                        "albumId", c.getLong(4), "duration", c.getLong(5), "folder", rel, "track", c.getInt(7),
                        "added", c.getLong(8)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    JSONArray videos() {
        JSONArray out = new JSONArray();
        String[] proj = {MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.MIME_TYPE};
        try (Cursor c = cr.query(collection("video"), proj, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext()) {
                String title = c.getString(1);
                if (title == null || title.isEmpty()) title = Util.baseName(c.getString(2));
                out.put(Util.obj("id", c.getLong(0), "title", title, "file", c.getString(2),
                        "duration", c.getLong(3), "width", c.getInt(4), "height", c.getInt(5),
                        "folder", c.getString(6), "added", c.getLong(7), "size", c.getLong(8), "mime", c.getString(9)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    JSONArray photos() {
        JSONArray out = new JSONArray();
        String[] proj = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT};
        try (Cursor c = cr.query(collection("image"), proj, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            int n = 0;
            while (c != null && c.moveToNext() && n++ < 4000) {
                out.put(Util.obj("id", c.getLong(0), "title", Util.baseName(c.getString(1)), "folder", c.getString(2),
                        "added", c.getLong(3), "width", c.getInt(4), "height", c.getInt(5)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    JSONObject counts() {
        return Util.obj("music", songs().length(), "videos", videos().length(), "photos", photos().length());
    }

    String mimeOf(Uri u) {
        String t = cr.getType(u);
        return t == null ? "application/octet-stream" : t;
    }

    byte[] thumb(String type, long id, int size) {
        String key = type + id + "@" + size;
        byte[] b = thumbs.get(key);
        if (b != null) return b;
        try {
            Bitmap bm = cr.loadThumbnail(uri(type, id), new Size(size, size), null);
            b = Util.jpeg(bm, 86);
            thumbs.put(key, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}
