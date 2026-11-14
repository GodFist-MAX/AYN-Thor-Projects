package dev.anideck.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;
import java.security.SecureRandom;

/** The top screen: the site in a WebView, driven by touch, the controller and the bottom screen. */
public class MainActivity extends Activity implements Net.Listener {
    static final String EXTRA_OPEN = "dev.anideck.app.OPEN";
    static final String EXTRA_EP = "dev.anideck.app.EP";
    static final String EXTRA_TITLE = "dev.anideck.app.TITLE";
    /** A watch page path, for history entries whose AniList id wasn't known. */
    static final String EXTRA_PATH = "dev.anideck.app.PATH";
    private static final String EXTRA_REFOCUS = "dev.anideck.app.REFOCUS";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler h = new Handler(Looper.getMainLooper());
    /** Proves a bridge call came from inject.js in the site's main frame, not from an ad iframe. */
    private final String nonce = new BigInteger(130, new SecureRandom()).toString(32);
    private FrameLayout root;
    private WebView web;
    private View full;
    private WebChromeClient.CustomViewCallback fullDone;
    private TextView offline, toast, exitButton;
    private String inject;
    private JSONObject state = new JSONObject();
    private Session session;
    private boolean resumed, refocusing, loadFailed;
    private long refocusAt, lastBlockedNotice;
    /** A show to pick out of the site's search results once they load: {a: AniList id, ep}. */
    private JSONObject pendingFind;
    /** An episode opened from the bottom screen or a notification starts playing if it loads before this. */
    private long playBefore;
    /** Playback paused because the connection dropped, to pick up again once it's back. */
    private boolean resumeOnNet;
    private double resumeAt = -1;
    private String resumeUrl;
    private final Runnable checkResumed = this::checkResumed;
    private final Runnable hideToast = () -> toast.setVisibility(View.GONE);
    private final Runnable hideExit = () -> exitButton.setVisibility(View.GONE);
    private long escAt;
    /** The display this activity was last on. CrossBar's Now Playing can move it to the bottom screen to watch there. */
    private int displayId = Display.DEFAULT_DISPLAY;
    /** Whether fill-screen mode was already on when the episode moved to the bottom screen, which turns it on. */
    private boolean theaterBefore;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        App.I.top = this;
        devFlag(getIntent());
        inject = "(" + App.I.asset("www/inject.js") + ")(\"" + nonce + "\", (function () {\n"
                + App.I.asset("www/nav.js") + "\nreturn AniNav;\n})())";

        root = new FrameLayout(this) {
            @Override public boolean dispatchKeyEventPreIme(KeyEvent e) {
                return Pad.early(e) || super.dispatchKeyEventPreIme(e);
            }
        };
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setDefaultFocusHighlightEnabled(false);
        root.addView(web, new FrameLayout.LayoutParams(MATCH, MATCH));
        offline = pill(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0xF0402A08, 0xFFFFC46B);
        offline.setText("No internet. AniDeck is paused and picks up where you left off when Wi-Fi is back.");
        toast = pill(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0xE6181822, 0x33FFFFFF);
        exitButton = pill(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0xE6181822, 0x66FFFFFF);
        exitButton.setText("✕   Exit fill screen");
        exitButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        exitButton.setPadding(dp(24), dp(12), dp(24), dp(12));
        exitButton.setOnClickListener(v -> {
            exitButton.setVisibility(View.GONE);
            if (full != null) exitFull();
            else cmd(App.obj("c", "theater", "on", false));
        });
        setContentView(root);
        // Keys reach dispatchKeyEventPreIme only along the focus path.
        web.requestFocus();
        setUpWeb();

