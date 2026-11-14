package dev.crossbar.launcher;

import android.app.role.RoleManager;
import android.content.Intent;
import android.net.Uri;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Display;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/** Methods the web UI calls through window.Native. Each returns "" on success or a message. */
final class Bridge {
    private final MainActivity act;
    private final App app = App.I;

    Bridge(MainActivity act) {
        this.act = act;
    }

    private static String ok(String err) {
        return err == null ? "" : err;
    }

    @JavascriptInterface
    public String launchApp(String pkg) {
        app.opening(true, app.apps.isGame(pkg));
        if (!app.apps.launch(pkg)) {
            app.back(true);
            return "Couldn't open that app";
        }
        app.store.launched("app:" + pkg);
        return "";
    }

    @JavascriptInterface
    public void clearMediaHistory() {
        app.mediaHistory.clear();
    }

    /** An item from an app's feed (see Feeds): the app's launch intent, with the extras the app gave for it. */
    @JavascriptInterface
    public String openFeed(String pkg, String extras) {
        Intent i = act.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return "That app isn't installed";
        try {
            JSONObject o = new JSONObject(extras);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = o.get(k);
                if (v instanceof Integer) i.putExtra(k, (int) (Integer) v);
                else if (v instanceof Long) i.putExtra(k, (long) (Long) v);
                else if (v instanceof Boolean) i.putExtra(k, (boolean) (Boolean) v);
                else if (v instanceof String) i.putExtra(k, (String) v);
            }
        } catch (Exception e) {
            return "Couldn't open that";
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        app.opening(true, app.apps.isGame(pkg));
        try {
            act.startActivity(i, Screens.on(android.view.Display.DEFAULT_DISPLAY));
        } catch (Exception e) {
            app.back(true);
            return "Couldn't open that app";
        }
        app.store.launched("app:" + pkg);
        return "";
    }

    @JavascriptInterface
    public String launchGame(String id, String player) {
        JSONObject g = app.games.byId(id);
        if (g == null) return "Game not found. Try Rescan Games.";
        app.opening(true, true);
        String err = Platforms.launch(act, g, player);
        if (err == null) app.store.launched("game:" + id);
        else app.back(true);
        return ok(err);
    }

    @JavascriptInterface
    public String startShortcut(String pkg, String id) {
        app.opening(true, app.apps.isGame(pkg));
        String err = app.apps.startShortcut(pkg, id);
        if (err != null && app.apps.launch(pkg)) return "";
        if (err != null) app.back(true);
        return ok(err);
    }

