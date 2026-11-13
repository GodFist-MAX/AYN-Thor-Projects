package dev.grokbuild.coronamon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.DropBoxManager;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONArray;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catches crashes and freezes of the apps Coronamon looks after from the system DropBox (needs
 * READ_LOGS and the usage-stats app op, both granted over adb) and saves each report to
 * /sdcard/Coronamon/reports/<package>/ for Grok Build to read.
 */
final class CrashWatch {
    interface Listener {
        void crashed(String pkg, String kind, File report, String headline);
    }

    private static final Pattern PROCESS = Pattern.compile("(?m)^Process: ([\\w.:]+)");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^Package: ([\\w.]+)");
    private static final String[] TAGS = {"data_app_crash", "data_app_anr", "data_app_native_crash"};

    private final Context ctx;
    private final Registry registry;
    private final Listener listener;
    private final Handler main;
    private final Handler work;
    private final Map<String, Long> lastReact = new HashMap<>();

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String tag = i.getStringExtra(DropBoxManager.EXTRA_TAG);
            long time = i.getLongExtra(DropBoxManager.EXTRA_TIME, System.currentTimeMillis());
            if (tag != null && Arrays.asList(TAGS).contains(tag)) work.post(() -> read(tag, time));
        }
    };

    CrashWatch(Context ctx, Registry registry, Handler main, Listener listener) {
        this.ctx = ctx;
        this.registry = registry;
        this.main = main;
        this.listener = listener;
        HandlerThread t = new HandlerThread("crashwatch");
        t.start();
        work = new Handler(t.getLooper());
    }

    void start() {
        ctx.registerReceiver(rx, new IntentFilter(DropBoxManager.ACTION_DROPBOX_ENTRY_ADDED), Context.RECEIVER_EXPORTED);
    }

    void stop() {
        try {
            ctx.unregisterReceiver(rx);
        } catch (Exception ignored) {
        }
        work.getLooper().quitSafely();
    }

    private void read(String tag, long time) {
        DropBoxManager dbm = ctx.getSystemService(DropBoxManager.class);
        DropBoxManager.Entry e = null;
        try {
            e = dbm.getNextEntry(tag, time - 1);
            if (e == null) return;
            String text = e.getText(256 * 1024);
            if (text == null) return;
            String pkg = null;
            Matcher m = PACKAGE.matcher(text);
            if (m.find()) pkg = m.group(1);
            if (pkg == null) {
                m = PROCESS.matcher(text);
                if (m.find()) pkg = m.group(1).split(":")[0];
            }
            if (pkg == null || !registry.has(pkg) || Registry.SELF.equals(pkg)) return;
            String kind = tag.endsWith("anr") ? "anr" : tag.endsWith("native_crash") ? "native" : "crash";
            File report = save(pkg, kind, e.getTimeMillis(), text);
            String headline = headline(text, kind);
            Long last = lastReact.get(pkg);
            boolean react = last == null || time - last > 60_000;
            lastReact.put(pkg, time);
            if (react) {
                String p = pkg;
                main.post(() -> listener.crashed(p, kind, report, headline));
            }
        } catch (SecurityException se) {
            Log.w("dropbox needs READ_LOGS + PACKAGE_USAGE_STATS: " + se.getMessage());
        } catch (Exception ex) {
            Log.w("crashwatch: " + ex);
        } finally {
            if (e != null) e.close();
        }
    }

    private static File save(String pkg, String kind, long time, String text) {
        File f = new File(new File(Paths.REPORTS, pkg), Util.stamp(time) + "-" + kind + ".txt");
        Util.writeText(f, text);
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
        java.util.List<File> all = new java.util.ArrayList<>();
        for (File d : dirs) {
            File[] fs = d.listFiles((dd, name) -> name.endsWith(".txt"));
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
