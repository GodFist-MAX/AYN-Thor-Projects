package dev.grokbuild.coronamon;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The apps Coronamon looks after: everything Grok Build made or installed for the Thor. Each entry
 * holds what PackageManager can't tell us (where it's built, where updates come from); live install
 * details are added when the registry is read.
 */
final class Registry {
    static final String SELF = "dev.grokbuild.coronamon";

    private final Context ctx;
    private final File file;
    private JSONObject apps = new JSONObject();

    Registry(Context ctx) {
        this.ctx = ctx;
        file = new File(ctx.getFilesDir(), "registry.json");
        load();
    }

    private synchronized void load() {
        JSONObject o = Util.readJson(file);
        JSONObject a = o == null ? null : o.optJSONObject("apps");
        apps = a == null ? new JSONObject() : a;
        if (apps.length() == 0) seed();
    }

    private void seed() {
        long now = System.currentTimeMillis();
        seedOne(SELF, "Coronamon", "built", "~/thor-coronamon", null, "ask", false,
                "This little guy. Watches over everything below.", now);
        seedOne("dev.crossbar.launcher", "CrossBar", "built", "~/xmb-launcher", null, "ask", false,
                "PSP/PS3/PS5-style home launcher (the Thor's home screen).", now);
        seedOne("dev.anideck.app", "AniDeck", "built", "~/anideck", null, "ask", true,
                "Anime app with AniList sync; uses both screens.", now);
        seedOne("com.naruto.stormgenerations", "Naruto Storm Generations", "built", "~/thor-naruto/translate",
                "Masterllgod/naruto-storm-generations-port-nativo", "notify", true,
                "English re-signed build of the native port. Upstream APKs can't install over it; Grok re-translates new versions.", now);
        seedOne("dev.igawa6.edenduo", "Eden Duo", "installed", null, "igawa6/eden-duo", "ask", true,
                "Dual-screen Switch emulator.", now);
        seedOne("com.thorpathfinder.app", "Thor Pathfinder", "installed", null, "KaitonGxx/thor-pathfinder", "ask", false,
                "Screen swapping and button shortcuts; needs Shizuku running.", now);
        seedOne("moe.shizuku.privileged.api", "Shizuku", "installed", null, "RikkaApps/Shizuku", "ask", false,
                "Shell-level helper for Pathfinder and Coronamon. Stops on reboot.", now);
        save();
    }

    private void seedOne(String pkg, String label, String kind, String project, String github, String updateMode,
                         boolean avoid, String notes, long now) {
        JSONObject e = Util.obj("pkg", pkg, "label", label, "kind", kind, "updateMode", updateMode,
                "avoid", avoid, "notes", notes, "added", now);
        if (project != null) Util.put(e, "project", project);
        if (github != null) Util.put(e, "github", github);
        Util.put(apps, pkg, e);
    }

    synchronized void save() {
        Util.writeText(file, Util.obj("apps", apps, "saved", System.currentTimeMillis()).toString());
        Paths.ROOT.mkdirs();
        try {
            Util.writeText(Paths.REGISTRY_MIRROR, liveAll().toString(2));
        } catch (Exception e) {
            Log.w("registry mirror: " + e);
        }
    }

    synchronized boolean has(String pkg) {
        return pkg != null && apps.has(pkg);
    }

    synchronized JSONObject entry(String pkg) {
        JSONObject e = apps.optJSONObject(pkg);
        return e == null ? null : Util.parse(e.toString());
    }

    synchronized List<String> packages() {
        List<String> out = new ArrayList<>();
        for (Iterator<String> it = apps.keys(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    /** Merges fields into the entry, creating it if needed. Returns true if it is new. */
    synchronized boolean upsert(String pkg, JSONObject fields) {
        JSONObject e = apps.optJSONObject(pkg);
        boolean isNew = e == null;
        if (isNew) {
            e = Util.obj("pkg", pkg, "added", System.currentTimeMillis(), "updateMode", "ask", "kind", "built");
            Util.put(apps, pkg, e);
        }
        if (fields != null) {
            for (Iterator<String> it = fields.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = fields.opt(k);
                if (v == null || v == JSONObject.NULL || "".equals(v)) e.remove(k);
                else Util.put(e, k, v);
            }
        }
        save();
        return isNew;
    }

    synchronized void remove(String pkg) {
        apps.remove(pkg);
        save();
    }

    String label(String pkg) {
        JSONObject e = entry(pkg);
        if (e != null && !e.optString("label").isEmpty()) return e.optString("label");
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ex) {
            return pkg;
        }
    }

    boolean avoid(String pkg) {
        JSONObject e = entry(pkg);
        return e != null && e.optBoolean("avoid");
    }

    String installer(String pkg) {
        try {
            InstallSourceInfo s = ctx.getPackageManager().getInstallSourceInfo(pkg);
            return s.getInstallingPackageName();
        } catch (Exception e) {
            return null;
        }
    }

    boolean amInstaller(String pkg) {
        return SELF.equals(pkg) || SELF.equals(installer(pkg));
    }

    /** The registry entry plus what's installed right now. */
    JSONObject live(String pkg) {
        JSONObject e = entry(pkg);
        if (e == null) e = Util.obj("pkg", pkg, "label", label(pkg));
        PackageManager pm = ctx.getPackageManager();
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            ApplicationInfo ai = pi.applicationInfo;
            Util.put(e, "installed", true);
            Util.put(e, "versionName", pi.versionName);
            Util.put(e, "versionCode", pi.getLongVersionCode());
            Util.put(e, "firstInstall", pi.firstInstallTime);
            Util.put(e, "lastUpdate", pi.lastUpdateTime);
            Util.put(e, "targetSdk", ai.targetSdkVersion);
            Util.put(e, "enabled", ai.enabled);
            Util.put(e, "apkSize", new File(ai.sourceDir).length());
            String inst = installer(pkg);
            Util.put(e, "installer", inst == null ? "adb" : inst);
            Util.put(e, "silentUpdates", amInstaller(pkg) && ai.targetSdkVersion >= 30);
            if (e.optString("label").isEmpty()) Util.put(e, "label", pm.getApplicationLabel(ai).toString());
        } catch (PackageManager.NameNotFoundException ex) {
            Util.put(e, "installed", false);
        }
        File dir = new File(Paths.APKS, pkg);
        String[] kept = dir.list((d, n) -> n.endsWith(".apk"));
        Util.put(e, "archived", kept == null ? 0 : kept.length);
        return e;
    }

    JSONArray liveAll() {
        JSONArray out = new JSONArray();
        for (String p : packages()) out.put(live(p));
        return out;
    }
}
