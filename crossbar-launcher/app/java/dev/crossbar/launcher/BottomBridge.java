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

    /**
     * Shows the video another app is playing, or has paused in the background, on this screen. Apps
     * pause when they leave the screen, so playback is resumed once the app is up; the controller
     * goes back to the menu if that's what the top screen shows by then.
     */
    @JavascriptInterface
    public String watchHere(String pkg) {
        Display d = act.getDisplay();
        if (d == null) return "Couldn't show that video on this screen";
        app.opening(false, false);
        if (!NotifListener.show(act, pkg, d.getDisplayId())) {
            app.back(false);
            CoroBugs.launchFailed(act, "Couldn't show a video on the bottom screen", "package " + pkg);
            return "Couldn't show that video on this screen";
        }
        for (long ms : new long[] {900, 2200}) {
            app.main.postDelayed(() -> {
                NotifListener.control(app, pkg, "play");
                MainActivity m = app.top;
                if (m == null || !app.topVisible) return;
                app.bottomFocus = true;
                m.focusTop();
            }, ms);
        }
        return "";
    }

    /** Opens the app behind a media session on the top screen, at its player, moving it up if it's down here. */
    @JavascriptInterface
    public String openOnTop(String pkg) {
        app.opening(true, app.apps.isGame(pkg));
        if (!NotifListener.show(act, pkg, Display.DEFAULT_DISPLAY)) {
            app.back(true);
            CoroBugs.launchFailed(act, "Couldn't open an app on the top screen", "package " + pkg);
            return "Couldn't open that app";
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
