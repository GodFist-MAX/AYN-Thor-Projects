package dev.grokbuild.coronamon;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** What Coronamon has done and seen, newest last, as JSON lines in the app's files. */
final class History {
    private final File file;
    private int added;

    History(Context ctx) {
        file = new File(ctx.getFilesDir(), "history.jsonl");
    }

    synchronized JSONObject add(String kind, String pkg, String app, Boolean ok, String msg, String version) {
        JSONObject o = Util.obj("t", System.currentTimeMillis(), "kind", kind);
        if (pkg != null) Util.put(o, "pkg", pkg);
        if (app != null) Util.put(o, "app", app);
        if (ok != null) Util.put(o, "ok", ok);
        if (msg != null && !msg.isEmpty()) Util.put(o, "msg", msg.length() > 600 ? msg.substring(0, 600) : msg);
        if (version != null) Util.put(o, "version", version);
        Util.appendLine(file, o.toString());
        if (++added % 50 == 0) trim();
        return o;
    }

    synchronized JSONArray recent(int n) {
        String s = Util.readText(file);
        JSONArray out = new JSONArray();
        if (s == null) return out;
        String[] lines = s.split("\n");
        for (int i = Math.max(0, lines.length - n); i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            JSONObject o = Util.parse(lines[i]);
            if (o.length() > 0) out.put(o);
        }
        return out;
    }

    private void trim() {
        String s = Util.readText(file);
        if (s == null) return;
        String[] lines = s.split("\n");
        if (lines.length <= 1500) return;
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - 1000; i < lines.length; i++) sb.append(lines[i]).append('\n');
        Util.writeText(file, sb.toString());
    }
}