    @JavascriptInterface
    public String openUrl(String url, String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg != null && !pkg.isEmpty() && Platforms.installed(act, pkg)) i.setPackage(pkg);
        app.opening(true, app.apps.isGame(i.getPackage()));
        try {
            act.startActivity(i);
            return "";
        } catch (Exception e) {
            try {
                i.setPackage(null);
                act.startActivity(i);
                return "";
            } catch (Exception e2) {
                app.back(true);
                return "Nothing can open that link";
            }
        }
    }

    @JavascriptInterface
    public String openSettings(String which) {
        String action;
        switch (which) {
            case "wifi": action = Settings.ACTION_WIFI_SETTINGS; break;
            case "bluetooth": action = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "display": action = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sound": action = Settings.ACTION_SOUND_SETTINGS; break;
            case "battery": action = Intent.ACTION_POWER_USAGE_SUMMARY; break;
            case "storage": action = Settings.ACTION_INTERNAL_STORAGE_SETTINGS; break;
            case "apps": action = Settings.ACTION_APPLICATION_SETTINGS; break;
            case "date": action = Settings.ACTION_DATE_SETTINGS; break;
            case "home": action = Settings.ACTION_HOME_SETTINGS; break;
            case "notifications": action = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS; break;
            case "allfiles": action = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION; break;
            case "usage": action = Settings.ACTION_USAGE_ACCESS_SETTINGS; break;
            default: action = Settings.ACTION_SETTINGS; break;
        }
        Intent i = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if ("allfiles".equals(which)) i.setData(Uri.parse("package:" + act.getPackageName()));
        try {
            act.startActivity(i);
            return "";
        } catch (Exception e) {
            try {
                act.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return "";
            } catch (Exception e2) {
                return "Settings unavailable";
            }
        }
    }

    @JavascriptInterface
    public void appInfo(String pkg) {
        try {
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void uninstall(String pkg) {
        try {
            act.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void requestRomAccess() {
        act.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            String root = app.store.str("romRoot");
            String sd = "/storage/emulated/0/";
            String rel = root.startsWith(sd) ? root.substring(sd.length()) : "Roms";
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + rel));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                act.startActivityForResult(i, MainActivity.REQ_TREE);
            } catch (Exception ignored) {
            }
        });
    }

    @JavascriptInterface
    public void requestDefaultHome() {
        act.runOnUiThread(() -> {
            try {
                RoleManager rm = act.getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    act.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), MainActivity.REQ_HOME);
                    return;
                }
            } catch (Exception ignored) {
            }
            openSettings("home");
        });
    }

    @JavascriptInterface
    public void music(String cmd, String arg) {
        MusicService.command(act, cmd, arg);
    }

    @JavascriptInterface
    public String mediaControl(String pkg, String cmd) {
        return ok(NotifListener.control(act, pkg, cmd));
    }

    @JavascriptInterface
    public void playVideoNative(String id) {
        long vid;
        try {
            vid = Long.parseLong(id);
        } catch (NumberFormatException e) {
            return;
        }
        app.opening(true, false);
        if (MusicService.playing()) MusicService.command(act, "pause", null);
        act.startActivity(new Intent(act, PlayerActivity.class).setData(MediaRepo.uri("video", vid)));
    }

    /** The web video player started ("" when it paused or closed), for the joystick lights. */
    @JavascriptInterface
    public void videoPlaying(String id) {
        try {
            app.lights.video(id == null || id.isEmpty() ? null : MediaRepo.uri("video", Long.parseLong(id)));
        } catch (NumberFormatException e) {
            app.lights.video(null);
        }
    }

    @JavascriptInterface
    public String openNotification(String key) {
        return ok(NotifListener.open(key));
    }

    @JavascriptInterface
    public void rescan() {
        app.io.execute(() -> {
            app.games.scan(true);
            act.js("window.CB&&CB.reload()");
        });
    }

    @JavascriptInterface
    public void refreshSocial() {
        app.social.refreshAsync();
    }

    @JavascriptInterface
    public boolean reloadConfig() {
        boolean changed = app.store.mergeExternalConfig(true);
        app.social.refreshAsync();
        return changed;
    }

    @JavascriptInterface
    public void haptic(int ms) {
        Vibrator v = act.getSystemService(Vibrator.class);
        if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(Math.max(5, Math.min(ms, 60)), 80));
    }

    @JavascriptInterface
    public void setTextMode(boolean on) {
        act.textMode = on;
        act.runOnUiThread(() -> act.showKeyboard(on));
    }

    @JavascriptInterface
    public void reload() {
        act.runOnUiThread(act::load);
    }

    @JavascriptInterface
    public String serverUrl() {
        return app.server.url("");
    }

    /** The view model for the bottom screen (JSON), sent whenever the selection changes. */
    @JavascriptInterface
    public void bottom(String json) {
        app.toBottom(json);
    }

    @JavascriptInterface
    public boolean hasBottomScreen() {
        return Screens.bottom(act) != null;
    }

    @JavascriptInterface
    public String launchAppOnBottom(String pkg) {
        Display d = Screens.bottom(act);
        if (d == null) return "There is no second screen";
        app.opening(false, app.apps.isGame(pkg));
        if (!app.apps.launch(pkg, d.getDisplayId())) {
            app.back(false);
            return "Couldn't open that app on the bottom screen";
        }
        app.store.opened("app:" + pkg);
        return "";
    }

    @JavascriptInterface
    public void setBottomScreen(boolean on) {
        act.runOnUiThread(() -> act.setBottomScreen(on));
    }
}
