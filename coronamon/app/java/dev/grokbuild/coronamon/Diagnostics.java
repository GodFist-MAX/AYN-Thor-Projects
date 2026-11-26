package dev.grokbuild.coronamon;

import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.DropBoxManager;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The Den's Run diagnostics button: Coronamon checks the Thor's side (permissions, Shizuku, storage, the internet,
 * update checks, the link to the Mac), asks the Mac's watcher to check its side, and saves both to
 * Coronamon/diagnostics/. coro diagnostics runs the same Thor checks over the bridge.
 */
final class Diagnostics {
    static final File DIR = new File(Paths.ROOT, "diagnostics");
    private static final int KEEP = 10;
    private static final String[][] PERMISSIONS = {{"overlay", "drawing over apps"}, {"usageStats", "usage access"},
            {"dump", "DUMP"}, {"readLogs", "READ_LOGS"}, {"notifications", "notifications"},
            {"installs", "installing apps"}, {"allFiles", "all-files access"}};

    private final PetService s;
    private boolean running;
    private long started, thorAt, macAt;
    private JSONArray thor, mac;
    /** none, waiting, working, done, skipped (not plugged into the Mac) or expired (the Mac never answered). */
    private String macState = "none";
    private String macId, macMsg, saved;

    Diagnostics(PetService s) {
        this.s = s;
    }

    /** Starts a run, unless one is going. */
    synchronized JSONObject start() {
        if (!running) {
            running = true;
            started = System.currentTimeMillis();
            thor = mac = null;
            thorAt = macAt = 0;
            macMsg = saved = null;
            if (s.mac.reachable()) {
                macId = s.mac.request("diagnostics", null).optString("id");
                macState = "waiting";
            } else {
                macId = null;
                macState = "skipped";
            }
            new Thread(() -> {
                JSONArray t;
                try {
                    t = thorChecks();
                } catch (Exception e) {
                    t = new JSONArray().put(finding("Diagnostics", "bad", "The Thor's checks stopped: " + e, null));
                }
                thorDone(t);
            }, "diagnostics").start();
        }
        return snapshot();
    }

    private synchronized void thorDone(JSONArray t) {
        thor = t;
        thorAt = System.currentTimeMillis();
        running = false;
        finish();
    }

    /** The Mac's answer to its half. */
    synchronized void macDone(String id, JSONObject result) {
        if (macId == null || !macId.equals(id)) return;
        mac = result.optJSONArray("checks");
        if (mac == null) {
            mac = new JSONArray().put(finding("Mac", "bad", result.optString("msg", "The Mac didn't send its checks."), null));
        }
        macAt = System.currentTimeMillis();
        macState = "done";
        finish();
    }

    synchronized JSONObject snapshot() {
        if ("waiting".equals(macState) || "working".equals(macState)) {
            JSONObject r = s.mac.get(macId);
            String st = r == null ? "expired" : r.optString("state");
            if ("waiting".equals(st) || "working".equals(st)) {
                macState = st;
            } else if ("expired".equals(st)) {
                macState = "expired";
                macMsg = r == null ? "The Mac didn't answer." : r.optString("msg");
                finish();
            }
        }
        return Util.obj("running", running, "started", started, "thor", copy(thor), "thorAt", thorAt, "mac", copy(mac),
                "macAt", macAt, "macState", macState, "macMsg", macMsg, "file", saved);
    }

