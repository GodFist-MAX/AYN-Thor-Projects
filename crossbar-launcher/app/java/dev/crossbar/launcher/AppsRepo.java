package dev.crossbar.launcher;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.util.LruCache;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Installed apps, their icons and launcher shortcuts. */
final class AppsRepo {
    static final Set<String> EMULATORS = set(
            "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold", "xyz.aethersx2.android", "me.magnum.melonds",
            "org.azahar_emu.azahar", "io.github.lime3ds.android", "dev.igawa6.edenduo", "dev.eden.eden_emulator",
            "com.retroarch.aarch64", "com.retroarch", "cyou.joiplay.joiplay", "com.dsemu.drastic",
            "com.github.stenzek.duckstation", "org.dolphinemu.dolphinemu", "io.recompiled.redream",
            "org.citron.citron_emu", "com.armsx2", "xyz.aethersx2.tturnip");
    static final Set<String> VIDEO = set(
            "com.google.android.videos", "com.google.android.youtube", "com.tubitv", "com.netflix.mediaclient",
            "com.disney.disneyplus", "com.hulu.plus", "com.amazon.avod.thirdpartyclient", "com.crunchyroll.crunchyroid",
            "com.plexapp.android", "org.jellyfin.mobile", "tv.twitch.android.app", "com.tiktok.lite.go",
            "com.zhiliaoapp.musically", "com.wbd.stream", "com.peacocktv.peacockandroid", "org.videolan.vlc",
            "is.xyz.mpv", "com.mxtech.videoplayer.ad");
    static final Set<String> MUSIC = set(
            "com.google.android.apps.youtube.music", "com.spotify.music", "com.apple.android.music",
            "com.soundcloud.android", "com.pandora.android", "com.amazon.mp3", "deezer.android.app", "james.dsp");
    static final Set<String> SOCIAL = set(
            "com.twitter.android", "com.discord", "com.valvesoftware.android.steam.community",
            "com.valvesoftware.steamlink", "com.reddit.frontpage", "org.telegram.messenger", "com.whatsapp",
            "com.instagram.android", "com.facebook.katana", "com.facebook.orca", "com.snapchat.android",
            "com.bsky.app", "com.google.android.apps.messaging");
    static final Set<String> NETWORK = set(
            "com.opera.browser", "com.android.chrome", "org.mozilla.firefox", "com.brave.browser",
            "com.microsoft.emmx", "com.duckduckgo.mobile.android", "com.google.android.apps.chromecast.app",
            "com.google.android.apps.docs", "dev.imranr.obtainium", "com.android.vending", "com.google.android.gm",
            "com.google.android.googlequicksearchbox");

