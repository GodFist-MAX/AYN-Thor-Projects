package dev.crossbar.launcher;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Tints the Thor's joystick lights with the colors of what's playing. Each ring is an SN3112 LED
 * driver that the Thor's settings app keeps in step with three system settings (a color and an
 * on/off per stick, and one brightness); while media plays this writes the same sysfs nodes, and
 * afterwards it puts the user's colors back.
 */
final class Lights {
    private static final String[] NODES = {"/sys/class/sn3112l/led/brightness", "/sys/class/sn3112r/led/brightness"};
    private static final String COLORS = "joystick_led_light_picker_color", ENABLED = "joystick_light_enabled",
            BRIGHTNESS = "led_light_brightness_percent";
    private static final String[] ART = {MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON};
    private static final String[] ART_URI = {MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI};
    private static final int WHITE = 0xffffffff, FADE_STEPS = 10;
    /** "adb shell setprop log.tag.CrossBarLights DEBUG" logs every write. */
    private static final String LOG_TAG = "CrossBarLights";
    private static Boolean supported;

    private final App app;
    private final Handler h;
    private final PowerManager power;
    private final Map<String, int[]> iconColors = new HashMap<>();
    private final Runnable update = this::update, recheck = this::update, restore = this::restore,
            fade = this::fadeStep, reapply = this::reapply;
    private final boolean[] lit = {true, true};

    /** The media colors on the sticks, or null while they show the user's own. */
    private int[] shown;
    /** The colors last written, before brightness, and the fade under way. */
    private int[] cur, from, to;
    private int step, failures;
    private float brightness = 1f;
    private volatile Uri video;
    private String artKey, front;
    private int[] artColors;
    private long eventsFrom;

