package dev.grokbuild.coronamon;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * How Grok Build on the Mac talks to Coronamon over adb:
 * {@code adb shell content call --uri content://dev.grokbuild.coronamon.bridge --method status}.
 * Only the shell user (adb) and root may call it. Values may be base64 when extra enc=b64.
 */
public final class Bridge extends ContentProvider {
    static final String AUTHORITY = "dev.grokbuild.coronamon.bridge";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != Process.SHELL_UID && uid != 0 && uid != Process.myUid()) {
            throw new SecurityException("Coronamon only takes orders over adb");
        }
        Bundle in = extras == null ? new Bundle() : extras;
        boolean b64 = "b64".equals(str(in, "enc", false));
        JSONObject res;
        try {
            String a = b64 && arg != null ? decode(arg) : arg;
            res = "diagnostics".equals(method) ? Diagnostics.forBridge(getContext()) : onMain(() -> handle(method, a, in, b64));
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            res = Util.obj("ok", false, "error", c.toString());
        }
        Bundle out = new Bundle();
        out.putString("json", res.toString());
        return out;
    }

    private static String decode(String s) {
        return new String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    private static String str(Bundle b, String k, boolean b64) {
        Object o = b.get(k);
        if (o == null) return null;
        String s = String.valueOf(o);
        return b64 && o instanceof String ? decode(s) : s;
    }

    static JSONObject onMain(Callable<JSONObject> c) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        FutureTask<JSONObject> f = new FutureTask<>(c);
        new Handler(Looper.getMainLooper()).post(f);
        return f.get(15, TimeUnit.SECONDS);
    }

    private JSONObject handle(String method, String arg, Bundle in, boolean b64) throws Exception {
        PetService s = PetService.instance;
        if (s == null) {
            PetService.start(getContext());
            if (!"ping".equals(method)) return Util.obj("ok", false, "error", "Coronamon is waking up; try again in a moment");
            return Util.obj("ok", true, "running", false);
        }
        switch (method) {
            case "ping":
                return Util.obj("ok", true, "running", true, "version", s.version());
            case "status":
                return ok(s.status());
            case "bugs": {
                int max = (int) parseFloat(str(in, "max", b64), 40);
                return Util.obj("ok", true, "rev", s.bugs.rev(), "waiting", s.bugs.waiting(),
                        "bugs", s.bugs.list(arg == null ? "unsynced" : arg, Math.max(1, max)));
            }
            case "bug": {
                JSONObject b = s.bugs.get(arg);
                return b == null ? Util.obj("ok", false, "error", "no bug " + arg) : Util.obj("ok", true, "bug", b);
            }
            case "bugs-ack": {
                JSONArray acks = Util.parseArray(str(in, "acks", b64));
                JSONArray firstTime = s.bugs.ack(acks);
                s.bugsAcked(firstTime);
                return Util.obj("ok", true, "acked", acks.length(), "firstTime", firstTime.length());
            }
            case "bug-state": {
                String state = str(in, "state", b64);
                JSONObject b = s.bugs.setState(arg, state, str(in, "version", b64));
                if (b == null) return Util.obj("ok", false, "error", "no bug " + arg + ", or state isn't open, fixed or ignored");
                if ("fixed".equals(state) && !"quiet".equals(str(in, "react", b64))) s.bugFixed(b);
                return Util.obj("ok", true, "bug", b);
            }
            case "bug-report": {
                JSONObject b = s.reportProblem(arg, nz(str(in, "category", b64)).isEmpty() ? "Something else" : str(in, "category", b64),
                        str(in, "text", b64), null, "true".equals(str(in, "github", b64)));
                return Util.obj("ok", true, "bug", b);
            }
            case "bug-forget":
                return Util.obj("ok", s.bugs.forget(arg));
            case "published": {
                s.published(arg, str(in, "label", b64), str(in, "version", b64), str(in, "url", b64));
                return Util.obj("ok", true);
            }
            case "say": {
                String key = str(in, "key", b64);
                Lines.Line line = key != null ? s.lines.pick(key, Brain.vars(str(in, "app", b64), str(in, "version", b64), str(in, "msg", b64)))
                        : new Lines.Line(nz(str(in, "src", b64)), arg == null ? "" : arg);
                if (line == null || line.text.isEmpty()) return Util.obj("ok", false, "error", "nothing to say");
                s.say(line, null, null);
                return Util.obj("ok", true, "said", line.text);
            }
            case "act": {
                float secs = parseFloat(str(in, "secs", b64), 0);
                boolean ok = s.brain.act(arg == null ? "" : arg, secs);
                return ok ? Util.obj("ok", true) : Util.obj("ok", false, "error", "can't do '" + arg + "' right now (unknown move, or he's in the air)");
            }
            case "moves": {
                JSONArray a = new JSONArray();
                for (String m : Moves.ALL.keySet()) a.put(m);
                a.put("hop");
                return Util.obj("ok", true, "moves", a);
            }
            case "job": {
                String kind = nz(str(in, "kind", b64)).isEmpty() ? "install" : str(in, "kind", b64);
                String label = str(in, "label", b64), pkg = str(in, "pkg", b64), version = str(in, "version", b64);
                if (label == null) label = pkg == null ? "your app" : s.registry.label(pkg);
                if ("begin".equals(arg)) {
                    s.jobBegin(kind, label, pkg, version, str(in, "detail", b64));
                    return Util.obj("ok", true);
                }
                if ("end".equals(arg)) {
                    boolean ok = "true".equals(str(in, "ok", b64)) || "1".equals(str(in, "ok", b64));
                    JSONObject reg = Util.parse(nz(str(in, "fields", b64)));
                    boolean isNew = s.jobEnd(kind, label, pkg, version, ok, str(in, "msg", b64), reg);
                    return Util.obj("ok", true, "new", isNew);
                }
                return Util.obj("ok", false, "error", "job needs --arg begin or end");
            }
            case "register": {
                if (arg == null) return Util.obj("ok", false, "error", "register needs a package");
                JSONObject f = Util.parse(nz(str(in, "fields", b64)));
                boolean isNew = s.registry.upsert(arg, f);
                return Util.obj("ok", true, "new", isNew, "entry", s.registry.live(arg));
            }
            case "forget":
                s.registry.remove(arg);
                return Util.obj("ok", true);
            case "registry":
                return Util.obj("ok", true, "apps", s.registry.liveAll());
            case "history":
                return Util.obj("ok", true, "events", s.history.recent((int) parseFloat(arg, 30)));
            case "reports":
                return Util.obj("ok", true, "reports", CrashWatch.list(arg, (int) parseFloat(str(in, "n", b64), 10)));
            case "install": {
                File f = new File(arg == null ? "" : arg);
                if (!f.isFile()) return Util.obj("ok", false, "error", "no such file: " + arg);
                s.installFile(f, "bridge", str(in, "label", b64));
                return Util.obj("ok", true, "started", true);
            }
            case "hide": {
                float secs = parseFloat(arg, 0);
                if (secs > 0) s.hideFor((long) (secs * 1000));
                else s.setVisible(false);
                return Util.obj("ok", true);
            }
            case "show":
                s.showNow();
                return Util.obj("ok", true);
            case "summon": {
                int i = s.stageIndexOf(arg == null ? "" : arg);
                if (i < 0) return Util.obj("ok", false, "error", "screen is top or bottom");
                s.showNow();
                s.summon(i);
                return Util.obj("ok", true);
            }
            case "setting": {
                String value = str(in, "value", b64);
                if (arg == null) return ok(s.settings.toJson());
                if (!s.settings.set(arg, value)) return Util.obj("ok", false, "error", "bad setting or value");
                if ("size".equals(arg)) s.sizeChanged();
                if ("visible".equals(arg)) s.setVisible(s.settings.visible());
                return ok(s.settings.toJson());
            }
            case "check":
                if (arg == null || arg.isEmpty()) s.updates.checkAll(!"false".equals(str(in, "announce", b64)));
                else s.updates.checkOne(arg);
                return Util.obj("ok", true, "started", true);
            case "grok": {
                boolean ok = !"false".equals(str(in, "ok", b64));
                if (!s.grok(nz(arg), nz(str(in, "msg", b64)), str(in, "detail", b64), ok)) {
                    return Util.obj("ok", false, "error", "grok needs --arg connected, away, fixing, done or stopped");
                }
                return Util.obj("ok", true);
            }
            case "control":
                return Util.obj("ok", true, "requests", s.mac.take());
            case "control-done": {
                JSONObject result = Util.parse(str(in, "result", b64));
                JSONObject r = s.mac.done(nz(arg), result);
                if (r != null) s.macAnswered(r, result);
                return Util.obj("ok", true, "found", r != null);
            }
            case "mac-status":
                s.mac.setStatus(Util.parse(str(in, "status", b64)));
                return Util.obj("ok", true);
            case "update":
                s.updates.fetch(arg);
                return Util.obj("ok", true, "started", true);
            case "lines":
                if ("reload".equals(arg)) s.lines.load(s);
                return Util.obj("ok", true, "lines", s.lines.toJson());
            case "shizuku":
                if ("request".equals(arg)) Shell.request();
                return Util.obj("ok", true, "state", Shell.state());
            case "den": {
                Intent i = new Intent(s, DenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                int idx = s.stageIndexOf(arg == null ? "" : arg);
                s.startActivity(i, Screens.on(idx < 0 ? s.brain.stage().displayId : s.stage(idx).displayId));
                return Util.obj("ok", true);
            }
            default:
                return Util.obj("ok", false, "error", "unknown method " + method);
        }
    }

    private static JSONObject ok(JSONObject o) {
        Util.put(o, "ok", true);
        return o;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static float parseFloat(String s, float def) {
        try {
            return s == null ? def : Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
