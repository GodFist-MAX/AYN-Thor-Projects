package dev.grokbuild.coronamon;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which app is in front on each screen, from the activity manager's dump (needs DUMP and the
 * usage-stats app op, granted over adb). Polled every few seconds while a screen is on.
 */
final class Foreground {
    interface Listener {
        void changed(Map<Integer, String> top);
    }

    private static final Pattern DISPLAY = Pattern.compile("(?m)^Display #(\\d+)");
    private static final Pattern RECORD = Pattern.compile("ActivityRecord\\{[0-9a-f]+ u\\d+ ([\\w.]+)/([\\w.$]+)");
    private static final Pattern WINDOW = Pattern.compile("\n  Window #\\d+ ");
    private static final Pattern WIN_DISPLAY = Pattern.compile("mDisplayId=(\\d+)");
    private static final Pattern WIN_PACKAGE = Pattern.compile(" package=([\\w.]+)");
    private static final Pattern WIN_PRESENTATION = Pattern.compile(" ty=(PRESENTATION|PRIVATE_PRESENTATION|2037|2030)\\b");
    /** Activities that play video in an app that isn't a video app. */
    private static final Set<String> VIDEO_ACTIVITIES = new HashSet<>(Arrays.asList("dev.crossbar.launcher/.PlayerActivity"));
    /** A video app's menu or remote on the other screen: nothing to watch there. */
    private static final Set<String> COMPANIONS = new HashSet<>(Arrays.asList("dev.anideck.app/.BottomActivity"));
    static final Set<String> EMULATORS = new HashSet<>(Arrays.asList(
            "com.retroarch.aarch64", "com.retroarch", "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold", "me.magnum.melonds",
            "org.azahar_emu.azahar", "xyz.aethersx2.android", "dev.igawa6.edenduo", "cyou.joiplay.joiplay",
            "org.dolphinemu.dolphinemu", "info.cemu.cemu", "com.github.stenzek.duckstation", "dev.eden.eden_emulator",
            "org.citra.citra_emu", "skyline.emu", "org.yuzu.yuzu_emu", "com.explusalpha.Snes9xPlus"));
    private static final Set<String> NEVER = new HashSet<>(Arrays.asList(
            "dev.crossbar.launcher", "rip.moth.cocoonshell", Registry.SELF, "com.android.systemui",
            "com.android.launcher3", "com.android.settings", "com.google.android.permissioncontroller"));

    private final Context ctx;
    private final Registry registry;
    private final Handler main, work;
    private final Listener listener;
    private volatile Map<Integer, String> last = new HashMap<>();
    /** Display id to "package/.Activity" in front. */
    private volatile Map<Integer, String> activities = new HashMap<>();
    /** Display id to the app showing a presentation there: a dual-screen game's second screen. */
    private volatile Map<Integer, String> presenting = new HashMap<>();
    private volatile boolean watchWindows, windowsRead;
    private final Runnable pollTask = this::poll;
    private final Map<String, Long> onScreenAt = new ConcurrentHashMap<>();
    /** The last app other than Coronamon's own screens (the den, the report trampoline) on each display. */
    private final Map<Integer, String> lastApp = new ConcurrentHashMap<>();
    private final Map<String, Boolean> avoidCache = new HashMap<>();
    private boolean running, denied;
    private final File tmp, tmpWindows;

    Foreground(Context ctx, Registry registry, Handler main, Listener listener) {
        this.ctx = ctx;
        this.registry = registry;
        this.main = main;
        this.listener = listener;
        HandlerThread t = new HandlerThread("foreground");
        t.start();
        work = new Handler(t.getLooper());
        tmp = new File(ctx.getCacheDir(), "activities.txt");
        tmpWindows = new File(ctx.getCacheDir(), "windows.txt");
    }

    void start() {
        if (running) return;
        running = true;
        work.post(pollTask);
    }

    void stop() {
        running = false;
        work.removeCallbacksAndMessages(null);
    }

    void quit() {
        stop();
        work.getLooper().quitSafely();
    }

    boolean working() {
        return !denied;
    }

    /** Display id to the package in front, as of the last poll, looking past Coronamon's own screens. */
    Map<Integer, String> current() {
        Map<Integer, String> m = new HashMap<>(last);
        for (Map.Entry<Integer, String> e : m.entrySet()) {
            if (Registry.SELF.equals(e.getValue()) && lastApp.containsKey(e.getKey())) e.setValue(lastApp.get(e.getKey()));
        }
        return m;
    }

    boolean recentlyOnScreen(String pkg, long ms) {
        Long t = onScreenAt.get(pkg);
        return t != null && System.currentTimeMillis() - t <= ms;
    }

    /** "package/.Activity" in front on the display, or null. */
    String activity(int displayId) {
        return activities.get(displayId);
    }

    /** The app showing a presentation (a dual-screen game's second screen) on the display, or null. */
    String presenter(int displayId) {
        return presenting.get(displayId);
    }

