package dev.anideck.app;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * Connecting AniList: the site's own AniList login in an overlay on the top screen. The user logs in on
 * AniList's page; once the site holds the AniList token, AniDeck takes it ({@link Library#offerToken}).
 */
final class Login {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final MainActivity a;
    private final FrameLayout root;
    private final String nonce = new BigInteger(130, new SecureRandom()).toString(32);
    private LinearLayout view;
    private WebView web;
    private boolean clicked;

    Login(MainActivity a, FrameLayout root) {
        this.a = a;
        this.root = root;
    }

    boolean isOpen() {
        return view != null;
    }

    void open() {
        if (view != null) return;
        clicked = false;
        view = new LinearLayout(a);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackgroundColor(0xFF0B0B12);
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(24), dp(10), dp(16), dp(10));
        TextView t = new TextView(a);
        t.setText("Connect AniList · log in on AniList's page below (B to cancel)");
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bar.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView close = new TextView(a);
        close.setText("✕   Cancel");
        close.setTextColor(Color.WHITE);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        close.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xE6181822);
        g.setCornerRadius(dp(20));
        close.setBackground(g);
        close.setOnClickListener(v -> close());
        bar.addView(close);
        view.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));

        web = new WebView(a);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setTextZoom(100);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(Color.BLACK);
        web.addJavascriptInterface(new Bridge(), "AniDeckLogin");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return r.isForMainFrame() && !Site.allowed(r.getUrl());
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (web == null || url == null || !Site.isSite(android.net.Uri.parse(url).getHost())) return;
                boolean click = !clicked;
                clicked = true;
                web.evaluateJavascript("(" + App.I.asset("www/login.js") + ")(\"" + nonce + "\", " + click + ")", null);
            }

            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                CoroBugs.rendererGone(a, "login", d.didCrash());
                web = null;
                a.runOnUiThread(Login.this::close);
                return true;
            }
        });
        view.addView(web, new LinearLayout.LayoutParams(MATCH, 0, 1));
        root.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
        view.bringToFront();
        web.requestFocus();
        web.loadUrl(Site.base());
    }

    void close() {
        if (view == null) return;
        root.removeView(view);
        if (web != null) web.destroy();
        web = null;
        view = null;
        a.loginClosed();
    }

    /** B: back a page on AniList, else cancel. */
    void back() {
        if (web != null && web.canGoBack()) web.goBack();
        else close();
    }

    private int dp(float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private final class Bridge {
        @JavascriptInterface public void token(String key, String jwt) {
            if (!nonce.equals(key) || jwt == null) return;
            App.I.library.login(jwt);
            App.I.main.post(() -> {
                a.showToast("AniList connected. Your lists are loading");
                close();
            });
        }

        @JavascriptInterface public void notice(String key, String text) {
            if (nonce.equals(key) && text != null) App.I.main.post(() -> a.showToast(text.length() > 100 ? text.substring(0, 100) : text));
        }
    }
}
