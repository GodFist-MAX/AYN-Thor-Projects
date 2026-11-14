package dev.grokbuild.coronamon;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import org.json.JSONObject;

/**
 * "Report a problem" from Coronamon's notification. Has no window of its own: waits for the shade
 * to close, hides him, captures both screens and the apps' recent logs, then opens the den's report
 * form on the bottom screen.
 */
public final class ReportActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        PetService s = PetService.instance;
        if (s == null) {
            PetService.start(this);
            openDen(null);
            return;
        }
        s.hideFor(8000);
        new Handler(Looper.getMainLooper()).postDelayed(() -> new Thread(() -> {
            JSONObject capture = Capture.take(s, -1);
            runOnUiThread(() -> openDen(capture));
        }, "report-capture").start(), 900);
    }

    private void openDen(JSONObject capture) {
        Intent i = new Intent(this, DenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(DenActivity.EXTRA_REPORT, capture == null ? "{}" : capture.toString());
        Display bottom = Screens.bottom(this);
        try {
            if (bottom != null) startActivity(i, Screens.on(bottom.getDisplayId()));
            else startActivity(i);
        } catch (Exception e) {
            startActivity(i);
        }
        PetService s = PetService.instance;
        if (s != null) s.hideFor(0);
        finish();
        overridePendingTransition(0, 0);
    }
}
