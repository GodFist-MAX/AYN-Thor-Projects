package dev.grokbuild.coronamon;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

/** Speech bubble: types the line out, optionally offers buttons, and points its tail at his head. */
final class Bubble extends View {
    interface Listener {
        void chose(int index);
        void dismissed();
    }

    private final float dp;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint btn = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final RectF r = new RectF();

    private String message = "", source = "", note = "";
    private String[] buttons = new String[0];
    private RectF[] buttonRects = new RectF[0];
    private StaticLayout layout, sourceLayout, noteLayout;
    private long startedAt;
    private int shown;
    private float maxWidth;
    /** Tail tip x within the bubble, in px. */
    float tailX = -1;
    Listener listener;

    private static final float CPS = 42;

    Bubble(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        text.setTextSize(15 * dp);
        text.setColor(0xFF3A1A10);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        small.setTextSize(11 * dp);
        small.setColor(0xFF9A6A55);
        small.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC));
        name.setTextSize(10.5f * dp);
        name.setColor(0xFFFFFFFF);
        name.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        name.setLetterSpacing(0.08f);
        btn.setTextSize(13.5f * dp);
        btn.setColor(0xFFFFFFFF);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * dp);
        line.setColor(0xFFE65E37);
        line.setStrokeJoin(Paint.Join.ROUND);
        maxWidth = 300 * dp;
    }

    void show(String msg, String src, String[] buttons) {
        this.message = msg == null ? "" : msg;
        this.source = src == null ? "" : src;
        this.note = "";
        this.buttons = buttons == null ? new String[0] : buttons;
        buttonRects = new RectF[this.buttons.length];
        startedAt = SystemClock.uptimeMillis();
        shown = 0;
        layout = null;
        requestLayout();
        postInvalidateOnAnimation();
    }

    /** A small line under the text that can change while the bubble is up, like what you're saying to him. */
    void note(String n) {
        n = n == null ? "" : n;
        if (n.equals(note)) return;
        note = n;
        requestLayout();
        invalidate();
    }

    boolean typing() {
        return shown < message.length();
    }

    boolean hasButtons() {
        return buttons.length > 0;
    }

    /** Milliseconds a reader needs once the text has been typed out. */
    long readTime() {
        return (long) (message.length() / CPS * 1000) + 1800 + message.length() * 45L;
    }

    private void build(int width) {
        int w = Math.max(1, width);
        layout = StaticLayout.Builder.obtain(message, 0, message.length(), text, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.08f).build();
        sourceLayout = source.isEmpty() ? null : StaticLayout.Builder.obtain("— " + source, 0, source.length() + 2, small, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        noteLayout = note.isEmpty() ? null : StaticLayout.Builder.obtain(note, 0, note.length(), small, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
    }

    private float pad() {
        return 13 * dp;
    }

    private float tailH() {
        return 12 * dp;
    }

    private float nameH() {
        return 18 * dp;
    }

    private float buttonsH() {
        return buttons.length == 0 ? 0 : 44 * dp;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float want = Math.min(maxWidth, StaticLayout.getDesiredWidth(message, text) + 2);
        if (!note.isEmpty()) want = Math.max(want, Math.min(maxWidth, StaticLayout.getDesiredWidth(note, small) + 2));
        float btnW = 0;
        for (String b : buttons) btnW += btn.measureText(b) + 34 * dp;
        want = Math.max(want, Math.min(maxWidth, btnW));
        want = Math.max(want, 110 * dp);
        build((int) want);
        float h = pad() + nameH() + layout.getHeight() + (sourceLayout != null ? sourceLayout.getHeight() + 4 * dp : 0)
                + (noteLayout != null ? noteLayout.getHeight() + 4 * dp : 0) + buttonsH() + pad() + tailH();
        setMeasuredDimension((int) (want + pad() * 2 + 4 * dp), (int) (h + 4 * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (layout == null) return;
        float w = getWidth(), h = getHeight();
        float in = 2 * dp;
        float bottom = h - tailH() - in;
        float rad = 16 * dp;
        float tx = tailX < 0 ? w / 2 : Math.max(rad + 12 * dp, Math.min(w - rad - 12 * dp, tailX));
        shape.rewind();
        r.set(in, in, w - in, bottom);
        shape.addRoundRect(r, rad, rad, Path.Direction.CW);
        Path tail = new Path();
        tail.moveTo(tx - 10 * dp, bottom - 1);
        tail.quadTo(tx - 2 * dp, bottom + tailH() * 0.4f, tx + 2 * dp, h - in);
        tail.quadTo(tx + 3 * dp, bottom + tailH() * 0.3f, tx + 10 * dp, bottom - 1);
        tail.close();
        shape.op(tail, Path.Op.UNION);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xF7FFFBF4);
        c.drawPath(shape, fill);
        c.drawPath(shape, line);

        float x = in + pad(), y = in + pad() * 0.8f;
        String tag = "CORONAMON";
        float tw = name.measureText(tag) + 14 * dp;
        r.set(x - 2 * dp, y - 2 * dp, x + tw, y + 14 * dp);
        fill.setColor(0xFFE65E37);
        c.drawRoundRect(r, 8 * dp, 8 * dp, fill);
        c.drawText(tag, x + 5 * dp, y + 9.5f * dp, name);

        long elapsed = SystemClock.uptimeMillis() - startedAt;
        int target = Math.min(message.length(), (int) (elapsed * CPS / 1000));
        if (target != shown) shown = target;
        y = in + pad() + nameH();
        c.save();
        c.translate(x, y);
        if (shown < message.length()) {
            StaticLayout part = StaticLayout.Builder.obtain(message, 0, shown, text, layout.getWidth())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.08f).build();
            part.draw(c);
            postInvalidateOnAnimation();
        } else {
            layout.draw(c);
        }
        c.restore();
        y += layout.getHeight();
        if (noteLayout != null) {
            c.save();
            c.translate(x, y + 4 * dp);
            noteLayout.draw(c);
            c.restore();
            y += noteLayout.getHeight() + 4 * dp;
        }
        if (sourceLayout != null && shown >= message.length()) {
            c.save();
            c.translate(x, y + 4 * dp);
            sourceLayout.draw(c);
            c.restore();
            y += sourceLayout.getHeight() + 4 * dp;
        }
        if (buttons.length > 0) {
            float bx = x, by = y + 8 * dp;
            for (int i = 0; i < buttons.length; i++) {
                float bw = btn.measureText(buttons[i]) + 28 * dp;
                RectF br = new RectF(bx, by, bx + bw, by + 30 * dp);
                buttonRects[i] = br;
                fill.setColor(i == 0 ? 0xFFE65E37 : 0xFF8A7A72);
                c.drawRoundRect(br, 15 * dp, 15 * dp, fill);
                c.drawText(buttons[i], bx + 14 * dp, by + 20 * dp, btn);
                bx += bw + 8 * dp;
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
        if (typing()) {
            startedAt = 0;
            invalidate();
            return true;
        }
        for (int i = 0; i < buttonRects.length; i++) {
            RectF br = buttonRects[i];
            if (br != null && br.contains(e.getX(), e.getY())) {
                if (listener != null) listener.chose(i);
                return true;
            }
        }
        if (listener != null) listener.dismissed();
        return true;
    }
}