    private final App app;
    private final PackageManager pm;
    private final LauncherApps launcherApps;
    private final Map<String, Boolean> games = new ConcurrentHashMap<>();
    private final LruCache<String, byte[]> icons = new LruCache<String, byte[]>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };

    AppsRepo(App app) {
        this.app = app;
        pm = app.getPackageManager();
        launcherApps = (LauncherApps) app.getSystemService(Context.LAUNCHER_APPS_SERVICE);
    }

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    JSONArray json() {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        Map<String, UsageStats> usage = usage();
        Set<String> feeds = app.feeds.withSections();
        List<JSONObject> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(app.getPackageName()) || !seen.add(pkg)) continue;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            JSONObject o = Util.obj("pkg", pkg, "cls", ri.activityInfo.name,
                    "label", String.valueOf(ri.loadLabel(pm)), "kind", classify(ai, pkg),
                    "system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            UsageStats u = usage.get(pkg);
            if (u != null) {
                Util.put(o, "lastUsed", u.getLastTimeUsed());
                Util.put(o, "fgTime", u.getTotalTimeInForeground());
            }
            if (feeds.contains(pkg)) Util.put(o, "feed", true);
            out.add(o);
        }
        Collections.sort(out, (a, b) -> a.optString("label").compareToIgnoreCase(b.optString("label")));
        JSONArray arr = new JSONArray();
        for (JSONObject o : out) arr.put(o);
        return arr;
    }

    /** Whether a package is a game or an emulator. */
    boolean isGame(String pkg) {
        if (pkg == null) return false;
        Boolean g = games.get(pkg);
        if (g == null) {
            String kind = kind(pkg);
            g = "game".equals(kind) || "emulator".equals(kind);
            games.put(pkg, g);
        }
        return g;
    }

    /** What classify() makes of an installed package: game, emulator, video, music, ... or "app". */
    String kind(String pkg) {
        try {
            return classify(pm.getApplicationInfo(pkg, 0), pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return "app";
        }
    }

    static String classify(ApplicationInfo ai, String pkg) {
        if (EMULATORS.contains(pkg)) return "emulator";
        if (VIDEO.contains(pkg)) return "video";
        if (MUSIC.contains(pkg)) return "music";
        if (SOCIAL.contains(pkg)) return "social";
        if (NETWORK.contains(pkg)) return "network";
        if (ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) return "game";
        switch (ai.category) {
            case ApplicationInfo.CATEGORY_VIDEO: return "video";
            case ApplicationInfo.CATEGORY_AUDIO: return "music";
            case ApplicationInfo.CATEGORY_SOCIAL: return "social";
            case ApplicationInfo.CATEGORY_IMAGE: return "photo";
            default: return "app";
        }
    }

    private Map<String, UsageStats> usage() {
        if (!usageAllowed()) return new HashMap<>();
        try {
            UsageStatsManager usm = (UsageStatsManager) app.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            Map<String, UsageStats> m = usm.queryAndAggregateUsageStats(now - 90L * 86400_000L, now);
            return m != null ? m : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    boolean usageAllowed() {
        AppOpsManager ops = (AppOpsManager) app.getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    byte[] icon(String pkg, String cls) {
        String key = pkg + "/" + cls;
        byte[] b = icons.get(key);
        if (b != null) return b;
        try {
            Drawable d = cls != null && !cls.isEmpty()
                    ? pm.getActivityIcon(new ComponentName(pkg, cls))
                    : pm.getApplicationIcon(pkg);
            b = Util.png(Util.toBitmap(d, 192));
            icons.put(key, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    boolean launch(String pkg) {
        return launch(pkg, Display.DEFAULT_DISPLAY);
    }

    boolean launch(String pkg, int displayId) {
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            app.startActivity(i, Screens.on(displayId));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    boolean shortcutsAvailable() {
        try {
            return launcherApps.hasShortcutHostPermission();
        } catch (Exception e) {
            return false;
        }
    }

    JSONObject shortcuts(String... pkgs) {
        JSONObject out = new JSONObject();
        if (!shortcutsAvailable()) return out;
        for (String pkg : pkgs) {
            try {
                LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
                q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                        | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                        | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
                q.setPackage(pkg);
                List<ShortcutInfo> list = launcherApps.getShortcuts(q, Process.myUserHandle());
                if (list == null || list.isEmpty()) continue;
                JSONArray arr = new JSONArray();
                for (ShortcutInfo si : list) {
                    if (!si.isEnabled()) continue;
                    CharSequence label = si.getShortLabel();
                    CharSequence longLabel = si.getLongLabel();
                    arr.put(Util.obj("id", si.getId(), "label", label == null ? si.getId() : label.toString(),
                            "long", longLabel == null ? "" : longLabel.toString()));
                }
                Util.put(out, pkg, arr);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    byte[] shortcutIcon(String pkg, String id) {
        String key = "sc:" + pkg + "/" + id;
        byte[] b = icons.get(key);
        if (b != null) return b;
        try {
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            q.setPackage(pkg);
            q.setShortcutIds(Collections.singletonList(id));
            List<ShortcutInfo> list = launcherApps.getShortcuts(q, Process.myUserHandle());
            if (list == null || list.isEmpty()) return null;
            Drawable d = launcherApps.getShortcutIconDrawable(list.get(0), app.getResources().getDisplayMetrics().densityDpi);
            if (d == null) return null;
            b = Util.png(Util.toBitmap(d, 160));
            icons.put(key, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    String startShortcut(String pkg, String id) {
        try {
            launcherApps.startShortcut(pkg, id, null, Screens.top(), Process.myUserHandle());
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? "Shortcut unavailable" : e.getMessage();
        }
    }
}