    /**
     * Also read the window list each poll, for presentations, which don't show up as activities.
     * It's a bigger dump, so only while a game or video is in front somewhere.
     */
    void watchWindows(boolean on) {
        if (on == watchWindows) return;
        watchWindows = on;
        windowsRead = false;
        if (!on) presenting = new HashMap<>();
        if (on && running) {
            work.removeCallbacks(pollTask);
            work.post(pollTask);
        }
    }

    /** Whether presentations have been checked since watchWindows(true). */
    boolean windowsRead() {
        return !watchWindows || windowsRead;
    }

    /**
     * Whether what's in front on the display is something to watch or play: a game, an emulator or a
     * video app (but not a video app's companion screen), a video in CrossBar's player, or another
     * app's presentation.
     */
    boolean content(int displayId, String pkg) {
        if (presenting.containsKey(displayId)) return true;
        String act = activities.get(displayId);
        if (act != null && VIDEO_ACTIVITIES.contains(act)) return true;
        if (act != null && COMPANIONS.contains(act)) return false;
        return avoid(pkg);
    }

    private void poll() {
        if (!running) return;
        Map<Integer, String> acts = new HashMap<>();
        Map<Integer, String> top = read(acts);
        if (top != null) {
            long now = System.currentTimeMillis();
            for (Map.Entry<Integer, String> e : top.entrySet()) {
                onScreenAt.put(e.getValue(), now);
                if (!Registry.SELF.equals(e.getValue())) lastApp.put(e.getKey(), e.getValue());
            }
            boolean windows = watchWindows;
            Map<Integer, String> pres = windows ? presentations() : new HashMap<>();
            if (!top.equals(last) || !acts.equals(activities) || !pres.equals(presenting)) {
                last = top;
                activities = acts;
                presenting = pres;
                Map<Integer, String> copy = new HashMap<>(top);
                main.post(() -> listener.changed(copy));
            }
            if (windows) windowsRead = true;
        }
        work.postDelayed(pollTask, 2500);
    }

    private Map<Integer, String> presentations() {
        Map<Integer, String> out = new HashMap<>();
        try (FileOutputStream o = new FileOutputStream(tmpWindows)) {
            if (!Debug.dumpService("window", o.getFD(), new String[]{"windows"})) return out;
        } catch (Exception e) {
            return out;
        }
        String s = Util.readText(tmpWindows);
        if (s == null) return out;
        for (String w : WINDOW.split(s)) {
            if (!w.contains("isOnScreen=true") || !w.contains("mViewVisibility=0x0") || !WIN_PRESENTATION.matcher(w).find()) continue;
            Matcher d = WIN_DISPLAY.matcher(w), p = WIN_PACKAGE.matcher(w);
            if (d.find() && p.find() && !NEVER.contains(p.group(1))) out.put(Integer.parseInt(d.group(1)), p.group(1));
        }
        return out;
    }

    private Map<Integer, String> read(Map<Integer, String> acts) {
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (!Debug.dumpService("activity", out.getFD(), new String[]{"activities"})) return null;
        } catch (Exception e) {
            return null;
        }
        String s = Util.readText(tmp);
        if (s == null || s.length() < 50 || s.contains("Permission Denial")) {
            if (!denied) Log.w("activity dump unavailable (grant android.permission.DUMP)");
            denied = true;
            return null;
        }
        denied = false;
        Map<Integer, String> top = new HashMap<>();
        Matcher d = DISPLAY.matcher(s);
        int prevId = -1, prevAt = -1;
        while (d.find()) {
            if (prevId >= 0) put(top, acts, prevId, s.substring(prevAt, d.start()));
            prevId = Integer.parseInt(d.group(1));
            prevAt = d.end();
        }
        if (prevId >= 0) {
            int end = s.indexOf("\nActivityTaskSupervisor state:", prevAt);
            put(top, acts, prevId, s.substring(prevAt, end > prevAt ? end : s.length()));
        }
        return top;
    }

    private static void put(Map<Integer, String> top, Map<Integer, String> acts, int id, String section) {
        int r = section.indexOf("topResumedActivity=");
        Matcher m = RECORD.matcher(r >= 0 ? section.substring(r) : section);
        if (!m.find()) return;
        String pkg = m.group(1), act = m.group(2);
        if (act.startsWith(pkg + ".")) act = act.substring(pkg.length());
        top.put(id, pkg);
        acts.put(id, pkg + "/" + act);
    }

    /** Games, emulators and video players: Coronamon keeps off their screen. */
    boolean avoid(String pkg) {
        if (pkg == null || NEVER.contains(pkg)) return false;
        Boolean c = avoidCache.get(pkg);
        if (c != null) return c;
        boolean v = EMULATORS.contains(pkg) || registry.avoid(pkg);
        if (!v) {
            try {
                ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
                v = ai.category == ApplicationInfo.CATEGORY_GAME || ai.category == ApplicationInfo.CATEGORY_VIDEO
                        || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        avoidCache.put(pkg, v);
        return v;
    }

    void forget(String pkg) {
        avoidCache.remove(pkg);
    }
}
