package dev.grokbuild.coronamon;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * A round button for his home: the house (opens his messages; can be dragged out of the way), and
 * the room's close and messages buttons. Shows a count of unread messages.
 */
final class HomeButton extends View {
    static final int HOUSE = 0, CLOSE = 1, MAIL = 2;

    interface Listener {
        void tap();

        /** Dragged by (dx, dy) screen pixels since the last call. */
        default void drag(float dx, float dy) {
        }

        default void dropped() {
        }
    }

    final int kind;
    final float dp;
    int badge;
    boolean draggable;
    Listener listener;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final int slop;
    private float downX, downY, lastX, lastY;
    private boolean dragging, down;

    HomeButton(Context ctx, int kind) {
        super(ctx);
        this.kind = kind;
        dp = ctx.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        setContentDescription(kind == HOUSE ? "Coronamon's messages" : kind == CLOSE ? "Close Coronamon's home" : "Messages");
    }

    void setBadge(int n) {
        if (n == badge) return;
        badge = n;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float rad = Math.min(w, h) / 2 - 6 * dp;
        float cx = w / 2, cy = h / 2 + (kind == HOUSE ? 2 * dp : 0);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(0x40000000);
        c.drawCircle(cx, cy + 2 * dp, rad, paint);
        if (kind == HOUSE) {
            paint.setShader(new RadialGradient(cx - rad * 0.3f, cy - rad * 0.4f, rad * 1.6f,
                    new int[]{0xFFFFB45A, 0xFFF0703A, 0xFFC8402A}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
        } else {
            paint.setColor(down ? 0xCC1C1A24 : 0x991C1A24);
        }
        c.drawCircle(cx, cy, rad, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * dp);
        paint.setColor(kind == HOUSE ? 0xFFFFE2B0 : 0x66FFFFFF);
        c.drawCircle(cx, cy, rad, paint);
        float s = rad / 22f;
        if (kind == HOUSE) house(c, cx, cy, s);
        else if (kind == CLOSE) cross(c, cx, cy, s);
        else mail(c, cx, cy, s);
        if (badge > 0) {
            float bx = cx + rad * 0.72f, by = cy - rad * 0.72f, br = 9.5f * dp;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFE02A3A);
            c.drawCircle(bx, by, br, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f * dp);
            paint.setColor(0xFFFFFFFF);
            c.drawCircle(bx, by, br, paint);
            text.setTextSize(11 * dp);
            c.drawText(badge > 9 ? "9+" : String.valueOf(badge), bx, by + 4 * dp, text);
        }
    }

    /** s is a dp scaled to the button: the glyphs are drawn for a 22 dp radius. */
    private void house(Canvas c, float cx, float cy, float s) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        r.set(cx + 4 * s, cy - 12 * s, cx + 7.5f * s, cy - 4 * s);
        c.drawRect(r, paint);
        path.rewind();
        path.moveTo(cx - 12 * s, cy - 1 * s);
        path.lineTo(cx, cy - 12 * s);
        path.lineTo(cx + 12 * s, cy - 1 * s);
        path.close();
        c.drawPath(path, paint);
        r.set(cx - 8.5f * s, cy - 2.5f * s, cx + 8.5f * s, cy + 10 * s);
        c.drawRoundRect(r, 1.5f * s, 1.5f * s, paint);
        paint.setColor(0xFFE8582E);
        r.set(cx - 2.6f * s, cy + 2.5f * s, cx + 2.6f * s, cy + 10 * s);
        c.drawRoundRect(r, 2.4f * s, 2.4f * s, paint);
        // A little flame curling out of the chimney: he's in.
        paint.setColor(0xFFFFD25A);
        path.rewind();
        path.moveTo(cx + 5.7f * s, cy - 13 * s);
        path.quadTo(cx + 2.5f * s, cy - 16 * s, cx + 6.5f * s, cy - 20.5f * s);
        path.quadTo(cx + 9.5f * s, cy - 16 * s, cx + 5.7f * s, cy - 13 * s);
        path.close();
        c.drawPath(path, paint);
    }

    private void cross(Canvas c, float cx, float cy, float s) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(3 * s);
        paint.setColor(0xFFFFFFFF);
        c.drawLine(cx - 7 * s, cy - 7 * s, cx + 7 * s, cy + 7 * s, paint);
        c.drawLine(cx + 7 * s, cy - 7 * s, cx - 7 * s, cy + 7 * s, paint);
    }

    private void mail(Canvas c, float cx, float cy, float s) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2.4f * s);
        paint.setColor(0xFFFFFFFF);
        r.set(cx - 10 * s, cy - 7 * s, cx + 10 * s, cy + 7 * s);
        c.drawRoundRect(r, 2 * s, 2 * s, paint);
        path.rewind();
        path.moveTo(cx - 9 * s, cy - 5.5f * s);
        path.lineTo(cx, cy + 1.5f * s);
        path.lineTo(cx + 9 * s, cy - 5.5f * s);
        c.drawPath(path, paint);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float rx = e.getRawX(), ry = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = rx;
                downY = lastY = ry;
                dragging = false;
                down = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (draggable && !dragging && Math.hypot(rx - downX, ry - downY) > slop) dragging = true;
                if (dragging && listener != null) listener.drag(rx - lastX, ry - lastY);
                lastX = rx;
                lastY = ry;
                return true;
            case MotionEvent.ACTION_UP:
                down = false;
                invalidate();
                if (listener == null) return true;
                if (dragging) listener.dropped();
                else if (Math.hypot(rx - downX, ry - downY) <= slop * 2) listener.tap();
                return true;
            case MotionEvent.ACTION_CANCEL:
                down = false;
                invalidate();
                if (dragging && listener != null) listener.dropped();
                return true;
            default:
                return true;
        }
    }
}
