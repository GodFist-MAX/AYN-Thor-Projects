package dev.anideck.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;

import dev.anideck.app.ext.Extensions;
import dev.anideck.app.ext.Host;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Plays episodes on the top screen: streamed from a source (with its servers, qualities, subtitles,
 * audio tracks and skip times) or downloaded. MainActivity hands it the controller, the bottom
 * screen's commands and the media controls; its state goes to the bottom screen, CrossBar's Now
 * Playing ({@link Session}) and the history ({@link Shelf}).
 */
final class VideoPlayer implements Player.Listener {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int ACCENT = 0xFF7AA7FF;
    /** X jumps about an opening's length when the source doesn't say where it is. */
    private static final double SKIP = 85;
    private static final Set<String> SKIPPABLE = Set.of("Opening", "Ending", "Recap", "MixedOp", "MixedEd");
    private static final double[] RATES = {1, 1.25, 1.5, 2, 0.75};

    private final MainActivity a;
    private final FrameLayout root, view, controls;
    private final SurfaceView surface;
    private final TextView subs, title, time, middle, skipChip;
    private final SeekBar bar;
    private final Menu menu;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick, hide = this::hideControls;

    private ExoPlayer exo;
    /** Streaming: {source, sourceName, anime, episodes, index, a, key}. */
    private JSONObject item;
    /** Playing a download: its record. */
    private JSONObject rec;
    private JSONArray hosters = new JSONArray();
    private JSONObject stream;
    private int hi = -1, vi = -1;
    private final Set<String> tried = new HashSet<>();
    /** Bumped on every episode change, so late answers about an old one are ignored. */
    private int gen;
    private String pstate = "idle", error = "", note = "";
    private boolean allFailed, reported, dragging, ended, autoSkipped;
    private float rate = 1;
    private int vw, vh;
    private long savedAt;
    private double pendingSeek = -1;
    private String lastSent = "", lastStamp = "";

