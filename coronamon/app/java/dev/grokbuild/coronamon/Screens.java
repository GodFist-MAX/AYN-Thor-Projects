package dev.grokbuild.coronamon;

import android.app.ActivityOptions;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;

/** The Thor's two screens: the main display on top and the presentation display below. */
final class Screens {
    static final int TOP_ID = Display.DEFAULT_DISPLAY;

    private Screens() {
    }

    static Display top(Context ctx) {
        return ctx.getSystemService(DisplayManager.class).getDisplay(TOP_ID);
    }

    static Display bottom(Context ctx) {
        DisplayManager dm = ctx.getSystemService(DisplayManager.class);
        for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (d.getDisplayId() != TOP_ID && (d.getFlags() & Display.FLAG_PRIVATE) == 0) return d;
        }
        return null;
    }

    static Bundle on(int displayId) {
        return ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
    }

    static String name(int displayId) {
        return displayId == TOP_ID ? "top" : "bottom";
    }
}