        session = new Session(this);
        App.I.net.listen(this);
        askForNotifications();
        if (!open(getIntent())) web.loadUrl(startUrl());
        if (App.I.pageCrashed) {
            App.I.pageCrashed = false;
            showToast("The page crashed, so AniDeck reloaded it");
        }
        if (!App.I.net.online()) offline.setVisibility(View.VISIBLE);
        App.I.library.syncIfStale();
        Shelf.get(this).refresh();
        hideSystemUi();
    }

    private void setUpWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setTextZoom(100);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(Color.BLACK);
        web.addJavascriptInterface(new Bridge(), "AniDeckTop");
        web.setDownloadListener((url, ua, disposition, mime, length) -> showToast("Downloads are turned off in AniDeck"));
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
    }

    /** The page the app was on last time, if it was the site. */
    private String startUrl() {
        String last = App.I.prefs.getString("lastUrl", null);
        if (last != null && Site.isSite(Uri.parse(last).getHost())) return last;
        return Site.base();
    }

    // ---- page script ----

    /** The page script runs on the site only, not on AniList's login pages. */
    private void injectNow(String url) {
        if (web != null && onSite(url)) web.evaluateJavascript(inject, null);
    }

    private static boolean onSite(String url) {
        return url != null && Site.isSite(Uri.parse(url).getHost());
    }

    /** Off the site (the AniList login) there's no page script, so the state says only where we are. */
    private void offSite(String url) {
        if (url == null || onSite(url) || url.equals(App.str(state, "url"))) return;
        String page = Site.isLogin(Uri.parse(url).getHost()) ? "login" : "external";
        onState(App.obj("url", url, "page", page, "player", JSONObject.NULL).toString());
    }

    JSONObject state() {
        return state;
    }

    void cmd(JSONObject c) {
        if (web != null) web.evaluateJavascript("window.__anideck&&__anideck.cmd(" + c + ")", null);
    }

    private void cmd(String name) {
        cmd(App.obj("c", name));
    }

    private boolean hasPlayer() {
        return state.optJSONObject("player") != null;
    }

    private void onState(String json) {
        JSONObject s;
        try {
            s = new JSONObject(json);
        } catch (JSONException e) {
            return;
        }
        state = s;
        App.I.state = json;
        session.update(s);
        Shelf.get(this).played(s);
        App.I.toBottom("onState", json);
        if (pendingFind != null && "search".equals(App.str(s, "page"))) {
            App.put(pendingFind, "c", "find");
            cmd(pendingFind);
            pendingFind = null;
        }
        JSONObject p = s.optJSONObject("player");
        if (playBefore > 0 && p != null && "watch".equals(App.str(s, "page"))) {
            if (SystemClock.uptimeMillis() < playBefore) cmd("autoplay");
            playBefore = 0;
        }
        if (resumeAt >= 0 && p != null && p.optDouble("d", 0) > 0 && App.str(s, "url").equals(resumeUrl)) {
            cmd(App.obj("c", "seek", "t", resumeAt, "play", true));
            resumeAt = -1;
        }
    }

    private final class Bridge {
        @JavascriptInterface public void state(String key, String json) {
            if (nonce.equals(key)) h.post(() -> onState(json));
        }

        @JavascriptInterface public void map(String key, String json) {
            if (nonce.equals(key)) App.I.library.learn(json);
        }

        @JavascriptInterface public void token(String key, String jwt) {
            if (nonce.equals(key)) App.I.library.offerToken(jwt);
        }

        @JavascriptInterface public void watched(String key, int id, int ep) {
            if (nonce.equals(key)) App.I.library.watched(id, ep);
        }

        @JavascriptInterface public void notice(String key, String text) {
            if (nonce.equals(key) && text != null) h.post(() -> showToast(text.length() > 90 ? text.substring(0, 90) : text));
        }

        @JavascriptInterface public String prefs(String key) {
            return nonce.equals(key) ? App.I.pagePrefs().toString() : "{}";
        }
    }

    // ---- navigation ----

    /** Opens a show from a notification or the bottom screen: its watch page, found by AniList id. */
    private boolean open(Intent i) {
        if (i == null) return false;
        int id = i.getIntExtra(EXTRA_OPEN, 0), ep = i.getIntExtra(EXTRA_EP, 0);
        String path = i.getStringExtra(EXTRA_PATH);
        if (id <= 0 && path != null && path.matches("/watch/[\\w.~%/-]+")) {
            playBefore = ep > 0 ? SystemClock.uptimeMillis() + 30000 : 0;
            go(path + (ep > 0 ? "?ep=" + ep : ""));
            return true;
        }
        if (id <= 0) return false;
        openAnime(id, ep, i.getStringExtra(EXTRA_TITLE));
        return true;
    }

    /** ep > 0 opens that episode, 0 the show's first page, and a negative ep its info page. */
    void openAnime(int id, int ep, String title) {
        playBefore = ep > 0 ? SystemClock.uptimeMillis() + 30000 : 0;
        String path = App.I.library.path(id);
        if (path != null) {
            web.loadUrl(Site.url(ep < 0 ? path.replaceFirst("^/watch/", "/info/") : path + (ep > 0 ? "?ep=" + ep : "")));
            return;
        }
        if (title == null || title.isEmpty()) {
            JSONObject e = App.I.library.entry(id);
            title = e == null ? null : App.str(e, "romaji");
        }
        if (title == null || title.isEmpty()) {
            showToast("Couldn't find that show on the site");
            return;
        }
        pendingFind = App.obj("a", id, "ep", ep);
        web.loadUrl(Site.search(title));
    }

    void go(String path) {
        web.loadUrl(Site.url(path));
    }

    void search(String query) {
        if (query != null && !query.trim().isEmpty()) web.loadUrl(Site.search(query.trim()));
    }

    private void blocked(Uri u) {
        Log.i(App.TAG, "blocked a jump to " + u.getHost());
        long now = SystemClock.uptimeMillis();
        if (now - lastBlockedNotice < 20000) return;
        lastBlockedNotice = now;
        showToast("Blocked a pop-up or redirect");
    }

    private final class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            if (!r.isForMainFrame() || Site.allowed(r.getUrl())) return false;
            blocked(r.getUrl());
            return true;
        }

        @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
            loadFailed = false;
            offSite(url);
        }

        @Override public void onPageCommitVisible(WebView v, String url) {
            injectNow(url);
        }

        @Override public void onPageFinished(WebView v, String url) {
            injectNow(url);
        }

        @Override public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
            injectNow(url);
            offSite(url);
            Uri u = Uri.parse(url);
            if (onSite(url) && !"/callback".equals(u.getPath())) App.I.prefs.edit().putString("lastUrl", url).apply();
        }

        @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (!r.isForMainFrame()) return;
            loadFailed = true;
            Log.w(App.TAG, "page failed to load: " + e.getDescription());
        }

        @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            Log.w(App.TAG, "the page's renderer stopped; reloading");
            CoroBugs.rendererGone(MainActivity.this, "top", d.didCrash());
            App.I.pageCrashed = true;
            root.removeView(web);
            web.destroy();
            web = null;
            recreate();
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override public void onShowCustomView(View view, CustomViewCallback done) {
            if (full != null) {
                done.onCustomViewHidden();
                return;
            }
            full = view;
            fullDone = done;
            root.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
            offline.bringToFront();
            toast.bringToFront();
            exitButton.bringToFront();
        }

        @Override public void onHideCustomView() {
            exitFull();
        }

        @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean userGesture, Message result) {
            blocked(Uri.parse("about:popup"));
            return false;
        }

        @Override public void onPermissionRequest(PermissionRequest r) {
            r.deny();
        }

        @Override public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }

        @Override public boolean onConsoleMessage(ConsoleMessage m) {
            if (App.I.prefs.getBoolean("dev", false)) Log.d(App.TAG, "console: " + m.message());
            return true;
        }
    }

    private void exitFull() {
        if (full == null) return;
        root.removeView(full);
        full = null;
        if (fullDone != null) fullDone.onCustomViewHidden();
        fullDone = null;
        web.requestFocus();
    }

    // ---- controller ----

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        return App.I.pad.key(e) || super.dispatchKeyEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        return App.I.pad.motion(e) || super.dispatchGenericMotionEvent(e);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            App.I.pad.touched(Pad.TOP);
            if (filling()) showExit();
        }
        return super.dispatchTouchEvent(e);
    }

    /** An episode fills the screen (fill-screen mode or the player's own fullscreen). */
    boolean filling() {
        return full != null || state.optBoolean("theater");
    }

    /** The touch way out of fill-screen mode, shown for a few seconds after touching the screen. */
    private void showExit() {
        exitButton.setVisibility(View.VISIBLE);
        exitButton.bringToFront();
        h.removeCallbacks(hideExit);
        h.postDelayed(hideExit, 3500);
    }

    /** D-pad and left stick on this screen: they move the highlight, or seek and change volume in fill-screen mode. */
    void nav(String dir) {
        boolean sideways = "left".equals(dir) || "right".equals(dir);
        if (filling()) {
            if (sideways && hasPlayer()) cmd(App.obj("c", "seekBy", "d", "left".equals(dir) ? -10 : 10));
            else if (!sideways) getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    "up".equals(dir) ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        } else if (sideways && state.optBoolean("typing")) {
            key("left".equals(dir) ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
        } else {
            cmd(App.obj("c", "nav", "d", dir));
        }
    }

    /** A: taps the highlighted item. With nothing highlighted it plays or pauses the episode. */
    void ok() {
        if (filling() || (!state.optBoolean("nav") && hasPlayer())) {
            if (hasPlayer()) cmd("toggle");
        } else if (state.optBoolean("typing")) {
            key(KeyEvent.KEYCODE_ENTER);
        } else {
            web.evaluateJavascript("window.__anideck&&__anideck.ok()", r -> {
                try {
                    JSONObject p = new JSONObject(r);
                    Pad.tap(web, (float) p.getDouble("x"), (float) p.getDouble("y"));
                } catch (JSONException ignored) {
                    // Nothing to tap: the highlight only just appeared, or A played the episode.
                }
            });
        }
    }

    void highlight(boolean show) {
        if (show && filling()) return;
        cmd(App.obj("c", "nav", "d", show ? "show" : "hide"));
    }

    void scrollPage(int dy) {
        if (!filling()) web.evaluateJavascript("scrollBy(0," + dy + ")", null);
    }

    /** Start, X, Y, L1/R1 and L2/R2 control the episode from either screen. */
    boolean playerKey(KeyEvent e) {
        if (!hasPlayer()) return false;
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, first = down && e.getRepeatCount() == 0;
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_START:
                if (first) cmd("toggle");
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (first) cmd("skip");
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (first) cmd("theater");
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (down) cmd(App.obj("c", "seekBy", "d", k == KeyEvent.KEYCODE_BUTTON_L1 ? -10 : 10));
                return true;
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2:
                if (first) cmd(k == KeyEvent.KEYCODE_BUTTON_L2 ? "prev" : "next");
                return true;
            default:
                return false;
        }
    }

    /** A key press straight to the page (caret moves, Enter, Escape), not through Pad. */
    private void key(int code) {
        long t = SystemClock.uptimeMillis();
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    /** B or Back: closes what's open (keyboard, menus, fill-screen) before going back a page. */
    void back() {
        JSONObject menu = state.optJSONObject("menu");
        long now = SystemClock.uptimeMillis();
        if (full != null) exitFull();
        else if (state.optBoolean("typing")) cmd("blur");
        else if (menu != null) cmd(App.obj("c", "menu", "label", App.str(menu, "label")));
        // Escape closes the site's menus; if one ignores it, the next B goes on.
        else if (state.optBoolean("popup") && now - escAt > 1500) {
            escAt = now;
            key(KeyEvent.KEYCODE_ESCAPE);
            // The site puts focus back on the menu's button, with its own outline next to the highlight.
            h.postDelayed(() -> cmd("blur"), 350);
        }
        else if (state.optBoolean("theater")) cmd(App.obj("c", "theater", "on", false));
        else if (!backOnSite()) {
            if (onSite(web.getUrl())) moveTaskToBack(true);
            else web.loadUrl(startUrl());
        }
    }

    /**
     * Goes back to the previous site page, past the login pages (AniList and the site's /callback).
     * WebView's goBack() and goBackOrForward() skip pages that were left without a tap (Chrome's
     * history intervention), as they are when the app logs in or opens a show from the bottom
     * screen; history.go() run by the page itself doesn't.
     */
    private boolean backOnSite() {
        WebBackForwardList list = web.copyBackForwardList();
        int cur = list.getCurrentIndex();
        String from = web.getUrl();
        for (int i = cur - 1; i >= 0; i--) {
            String url = list.getItemAtIndex(i).getUrl();
            if (onSite(url) && !"/callback".equals(Uri.parse(url).getPath())) {
                web.evaluateJavascript("history.go(" + (i - cur) + ")", null);
                h.postDelayed(() -> {
                    if (from != null && from.equals(web.getUrl())) web.loadUrl(url);
                }, 1500);
                return true;
            }
        }
        return false;
    }

    @Override public void onBackPressed() {
        back();
    }

    // ---- connection ----

    @Override public void onNet(boolean online) {
        offline.setVisibility(online ? View.GONE : View.VISIBLE);
        App.I.toBottom("onNet", String.valueOf(online));
        h.removeCallbacks(checkResumed);
        if (!online) {
            JSONObject p = state.optJSONObject("player");
            if (p != null && "playing".equals(App.str(p, "state"))) {
                resumeOnNet = true;
                cmd("pause");
            }
            return;
        }
        App.I.library.syncIfStale();
        if (loadFailed) {
            web.reload();
        } else if (resumeOnNet) {
            resumeOnNet = false;
            cmd("play");
            h.postDelayed(checkResumed, 8000);
        }
    }

    /** After a reconnect: a stream that gave up while offline reloads and continues at the same spot. */
    private void checkResumed() {
        JSONObject p = state.optJSONObject("player");
        if (p == null) return;
        String ps = App.str(p, "state");
        if ("playing".equals(ps)) return;
        if ("paused".equals(ps)) {
            cmd("play");
            return;
        }
        resumeAt = p.optDouble("t", 0);
        resumeUrl = App.str(state, "url");
        web.reload();
    }

    // ---- screens ----

    @Override protected void onStart() {
        super.onStart();
        App.I.topVisible = true;
        if (web != null) web.onResume();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        screenChanged();
    }

    private boolean onBottomScreen() {
        Display d = getDisplay();
        return d != null && d.getDisplayId() != Display.DEFAULT_DISPLAY;
    }

    /**
     * After a move between the screens. On the bottom screen the episode fills it and there's no
     * companion, which would cover it; back on the top both return to how they were.
     */
    private void screenChanged() {
        Display d = getDisplay();
        int id = d == null ? Display.DEFAULT_DISPLAY : d.getDisplayId();
        if (id == displayId) return;
        displayId = id;
        if (id != Display.DEFAULT_DISPLAY) {
            BottomActivity b = App.I.bottom;
            if (b != null) b.finish();
            theaterBefore = state.optBoolean("theater");
            if (!theaterBefore) cmd(App.obj("c", "theater", "on", true));
        } else {
            if (!theaterBefore && state.optBoolean("theater")) cmd(App.obj("c", "theater", "on", false));
            ensureBottom();
        }
        hideSystemUi();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        // When CrossBar moves the app to the other screen, onStart still sees the one it left.
        screenChanged();
        ensureBottom();
        hideSystemUi();
        if (refocusing) refocusing = false;
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override protected void onStop() {
        super.onStop();
        App.I.topVisible = false;
        if (isChangingConfigurations()) return;
        // Leaving the app or turning the screen off pauses the episode and hands the bottom screen back.
        cmd("pause");
        if (web != null) web.onPause();
        BottomActivity b = App.I.bottom;
        if (b != null) b.finish();
    }

    @Override protected void onDestroy() {
        App.I.net.unlisten(this);
        session.release();
        if (App.I.top == this) App.I.top = null;
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getBooleanExtra(EXTRA_REFOCUS, false)) {
            refocusing = !resumed;
            return;
        }
        devFlag(i);
        open(i);
    }

    private void ensureBottom() {
        if (App.I.bottom != null || onBottomScreen()) return;
        Display d = Screens.bottom(this);
        if (d == null) return;
        try {
            startActivity(new Intent(this, BottomActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), Screens.on(d.getDisplayId()));
        } catch (Exception e) {
            Log.w(App.TAG, "could not open the bottom screen: " + e);
        }
    }

    /**
     * Gives the controller and keyboard back to this screen after the bottom one took the
     * system-wide focus. Restarting this activity is what moves display focus on the Thor.
     */
    void focusTop() {
        if (!resumed || !App.I.bottomFocus || onBottomScreen() || SystemClock.uptimeMillis() - refocusAt < 1000) return;
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

    // ---- misc ----

    /** `adb shell am start -n dev.anideck.app/.MainActivity --ez dev true` turns on WebView debugging. */
    private void devFlag(Intent i) {
        if (i != null && i.hasExtra("dev")) App.I.prefs.edit().putBoolean("dev", i.getBooleanExtra("dev", false)).apply();
        WebView.setWebContentsDebuggingEnabled(App.I.prefs.getBoolean("dev", false));
    }

    private void askForNotifications() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                || App.I.prefs.getBoolean("askedNotify", false)) return;
        App.I.prefs.edit().putBoolean("askedNotify", true).apply();
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    void showToast(String text) {
        toast.setText(text);
        toast.setVisibility(View.VISIBLE);
        toast.bringToFront();
        h.removeCallbacks(hideToast);
        h.postDelayed(hideToast, 2800);
    }

    private TextView pill(int gravity, int background, int border) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(dp(18), dp(9), dp(18), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setColor(background);
        g.setStroke(dp(1), border);
        g.setCornerRadius(dp(20));
        t.setBackground(g);
        t.setElevation(dp(8));
        t.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP, gravity);
        lp.topMargin = lp.bottomMargin = dp(16);
        root.addView(t, lp);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) hideSystemUi();
    }

    private void hideSystemUi() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.hide(WindowInsets.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
}
