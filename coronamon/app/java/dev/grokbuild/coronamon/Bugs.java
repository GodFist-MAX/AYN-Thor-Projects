package dev.grokbuild.coronamon;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Problems found on the Thor: crashes, freezes and native crashes CrashWatch catches, problems the
 * Grok-built apps report themselves (BugsProvider), and reports the user files from the den.
 * Repeats of the same problem are one bug with a count. When the Thor is plugged into the Mac,
 * coro sync takes every bug whose rev is past its syncedRev, files GitHub issues, and acknowledges
 * each bug at the rev it took, with the issue number.
 */
final class Bugs {
    /** Screenshots and logs that go with the user's reports, one folder per report. */
    static final File FILES = new File(Paths.ROOT, "bugs");
    /** The latest rev, for the Mac's watcher to poll cheaply over adb. */
    private static final File REV = new File(Paths.ROOT, "bugs.rev");
    private static final int MAX = 200;

    static final class Result {
        final JSONObject bug;
        final boolean isNew, cameBack;

        Result(JSONObject bug, boolean isNew, boolean cameBack) {
            this.bug = bug;
            this.isNew = isNew;
            this.cameBack = cameBack;
        }
    }

    private final File file;
    private JSONObject bugs = new JSONObject();
    private long rev, lastSync;

    Bugs(Context ctx) {
        file = new File(ctx.getFilesDir(), "bugs.json");
        JSONObject o = Util.readJson(file);
        if (o != null) {
            JSONObject b = o.optJSONObject("bugs");
            if (b != null) bugs = b;
            rev = o.optLong("rev");
            lastSync = o.optLong("lastSync");
        }
    }

    // ---- recording ----

    synchronized Result crash(String pkg, String app, String kind, String text, long time, File report, File log,
                              JSONObject context, boolean external) {
        Sig s = Sig.of(pkg, kind, text);
        if (s.driver != null) {
            if (context == null) context = new JSONObject();
            Util.put(context, "driver", s.driver);
        }
        return record(pkg, app, kind, s.title, s.sig, s.version, Sig.excerpt(kind, text), time, report, log, context, external);
    }

    synchronized Result appReport(String pkg, String app, String kind, String title, String detail, String version,
                                  JSONObject context) {
        return record(pkg, app, kind, title, kind + "|" + Sig.norm(title), version, detail, System.currentTimeMillis(),
                null, null, context, false);
    }

    /** A report the user wrote. Each one is its own bug; the capture's files move into its folder. */
    synchronized JSONObject userReport(String pkg, String app, String version, String category, String text,
                                       JSONObject capture, boolean github) {
        long now = System.currentTimeMillis();
        String id = "r" + Util.stamp(now).replace("-", "");
        while (bugs.has(id)) id += "x";
        String first = text == null ? "" : text.trim().split("\n")[0].trim();
        String title = first.isEmpty() ? category : clip(first, 90);
        JSONArray shots = new JSONArray(), logs = new JSONArray();
        JSONObject context = new JSONObject();
        if (capture != null) {
            File from = new File(capture.optString("dir"));
            File to = new File(FILES, id);
            File dir = from.isDirectory() && from.renameTo(to) ? to : from;
            JSONArray s = capture.optJSONArray("shots");
            for (int i = 0; s != null && i < s.length(); i++) {
                File f = new File(dir, new File(s.optString(i)).getName());
                if (f.isFile()) shots.put(f.getPath());
            }
            JSONObject l = capture.optJSONObject("logs");
            String log = l == null ? null : l.optString(pkg, null);
            if (log != null) {
                File f = new File(dir, new File(log).getName());
                if (f.isFile()) logs.put(f.getPath());
            }
            JSONObject screens = capture.optJSONObject("screens");
            if (screens != null) Util.put(context, "screens", screens);
        }
        JSONObject b = Util.obj("id", id, "pkg", pkg, "app", app, "kind", "report", "title", title, "sig", id,
                "category", category, "note", text == null ? "" : clip(text.trim(), 4000), "github", github,
                "first", now, "last", now, "count", 1, "versions", new JSONArray(), "reports", new JSONArray(),
                "shots", shots, "logs", logs, "context", context, "state", "open", "rev", 0, "syncedRev", 0,
                "external", false);
        if (version != null && !version.isEmpty()) b.optJSONArray("versions").put(version);
        Util.put(bugs, id, b);
        bump(b);
        return copy(b);
    }

