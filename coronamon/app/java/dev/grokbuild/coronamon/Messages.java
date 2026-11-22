package dev.grokbuild.coronamon;

import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What Coronamon has to tell you, for the house button and the messages panel: updates he found,
 * crashes he saved for Grok, installs and Grok Build runs that went wrong (or right), and anything
 * that stops him doing his job, like Shizuku not running or a missing permission. Which ones you've
 * read or dismissed is kept in files/messages.json.
 */
final class Messages {
    static final class Msg {
        final String id, kind, title, text;
        final long t;
        String pkg;
        /** Offers an Update button. */
        boolean update;

        Msg(String id, String kind, String title, String text, long t) {
            this.id = id;
            this.kind = kind;
            this.title = title;
            this.text = text == null ? "" : text;
            this.t = t;
        }
    }

    /** Kinds, which set the colour and the order: problems he found first. */
    static final String LIVE = "live", UPDATE = "update", BUG = "bug", WARN = "warn", INFO = "info", GOOD = "good";

    private static final long NOTICE_AGE = 3 * 86_400_000L;
    private static final int KEEP_IDS = 300;

    private final PetService svc;
    private final File file;
    private final Set<String> seen = new LinkedHashSet<>(), dismissed = new LinkedHashSet<>();
    private List<Msg> cache = new ArrayList<>();
    private long cacheAt;

    Messages(PetService svc) {
        this.svc = svc;
        file = new File(svc.getFilesDir(), "messages.json");
        JSONObject o = Util.readJson(file);
        if (o != null) {
            load(o.optJSONArray("seen"), seen);
            load(o.optJSONArray("dismissed"), dismissed);
        }
    }

