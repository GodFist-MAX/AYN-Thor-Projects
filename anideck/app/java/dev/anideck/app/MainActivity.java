package dev.anideck.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import dev.anideck.app.ext.Images;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * The top screen: AniDeck's own pages (sources, shows, episodes, library, downloads, settings) in a
 * WebView served from the app at https://app.anideck/, the {@link VideoPlayer} over them, and the AniList
 * login. Driven by touch, the controller ({@link Pad}) and the bottom screen.
 */
public class MainActivity extends Activity implements Net.Listener {
    static final String EXTRA_OPEN = "dev.anideck.app.OPEN";
    static final String EXTRA_EP = "dev.anideck.app.EP";
    static final String EXTRA_TITLE = "dev.anideck.app.TITLE";
    /** A show on the Thor (its key in {@link Shelf}); with EXTRA_RESUME it plays where it was left off. */
    static final String EXTRA_SHOW = "dev.anideck.app.SHOW";
    static final String EXTRA_RESUME = "dev.anideck.app.RESUME";
    /** A downloaded episode to play (its id in {@link Downloads}). */
    static final String EXTRA_DOWNLOAD = "dev.anideck.app.DOWNLOAD";
    /** A page of the app, like "#/downloads". */
    static final String EXTRA_ROUTE = "dev.anideck.app.ROUTE";
    private static final String EXTRA_REFOCUS = "dev.anideck.app.REFOCUS";
    static final String HOST = "app.anideck";
    private static final String HOME = "https://" + HOST + "/index.html";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final Map<String, String> TYPES = Map.of("html", "text/html", "js", "application/javascript",
            "css", "text/css", "svg", "image/svg+xml", "png", "image/png", "json", "application/json", "woff2", "font/woff2");

    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private TextView offline, toast;
    private VideoPlayer player;
    private Login login;
    private Session session;
    private boolean resumed, refocusing, pageReady;
    private long refocusAt;
    /** A page to show once the app's page has loaded. */
    private String pendingRoute;
    private final Runnable hideToast = () -> toast.setVisibility(View.GONE);
    /** The display this activity was last on. CrossBar's Now Playing can move it to the bottom screen to watch there. */
    private int displayId = Display.DEFAULT_DISPLAY;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        App.I.top = this;
        devFlag(getIntent());
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
        offline.setText("No internet. Downloaded episodes still play, in Downloads.");
        toast = pill(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0xE6181822, 0x33FFFFFF);
        setContentView(root);
        // Keys reach dispatchKeyEventPreIme only along the focus path.
        web.requestFocus();
        setUpWeb();
        player = new VideoPlayer(this, root);
        login = new Login(this, root);
        session = new Session(this);
        App.I.net.listen(this);
        askForNotifications();
        web.loadUrl(HOME);
        open(getIntent());
        if (App.I.pageCrashed) {
            App.I.pageCrashed = false;
            showToast("The page crashed, so AniDeck reloaded it");
        }
        offline.setVisibility(App.I.net.online() ? View.GONE : View.VISIBLE);
        App.I.library.syncIfStale();
        Downloads.get(this).run();
        hideSystemUi();
    }

    private void setUpWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setTextZoom(100);
        web.setBackgroundColor(0xFF0B0B12);
        web.addJavascriptInterface(new Bridge(), "AniDeck");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                if (App.I.prefs.getBoolean("dev", false)) Log.d(App.TAG, "top: " + m.message() + " (" + m.lineNumber() + ")");
                return true;
            }
        });
    }

    // ---- the app's pages ----

    /** Calls window.app.on(event, data) in the top screen's page. */
    void toTop(String event, String json) {
        h.post(() -> {
            if (web != null && pageReady) web.evaluateJavascript("window.app&&app.on(" + JSONObject.quote(event) + "," + json + ")", null);
        });
    }

    /** Shows a page of the app (a route like "#/show/..."). */
    void route(String r) {
        if (r == null || !r.startsWith("#/")) return;
        closePlayer();
        if (login.isOpen()) login.close();
        if (!pageReady) {
            pendingRoute = r;
            return;
        }
        web.evaluateJavascript("window.app&&app.go(" + JSONObject.quote(r) + ")", null);
    }

    private final class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            return !HOST.equals(r.getUrl().getHost());
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (!HOST.equals(u.getHost()) || !"https".equals(u.getScheme())) return blocked();
            List<String> seg = u.getPathSegments();
            try {
                if (seg.size() == 2 && "img".equals(seg.get(0))) {
                    String src = u.getQueryParameter("u");
                    File f = src == null ? null : Images.get(seg.get(1), src);
                    return f == null ? notFound() : image(f);
                }
                if (seg.size() == 2 && "cover".equals(seg.get(0))) {
                    File f = Shelf.get(MainActivity.this).coverFile(seg.get(1));
                    return f == null || !f.isFile() ? notFound() : image(f);
                }
                String path = u.getPath() == null || "/".equals(u.getPath()) ? "/index.html" : u.getPath();
                if (path.contains("..")) return notFound();
                String asset = "/nav.js".equals(path) ? "www/nav.js" : "www/app" + path;
                String ext = path.substring(path.lastIndexOf('.') + 1);
                InputStream in = getAssets().open(asset);
                WebResourceResponse res = new WebResourceResponse(TYPES.getOrDefault(ext, "application/octet-stream"), "utf-8", in);
                res.setResponseHeaders(Map.of("Cache-Control", "no-store"));
                return res;
            } catch (IOException e) {
                return notFound();
            }
        }

        @Override public void onPageFinished(WebView v, String url) {
            pageReady = true;
            if (pendingRoute != null) {
                String r = pendingRoute;
                pendingRoute = null;
                route(r);
            }
        }

        @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            Log.w(App.TAG, "the top screen's renderer stopped; reloading");
            CoroBugs.rendererGone(MainActivity.this, "top", d.didCrash());
            App.I.pageCrashed = true;
            root.removeView(web);
            web.destroy();
            web = null;
            recreate();
            return true;
        }
    }

    private static WebResourceResponse image(File f) throws IOException {
        WebResourceResponse res = new WebResourceResponse("image/jpeg", null, new FileInputStream(f));
        res.setResponseHeaders(Map.of("Cache-Control", "max-age=86400"));
        return res;
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", null, 404, "Not Found", Map.of(), new ByteArrayInputStream(new byte[0]));
    }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", null, 403, "Forbidden", Map.of(), new ByteArrayInputStream(new byte[0]));
    }

    /** What the page's JavaScript can ask for. Only AniDeck's own page is ever loaded in this WebView. */
    private final class Bridge {
        /** Answers arrive later through app.reply(id, json). */
        @JavascriptInterface public void call(int id, String method, String args) {
            App.I.exec.execute(() -> {
                String json = Api.call(method, args);
                h.post(() -> {
                    if (web != null) web.evaluateJavascript("window.app&&app.reply(" + id + "," + json + ")", null);
                });
            });
        }

        @JavascriptInterface public void play(String json) {
            h.post(() -> {
                try {
                    playItem(new JSONObject(json));
                } catch (JSONException e) {
                    showToast("Couldn't open that episode");
                }
            });
        }

        @JavascriptInterface public void playDownload(String id) {
            h.post(() -> MainActivity.this.playDownload(id));
        }

        @JavascriptInterface public void toast(String text) {
            if (text != null) h.post(() -> showToast(text.length() > 140 ? text.substring(0, 140) : text));
        }

        /** A real tap for the controller's A on a text field, so the keyboard opens. */
        @JavascriptInterface public void tap(float x, float y) {
            h.post(() -> {
                if (web != null) Pad.tap(web, x, y);
            });
        }

        @JavascriptInterface public void login() {
            h.post(MainActivity.this::startLogin);
        }

        @JavascriptInterface public void signOut() {
            App.I.library.signOut();
        }

        @JavascriptInterface public void useName(String name) {
            App.I.library.useName(name);
        }

        @JavascriptInterface public void exit() {
            h.post(() -> moveTaskToBack(true));
        }

        @JavascriptInterface public String info() {
            return App.obj("version", App.I.versionName(), "net", App.I.net.online(), "dev", App.I.prefs.getBoolean("dev", false),
                    "bottom", App.I.bottom != null).toString();
        }
    }

    // ---- playing ----

    /** From the page: {source, sourceName, anime, episodes, index, a}. A downloaded copy plays from the Thor. */
    void playItem(JSONObject item) {
        if (login.isOpen()) login.close();
        String key = Shelf.key(App.str(item, "source"), App.str(item.optJSONObject("anime"), "url"));
        App.put(item, "key", key);
        JSONObject ep = item.optJSONArray("episodes") == null ? null : item.optJSONArray("episodes").optJSONObject(item.optInt("index"));
        JSONObject d = ep == null ? null : Downloads.get(this).find(key, App.str(ep, "url"));
        offline.setVisibility(View.GONE);
        if (d != null && "done".equals(App.str(d, "state"))) player.openDownload(d);
        else player.open(item);
        App.I.pad.use(Pad.TOP, false);
    }

    /** Plays a downloaded episode, from either screen, a notification or CrossBar. */
    void playDownload(String id) {
        JSONObject r = Downloads.get(this).get(id);
        if (r == null || !"done".equals(App.str(r, "state"))) {
            showToast("That download isn't on the Thor anymore");
            return;
        }
        if (login.isOpen()) login.close();
        offline.setVisibility(View.GONE);
        player.openDownload(r);
    }

    /** B or Close: back to the page underneath. */
    void closePlayer() {
        if (!player.isOpen()) return;
        player.close();
        playerState(App.obj("page", "browse", "player", JSONObject.NULL));
        toTop("playerClosed", "{}");
        if (!App.I.net.online()) offline.setVisibility(View.VISIBLE);
        if (web != null) web.requestFocus();
    }

    /** The player's state, for the bottom screen, the media controls and CrossBar. */
    void playerState(JSONObject s) {
        String json = s.toString();
        App.I.state = json;
        session.update(s);
        App.I.toBottom("onState", json);
    }

    JSONObject state() {
        try {
            return new JSONObject(App.I.state);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** A command for the episode on screen, from the bottom screen or the media controls. */
    void cmd(JSONObject c) {
        player.cmd(c);
    }

    /** AniList login, from Settings on either screen. */
    void startLogin() {
        if (player.isOpen()) closePlayer();
        login.open();
    }

    void loginClosed() {
        if (web != null) web.requestFocus();
    }

    // ---- opening things ----

    private void open(Intent i) {
        if (i == null) return;
        String download = i.getStringExtra(EXTRA_DOWNLOAD);
        String show = i.getStringExtra(EXTRA_SHOW);
        String route = i.getStringExtra(EXTRA_ROUTE);
        int id = i.getIntExtra(EXTRA_OPEN, 0);
        if (download != null) playDownload(download);
        else if (show != null) openShow(show, i.getBooleanExtra(EXTRA_RESUME, false));
        else if (id > 0) openAniList(id, i.getIntExtra(EXTRA_EP, 0), i.getStringExtra(EXTRA_TITLE));
        else if (route != null) route(route);
    }

    /** A show from the history or saved shows; resume plays its last episode where it stopped, or the next one. */
    void openShow(String key, boolean resume) {
        JSONObject s = Shelf.get(this).show(key);
        if (s == null) {
            showToast("That show isn't on the Thor anymore");
            return;
        }
        if (App.str(s, "source").isEmpty()) {
            JSONObject last = s.optJSONObject("last");
            int ep = last == null ? 0 : (int) last.optDouble("num", 0);
            openAniList(s.optInt("a"), resume ? ep : 0, App.str(s, "title"));
            return;
        }
        route("#/show/" + App.str(s, "source") + "/" + Uri.encode(App.str(s, "url")) + (resume ? "?resume=1" : ""));
    }

    /** A show from AniList (lists, notifications): its linked show, else a search of the sources. ep > 0 plays that episode. */
    void openAniList(int id, int ep, String title) {
        String key = Shelf.get(this).keyFor(id);
        if (key != null) {
            JSONObject s = Shelf.get(this).show(key);
            route("#/show/" + App.str(s, "source") + "/" + Uri.encode(App.str(s, "url")) + (ep > 0 ? "?ep=" + ep : ""));
            return;
        }
        StringBuilder q = new StringBuilder();
        if (ep > 0) q.append("ep=").append(ep);
        if (title != null && !title.isEmpty()) q.append(q.length() > 0 ? "&" : "").append("title=").append(Uri.encode(title));
        route("#/anilist/" + id + (q.length() > 0 ? "?" + q : ""));
    }

    void search(String query) {
        if (query != null && !query.trim().isEmpty()) route("#/search?q=" + Uri.encode(query.trim()));
    }

    // ---- controller ----

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        return App.I.pad.key(e) || super.dispatchKeyEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        return App.I.pad.motion(e) || super.dispatchGenericMotionEvent(e);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) App.I.pad.touched(Pad.TOP);
        return super.dispatchTouchEvent(e);
    }

    /** An episode fills the screen, so the controller stays with it. */
    boolean filling() {
        return player.isOpen();
    }

    /** D-pad and left stick on this screen: the page's highlight, the player's menu, or seek and volume. */
    void nav(String dir) {
        if (login.isOpen()) return;
        if (player.isOpen()) {
            if (player.nav(dir)) return;
            boolean sideways = "left".equals(dir) || "right".equals(dir);
            if (sideways) player.cmd(App.obj("c", "seekBy", "d", "left".equals(dir) ? -10 : 10));
            else getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    "up".equals(dir) ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            return;
        }
        js("app.nav(" + JSONObject.quote(dir) + ")");
    }

    /** A: chooses the highlighted item, or plays and pauses. */
    void ok() {
        if (login.isOpen()) return;
        if (player.isOpen()) player.ok();
        else js("app.ok()");
    }

    void highlight(boolean show) {
        if (!player.isOpen() && !login.isOpen()) js("app.pad(" + show + ")");
    }

    void scrollPage(int dy) {
        if (!player.isOpen() && !login.isOpen()) js("app.scroll(" + dy + ")");
    }

    /** Start, X, Y, L1/R1 and L2/R2 control the episode from either screen. */
    boolean playerKey(KeyEvent e) {
        if (!player.isOpen()) return false;
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, first = down && e.getRepeatCount() == 0;
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_START:
                if (first) player.cmd(App.obj("c", "toggle"));
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (first) player.cmd(App.obj("c", "skip"));
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (first) player.cmd(App.obj("c", "menu"));
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (down) player.cmd(App.obj("c", "seekBy", "d", k == KeyEvent.KEYCODE_BUTTON_L1 ? -10 : 10));
                return true;
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2:
                if (first) player.cmd(App.obj("c", k == KeyEvent.KEYCODE_BUTTON_L2 ? "prev" : "next"));
                return true;
            default:
                return false;
        }
    }

    /** B or Back: the player's menu, the player, the login, then the page's own back; at Home it leaves the app. */
    void back() {
        if (player.isOpen()) {
            if (!player.back()) closePlayer();
        } else if (login.isOpen()) {
            login.back();
        } else if (web != null) {
            web.evaluateJavascript("window.app?app.back():false", r -> {
                if (!"true".equals(r)) moveTaskToBack(true);
            });
        }
    }

    @Override public void onBackPressed() {
        back();
    }

    private void js(String code) {
        if (web != null && pageReady) web.evaluateJavascript("window.app&&" + code, null);
    }

    // ---- connection ----

    @Override public void onNet(boolean online) {
        offline.setVisibility(online || player.isOpen() ? View.GONE : View.VISIBLE);
        App.I.toBottom("onNet", String.valueOf(online));
        toTop("net", String.valueOf(online));
        if (online) {
            App.I.library.syncIfStale();
            player.onNet(true);
        }
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

    /** On the bottom screen (moved there by CrossBar) there's no companion, which would cover it. */
    private void screenChanged() {
        Display d = getDisplay();
        int id = d == null ? Display.DEFAULT_DISPLAY : d.getDisplayId();
        if (id == displayId) return;
        displayId = id;
        if (id != Display.DEFAULT_DISPLAY) {
            BottomActivity b = App.I.bottom;
            if (b != null) b.finish();
        } else {
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
        player.cmd(App.obj("c", "pause"));
        if (web != null) web.onPause();
        BottomActivity b = App.I.bottom;
        if (b != null) b.finish();
    }

    @Override protected void onDestroy() {
        App.I.net.unlisten(this);
        player.close();
        login.close();
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
