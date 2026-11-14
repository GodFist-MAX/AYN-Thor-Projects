package dev.crossbar.launcher;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Social notifications and other apps' media sessions ("now playing"). */
public class NotifListener extends NotificationListenerService {
    static volatile NotifListener I;
    private static final LinkedHashMap<String, JSONObject> recent = new LinkedHashMap<>();
    static final Set<String> SOCIAL = new HashSet<>(Arrays.asList(
            "com.twitter.android", "com.discord", "com.valvesoftware.android.steam.community",
            "com.valvesoftware.steamlink", "com.google.android.youtube", "com.google.android.apps.youtube.music",
            "com.google.android.videos", "com.tiktok.lite.go", "com.zhiliaoapp.musically", "com.reddit.frontpage",
            "org.telegram.messenger", "com.whatsapp", "com.instagram.android", "com.facebook.orca",
            "com.facebook.katana", "com.snapchat.android", "com.bsky.app", "tv.twitch.android.app",
            "com.google.android.apps.messaging", "com.google.android.gm"));

    private final List<MediaController> watched = new ArrayList<>();
    private final Set<String> othersPlaying = new HashSet<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::watch;
    private final MediaController.Callback sessionCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { mediaChanged(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { mediaChanged(); }
        @Override public void onSessionDestroyed() { mediaChanged(); }
    };

    @Override
    public void onListenerConnected() {
        I = this;
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) for (StatusBarNotification sbn : active) add(sbn);
        } catch (Exception ignored) {
        }
        try {
            MediaSessionManager m = getSystemService(MediaSessionManager.class);
            ComponentName me = new ComponentName(this, NotifListener.class);
            m.addOnActiveSessionsChangedListener(sessionsChanged, me, App.I.main);
            watch(m.getActiveSessions(me));
        } catch (Exception e) {
            Log.w(App.TAG, "can't watch media sessions: " + e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        I = null;
        try {
            getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(sessionsChanged);
        } catch (Exception ignored) {
        }
        watch(null);
    }

    /** Follows every active media session, for the joystick lights and for music playing alongside a game. */
    private void watch(List<MediaController> list) {
        for (MediaController mc : watched) mc.unregisterCallback(sessionCallback);
        watched.clear();
        if (list != null) {
            for (MediaController mc : list) {
                mc.registerCallback(sessionCallback, App.I.main);
                watched.add(mc);
            }
        }
        mediaChanged();
    }

    private void mediaChanged() {
        Set<String> now = new HashSet<>();
        for (MediaController mc : watched) {
            PlaybackState st = mc.getPlaybackState();
            String pkg = mc.getPackageName();
            if (st == null || st.getState() != PlaybackState.STATE_PLAYING || pkg.equals(getPackageName())) continue;
            App.I.mediaHistory.playing(pkg, mc.getMetadata());
            if (!App.I.apps.isGame(pkg)) now.add(pkg);
        }
        boolean started = !othersPlaying.containsAll(now);
        othersPlaying.clear();
        othersPlaying.addAll(now);
        if (started) MusicService.otherMediaStarted();
        App.I.lights.refresh();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        add(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        synchronized (recent) {
            JSONObject o = recent.get(sbn.getKey());
            if (o != null) Util.put(o, "active", false);
        }
    }

    private void add(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName()) || sbn.isOngoing()) return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        boolean social = SOCIAL.contains(pkg) || Notification.CATEGORY_MESSAGE.equals(n.category)
                || Notification.CATEGORY_SOCIAL.equals(n.category) || Notification.CATEGORY_EMAIL.equals(n.category);
        if (!social) return;
        Bundle ex = n.extras;
        CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (text == null) text = ex.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null && text == null) return;
        String label = pkg;
        try {
            PackageManager pm = getPackageManager();
            label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception ignored) {
        }
        JSONObject o = Util.obj("key", sbn.getKey(), "pkg", pkg, "app", label,
                "title", title == null ? "" : title.toString(), "text", text == null ? "" : text.toString(),
                "time", sbn.getPostTime(), "active", true, "category", n.category == null ? "" : n.category);
        synchronized (recent) {
            recent.remove(sbn.getKey());
            recent.put(sbn.getKey(), o);
            Iterator<String> it = recent.keySet().iterator();
            while (recent.size() > 80 && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    static JSONArray list() {
        List<JSONObject> l;
        synchronized (recent) {
            l = new ArrayList<>(recent.values());
        }
        Collections.reverse(l);
        JSONArray arr = new JSONArray();
        for (JSONObject o : l) arr.put(o);
        return arr;
    }

    static int count() {
        synchronized (recent) {
            int n = 0;
            for (JSONObject o : recent.values()) if (o.optBoolean("active")) n++;
            return n;
        }
    }

    static boolean granted(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(new ComponentName(c, NotifListener.class));
    }

    static String open(String key) {
        NotifListener l = I;
        if (l == null) return "Notification access is off";
        try {
            StatusBarNotification[] a = l.getActiveNotifications(new String[]{key});
            if (a != null && a.length > 0 && a[0].getNotification().contentIntent != null) {
                a[0].getNotification().contentIntent.send();
                return null;
            }
        } catch (Exception ignored) {
        }
        return "That notification is no longer available";
    }

    static List<MediaController> sessions(Context c) {
        try {
            MediaSessionManager m = c.getSystemService(MediaSessionManager.class);
            List<MediaController> l = m.getActiveSessions(new ComponentName(c, NotifListener.class));
            return l != null ? l : Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static MediaController current(Context c) {
        MediaController paused = null;
        for (MediaController mc : sessions(c)) {
            if (mc.getPackageName().equals(c.getPackageName())) continue;
            PlaybackState st = mc.getPlaybackState();
            if (mc.getMetadata() == null) continue;
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return mc;
            if (paused == null) paused = mc;
        }
        return paused;
    }

    static JSONObject nowPlaying(Context c) {
        return info(c, current(c));
    }

    /** One app's session, playing or not, for a screen that follows that app. */
    static JSONObject nowPlaying(Context c, String pkg) {
        return info(c, session(c, pkg));
    }

    private static JSONObject info(Context c, MediaController mc) {
        if (mc == null || mc.getMetadata() == null) return Util.obj("active", false);
        MediaMetadata md = mc.getMetadata();
        PlaybackState st = mc.getPlaybackState();
        String label = mc.getPackageName();
        try {
            PackageManager pm = c.getPackageManager();
            label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(label, 0)));
        } catch (Exception ignored) {
        }
        String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        long duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long actions = st == null ? 0 : st.getActions();
        String kind = App.I.apps.kind(mc.getPackageName());
        return Util.obj("active", true, "pkg", mc.getPackageName(), "app", label,
                "title", title == null ? "" : title, "artist", artist == null ? "" : artist,
                "playing", st != null && st.getState() == PlaybackState.STATE_PLAYING,
                "position", positionOf(mc.getPackageName(), st, duration), "duration", duration,
                "speed", st == null ? 0 : st.getPlaybackSpeed(),
                "kind", kind, "video", "video".equals(kind),
                "canSeek", (actions & PlaybackState.ACTION_SEEK_TO) != 0,
                "canPrev", (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0,
                "canNext", (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0,
                "canShow", canShow(c, mc));
    }

    /** PlaybackState holds the position at its last update; while playing it has moved on since, at the playback speed. */
    static long position(PlaybackState st, long duration) {
        if (st == null) return 0;
        long p = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
            p += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        if (duration > 0) p = Math.min(p, duration);
        return Math.max(0, p);
    }

    static Bitmap art(Context c) {
        return art(current(c));
    }

    static Bitmap art(Context c, String pkg) {
        return art(session(c, pkg));
    }

    private static Bitmap art(MediaController mc) {
        if (mc == null || mc.getMetadata() == null) return null;
        MediaMetadata md = mc.getMetadata();
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b;
    }

    /** An app's session: the playing one if it has several, else one with something loaded. */
    private static MediaController session(Context c, String pkg) {
        MediaController best = null;
        for (MediaController mc : sessions(c)) {
            if (!mc.getPackageName().equals(pkg)) continue;
            PlaybackState st = mc.getPlaybackState();
            if (mc.getMetadata() != null && st != null && st.getState() == PlaybackState.STATE_PLAYING) return mc;
            if (best == null || best.getMetadata() == null && mc.getMetadata() != null) best = mc;
        }
        return best;
    }

    static String control(Context c, String pkg, String cmd) {
        MediaController mc = session(c, pkg);
        if (mc == null) return "Nothing is playing";
        MediaController.TransportControls t = mc.getTransportControls();
        PlaybackState st = mc.getPlaybackState();
        switch (cmd) {
            case "toggle":
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) t.pause();
                else t.play();
                break;
            case "next": t.skipToNext(); break;
            case "prev": t.skipToPrevious(); break;
            case "pause": t.pause(); break;
            case "play": t.play(); break;
            case "back10":
            case "fwd10":
                MediaMetadata md = mc.getMetadata();
                long duration = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                long to = Math.max(0, positionOf(mc.getPackageName(), st, duration) + ("fwd10".equals(cmd) ? 10_000 : -10_000));
                if (duration > 0) to = Math.min(duration, to);
                t.seekTo(to);
                seekPkg = mc.getPackageName();
                seekTarget = to;
                seekAt = SystemClock.elapsedRealtime();
                break;
            default: break;
        }
        return null;
    }

    /**
     * The last ±10 s jump. Apps report where it went only once they've loaded it, which can take
     * seconds, and a second tap before then goes on from the jump, not from the old position.
     */
    private static String seekPkg;
    private static long seekTarget, seekAt;

    /** Where an app's playback is: until it reports the last jump, where the jump went. */
    private static long positionOf(String pkg, PlaybackState st, long duration) {
        long reported = position(st, duration);
        long since = SystemClock.elapsedRealtime() - seekAt;
        if (since >= 15000 || !pkg.equals(seekPkg)) return reported;
        long p = seekTarget + (st != null && st.getState() == PlaybackState.STATE_PLAYING ? (long) (since * st.getPlaybackSpeed()) : 0);
        if (duration > 0) p = Math.min(duration, p);
        return Math.abs(reported - p) < 1500 ? reported : p;
    }

    /** Whether the app behind a session can be brought up on another screen: it has a screen of its own to open. */
    private static boolean canShow(Context c, MediaController mc) {
        PendingIntent pi = mc.getSessionActivity();
        return pi != null && pi.isActivity() || c.getPackageManager().getLaunchIntentForPackage(mc.getPackageName()) != null;
    }

    /**
     * Opens the app behind a media session on a screen: the screen its session names (YouTube's is the
     * watch page), else its launcher activity. A running app moves there with its task, so its video
     * carries on where it was.
     */
    static boolean show(Context c, String pkg, int displayId) {
        MediaController mc = session(c, pkg);
        PendingIntent pi = mc == null ? null : mc.getSessionActivity();
        if (pi != null && pi.isActivity()) {
            try {
                pi.send(c, 0, null, null, null, null, Screens.on(displayId));
                return true;
            } catch (PendingIntent.CanceledException e) {
                Log.w(App.TAG, "session activity of " + pkg + " is gone: " + e);
            }
        }
        return App.I.apps.launch(pkg, displayId);
    }
}