    private Result record(String pkg, String app, String kind, String title, String sig, String version, String text,
                          long time, File report, File log, JSONObject context, boolean external) {
        String id = "b" + sha1(pkg + "|" + sig).substring(0, 8);
        JSONObject b = bugs.optJSONObject(id);
        boolean isNew = b == null, back = false;
        if (isNew) {
            b = Util.obj("id", id, "pkg", pkg, "kind", kind, "title", clip(title, 140), "sig", sig, "first", time,
                    "last", time, "count", 0, "versions", new JSONArray(), "reports", new JSONArray(),
                    "logs", new JSONArray(), "state", "open", "rev", 0, "syncedRev", 0, "external", external);
            Util.put(bugs, id, b);
        } else if (report != null && has(b.optJSONArray("reports"), report.getPath())) {
            return new Result(copy(b), false, false);
        }
        Util.put(b, "app", app);
        Util.put(b, "count", b.optInt("count") + 1);
        if (time > b.optLong("last")) Util.put(b, "last", time);
        if (time < b.optLong("first")) Util.put(b, "first", time);
        if (version != null && !version.isEmpty()) addLast(b, "versions", version, 6);
        if (report != null) addLast(b, "reports", report.getPath(), 5);
        if (log != null) addLast(b, "logs", log.getPath(), 5);
        if (text != null && !text.isEmpty()) Util.put(b, "text", clip(text, 5000));
        if (context != null && context.length() > 0) Util.put(b, "context", context);
        if ("fixed".equals(b.optString("state")) && version != null
                && Util.compareVersions(version, b.optString("fixedIn")) >= 0) {
            Util.put(b, "state", "open");
            Util.put(b, "reopened", b.optInt("reopened") + 1);
            Util.put(b, "backIn", version);
            back = true;
        }
        bump(b);
        return new Result(copy(b), isNew, back);
    }

    // ---- what the Mac and the den ask for ----

    /** filter: all, open, or unsynced (changed since the Mac last took it). Newest first. */
    synchronized JSONArray list(String filter, int max) {
        List<JSONObject> out = new ArrayList<>();
        for (Iterator<String> it = bugs.keys(); it.hasNext(); ) {
            JSONObject b = bugs.optJSONObject(it.next());
            if (b == null) continue;
            if ("unsynced".equals(filter) && b.optLong("rev") <= b.optLong("syncedRev")) continue;
            if ("open".equals(filter) && !"open".equals(b.optString("state"))) continue;
            out.add(b);
        }
        out.sort((x, y) -> {
            int sx = "open".equals(x.optString("state")) ? 0 : 1, sy = "open".equals(y.optString("state")) ? 0 : 1;
            if (!"unsynced".equals(filter) && sx != sy) return sx - sy;
            return Long.compare(y.optLong("last"), x.optLong("last"));
        });
        JSONArray a = new JSONArray();
        for (int i = 0; i < Math.min(max, out.size()); i++) a.put(copy(out.get(i)));
        return a;
    }

    synchronized JSONObject get(String id) {
        JSONObject b = id == null ? null : bugs.optJSONObject(id);
        return b == null ? null : copy(b);
    }

    synchronized long rev() {
        return rev;
    }

    synchronized long lastSync() {
        return lastSync;
    }

    synchronized int waiting() {
        int n = 0;
        for (Iterator<String> it = bugs.keys(); it.hasNext(); ) {
            JSONObject b = bugs.optJSONObject(it.next());
            if (b != null && b.optLong("rev") > b.optLong("syncedRev")) n++;
        }
        return n;
    }

