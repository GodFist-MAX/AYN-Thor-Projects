package dev.crossbar.launcher;

import android.app.Activity;
import android.os.Bundle;

/**
 * Started on the top screen to give it back the system-wide input focus while a game or app
 * covers the menu. It has no window and finishes at once, so the app below only pauses and
 * resumes; on the Thor, starting an activity is the only way to move the focus to another display.
 */
public class FocusActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        finish();
    }
}
