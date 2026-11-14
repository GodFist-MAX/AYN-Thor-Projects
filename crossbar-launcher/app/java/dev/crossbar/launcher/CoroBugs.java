package dev.crossbar.launcher;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells Coronamon about problems CrossBar catches itself (a page's script error, a web renderer
 * that crashed, something that wouldn't start) so they reach Grok Build with the Thor's other
 * bugs. The title can end up in a public GitHub issue, so it says what broke without names or
 * paths; the detail only goes to Grok. Does nothing when Coronamon isn't installed.
 */
final class CoroBugs {
    private static final Uri URI = Uri.parse("content://dev.grokbuild.coronamon.bugs");
    private static final Pattern SCRIPT = Pattern.compile("^(error|rejection)\\[(top|bottom)\\]: (.*?)(?: @(\\S*):(\\d+))?$",
            Pattern.DOTALL);
    private static final Pattern NOISE = Pattern.compile(
            "^Script error\\.?$|ResizeObserver|Failed to fetch|NetworkError|AbortError|Load failed|net::ERR_");
    private static final Map<String, Long> recent = new HashMap<>();

    private CoroBugs() {
    }

    static void report(Context ctx, String kind, String title, String detail) {
        Context app = ctx.getApplicationContext();
        synchronized (recent) {
            long now = System.currentTimeMillis();
            Long last = recent.get(kind + title);
            if (last != null && now - last < 60_000) return;
            recent.put(kind + title, now);
        }
        new Thread(() -> {
            try {
                Bundle b = new Bundle();
                b.putString("kind", kind);
                b.putString("title", title);
                b.putString("detail", detail == null ? "" : detail);
                app.getContentResolver().call(URI, "report", null, b);
            } catch (Exception e) {
                Log.i(App.TAG, "couldn't tell Coronamon: " + e.getMessage());
            }
        }, "corobugs").start();
    }

    /** A page's uncaught error, as core.js logs it: "error[top]: message @file:line". */
    static void scriptError(Context ctx, String logged) {
        Matcher m = SCRIPT.matcher(logged == null ? "" : logged.trim());
        if (!m.find()) return;
        String what = m.group(3).split("\n")[0].trim();
        if (what.isEmpty() || NOISE.matcher(what).find()) return;
        if (what.length() > 100) what = what.substring(0, 99) + "…";
        String file = m.group(4) == null ? "" : m.group(4).replaceAll(".*/", "");
        String where = file.isEmpty() ? "" : " in " + file + (m.group(5) == null ? "" : ":" + m.group(5));
        report(ctx, "js", "Script error on the " + m.group(2) + " screen: " + what + where, logged);
    }

    /** Called from onRenderProcessGone. A renderer the system stopped to free memory isn't a bug. */
    static void rendererGone(Context ctx, String screen, boolean crashed) {
        if (crashed) report(ctx, "renderer", "Web page renderer crashed on the " + screen + " screen", "didCrash=true");
    }

    /** "Couldn't start PPSSPP: <exception>": the part before the colon is the title. */
    static void launchFailed(Context ctx, String err, String detail) {
        if (err == null || !err.startsWith("Couldn't")) return;
        int colon = err.indexOf(':');
        report(ctx, "launch", colon > 0 ? err.substring(0, colon) : err, err + (detail == null ? "" : "\n" + detail));
    }
}