    /**
     * The Mac took these bugs: [{id, rev, issue, url}]. Returns the ones it took for the first time.
     * Acks don't change the rev, so they never make a bug look unsynced again.
     */
    synchronized JSONArray ack(JSONArray acks) {
        JSONArray firstTime = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = 0; acks != null && i < acks.length(); i++) {
            JSONObject a = acks.optJSONObject(i);
            JSONObject b = a == null ? null : bugs.optJSONObject(a.optString("id"));
            if (b == null) continue;
            long r = Math.min(a.optLong("rev"), b.optLong("rev"));
            boolean first = b.optLong("syncedRev") == 0 && r > 0;
            if (r > b.optLong("syncedRev")) Util.put(b, "syncedRev", r);
            int issue = a.optInt("issue");
            if (issue > 0) {
                Util.put(b, "issue", issue);
                Util.put(b, "url", a.optString("url"));
            }
            Util.put(b, "grokAt", now);
            if (first) firstTime.put(copy(b));
        }
        lastSync = now;
        save();
        return firstTime;
    }

    /** open, fixed or ignored. A fixed bug that happens again on fixedIn or later opens again. */
    synchronized JSONObject setState(String id, String state, String version) {
        JSONObject b = id == null ? null : bugs.optJSONObject(id);
        if (b == null || state == null || !state.matches("open|fixed|ignored")) return null;
        Util.put(b, "state", state);
        if ("fixed".equals(state)) Util.put(b, "fixedIn", version == null ? "" : version);
        bump(b);
        return copy(b);
    }

    synchronized boolean forget(String id) {
        if (id == null || bugs.remove(id) == null) return false;
        File dir = new File(FILES, id);
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        dir.delete();
        rev++;
        save();
        return true;
    }

    /** Everything the den shows when a bug is opened. */
    synchronized String detail(String id) {
        JSONObject b = bugs.optJSONObject(id);
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(b.optString("app")).append(" (").append(b.optString("pkg")).append(")\n");
        JSONArray v = b.optJSONArray("versions");
        if (v != null && v.length() > 0) sb.append("Versions: ").append(join(v)).append('\n');
        sb.append("Seen ").append(b.optInt("count")).append(b.optInt("count") == 1 ? " time" : " times")
                .append(", first ").append(Util.clock(b.optLong("first"))).append(", last ").append(Util.clock(b.optLong("last"))).append('\n');
        if (b.optInt("issue") > 0) sb.append("GitHub issue #").append(b.optInt("issue")).append('\n');
        JSONObject ctx = b.optJSONObject("context");
        if (ctx != null && ctx.optJSONObject("screens") != null) sb.append("On screen: ").append(ctx.optJSONObject("screens")).append('\n');
        if (ctx != null && !ctx.optString("driver").isEmpty()) sb.append("Vulkan driver: ").append(ctx.optString("driver")).append('\n');
        if (!b.optString("note").isEmpty()) sb.append("\nWhat happened:\n").append(b.optString("note")).append('\n');
        JSONArray shots = b.optJSONArray("shots");
        if (shots != null && shots.length() > 0) sb.append("\n").append(shots.length()).append(" screenshot(s) saved for Grok.\n");
        JSONArray reports = b.optJSONArray("reports");
        String text = null;
        if (reports != null && reports.length() > 0) text = Util.readText(new File(reports.optString(reports.length() - 1)));
        if (text == null) text = b.optString("text");
        if (!text.isEmpty()) sb.append("\n").append(clip(text, 60000));
        return sb.toString();
    }

    // ---- storage ----

    private void bump(JSONObject b) {
        Util.put(b, "rev", ++rev);
        save();
    }

    private void save() {
        trim();
        Util.writeText(file, Util.obj("bugs", bugs, "rev", rev, "lastSync", lastSync).toString());
        Util.writeText(REV, String.valueOf(rev));
    }

    /** Drops the oldest closed bugs first once there are too many. */
    private void trim() {
        while (bugs.length() > MAX) {
            String drop = null;
            long oldest = Long.MAX_VALUE;
            boolean closedFound = false;
            for (Iterator<String> it = bugs.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject b = bugs.optJSONObject(k);
                boolean closed = b != null && !"open".equals(b.optString("state"));
                long t = b == null ? 0 : b.optLong("last");
                if ((closed && !closedFound) || (closed == closedFound && t < oldest)) {
                    drop = k;
                    oldest = t;
                    closedFound = closed;
                }
            }
            if (drop == null) return;
            bugs.remove(drop);
        }
    }

    private static JSONObject copy(JSONObject b) {
        return Util.parse(b.toString());
    }

    private static boolean has(JSONArray a, String v) {
        for (int i = 0; a != null && i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    private static void addLast(JSONObject b, String key, String v, int keep) {
        JSONArray a = b.optJSONArray(key);
        JSONArray out = new JSONArray();
        for (int i = 0; a != null && i < a.length(); i++) if (!v.equals(a.optString(i))) out.put(a.optString(i));
        out.put(v);
        JSONArray kept = new JSONArray();
        for (int i = Math.max(0, out.length() - keep); i < out.length(); i++) kept.put(out.optString(i));
        Util.put(b, key, kept);
    }

    private static String join(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) sb.append(i == 0 ? "" : ", ").append(a.optString(i));
        return sb.toString();
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static String sha1(String s) {
        try {
            return Util.hex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode()) + "00000000";
        }
    }

    /** What a crash report says: a stable signature for spotting repeats, a short title, the version. */
    static final class Sig {
        String title, sig, version, driver;

        private static final Pattern EXC = Pattern.compile(
                "^(?:Caused by: )?((?:[A-Za-z_$][\\w$]*\\.)+[\\w$]*(?:Exception|Error|Throwable))(?::\\s*(.*))?$");
        private static final Pattern SIGNAL = Pattern.compile("^signal \\d+ \\((SIG\\w+)\\)");
        private static final Pattern FRAME = Pattern.compile(
                "^#\\d+ pc [0-9a-f]+\\s+(\\S+)(?:\\s+\\(offset 0x[0-9a-f]+\\))?(?:\\s+\\(([^)+]+)(?:\\+\\d+)?\\))?");
        private static final Pattern HEADER = Pattern.compile("^(Process|Package|Foreground|Subject):");

        static Sig of(String pkg, String kind, String text) {
            Sig s = new Sig();
            Matcher v = Pattern.compile("(?m)^Package: " + Pattern.quote(pkg) + " v\\d+ \\(([^)]+)\\)").matcher(text);
            if (v.find()) s.version = v.group(1);
            if ("anr".equals(kind)) s.anr(pkg, text);
            else if ("native".equals(kind)) s.nat(text);
            else s.java(pkg, text);
            return s;
        }

        private void java(String pkg, String text) {
            String cls = null, firstCls = null;
            List<String> frames = new ArrayList<>(), firstFrames = null;
            for (String line : text.split("\n")) {
                String t = line.trim();
                Matcher m = EXC.matcher(t);
                if (m.matches()) {
                    if (firstCls == null) firstCls = m.group(1);
                    else if (firstFrames == null) firstFrames = new ArrayList<>(frames);
                    cls = m.group(1);
                    frames = new ArrayList<>();
                } else if (cls != null && t.startsWith("at ")) {
                    frames.add(t.substring(3));
                }
            }
            if (cls != null && frames.isEmpty() && firstFrames != null) {
                cls = firstCls;
                frames = firstFrames;
            }
            List<String> mine = ours(pkg, frames);
            List<String> use = mine.isEmpty() ? frames : mine;
            StringBuilder sb = new StringBuilder("crash|").append(cls);
            for (int i = 0; i < Math.min(3, use.size()); i++) sb.append('|').append(method(use.get(i)));
            sig = sb.toString();
            title = "Crash: " + (cls == null ? "app stopped" : simple(cls)) + (mine.isEmpty() ? "" : " in " + shortMethod(mine.get(0)));
        }

        private void anr(String pkg, String text) {
            String subject = "Not responding";
            List<String> frames = new ArrayList<>();
            boolean inMain = false;
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (t.startsWith("Subject:") && "Not responding".equals(subject)) {
                    subject = t.substring(8).replaceAll("\\s*[({].*", "").trim();
                    if (subject.isEmpty()) subject = "Not responding";
                } else if (t.startsWith("\"main\"")) {
                    inMain = frames.isEmpty();
                } else if (inMain) {
                    if (t.isEmpty()) inMain = false;
                    else if (t.startsWith("at ")) frames.add(t.substring(3));
                }
            }
            List<String> mine = ours(pkg, frames);
            sig = "anr|" + norm(subject) + "|" + (mine.isEmpty() ? "" : method(mine.get(0)));
            title = "Froze: " + subject + (mine.isEmpty() ? "" : " in " + shortMethod(mine.get(0)));
        }

        private void nat(String text) {
            String signal = "signal", abort = null;
            List<String> frames = new ArrayList<>();
            String firstLib = null;
            for (String line : text.split("\n")) {
                String t = line.trim();
                Matcher m = SIGNAL.matcher(t);
                if (m.find()) signal = m.group(1);
                else if (t.startsWith("Abort message: '")) {
                    abort = t.substring(16).replaceAll("'$", "").replaceAll("^\\[FATAL:[^\\]]*\\]\\s*", "")
                            .replaceAll("\\s*\\(\\d+\\)", "").replaceAll("\\s+", " ").trim();
                } else {
                    Matcher f = FRAME.matcher(t);
                    if (f.find()) {
                        String path = f.group(1);
                        String lib = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('!')) + 1);
                        if (firstLib == null) firstLib = lib;
                        if (frames.size() < 3) frames.add(lib + ":" + (f.group(2) == null ? "" : f.group(2).trim()));
                        if (driver == null && (lib.contains("vulkan") || lib.contains("freedreno"))) {
                            File p = new File(path.replaceAll("!.*", ""));
                            String parent = p.getParentFile() == null ? "" : p.getParentFile().getName();
                            driver = path.startsWith("/vendor/") || path.startsWith("/system/") ? "system driver (" + lib + ")" : parent + "/" + lib;
                        }
                    }
                }
            }
            sig = "native|" + signal + "|" + norm(abort == null ? "" : abort) + "|" + String.join("|", frames);
            title = "Native crash (" + signal + ")" + (abort != null && !abort.isEmpty() ? ": " + clip(abort, 100)
                    : firstLib != null ? " in " + firstLib : "");
        }

        /** The report with the noise taken out: the header, then the exception or backtrace. */
        static String excerpt(String kind, String text) {
            StringBuilder head = new StringBuilder(), body = new StringBuilder();
            boolean started = false, inMain = false;
            int frames = 0;
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (HEADER.matcher(t).find()) {
                    head.append(t).append('\n');
                    continue;
                }
                if ("native".equals(kind)) {
                    if (t.startsWith("signal ") || t.startsWith("Abort message:") || t.startsWith("Cmdline:")) body.append(t).append('\n');
                    else if (FRAME.matcher(t).find() && frames++ < 40) body.append(t.replaceAll("\\s*\\(BuildId: [0-9a-f]+\\)", "")).append('\n');
                } else if ("anr".equals(kind)) {
                    if (t.startsWith("\"main\"")) inMain = true;
                    else if (inMain && t.isEmpty()) inMain = false;
                    if (inMain && frames++ < 50) body.append(t).append('\n');
                } else {
                    if (!started && EXC.matcher(t).matches()) started = true;
                    if (started && frames++ < 120) body.append(t).append('\n');
                }
            }
            return clip(head.append(head.length() > 0 ? "\n" : "").append(body).toString().trim(), 5000);
        }

        /** Frames from the app's own code, without line numbers. */
        private static List<String> ours(String pkg, List<String> frames) {
            List<String> out = new ArrayList<>();
            for (String f : frames) if (f.startsWith(pkg + ".")) out.add(f);
            return out;
        }

        private static String method(String frame) {
            int p = frame.indexOf('(');
            return p > 0 ? frame.substring(0, p) : frame;
        }

        private static String shortMethod(String frame) {
            String m = method(frame);
            String[] parts = m.split("\\.");
            return parts.length >= 2 ? parts[parts.length - 2] + "." + parts[parts.length - 1] : m;
        }

        private static String simple(String cls) {
            return cls.substring(cls.lastIndexOf('.') + 1);
        }

        /** Numbers, addresses and spacing taken out, so repeats match. */
        static String norm(String s) {
            return clip(s.replaceAll("0x[0-9a-fA-F]+", "X").replaceAll("\\d+", "N").replaceAll("\\s+", " ").trim(), 200);
        }
    }
}
