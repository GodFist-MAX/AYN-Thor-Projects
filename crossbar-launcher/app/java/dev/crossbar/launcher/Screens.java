package dev.crossbar.launcher;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;

/** The Thor's two screens: the main (top) display and the built-in second (bottom) display. */
final class Screens {
    private Screens() {
    }

    /**
     * Launch options for an activity on the given display. Starts without a source activity
     * otherwise land on whichever display last had input focus, which may be the bottom one.
     */
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

    static boolean bottomEnabled(Context c) {
        int s = c.getPackageManager().getComponentEnabledSetting(new ComponentName(c, BottomActivity.class));
        return s != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    /**
     * Turns the bottom-screen companion on or off. Off disables the activity so the system
     * shows its own secondary launcher on that screen again.
     */
    static void setBottomEnabled(Context c, boolean on) {
        c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, BottomActivity.class),
                on ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        BottomActivity b = App.I.bottom;
        if (!on && b != null) b.finish();
        Display d = bottom(c);
        if (d == null) return;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_SECONDARY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (on) {
            home.setClass(c, BottomActivity.class);
        } else {
            // What the system picks when the home app has no bottom-screen activity: its own launcher.
            ResolveInfo pick = null;
            for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(c.getPackageName())) continue;
                if (pick == null || pkg.equals("com.android.launcher3")) pick = ri;
            }
            if (pick == null) return;
            home.setClassName(pick.activityInfo.packageName, pick.activityInfo.name);
        }
        try {
            c.startActivity(home, on(d.getDisplayId()));
        } catch (Exception ignored) {
        }
    }
}
