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
    private static final Pattern RECORD = Pattern.compile("ActivityRecord\\{[0-9a-f]+ u\\d+ ([\\w.]+)/");
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
    private Map<Integer, String> last = new HashMap<>();
    private final Map<String, Boolean> avoidCache = new HashMap<>();
    private boolean running, denied;
    private final File tmp;

    Foreground(Context ctx, Registry registry, Handler main, Listener listener) {
        this.ctx = ctx;
        this.registry = registry;
        this.main = main;
        this.listener = listener;
        HandlerThread t = new HandlerThread("foreground");
        t.start();
        work = new Handler(t.getLooper());
        tmp = new File(ctx.getCacheDir(), "activities.txt");
    }

    void start() {
        if (running) return;
        running = true;
        work.post(this::poll);
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

    private void poll() {
        if (!running) return;
        Map<Integer, String> top = read();
        if (top != null && !top.equals(last)) {
            last = top;
            Map<Integer, String> copy = new HashMap<>(top);
            main.post(() -> listener.changed(copy));
        }
        work.postDelayed(this::poll, 2500);
    }

    private Map<Integer, String> read() {
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
            if (prevId >= 0) put(top, prevId, s.substring(prevAt, d.start()));
            prevId = Integer.parseInt(d.group(1));
            prevAt = d.end();
        }
        if (prevId >= 0) {
            int end = s.indexOf("\nActivityTaskSupervisor state:", prevAt);
            put(top, prevId, s.substring(prevAt, end > prevAt ? end : s.length()));
        }
        return top;
    }

    private static void put(Map<Integer, String> top, int id, String section) {
        int r = section.indexOf("topResumedActivity=");
        Matcher m = RECORD.matcher(r >= 0 ? section.substring(r) : section);
        if (m.find()) top.put(id, m.group(1));
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
