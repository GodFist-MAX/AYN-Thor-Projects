package dev.grokbuild.coronamon;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Calendar;

/**
 * His room on a screen: the back layer, drawn into a bitmap every ten minutes of the day with the
 * TV's glass left see-through for the video behind it, then him and the front layer every frame.
 * The room is scaled to fit the screen's height and centred; walls and floor fill the sides.
 */
final class RoomView extends View {
    interface Touch {
        void tapPet();

        void pressPet();

        void rubPet();

        void releasePet();

        void tapSeat(int seat);

        void tapTv();
    }

    final Room.Scene scene = new Room.Scene();
    float scale = 1, ox, oy;
    Brain brain;
    Touch touch;
    /** What's on his computer screen, and whether the TV shows a video. */
    int screen = Room.SCREEN_SAVER;
    boolean tvOn;

    private final Room room = new Room();
    private final Puppet puppet = new Puppet();
    private final AndroidGfx gfx = new AndroidGfx();
    private final Pose posed = new Pose();
    private final Paint clear = new Paint();
    private Bitmap back;
    private int backSlot = -1;
    private final float[] p = new float[2], h = new float[2];

    private final int slop;
    private float downX, downY, rubX, rubY, rubDist;
    private long downAt;
    private boolean onPet, pressed, moved;
    private final Runnable longPress = () -> {
        if (onPet && !moved) {
            pressed = true;
            if (touch != null) touch.pressPet();
        }
    };

    RoomView(Context ctx) {
        super(ctx);
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
    }

    @Override
    protected void onSizeChanged(int w, int hh, int ow, int oh) {
        scale = Math.min(w / Room.W, hh / Room.H);
        ox = (w - Room.W * scale) / 2;
        oy = (hh - Room.H * scale) / 2;
        if (back != null) back.recycle();
        back = null;
    }

    static float hourNow() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f + c.get(Calendar.SECOND) / 3600f;
    }

    private void renderBack(float hour) {
        int w = getWidth(), hh = getHeight();
        if (w <= 0 || hh <= 0) return;
        if (back == null) back = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        // Opaque around the room too (the top screen is wider than it): the window takes every touch.
        back.eraseColor(0xFF16131D);
        Canvas c = new Canvas(back);
        c.translate(ox, oy);
        c.scale(scale, scale);
        gfx.begin(c);
        room.drawBack(gfx, hour);
        c.drawRect(Room.TV_L, Room.TV_T, Room.TV_R, Room.TV_B, clear);
        backSlot = (int) (hour * 6);
    }

    @Override
    protected void onDraw(Canvas c) {
        Brain b = brain;
        if (b == null) return;
        float hour = hourNow();
        if (back == null || (int) (hour * 6) != backSlot) renderBack(hour);
        if (back != null) c.drawBitmap(back, 0, 0, null);
        scene.t = b.actor.clock;
        scene.hour = hour;
        scene.seat = b.seat;
        scene.blend = b.seatBlend;
        scene.x = b.x;
        scene.y = b.y;
        posed.set(b.actor.pose);
        Room.seatPose(posed, scene.seat, scene.blend);
        scene.pose = posed;
        scene.clock = b.actor.clock;
        scene.monitor = screen;
        scene.tvOn = tvOn;
        scene.cheer = b.cheer;
        c.save();
        c.translate(ox, oy);
        c.scale(scale, scale);
        gfx.begin(c);
        room.drawFront(gfx, puppet, scene);
        c.restore();
    }

    /** The top of his head in view pixels, for the speech bubble. */
    void headTop(float[] out) {
        Room.headTop(scene, out);
        out[0] = ox + out[0] * scale;
        out[1] = oy + out[1] * scale;
    }

    // ---- touch ----

    private boolean hitPet(float ux, float uy) {
        if (scene.pose == null) return false;
        if (Room.tucked(scene)) return ux > 90 && ux < 440 && uy > 520 && uy < 730;
        Room.petAt(scene, p);
        Room.headTop(scene, h);
        return Math.abs(ux - p[0]) < 70 * Room.K && uy > h[1] - 12 && uy < p[1] + 14;
    }

    private static boolean inTv(float ux, float uy) {
        return ux > Room.TV_L - 14 && ux < Room.TV_R + 14 && uy > Room.TV_T - 14 && uy < Room.TV_B + 16;
    }

    private static int hitSeat(float ux, float uy) {
        if (ux > 770 && ux < 1040 && uy > 780 && uy < 990) return Room.BEANBAG;
        if ((ux > 600 && ux < 800 && uy > 680 && uy < 950) || (ux > Room.MON_L && ux < Room.MON_R && uy > Room.MON_T && uy < 600)) return Room.CHAIR;
        if (ux > 20 && ux < 450 && uy > 470 && uy < 800) return Room.BED;
        return Room.NONE;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float ux = (e.getX() - ox) / scale, uy = (e.getY() - oy) / scale;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = rubX = e.getX();
                downY = rubY = e.getY();
                downAt = e.getEventTime();
                onPet = hitPet(ux, uy);
                pressed = moved = false;
                rubDist = 0;
                if (onPet) postDelayed(longPress, 550);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > slop) moved = true;
                if (pressed) {
                    rubDist += Math.abs(e.getX() - rubX) + Math.abs(e.getY() - rubY);
                    if (rubDist > slop * 3) {
                        rubDist = 0;
                        if (touch != null) touch.rubPet();
                    }
                } else if (moved) {
                    removeCallbacks(longPress);
                }
                rubX = e.getX();
                rubY = e.getY();
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (touch == null) return true;
                if (pressed) {
                    touch.releasePet();
                } else if (!moved && e.getEventTime() - downAt < 500) {
                    if (onPet) touch.tapPet();
                    else if (inTv(ux, uy)) touch.tapTv();
                    else {
                        int seat = hitSeat(ux, uy);
                        if (seat != Room.NONE) touch.tapSeat(seat);
                    }
                }
                pressed = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                if (pressed && touch != null) touch.releasePet();
                pressed = false;
                return true;
            default:
                return true;
        }
    }
}