    VideoPlayer(MainActivity a, FrameLayout root) {
        this.a = a;
        this.root = root;
        view = new FrameLayout(a);
        view.setBackgroundColor(Color.BLACK);
        view.setOnClickListener(v -> tapped());
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> view.post(this::fit));
        surface = new SurfaceView(a);
        view.addView(surface, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));

        subs = new Outlined(a);
        subs.setTextColor(Color.WHITE);
        subs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        subs.setTypeface(Typeface.DEFAULT_BOLD);
        subs.setGravity(Gravity.CENTER);
        subs.setMaxLines(5);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.bottomMargin = dp(28);
        sl.leftMargin = sl.rightMargin = dp(48);
        view.addView(subs, sl);

        middle = new TextView(a);
        middle.setTextColor(Color.WHITE);
        middle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        middle.setGravity(Gravity.CENTER);
        middle.setPadding(dp(28), dp(16), dp(28), dp(16));
        middle.setMaxWidth(dp(560));
        middle.setBackground(round(0xB3000000, dp(16)));
        view.addView(middle, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));

        skipChip = new TextView(a);
        skipChip.setTextColor(Color.WHITE);
        skipChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        skipChip.setPadding(dp(20), dp(10), dp(20), dp(10));
        skipChip.setBackground(round(0xE6181822, dp(20)));
        skipChip.setVisibility(View.GONE);
        skipChip.setOnClickListener(v -> skip());
        FrameLayout.LayoutParams kl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.END);
        kl.bottomMargin = dp(84);
        kl.rightMargin = dp(32);
        view.addView(skipChip, kl);

        menu = new Menu();
        controls = new FrameLayout(a);
        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(24), dp(14), dp(16), dp(28));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xCC000000, 0}));
        title = new TextView(a);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        top.addView(button("☰   Options", v -> menu.open()), new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(WRAP, WRAP);
        cl.leftMargin = dp(10);
        top.addView(button("✕   Close", v -> a.closePlayer()), cl);
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
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
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
        return item != null || rec != null;
    }

    boolean isOffline() {
        return rec != null;
    }

    boolean menuOpen() {
        return menu.isOpen();
    }

    // ---- opening ----

    /** Streams episode item.index of a show. */
    void open(JSONObject it) {
        save();
        stopPlayback();
        item = it;
        rec = null;
        JSONObject ep = episode();
        String key = App.str(it, "key");
        Shelf.get(a).remember(App.str(it, "source"), App.str(it, "sourceName"), it.optJSONObject("anime"));
        if (it.optInt("a") > 0) Shelf.get(a).link(key, it.optInt("a"));
        double start = it.optDouble("start", -1);
        if (start < 0) start = resumeFrom(shelfEp(key, App.str(ep, "url")));
        pendingSeek = start;
        begin();
        title.setText(App.str(it.optJSONObject("anime"), "title") + "   ·   " + epLabel(ep));
        int g = gen;
        loading("Finding a video…");
        App.I.exec.execute(() -> {
            JSONObject res;
            String err = null;
            try {
                res = Api.ext("best", App.obj("source", App.str(it, "source"), "episode", ep));
            } catch (IOException e) {
                res = null;
                err = e.getMessage();
            }
            JSONObject r = res;
            String why = err;
            h.post(() -> {
                if (g != gen || item != it) return;
                if (r == null) {
                    failed(why, false);
                    return;
                }
                hosters = r.optJSONArray("hosters") != null ? r.optJSONArray("hosters") : new JSONArray();
                JSONObject s = r.optJSONObject("stream");
                if (s == null) {
                    failed(App.str(r, "error"), true);
                    return;
                }
                play(r.optInt("hoster"), r.optInt("video"), s);
            });
        });
        pushEpisodes();
    }

    /** Plays a downloaded episode where it was left off, whether that was in the download or streaming. */
    void openDownload(JSONObject r) {
        save();
        stopPlayback();
        rec = r;
        item = null;
        hosters = new JSONArray();
        JSONObject seen = shelfEp(App.str(r, "key"), App.str(r.optJSONObject("episode"), "url"));
        pendingSeek = resumeFrom(seen != null ? seen : r);
        begin();
        String name = cleanTitle(App.str(r, "epTitle"));
        title.setText(App.str(r, "show") + "   ·   Episode " + DownloadService.num(r.optDouble("ep")) + (name.isEmpty() ? "" : " — " + name));
        loading("Loading…");
        List<MediaItem.SubtitleConfiguration> sc = new ArrayList<>();
        JSONArray s = r.optJSONArray("subs");
        for (int i = 0; s != null && i < s.length(); i++) {
            JSONObject o = s.optJSONObject(i);
            String path = App.str(o, "path");
            if (path.isEmpty() || !new File(path).isFile()) continue;
            sc.add(new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(new File(path)))
                    .setMimeType(subMime(App.str(o, "mime"), path)).setLanguage(langCode(App.str(o, "lang")))
                    .setLabel(App.str(o, "name")).build());
        }
        MediaItem mi = new MediaItem.Builder().setUri(Downloads.get(a).uri(r)).setSubtitleConfigurations(sc).build();
        DataSource.Factory ds = new DefaultDataSource.Factory(a);
        prepare(new DefaultMediaSourceFactory(ds).createMediaSource(mi));
        pushEpisodes();
    }

    private void begin() {
        gen++;
        tried.clear();
        stream = null;
        hi = vi = -1;
        error = note = "";
        allFailed = reported = ended = autoSkipped = false;
        vw = vh = 0;
        lastStamp = "";
        subs.setText("");
        skipChip.setVisibility(View.GONE);
        menu.close();
        if (view.getParent() == null) root.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
        view.bringToFront();
        showControls();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    void close() {
        if (!isOpen()) return;
        save();
        stopPlayback();
        gen++;
        item = rec = null;
        lastSent = "";
        pstate = "idle";
        pushEpisodes();
        h.removeCallbacks(tick);
        h.removeCallbacks(hide);
        menu.close();
        if (exo != null) {
            exo.release();
            exo = null;
        }
        root.removeView(view);
    }

    private void stopPlayback() {
        if (exo != null) exo.stop();
        view.setKeepScreenOn(false);
    }

    private ExoPlayer player() {
        if (exo == null) {
            exo = new ExoPlayer.Builder(a)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                    .setHandleAudioBecomingNoisy(true)
                    .setWakeMode(C.WAKE_MODE_NETWORK)
                    .build();
            exo.addListener(this);
            exo.setVideoSurfaceView(surface);
            String subLang = App.I.prefs.getString("subLang", "en"), audioLang = App.I.prefs.getString("audioLang", "ja");
            exo.setTrackSelectionParameters(exo.getTrackSelectionParameters().buildUpon()
                    .setPreferredTextLanguage(subLang).setSelectUndeterminedTextLanguage(true)
                    .setPreferredAudioLanguage(audioLang)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, App.I.prefs.getBoolean("subsOff", false)).build());
        }
        return exo;
    }

    // ---- streaming ----

    /** Plays one of the episode's videos (already resolved). */
    private void play(int h0, int v0, JSONObject s) {
        hi = h0;
        vi = v0;
        stream = s;
        tried.add(App.str(s, "key"));
        error = "";
        allFailed = false;
        double at = pendingSeek >= 0 ? pendingSeek : exo != null && exo.getPlaybackState() != Player.STATE_IDLE ? position() : 0;
        pendingSeek = at;
        loading("Loading " + serverName() + "…");
        int g = gen;
        App.I.exec.execute(() -> {
            String mime = mimeFor(s);
            h.post(() -> {
                if (g != gen) return;
                prepare(source(s, mime));
            });
        });
    }

    private MediaSource source(JSONObject s, String mime) {
        Map<String, String> headers = new HashMap<>();
        JSONObject hs = s.optJSONObject("headers");
        if (hs != null) for (Iterator<String> it = hs.keys(); it.hasNext(); ) {
            String k = it.next();
            headers.put(k, hs.optString(k));
        }
        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(Host.getClient()).setDefaultRequestProperties(headers);
        String ua = headers.get("User-Agent");
        if (ua != null) http.setUserAgent(ua);
        DataSource.Factory ds = new DefaultDataSource.Factory(a, http);
        DefaultMediaSourceFactory f = new DefaultMediaSourceFactory(ds);
        List<MediaItem.SubtitleConfiguration> sc = new ArrayList<>();
        JSONArray st = s.optJSONArray("subtitles");
        for (int i = 0; st != null && i < st.length(); i++) {
            JSONObject t = st.optJSONObject(i);
            String url = App.str(t, "url");
            if (url.isEmpty()) continue;
            sc.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(url)).setMimeType(subMime("", url))
                    .setLanguage(langCode(App.str(t, "lang"))).setLabel(App.str(t, "lang")).build());
        }
        MediaItem.Builder mb = new MediaItem.Builder().setUri(App.str(s, "url")).setSubtitleConfigurations(sc);
        if (mime != null) mb.setMimeType(mime);
        MediaSource main = f.createMediaSource(mb.build());
        JSONArray au = s.optJSONArray("audios");
        if (au == null || au.length() == 0) return main;
        List<MediaSource> all = new ArrayList<>();
        all.add(main);
        for (int i = 0; i < au.length(); i++) {
            JSONObject t = au.optJSONObject(i);
            String url = App.str(t, "url");
            if (url.isEmpty()) continue;
            MediaItem.Builder ab = new MediaItem.Builder().setUri(url);
            if (url.toLowerCase(Locale.ROOT).contains(".m3u8")) ab.setMimeType(MimeTypes.APPLICATION_M3U8);
            all.add(f.createMediaSource(ab.build()));
        }
        return new MergingMediaSource(true, true, all.toArray(new MediaSource[0]));
    }

    private void prepare(MediaSource src) {
        ExoPlayer p = player();
        long start = pendingSeek > 0 ? (long) (pendingSeek * 1000) : 0;
        pendingSeek = -1;
        p.setMediaSource(src, start);
        p.setPlaybackSpeed(rate);
        p.setPlayWhenReady(true);
        p.prepare();
        send();
    }

    /** HLS, DASH or a file. Sources don't always say, and ExoPlayer guesses only from the address. */
    private static String mimeFor(JSONObject s) {
        String url = App.str(s, "url");
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8") || lower.contains("m3u8")) return MimeTypes.APPLICATION_M3U8;
        if (lower.contains(".mpd")) return MimeTypes.APPLICATION_MPD;
        String path = Uri.parse(url).getPath();
        path = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (path.matches(".*\\.(mp4|m4v|mkv|webm|mov|ts|avi|flv)$")) return null;
        Request.Builder rb = new Request.Builder().url(url).header("Range", "bytes=0-1023");
        JSONObject hs = s.optJSONObject("headers");
        if (hs != null) for (Iterator<String> it = hs.keys(); it.hasNext(); ) {
            String k = it.next();
            try {
                rb.header(k, hs.optString(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
        try (Response r = Host.getClient().newCall(rb.build()).execute()) {
            String type = r.header("Content-Type", "");
            type = type == null ? "" : type.toLowerCase(Locale.ROOT);
            if (type.contains("mpegurl")) return MimeTypes.APPLICATION_M3U8;
            if (type.contains("dash+xml")) return MimeTypes.APPLICATION_MPD;
            byte[] head = new byte[64];
            int n;
            try (InputStream in = r.body().byteStream()) {
                n = in.read(head);
            }
            String start = n > 0 ? new String(head, 0, n, StandardCharsets.ISO_8859_1).trim() : "";
            if (start.startsWith("#EXTM3U")) return MimeTypes.APPLICATION_M3U8;
            if (start.startsWith("<?xml") || start.startsWith("<MPD")) return MimeTypes.APPLICATION_MPD;
        } catch (IOException | IllegalArgumentException e) {
            Log.w(App.TAG, "probe: " + e.getMessage());
        }
        return null;
    }

    /** The current video failed: the next one in the same server, then the other servers. */
    private void fallback(String why) {
        if (rec != null || item == null) {
            failed(why, false);
            return;
        }
        if (!App.I.setting("autoServer")) {
            failed(why, false);
            return;
        }
        int[] next = nextCandidate();
        if (next == null) {
            failed(why, true);
            return;
        }
        a.showToast("That server didn't work, trying " + hosterName(next[0]) + "…");
        choose(next[0], next[1], true);
    }

    private int[] nextCandidate() {
        for (int k = 0; k < hosters.length(); k++) {
            int h0 = (Math.max(hi, 0) + k) % hosters.length();
            JSONObject ho = hosters.optJSONObject(h0);
            JSONArray vs = ho == null ? null : ho.optJSONArray("videos");
            if (ho != null && ho.optBoolean("lazy") && (vs == null || vs.length() == 0)) return new int[] {h0, -1};
            for (int v = 0; vs != null && v < vs.length(); v++) {
                if (!tried.contains(App.str(vs.optJSONObject(v), "key"))) return new int[] {h0, v};
            }
        }
        return null;
    }

    /** A server and video picked on either screen (or by fallback); v -1 loads a server's videos first. */
    private void choose(int h0, int v0, boolean auto) {
        JSONObject ho = hosters.optJSONObject(h0);
        if (ho == null || item == null) return;
        int g = gen;
        if (exo != null && exo.getPlaybackState() != Player.STATE_IDLE && pendingSeek < 0) pendingSeek = position();
        loading("Loading " + hosterName(h0) + "…");
        App.I.exec.execute(() -> {
            JSONObject h1 = ho;
            String err = null;
            JSONObject s = null;
            int v = v0;
            try {
                JSONArray vs = h1.optJSONArray("videos");
                if (v < 0 || vs == null || vs.length() == 0) {
                    h1 = Api.ext("videos", App.obj("hoster", App.str(ho, "key")));
                    vs = h1.optJSONArray("videos");
                    if (v < 0) v = preferred(vs);
                }
                JSONObject video = vs == null ? null : vs.optJSONObject(v);
                if (video == null) throw new IOException(App.str(h1, "error").isEmpty() ? "This server has no videos" : App.str(h1, "error"));
                s = Api.ext("resolve", App.obj("video", App.str(video, "key")));
            } catch (IOException e) {
                err = e.getMessage();
            }
            JSONObject hFinal = h1, sFinal = s;
            int vFinal = v;
            String why = err;
            h.post(() -> {
                if (g != gen) return;
                try {
                    hosters.put(h0, hFinal);
                } catch (JSONException ignored) {
                }
                if (sFinal == null) {
                    JSONArray vs = hFinal.optJSONArray("videos");
                    if (vs != null && vFinal >= 0 && vFinal < vs.length()) tried.add(App.str(vs.optJSONObject(vFinal), "key"));
                    else markHosterTried(hFinal);
                    if (auto) fallback(why);
                    else failed(why, false);
                    return;
                }
                play(h0, vFinal, sFinal);
            });
        });
    }

    private void markHosterTried(JSONObject ho) {
        App.put(ho, "lazy", false);
        JSONArray vs = ho.optJSONArray("videos");
        for (int v = 0; vs != null && v < vs.length(); v++) tried.add(App.str(vs.optJSONObject(v), "key"));
    }

    private static int preferred(JSONArray vs) {
        for (int i = 0; vs != null && i < vs.length(); i++) if (vs.optJSONObject(i).optBoolean("preferred")) return i;
        return 0;
    }

    // ---- ExoPlayer ----

    @Override public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_ENDED) {
            ended = true;
            save();
            view.setKeepScreenOn(false);
            if (App.I.setting("autoNext") && hasNext()) {
                a.showToast("Next: " + nextLabel());
                h.postDelayed(() -> {
                    if (ended && isOpen()) step(1);
                }, 1200);
            } else {
                showControls();
            }
        }
        update();
    }

    @Override public void onIsPlayingChanged(boolean playing) {
        view.setKeepScreenOn(playing);
        if (!playing) save();
        update();
    }

    @Override public void onPlayerError(PlaybackException e) {
        Log.w(App.TAG, "player error " + e.getErrorCodeName() + ": " + e.getMessage());
        String why;
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS: why = "The video server refused the stream"; break;
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT: why = "Couldn't reach the video server"; break;
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED: why = "The server sent something that isn't a video"; break;
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED: why = "This video's format can't be played on the Thor"; break;
            case PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND: why = "The downloaded file is missing"; break;
            default: why = "The video stopped loading";
        }
        if (rec == null && !App.I.net.online()) {
            pendingSeek = position();
            failed("No internet. The episode continues when Wi-Fi is back", false);
            return;
        }
        if (rec == null && exo != null) pendingSeek = position();
        fallback(why);
    }

    @Override public void onCues(CueGroup g) {
        StringBuilder b = new StringBuilder();
        for (Cue c : g.cues) {
            if (c.text == null) continue;
            if (b.length() > 0) b.append('\n');
            b.append(c.text);
        }
        subs.setText(b);
    }

    @Override public void onVideoSizeChanged(VideoSize s) {
        vw = Math.round(s.width * (s.pixelWidthHeightRatio > 0 ? s.pixelWidthHeightRatio : 1));
        vh = s.height;
        fit();
    }

    @Override public void onTracksChanged(Tracks t) {
        lastSent = "";
        send();
    }

    /** After the connection comes back: an episode that stopped for it continues. */
    void onNet(boolean online) {
        if (!online || rec != null || item == null || !"error".equals(pstate)) return;
        if (stream != null && hi >= 0) {
            play(hi, vi, stream);
        } else {
            JSONObject it = item;
            App.put(it, "start", pendingSeek);
            open(it);
        }
    }

    // ---- transport ----

    private void toggle() {
        if (exo == null) return;
        if ("error".equals(pstate)) {
            retry();
            return;
        }
        if (ended) {
            exo.seekTo(0);
            ended = false;
            exo.play();
        } else if (exo.getPlayWhenReady()) exo.pause();
        else exo.play();
    }

    private void retry() {
        if (rec != null) openDownload(rec);
        else if (item != null) {
            tried.clear();
            if (stream != null && hi >= 0) play(hi, vi, stream);
            else open(item);
        }
    }

    private void seekTo(double t) {
        if (exo == null) return;
        double d = duration();
        t = Math.max(0, d > 1 ? Math.min(t, d - 1) : t);
        exo.seekTo((long) (t * 1000));
        ended = false;
        autoSkipped = false;
    }

    private double position() {
        return exo == null ? 0 : exo.getCurrentPosition() / 1000.0;
    }

    private double duration() {
        if (exo == null) return 0;
        long d = exo.getDuration();
        return d == C.TIME_UNSET || d < 0 ? 0 : d / 1000.0;
    }

    /** X: past the opening or ending (where the source marks them), else 85 seconds on. */
    private void skip() {
        JSONObject st = stampAt(position());
        if (st != null) {
            seekTo(st.optDouble("end"));
            a.showToast("Skipped the " + stampName(st).toLowerCase(Locale.ROOT));
        } else {
            seekTo(position() + SKIP);
        }
    }

    private JSONObject stampAt(double t) {
        JSONArray ts = stream == null ? null : stream.optJSONArray("timestamps");
        for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject s = ts.optJSONObject(i);
            if (s != null && SKIPPABLE.contains(App.str(s, "type")) && t >= s.optDouble("start") - 0.5 && t < s.optDouble("end") - 1) return s;
        }
        return null;
    }

    private static String stampName(JSONObject s) {
        String type = App.str(s, "type");
        switch (type) {
            case "MixedOp": return "Opening";
            case "MixedEd": return "Ending";
            default: return type;
        }
    }

    private boolean hasNext() {
        if (rec != null) return Downloads.get(a).neighbor(rec, 1) != null || streamsOn();
        JSONArray eps = item == null ? null : item.optJSONArray("episodes");
        return eps != null && item.optInt("index") + 1 < eps.length();
    }

    private boolean hasPrev() {
        if (rec != null) return Downloads.get(a).neighbor(rec, -1) != null || streamsOn();
        return item != null && item.optInt("index") > 0;
    }

    /** Past the downloaded episodes, a show goes on by streaming when its source is installed and there's internet. */
    private boolean streamsOn() {
        return rec != null && App.I.net.online() && rec.optJSONObject("anime") != null
                && !App.str(rec.optJSONObject("episode"), "url").isEmpty() && Extensions.isInstalled(App.str(rec, "source"));
    }

    private String nextLabel() {
        if (rec != null) {
            JSONObject n = Downloads.get(a).neighbor(rec, 1);
            if (n == null) return streamsOn() ? "Episode " + DownloadService.num(Math.floor(rec.optDouble("ep")) + 1) : "";
            return "Episode " + DownloadService.num(n.optDouble("ep"));
        }
        JSONArray eps = item.optJSONArray("episodes");
        return epLabel(eps.optJSONObject(item.optInt("index") + 1));
    }

    private void step(int dir) {
        if (rec != null) {
            JSONObject n = Downloads.get(a).neighbor(rec, dir);
            if (n != null) openDownload(n);
            else if (streamsOn()) {
                // The show page finds the neighbor in the source's full list and streams it.
                a.route("#/show/" + Uri.encode(App.str(rec, "source")) + "/" + Uri.encode(App.str(rec.optJSONObject("anime"), "url"))
                        + "?after=" + Uri.encode(App.str(rec.optJSONObject("episode"), "url")) + "&dir=" + dir);
            } else a.showToast(dir > 0 ? "No later episode of this show is downloaded" : "No earlier episode of this show is downloaded");
            return;
        }
        if (item == null) return;
        JSONArray eps = item.optJSONArray("episodes");
        int i = item.optInt("index") + dir;
        if (eps == null || i < 0 || i >= eps.length()) {
            a.showToast(dir > 0 ? "That was the last episode" : "That's the first episode");
            return;
        }
        playIndex(i);
    }

    private void playIndex(int i) {
        JSONObject it = copy(item);
        App.put(it, "index", i);
        it.remove("start");
        // A downloaded copy plays from the Thor.
        JSONObject ep = it.optJSONArray("episodes").optJSONObject(i);
        JSONObject d = Downloads.get(a).find(App.str(it, "key"), App.str(ep, "url"));
        if (d != null && "done".equals(App.str(d, "state"))) {
            openDownload(d);
            return;
        }
        open(it);
    }

    // ---- tracks ----

    private List<Object[]> tracks(int type) {
        List<Object[]> out = new ArrayList<>();
        if (exo == null) return out;
        for (Tracks.Group g : exo.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) if (g.isTrackSupported(i)) out.add(new Object[] {g, i});
        }
        return out;
    }

    private JSONArray trackNames(int type) {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        int n = 0;
        for (Object[] t : tracks(type)) {
            Format f = ((Tracks.Group) t[0]).getTrackFormat((Integer) t[1]);
            String name = f.label != null && !f.label.isEmpty() ? f.label : f.language != null && !"und".equals(f.language)
                    ? new Locale(f.language).getDisplayLanguage(Locale.ENGLISH) : (type == C.TRACK_TYPE_TEXT ? "Subtitles " : "Audio ") + (n + 1);
            if (!seen.add(name)) name += " (" + (n + 1) + ")";
            out.put(name);
            n++;
        }
        return out;
    }

    private int selectedTrack(int type) {
        if (exo == null) return -1;
        if (type == C.TRACK_TYPE_TEXT && exo.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return -1;
        List<Object[]> ts = tracks(type);
        for (int i = 0; i < ts.size(); i++) {
            if (((Tracks.Group) ts.get(i)[0]).isTrackSelected((Integer) ts.get(i)[1])) return i;
        }
        return -1;
    }

    private void pickTrack(int type, int i) {
        if (exo == null) return;
        List<Object[]> ts = tracks(type);
        TrackSelectionParameters.Builder b = exo.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type);
        if (i < 0 || i >= ts.size()) {
            if (type == C.TRACK_TYPE_TEXT) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        } else {
            Tracks.Group g = (Tracks.Group) ts.get(i)[0];
            b.setTrackTypeDisabled(type, false).setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), (Integer) ts.get(i)[1]));
            Format f = g.getTrackFormat((Integer) ts.get(i)[1]);
            if (f.language != null && !"und".equals(f.language)) {
                App.I.prefs.edit().putString(type == C.TRACK_TYPE_TEXT ? "subLang" : "audioLang", f.language).apply();
                if (type == C.TRACK_TYPE_TEXT) b.setPreferredTextLanguage(f.language);
                else b.setPreferredAudioLanguage(f.language);
            }
        }
        if (type == C.TRACK_TYPE_TEXT) App.I.prefs.edit().putBoolean("subsOff", i < 0).apply();
        exo.setTrackSelectionParameters(b.build());
        subs.setText("");
        JSONArray names = trackNames(type);
        a.showToast(type == C.TRACK_TYPE_TEXT ? (i >= 0 ? "Subtitles: " + names.optString(i) : "Subtitles off") : "Audio: " + names.optString(i));
    }

    private void toggleSubs() {
        List<Object[]> ts = tracks(C.TRACK_TYPE_TEXT);
        if (ts.isEmpty()) {
            a.showToast("This episode has no subtitle tracks");
            return;
        }
        int cur = selectedTrack(C.TRACK_TYPE_TEXT);
        pickTrack(C.TRACK_TYPE_TEXT, cur >= 0 ? -1 : 0);
    }

    // ---- commands and state ----

    /** A command from the controller, the bottom screen or the media controls. */
    boolean cmd(JSONObject c) {
        if (!isOpen()) return false;
        switch (App.str(c, "c")) {
            case "toggle": toggle(); break;
            case "play":
            case "autoplay":
                if (exo != null && !"error".equals(pstate)) exo.play();
                break;
            case "pause":
                if (exo != null) exo.pause();
                break;
            case "seekBy": seekTo(position() + c.optDouble("d", 0)); break;
            case "seek":
                seekTo(c.optDouble("t", 0));
                if (c.optBoolean("play") && exo != null) exo.play();
                break;
            case "skip": skip(); break;
            case "next": step(1); break;
            case "prev": step(-1); break;
            case "episode": {
                int i = c.optInt("i", -1);
                if (rec != null) {
                    JSONObject d = Downloads.get(a).get(App.str(c, "dl"));
                    if (d != null) openDownload(d);
                } else if (item != null && i >= 0 && i < item.optJSONArray("episodes").length()) playIndex(i);
                break;
            }
            case "rate":
                rate = (float) Math.max(0.25, Math.min(3, c.optDouble("r", 1)));
                if (exo != null) exo.setPlaybackSpeed(rate);
                break;
            case "speed": {
                int k = 0;
                for (int i = 0; i < RATES.length; i++) if (Math.abs(RATES[i] - rate) < 0.01) k = i;
                rate = (float) RATES[(k + 1) % RATES.length];
                if (exo != null) exo.setPlaybackSpeed(rate);
                a.showToast("Speed " + fmtRate(rate));
                break;
            }
            case "captions": toggleSubs(); break;
            case "sub": pickTrack(C.TRACK_TYPE_TEXT, c.optInt("i", -1)); break;
            case "audio": pickTrack(C.TRACK_TYPE_AUDIO, c.optInt("i", 0)); break;
            case "server": choose(c.optInt("h"), c.optInt("v", -1), false); break;
            case "retry": retry(); break;
            case "menu":
                if (menu.isOpen()) menu.close();
                else menu.open();
                break;
            case "download": download(); break;
            default:
                return false;
        }
        showControls();
        send();
        return true;
    }

    /** Download on Now Playing: this episode, from the server and in the quality playing now. */
    private void download() {
        if (item == null) {
            a.showToast(rec != null ? "This episode is already downloaded" : "Nothing to download");
            return;
        }
        JSONObject pick = null;
        JSONObject ho = hosters.optJSONObject(hi);
        if (ho != null && stream != null) pick = App.obj("hoster", App.str(ho, "name"), "video", App.str(stream, "title"));
        JSONArray one = new JSONArray().put(episode());
        int n = Downloads.get(a).add(App.str(item, "source"), App.str(item, "sourceName"), item.optJSONObject("anime"), one, item.optInt("a"), pick);
        a.showToast(n > 0 ? "Downloading " + epLabel(episode()) + ". Follow it in Downloads" : "This episode is already downloaded or in the queue");
    }

    private JSONObject state() {
        double t = Math.round(position() * 10) / 10.0, d = Math.round(duration() * 10) / 10.0;
        JSONObject s;
        if (rec != null) {
            s = App.obj("page", "watch", "offline", true, "download", App.str(rec, "id"), "key", App.str(rec, "key"),
                    "show", App.str(rec, "show"), "anilistId", rec.optInt("a"), "ep", rec.optDouble("ep"), "epTitle", cleanTitle(App.str(rec, "epTitle")),
                    "hasPrev", hasPrev(), "hasNext", hasNext(), "quality", App.str(rec, "quality"),
                    "cover", coverName(App.str(rec, "key"), rec.optInt("a")));
        } else {
            JSONObject ep = episode(), anime = item.optJSONObject("anime");
            String key = App.str(item, "key");
            JSONObject d0 = Downloads.get(a).find(key, App.str(ep, "url"));
            s = App.obj("page", "watch", "offline", false, "key", key, "source", App.str(item, "source"), "sourceName", App.str(item, "sourceName"),
                    "show", App.str(anime, "title"), "anilistId", item.optInt("a"), "ep", ep.optDouble("episode_number", item.optInt("index") + 1),
                    "epTitle", epTitle(ep), "epIndex", item.optInt("index"), "epCount", item.optJSONArray("episodes").length(),
                    "hasPrev", hasPrev(), "hasNext", hasNext(), "servers", serversJson(), "server", hi, "video", vi,
                    "quality", stream == null ? "" : App.str(stream, "title"), "coverUrl", App.str(anime, "thumbnail_url"),
                    "cover", coverName(key, item.optInt("a")), "dl", d0 == null ? "" : App.str(d0, "state"), "dlId", d0 == null ? "" : App.str(d0, "id"));
            JSONObject stamp = stampAt(t);
            if (stamp != null) App.put(s, "stamp", stampName(stamp));
        }
        App.put(s, "player", App.obj("state", pstate, "t", t, "d", d, "error", error, "allFailed", allFailed, "note", note));
        App.put(s, "subs", trackNames(C.TRACK_TYPE_TEXT));
        App.put(s, "sub", selectedTrack(C.TRACK_TYPE_TEXT));
        App.put(s, "audios", trackNames(C.TRACK_TYPE_AUDIO));
        App.put(s, "audio", selectedTrack(C.TRACK_TYPE_AUDIO));
        App.put(s, "rate", rate);
        App.put(s, "menu", menu.isOpen());
        return s;
    }

    private String coverName(String key, int anilistId) {
        Shelf sh = Shelf.get(a);
        if (!key.isEmpty() && sh.cover(key).isFile()) return Shelf.coverName(key);
        if (anilistId > 0 && sh.cover(Shelf.aniListKey(anilistId)).isFile()) return Shelf.coverName(Shelf.aniListKey(anilistId));
        return "";
    }

    private JSONArray serversJson() {
        JSONArray out = new JSONArray();
        for (int i = 0; i < hosters.length(); i++) {
            JSONObject ho = hosters.optJSONObject(i);
            JSONArray vs = ho.optJSONArray("videos"), names = new JSONArray();
            for (int k = 0; vs != null && k < vs.length(); k++) names.put(App.str(vs.optJSONObject(k), "title"));
            out.put(App.obj("name", hosterName(i), "videos", names, "lazy", ho.optBoolean("lazy"), "error", App.str(ho, "error")));
        }
        return out;
    }

    /** The show's episodes for the bottom screen's strip, sent when the show changes. */
    private void pushEpisodes() {
        JSONArray out = new JSONArray();
        if (item != null) {
            JSONArray eps = item.optJSONArray("episodes");
            JSONObject show = Shelf.get(a).show(App.str(item, "key"));
            JSONObject seen = show == null ? null : show.optJSONObject("eps");
            for (int i = 0; eps != null && i < eps.length(); i++) {
                JSONObject e = eps.optJSONObject(i);
                JSONObject p = seen == null ? null : seen.optJSONObject(App.str(e, "url"));
                out.put(App.obj("i", i, "n", numLabel(e.optDouble("episode_number", i + 1)), "name", epTitle(e), "w", p != null && p.optBoolean("w")));
            }
        } else if (rec != null) {
            for (JSONObject r : Downloads.get(a).showDownloads(rec)) {
                out.put(App.obj("dl", App.str(r, "id"), "n", numLabel(r.optDouble("ep")), "name", cleanTitle(App.str(r, "epTitle")),
                        "w", r.optDouble("d") > 0 && r.optDouble("t") >= r.optDouble("d") * Shelf.DONE));
            }
        }
        App.I.episodes = out.toString();
        App.I.toBottom("onEpisodes", App.I.episodes);
    }

    private void update() {
        if (!isOpen() || exo == null) return;
        if ("error".equals(pstate) && exo.getPlaybackState() == Player.STATE_IDLE) {
            send();
            return;
        }
        int st = exo.getPlaybackState();
        if (st == Player.STATE_BUFFERING || (st == Player.STATE_IDLE && exo.getPlayWhenReady())) {
            pstate = "loading";
        } else if (st == Player.STATE_READY) {
            pstate = exo.getPlayWhenReady() ? "playing" : "paused";
            note = "";
        } else if (st == Player.STATE_ENDED) {
            pstate = "paused";
        }
        send();
    }

    private void loading(String why) {
        pstate = "loading";
        note = why;
        error = "";
        send();
    }

    private void failed(String why, boolean all) {
        pstate = "error";
        error = why == null || why.isEmpty() ? "The video couldn't be played" : why;
        allFailed = all;
        note = "";
        view.setKeepScreenOn(false);
        if (exo != null) exo.stop();
        showControls();
        send();
    }

    private void send() {
        if (!isOpen()) return;
        boolean showMiddle = !"playing".equals(pstate);
        middle.setVisibility(showMiddle ? View.VISIBLE : View.GONE);
        if (showMiddle) {
            String m;
            switch (pstate) {
                case "error":
                    m = error + (rec == null ? (allFailed ? "\n\nNone of the servers worked. Press A to try again, or Y for the server list"
                            : "\n\nPress A to try again, or Y to pick another server") : "");
                    break;
                case "loading": m = note.isEmpty() ? "Loading…" : note; break;
                default: m = ended ? (hasNext() ? "Finished. R2 plays the next episode" : "Finished. Press A to watch again") : "Paused";
            }
            middle.setText(m);
        }
        JSONObject s = state();
        String json = s.toString();
        if (json.equals(lastSent)) return;
        lastSent = json;
        a.playerState(s);
    }

    private void tick() {
        if (!isOpen()) return;
        double t = position(), d = duration();
        if (!dragging) {
            bar.setMax((int) Math.max(1, d));
            bar.setProgress((int) t);
            time.setText(clock(t) + " / " + clock(d));
        }
        JSONObject stamp = rec == null ? stampAt(t) : null;
        String sk = stamp == null ? "" : App.str(stamp, "type") + stamp.optDouble("start");
        if (stamp != null && App.I.setting("autoSkip") && !autoSkipped && "playing".equals(pstate) && !sk.equals(lastStamp)) {
            autoSkipped = true;
            lastStamp = sk;
            seekTo(stamp.optDouble("end"));
            a.showToast("Skipped the " + stampName(stamp).toLowerCase(Locale.ROOT));
        } else {
            if (stamp == null) autoSkipped = false;
            skipChip.setVisibility(stamp != null ? View.VISIBLE : View.GONE);
            if (stamp != null) skipChip.setText("X   Skip " + stampName(stamp).toLowerCase(Locale.ROOT));
        }
        if (!reported && d > 0 && t / d >= Shelf.DONE) {
            reported = true;
            watchedToEnd();
        }
        if (exo != null && exo.isPlaying() && SystemClock.uptimeMillis() - savedAt > 5000) save();
        send();
        h.postDelayed(tick, 500);
    }

    /** At 85%: the episode counts as watched here, and on AniList for a linked show. */
    private void watchedToEnd() {
        int anilist;
        double num;
        if (rec != null) {
            anilist = rec.optInt("a");
            num = rec.optDouble("ep");
        } else {
            anilist = item.optInt("a");
            if (anilist <= 0) {
                JSONObject show = Shelf.get(a).show(App.str(item, "key"));
                anilist = show == null ? 0 : show.optInt("a");
            }
            num = episode().optDouble("episode_number", -1);
        }
        if (anilist > 0 && num >= 1 && num == Math.floor(num)) App.I.library.watched(anilist, (int) num);
    }

    /** Positions go to the history (streams) or the download's record. */
    private void save() {
        if (!isOpen() || exo == null || "error".equals(pstate)) return;
        double t = ended ? duration() : position(), d = duration();
        if (d <= 0) return;
        savedAt = SystemClock.uptimeMillis();
        if (rec != null) {
            Downloads.get(a).position(App.str(rec, "id"), t, d);
            String key = App.str(rec, "key");
            JSONObject ep = rec.optJSONObject("episode");
            if (!key.isEmpty() && ep != null) {
                Shelf sh = Shelf.get(a);
                if (sh.show(key) == null) sh.remember(App.str(rec, "source"), App.str(rec, "sourceName"), rec.optJSONObject("anime"));
                sh.progress(key, ep, -1, 0, t, d);
            }
        } else {
            Shelf.get(a).progress(App.str(item, "key"), episode(), item.optInt("index"), item.optJSONArray("episodes").length(), t, d);
        }
    }

    // ---- helpers ----

    private JSONObject episode() {
        JSONArray eps = item.optJSONArray("episodes");
        JSONObject e = eps == null ? null : eps.optJSONObject(item.optInt("index"));
        return e == null ? new JSONObject() : e;
    }

    /** The shelf's progress for one episode of a show, if it was played. */
    private JSONObject shelfEp(String key, String epUrl) {
        JSONObject show = key.isEmpty() ? null : Shelf.get(a).show(key);
        JSONObject eps = show == null ? null : show.optJSONObject("eps");
        return eps == null || epUrl.isEmpty() ? null : eps.optJSONObject(epUrl);
    }

    /** Where to pick up from progress {t, d, w}: the start if it was finished or barely begun. */
    private static double resumeFrom(JSONObject e) {
        if (e == null || e.optBoolean("w")) return 0;
        double t = e.optDouble("t", 0), d = e.optDouble("d", 0);
        return t > 5 && (d <= 0 || t < d - 20) ? t : 0;
    }

    static String epLabel(JSONObject ep) {
        if (ep == null) return "";
        double n = ep.optDouble("episode_number", -1);
        String name = epTitle(ep);
        String num = n >= 0 ? "Episode " + numLabel(n) : "";
        if (num.isEmpty()) return name;
        return name.isEmpty() ? num : num + " — " + name;
    }

    /** The episode's own title, without a repeat of its number ("Episode 3", "Ep. 3: ..."). */
    static String epTitle(JSONObject ep) {
        return cleanTitle(App.str(ep, "name"));
    }

    static String cleanTitle(String name) {
        String stripped = name.trim().replaceFirst("(?i)^(episode|ep\\.?|e)\\s*[\\d.]+\\s*[:\\-–—]?\\s*", "").trim();
        return stripped.matches("[\\d.]*") ? "" : stripped;
    }

    static String numLabel(double n) {
        return n == Math.floor(n) ? String.valueOf((long) n) : String.valueOf(n);
    }

    private String serverName() {
        return hi >= 0 ? hosterName(hi) : "the video";
    }

    private String hosterName(int i) {
        JSONObject ho = hosters.optJSONObject(i);
        String n = ho == null ? "" : App.str(ho, "name");
        return n.isEmpty() ? "Server " + (i + 1) : n;
    }

    private static String subMime(String given, String url) {
        if (!given.isEmpty()) return given;
        String p = Uri.parse(url).getPath();
        p = p == null ? "" : p.toLowerCase(Locale.ROOT);
        if (p.endsWith(".srt")) return MimeTypes.APPLICATION_SUBRIP;
        if (p.endsWith(".ass") || p.endsWith(".ssa")) return MimeTypes.TEXT_SSA;
        if (p.endsWith(".ttml") || p.endsWith(".dfxp") || p.endsWith(".xml")) return MimeTypes.APPLICATION_TTML;
        return MimeTypes.TEXT_VTT;
    }

    /** "English" → "en", for the track's language; names AniDeck doesn't know stay as labels only. */
    private static String langCode(String name) {
        String n = name.toLowerCase(Locale.ROOT).trim();
        if (n.matches("[a-z]{2,3}(-[a-z0-9]+)?")) return n;
        for (Locale l : Locale.getAvailableLocales()) {
            if (!l.getLanguage().isEmpty() && n.startsWith(l.getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT))) return l.getLanguage();
        }
        return null;
    }

    static String fmtRate(float r) {
        return (r == Math.floor(r) ? String.valueOf((int) r) : String.valueOf(r)) + "×";
    }

    // ---- touch and controls ----

    /** A tap shows the controls; another while they're up plays or pauses. */
    private void tapped() {
        if (menu.isOpen()) {
            menu.close();
            return;
        }
        if (controls.getVisibility() != View.VISIBLE) {
            showControls();
            return;
        }
        cmd(App.obj("c", "toggle"));
    }

    void showControls() {
        controls.setVisibility(View.VISIBLE);
        subs.setTranslationY(-dp(64));
        h.removeCallbacks(hide);
        h.postDelayed(hide, 3500);
    }

    /** They stay up while paused or failed. */
    private void hideControls() {
        if (dragging || menu.isOpen() || !"playing".equals(pstate)) {
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
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        int fw = Math.round(vw * k), fh = Math.round(vh * k);
        if (lp.width == fw && lp.height == fh) return;
        lp.width = fw;
        lp.height = fh;
        surface.setLayoutParams(lp);
    }

    // ---- controller ----

    /** D-pad on the top screen while playing: seek and volume, or move through the options menu. */
    boolean nav(String dir) {
        if (menu.isOpen()) {
            menu.nav(dir);
            return true;
        }
        return false;
    }

    /** A: picks in the menu, retries after an error, else plays or pauses. */
    void ok() {
        if (menu.isOpen()) menu.ok();
        else cmd(App.obj("c", "toggle"));
    }

    /** B: closes the menu first. */
    boolean back() {
        if (menu.isOpen()) {
            menu.close();
            send();
            return true;
        }
        return false;
    }

    private static String clock(double s) {
        long n = Math.max(0, Math.round(s));
        return n >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", n / 3600, n / 60 % 60, n % 60)
                : String.format(Locale.ROOT, "%d:%02d", n / 60, n % 60);
    }

    private TextView button(String text, View.OnClickListener l) {
        TextView b = new TextView(a);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        b.setBackground(round(0xE6181822, dp(20)));
        b.setOnClickListener(l);
        return b;
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

    private static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    /**
     * Options on the top screen (Y, or the Options button): server and quality, subtitles, audio and
     * speed, as columns the d-pad moves through. The bottom screen has the same choices.
     */
    private final class Menu {
        private final String[] sections = {"Server", "Subtitles", "Audio", "Speed"};
        private final LinearLayout panel, tabs, list;
        private final ScrollView scroll;
        private int section, row;
        private List<Runnable> actions = new ArrayList<>();

        Menu() {
            panel = new LinearLayout(a);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(22), dp(18), dp(22), dp(18));
            panel.setBackground(round(0xF0101018, dp(18)));
            panel.setClickable(true);
            tabs = new LinearLayout(a);
            panel.addView(tabs, new LinearLayout.LayoutParams(MATCH, WRAP));
            scroll = new ScrollView(a);
            list = new LinearLayout(a);
            list.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(list, new ScrollView.LayoutParams(MATCH, WRAP));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(MATCH, dp(420));
            sp.topMargin = dp(12);
            panel.addView(scroll, sp);
            TextView hint = new TextView(a);
            hint.setText("D-pad to move · A to choose · B to close");
            hint.setTextColor(0x99FFFFFF);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            hint.setPadding(0, dp(10), 0, 0);
            panel.addView(hint);
        }

        boolean isOpen() {
            return panel.getParent() != null;
        }

        void open() {
            if (!VideoPlayer.this.isOpen()) return;
            if (rec != null && section == 0) section = 1;
            row = -1;
            render();
            if (panel.getParent() == null) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(640), WRAP, Gravity.CENTER_VERTICAL | Gravity.END);
                lp.rightMargin = dp(28);
                view.addView(panel, lp);
            }
            panel.bringToFront();
            showControls();
        }

        void close() {
            if (panel.getParent() != null) view.removeView(panel);
        }

        void nav(String dir) {
            switch (dir) {
                case "left":
                case "right": {
                    int n = sections.length, step = "left".equals(dir) ? -1 : 1;
                    do section = (section + step + n) % n; while (rec != null && section == 0);
                    row = -1;
                    break;
                }
                case "up": row = Math.max(0, row - 1); break;
                case "down": row = Math.min(actions.size() - 1, row + 1); break;
                default: return;
            }
            render();
        }

        void ok() {
            if (row >= 0 && row < actions.size()) actions.get(row).run();
        }

        void render() {
            tabs.removeAllViews();
            for (int i = 0; i < sections.length; i++) {
                if (rec != null && i == 0) continue;
                TextView t = new TextView(a);
                t.setText(sections[i]);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                t.setTextColor(i == section ? Color.BLACK : Color.WHITE);
                t.setPadding(dp(16), dp(8), dp(16), dp(8));
                t.setBackground(round(i == section ? ACCENT : 0x22FFFFFF, dp(16)));
                int s = i;
                t.setOnClickListener(v -> {
                    section = s;
                    row = -1;
                    render();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
                lp.rightMargin = dp(8);
                tabs.addView(t, lp);
            }
            list.removeAllViews();
            actions = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            List<Boolean> on = new ArrayList<>();
            int current = -1;
            switch (section) {
                case 0:
                    for (int i = 0; i < hosters.length(); i++) {
                        JSONObject ho = hosters.optJSONObject(i);
                        JSONArray vs = ho.optJSONArray("videos");
                        if (vs == null || vs.length() == 0) {
                            int h0 = i;
                            labels.add(hosterName(i) + (App.str(ho, "error").isEmpty() ? (ho.optBoolean("lazy") ? "  ·  load" : "  ·  no videos") : "  ·  failed"));
                            on.add(false);
                            actions.add(() -> pick(() -> choose(h0, -1, false)));
                            continue;
                        }
                        for (int k = 0; k < vs.length(); k++) {
                            int h0 = i, v0 = k;
                            String vt = App.str(vs.optJSONObject(k), "title");
                            labels.add(vt.toLowerCase(Locale.ROOT).contains(hosterName(i).toLowerCase(Locale.ROOT)) ? vt : hosterName(i) + "  ·  " + vt);
                            boolean cur = i == hi && k == vi;
                            if (cur) current = labels.size() - 1;
                            on.add(cur);
                            actions.add(() -> pick(() -> choose(h0, v0, false)));
                        }
                    }
                    if (labels.isEmpty()) labels.add("No servers yet");
                    break;
                case 1: {
                    JSONArray names = trackNames(C.TRACK_TYPE_TEXT);
                    int sel = selectedTrack(C.TRACK_TYPE_TEXT);
                    labels.add("Off");
                    on.add(sel < 0);
                    actions.add(() -> pick(() -> pickTrack(C.TRACK_TYPE_TEXT, -1)));
                    for (int i = 0; i < names.length(); i++) {
                        int k = i;
                        labels.add(names.optString(i));
                        on.add(i == sel);
                        actions.add(() -> pick(() -> pickTrack(C.TRACK_TYPE_TEXT, k)));
                    }
                    current = sel + 1;
                    break;
                }
                case 2: {
                    JSONArray names = trackNames(C.TRACK_TYPE_AUDIO);
                    int sel = selectedTrack(C.TRACK_TYPE_AUDIO);
                    for (int i = 0; i < names.length(); i++) {
                        int k = i;
                        labels.add(names.optString(i));
                        on.add(i == sel);
                        actions.add(() -> pick(() -> pickTrack(C.TRACK_TYPE_AUDIO, k)));
                    }
                    if (labels.isEmpty()) labels.add("Only one audio track");
                    current = sel;
                    break;
                }
                default:
                    for (double r : RATES) {
                        labels.add(fmtRate((float) r));
                        boolean cur = Math.abs(r - rate) < 0.01;
                        if (cur) current = labels.size() - 1;
                        on.add(cur);
                        actions.add(() -> pick(() -> {
                            rate = (float) r;
                            if (exo != null) exo.setPlaybackSpeed(rate);
                        }));
                    }
            }
            if (row < 0) row = Math.max(0, current);
            row = Math.min(row, Math.max(0, actions.size() - 1));
            for (int i = 0; i < labels.size(); i++) {
                TextView t = new TextView(a);
                boolean sel = i < on.size() && on.get(i);
                t.setText((sel ? "✓  " : "     ") + labels.get(i));
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                t.setTextColor(Color.WHITE);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(dp(14), dp(11), dp(14), dp(11));
                boolean hl = i == row && i < actions.size();
                GradientDrawable bg = round(hl ? 0x557AA7FF : 0, dp(12));
                if (hl) bg.setStroke(dp(2), ACCENT);
                t.setBackground(bg);
                if (i < actions.size()) {
                    int k = i;
                    t.setOnClickListener(v -> {
                        row = k;
                        actions.get(k).run();
                    });
                }
                list.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
                if (hl) {
                    View tv = t;
                    scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, tv.getTop() - dp(120))));
                }
            }
        }

        private void pick(Runnable r) {
            r.run();
            h.postDelayed(() -> {
                if (isOpen()) render();
            }, 400);
        }
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
