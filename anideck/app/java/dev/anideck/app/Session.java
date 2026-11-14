package dev.anideck.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import org.json.JSONObject;

/**
 * Publishes the episode on the top screen as a media session: system media controls work,
 * and other apps (CrossBar's joystick lights and music) can see what is playing and its cover.
 */
final class Session {
    private static final long ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD
            | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;

    private final MediaSession s;
    private String meta = "";
    private int artId, artLoading;
    private Bitmap art;
    private JSONObject last = new JSONObject();
    private int lastState = -1;
    private long lastPos, lastAt;

    Session(Context c) {
        s = new MediaSession(c, "AniDeck");
        s.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                top(App.obj("c", "play"));
            }

            @Override public void onPause() {
                top(App.obj("c", "pause"));
            }

            @Override public void onStop() {
                top(App.obj("c", "pause"));
            }

            @Override public void onSkipToNext() {
                top(App.obj("c", "next"));
            }

            @Override public void onSkipToPrevious() {
                top(App.obj("c", "prev"));
            }

            @Override public void onFastForward() {
                top(App.obj("c", "seekBy", "d", 10));
            }

            @Override public void onRewind() {
                top(App.obj("c", "seekBy", "d", -10));
            }

            @Override public void onSeekTo(long ms) {
                top(App.obj("c", "seek", "t", ms / 1000.0));
            }
        }, App.I.main);
    }

    private static void top(JSONObject cmd) {
        MainActivity m = App.I.top;
        if (m != null) m.cmd(cmd);
    }

    void update(JSONObject st) {
        last = st;
        JSONObject p = st.optJSONObject("player");
        if (p == null) {
            if (s.isActive()) {
                s.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_NONE, 0, 0).build());
                s.setActive(false);
            }
            meta = "";
            lastState = -1;
            return;
        }
        int id = st.optInt("anilistId");
        if (id > 0 && id != artId && id != artLoading) loadArt(id);
        String title = App.str(st, "show"), epTitle = App.str(st, "epTitle");
        String sub = "Episode " + st.optInt("ep") + (epTitle.isEmpty() ? "" : " · " + epTitle);
        long duration = (long) (p.optDouble("d", 0) * 1000);
        Bitmap cover = id > 0 && id == artId ? art : null;
        String key = title + '|' + sub + '|' + duration / 1000 + '|' + (cover != null);
        if (!key.equals(meta)) {
            meta = key;
            MediaMetadata.Builder b = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, sub)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, sub)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
            if (cover != null) b.putBitmap(MediaMetadata.METADATA_KEY_ART, cover);
            s.setMetadata(b.build());
        }
        String ps = App.str(p, "state");
        int state;
        switch (ps) {
            case "playing": state = PlaybackState.STATE_PLAYING; break;
            case "loading": case "waiting": case "buffering": case "stalled": state = PlaybackState.STATE_BUFFERING; break;
            case "error": state = PlaybackState.STATE_ERROR; break;
            default: state = PlaybackState.STATE_PAUSED;
        }
        // Listeners extrapolate the position while playing, so only state changes and seeks are sent.
        long pos = (long) (p.optDouble("t", 0) * 1000), now = SystemClock.elapsedRealtime();
        long expected = lastState == PlaybackState.STATE_PLAYING ? lastPos + now - lastAt : lastPos;
        if (state != lastState || Math.abs(pos - expected) > 2000) {
            lastState = state;
            lastPos = pos;
            lastAt = now;
            s.setPlaybackState(new PlaybackState.Builder().setActions(ACTIONS)
                    .setState(state, pos, state == PlaybackState.STATE_PLAYING ? 1f : 0f, now)
                    .build());
        }
        if (!s.isActive()) s.setActive(true);
    }

    private void loadArt(int id) {
        artLoading = id;
        App.I.io.execute(() -> {
            JSONObject info = App.I.library.details(id);
            Bitmap b = info == null ? null : Library.bitmap(App.str(info, "cover"), 512);
            App.I.main.post(() -> {
                if (artLoading != id) return;
                artLoading = 0;
                artId = id;
                art = b;
                meta = "";
                update(last);
            });
        });
    }

    void release() {
        s.release();
    }
}
