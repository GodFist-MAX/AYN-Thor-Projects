package dev.grokbuild.coronamon;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/** Draws the puppet on an Android canvas. */
final class AndroidGfx implements Gfx {
    private Canvas c;
    private final Path path = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final RectF rect = new RectF();
    private float alpha = 1;
    private float[] alphas = new float[32];
    private int depth;

    AndroidGfx() {
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void begin(Canvas canvas) {
        c = canvas;
        alpha = 1;
        depth = 0;
    }

    public void save() {
        c.save();
        if (depth == alphas.length) alphas = java.util.Arrays.copyOf(alphas, depth * 2);
        alphas[depth++] = alpha;
    }

    public void restore() {
        c.restore();
        alpha = alphas[--depth];
    }

    public void translate(float x, float y) {
        c.translate(x, y);
    }

    public void rotate(float degrees) {
        c.rotate(degrees);
    }

    public void scale(float sx, float sy) {
        c.scale(sx, sy);
    }

    public void path() {
        path.rewind();
    }

    public void moveTo(float x, float y) {
        path.moveTo(x, y);
    }

    public void lineTo(float x, float y) {
        path.lineTo(x, y);
    }

    public void quadTo(float cx, float cy, float x, float y) {
        path.quadTo(cx, cy, x, y);
    }

    public void cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
        path.cubicTo(c1x, c1y, c2x, c2y, x, y);
    }

    public void close() {
        path.close();
    }

    public void oval(float cx, float cy, float rx, float ry) {
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
        path.addOval(rect, Path.Direction.CW);
    }

    private int mul(int argb) {
        if (alpha >= 0.999f) return argb;
        int a = Math.round((argb >>> 24) * alpha);
        return (Math.max(0, Math.min(255, a)) << 24) | (argb & 0xFFFFFF);
    }

    private int[] mul(int[] colors) {
        int[] out = new int[colors.length];
        for (int i = 0; i < colors.length; i++) out[i] = mul(colors[i]);
        return out;
    }

    public void fill(int argb) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(mul(argb));
        c.drawPath(path, paint);
    }

    public void fillLinear(float x0, float y0, float x1, float y1, int[] colors, float[] stops) {
        if (x0 == x1 && y0 == y1) y1 += 0.01f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setShader(new LinearGradient(x0, y0, x1, y1, mul(colors), stops, Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
        paint.setShader(null);
    }

    public void fillRadial(float cx, float cy, float r, int[] colors, float[] stops) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setShader(new RadialGradient(cx, cy, Math.max(0.01f, r), mul(colors), stops, Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
        paint.setShader(null);
    }

    public void stroke(int argb, float width) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(mul(argb));
        c.drawPath(path, paint);
    }

    public void clip() {
        c.clipPath(path);
    }

    public void alpha(float a) {
        alpha *= a;
    }
}
