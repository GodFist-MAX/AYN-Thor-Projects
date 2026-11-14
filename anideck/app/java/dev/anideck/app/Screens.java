package dev.anideck.app;

import android.app.ActivityOptions;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;

/** The Thor's two screens: the main (top) display and the built-in second (bottom) display. */
final class Screens {
    private Screens() {
    }

    static Bundle on(int displayId) {
        return ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
    }

    static Bundle top() {
        return on(Display.DEFAULT_DISPLAY);
    }

    /** The second built-in screen, or null on single-screen devices. */
    static Display bottom(Context c) {
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        if (dm == null) return null;
        for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY && (d.getFlags() & Display.FLAG_PRIVATE) == 0) return d;
        }
        return null;
    }
}
