package dev.anideck.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plays a downloaded episode on the top screen, over the site. MainActivity gives it the commands
 * meant for the site's player (controller, bottom screen, media controls) and passes its state on in
 * the page's place, so the rest of AniDeck treats it like an episode on the site.
 */
final class OfflinePlayer implements TextureView.SurfaceTextureListener {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** X jumps about an opening's length; downloads don't know where the opening is. */
    private static final double SKIP = 85;
    private static final AudioAttributes ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();

    private final MainActivity a;
    private final FrameLayout root, view, controls;
    private final TextureView video;
    private final TextView subs, title, time, middle;
    private final SeekBar bar;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private final Runnable tick = this::tick, hide = this::hideControls;
    /** MediaPlayer's track numbers of the subtitle tracks. */
    private final List<Integer> texts = new ArrayList<>();

    private JSONObject rec;
    private MediaPlayer mp;
    private Surface surface;
    private boolean prepared, wantPlay, ended, reported, dragging;
    private String pstate = "loading", error = "", lastSent = "";
    private float rate = 1;
    private int vw, vh;
    private JSONArray subNames = new JSONArray();
    /** The subtitle track shown (an index into texts), -1 for none, and the last one shown. */
    private int sub = -1, lastSub = -1;
    private long savedAt;

    OfflinePlayer(MainActivity a, FrameLayout root) {
        this.a = a;
        this.root = root;
        audio = a.getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(ATTRS)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        pause();
                        send();
                    }
                }).build();

        view = new FrameLayout(a);
        view.setBackgroundColor(Color.BLACK);
        view.setOnClickListener(v -> tapped());
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> view.post(this::fit));
        video = new TextureView(a);
        video.setSurfaceTextureListener(this);
        view.addView(video, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));

        subs = new Outlined(a);
        subs.setTextColor(Color.WHITE);
        subs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        subs.setTypeface(Typeface.DEFAULT_BOLD);
        subs.setGravity(Gravity.CENTER);
        subs.setMaxLines(4);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.bottomMargin = dp(28);
        sl.leftMargin = sl.rightMargin = dp(48);
        view.addView(subs, sl);

        middle = new TextView(a);
        middle.setTextColor(Color.WHITE);
        middle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        middle.setGravity(Gravity.CENTER);
        middle.setPadding(dp(28), dp(16), dp(28), dp(16));
        middle.setBackground(round(0xB3000000, dp(16)));
        view.addView(middle, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));

        controls = new FrameLayout(a);
        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(24), dp(14), dp(16), dp(28));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xCC000000, 0}));
        title = new TextView(a);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setSingleLine(true);
        top.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView close = new TextView(a);
        close.setText("✕   Close");
        close.setTextColor(Color.WHITE);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        close.setPadding(dp(20), dp(10), dp(20), dp(10));
        close.setBackground(round(0xE6181822, dp(20)));
        close.setOnClickListener(v -> a.closeDownload());
        top.addView(close, new LinearLayout.LayoutParams(WRAP, WRAP));
        controls.addView(top, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(a);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(24), dp(28), dp(24), dp(14));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xCC000000, 0}));
        time = new TextView(a);
        time.setTextColor(Color.WHITE);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        time.setFontFeatureSettings("tnum");
        bottom.addView(time, new LinearLayout.LayoutParams(WRAP, WRAP));
        bar = new SeekBar(a);
        bar.setFocusable(false);
        bar.setProgressTintList(ColorStateList.valueOf(0xFF7AA7FF));
        bar.setThumbTintList(ColorStateList.valueOf(0xFF7AA7FF));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) time.setText(clock(p) + " / " + clock(duration()));
            }

            @Override public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
                h.removeCallbacks(hide);
            }

            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                seekTo(s.getProgress());
                showControls();
                send();
            }
        });
        bottom.addView(bar, new LinearLayout.LayoutParams(0, WRAP, 1));
        controls.addView(bottom, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
        view.addView(controls, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    boolean isOpen() {
        return rec != null;
    }

    /** Starts a downloaded episode, where it was left off; another one playing stops first. */
    void open(JSONObject r) {
        stop();
        rec = r;
        prepared = ended = reported = false;
        wantPlay = true;
        pstate = "loading";
        error = "";
        vw = vh = 0;
        sub = lastSub = -1;
        texts.clear();
        subNames = names(r);
        subs.setText("");
        String epTitle = App.str(r, "epTitle");
        title.setText(App.str(r, "show") + "   ·   Episode " + r.optInt("ep") + (epTitle.isEmpty() ? "" : " — " + epTitle));
        if (view.getParent() == null) root.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
        view.bringToFront();
        if (video.isAvailable()) prepare(video.getSurfaceTexture());
        showControls();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    void close() {
        if (rec == null) return;
        stop();
        rec = null;
        lastSent = "";
        h.removeCallbacks(tick);
        h.removeCallbacks(hide);
        audio.abandonAudioFocusRequest(focus);
        root.removeView(view);
    }

    private void stop() {
        if (rec == null) return;
        savePosition();
        if (mp != null) mp.release();
        mp = null;
        prepared = false;
        view.setKeepScreenOn(false);
    }

    // ---- playback ----

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hgt) {
        prepare(st);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hgt) {
    }

    // Also when the app goes to the background; a new surface comes when it's back.
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (mp != null) mp.setSurface(null);
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    private void prepare(SurfaceTexture st) {
        if (rec == null) return;
        if (surface != null) surface.release();
        surface = new Surface(st);
        if (mp != null) {
            mp.setSurface(surface);
            return;
        }
        mp = new MediaPlayer();
        mp.setAudioAttributes(ATTRS);
        mp.setSurface(surface);
        mp.setOnPreparedListener(this::prepared);
        mp.setOnCompletionListener(m -> {
            ended = true;
            wantPlay = false;
            pstate = "paused";
            view.setKeepScreenOn(false);
            savePosition();
            showControls();
            send();
        });
        mp.setOnErrorListener((m, what, extra) -> {
            Log.w(App.TAG, "offline player error " + what + "/" + extra);
            failed("The episode couldn't be played");
            return true;
        });
        mp.setOnVideoSizeChangedListener((m, w, hgt) -> {
            vw = w;
            vh = hgt;
            fit();
        });
        mp.setOnTimedTextListener((m, t) -> subs.setText(t == null || t.getText() == null ? "" : t.getText().trim()));
        try {
            mp.setDataSource(a, Downloads.get(a).uri(rec));
            mp.prepareAsync();
        } catch (IOException | RuntimeException e) {
            Log.w(App.TAG, "offline player: " + e);
            failed("The downloaded file is missing or can't be read");
        }
    }

    private void prepared(MediaPlayer m) {
        prepared = true;
        texts.clear();
        MediaPlayer.TrackInfo[] info = m.getTrackInfo();
        for (int i = 0; i < info.length; i++) {
            if (info[i].getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) texts.add(i);
        }
        if (subNames.length() != texts.size()) {
            if (subNames.length() > 0) Log.w(App.TAG, "subtitle names: " + subNames.length() + ", tracks: " + texts.size());
            subNames = new JSONArray();
            for (int i = 0; i < texts.size(); i++) subNames.put("Subtitles " + (i + 1));
        }
        pickSub(preferredSub());
        double t = rec.optDouble("t", 0), d = m.getDuration() / 1000.0;
        if (t > 5 && t < d - 20) m.seekTo((long) (t * 1000), MediaPlayer.SEEK_CLOSEST);
        if (wantPlay) play();
        else pstate = "paused";
        send();
    }

    private void failed(String why) {
        pstate = "error";
        error = why;
        wantPlay = false;
        view.setKeepScreenOn(false);
        showControls();
        send();
    }

    private void play() {
        wantPlay = true;
        if (!prepared || mp == null || "error".equals(pstate)) return;
        if (ended) {
            mp.seekTo(0);
            ended = false;
        }
        audio.requestAudioFocus(focus);
        mp.start();
        if (rate != 1) speed();
        pstate = "playing";
        view.setKeepScreenOn(true);
    }

    private void pause() {
        wantPlay = false;
        if (prepared && mp != null && mp.isPlaying()) mp.pause();
        if (!"error".equals(pstate)) pstate = prepared ? "paused" : pstate;
        view.setKeepScreenOn(false);
        savePosition();
    }

    /** Setting a speed while paused would start playback, so it waits for play(). */
    private void speed() {
        try {
            mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(rate));
        } catch (RuntimeException e) {
            Log.w(App.TAG, "playback speed: " + e);
        }
    }

    private void seekTo(double t) {
        if (!prepared || mp == null) return;
        double d = duration();
        t = Math.max(0, d > 1 ? Math.min(t, d - 1) : t);
        mp.seekTo((long) (t * 1000), MediaPlayer.SEEK_CLOSEST);
        ended = false;
    }

    private double position() {
        return prepared && mp != null ? mp.getCurrentPosition() / 1000.0 : rec.optDouble("t", 0);
    }

    private double duration() {
        return prepared && mp != null ? mp.getDuration() / 1000.0 : rec.optDouble("d", 0);
    }

    private void step(int dir) {
        JSONObject n = Downloads.get(a).neighbor(rec, dir);
        if (n != null) open(n);
        else a.showToast(dir > 0 ? "No later episode of this show is downloaded" : "No earlier episode of this show is downloaded");
    }

    private void savePosition() {
        if (rec == null || !prepared || mp == null) return;
        savedAt = SystemClock.uptimeMillis();
        Downloads.get(a).position(App.str(rec, "id"), ended ? duration() : position(), duration());
    }

    // ---- subtitles ----

    /** "English (Full Subtitles / ASS / CR)" → "English (Full Subtitles / CR)": the format means nothing here. */
    private static JSONArray names(JSONObject r) {
        JSONArray out = new JSONArray(), subs = r.optJSONArray("subs");
        for (int i = 0; subs != null && i < subs.length(); i++) {
            JSONObject s = subs.optJSONObject(i);
            String n = s == null ? "" : App.str(s, "name").replaceAll("(?i)\\s*/\\s*(ASS|SSA|SRT|VTT|PGS)\\b", "").trim();
            out.put(n.isEmpty() ? "Subtitles " + (i + 1) : n);
        }
        return out;
    }

    /** The subtitles picked last time if this file has them, else the ones the file turns on. */
    private int preferredSub() {
        String want = App.I.prefs.getString("dlSub", "");
        if ("off".equals(want)) return -1;
        for (int i = 0; i < subNames.length(); i++) if (subNames.optString(i).equals(want)) return i;
        return fileSub();
    }

    private int fileSub() {
        JSONArray s = rec.optJSONArray("subs");
        for (int i = 0; s != null && s.length() == texts.size() && i < s.length(); i++) {
            JSONObject o = s.optJSONObject(i);
            if (o != null && o.optBoolean("on")) return i;
        }
        return texts.isEmpty() ? -1 : 0;
    }

    private void pickSub(int i) {
        if (mp == null || !prepared) return;
        try {
            if (sub >= 0) mp.deselectTrack(texts.get(sub));
            sub = i >= 0 && i < texts.size() ? i : -1;
            if (sub >= 0) mp.selectTrack(texts.get(sub));
        } catch (RuntimeException e) {
            Log.w(App.TAG, "subtitle track: " + e);
            sub = -1;
        }
        if (sub >= 0) lastSub = sub;
        subs.setText("");
    }

    /** A pick from the bottom screen (or Y), remembered for the next episode. */
    private void chooseSub(int i) {
        if (texts.isEmpty()) {
            a.showToast("This episode has no subtitle tracks");
            return;
        }
        pickSub(i);
        App.I.prefs.edit().putString("dlSub", sub >= 0 ? subNames.optString(sub) : "off").apply();
        a.showToast(sub >= 0 ? "Subtitles: " + subNames.optString(sub) : "Subtitles off");
    }

    // ---- commands and state ----

    /** A command meant for the site's player; false for the ones about the page (menus, highlight). */
    boolean cmd(JSONObject c) {
        switch (App.str(c, "c")) {
            case "toggle":
                if (wantPlay) pause();
                else play();
                break;
            case "play":
            case "autoplay":
                play();
                break;
            case "pause":
                pause();
                break;
            case "seekBy": seekTo(position() + c.optDouble("d", 0)); break;
            case "seek":
                seekTo(c.optDouble("t", 0));
                if (c.optBoolean("play")) play();
                break;
            case "skip": seekTo(position() + SKIP); break;
            case "next": step(1); break;
            case "prev": step(-1); break;
            case "episode": {
                JSONObject n = Downloads.get(a).neighbor(App.obj("a", rec.optInt("a"), "show", App.str(rec, "show"), "ep", c.optInt("ep") - 1), 1);
                if (n != null && n.optInt("ep") == c.optInt("ep")) open(n);
                break;
            }
            case "rate":
                rate = (float) Math.max(0.25, Math.min(3, c.optDouble("r", 1)));
                if (prepared && mp != null && mp.isPlaying()) speed();
                break;
            case "captions":
            case "theater":
                chooseSub(sub >= 0 ? -1 : lastSub >= 0 ? lastSub : fileSub());
                break;
            case "sub": chooseSub(c.optInt("i", -1)); break;
            default:
                return false;
        }
        showControls();
        send();
        return true;
    }

    /** Like the page's state from inject.js, with offline: true and the download's subtitle tracks. */
    private JSONObject state() {
        Downloads d = Downloads.get(a);
        double t = Math.round(position() * 10) / 10.0, dur = Math.round(duration() * 10) / 10.0;
        return App.obj("url", "anideck:download/" + App.str(rec, "id"), "path", App.str(rec, "path"), "page", "watch",
                "offline", true, "download", App.str(rec, "id"), "show", App.str(rec, "show"), "anilistId", rec.optInt("a"),
                "ep", rec.optInt("ep"), "epTitle", App.str(rec, "epTitle"), "epCount", 0,
                "hasPrev", d.neighbor(rec, -1) != null, "hasNext", d.neighbor(rec, 1) != null,
                "player", App.obj("state", pstate, "t", t, "d", dur, "error", error),
                "theater", true, "menu", JSONObject.NULL, "nav", false, "typing", false, "popup", false,
                "subs", subNames, "sub", sub, "rate", rate, "quality", App.str(rec, "quality"));
    }

    private void send() {
        if (rec == null) return;
        middle.setVisibility("playing".equals(pstate) ? View.GONE : View.VISIBLE);
        middle.setText("error".equals(pstate) ? error : !prepared ? "Loading…" : ended ? "Finished. Press A to watch again" : "Paused");
        JSONObject s = state();
        String json = s.toString();
        if (json.equals(lastSent)) return;
        lastSent = json;
        a.playerState(s);
    }

    private void tick() {
        if (rec == null) return;
        double t = position(), d = duration();
        if (!dragging) {
            bar.setMax((int) Math.max(1, d));
            bar.setProgress((int) t);
            time.setText(clock(t) + " / " + clock(d));
        }
        if (!reported && prepared && d > 0 && t / d >= 0.85) {
            reported = true;
            App.I.library.watched(rec.optInt("a"), rec.optInt("ep"));
        }
        if (prepared && mp != null && mp.isPlaying() && SystemClock.uptimeMillis() - savedAt > 15000) savePosition();
        send();
        h.postDelayed(tick, 500);
    }

    // ---- touch and controls ----

    /** A tap shows the controls; another while they're up plays or pauses. */
    private void tapped() {
        if (controls.getVisibility() != View.VISIBLE) {
            showControls();
            return;
        }
        cmd(App.obj("c", "toggle"));
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        subs.setTranslationY(-dp(64));
        h.removeCallbacks(hide);
        h.postDelayed(hide, 3500);
    }

    /** They stay up while paused. */
    private void hideControls() {
        if (dragging || !wantPlay) {
            h.postDelayed(hide, 3500);
            return;
        }
        controls.setVisibility(View.GONE);
        subs.setTranslationY(0);
    }

    private void fit() {
        int w = view.getWidth(), hgt = view.getHeight();
        if (vw <= 0 || vh <= 0 || w <= 0 || hgt <= 0) return;
        float k = Math.min((float) w / vw, (float) hgt / vh);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) video.getLayoutParams();
        int fw = Math.round(vw * k), fh = Math.round(vh * k);
        if (lp.width == fw && lp.height == fh) return;
        lp.width = fw;
        lp.height = fh;
        video.setLayoutParams(lp);
    }

    private static String clock(double s) {
        long n = Math.max(0, Math.round(s));
        return n >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", n / 3600, n / 60 % 60, n % 60)
                : String.format(Locale.ROOT, "%d:%02d", n / 60, n % 60);
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    /** White text with a black outline, readable on any picture. */
    private static final class Outlined extends TextView {
        private boolean drawing;

        Outlined(Context c) {
            super(c);
        }

        // Changing the color to draw the outline would otherwise ask for another frame, forever.
        @Override public void invalidate() {
            if (!drawing) super.invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            drawing = true;
            Paint p = getPaint();
            int fill = getCurrentTextColor();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(getTextSize() / 7f);
            setTextColor(Color.BLACK);
            super.onDraw(c);
            p.setStyle(Paint.Style.FILL);
            setTextColor(fill);
            super.onDraw(c);
            drawing = false;
        }
    }
}
