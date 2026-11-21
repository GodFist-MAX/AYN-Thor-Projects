package dev.crossbar.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CrossBar's own video player. Plays a list of library videos full screen, and keeps playing in a
 * picture-in-picture window over the menu when the user leaves: Home while a video plays, or X.
 */
public class PlayerActivity extends Activity implements VideoEngine.Listener {
    /** JSON array of {id, title, sub}: the videos L1 and R1 move through, in order. */
    static final String EXTRA_LIST = "dev.crossbar.launcher.VIDEOS";
    static final String EXTRA_INDEX = "dev.crossbar.launcher.INDEX";
    private static final String ACTION_PIP = "dev.crossbar.launcher.PIP_ACTION";
    private static final int A_REWIND = 1, A_PLAY = 2, A_PAUSE = 3, A_FORWARD = 4, A_NEXT = 5;
    private static final long STEP_US = 10_000_000L, JUMP_US = 60_000_000L;

    private static final class Item {
        final long id;
        final String title, sub;

        Item(long id, String title, String sub) {
            this.id = id;
            this.title = title;
            this.sub = sub;
        }
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();
    private int index;
    private VideoEngine engine;
    private Surface surface;
    private float aspect = 16f / 9f;
    private boolean inPip;

    private Fit fit;
    private SurfaceView video;
    private View controls;
    private TextView title, sub, pos, dur, note;
    private Bar bar;
    private ImageButton playBtn, prevBtn, nextBtn;
    private ImageView flash;
    private ProgressBar spinner;

    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest focus;
    private boolean pausedForFocus;

