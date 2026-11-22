package dev.grokbuild.coronamon;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Coronamon's TV: plays what you watched lately, muted, in his room. The list comes from CrossBar
 * (library videos you played in its player) and AniDeck (downloaded episodes you've watched), newest
 * first, leaving out whatever is on the top screen right now. Each video plays from the start up to
 * where you stopped, so his TV never shows you anything you haven't seen yet. Where the TV got to
 * in each one is kept in files/tv.json.
 */
final class Tv implements TextureView.SurfaceTextureListener {
    interface Listener {
        /** The picture's size: fit the TV screen to it. */
        void picture(int w, int h);

        /** A video started on the TV, or null when it went dark. */
        void showing(Item it);
    }

    static final class Item {
        String key, title, sub, app, path, uri;
        long id, pos, dur, at;
        JSONObject open;

        String label() {
            return sub == null || sub.isEmpty() ? title : title + " · " + sub;
        }

        JSONObject toJson() {
            return Util.obj("key", key, "title", title, "sub", sub, "app", app, "pos", pos, "dur", dur, "at", at);
        }
    }

    private static final Uri CROSSBAR = Uri.parse("content://dev.crossbar.launcher.recent");
    private static final Uri ANIDECK = Uri.parse("content://dev.anideck.app.feed");
    /** Videos you stopped this early in start over next time, so they play to the end. */
    private static final long MIN_SPOT = 60_000;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper()), work;
    private final File spotsFile;
    private final JSONObject spots;
    private List<Item> items = new ArrayList<>();
    private final Set<String> broken = new HashSet<>();
    private long fetchedAt;
    private boolean fetching;
    private MediaPlayer mp;
    private boolean prepared;
    private Item cur;
    private TextureView view;
    private Surface surface;
    private boolean on;
    private long lastSpotSave;
    Listener listener;

    Tv(Context ctx) {
        this.ctx = ctx;
        HandlerThread t = new HandlerThread("tv");
        t.start();
        work = new Handler(t.getLooper());
        spotsFile = new File(ctx.getFilesDir(), "tv.json");
        JSONObject s = Util.readJson(spotsFile);
        spots = s == null ? new JSONObject() : s;
    }

    /** Whether there's anything to watch. */
    boolean ready() {
        for (Item it : items) if (!broken.contains(it.key)) return true;
        return false;
    }

    /** Whether a picture is showing (playing or paused on a frame). */
    boolean picture() {
        return mp != null && prepared;
    }

    Item current() {
        return cur;
    }

    long fetchedAt() {
        return fetchedAt;
    }

    // ---- the list ----

    void refresh() {
        if (fetching) return;
        fetching = true;
        work.post(() -> {
            List<Item> list = new ArrayList<>();
            fetch(list, CROSSBAR, "CrossBar");
            fetch(list, ANIDECK, "AniDeck");
            list.sort((a, b) -> Long.compare(b.at, a.at));
            main.post(() -> {
                fetching = false;
                fetchedAt = System.currentTimeMillis();
                items = list;
                if (cur != null && indexOf(cur.key) < 0) next();
                else if (on && surface != null && mp == null) next();
            });
        });
    }

    private void fetch(List<Item> out, Uri uri, String app) {
        JSONArray a;
        try {
            Bundle b = ctx.getContentResolver().call(uri, "recent", null, null);
            a = b == null ? null : Util.parse(b.getString("json")).optJSONArray("items");
        } catch (Exception e) {
            // Not installed, or a version from before it shared what you watched.
            return;
        }
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optBoolean("playing")) continue;
            Item it = new Item();
            it.key = o.optString("key");
            it.title = o.optString("title", "Video");
            it.sub = o.optString("sub");
            it.app = app;
            it.path = o.optString("path");
            it.uri = o.optString("uri");
            it.id = o.optLong("id");
            it.pos = o.optLong("pos");
            it.dur = o.optLong("dur");
            it.at = o.optLong("at");
            it.open = o.optJSONObject("open");
            if (it.key.isEmpty() || (it.path.isEmpty() && it.uri.isEmpty())) continue;
            out.add(it);
        }
    }

    private int indexOf(String key) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).key.equals(key)) return i;
        return -1;
    }

    // ---- the screen ----

    void attach(TextureView v) {
        view = v;
        v.setSurfaceTextureListener(this);
        if (v.isAvailable()) onSurfaceTextureAvailable(v.getSurfaceTexture(), v.getWidth(), v.getHeight());
    }

    void detach() {
        stop(true);
        if (view != null) view.setSurfaceTextureListener(null);
        view = null;
        if (surface != null) surface.release();
        surface = null;
    }

    /** Off while he sleeps, and while there's no room on screen. */
    void setOn(boolean v) {
        if (v == on) return;
        on = v;
        if (!on) {
            stop(true);
            if (listener != null) listener.showing(null);
        } else if (surface != null && mp == null) {
            resume();
        }
    }

    void quit() {
        detach();
        work.getLooper().quitSafely();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (surface != null) surface.release();
        surface = new Surface(st);
        if (on && mp == null) resume();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        stop(true);
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    // ---- playing ----

    private void resume() {
        if (cur != null && indexOf(cur.key) >= 0 && !broken.contains(cur.key)) play(cur);
        else next();
    }

    void next() {
        step(1);
    }

    void prev() {
        step(-1);
    }

    private void step(int dir) {
        int n = items.size();
        int at = cur == null ? -1 : indexOf(cur.key);
        if (at < 0) at = dir > 0 ? -1 : 1;
        for (int k = 1; k <= n; k++) {
            Item it = items.get(((at + dir * k) % n + n) % n);
            if (!broken.contains(it.key)) {
                if (on && surface != null) play(it);
                else cur = it;
                return;
            }
        }
        stop(true);
        cur = null;
        if (listener != null) listener.showing(null);
    }

    private void play(Item it) {
        stop(true);
        cur = it;
        if (!on || surface == null) return;
        MediaPlayer p = new MediaPlayer();
        mp = p;
        prepared = false;
        p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
        p.setVolume(0, 0);
        p.setSurface(surface);
        p.setOnPreparedListener(x -> {
            if (mp != x) return;
            prepared = true;
            long from = startAt(it);
            if (from > 0) x.seekTo(from, MediaPlayer.SEEK_CLOSEST_SYNC);
            x.start();
            if (listener != null) {
                if (x.getVideoWidth() > 0) listener.picture(x.getVideoWidth(), x.getVideoHeight());
                listener.showing(it);
            }
            main.removeCallbacks(tick);
            main.postDelayed(tick, 2000);
        });
        p.setOnVideoSizeChangedListener((x, w, h) -> {
            if (mp == x && w > 0 && h > 0 && listener != null) listener.picture(w, h);
        });
        p.setOnCompletionListener(x -> {
            if (mp == x) finished(it);
        });
        p.setOnErrorListener((x, what, extra) -> {
            if (mp == x) failed(it, what + "/" + extra);
            return true;
        });
        try {
            File f = it.path.isEmpty() ? null : new File(it.path);
            if (f != null && f.canRead()) p.setDataSource(f.getPath());
            else p.setDataSource(ctx, Uri.parse(it.uri));
            p.prepareAsync();
        } catch (Exception e) {
            failed(it, e.toString());
        }
    }

    private void failed(Item it, String why) {
        Log.w("tv: can't play " + it.title + ": " + why);
        broken.add(it.key);
        main.post(this::next);
    }

    /** Where you stopped, if that's far enough in to be worth stopping at; the TV plays up to there. */
    private static long limit(Item it) {
        return it.pos > MIN_SPOT ? it.pos : Long.MAX_VALUE;
    }

    private long startAt(Item it) {
        long s = spots.optLong(it.key, 0);
        return s > 0 && s < limit(it) - 5000 && (it.dur <= 0 || s < it.dur - 5000) ? s : 0;
    }

    private void finished(Item it) {
        spots.remove(it.key);
        saveSpots(true);
        next();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            MediaPlayer p = mp;
            if (p == null || !prepared || cur == null) return;
            long at;
            try {
                at = p.getCurrentPosition();
            } catch (IllegalStateException e) {
                return;
            }
            if (at >= limit(cur)) {
                finished(cur);
                return;
            }
            Util.put(spots, cur.key, at);
            saveSpots(false);
            main.postDelayed(this, 2000);
        }
    };

    private void stop(boolean keepSpot) {
        main.removeCallbacks(tick);
        MediaPlayer p = mp;
        if (p == null) return;
        mp = null;
        if (keepSpot && prepared && cur != null) {
            try {
                Util.put(spots, cur.key, p.getCurrentPosition());
            } catch (IllegalStateException ignored) {
            }
            saveSpots(true);
        }
        prepared = false;
        try {
            p.release();
        } catch (Exception ignored) {
        }
    }

    private void saveSpots(boolean now) {
        long t = System.currentTimeMillis();
        if (!now && t - lastSpotSave < 15_000) return;
        lastSpotSave = t;
        Set<String> keep = new HashSet<>();
        for (Item it : items) keep.add(it.key);
        if (!keep.isEmpty()) {
            for (Iterator<String> k = spots.keys(); k.hasNext(); ) if (!keep.contains(k.next())) k.remove();
        }
        Util.writeText(spotsFile, spots.toString());
    }

    // ---- watching it properly ----

    /** Opens what's on his TV on the top screen, in the app it's from, where you left off. */
    boolean watchOnTop() {
        Item it = cur;
        if (it == null) return false;
        try {
            if ("CrossBar".equals(it.app)) {
                Bundle b = ctx.getContentResolver().call(CROSSBAR, "open", String.valueOf(it.id), null);
                PendingIntent pi = b == null ? null : (PendingIntent) b.getParcelable("intent");
                if (pi == null) return false;
                pi.send(ctx, 0, null, null, null, null, Screens.on(Screens.TOP_ID));
            } else {
                Intent i = ctx.getPackageManager().getLaunchIntentForPackage("dev.anideck.app");
                if (i == null) return false;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (it.open != null) {
                    for (Iterator<String> k = it.open.keys(); k.hasNext(); ) {
                        String key = k.next();
                        i.putExtra(key, it.open.optString(key));
                    }
                }
                ctx.startActivity(i, Screens.on(Screens.TOP_ID));
            }
            return true;
        } catch (Exception e) {
            Log.w("tv: watch on top: " + e);
            return false;
        }
    }

    JSONObject status() {
        JSONArray list = new JSONArray();
        for (Item it : items) {
            JSONObject o = it.toJson();
            Util.put(o, "broken", broken.contains(it.key));
            list.put(o);
        }
        return Util.obj("on", on, "picture", picture(), "now", cur == null ? null : cur.toJson(), "items", list,
                "fetchedAt", fetchedAt);
    }
}