    /** Once both halves are in, or the Mac's can't come, saves the report and notes it in Activity. */
    private void finish() {
        if (running || saved != null || thor == null) return;
        if ("waiting".equals(macState) || "working".equals(macState)) return;
        List<String> look = new ArrayList<>();
        boolean bad = false;
        for (JSONArray list : new JSONArray[]{thor, mac}) {
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject c = list.optJSONObject(i);
                String st = c.optString("state");
                if ("warn".equals(st) || "bad".equals(st)) look.add(c.optString("name"));
                bad |= "bad".equals(st);
            }
        }
        String summary = look.isEmpty() ? "Everything looks good." : look.size() + (look.size() == 1 ? " thing" : " things")
                + " to look at: " + String.join(", ", look) + ".";
        if (mac == null) summary += " Only the Thor was checked" + ("skipped".equals(macState) ? " (not plugged into the Mac)." : ": " + macMsg);
        StringBuilder b = new StringBuilder("Coronamon diagnostics, ").append(Util.clock(started)).append("\n\nOn the Thor\n");
        lines(b, thor);
        b.append("\nOn the Mac\n");
        if (mac != null) lines(b, mac);
        else b.append("  ").append("skipped".equals(macState) ? "Not plugged into the Mac." : macMsg).append('\n');
        b.append('\n').append(summary).append('\n');
        File f = new File(DIR, "diagnostics-" + Util.stamp(started) + ".txt");
        saved = Util.writeText(f, b.toString()) ? f.getPath() : "";
        prune();
        s.history.add("diagnostics", null, null, !bad, summary, null);
    }

    private static void lines(StringBuilder b, JSONArray list) {
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i);
            b.append(String.format(Locale.US, "  %-5s %-18s %s%n", c.optString("state"), c.optString("name"), c.optString("detail")));
            if (!c.isNull("fix")) b.append(String.format(Locale.US, "        %-18s fix: %s%n", "", c.optString("fix")));
        }
    }

    private static void prune() {
        String[] names = DIR.list((d, n) -> n.startsWith("diagnostics-") && n.endsWith(".txt"));
        if (names == null || names.length <= KEEP) return;
        Arrays.sort(names);
        for (int i = 0; i < names.length - KEEP; i++) new File(DIR, names[i]).delete();
    }

    /** coro diagnostics: the Thor's checks, run on the bridge's thread. */
    static JSONObject forBridge(Context ctx) {
        PetService s = PetService.instance;
        if (s == null) {
            PetService.start(ctx);
            return Util.obj("ok", false, "error", "Coronamon is waking up; try again in a moment");
        }
        try {
            return Util.obj("ok", true, "checks", s.diag.thorChecks());
        } catch (Exception e) {
            return Util.obj("ok", false, "error", String.valueOf(e));
        }
    }

    /** The Thor's side. Not on the main thread: it waits for the network. */
    JSONArray thorChecks() throws Exception {
        JSONObject st = Bridge.onMain(() -> {
            JSONObject o = s.status();
            JSONArray apps = new JSONArray();
            for (String p : s.registry.packages()) {
                JSONObject e = s.registry.entry(p);
                if (e != null && !e.optString("github").isEmpty() && !"off".equals(e.optString("updateMode"))) {
                    apps.put(Util.parse(e.toString()));
                }
            }
            Util.put(o, "githubApps", apps);
            return o;
        });
        JSONArray out = new JSONArray();
        long now = System.currentTimeMillis();

        out.put(finding("Coronamon", "good", "Version " + st.optString("version")
                + (st.optBoolean("visible") ? ", out on the " + st.optString("screen") + " screen." : ", resting in the den."), null));

        JSONObject p = st.optJSONObject("permissions");
        List<String> missing = new ArrayList<>();
        for (String[] k : PERMISSIONS) if (p == null || !p.optBoolean(k[0])) missing.add(k[1]);
        out.put(missing.isEmpty() ? finding("Permissions", "good", "All " + PERMISSIONS.length + " granted.", null)
                : finding("Permissions", "bad", "Missing " + String.join(", ", missing) + ".", "Plug into the Mac and run coro setup."));

        String shizuku = st.optString("shizuku");
        if ("granted".equals(shizuku)) {
            out.put(finding("Shizuku", "good", "Running and trusts Coronamon, so installs need no taps.", null));
        } else if ("running".equals(shizuku)) {
            out.put(finding("Shizuku", "warn", "Running, but hasn't said yes to Coronamon.", "Settings tab, Connect Shizuku."));
        } else if (installed("moe.shizuku.privileged.api")) {
            out.put(finding("Shizuku", "warn", "Not running (it stops when the Thor restarts).",
                    "Plug into the Mac and run coro setup, or start it in the Shizuku app."));
        } else {
            out.put(finding("Shizuku", "info", "Not installed, so installs ask you to confirm.", null));
        }

        out.put(st.optBoolean("foregroundWorking")
                ? finding("App watching", "good", "Sees which app is on each screen.", null)
                : finding("App watching", "bad", "Can't see which app is in front, so he won't move out of games.",
                "Plug into the Mac and run coro setup."));

        boolean crashLog;
        try {
            DropBoxManager.Entry e = s.getSystemService(DropBoxManager.class).getNextEntry("data_app_crash", now);
            if (e != null) e.close();
            crashLog = true;
        } catch (SecurityException e) {
            crashLog = false;
        }
        out.put(crashLog ? finding("Crash watch", "good", "Can read Android's crash log.", null)
                : finding("Crash watch", "bad", "Can't read Android's crash log, so crashes go unnoticed.", "Plug into the Mac and run coro setup."));

        out.put(s.getSystemService(NotificationManager.class).areNotificationsEnabled()
                ? finding("Notifications", "good", "On.", null)
                : finding("Notifications", "warn", "Off, so updates, crashes and Grok Build results don't show up.",
                "Android Settings, Apps, Coronamon, Notifications."));

        out.put(s.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(s.getPackageName())
                ? finding("Battery", "good", "Android lets him keep running in the background.", null)
                : finding("Battery", "warn", "Android may pause him in the background.", "Plug into the Mac and run coro setup."));

        JSONArray stages = st.optJSONArray("stages");
        int screens = stages == null ? 0 : stages.length();
        out.put(screens >= 2 ? finding("Screens", "good", "Both screens found.", null)
                : finding("Screens", "warn", "Only " + screens + " screen found.", null));

        File probe = new File(Paths.ROOT, ".diagnostics-probe");
        boolean writable = Util.writeText(probe, "ok") && probe.delete();
        long free = Paths.ROOT.getUsableSpace();
        if (!writable) {
            out.put(finding("Storage", "bad", "Can't write to the Coronamon folder.", "Plug into the Mac and run coro setup."));
        } else if (free < 2L << 30) {
            out.put(finding("Storage", "warn", "Only " + Util.human(free) + " free.", "Free up space on the Thor."));
        } else {
            out.put(finding("Storage", "good", "Coronamon folder OK; " + Util.human(free) + " free.", null));
        }

        String net = null;
        try {
            HttpURLConnection c = Util.open("https://api.github.com/", 6000);
            try {
                c.getResponseCode();
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            net = e.getClass().getSimpleName();
        }
        out.put(net == null ? finding("Internet", "good", "GitHub answers, so update checks work.", null)
                : finding("Internet", "warn", "Can't reach GitHub (" + net + "), so update checks wait until the Thor is online.",
                "Check the Thor's Wi-Fi."));

        JSONArray apps = st.optJSONArray("githubApps");
        int n = apps == null ? 0 : apps.length();
        long newest = 0;
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            JSONObject a = apps.optJSONObject(i);
            newest = Math.max(newest, a.optLong("lastCheck"));
            String err = a.optString("lastCheckError");
            if (!err.isEmpty()) failed.add(a.optString("label", a.optString("pkg")) + " (" + clip(err, 60) + ")");
        }
        if (n == 0) {
            out.put(finding("Update checks", "info", "No apps with GitHub releases to check.", null));
        } else if (!failed.isEmpty()) {
            out.put(finding("Update checks", "warn", failed.size() + " of " + n + " failed last time: " + String.join(", ", failed) + ".",
                    "Apps tab, Check all for updates."));
        } else if (now - newest > 26 * 3600_000L) {
            out.put(finding("Update checks", "warn", "Last checked " + (newest == 0 ? "never" : ago(newest)) + "; he checks every 12 hours.",
                    "Apps tab, Check all for updates."));
        } else {
            out.put(finding("Update checks", "good", "All " + n + " apps checked " + ago(newest) + ".", null));
        }

        JSONObject bugs = st.optJSONObject("bugs");
        int waiting = bugs == null ? 0 : bugs.optInt("waiting");
        long lastSync = bugs == null ? 0 : bugs.optLong("lastSync");
        out.put(waiting == 0 ? finding("Bugs", "good", "Grok has every bug" + (lastSync > 0 ? " (last sent " + ago(lastSync) + ")." : "."), null)
                : finding("Bugs", "info", waiting + (waiting == 1 ? " bug is" : " bugs are")
                + " waiting for Grok; they go over when the Thor is plugged into the Mac.", null));

        JSONObject usb = s.mac.usb();
        long heard = s.mac.statusAt();
        boolean fresh = heard > 0 && now - heard < 4 * 60_000;
        String lastHeard = heard > 0 ? "; last heard from the Mac " + ago(heard) : "";
        if (usb.optBoolean("known") && !usb.optBoolean("connected")) {
            out.put(finding("Link to the Mac", "info", "Not plugged in" + lastHeard + ".", null));
        } else if (usb.optBoolean("known") && !usb.optBoolean("adb")) {
            out.put(finding("Link to the Mac", "warn", "Plugged in, but USB debugging is off, so the Mac can't reach Coronamon.",
                    "Developer options, USB debugging."));
        } else if (fresh) {
            out.put(finding("Link to the Mac", "good", "Plugged in; the Mac's watcher checked in " + ago(heard) + ".", null));
        } else {
            out.put(finding("Link to the Mac", "warn", "Plugged in, but the Mac's watcher hasn't checked in"
                    + (heard > 0 ? " since " + Util.clock(heard) : " yet") + ".", "On the Mac, run coro watch --install."));
        }

        JSONObject g = st.optJSONObject("grok");
        String state = g == null ? "" : g.optString("state");
        String msg = g == null || g.isNull("msg") ? "" : g.optString("msg");
        long since = g == null ? 0 : g.optLong("since");
        switch (state) {
            case "connected":
                out.put(finding("Grok Build", "good", "Ready on the Mac as of " + ago(since) + ".", null));
                break;
            case "fixing":
                out.put(finding("Grok Build", "info", "Fixing things now" + (msg.isEmpty() ? "." : ": " + msg), null));
                break;
            case "away":
                out.put(finding("Grok Build", "warn", (msg.isEmpty() ? "Not ready on the Mac" : msg) + " (" + ago(since) + ").",
                        "Open Grok Build on the Mac."));
                break;
            default:
                out.put(finding("Grok Build", "info", "Hasn't heard from the Mac since Coronamon started.", null));
        }
        out.put(s.mind.check());
        out.put(s.voice.check());
        return out;
    }

    static JSONObject finding(String name, String state, String detail, String fix) {
        return Util.obj("name", name, "state", state, "detail", detail, "fix", fix);
    }

    private boolean installed(String pkg) {
        try {
            s.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60_000;
        if (m < 1) return "just now";
        if (m < 60) return m + (m == 1 ? " minute ago" : " minutes ago");
        long h = m / 60;
        if (h < 48) return h + (h == 1 ? " hour ago" : " hours ago");
        return h / 24 + " days ago";
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static JSONArray copy(JSONArray a) {
        return a == null ? null : Util.parseArray(a.toString());
    }
}
