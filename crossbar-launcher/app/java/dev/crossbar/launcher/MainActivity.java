package dev.crossbar.launcher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MainActivity extends Activity {
    static final int REQ_TREE = 41;
    static final int REQ_PERMS = 42;
    static final int REQ_HOME = 43;

    private FramePacer root;
    WebView web;
    volatile boolean textMode;
    private boolean resumed;
    private static final String EXTRA_REFOCUS = "dev.crossbar.launcher.REFOCUS";
    /** When focusTop last restarted this activity, and whether the resume that follows is that restart's. */
    private long refocusAt;
    private boolean refocusing;
    private final Handler h = new Handler(Looper.getMainLooper());
    private String stickDir;
    private long lastDpadKey;
    private final Runnable stickRepeat = new Runnable() {
        @Override
        public void run() {
            if (stickDir == null) return;
            key(stickDir, true);
            h.postDelayed(this, 95);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        WebView.setWebContentsDebuggingEnabled(App.I.store.bool("devMode"));
        App.I.top = this;
        root = new FramePacer(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        applyPerf();
        createWebView();
        hideSystemUi();
        requestRuntimePermissions();
    }

    void applyPerf() {
        root.apply(this, App.I.menuFps());
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createWebView() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setOffscreenPreRaster(true);
        web.addJavascriptInterface(new Bridge(this), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("127.0.0.1".equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                Log.w(App.TAG, "WebView renderer gone; recreating");
                h.post(() -> createWebView());
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(App.TAG, "js " + m.messageLevel() + ": " + m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        web.requestFocus();
        load();
    }

    void load() {
        LocalServer srv = App.I.server;
        web.loadUrl(srv.url("/app/index.html?t=" + srv.token));
    }

    void js(String code) {
        h.post(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    private void key(String k, boolean repeat) {
        js("window.CB&&CB.key('" + k + "'," + repeat + ")");
    }

    static String map(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return "ok";
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE: return "back";
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_MENU: return "options";
            case KeyEvent.KEYCODE_BUTTON_X: return "x";
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_PAGE_UP: return "l1";
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_PAGE_DOWN: return "r1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "l2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "r2";
            case KeyEvent.KEYCODE_BUTTON_START: return "start";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "select";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "l3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "r3";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "playpause";
            case KeyEvent.KEYCODE_MEDIA_NEXT: return "next";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return "prev";
            default: return null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        App.I.bottomFocus = false;
        return handleKey(e) || super.dispatchKeyEvent(e);
    }

    /** Menu keys, from this window or forwarded by the bottom screen. False for keys the system handles. */
    boolean handleKey(KeyEvent e) {
        int code = e.getKeyCode();
        String k = map(code);
        if (k == null) return false;
        if (textMode && (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DEL)) {
            return false;
        }
        if (code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT) lastDpadKey = SystemClock.uptimeMillis();
        if (e.getAction() == KeyEvent.ACTION_DOWN) key(k, e.getRepeatCount() > 0);
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) App.I.bottomFocus = false;
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        return handleMotion(e) || super.dispatchGenericMotionEvent(e);
    }

    boolean handleMotion(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && e.getAction() == MotionEvent.ACTION_MOVE) {
            float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            String d = null;
            boolean hatRecentlyKeyed = SystemClock.uptimeMillis() - lastDpadKey < 400;
            if (!hatRecentlyKeyed && (Math.abs(hx) > 0.5f || Math.abs(hy) > 0.5f)) {
                d = Math.abs(hx) > Math.abs(hy) ? (hx > 0 ? "right" : "left") : (hy > 0 ? "down" : "up");
            } else if (Math.abs(x) > 0.55f || Math.abs(y) > 0.55f) {
                d = Math.abs(x) > Math.abs(y) ? (x > 0 ? "right" : "left") : (y > 0 ? "down" : "up");
            }
            if (!Objects.equals(d, stickDir)) {
                stickDir = d;
                h.removeCallbacks(stickRepeat);
                if (d != null) {
                    key(d, false);
                    h.postDelayed(stickRepeat, 360);
                }
            }
            return true;
        }
        return false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        setTopVisible(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        hideSystemUi();
        if (refocusing) {
            refocusing = false;
            return;
        }
        App.I.store.returned();
        App.I.back(true);
        applyPerf();
        if (web != null) {
            web.resumeTimers();
            web.onResume();
        }
        js("window.CB&&CB.onResume()");
        ensureBottom();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        stickDir = null;
        h.removeCallbacks(stickRepeat);
        if (SystemClock.uptimeMillis() - refocusAt < 1000) return;
        App.I.topPaused();
        js("window.CB&&CB.onPause()");
        // onNewIntent runs between a pause and a resume; skip if we're already back. No
        // pauseTimers(): it is global and would also stop the bottom screen's page.
        h.post(() -> {
            if (web != null && !resumed) web.onPause();
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        setTopVisible(false);
    }

    private void setTopVisible(boolean on) {
        App.I.topVisible = on;
        BottomActivity b = App.I.bottom;
        if (b == null) return;
        b.setFocusable(on);
        b.js("window.Bottom&&Bottom.setTopVisible(" + on + ")");
    }

    /** Opens the bottom-screen companion if the system hasn't (it does at boot and on Home there). */
    private void ensureBottom() {
        if (App.I.bottom != null || !Screens.bottomEnabled(this)) return;
        Display d = Screens.bottom(this);
        if (d == null) return;
        try {
            startActivity(new Intent(this, BottomActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), Screens.on(d.getDisplayId()));
        } catch (Exception e) {
            Log.w(App.TAG, "could not open the bottom screen: " + e);
        }
    }

    /** Switches the bottom screen between the companion and the system's launcher, keeping the controller here. */
    void setBottomScreen(boolean on) {
        Screens.setBottomEnabled(this, on);
        if (on) {
            ensureBottom();
            return;
        }
        // The system's launcher takes the screen, and with it the focus, a moment after the companion closes.
        for (long ms : new long[] {1200, 2600}) {
            h.postDelayed(() -> {
                App.I.bottomFocus = true;
                focusTop();
            }, ms);
        }
    }

    /**
     * Makes this display the one that receives keys and the soft keyboard, after the bottom
     * screen took the system-wide focus. Each display keeps its own focused window, so
     * hasWindowFocus() can't tell, and moveTaskToFront() leaves the display order alone on the
     * Thor. Starting this activity again does reorder the displays; it costs a pause and resume,
     * which onPause and onResume skip.
     */
    void focusTop() {
        if (!resumed || !App.I.bottomFocus || SystemClock.uptimeMillis() - refocusAt < 1000) return;
        App.I.bottomFocus = false;
        refocusAt = SystemClock.uptimeMillis();
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(EXTRA_REFOCUS, true), Screens.top());
        } catch (Exception e) {
            refocusAt = 0;
            Log.w(App.TAG, "could not refocus the top screen: " + e);
        }
    }

    void showKeyboard(boolean on) {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm == null || web == null) return;
        if (on) {
            focusTop();
            web.requestFocus();
            h.postDelayed(() -> imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT), 400);
        } else {
            imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i != null && i.getBooleanExtra(EXTRA_REFOCUS, false)) {
            refocusing = !resumed;
            return;
        }
        if (i != null && i.hasCategory(Intent.CATEGORY_HOME)) js("window.CB&&CB.onHome()");
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) hideSystemUi();
    }

    @Override
    public void onBackPressed() {
        key("back", false);
    }

    void hideSystemUi() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void requestRuntimePermissions() {
        List<String> need = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        js("window.CB&&CB.reload()");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_TREE && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                App.I.store.put("romTree", u.toString());
                js("window.CB&&CB.toast('ROM folder access granted')");
            } catch (Exception e) {
                js("window.CB&&CB.toast('Could not keep access to that folder')");
            }
        }
        js("window.CB&&CB.reload()");
    }

    @Override
    protected void onDestroy() {
        if (App.I.top == this) App.I.top = null;
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
