package dev.crossbar.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * The bottom screen's home: a touch companion that follows the selection on the top screen.
 * Registered as SECONDARY_HOME, so the system starts it on the second display while CrossBar
 * is the default home. Controller input that lands here is passed to the top screen's menu,
 * or, while a game or app covers the menu, the focus goes back up to it.
 */
public class BottomActivity extends Activity {
    private FramePacer root;
    private WebView web;
    private final Handler h = new Handler(Looper.getMainLooper());
    /** Whether this is the system's top resumed activity: this screen holds the system-wide key focus. */
    private boolean topResumed;
    private long focusReturnedAt;
    private final Runnable refocus = this::returnFocus;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        root = new FramePacer(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        applyPerf();
        createWebView();
        hideSystemUi();
        // After an update MainActivity.ensureBottom starts the companion as an ordinary task, and Home
        // on this screen later starts the system's own home task: keep only the newest.
        BottomActivity old = App.I.bottom;
        App.I.bottom = this;
        if (old != null && old != this) old.finish();
        updateFocusable();
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
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new BottomBridge(this), "NativeB");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !"127.0.0.1".equals(r.getUrl().getHost());
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                Log.w(App.TAG, "bottom WebView renderer gone; recreating");
                CoroBugs.rendererGone(BottomActivity.this, "bottom", d.didCrash());
                h.post(() -> createWebView());
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(App.TAG, "bottom js " + m.messageLevel() + ": " + m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        load();
    }

    void load() {
        LocalServer srv = App.I.server;
        web.loadUrl(srv.url("/app/bottom.html?t=" + srv.token));
    }

    void js(String code) {
        h.post(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    void push(String json) {
        js("window.Bottom&&Bottom.update(" + json + ")");
    }

    /**
     * While a game runs on the top screen this window must not take input focus, or a touch on
     * the bottom screen would leave the game without controller input. It stays focusable while
     * the menu is up, and while this screen holds the system-wide focus anyway (the Thor's
     * dual-screen panel was touched, Home was pressed down here, or CrossBar restarted): an
     * unfocusable home activity on the focused display swallows all keys, and after 5 seconds
     * the system reports CrossBar as not responding.
     */
    void updateFocusable() {
        h.post(() -> {
            if (App.I.topVisible || topResumed) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            else getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        });
    }

    /**
     * Gives the system-wide focus back to the game or app covering the menu, once this screen
     * holds it and nothing else down here takes keys. Not while the top screen is off: an
     * activity started there then can't take the focus, and keys would wait for it instead.
     */
    private void returnFocus() {
        if (App.I.topVisible || !topResumed || !hasWindowFocus() || !Screens.topOn(this)) return;
        long now = SystemClock.uptimeMillis();
        if (now - focusReturnedAt < 1000) return;
        focusReturnedAt = now;
        try {
            startActivity(new Intent(this, FocusActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_NO_USER_ACTION), Screens.top());
        } catch (Exception e) {
            Log.w(App.TAG, "could not give the top screen its focus back: " + e);
        }
    }

    @Override
    public void onTopResumedActivityChanged(boolean top) {
        super.onTopResumedActivityChanged(top);
        topResumed = top;
        updateFocusable();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && App.I.topVisible) App.I.bottomFocus = true;
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        App.I.bottomFocus = true;
        MainActivity m = App.I.top;
        if (m != null && App.I.topVisible && m.handleKey(e)) return true;
        int code = e.getKeyCode();
        if (!App.I.topVisible && MainActivity.map(code) != null && !KeyEvent.isMediaSessionKey(code)) {
            // The controller belongs to the game or app up there; it mustn't press anything on this page.
            if (e.getAction() == KeyEvent.ACTION_DOWN) returnFocus();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        MainActivity m = App.I.top;
        if (m != null && App.I.topVisible && m.handleMotion(e)) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    @Override
    public void onBackPressed() {
        // Back from a controller is forwarded in dispatchKeyEvent; a home screen never closes.
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i == null || !i.hasCategory(Intent.CATEGORY_SECONDARY_HOME)) return;
        js("window.Bottom&&Bottom.onHome()");
        MainActivity m = App.I.top;
        if (m != null && App.I.topVisible) m.js("window.CB&&CB.onHome()");
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (web != null) web.onResume();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        applyPerf();
        App.I.back(false);
        // Starting here moves system-wide focus to this display; give it back to the menu.
        App.I.bottomFocus = true;
        h.postDelayed(() -> {
            MainActivity m = App.I.top;
            if (m != null && App.I.topVisible) m.focusTop();
        }, 300);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (web != null) web.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus) return;
        hideSystemUi();
        h.removeCallbacks(refocus);
        h.postDelayed(refocus, 300);
    }

    private void hideSystemUi() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    protected void onDestroy() {
        if (App.I.bottom == this) App.I.bottom = null;
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