    private static void load(JSONArray a, Set<String> into) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) into.add(a.optString(i));
    }

    List<Msg> list() {
        if (System.currentTimeMillis() - cacheAt > 20_000) refresh();
        return cache;
    }

    boolean unread(Msg m) {
        return !seen.contains(m.id);
    }

    int unread() {
        int n = 0;
        for (Msg m : list()) if (unread(m)) n++;
        return n;
    }

    /** Something happened that may change the list: rebuild it next time. */
    void changed() {
        cacheAt = 0;
    }

    void markSeen() {
        boolean any = false;
        for (Msg m : list()) any |= seen.add(m.id);
        if (any) save();
    }

    void dismiss(String id) {
        dismissed.add(id);
        seen.add(id);
        cacheAt = 0;
        save();
    }

    JSONArray toJson() {
        JSONArray a = new JSONArray();
        for (Msg m : list()) {
            a.put(Util.obj("id", m.id, "kind", m.kind, "title", m.title, "text", m.text, "t", m.t, "pkg", m.pkg,
                    "update", m.update, "unread", unread(m)));
        }
        return a;
    }

    private void save() {
        Util.writeText(file, Util.obj("seen", tail(seen), "dismissed", tail(dismissed)).toString());
    }

    private static JSONArray tail(Set<String> ids) {
        while (ids.size() > KEEP_IDS) {
            Iterator<String> it = ids.iterator();
            it.next();
            it.remove();
        }
        JSONArray a = new JSONArray();
        for (String s : ids) a.put(s);
        return a;
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        List<Msg> live = new ArrayList<>(), rest = new ArrayList<>();
        problems(live, now);
        updates(rest);
        bugs(rest);
        notices(rest, now);
        rest.sort((a, b) -> Long.compare(b.t, a.t));
        List<Msg> out = new ArrayList<>();
        for (Msg m : live) if (!dismissed.contains(m.id)) out.add(m);
        for (Msg m : rest) if (!dismissed.contains(m.id) && out.size() < 30) out.add(m);
        cache = out;
        cacheAt = now;
    }

    /** What's stopping him from looking after your apps right now. */
    private void problems(List<Msg> out, long now) {
        long day = now / 86_400_000L;
        String sh = Shell.state();
        if (installed("moe.shizuku.privileged.api")) {
            if ("stopped".equals(sh)) {
                out.add(new Msg("live:shizuku:" + day, LIVE, "Shizuku isn't running",
                        "I can't install updates by myself until it's started. Open the Shizuku app, or plug into the Mac and run coro setup.", now));
            } else if ("running".equals(sh)) {
                out.add(new Msg("live:shizuku-ok:" + day, LIVE, "Shizuku is waiting for your OK",
                        "Open my den, go to Settings and tap Connect Shizuku, then say yes.", now));
            }
        }
        JSONObject p = svc.permissions();
        for (Iterator<String> it = p.keys(); it.hasNext(); ) {
            String k = it.next();
            if (p.optBoolean(k, true)) continue;
            out.add(new Msg("live:perm:" + k + ":" + day, LIVE, "I'm missing a permission",
                    permName(k) + ". Plug into the Mac and run coro setup to grant it.", now));
        }
    }

    private static String permName(String k) {
        switch (k) {
            case "overlay":
                return "Showing over other apps";
            case "usageStats":
                return "Usage access, to see which app is open";
            case "dump":
                return "Reading the app list on each screen (DUMP)";
            case "readLogs":
                return "Reading crash logs";
            case "notifications":
                return "Notifications";
            case "installs":
                return "Installing apps";
            case "allFiles":
                return "All files access, for APKs and reports";
            default:
                return k;
        }
    }

    private void updates(List<Msg> out) {
        for (String pkg : svc.registry.packages()) {
            JSONObject e = svc.registry.entry(pkg);
            JSONObject up = e == null ? null : e.optJSONObject("update");
            if (up == null || up.optString("version").isEmpty()) continue;
            String label = svc.registry.label(pkg), version = up.optString("version");
            boolean canInstall = !up.optString("url").isEmpty() && !"notify".equals(e.optString("updateMode"));
            Msg m = new Msg("update:" + pkg + ":" + version, UPDATE, label + " " + version + " is out",
                    canInstall ? "Tap Update and I'll download and install it." : "There's no APK I can install for this one. Grok can make a build.",
                    up.optLong("found"));
            m.pkg = pkg;
            m.update = canInstall;
            out.add(m);
        }
    }

    private void bugs(List<Msg> out) {
        JSONArray a = svc.bugs.list("open", 8);
        for (int i = 0; i < a.length(); i++) {
            JSONObject b = a.optJSONObject(i);
            if (b == null) continue;
            int n = Math.max(1, b.optInt("count"));
            String app = b.optString("app", "An app");
            String text = b.optString("title") + (n > 1 ? " (" + n + " times)" : "") + ". "
                    + (b.optInt("issue") > 0 ? "It's on GitHub for Grok to fix." : "I saved a report for Grok.");
            Msg m = new Msg("bug:" + b.optString("id") + ":" + n, BUG, app + " " + verb(b.optString("kind")), text, b.optLong("last"));
            m.pkg = b.optString("pkg", null);
            out.add(m);
        }
    }

    private static String verb(String kind) {
        switch (kind) {
            case "anr":
                return "froze";
            case "report":
                return "has a problem you reported";
            case "renderer":
                return "had a page crash";
            case "js":
                return "had a script error";
            case "launch":
                return "didn't start";
            default:
                return "crashed";
        }
    }

    /** The last few days' installs and Grok Build runs worth knowing about, newest of each kind per app. */
    private void notices(List<Msg> out, long now) {
        JSONArray h = svc.history.recent(250);
        Set<String> done = new HashSet<>(), installedLater = new HashSet<>();
        for (int i = h.length() - 1; i >= 0; i--) {
            JSONObject e = h.optJSONObject(i);
            if (e == null) continue;
            long t = e.optLong("t");
            if (now - t > NOTICE_AGE) break;
            String kind = e.optString("kind"), app = e.optString("app", ""), pkg = e.optString("pkg", "");
            boolean ok = e.optBoolean("ok", true);
            String msg = firstLine(e.optString("msg", "")), version = e.optString("version", "");
            if (("install".equals(kind) || "update".equals(kind)) && ok) {
                installedLater.add(pkg);
                continue;
            }
            if (!done.add(kind + "|" + app)) continue;
            String id = "h:" + t + ":" + kind;
            switch (kind) {
                case "install":
                case "update":
                    if (installedLater.contains(pkg)) break;
                    out.add(withPkg(new Msg(id, WARN, ("install".equals(kind) ? "Couldn't install " : "Couldn't update ") + app, msg, t), pkg));
                    break;
                case "grok_done":
                    out.add(new Msg(id, ok ? GOOD : WARN, ok ? "Grok Build finished" : "Grok Build stopped early", msg, t));
                    break;
                case "grok_stopped":
                    out.add(new Msg(id, INFO, "Grok Build was stopped", msg, t));
                    break;
                case "bug_fixed":
                    out.add(withPkg(new Msg(id, GOOD, "Fixed: " + app, msg + (version.isEmpty() ? "" : " (in " + version + ")"), t), pkg));
                    break;
                case "published":
                    out.add(withPkg(new Msg(id, GOOD, app + (version.isEmpty() ? "" : " " + version) + " is on GitHub",
                            "The update is published, so other Thors can get it too.", t), pkg));
                    break;
                case "bugs_sent":
                    out.add(new Msg(id, INFO, "Sent problems to Grok", msg.isEmpty() ? "Grok has the reports now." : "Grok has " + msg + " now.", t));
                    break;
                default:
                    break;
            }
        }
    }

    private static Msg withPkg(Msg m, String pkg) {
        m.pkg = pkg.isEmpty() ? null : pkg;
        return m;
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        if (nl >= 0) s = s.substring(0, nl);
        s = s.trim();
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }

    private boolean installed(String pkg) {
        try {
            svc.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
