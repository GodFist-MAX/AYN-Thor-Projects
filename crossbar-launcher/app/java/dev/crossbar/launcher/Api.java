package dev.crossbar.launcher;

import android.Manifest;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;

import dev.crossbar.launcher.LocalServer.Req;
import dev.crossbar.launcher.LocalServer.Resp;

/** JSON, image and media routes for the UI. */
final class Api {
    private final App app;

    Api(App app) {
        this.app = app;
    }

    Resp handle(Req r) throws Exception {
        switch (r.path) {
            case "/api/home": return Resp.json(home());
            case "/api/status": return Resp.json(status());
            case "/api/games": return Resp.json(games());
            case "/api/rescan":
                app.games.scan(true);
                return Resp.json(games());
            case "/api/music": return Resp.json(app.media.songs());
            case "/api/videos": return Resp.json(app.media.videos());
            case "/api/photos": return Resp.json(app.media.photos());
            case "/api/social": return Resp.json(app.social.snapshot("1".equals(r.q("refresh"))));
            case "/api/nowplaying": return Resp.json(nowPlaying());
            case "/api/queue": return Resp.json(MusicService.queue());
            case "/api/gameinfo": return Resp.json(gameInfo(r));
            case "/api/sysinfo": return Resp.json(sysInfo());
            case "/api/googletv": return Resp.json(GoogleTv.downloads(app));
            case "/api/appfeed": return Resp.json(app.feeds.get(r.q("pkg")));
            case "/api/settings":
                if ("POST".equals(r.method)) {
                    app.store.update(new JSONObject(r.bodyText()));
                    app.settingsChanged();
                }
                return Resp.json(app.store.settings());
            case "/api/log":
                Log.i(App.TAG, "ui: " + r.bodyText());
                return Resp.text(200, "ok");
            case "/img/app": return image(app.apps.icon(r.q("pkg"), r.q("cls")), "image/png");
            case "/img/shortcut": return image(app.apps.shortcutIcon(r.q("pkg"), r.q("id")), "image/png");
            case "/img/file": return imageFile(r);
            case "/img/feed": return image(app.feeds.image(r.q("pkg"), r.q("p")), "image/jpeg");
            case "/img/thumb":
                return image(app.media.thumb(r.q("type"), parseLong(r.q("id")), parseInt(r.q("s"), 320)), "image/jpeg");
            case "/img/np": return nowPlayingArt();
            case "/media/audio": return media(r, "audio");
            case "/media/video": return media(r, "video");
            case "/media/image": return media(r, "image");
            default: return Resp.text(404, "not found");
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static Resp image(byte[] b, String type) {
        if (b == null) return Resp.text(404, "no image");
        Resp r = Resp.bytes(200, type, b);
        r.header("Cache-Control", "max-age=3600");
        return r;
    }

    private JSONObject home() {
        JSONObject o = new JSONObject();
        Util.put(o, "settings", app.store.settings());
        Util.put(o, "apps", app.apps.json());
        Util.put(o, "platforms", Platforms.json(app));
        Util.put(o, "games", games());
        Util.put(o, "history", app.store.history());
        Util.put(o, "googleTv", GoogleTv.downloads(app));
        Util.put(o, "shortcuts", app.apps.shortcuts(GoogleTv.PKG, "com.google.android.youtube",
                "com.twitter.android", "com.google.android.apps.youtube.music", "com.tubitv", "com.tiktok.lite.go",
                "com.discord", "com.valvesoftware.android.steam.community"));
        Util.put(o, "media", app.media.counts());
        Util.put(o, "perms", perms());
        Util.put(o, "wallpapers", customWallpapers());
        String version = "";
        try {
            version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        Util.put(o, "device", Util.obj("model", Build.MODEL, "android", Build.VERSION.RELEASE, "version", version,
                "dualScreen", Screens.bottom(app) != null, "joystickLights", Lights.supported()));
        return o;
    }

    private JSONArray games() {
        JSONArray arr = new JSONArray();
        for (JSONObject g : app.games.list()) {
            JSONObject c = Util.copy(g);
            c.remove("path");
            JSONObject art = g.optJSONObject("art");
            JSONObject urls = new JSONObject();
            if (art != null) {
                for (Iterator<String> it = art.keys(); it.hasNext(); ) {
                    String k = it.next();
                    Util.put(urls, k, Util.fileUrl(art.optString(k)));
                }
            }
            Util.put(c, "art", urls);
            arr.put(c);
        }
        return arr;
    }

    private JSONObject status() {
        JSONObject o = new JSONObject();
        Intent bat = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            Util.put(o, "battery", scale > 0 ? level * 100 / scale : level);
            Util.put(o, "charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
        }
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        boolean wifi = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        Util.put(o, "wifi", wifi);
        Util.put(o, "online", nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        if (wifi) {
            try {
                WifiManager wm = app.getSystemService(WifiManager.class);
                WifiInfo wi = wm.getConnectionInfo();
                Util.put(o, "wifiLevel", wm.calculateSignalLevel(wi.getRssi()));
                Util.put(o, "wifiMax", wm.getMaxSignalLevel());
            } catch (Exception ignored) {
            }
        }
        Util.put(o, "time", System.currentTimeMillis());
        Util.put(o, "friends", app.social.onlineCount());
        Util.put(o, "notifs", NotifListener.count());
        Util.put(o, "np", nowPlaying());
        return o;
    }

    private JSONObject nowPlaying() {
        return Util.obj("local", MusicService.state(), "external", NotifListener.nowPlaying(app));
    }

    private JSONObject gameInfo(Req r) {
        String id = r.q("id");
        if (id != null && !id.isEmpty()) {
            JSONObject g = app.games.byId(id);
            return g == null ? new JSONObject() : app.gameInfo.forGame(g);
        }
        return app.gameInfo.forApp(r.q("app"), r.q("title"));
    }

    private JSONObject sysInfo() {
        JSONObject o = new JSONObject();
        StatFs fs = new StatFs(Environment.getExternalStorageDirectory().getPath());
        Util.put(o, "storageTotal", fs.getTotalBytes());
        Util.put(o, "storageFree", fs.getAvailableBytes());
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        app.getSystemService(ActivityManager.class).getMemoryInfo(mi);
        Util.put(o, "memTotal", mi.totalMem);
        Util.put(o, "memFree", mi.availMem);
        Util.put(o, "uptime", SystemClock.elapsedRealtime());
        Intent bat = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) Util.put(o, "batteryTemp", bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
        Util.put(o, "model", Build.MODEL);
        Util.put(o, "maker", Build.MANUFACTURER);
        Util.put(o, "android", Build.VERSION.RELEASE);
        Util.put(o, "games", app.games.list().size());
        return o;
    }

    private Resp nowPlayingArt() {
        Bitmap b = MusicService.playing() || MusicService.art() != null && !NotifListener.nowPlaying(app).optBoolean("playing")
                ? MusicService.art() : NotifListener.art(app);
        if (b == null) b = MusicService.art();
        if (b == null) return Resp.text(404, "no art");
        Resp r = Resp.bytes(200, "image/png", Util.png(b));
        r.header("Cache-Control", "no-store");
        return r;
    }

    JSONObject perms() {
        boolean media = app.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
                && app.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                && app.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        return Util.obj("media", media, "allFiles", Environment.isExternalStorageManager(),
                "listener", NotifListener.granted(app),
                "romTree", Platforms.hasRomTree(app, app.store.str("romRoot")),
                "home", isDefaultHome(app), "shortcuts", app.apps.shortcutsAvailable(),
                "usage", app.apps.usageAllowed(), "bottomScreen", Screens.bottomEnabled(app));
    }

    static boolean isDefaultHome(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = c.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && c.getPackageName().equals(ri.activityInfo.packageName);
    }

    /** Images the user drops into /sdcard/CrossBar/wallpapers/<category or platform>/. */
    private JSONObject customWallpapers() {
        JSONObject out = new JSONObject();
        File root = new File(Store.EXT_DIR, "wallpapers");
        File[] dirs = root.listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            File[] fs = d.listFiles();
            if (fs == null) continue;
            Arrays.sort(fs);
            JSONArray arr = new JSONArray();
            for (File f : fs) if (Util.isImage(f.getName())) arr.put(Util.fileUrl(f.getAbsolutePath()) + "&w=1920");
            if (arr.length() > 0) Util.put(out, d.getName().toLowerCase(Locale.ROOT), arr);
        }
        return out;
    }

    private Resp imageFile(Req r) throws IOException {
        File f = new File(r.q("p"));
        String canon = f.getCanonicalPath();
        String sd = Environment.getExternalStorageDirectory().getCanonicalPath();
        String files = app.getFilesDir().getCanonicalPath();
        if (!(canon.startsWith(sd + "/") || canon.startsWith(files + "/"))) return Resp.text(403, "forbidden");
        File cf = new File(canon);
        if (!Util.isImage(canon) || !cf.isFile()) return Resp.text(404, "not found");
        int w = parseInt(r.q("w"), 0);
        if (w > 0) {
            Resp s = scaled(cf, w);
            if (s != null) return s;
        }
        Resp res = Resp.file(cf, Util.mime(canon));
        res.header("Cache-Control", "max-age=3600");
        return res;
    }

    /* A copy at the size the page draws it. The WebView decodes pictures while it rasterizes a frame,
       and a 1024px PNG icon takes it 60ms (a dropped-frame stutter); a 256px copy takes 1ms.
       Big PNGs are re-encoded even when they are not shrunk, as JPEG decodes several times faster. */
    private Resp scaled(File f, int w) {
        File dir = new File(app.getCacheDir(), "scaled");
        String key = Util.sha1(f.getAbsolutePath() + f.lastModified() + "@" + w + "/2");
        for (String ext : new String[] {".jpg", ".webp"}) {
            File c = new File(dir, key + ext);
            if (!c.isFile()) continue;
            try (FileInputStream in = new FileInputStream(c)) {
                return image(Util.readAll(in), Util.mime(c.getName()));
            } catch (IOException ignored) {
            }
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        boolean jpeg = "image/jpeg".equals(o.outMimeType);
        if (o.outWidth <= w && (jpeg || (long) o.outWidth * o.outHeight < 250_000)) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), d);
        if (b == null) return null;
        if (b.getWidth() > w) b = Bitmap.createScaledBitmap(b, w, Math.max(1, Math.round(b.getHeight() * (w / (float) b.getWidth()))), true);
        boolean alpha = !jpeg && transparent(b);
        byte[] out = alpha ? Util.webp(b, 90) : Util.jpeg(b, 88);
        Util.writeBytes(new File(dir, key + (alpha ? ".webp" : ".jpg")), out);
        return image(out, alpha ? "image/webp" : "image/jpeg");
    }

    private static boolean transparent(Bitmap b) {
        if (!b.hasAlpha()) return false;
        int w = b.getWidth();
        int[] row = new int[w];
        for (int y = 0; y < b.getHeight(); y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int p : row) if (p >>> 24 < 250) return true;
        }
        return false;
    }

    private Resp media(Req r, String type) throws IOException {
        long id = parseLong(r.q("id"));
        Uri u = MediaRepo.uri(type, id);
        ContentResolver cr = app.getContentResolver();
        long total;
        try (ParcelFileDescriptor probe = cr.openFileDescriptor(u, "r")) {
            if (probe == null) return Resp.text(404, "not found");
            total = probe.getStatSize();
        }
        String mime = app.media.mimeOf(u);
        return Resp.ranged(r, total, mime, offset -> {
            ParcelFileDescriptor pfd = cr.openFileDescriptor(u, "r");
            if (pfd == null) throw new IOException("gone");
            FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            in.getChannel().position(offset);
            return in;
        });
    }
}