    Lights(App app) {
        this.app = app;
        power = app.getSystemService(PowerManager.class);
        HandlerThread t = new HandlerThread("lights");
        t.start();
        h = new Handler(t.getLooper());
        if (!supported()) return;
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                h.removeCallbacks(reapply);
                // The settings app turns the lights off with the screen and re-applies the user's colors 500 ms after it comes on.
                if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) h.postDelayed(reapply, 900);
            }
        }, screen, null, h);
        ContentObserver userChanged = new ContentObserver(h) {
            @Override
            public void onChange(boolean self) {
                h.removeCallbacks(reapply);
                h.postDelayed(reapply, 600);
            }
        };
        for (String k : new String[]{COLORS, ENABLED, BRIGHTNESS})
            app.getContentResolver().registerContentObserver(Settings.System.getUriFor(k), false, userChanged);
        // An earlier run may have stopped (an update, say) with media colors on the sticks.
        if (app.store.bool("mediaLights")) {
            h.post(() -> {
                readUser();
                write(userColors());
                cur = null;
            });
        }
        refresh();
    }

    static boolean supported() {
        if (supported == null) supported = new File(NODES[0]).canWrite() && new File(NODES[1]).canWrite();
        return supported;
    }

    /** Looks again at the setting and at what's playing. */
    void refresh() {
        h.removeCallbacks(update);
        h.postDelayed(update, 150);
        h.removeCallbacks(recheck);
        h.postDelayed(recheck, 1500); // usage events trail a switch of apps
    }

    /** CrossBar's own player is showing this video; null when it stops. */
    void video(Uri u) {
        video = u;
        refresh();
    }

    private void update() {
        if (!app.store.bool("mediaLights") || !supported() || failures >= 5) {
            h.removeCallbacks(restore);
            restore();
            return;
        }
        boolean game = gameInFront();
        Uri v = video;
        MediaController mc = v == null ? playingSession() : null;
        if (v == null && mc == null) {
            // Track changes and seeks stop playback for a moment; don't flash the user's colors for those.
            if (shown != null && !h.hasCallbacks(restore)) h.postDelayed(restore, 2500);
            return;
        }
        // Sessions don't report another app coming to the front.
        if (!h.hasCallbacks(recheck)) h.postDelayed(recheck, 5000);
        h.removeCallbacks(restore);
        if (game) {
            restore();
            return;
        }
        int[] want = v != null ? videoColors(v) : sessionColors(mc);
        if (!Arrays.equals(want, shown)) {
            shown = want;
            fadeTo(want);
        } else if (!h.hasCallbacks(fade)) {
            write(want); // in case something else set the lights since
        }
    }

    private void restore() {
        if (shown == null) return;
        shown = null;
        fadeTo(userColors());
    }

    /** Puts the media colors back after the settings app re-applied the user's (screen on, or the user changed them). */
    private void reapply() {
        if (shown == null || h.hasCallbacks(fade)) return;
        readUser();
        write(shown);
    }

    /** Whether a game or emulator is in front; the lights keep the user's colors for those. */
    private boolean gameInFront() {
        String pkg = frontApp();
        return pkg != null ? app.apps.isGame(pkg) : app.gameTop;
    }

    /** The app whose screen came up last, from usage events; null without usage access. */
    private String frontApp() {
        if (!app.apps.usageAllowed()) return null;
        long now = System.currentTimeMillis();
        try {
            UsageEvents ev = app.getSystemService(UsageStatsManager.class)
                    .queryEvents(eventsFrom > 0 ? eventsFrom : now - 86400_000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED
                        && !BottomActivity.class.getName().equals(e.getClassName())
                        && !FocusActivity.class.getName().equals(e.getClassName())) front = e.getPackageName();
            }
            eventsFrom = now - 2000; // events can be recorded a moment late
        } catch (Exception ignored) {
        }
        return front;
    }

    /** The session to follow: one that's playing, the front app's first; never a game's. */
    private MediaController playingSession() {
        MediaController first = null;
        for (MediaController mc : NotifListener.sessions(app)) {
            PlaybackState st = mc.getPlaybackState();
            String pkg = mc.getPackageName();
            if (st == null || !playing(st.getState()) || app.apps.isGame(pkg)) continue;
            if (pkg.equals(front)) return mc;
            if (first == null) first = mc;
        }
        return first;
    }

    private static boolean playing(int state) {
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_FAST_FORWARDING || state == PlaybackState.STATE_REWINDING;
    }

    private int[] sessionColors(MediaController mc) {
        String pkg = mc.getPackageName();
        MediaMetadata md = mc.getMetadata();
        Bitmap art = null;
        String uri = null, title = "";
        if (md != null) {
            title = md.getDescription().getTitle() + "|" + md.getDescription().getSubtitle();
            for (String k : ART) if (art == null) art = md.getBitmap(k);
            for (String k : ART_URI) if (uri == null) uri = md.getString(k);
        }
        String key = pkg + "|" + title + "|" + (art != null ? art.getWidth() + "x" + art.getHeight() : uri);
        if (!key.equals(artKey)) {
            if (art == null && uri != null) art = load(Uri.parse(uri));
            remember(key, colorsOrIcon(art, pkg));
        }
        return artColors;
    }

    private int[] videoColors(Uri v) {
        String key = "video|" + v;
        if (!key.equals(artKey)) {
            Bitmap thumb = null;
            try {
                thumb = app.getContentResolver().loadThumbnail(v, new Size(160, 160), null);
            } catch (Exception ignored) {
            }
            remember(key, colorsOrIcon(thumb, app.getPackageName()));
        }
        return artColors;
    }

    private void remember(String key, int[] colors) {
        artKey = key;
        artColors = colors;
    }

    /** Artwork another app shares by content URI; web addresses are left alone. */
    private Bitmap load(Uri u) {
        if (!"content".equals(u.getScheme()) && !"android.resource".equals(u.getScheme())) return null;
        try {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.getContentResolver(), u), (d, info, src) -> {
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                d.setTargetSampleSize(Math.max(1, Math.min(info.getSize().getWidth(), info.getSize().getHeight()) / 96));
            });
        } catch (Exception e) {
            return null;
        }
    }

    private int[] colorsOrIcon(Bitmap art, String pkg) {
        int[] c = art == null ? null : colorsOf(art);
        return c != null ? c : iconColors(pkg);
    }

    private int[] iconColors(String pkg) {
        int[] c = iconColors.get(pkg);
        if (c == null) {
            try {
                c = colorsOf(Util.toBitmap(app.getPackageManager().getApplicationIcon(pkg), 64));
            } catch (Exception ignored) {
            }
            if (c == null) c = new int[]{WHITE, WHITE};
            iconColors.put(pkg, c);
        }
        return c;
    }

    /**
     * The main color of a picture and a second one well apart from it (or the main one again), made fully
     * bright since the user's light brightness scales them; white for gray pictures, null for empty ones.
     */
    static int[] colorsOf(Bitmap src) {
        if (src.getConfig() == Bitmap.Config.HARDWARE) src = src.copy(Bitmap.Config.ARGB_8888, false);
        if (src == null) return null;
        Bitmap b = Bitmap.createScaledBitmap(src, 24, 24, true);
        float[] weight = new float[12], x = new float[12], y = new float[12], sat = new float[12], hsv = new float[3];
        float total = 0;
        int opaque = 0;
        for (int py = 0; py < 24; py++) {
            for (int px = 0; px < 24; px++) {
                int p = b.getPixel(px, py);
                if (Color.alpha(p) < 128) continue;
                opaque++;
                Color.colorToHSV(p, hsv);
                if (hsv[1] < .3f || hsv[2] < .25f) continue;
                float w = hsv[1] * hsv[2] * hsv[1] * hsv[2];
                int k = (int) (hsv[0] / 30) % 12;
                double a = Math.toRadians(hsv[0]);
                weight[k] += w;
                x[k] += (float) Math.cos(a) * w;
                y[k] += (float) Math.sin(a) * w;
                sat[k] += hsv[1] * w;
                total += w;
            }
        }
        if (opaque == 0) return null;
        if (total < opaque * .02f) return new int[]{WHITE, WHITE};
        int main = 0;
        for (int k = 1; k < 12; k++) if (weight[k] > weight[main]) main = k;
        int second = -1;
        for (int k = 0; k < 12; k++) {
            int d = Math.abs(k - main);
            if (Math.min(d, 12 - d) >= 2 && weight[k] >= weight[main] * .2f && (second < 0 || weight[k] > weight[second]))
                second = k;
        }
        int c = hue(x[main], y[main], sat[main] / weight[main]);
        return new int[]{c, second < 0 ? c : hue(x[second], y[second], sat[second] / weight[second])};
    }

    private static int hue(float x, float y, float saturation) {
        float deg = (float) Math.toDegrees(Math.atan2(y, x));
        return Color.HSVToColor(new float[]{deg < 0 ? deg + 360 : deg, Math.max(.8f, Math.min(1f, saturation)), 1f});
    }

    private void fadeTo(int[] target) {
        readUser();
        h.removeCallbacks(fade);
        from = cur != null ? cur : userColors();
        to = target;
        step = 0;
        fadeStep();
    }

    private void fadeStep() {
        step++;
        float t = step / (float) FADE_STEPS;
        write(new int[]{mix(from[0], to[0], t), mix(from[1], to[1], t)});
        if (step < FADE_STEPS) h.postDelayed(fade, 40);
        else if (shown == null) cur = null; // the settings app's colors again
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Writes colors the way the settings app does: "1-R:G:B", each scaled by the brightness. */
    private void write(int[] c) {
        cur = c;
        if (!power.isInteractive()) return;
        for (int i = 0; i < 2; i++) {
            if (!lit[i]) continue;
            String v = "1-" + (int) (Color.red(c[i]) * brightness) + ":" + (int) (Color.green(c[i]) * brightness)
                    + ":" + (int) (Color.blue(c[i]) * brightness);
            try (FileOutputStream o = new FileOutputStream(NODES[i])) {
                o.write(v.getBytes(StandardCharsets.US_ASCII));
                failures = 0;
            } catch (IOException e) {
                if (failures++ == 0) Log.w(App.TAG, "joystick lights: " + e.getMessage());
                return;
            }
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) Log.d(LOG_TAG, (i == 0 ? "left " : "right ") + v + (shown == null ? " (user)" : ""));
        }
    }

    private int[] userColors() {
        String[] c = system(COLORS, "#ffffffff").split(",");
        return new int[]{color(c[0]), color(c[c.length > 1 ? 1 : 0])};
    }

    private static int color(String s) {
        try {
            return Color.parseColor(s.trim());
        } catch (IllegalArgumentException e) {
            return WHITE;
        }
    }

    private void readUser() {
        String[] on = system(ENABLED, "1,1").split(",");
        for (int i = 0; i < 2; i++) lit[i] = !"0".equals(on[Math.min(i, on.length - 1)].trim());
        try {
            brightness = Math.max(0f, Math.min(1f, Float.parseFloat(system(BRIGHTNESS, "1").trim())));
        } catch (NumberFormatException e) {
            brightness = 1f;
        }
    }

    private String system(String key, String def) {
        try {
            String v = Settings.System.getString(app.getContentResolver(), key);
            return v == null || v.trim().isEmpty() ? def : v;
        } catch (Exception e) {
            return def;
        }
    }
}
