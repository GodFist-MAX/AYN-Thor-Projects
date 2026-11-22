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

/** Coronamon's den: the apps he looks after, what happened lately, bugs, problem reports and settings. */
public final class DenActivity extends Activity {
    /** A capture from ReportActivity (JSON); the den opens the report form with it. */
    static final String EXTRA_REPORT = "report";

    private WebView web;
    private volatile String pendingReport;
    private volatile int displayId = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        PetService.start(this);
        pendingReport = getIntent().getStringExtra(EXTRA_REPORT);
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
        if (getDisplay() != null) displayId = getDisplay().getDisplayId();
        if (web != null) web.evaluateJavascript("window.den && den.refresh()", null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String r = intent.getStringExtra(EXTRA_REPORT);
        if (r != null) {
            pendingReport = r;
            if (web != null) web.evaluateJavascript("window.den && den.checkReport()", null);
        }
    }

    private static String reply(Object data) {
        return Util.obj("ok", true, "data", data == null ? JSONObject.NULL : data).toString();
    }

    private static void deleteCapture(JSONObject capture) {
        File dir = new File(capture.optString("dir"));
        if (!dir.getName().startsWith("pending-") || !Bugs.FILES.equals(dir.getParentFile())) return;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        dir.delete();
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

        /** open, close or panel: his home on the bottom screen. */
        @JavascriptInterface
        public String home(String action) {
            return call(s -> {
                s.showNow();
                return s.home.command(action, null);
            });
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

        // ---- the Mac: Grok Build's status, and the buttons that run coro there ----

        @JavascriptInterface
        public String mac() {
            return call(PetService::macForDen);
        }

        @JavascriptInterface
        public String control(String action, String arg) {
            return call(s -> s.mac.request(action, arg));
        }

        @JavascriptInterface
        public String diagnostics() {
            return call(s -> s.diag.start());
        }

        // ---- his Grok brain: the Talk tab ----

        @JavascriptInterface
        public String brain() {
            return call(s -> s.mind.forDen());
        }

        @JavascriptInterface
        public String brainSend(String text) {
            return call(s -> s.mind.ask(text, "den"));
        }

        @JavascriptInterface
        public String brainAction(String msgId, String actionId, boolean yes) {
            return call(s -> s.mind.confirm(msgId, actionId, yes));
        }

        /** Takes a pasted API key into GrokKey. The key never comes back out to the page. */
        @JavascriptInterface
        public String brainKey(String key) {
            return call(s -> {
                GrokKey.set(s, key);
                s.mind.keySet();
                return GrokKey.hint(s);
            });
        }

        @JavascriptInterface
        public String brainForgetKey() {
            return call(s -> {
                GrokKey.clear(s);
                s.mind.keyCleared();
                return true;
            });
        }

        @JavascriptInterface
        public String brainClear(String what) {
            return call(s -> {
                s.mind.clear(what);
                return true;
            });
        }

        @JavascriptInterface
        public String brainLines() {
            return call(s -> s.mind.refreshLines());
        }

        // ---- bugs and problem reports ----

        @JavascriptInterface
        public String bugs() {
            return call(s -> Util.obj("bugs", s.bugs.list("all", 200), "waiting", s.bugs.waiting(), "lastSync", s.bugs.lastSync()));
        }

        @JavascriptInterface
        public String bugText(String id) {
            return call(s -> s.bugs.detail(id));
        }

        @JavascriptInterface
        public String setBugState(String id, String state) {
            return call(s -> {
                JSONObject b = s.bugs.setState(id, state, null);
                if (b == null) throw new IllegalArgumentException("no bug " + id);
                return b;
            });
        }

        /** Shows the soft keyboard for the focused text box (the d-pad can focus it, but only a tap opens the keyboard). */
        @JavascriptInterface
        public void keyboard() {
            runOnUiThread(() -> {
                web.requestFocus();
                getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(web, 0);
            });
        }

        @JavascriptInterface
        public String takeReport() {
            String r = pendingReport;
            pendingReport = null;
            return reply(r == null ? null : Util.parse(r));
        }

        /** Captures the screens (except the den's) and the apps' logs. Runs on the bridge thread, not the main one. */
        @JavascriptInterface
        public String startReport() {
            PetService s = PetService.instance;
            if (s == null) return Util.obj("ok", false, "error", "Coronamon is waking up").toString();
            runOnUiThread(() -> s.hideFor(8000));
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            JSONObject capture = Capture.take(s, displayId);
            runOnUiThread(() -> s.hideFor(0));
            return reply(capture);
        }

        /** {pkg, category, text, capture, shots, github} */
        @JavascriptInterface
        public String submitReport(String json) {
            JSONObject r = Util.parse(json);
            String pkg = r.optString("pkg");
            JSONObject capture = r.optJSONObject("capture");
            if (capture != null && !capture.optString("dir").isEmpty()) {
                if (!r.optBoolean("shots", true)) {
                    JSONArray shots = capture.optJSONArray("shots");
                    for (int i = 0; shots != null && i < shots.length(); i++) new File(shots.optString(i)).delete();
                    Util.put(capture, "shots", new JSONArray());
                }
                JSONObject logs = capture.optJSONObject("logs");
                if (logs == null) Util.put(capture, "logs", logs = new JSONObject());
                if (!pkg.isEmpty() && !logs.has(pkg) && Shell.granted()) {
                    File lf = Capture.log(pkg, new File(capture.optString("dir")));
                    if (lf != null) Util.put(logs, pkg, lf.getPath());
                }
            }
            return call(s -> s.reportProblem(pkg, r.optString("category", "Something else"), r.optString("text"), capture,
                    r.optBoolean("github")));
        }

        @JavascriptInterface
        public String discardReport(String json) {
            JSONObject capture = Util.parse(json);
            deleteCapture(capture);
            return reply(true);
        }

        /** A small JPEG of a screenshot saved with a report. */
        @JavascriptInterface
        public String thumb(String path) {
            try {
                File f = new File(path).getCanonicalFile();
                if (!f.getPath().startsWith(Bugs.FILES.getCanonicalPath() + "/")) throw new SecurityException("not a report file");
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                o.inJustDecodeBounds = false;
                o.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1, o.outWidth / 360)));
                Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                if (b == null) return reply("");
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 70, out);
                return reply("data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP));
            } catch (Exception e) {
                return Util.obj("ok", false, "error", e.toString()).toString();
            }
        }
    }
}
