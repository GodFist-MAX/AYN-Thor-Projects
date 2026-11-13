package dev.grokbuild.coronamon;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Coronamon's window contents: the puppet, drawn from the shared actor, plus touch handling.
 * The window is WIN_W x WIN_H puppet units with his feet at (ROOT_X, ROOT_Y).
 */
final class PetView extends View {
    static final float WIN_W = 210, WIN_H = 310, ROOT_X = 105, ROOT_Y = 300;

    interface Touch {
        void down(float rawX, float rawY);
        void tap();
        void longPress();
        void rub();
        void dragStart(float rawX, float rawY);
        void drag(float rawX, float rawY);
        void release(float vx, float vy);
    }

    private final Puppet puppet = new Puppet();
    private final AndroidGfx gfx = new AndroidGfx();
    Actor actor;
    float scale = 1;
    Touch touch;

    private final int slop;
    private float downX, downY, lastX, lastY;
    private long downAt;
    private boolean dragging, longPressed, tracking;
    private final float[] histX = new float[6], histY = new float[6];
    private final long[] histT = new long[6];
    private int hist;
    private float rubDist;

    private final Runnable longPressCheck = () -> {
        if (tracking && !dragging) {
            longPressed = true;
            if (touch != null) touch.longPress();
        }
    };

    PetView(Context ctx) {
        super(ctx);
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
    }

    void setGreenEyes(boolean g) {
        puppet.greenEyes = g;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (actor == null) return;
        c.save();
        c.translate(ROOT_X * scale, ROOT_Y * scale);
        c.scale(scale, scale);
        gfx.begin(c);
        puppet.draw(gfx, actor.pose, actor.clock);
        c.restore();
    }

    /** Roughly his body and head, in window pixels. */
    private boolean hit(float x, float y) {
        float ux = x / scale - ROOT_X, uy = y / scale - ROOT_Y;
        float lift = actor == null ? 0 : actor.pose.v[Pose.LIFT];
        uy += lift;
        return Math.abs(ux) < 64 && uy > -230 && uy < 12;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float rx = e.getRawX(), ry = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!hit(e.getX(), e.getY())) {
                    tracking = false;
                    return true;
                }
                tracking = true;
                dragging = false;
                longPressed = false;
                rubDist = 0;
                downX = lastX = rx;
                downY = lastY = ry;
                downAt = e.getEventTime();
                hist = 0;
                record(rx, ry, e.getEventTime());
                if (touch != null) touch.down(rx, ry);
                postDelayed(longPressCheck, 550);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return true;
                record(rx, ry, e.getEventTime());
                if (longPressed && !dragging) {
                    rubDist += Math.abs(rx - lastX) + Math.abs(ry - lastY);
                    if (rubDist > slop * 3) {
                        rubDist = 0;
                        if (touch != null) touch.rub();
                    }
                    if (Math.hypot(rx - downX, ry - downY) > slop * 8) startDrag(rx, ry);
                } else if (!dragging && Math.hypot(rx - downX, ry - downY) > slop) {
                    startDrag(rx, ry);
                } else if (dragging && touch != null) {
                    touch.drag(rx, ry);
                }
                lastX = rx;
                lastY = ry;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressCheck);
                if (!tracking) return true;
                tracking = false;
                record(rx, ry, e.getEventTime());
                if (dragging) {
                    float[] v = velocity();
                    if (touch != null) touch.release(v[0], v[1]);
                } else if (!longPressed && e.getActionMasked() == MotionEvent.ACTION_UP
                        && e.getEventTime() - downAt < 500 && touch != null) {
                    touch.tap();
                } else if (longPressed && touch != null) {
                    touch.release(0, 0);
                }
                dragging = false;
                return true;
        }
        return true;
    }

    private void startDrag(float rx, float ry) {
        dragging = true;
        removeCallbacks(longPressCheck);
        if (touch != null) touch.dragStart(rx, ry);
    }

    private void record(float x, float y, long t) {
        int i = hist % histX.length;
        histX[i] = x;
        histY[i] = y;
        histT[i] = t;
        hist++;
    }

    /** Fling velocity in px/s from the last ~80 ms of movement. */
    private float[] velocity() {
        int n = Math.min(hist, histX.length);
        if (n < 2) return new float[]{0, 0};
        int last = (hist - 1) % histX.length;
        int first = last;
        for (int k = 1; k < n; k++) {
            int idx = (hist - 1 - k) % histX.length;
            if (histT[last] - histT[idx] > 80) break;
            first = idx;
        }
        float dt = (histT[last] - histT[first]) / 1000f;
        if (dt < 0.008f) return new float[]{0, 0};
        return new float[]{(histX[last] - histX[first]) / dt, (histY[last] - histY[first]) / dt};
    }
}
