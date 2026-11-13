package dev.grokbuild.coronamon;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;

/** Coronamon's den: the apps he looks after, what happened lately, crash reports and settings. */
public final class DenActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        PetService.start(this);
        web = new WebView(this);
        web.setBackgroundColor(0xFF1B0E0A);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("https".equals(u.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) {
                    }
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Js(), "Coro");
        web.loadUrl("file:///android_asset/den/index.html");
        setContentView(web);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.den && den.refresh()", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_A) {
            return super.dispatchKeyEvent(new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), KeyEvent.KEYCODE_ENTER,
                    e.getRepeatCount(), e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource()));
        }
        if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) back();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                web.evaluateJavascript("den.tab(" + (code == KeyEvent.KEYCODE_BUTTON_L1 ? -1 : 1) + ")", null);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void back() {
        web.evaluateJavascript("den.back()", r -> {
            if (!"true".equals(r)) finish();
        });
    }

    private interface Call {
        Object run(PetService s) throws Exception;
    }

    private static String call(Call c) {
        try {
            JSONObject r = Bridge.onMain(() -> {
                PetService s = PetService.instance;
                if (s == null) return Util.obj("ok", false, "error", "Coronamon is waking up");
                Object o = c.run(s);
                return Util.obj("ok", true, "data", o == null ? JSONObject.NULL : o);
            });
            return r.toString();
        } catch (Exception e) {
            return Util.obj("ok", false, "error", String.valueOf(e.getCause() != null ? e.getCause() : e)).toString();
        }
    }

    private final class Js {
        @JavascriptInterface
        public String status() {
            return call(PetService::status);
        }

        @JavascriptInterface
        public String apps() {
            return call(s -> s.registry.liveAll());
        }

        @JavascriptInterface
        public String history(int n) {
            return call(s -> s.history.recent(n));
        }

        @JavascriptInterface
        public String reports(String pkg) {
            return call(s -> CrashWatch.list(pkg == null || pkg.isEmpty() ? null : pkg, 40));
        }

        @JavascriptInterface
        public String report(String path) {
            return call(s -> {
                File f = new File(path).getCanonicalFile();
                if (!f.getPath().startsWith(Paths.REPORTS.getCanonicalPath() + "/")) throw new SecurityException("not a report");
                String t = Util.readText(f);
                return t == null ? "" : t;
            });
        }

        @JavascriptInterface
        public String settings() {
            return call(s -> s.settings.toJson());
        }

        @JavascriptInterface
        public String set(String key, String value) {
            return call(s -> {
                if (!s.settings.set(key, value)) throw new IllegalArgumentException("bad value for " + key);
                if ("size".equals(key)) s.sizeChanged();
                if ("visible".equals(key)) s.setVisible(s.settings.visible());
                return s.settings.toJson();
            });
        }

        @JavascriptInterface
        public String icon(String pkg) {
            return call(s -> {
                try {
                    return Util.pngDataUrl(Util.toBitmap(getPackageManager().getApplicationIcon(pkg), 96));
                } catch (PackageManager.NameNotFoundException e) {
                    return "";
                }
            });
        }

        @JavascriptInterface
        public String avatar(String move) {
            return call(s -> {
                Pose p = new Pose();
                Moves.Move m = Moves.get(move == null || move.isEmpty() ? "idle" : move);
                Actor a = new Actor();
                if (m != null) m.apply(p, 0.6f, a);
                p.v[Pose.SHADOW] = 0;
                Bitmap b = Bitmap.createBitmap(240, 300, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                c.translate(120, 292);
                c.scale(1.02f, 1.02f);
                AndroidGfx g = new AndroidGfx();
                g.begin(c);
                new Puppet().draw(g, p, 0.6f);
                return Util.pngDataUrl(b);
            });
        }

        @JavascriptInterface
        public String inbox() {
            return call(s -> {
                JSONArray out = new JSONArray();
                for (File dir : new File[]{Paths.INBOX, new File("/sdcard/Download")}) {
                    File[] fs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".apk"));
                    if (fs == null) continue;
                    Arrays.sort(fs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
                    for (File f : fs) {
                        Installer.Apk a = Installer.inspect(s, f);
                        JSONObject o = Util.obj("path", f.getPath(), "name", f.getName(), "size", f.length(),
                                "modified", f.lastModified(), "folder", dir.getPath());
                        if (a != null) {
                            Util.put(o, "pkg", a.pkg);
                            Util.put(o, "label", a.label);
                            Util.put(o, "version", a.versionName);
                            String have = s.registry.live(a.pkg).optString("versionName", "");
                            if (!have.isEmpty()) Util.put(o, "installed", have);
                        }
                        out.put(o);
                    }
                }
                return out;
            });
        }

        @JavascriptInterface
        public String install(String path) {
            return call(s -> {
                File f = new File(path);
                if (!f.isFile()) throw new IllegalArgumentException("missing " + path);
                s.installFile(f, "den", null);
                return true;
            });
        }

        @JavascriptInterface
        public String check(String pkg) {
            return call(s -> {
                if (pkg == null || pkg.isEmpty()) s.updates.checkAll(true);
                else s.updates.checkOne(pkg);
                return true;
            });
        }

        @JavascriptInterface
        public String update(String pkg) {
            return call(s -> {
                s.updates.fetch(pkg);
                return true;
            });
        }

        @JavascriptInterface
        public String open(String pkg) {
            return call(s -> {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) throw new IllegalStateException("it has no launcher screen");
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            });
        }

        @JavascriptInterface
        public String info(String pkg) {
            return call(s -> {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            });
        }

        @JavascriptInterface
        public String summon(String screen) {
            return call(s -> {
                s.showNow();
                int i = s.stageIndexOf(screen);
                if (i >= 0) s.summon(i);
                return true;
            });
        }

        @JavascriptInterface
        public String act(String move) {
            return call(s -> s.brain.act(move, 0));
        }

        @JavascriptInterface
        public String say(String key) {
            return call(s -> {
                Lines.Line l = s.lines.pick(key, null);
                if (l != null) s.say(l, null, null);
                return l == null ? null : l.text;
            });
        }

        @JavascriptInterface
        public String lines() {
            return call(s -> s.lines.toJson());
        }

        @JavascriptInterface
        public String reloadLines() {
            return call(s -> {
                s.lines.load(s);
                return true;
            });
        }

        @JavascriptInterface
        public String shizuku() {
            return call(s -> {
                Shell.request();
                return Shell.state();
            });
        }

        @JavascriptInterface
        public String setVisible(boolean v) {
            return call(s -> {
                s.setVisible(v);
                return v;
            });
        }
    }
}
