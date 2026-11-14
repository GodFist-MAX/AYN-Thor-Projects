package dev.grokbuild.coronamon;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Lets the apps Grok Build made for the Thor report problems they catch themselves: a web page
 * renderer that died, a script error, a game that wouldn't start. Only registered Grok-built apps
 * and adb's shell (with a pkg extra, for testing) may call it. Reports become bugs and go to the
 * Mac with the rest.
 *
 * call(content://dev.grokbuild.coronamon.bugs, "report", null, {kind, title, detail})
 * title may be posted publicly on GitHub, so it should name what broke but nothing personal;
 * detail stays with Grok.
 */
public final class BugsProvider extends ContentProvider {
    private static final int PER_HOUR = 30;
    private final Map<String, ArrayDeque<Long>> recent = new HashMap<>();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        out.putBoolean("ok", false);
        if (!"report".equals(method) || extras == null) return out;
        PetService s = PetService.instance;
        if (s == null) {
            out.putString("error", "Coronamon isn't running");
            return out;
        }
        int uid = Binder.getCallingUid();
        String pkg = caller(uid, s, extras);
        if (pkg == null) throw new SecurityException("only apps Grok Build made for the Thor can report problems to Coronamon");
        String title = Bugs.clip(String.valueOf(extras.getString("title", "")).replaceAll("\\s+", " ").trim(), 140);
        if (title.isEmpty()) {
            out.putString("error", "a report needs a title");
            return out;
        }
        if (!allow(pkg)) {
            out.putString("error", "too many reports this hour");
            return out;
        }
        String kind = String.valueOf(extras.getString("kind", "error"));
        if (!kind.matches("renderer|js|launch|freeze|error")) kind = "error";
        String detail = Bugs.clip(extras.getString("detail", ""), 8000);
        String k = kind;
        s.main.post(() -> s.appReported(pkg, k, title, detail));
        out.putBoolean("ok", true);
        return out;
    }

    private static String caller(int uid, PetService s, Bundle extras) {
        if (uid == Process.SHELL_UID || uid == 0) return extras.getString("pkg");
        String[] pkgs = s.getPackageManager().getPackagesForUid(uid);
        if (pkgs == null) return null;
        for (String p : pkgs) {
            JSONObject e = s.registry.entry(p);
            if (e != null && "built".equals(e.optString("kind")) && !Registry.SELF.equals(p)) return p;
        }
        return null;
    }

    private synchronized boolean allow(String pkg) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> q = recent.computeIfAbsent(pkg, p -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > 3600_000L) q.pollFirst();
        if (q.size() >= PER_HOUR) return false;
        q.addLast(now);
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
