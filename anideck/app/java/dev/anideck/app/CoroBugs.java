package dev.anideck.app;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Tells Coronamon about problems AniDeck catches itself (a web renderer that crashed) so they
 * reach Grok Build with the Thor's other bugs. The title can end up in a public GitHub issue, so it
 * names what broke and nothing about what was being watched; the detail only goes to Grok. Does
 * nothing when Coronamon isn't installed.
 */
final class CoroBugs {
    private static final Uri URI = Uri.parse("content://dev.grokbuild.coronamon.bugs");
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

    /** Called from onRenderProcessGone. A renderer the system stopped to free memory isn't a bug. */
    static void rendererGone(Context ctx, String screen, boolean crashed) {
        if (crashed) report(ctx, "renderer", "Web page renderer crashed on the " + screen + " screen", "didCrash=true");
    }
}
