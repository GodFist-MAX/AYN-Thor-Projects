package dev.grokbuild.coronamon;

import android.view.Display;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Screenshots of the screens and recent logs of the apps on them, for a problem report. Taken
 * through Shizuku (screencap and logcat run as the shell user) into a folder under
 * /sdcard/Coronamon/bugs/ that the report takes over when it is saved. Runs off the main thread.
 */
final class Capture {
    private static final Pattern PHYSICAL = Pattern.compile("Display (\\d+) \\(HWC display (\\d+)\\)");

    private Capture() {
    }

    /** skipDisplay: the screen the den is on, so the form itself isn't in the picture (-1 for none). */
    static JSONObject take(PetService s, int skipDisplay) {
        long now = System.currentTimeMillis();
        String id = "pending-" + Util.stamp(now);
        File dir = new File(Bugs.FILES, id);
        dir.mkdirs();
        clean(now);
        JSONObject screens = s.screensNow();
        JSONObject labels = new JSONObject();
        for (java.util.Iterator<String> it = screens.keys(); it.hasNext(); ) {
            String pkg = screens.optString(it.next());
            Util.put(labels, pkg, s.registry.label(pkg));
        }
        JSONObject out = Util.obj("id", id, "dir", dir.getPath(), "time", now, "screens", screens, "labels", labels);
        JSONArray shots = new JSONArray();
        JSONObject logs = new JSONObject();
        if (Shell.granted()) {
            try {
                Display bottom = Screens.bottom(s);
                List<String> phys = physicalIds();
                for (int i = 0; i < phys.size() && i < 2; i++) {
                    int logical = i == 0 ? Screens.TOP_ID : bottom == null ? -2 : bottom.getDisplayId();
                    if (logical == skipDisplay) continue;
                    File f = new File(dir, (i == 0 ? "top" : "bottom") + ".png");
                    Shell.run("screencap -p -d " + phys.get(i) + " > '" + f.getPath() + "'", 10_000);
                    if (f.length() > 0) shots.put(f.getPath());
                    else f.delete();
                }
                Set<String> pkgs = new LinkedHashSet<>();
                for (java.util.Iterator<String> it = screens.keys(); it.hasNext(); ) pkgs.add(screens.optString(it.next()));
                for (String pkg : pkgs) {
                    if (pkg.isEmpty() || Registry.SELF.equals(pkg)) continue;
                    File lf = log(pkg, dir);
                    if (lf != null) Util.put(logs, pkg, lf.getPath());
                }
            } catch (Exception e) {
                Log.w("capture: " + e);
            }
        }
        Util.put(out, "shots", shots);
        Util.put(out, "logs", logs);
        Util.put(out, "shizuku", Shell.granted());
        return out;
    }

    /** The app's recent logcat lines, or null if it isn't running. */
    static File log(String pkg, File dir) {
        try {
            String pids = Shell.run("pidof " + pkg, 4000).out.trim();
            if (!pids.matches("\\d+( \\d+)*")) return null;
            File f = new File(dir, pkg + "-log.txt");
            Shell.run("logcat -d -v threadtime -t 2000 --pid=" + pids.split(" ")[0] + " > '" + f.getPath() + "'", 10_000);
            return f.length() > 0 ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Physical display ids for screencap, top screen first (lower hardware composer number). */
    private static List<String> physicalIds() throws Exception {
        Map<Integer, String> byHwc = new TreeMap<>();
        Matcher m = PHYSICAL.matcher(Shell.run("dumpsys SurfaceFlinger --display-id", 6000).out);
        while (m.find()) byHwc.put(Integer.parseInt(m.group(2)), m.group(1));
        return new ArrayList<>(byHwc.values());
    }

    /** Captures for reports that were never sent, after a day. */
    private static void clean(long now) {
        File[] old = Bugs.FILES.listFiles((d, name) -> name.startsWith("pending-"));
        if (old == null) return;
        for (File d : old) {
            if (now - d.lastModified() < 24 * 3600_000L) continue;
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            d.delete();
        }
    }
}
