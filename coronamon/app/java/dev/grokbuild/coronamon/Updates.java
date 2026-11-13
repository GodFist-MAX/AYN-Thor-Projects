package dev.grokbuild.coronamon;

import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * Release checks for registry entries with a GitHub repo, plus fetching and installing them.
 * Grok-built apps update from the Mac instead, so only third-party installs have a repo.
 */
final class Updates {
    interface Listener {
        void found(String pkg, String label, String version, boolean canInstall);

        void progress(String pkg, String what);

        void ready(String pkg, File apk, String version, String sha256);

        void failed(String pkg, String why);
    }

    private final PetService svc;
    private final Registry registry;
    private final Listener listener;
    private final Handler work;
    private final Handler main;

    Updates(PetService svc, Registry registry, Handler main, Listener listener) {
        this.svc = svc;
        this.registry = registry;
        this.main = main;
        this.listener = listener;
        HandlerThread t = new HandlerThread("updates");
        t.start();
        work = new Handler(t.getLooper());
    }

    void quit() {
        work.getLooper().quitSafely();
    }

    void checkAll(boolean announce) {
        work.post(() -> {
            for (String pkg : registry.packages()) {
                JSONObject e = registry.entry(pkg);
                if (e == null || e.optString("github").isEmpty() || "off".equals(e.optString("updateMode"))) continue;
                try {
                    check(pkg, e, announce);
                } catch (Exception ex) {
                    Log.w("update check " + pkg + ": " + ex.getMessage());
                    registry.upsert(pkg, Util.obj("lastCheck", System.currentTimeMillis(), "lastCheckError", String.valueOf(ex.getMessage())));
                }
            }
        });
    }

    void checkOne(String pkg) {
        work.post(() -> {
            JSONObject e = registry.entry(pkg);
            if (e == null || e.optString("github").isEmpty()) {
                main.post(() -> listener.failed(pkg, "there's no GitHub source for it"));
                return;
            }
            try {
                if (!check(pkg, e, true)) main.post(() -> listener.progress(pkg, "up to date"));
            } catch (Exception ex) {
                main.post(() -> listener.failed(pkg, "the release check failed: " + ex.getMessage()));
            }
        });
    }

    /** Returns true if a newer release is available. */
    private boolean check(String pkg, JSONObject e, boolean announce) throws Exception {
        String repo = e.optString("github");
        JSONObject rel = Util.parse(Util.httpGet("https://api.github.com/repos/" + repo + "/releases/latest", 20000,
                "application/vnd.github+json"));
        String tag = rel.optString("tag_name");
        JSONObject asset = pickAsset(rel.optJSONArray("assets"), e.optString("assetRegex", null));
        JSONObject live = registry.live(pkg);
        String have = live.optString("versionName", "");
        long now = System.currentTimeMillis();
        boolean newer = !tag.isEmpty() && (have.isEmpty() || Util.compareVersions(tag, have) > 0);
        JSONObject fields = Util.obj("lastCheck", now, "lastCheckError", "", "latestTag", tag);
        if (newer) {
            JSONObject up = Util.obj("version", tag, "found", now, "page", rel.optString("html_url"));
            if (asset != null) {
                Util.put(up, "url", asset.optString("browser_download_url"));
                Util.put(up, "asset", asset.optString("name"));
                Util.put(up, "size", asset.optLong("size"));
                String digest = asset.optString("digest", "");
                if (digest.startsWith("sha256:")) Util.put(up, "sha256", digest.substring(7));
            }
            Util.put(fields, "update", up);
        } else {
            Util.put(fields, "update", "");
        }
        JSONObject before = e.optJSONObject("update");
        boolean fresh = newer && (before == null || !tag.equals(before.optString("version")));
        registry.upsert(pkg, fields);
        if (newer && (announce || fresh)) {
            boolean canInstall = asset != null && !"notify".equals(e.optString("updateMode"));
            String label = registry.label(pkg);
            main.post(() -> listener.found(pkg, label, tag, canInstall));
        }
        return newer;
    }

    static JSONObject pickAsset(JSONArray assets, String regex) {
        if (assets == null) return null;
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name");
            String n = name.toLowerCase(Locale.ROOT);
            if (!n.endsWith(".apk")) continue;
            if (regex != null && !regex.isEmpty() && !name.matches(regex)) continue;
            int score = 0;
            if (n.contains("arm64") || n.contains("aarch64")) score += 4;
            if (n.contains("universal")) score += 2;
            if (n.contains("x86") || n.contains("armeabi-v7a") || n.contains("armv7")) score -= 5;
            if (n.contains("debug")) score -= 1;
            if (score > bestScore) {
                best = a;
                bestScore = score;
            }
        }
        return best;
    }

    /** Downloads the update found by the last check, verifies it, and hands it to the listener. */
    void fetch(String pkg) {
        work.post(() -> {
            JSONObject e = registry.entry(pkg);
            JSONObject up = e == null ? null : e.optJSONObject("update");
            if (up == null || up.optString("url").isEmpty()) {
                main.post(() -> listener.failed(pkg, "there's no downloadable update"));
                return;
            }
            String version = up.optString("version");
            File out = new File(Paths.DOWNLOADS, pkg + "-" + version.replaceAll("[^\\w.-]", "_") + ".apk");
            try {
                main.post(() -> listener.progress(pkg, "downloading " + up.optString("asset")));
                Util.download(up.optString("url"), out, 60000, null);
                String sha = Util.sha256(out);
                String want = up.optString("sha256", "");
                if (!want.isEmpty() && !want.equalsIgnoreCase(sha)) {
                    out.delete();
                    throw new IllegalStateException("the download's SHA-256 doesn't match the release");
                }
                Installer.Apk apk = Installer.inspect(svc, out);
                if (apk == null || !pkg.equals(apk.pkg)) {
                    out.delete();
                    throw new IllegalStateException("the download isn't " + pkg);
                }
                String sig = Installer.signatureProblem(svc, out, pkg);
                if (sig != null) throw new IllegalStateException(sig);
                main.post(() -> listener.ready(pkg, out, version, sha));
            } catch (Exception ex) {
                String why = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                main.post(() -> listener.failed(pkg, why));
            }
        });
    }
}
