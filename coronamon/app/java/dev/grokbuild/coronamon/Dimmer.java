package dev.grokbuild.coronamon;

import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notices when Android dims the screens because nobody has touched the Thor for a while: the step
 * before they turn off, or where they stay while it charges with Stay awake on. Android has no
 * public signal for it, so this reads the system's dumps (needs DUMP). One power dump says when the
 * dim is due; while dimmed, the smaller display dump says when the screens brighten again.
 */
final class Dimmer {
    interface Listener {
        void dimChanged(boolean dim);
    }

    private static final Pattern POLICY = Pattern.compile("mPowerRequest=policy=(\\w+)");
    private static final Pattern ACTIVITY = Pattern.compile("lastUserActivityTime=\\d+ \\((\\d+) ms ago\\)");
    private static final Pattern TIMEOUT = Pattern.compile("Screen off timeout: (\\d+) ms");
    private static final Pattern DIM_FOR = Pattern.compile("Screen dim duration: (\\d+) ms");

    private final Handler main, work;
    private final HandlerThread thread;
    private final Listener listener;
    private final File tmp;
    private volatile boolean dim;
    private volatile String policy;
    private boolean running;
    private long dimSince;

    Dimmer(Context ctx, Handler main, Listener listener) {
        this.main = main;
        this.listener = listener;
        tmp = new File(ctx.getCacheDir(), "dim.txt");
        thread = new HandlerThread("dimmer");
        thread.start();
        work = new Handler(thread.getLooper());
    }

    boolean dimmed() {
        return dim;
    }

    /** The display power policy last seen: BRIGHT, DIM, DOZE, OFF, or null before the first look. */
    String policy() {
        return policy;
    }

    /** The screens are on: keep an eye on them. */
    void start() {
        work.post(() -> {
            if (running) return;
            running = true;
            work.removeCallbacks(poll);
            work.post(poll);
        });
    }

    /** The screens went off. A dim stays as it was, so he's still home when they come on and walks out to meet you. */
    void stop() {
        work.post(() -> {
            running = false;
            work.removeCallbacks(poll);
        });
    }

    /** Something touched him, which Android counts as activity: while dimmed, look again now. */
    void poke() {
        work.post(() -> {
            if (!running || !dim) return;
            work.removeCallbacks(poll);
            work.postDelayed(poll, 150);
        });
    }

    void quit() {
        work.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    private final Runnable poll = this::poll;

    private void poll() {
        if (!running) return;
        String p = readPolicy();
        policy = p;
        long next;
        if (p == null) {
            next = 60_000;
        } else if ("DIM".equals(p)) {
            if (!dim) dimSince = SystemClock.uptimeMillis();
            set(true);
            next = SystemClock.uptimeMillis() - dimSince < 60_000 ? 1000 : 2000;
        } else {
            set(false);
            next = "BRIGHT".equals(p) ? untilDim() : 30_000;
        }
        work.postDelayed(poll, next);
    }

    /** When to look again: just after the dim is due if nobody touches anything. */
    private long untilDim() {
        String s = dump("power");
        if (s == null) return 30_000;
        Matcher a = ACTIVITY.matcher(s), t = TIMEOUT.matcher(s), d = DIM_FOR.matcher(s);
        if (!a.find() || !t.find() || !d.find()) return 30_000;
        long due = Long.parseLong(t.group(1)) - Long.parseLong(d.group(1)) - Long.parseLong(a.group(1));
        // Past due and still bright: a video, a game or a wake lock is keeping the screens on.
        return due <= 0 ? 15_000 : Math.max(500, Math.min(60_000, due + 250));
    }

    private String readPolicy() {
        String s = dump("display");
        if (s == null) return null;
        Matcher m = POLICY.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private String dump(String service) {
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            if (!Debug.dumpService(service, o.getFD(), new String[0])) return null;
        } catch (Exception e) {
            return null;
        }
        String s = Util.readText(tmp);
        return s == null || s.contains("Permission Denial") ? null : s;
    }

    private void set(boolean d) {
        if (d == dim) return;
        dim = d;
        Log.i("screens " + (d ? "dimmed" : "bright again"));
        main.post(() -> listener.dimChanged(d));
    }
}
