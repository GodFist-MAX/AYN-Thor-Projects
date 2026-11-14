package dev.crossbar.launcher;

import android.app.Activity;
import android.content.Context;
import android.view.Choreographer;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Holds a screen's WebView and sets its frame rate. A WebView only makes a new frame when it is
 * invalidated and drawn, so below the display's rate each invalidation waits for a vsync at least
 * a whole frame after the last one: at 30 FPS frames land on every other refresh, evenly spaced,
 * and the renderer and GPU do half the work.
 */
final class FramePacer extends FrameLayout implements Choreographer.FrameCallback {
    private final Choreographer choreographer = Choreographer.getInstance();
    /** Display refreshes per frame: 1 draws on every vsync, 2 on every other one. */
    private int interval = 1;
    private long periodNs = 16_666_667L;
    private long lastFrameNs;
    private boolean waiting;
    private View heldChild, heldTarget;

    FramePacer(Context c) {
        super(c);
    }

    /** Keeps the window's display at 60 Hz and paces drawing to fps (30 or 60). */
    void apply(Activity a, int fps) {
        Display d = a.getDisplay();
        float hz = 60f;
        if (d != null) {
            Display.Mode cur = d.getMode(), best = null;
            for (Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
                if (best == null || Math.abs(m.getRefreshRate() - 60f) < Math.abs(best.getRefreshRate() - 60f)) best = m;
            }
            Window w = a.getWindow();
            WindowManager.LayoutParams lp = w.getAttributes();
            int id = best != null ? best.getModeId() : 0;
            if (lp.preferredDisplayModeId != id) {
                lp.preferredDisplayModeId = id;
                w.setAttributes(lp);
            }
            hz = best != null ? best.getRefreshRate() : d.getRefreshRate();
        }
        if (hz > 1) periodNs = (long) (1e9 / hz);
        interval = fps > 0 ? Math.max(1, Math.round(hz / fps)) : 1;
        if (interval == 1) release();
    }

    @Override
    protected void onDetachedFromWindow() {
        choreographer.removeFrameCallback(this);
        waiting = false;
        heldChild = heldTarget = null;
        super.onDetachedFromWindow();
    }

    @Override
    public void onDescendantInvalidated(View child, View target) {
        if (interval <= 1) {
            super.onDescendantInvalidated(child, target);
            return;
        }
        heldChild = child;
        heldTarget = target;
        if (!waiting) {
            waiting = true;
            choreographer.postFrameCallback(this);
        }
    }

    /** Runs before this vsync's traversal, so a released invalidation is drawn in this same frame. */
    @Override
    public void doFrame(long frameTimeNanos) {
        waiting = false;
        if (heldChild == null) return;
        if (interval > 1 && frameTimeNanos - lastFrameNs < (interval - 0.5) * periodNs) {
            waiting = true;
            choreographer.postFrameCallback(this);
            return;
        }
        lastFrameNs = frameTimeNanos;
        release();
    }

    private void release() {
        View c = heldChild, t = heldTarget;
        heldChild = heldTarget = null;
        if (c != null) super.onDescendantInvalidated(c, t);
    }
}