    private final Runnable hideControls = () -> showControls(false);
    private final Runnable hideNote = () -> note.animate().alpha(0).setDuration(400).start();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            progress();
            h.postDelayed(this, 250);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) {
                pause();
                return;
            }
            switch (i.getIntExtra("a", 0)) {
                case A_REWIND: seekBy(-STEP_US); break;
                case A_FORWARD: seekBy(STEP_US); break;
                case A_PLAY: play(); break;
                case A_PAUSE: pause(); break;
                case A_NEXT: go(1); break;
                default: break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        buildViews();
        hideSystemUi();
        am = getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setOnAudioFocusChangeListener(this::onFocusChange, h)
                .build();
        session = new MediaSession(this, "CrossBar Video");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSeekTo(long ms) { seekTo(ms * 1000); }
            @Override public void onSkipToNext() { go(1); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onFastForward() { seekBy(STEP_US); }
            @Override public void onRewind() { seekBy(-STEP_US); }
            @Override public void onStop() { close(); }
        }, h);
        session.setActive(true);
        IntentFilter f = new IntentFilter(ACTION_PIP);
        f.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        load(getIntent());
        open(index);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        long was = engine != null && !items.isEmpty() ? items.get(index).id : -1;
        saveSpot();
        load(i);
        if (!items.isEmpty() && items.get(index).id == was) {
            // The same video chosen again, as when bringing it back from picture-in-picture, carries on instead of restarting.
            showItem();
            play();
            poke();
            return;
        }
        open(index);
    }

    private void load(Intent i) {
        items.clear();
        index = 0;
        String json = i.getStringExtra(EXTRA_LIST);
        if (json != null) {
            try {
                JSONArray a = new JSONArray(json);
                for (int k = 0; k < a.length(); k++) {
                    JSONObject o = a.getJSONObject(k);
                    items.add(new Item(o.getLong("id"), o.optString("title", "Video"), o.optString("sub")));
                }
                index = Math.max(0, Math.min(i.getIntExtra(EXTRA_INDEX, 0), items.size() - 1));
            } catch (Exception e) {
                items.clear();
            }
        }
        Uri u = i.getData();
        if (items.isEmpty() && u != null) {
            try {
                items.add(new Item(ContentUris.parseId(u), titleOf(u), ""));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private String titleOf(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{MediaStore.Video.Media.TITLE}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {
        }
        return "Video";
    }

    private static Uri uri(Item it) {
        return MediaRepo.uri("video", it.id);
    }

    /** Starts items[i], where the user left off last time. */
    private void open(int i) {
        if (items.isEmpty()) {
            finish();
            return;
        }
        if (engine != null) {
            engine.release();
            engine = null;
        }
        index = i;
        Item it = items.get(i);
        long resume = App.I.store.videoPosition(it.id);
        engine = new VideoEngine(this, uri(it), resume * 1000, this);
        if (surface != null) engine.setSurface(surface);
        am.requestAudioFocus(focus);
        engine.start(true);
        spinner.setVisibility(View.VISIBLE);
        showItem();
        bar.set(0);
        if (resume > 0) showNote("Resuming at " + time(resume) + "  ·  Y for Start Over");
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, it.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it.sub)
                .build());
        playingChanged();
    }

    private void showItem() {
        Item it = items.get(index);
        title.setText(it.title);
        List<String> s = new ArrayList<>();
        if (!it.sub.isEmpty()) s.add(it.sub);
        if (items.size() > 1) s.add((index + 1) + " of " + items.size());
        sub.setText(String.join("  ·  ", s));
        sub.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        prevBtn.setAlpha(index > 0 ? 1f : 0.35f);
        nextBtn.setAlpha(index < items.size() - 1 ? 1f : 0.35f);
    }

    /** Remembers the spot in the current video; finished or barely started videos start over next time. */
    private void saveSpot() {
        if (engine == null || items.isEmpty()) return;
        long p = engine.positionUs() / 1000, d = engine.durationUs() / 1000;
        boolean keep = d > 120_000 && p > 15_000 && p < d - 60_000;
        App.I.store.saveVideoPosition(items.get(index).id, keep ? p : 0);
    }

    // ---------- playback ----------

    private void play() {
        if (engine == null) return;
        pausedForFocus = false;
        am.requestAudioFocus(focus);
        engine.play();
        playingChanged();
    }

    private void pause() {
        if (engine == null || !engine.playing()) return;
        engine.pause();
        playingChanged();
        saveSpot();
    }

    private void toggle() {
        if (engine == null) return;
        if (engine.playing()) {
            pause();
            flash(R.drawable.ic_pause);
        } else {
            play();
            flash(R.drawable.ic_play);
        }
    }

    private void seekBy(long deltaUs) {
        if (engine == null) return;
        engine.seekBy(deltaUs);
        flash(deltaUs < 0 ? R.drawable.ic_rewind : R.drawable.ic_forward);
        afterSeek();
    }

    private void seekTo(long us) {
        if (engine == null) return;
        engine.seekTo(us);
        afterSeek();
    }

    private void afterSeek() {
        progress();
        updateSession();
        poke();
    }

    private void go(int d) {
        int ni = index + d;
        if (ni < 0 || ni >= items.size()) return;
        saveSpot();
        open(ni);
    }

    /** Back to the start of this video, or to the previous one when it just started. */
    private void previous() {
        if (engine != null && (index == 0 || engine.positionUs() > 5_000_000L)) {
            seekTo(0);
            flash(R.drawable.ic_prev);
        } else {
            go(-1);
        }
    }

    private void close() {
        if (engine != null) engine.pause();
        finish();
    }

    private void playingChanged() {
        boolean on = engine != null && engine.playing();
        playBtn.setImageResource(on ? R.drawable.ic_pause : R.drawable.ic_play);
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        App.I.lights.video(on && !items.isEmpty() ? uri(items.get(index)) : null);
        updateSession();
        updatePip();
        poke();
    }

    private void updateSession() {
        if (engine == null) return;
        boolean on = engine.playing();
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | (index < items.size() - 1 ? PlaybackState.ACTION_SKIP_TO_NEXT : 0);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(on ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        engine.positionUs() / 1000, on ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());
    }

    private void onFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (engine != null && engine.playing()) {
                    pause();
                    pausedForFocus = true;
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (pausedForFocus) play();
                break;
            default:
                break;
        }
    }

    // ---------- VideoEngine.Listener ----------

    @Override
    public void onVideoSize(int width, int height) {
        aspect = width / (float) height;
        fit.setAspect(aspect);
        updatePip();
    }

    @Override
    public void onReady() {
        spinner.setVisibility(View.GONE);
        if (engine != null && engine.durationUs() > 0) {
            Item it = items.get(index);
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, it.title)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it.sub)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, engine.durationUs() / 1000)
                    .build());
        }
        updateSession();
        progress();
    }

    @Override
    public void onEnded() {
        App.I.store.saveVideoPosition(items.get(index).id, 0);
        if (index < items.size() - 1) open(index + 1);
        else close();
    }

    @Override
    public void onError(String message) {
        App.I.store.saveVideoPosition(items.get(index).id, 0);
        showNote(message);
        spinner.setVisibility(View.GONE);
        h.postDelayed(() -> {
            if (index < items.size() - 1) open(index + 1);
            else close();
        }, 2500);
    }

    // ---------- picture-in-picture ----------

    private boolean pipAvailable() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private void enterPip() {
        if (!pipAvailable()) {
            showNote("Picture-in-picture isn't available on this device");
            return;
        }
        showControlsNow(false);
        try {
            enterPictureInPictureMode(pipParams());
        } catch (RuntimeException e) {
            Log.w(App.TAG, "picture-in-picture failed: " + e);
            poke();
        }
    }

    /** Keeps the system's picture-in-picture setup current: shape, buttons, and Home entering it while playing. */
    private void updatePip() {
        if (!pipAvailable() || isFinishing()) return;
        try {
            setPictureInPictureParams(pipParams());
        } catch (RuntimeException e) {
            Log.w(App.TAG, "picture-in-picture params: " + e);
        }
    }

    private PictureInPictureParams pipParams() {
        float a = Math.max(0.42f, Math.min(2.39f, aspect));
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(Math.round(a * 1000), 1000))
                .setActions(pipActions())
                .setAutoEnterEnabled(engine != null && engine.playing())
                .setSeamlessResizeEnabled(false);
        Rect r = new Rect();
        if (video.getGlobalVisibleRect(r) && !r.isEmpty()) b.setSourceRectHint(r);
        if (!items.isEmpty()) b.setTitle(items.get(index).title).setSubtitle(items.get(index).sub);
        return b.build();
    }

    private List<RemoteAction> pipActions() {
        boolean on = engine != null && engine.playing(), hasNext = index < items.size() - 1;
        int max = getMaxNumPictureInPictureActions();
        List<RemoteAction> out = new ArrayList<>();
        if (max >= 3) out.add(action(A_REWIND, R.drawable.ic_rewind, "Back 10 seconds"));
        out.add(on ? action(A_PAUSE, R.drawable.ic_pause, "Pause") : action(A_PLAY, R.drawable.ic_play, "Play"));
        if (hasNext && max >= 4) out.add(action(A_FORWARD, R.drawable.ic_forward, "Forward 10 seconds"));
        out.add(hasNext ? action(A_NEXT, R.drawable.ic_next, "Next video") : action(A_FORWARD, R.drawable.ic_forward, "Forward 10 seconds"));
        return out.size() > max ? out.subList(0, Math.max(0, max)) : out;
    }

    private RemoteAction action(int id, int icon, String label) {
        Intent i = new Intent(ACTION_PIP).setPackage(getPackageName()).putExtra("a", id);
        PendingIntent pi = PendingIntent.getBroadcast(this, id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new RemoteAction(Icon.createWithResource(this, icon), label, label, pi);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (engine != null && engine.playing()) showControlsNow(false);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean pip, Configuration cfg) {
        super.onPictureInPictureModeChanged(pip, cfg);
        inPip = pip;
        if (pip) {
            showControlsNow(false);
            flash.animate().cancel();
            flash.setAlpha(0f);
            note.setAlpha(0f);
        } else {
            hideSystemUi();
            poke();
        }
    }

    // ---------- lifecycle ----------

    @Override
    protected void onStart() {
        super.onStart();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // The menu resumes, and reads the play history, before onStop and onDestroy run.
        saveSpot();
    }

    @Override
    protected void onStop() {
        super.onStop();
        h.removeCallbacks(tick);
        if (isFinishing()) return;
        PowerManager pm = getSystemService(PowerManager.class);
        boolean screenOn = pm == null || pm.isInteractive();
        if (isInPictureInPictureMode() && screenOn) return;
        pause();
        // Sent behind another app without picture-in-picture (Home while paused): close, keeping the spot.
        if (screenOn) finish();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        saveSpot();
        if (engine != null) engine.release();
        engine = null;
        App.I.lights.video(null);
        session.release();
        am.abandonAudioFocusRequest(focus);
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    // ---------- keys ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        String k = MainActivity.map(e.getKeyCode());
        if (k == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) key(k, e.getRepeatCount() > 0);
        return true;
    }

    private void key(String k, boolean repeat) {
        switch (k) {
            case "left": seekBy(-STEP_US); return;
            case "right": seekBy(STEP_US); return;
            case "up": seekBy(JUMP_US); return;
            case "down": seekBy(-JUMP_US); return;
            default: break;
        }
        if (repeat) return;
        switch (k) {
            case "ok": case "playpause": toggle(); break;
            case "l1": case "prev": previous(); break;
            case "r1": case "next": go(1); break;
            case "x": enterPip(); break;
            case "options": case "start": options(); break;
            case "back": close(); break;
            default: poke(); break;
        }
    }

    private void options() {
        if (engine == null) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add("Picture-in-Picture");
        acts.add(this::enterPip);
        labels.add("Start Over");
        acts.add(() -> {
            seekTo(0);
            play();
        });
        List<VideoEngine.Track> tracks = engine.audioTracks();
        if (tracks.size() > 1) {
            for (VideoEngine.Track t : tracks) {
                labels.add((t.selected ? "✓  " : "      ") + "Sound: " + t.label());
                acts.add(() -> {
                    if (engine == null || t.selected) return;
                    engine.selectAudio(t.stream);
                    showNote("Sound: " + t.label());
                });
            }
        }
        poke();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(items.get(index).title)
                .setItems(labels.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .show();
    }

    // ---------- the screen ----------

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        fit = new Fit(this);
        video = new SurfaceView(this);
        fit.addView(video);
        root.addView(fit, new FrameLayout.LayoutParams(-1, -1));
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder sh) {
                surface = sh.getSurface();
                if (engine != null) engine.setSurface(surface);
            }

            @Override
            public void surfaceChanged(SurfaceHolder sh, int format, int w, int hgt) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder sh) {
                surface = null;
                if (engine != null) engine.setSurface(null);
            }
        });
        video.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (l != ol || t != ot || r != or || b != ob) updatePip();
        });

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(0xCCFFFFFF));
        root.addView(spinner, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));

        flash = new ImageView(this);
        flash.setBackground(oval(0x8C000000));
        flash.setPadding(dp(18), dp(18), dp(18), dp(18));
        flash.setAlpha(0f);
        root.addView(flash, new FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER));

        controls = buildControls();
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));

        note = text(14, 0xFFFFFFFF, false);
        GradientDrawable nb = new GradientDrawable();
        nb.setColor(0xB3000000);
        nb.setCornerRadius(dp(18));
        note.setBackground(nb);
        note.setPadding(dp(16), dp(8), dp(16), dp(8));
        note.setAlpha(0f);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        np.topMargin = dp(84);
        root.addView(note, np);

        root.setOnClickListener(v -> {
            if (controls.getVisibility() == View.VISIBLE) showControls(false);
            else poke();
        });
        noFocus(root);
        setContentView(root);
    }

    /**
     * Keys are all handled in dispatchKeyEvent, so nothing here may take focus. Clickable views are focusable by
     * default, and then the first d-pad press after a touch is spent leaving touch mode (it never reaches
     * dispatchKeyEvent) and the focused view gets the system's grey focus highlight.
     */
    private static void noFocus(View v) {
        v.setFocusable(View.NOT_FOCUSABLE);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int k = 0; k < g.getChildCount(); k++) noFocus(g.getChildAt(k));
        }
    }

    private View buildControls() {
        FrameLayout c = new FrameLayout(this);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xC0000000, 0x00000000}));
        top.setPadding(dp(32), dp(22), dp(32), dp(48));
        title = text(21, 0xFFFFFFFF, true);
        sub = text(14, 0xCCFFFFFF, false);
        top.addView(title);
        top.addView(sub);
        c.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xD0000000, 0x00000000}));
        bottom.setPadding(dp(28), dp(56), dp(28), dp(14));

        bar = new Bar(this);
        bottom.addView(bar, new LinearLayout.LayoutParams(-1, dp(26)));

        LinearLayout times = new LinearLayout(this);
        pos = text(13, 0xE6FFFFFF, false);
        dur = text(13, 0xB3FFFFFF, false);
        View gap = new View(this);
        times.addView(pos);
        times.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        times.addView(dur);
        times.setPadding(dp(6), 0, dp(6), 0);
        bottom.addView(times);

        FrameLayout row = new FrameLayout(this);
        LinearLayout mid = new LinearLayout(this);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        prevBtn = button(R.drawable.ic_prev, "Previous", 44, v -> previous());
        nextBtn = button(R.drawable.ic_next, "Next", 44, v -> go(1));
        playBtn = button(R.drawable.ic_pause, "Play or pause", 58, v -> toggle());
        mid.addView(prevBtn);
        mid.addView(button(R.drawable.ic_rewind, "Back 10 seconds", 44, v -> seekBy(-STEP_US)));
        mid.addView(playBtn);
        mid.addView(button(R.drawable.ic_forward, "Forward 10 seconds", 44, v -> seekBy(STEP_US)));
        mid.addView(nextBtn);
        row.addView(mid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        row.addView(button(R.drawable.ic_pip, "Picture-in-picture", 44, v -> enterPip()),
                new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(2);
        bottom.addView(row, rp);

        TextView hints = text(12, 0x99FFFFFF, false);
        hints.setText("A  Play/Pause     ◀ ▶  10 s     ▲ ▼  1 min     L1 R1  Previous/Next     X  Picture-in-Picture     Y  Options     B  Back");
        hints.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(8);
        bottom.addView(hints, hp);
        c.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return c;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private ImageButton button(int icon, String label, int sizeDp, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setContentDescription(label);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(sizeDp / 4.5f);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x66FFFFFF), oval(0x26FFFFFF), null));
        b.setFocusable(false);
        b.setOnClickListener(v -> {
            l.onClick(v);
            poke();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.setMargins(dp(10), 0, dp(10), 0);
        b.setLayoutParams(lp);
        return b;
    }

    /** Shows the controls, and hides them again after a while if a video is playing. */
    private void poke() {
        showControls(true);
        h.removeCallbacks(hideControls);
        if (engine != null && engine.playing()) h.postDelayed(hideControls, 3500);
    }

    private void showControls(boolean on) {
        if (inPip) on = false;
        if (on) {
            if (controls.getVisibility() != View.VISIBLE) {
                controls.setAlpha(0f);
                controls.setVisibility(View.VISIBLE);
            }
            controls.animate().alpha(1f).setDuration(150).withEndAction(null).start();
        } else if (controls.getVisibility() == View.VISIBLE) {
            controls.animate().alpha(0f).setDuration(350).withEndAction(() -> controls.setVisibility(View.GONE)).start();
        }
    }

    private void showControlsNow(boolean on) {
        h.removeCallbacks(hideControls);
        controls.animate().cancel();
        controls.setAlpha(on ? 1f : 0f);
        controls.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void flash(int icon) {
        if (inPip) return;
        flash.setImageResource(icon);
        flash.animate().cancel();
        flash.setAlpha(1f);
        flash.animate().alpha(0f).setStartDelay(450).setDuration(300).start();
    }

    private void showNote(String s) {
        note.setText(s);
        note.animate().cancel();
        note.setAlpha(inPip ? 0f : 1f);
        h.removeCallbacks(hideNote);
        h.postDelayed(hideNote, 3500);
    }

    private void progress() {
        if (engine == null || inPip) return;
        long p = engine.positionUs() / 1000, d = engine.durationUs() / 1000;
        if (bar.dragging) p = (long) (bar.value * d);
        else bar.set(d > 0 ? p / (float) d : 0);
        pos.setText(time(p));
        dur.setText(d > 0 ? time(d) : "");
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** Fits the picture in the window at its own shape. */
    private static final class Fit extends FrameLayout {
        private float aspect = 16f / 9f;

        Fit(Context c) {
            super(c);
        }

        void setAspect(float a) {
            if (a > 0 && Math.abs(a - aspect) > 0.001f) {
                aspect = a;
                requestLayout();
            }
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), hgt = MeasureSpec.getSize(hs);
            int cw = w, ch = Math.round(w / aspect);
            if (ch > hgt) {
                ch = hgt;
                cw = Math.round(hgt * aspect);
            }
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(w, hgt);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                int x = (r - l - v.getMeasuredWidth()) / 2, y = (b - t - v.getMeasuredHeight()) / 2;
                v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
            }
        }
    }

    /** The progress line; drag along it to seek. */
    private final class Bar extends View {
        float value;
        boolean dragging;
        private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG), front = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bar(Context c) {
            super(c);
            back.setColor(0x47FFFFFF);
            front.setColor(Color.WHITE);
        }

        void set(float v) {
            value = Math.max(0, Math.min(1, v));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = dp(dragging ? 8 : 6), th = dp(dragging ? 5 : 4), y = getHeight() / 2f;
            float x0 = r, x1 = getWidth() - r, x = x0 + (x1 - x0) * value;
            c.drawRoundRect(x0, y - th / 2, x1, y + th / 2, th / 2, th / 2, back);
            c.drawRoundRect(x0, y - th / 2, x, y + th / 2, th / 2, th / 2, front);
            c.drawCircle(x, y, r, front);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float r = dp(6);
            float v = (e.getX() - r) / Math.max(1, getWidth() - 2 * r);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    // fall through
                case MotionEvent.ACTION_MOVE:
                    set(v);
                    progress();
                    poke();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging && engine != null && engine.durationUs() > 0) seekTo((long) (value * engine.durationUs()));
                    dragging = false;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
    }
}
