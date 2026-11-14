package dev.grokbuild.coronamon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.DropBoxManager;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catches crashes and freezes from the system DropBox (needs READ_LOGS and the usage-stats app op,
 * both granted over adb), saves each report to /sdcard/Coronamon/reports/<package>/ and records it
 * as a bug for Grok Build. Covers the apps Coronamon looks after, itself included, and any other
 * non-system app that was on screen when it went down (emulators, ports). Entries that arrived
 * while Coronamon wasn't running are picked up when it starts.
 */
final class CrashWatch {
    interface Listener {
        /** react: it just happened, and not within a minute of the app's last crash. */
        void crashed(String pkg, String kind, File report, String headline, Bugs.Result bug, boolean managed, boolean react);
    }

    /** What was on each screen, for bug context and for deciding about apps Coronamon doesn't manage. */
    interface OnScreen {
        JSONObject now();

        boolean recently(String pkg, long ms);
    }

    private static final Pattern PROCESS = Pattern.compile("(?m)^Process: ([\\w.:]+)");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^Package: ([\\w.]+)");
    private static final Pattern PID = Pattern.compile("(?m)^(?:PID: |pid: )(\\d+)");
    private static final String[] TAGS = {"data_app_crash", "data_app_anr", "data_app_native_crash"};
    private static final int KEEP = 30;

