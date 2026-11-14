package dev.crossbar.launcher;

import android.content.Intent;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Display;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/** Methods the bottom-screen page calls through window.NativeB. */
final class BottomBridge {
    private final BottomActivity act;
    private final App app = App.I;

    BottomBridge(BottomActivity act) {
        this.act = act;
    }

    /** The last view model from the top screen, or "" before the top screen has sent one. */
    @JavascriptInterface
    public String state() {
        return app.bottomState;
    }

    @JavascriptInterface
    public boolean topVisible() {
        return app.topVisible;
    }

    /** Sends a command (a JSON object) to the top screen's menu. */
    @JavascriptInterface
    public void toTop(String json) {
        try {
            app.toTop(new JSONObject(json).toString());
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void music(String cmd, String arg) {
        MusicService.command(act, cmd, arg);
    }

    @JavascriptInterface
    public String mediaControl(String pkg, String cmd) {
        String err = NotifListener.control(act, pkg, cmd);
        return err == null ? "" : err;
    }

    /** Brings the CrossBar menu back on the top screen, like the Home button. */
    @JavascriptInterface
    public void showTop() {
        act.runOnUiThread(() -> {
            try {
                act.startActivity(new Intent(act, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), Screens.top());
            } catch (Exception ignored) {
            }
        });
    }

    @JavascriptInterface
    public String launchHere(String pkg) {
        Display d = act.getDisplay();
        if (d == null) return "Couldn't open that app on this screen";
        app.opening(false, app.apps.isGame(pkg));
        if (!app.apps.launch(pkg, d.getDisplayId())) {
            app.back(false);
            CoroBugs.launchFailed(act, "Couldn't open an app on the bottom screen", "package " + pkg);
            return "Couldn't open that app on this screen";
        }
        app.store.opened("app:" + pkg);
        return "";
    }

    @JavascriptInterface
    public void haptic(int ms) {
        Vibrator v = act.getSystemService(Vibrator.class);
        if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(Math.max(5, Math.min(ms, 40)), 60));
    }

    @JavascriptInterface
    public void reload() {
        act.runOnUiThread(act::load);
    }
}
