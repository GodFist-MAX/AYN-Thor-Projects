package dev.anideck.app;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import dev.anideck.app.ext.Images;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** The bottom screen: a touch companion (player controls, lists, schedule, downloads, settings). */
public class BottomActivity extends Activity {
    private static final String SHELF_HOST = "shelf.anideck";

    private final Handler h = new Handler(Looper.getMainLooper());
    private WebView web;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        App.I.bottom = this;
        web = new WebView(this) {
            @Override public boolean dispatchKeyEventPreIme(KeyEvent e) {
                return Pad.early(e) || super.dispatchKeyEventPreIme(e);
            }
        };
        web.setDefaultFocusHighlightEnabled(false);
        web.setBackgroundColor(0xFF0B0B12);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        web.addJavascriptInterface(new Deck(), "Deck");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true;
            }

            /** Covers of the shows on the Thor come from the app, so they show offline. */
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!SHELF_HOST.equals(u.getHost())) return null;
                List<String> seg = u.getPathSegments();
                File f = null;
                if (seg.size() == 2 && "cover".equals(seg.get(0))) f = Shelf.get(BottomActivity.this).coverFile(seg.get(1));
                else if (seg.size() == 2 && "img".equals(seg.get(0)) && u.getQueryParameter("u") != null) {
                    f = Images.get(seg.get(1), u.getQueryParameter("u"));
                }
                try {
                    if (f != null && f.isFile()) return new WebResourceResponse("image/jpeg", null, new FileInputStream(f));
                } catch (IOException ignored) {
                }
                return new WebResourceResponse("text/plain", null, 404, "Not Found", Map.of(), new ByteArrayInputStream(new byte[0]));
            }

            /** Both screens share one renderer; if either WebView leaves this unhandled, Android kills the app. */
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                Log.w(App.TAG, "the bottom screen's renderer stopped; reloading");
                CoroBugs.rendererGone(BottomActivity.this, "bottom", d.didCrash());
                ((ViewGroup) web.getParent()).removeView(web);
                web.destroy();
                web = null;
                recreate();
                return true;
            }
        });
        setContentView(web);
        web.requestFocus();
        web.loadUrl("file:///android_asset/www/bottom.html");
        hideSystemUi();
    }

    void js(String code) {
        h.post(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            App.I.bottomFocus = true;
            App.I.pad.touched(Pad.BOTTOM);
        }
        return super.dispatchTouchEvent(e);
    }

    /** Controller input lands on whichever screen has the system focus; Pad sends it where it belongs. */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        return App.I.pad.key(e) || super.dispatchKeyEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        return App.I.pad.motion(e) || super.dispatchGenericMotionEvent(e);
    }

    @Override public void onBackPressed() {
        js("window.deck&&deck.onBack()");
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
        // Starting here moves the system-wide focus to this display; give it back to the top screen.
        App.I.bottomFocus = true;
        h.postDelayed(() -> {
            MainActivity m = App.I.top;
            if (m != null && App.I.topVisible) m.focusTop();
        }, 300);
    }

    @Override protected void onDestroy() {
        if (App.I.bottom == this) App.I.bottom = null;
        if (web != null) web.destroy();
        web = null;
        super.onDestroy();
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

    private void top(Consumer<MainActivity> f) {
        h.post(() -> {
            MainActivity m = App.I.top;
            if (m != null) f.accept(m);
        });
    }

    /** Opens something on the top screen, which takes the controller with it. */
    private void topPage(Consumer<MainActivity> f) {
        top(m -> {
            f.accept(m);
            App.I.pad.use(Pad.TOP, false);
        });
    }

    private final class Deck {
        @JavascriptInterface public String init() {
            JSONObject state, library;
            JSONArray episodes;
            try {
                state = new JSONObject(App.I.state);
                library = new JSONObject(App.I.library.json());
                episodes = new JSONArray(App.I.episodes);
            } catch (JSONException e) {
                state = new JSONObject();
                library = new JSONObject();
                episodes = new JSONArray();
            }
            return App.obj("state", state, "library", library, "net", App.I.net.online(),
                    "settings", App.I.settings(), "version", App.I.versionName(),
                    "shelf", Shelf.get(BottomActivity.this).json(), "episodes", episodes,
                    "downloads", Downloads.get(BottomActivity.this).json()).toString();
        }

        /** The Downloads tab asks for progress while episodes download. */
        @JavascriptInterface public String downloads() {
            return Downloads.get(BottomActivity.this).json().toString();
        }

        @JavascriptInterface public void playDownload(String id) {
            if (id != null) topPage(m -> m.playDownload(id));
        }

        @JavascriptInterface public void closePlayer() {
            top(MainActivity::closePlayer);
        }

        /** Cancel on a download that's going, Delete on one that's done. */
        @JavascriptInterface public void deleteDownload(String id) {
            if (id != null) h.post(() -> Downloads.get(BottomActivity.this).delete(id));
        }

        @JavascriptInterface public void retryDownload(String id) {
            if (id != null) h.post(() -> Downloads.get(BottomActivity.this).retry(id));
        }

        /** "Save show" on Now Playing. */
        @JavascriptInterface public void save(String key, boolean on) {
            if (key != null) Shelf.get(BottomActivity.this).save(key, on);
        }

        @JavascriptInterface public void forget(String key) {
            if (key != null) Shelf.get(BottomActivity.this).forget(key);
        }

        @JavascriptInterface public void clearRecent() {
            Shelf.get(BottomActivity.this).clearRecent();
        }

        @JavascriptInterface public void cmd(String json) {
            top(m -> {
                try {
                    m.cmd(new JSONObject(json));
                } catch (JSONException ignored) {
                }
            });
        }

        /** A show from the AniList lists: its show on a source, found by title if it isn't linked yet. */
        @JavascriptInterface public void open(int id, int ep, String title) {
            topPage(m -> m.openAniList(id, ep, title));
        }

        /** A show on the Thor (history, saved); resume plays where it was left off. */
        @JavascriptInterface public void openShow(String key, boolean resume) {
            if (key != null) topPage(m -> m.openShow(key, resume));
        }

        /** A page of the top screen's app, like "#/downloads". */
        @JavascriptInterface public void go(String route) {
            if (route != null && route.startsWith("#/")) topPage(m -> m.route(route));
        }

        @JavascriptInterface public void search(String query) {
            topPage(m -> m.search(query));
        }

        @JavascriptInterface public void login() {
            topPage(MainActivity::startLogin);
        }

        @JavascriptInterface public void sync() {
            App.I.library.sync(true);
        }

        @JavascriptInterface public void media(int id) {
            App.I.library.media(id);
        }

        @JavascriptInterface public void set(String key, boolean on) {
            h.post(() -> App.I.set(key, on));
        }

        @JavascriptInterface public void choose(String key, String value) {
            h.post(() -> App.I.set(key, value));
        }

        /** A real tap for the controller's A on a text field, so the keyboard opens. */
        @JavascriptInterface public void tap(float x, float y) {
            h.post(() -> {
                if (web != null) Pad.tap(web, x, y);
            });
        }

        @JavascriptInterface public void padTop() {
            h.post(() -> App.I.pad.use(Pad.TOP, true));
        }

        @JavascriptInterface public void useName(String name) {
            App.I.library.useName(name);
        }

        @JavascriptInterface public void signOut() {
            App.I.library.signOut();
        }
    }
}