    private final Context ctx;
    private final Registry registry;
    private final Bugs bugs;
    private final OnScreen screens;
    private final Listener listener;
    private final Handler main;
    private final Handler work;
    private final SharedPreferences seen;
    private final Map<String, Long> lastReact = new HashMap<>();

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String tag = i.getStringExtra(DropBoxManager.EXTRA_TAG);
            if (tag != null && Arrays.asList(TAGS).contains(tag)) work.post(() -> drain(tag, true));
        }
    };

    CrashWatch(Context ctx, Registry registry, Bugs bugs, OnScreen screens, Handler main, Listener listener) {
        this.ctx = ctx;
        this.registry = registry;
        this.bugs = bugs;
        this.screens = screens;
        this.main = main;
        this.listener = listener;
        seen = ctx.getSharedPreferences("crashwatch", Context.MODE_PRIVATE);
        HandlerThread t = new HandlerThread("crashwatch");
        t.start();
        work = new Handler(t.getLooper());
    }

    void start() {
        ctx.registerReceiver(rx, new IntentFilter(DropBoxManager.ACTION_DROPBOX_ENTRY_ADDED), Context.RECEIVER_EXPORTED);
        work.post(this::catchUp);
    }

    void stop() {
        try {
            ctx.unregisterReceiver(rx);
        } catch (Exception ignored) {
        }
        work.getLooper().quitSafely();
    }

    /** The first time: the saved reports become bugs. After that: whatever arrived while stopped. */
    private void catchUp() {
        if (!seen.getBoolean("init", false)) {
            importReports();
            SharedPreferences.Editor e = seen.edit().putBoolean("init", true);
            for (String tag : TAGS) e.putLong(tag, System.currentTimeMillis());
            e.apply();
            return;
        }
        for (String tag : TAGS) drain(tag, false);
    }

    /** Every entry for the tag newer than the last one handled. */
    private void drain(String tag, boolean live) {
        DropBoxManager dbm = ctx.getSystemService(DropBoxManager.class);
        long after = seen.getLong(tag, System.currentTimeMillis() - 60_000);
        for (int n = 0; n < 50; n++) {
            DropBoxManager.Entry e = null;
            try {
                e = dbm.getNextEntry(tag, after);
                if (e == null) return;
                after = e.getTimeMillis();
                seen.edit().putLong(tag, after).apply();
                String text = e.getText(256 * 1024);
                if (text != null) handle(tag, after, text, live && System.currentTimeMillis() - after < 120_000);
            } catch (SecurityException se) {
                Log.w("dropbox needs READ_LOGS + PACKAGE_USAGE_STATS: " + se.getMessage());
                return;
            } catch (Exception ex) {
                Log.w("crashwatch: " + ex);
                return;
            } finally {
                if (e != null) e.close();
            }
        }
    }

    private void handle(String tag, long time, String text, boolean live) {
        String pkg = null;
        Matcher m = PACKAGE.matcher(text);
        if (m.find()) pkg = m.group(1);
        if (pkg == null) {
            m = PROCESS.matcher(text);
            if (m.find()) pkg = m.group(1).split(":")[0];
        }
        if (pkg == null) return;
        boolean managed = registry.has(pkg);
        if (!managed && !(onScreen(text, pkg, live) && !system(pkg))) return;
        String kind = tag.endsWith("anr") ? "anr" : tag.endsWith("native_crash") ? "native" : "crash";
        File report = save(pkg, kind, time, text);
        File log = live ? log(text, report) : null;
        JSONObject context = new JSONObject();
        if (live) Util.put(context, "screens", screens.now());
        Util.put(context, "foreground", text.contains("\nForeground: Yes"));
        Bugs.Result bug = bugs.crash(pkg, registry.label(pkg), kind, text, time, report, log, context, !managed);
        if (Registry.SELF.equals(pkg)) return;
        String headline = headline(text, kind);
        Long last = lastReact.get(pkg);
        boolean react = live && (last == null || time - last > 60_000);
        if (live) lastReact.put(pkg, time);
        String p = pkg;
        main.post(() -> listener.crashed(p, kind, report, headline, bug, managed, react));
    }

    /** Apps Coronamon doesn't manage only count if they were in front when they went down. */
    private boolean onScreen(String text, String pkg, boolean live) {
        return text.contains("\nForeground: Yes") || (live && screens.recently(pkg, 120_000));
    }

    private boolean system(String pkg) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (Exception e) {
            return true;
        }
    }

    /** The process's recent logcat, through Shizuku, next to the report. */
    private static File log(String text, File report) {
        Matcher m = PID.matcher(text);
        if (!m.find() || !Shell.granted()) return null;
        File f = new File(report.getPath().replace(".txt", "-log.txt"));
        try {
            Shell.run("logcat -d -v threadtime -t 1500 --pid=" + m.group(1) + " > '" + f.getPath() + "'", 8000);
        } catch (Exception e) {
            return null;
        }
        return f.length() > 0 ? f : null;
    }

    private void importReports() {
        File[] dirs = Paths.REPORTS.listFiles(File::isDirectory);
        if (dirs == null) return;
        List<File> all = new ArrayList<>();
        for (File d : dirs) {
            File[] fs = d.listFiles((dd, name) -> name.endsWith(".txt") && !name.endsWith("-log.txt"));
            if (fs != null) all.addAll(Arrays.asList(fs));
        }
        all.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : all) {
            String text = Util.readText(f);
            if (text == null) continue;
            String pkg = f.getParentFile().getName(), name = f.getName();
            String kind = name.endsWith("-anr.txt") ? "anr" : name.endsWith("-native.txt") ? "native" : "crash";
            File log = new File(f.getPath().replace(".txt", "-log.txt"));
            bugs.crash(pkg, registry.label(pkg), kind, text, f.lastModified(), f, log.isFile() ? log : null,
                    Util.obj("foreground", text.contains("\nForeground: Yes")), !registry.has(pkg));
        }
        if (!all.isEmpty()) Log.i("imported " + all.size() + " saved crash reports as bugs");
    }

    private static File save(String pkg, String kind, long time, String text) {
        File dir = new File(Paths.REPORTS, pkg);
        File f = new File(dir, Util.stamp(time) + "-" + kind + ".txt");
        Util.writeText(f, text);
        File[] fs = dir.listFiles((d, name) -> name.endsWith(".txt") && !name.endsWith("-log.txt"));
        if (fs != null && fs.length > KEEP) {
            Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < fs.length - KEEP; i++) {
                new File(fs[i].getPath().replace(".txt", "-log.txt")).delete();
                fs[i].delete();
            }
        }
        return f;
    }

    /** First exception line, or the ANR subject. */
    static String headline(String text, String kind) {
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (kind.equals("anr") && l.startsWith("Subject:")) return l.substring(8).trim();
            if (l.matches("^([\\w$]+\\.)+[\\w$]*(Exception|Error|Throwable)(:.*)?$")) return l.length() > 200 ? l.substring(0, 200) : l;
            if (l.startsWith("Abort message:") || l.startsWith("signal ")) return l;
        }
        return kind.equals("anr") ? "Not responding" : "Crashed";
    }

    static JSONArray list(String pkg, int n) {
        JSONArray out = new JSONArray();
        File[] dirs = pkg != null ? new File[]{new File(Paths.REPORTS, pkg)} : Paths.REPORTS.listFiles(File::isDirectory);
        if (dirs == null) return out;
        List<File> all = new ArrayList<>();
        for (File d : dirs) {
            File[] fs = d.listFiles((dd, name) -> name.endsWith(".txt") && !name.endsWith("-log.txt"));
            if (fs != null) all.addAll(Arrays.asList(fs));
        }
        all.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 0; i < Math.min(n, all.size()); i++) {
            File f = all.get(i);
            String text = Util.readText(f);
            String name = f.getName();
            String kind = name.endsWith("-anr.txt") ? "anr" : name.endsWith("-native.txt") ? "native" : "crash";
            out.put(Util.obj("pkg", f.getParentFile().getName(), "path", f.getAbsolutePath(), "time", f.lastModified(),
                    "kind", kind, "headline", text == null ? "" : headline(text, kind)));
        }
        return out;
    }
}
