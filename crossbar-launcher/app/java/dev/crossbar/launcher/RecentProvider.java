package dev.crossbar.launcher;

import android.app.PendingIntent;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * What was watched lately in CrossBar's player, for Coronamon's TV on the bottom screen.
 * call("recent") returns {"v":1,"items":[{key, title, sub, path, uri, pos, dur, at, playing}]}, newest
 * first, with pos and dur in ms; playing marks the video in the player now. call("open", id) returns
 * a PendingIntent ("intent") that plays that video in the player. Only Coronamon and adb may call it.
 */
public final class RecentProvider extends ContentProvider {
    private static final String CORONAMON = "dev.grokbuild.coronamon";
    private static final int SHELL_UID = 2000;

    @Override
    public boolean onCreate() {
        return true;
    }

    private void check() {
        int uid = Binder.getCallingUid();
        if (uid == SHELL_UID || uid == Process.myUid()) return;
        String[] pkgs = getContext().getPackageManager().getPackagesForUid(uid);
        if (pkgs != null) for (String p : pkgs) if (CORONAMON.equals(p)) return;
        throw new SecurityException("CrossBar's watch history is only for Coronamon");
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        check();
        Bundle b = new Bundle();
        if ("recent".equals(method)) {
            b.putString("json", recent().toString());
        } else if ("open".equals(method) && arg != null) {
            PendingIntent pi = open(arg);
            if (pi == null) return null;
            b.putParcelable("intent", pi);
        } else {
            return null;
        }
        return b;
    }

    private JSONObject recent() {
        JSONArray items = new JSONArray();
        App app = App.I;
        List<JSONObject> list = app == null ? List.of() : app.store.recentVideos(16);
        String[] proj = {MediaStore.Video.Media.DATA, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME};
        for (JSONObject h : list) {
            if (items.length() >= 12) break;
            long id = h.optLong("id");
            Uri u = MediaRepo.uri("video", id);
            String path, name, folder;
            long dur;
            try (Cursor c = getContext().getContentResolver().query(u, proj, null, null, null)) {
                if (c == null || !c.moveToFirst()) continue;
                path = c.getString(0);
                dur = c.getLong(1);
                name = c.getString(2);
                folder = c.getString(3);
            } catch (RuntimeException e) {
                continue;
            }
            String[] n = names(h, name, folder);
            items.put(Util.obj("key", "crossbar:" + id, "id", id, "title", n[0], "sub", n[1], "path", path == null ? "" : path,
                    "uri", u.toString(), "pos", h.optLong("pos"), "dur", dur, "at", h.optLong("last"),
                    "playing", PlayerActivity.playingId == id));
        }
        return Util.obj("v", 1, "items", items);
    }

    /** The name CrossBar showed when you played it, or else the file's name and its folder. */
    private static String[] names(JSONObject h, String file, String folder) {
        String title = h == null ? "" : h.optString("title");
        if (!title.isEmpty() && !"Video".equals(title)) return new String[]{title, h.optString("sub")};
        return new String[]{file == null ? "Video" : file.replaceFirst("\\.[A-Za-z0-9]{2,4}$", ""), folder == null ? "" : folder};
    }

    private PendingIntent open(String arg) {
        long id;
        try {
            id = Long.parseLong(arg);
        } catch (NumberFormatException e) {
            return null;
        }
        App app = App.I;
        JSONObject h = app == null ? null : app.store.history().optJSONObject("video:" + id);
        String file = null, folder = null;
        try (Cursor c = getContext().getContentResolver().query(MediaRepo.uri("video", id),
                new String[]{MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.BUCKET_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                file = c.getString(0);
                folder = c.getString(1);
            }
        } catch (RuntimeException ignored) {
        }
        String[] n = names(h, file, folder);
        JSONArray list = new JSONArray().put(Util.obj("id", id, "title", n[0], "sub", n[1]));
        Intent i = new Intent(getContext(), PlayerActivity.class)
                .setData(MediaRepo.uri("video", id))
                .putExtra(PlayerActivity.EXTRA_LIST, list.toString())
                .putExtra(PlayerActivity.EXTRA_INDEX, 0)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (app != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                app.opening(true, false);
                if (MusicService.playing()) MusicService.command(app, "pause", null);
            });
        }
        return PendingIntent.getActivity(getContext(), (int) id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
