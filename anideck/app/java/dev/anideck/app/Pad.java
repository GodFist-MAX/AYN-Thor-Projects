package dev.anideck.app;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.Objects;

/**
 * The Thor's controller works one screen at a time. The d-pad, left stick, A and B move and use
 * the highlight on that screen, and the right stick scrolls it; Start, X, Y and the shoulder
 * buttons control the episode from either screen. Select switches screens, and touching a screen
 * hands it the controller, except that while an episode fills the top screen the controller
 * stays with the video.
 */
final class Pad {
    static final int TOP = 0, BOTTOM = 1;
    private static final long REPEAT_START = 380, REPEAT_EVERY = 110;

    private final Handler h = new Handler(Looper.getMainLooper());
    private int screen = TOP;
    private long lastMove, lastDpadKey;
    /** Direction the left stick or the d-pad's hat is held in. */
    private String held;
    /** Right stick, up/down, -1..1. */
    private float scroll;
    private final Runnable repeat = this::repeat;
    private final Runnable scroller = this::scrollStep;

    /**
     * Controller keys, taken before the system's own handling: otherwise the first d-pad press
     * after a touch takes the window out of touch mode instead, which focuses the WebView (graying
     * it with Android's focus highlight) and the page's first link, and never reaches the app.
     */
    static boolean early(KeyEvent e) {
        return App.I.pad.key(e);
    }

    /** Buttons from the controller; false for anything else (the touch keyboard, the Back gesture). */
    boolean key(KeyEvent e) {
        if (!fromController(e)) return false;
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, first = down && e.getRepeatCount() == 0;
        String dir = direction(k);
        if (dir != null) {
            lastDpadKey = SystemClock.uptimeMillis();
            if (first || (down && lastDpadKey - lastMove >= REPEAT_EVERY)) move(dir);
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_A:
                if (first) ok();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                if (first) back();
                return true;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (first) use(screen == TOP ? BOTTOM : TOP, true);
                return true;
            default:
                MainActivity m = App.I.top;
                return m != null && m.playerKey(e);
        }
    }

    /** The sticks, and the d-pad on controllers that report it as a hat. */
    boolean motion(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || e.getActionMasked() != MotionEvent.ACTION_MOVE) return false;
        String d = null;
        // A d-pad that reports both keys and a hat has already moved with the key.
        if (SystemClock.uptimeMillis() - lastDpadKey > 250) {
            d = direction(e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y), 0.5f);
        }
        if (d == null) {
            d = direction(e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y), held == null ? 0.6f : 0.35f);
        }
        hold(d);
        float v = e.getAxisValue(MotionEvent.AXIS_RZ);
        boolean idle = scroll == 0;
        scroll = Math.abs(v) < 0.2f ? 0 : v;
        if (idle && scroll != 0) h.post(scroller);
        return true;
    }

    void touched(int s) {
        MainActivity m = App.I.top;
        if (s == BOTTOM && m != null && m.filling()) s = TOP;
        use(s, false);
    }

    /** Hands the controller to a screen; `show` puts its highlight up right away (Select), a touch hides both. */
    void use(int s, boolean show) {
        MainActivity m = App.I.top;
        BottomActivity b = App.I.bottom;
        if (s == BOTTOM && b == null) s = TOP;
        boolean changed = s != screen;
        screen = s;
        if (m != null) m.highlight(s == TOP && show);
        if (b != null) b.js("window.deck&&deck.pad(" + (s == BOTTOM) + "," + (s == BOTTOM && show) + ")");
        if (!changed || !show) return;
        if (s == TOP && m != null) m.showToast("Controller: top screen");
        else if (s == BOTTOM && b != null) b.js("window.deck&&deck.toast('Controller: bottom screen')");
    }

    private void move(String dir) {
        lastMove = SystemClock.uptimeMillis();
        BottomActivity b = App.I.bottom;
        MainActivity m = App.I.top;
        if (screen == BOTTOM && b != null) b.js("window.deck&&deck.nav('" + dir + "')");
        else if (m != null) m.nav(dir);
    }

    private void ok() {
        BottomActivity b = App.I.bottom;
        MainActivity m = App.I.top;
        if (screen == BOTTOM && b != null) b.js("window.deck&&deck.ok()");
        else if (m != null) m.ok();
    }

    private void back() {
        BottomActivity b = App.I.bottom;
        MainActivity m = App.I.top;
        if (screen == BOTTOM && b != null) b.js("window.deck&&deck.back()");
        else if (m != null) m.back();
    }

    private void hold(String d) {
        if (Objects.equals(d, held)) return;
        held = d;
        h.removeCallbacks(repeat);
        if (d == null) return;
        move(d);
        h.postDelayed(repeat, REPEAT_START);
    }

    private void repeat() {
        if (held == null) return;
        move(held);
        h.postDelayed(repeat, REPEAT_EVERY);
    }

    private void scrollStep() {
        if (scroll == 0) return;
        // Squared, so a slight tilt scrolls slowly.
        int dy = Math.round(scroll * Math.abs(scroll) * 70);
        BottomActivity b = App.I.bottom;
        MainActivity m = App.I.top;
        if (screen == BOTTOM && b != null) b.js("window.deck&&deck.scroll(" + dy + ")");
        else if (m != null) m.scrollPage(dy);
        h.postDelayed(scroller, 33);
    }

    /** A real touch on a WebView, which the page treats as the user's (unlike a scripted click). */
    static void tap(View v, float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        v.dispatchTouchEvent(down);
        v.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private static boolean fromController(KeyEvent e) {
        if (KeyEvent.isGamepadButton(e.getKeyCode())) return true;
        int src = e.getSource();
        return has(src, InputDevice.SOURCE_GAMEPAD) || has(src, InputDevice.SOURCE_JOYSTICK) || has(src, InputDevice.SOURCE_DPAD);
    }

    private static boolean has(int src, int kind) {
        return (src & kind) == kind;
    }

    private static String direction(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            default: return null;
        }
    }

    private static String direction(float x, float y, float min) {
        if (Math.max(Math.abs(x), Math.abs(y)) < min) return null;
        if (Math.abs(x) > Math.abs(y)) return x > 0 ? "right" : "left";
        return y > 0 ? "down" : "up";
    }
}
