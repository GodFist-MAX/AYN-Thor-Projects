package dev.grokbuild.coronamon;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayDeque;

/** Java2D backend for previewing the puppet on the Mac. */
final class AwtGfx implements Gfx {
    private final Graphics2D g;
    private Path2D.Float p = new Path2D.Float();
    private float alpha = 1f;
    private final ArrayDeque<Object[]> stack = new ArrayDeque<>();

    AwtGfx(Graphics2D g) {
        this.g = g;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
    }

    public void save() {
        stack.push(new Object[]{g.getTransform(), g.getClip(), alpha});
    }

    public void restore() {
        Object[] s = stack.pop();
        g.setTransform((AffineTransform) s[0]);
        g.setClip((Shape) s[1]);
        alpha = (Float) s[2];
    }

    public void translate(float x, float y) {
        g.translate(x, y);
    }

    public void rotate(float d) {
        g.rotate(Math.toRadians(d));
    }

    public void scale(float sx, float sy) {
        g.scale(sx, sy);
    }

    public void path() {
        p = new Path2D.Float();
    }

    public void moveTo(float x, float y) {
        p.moveTo(x, y);
    }

    public void lineTo(float x, float y) {
        p.lineTo(x, y);
    }

    public void quadTo(float cx, float cy, float x, float y) {
        p.quadTo(cx, cy, x, y);
    }

    public void cubicTo(float a, float b, float c, float d, float x, float y) {
        p.curveTo(a, b, c, d, x, y);
    }

    public void close() {
        p.closePath();
    }

    public void oval(float cx, float cy, float rx, float ry) {
        p.append(new Ellipse2D.Float(cx - rx, cy - ry, rx * 2, ry * 2), false);
    }

    private Color c(int argb) {
        int a = Math.round(((argb >>> 24) & 0xff) * alpha);
        return new Color((argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff, Math.max(0, Math.min(255, a)));
    }

    private Color[] cs(int[] colors) {
        Color[] out = new Color[colors.length];
        for (int i = 0; i < colors.length; i++) out[i] = c(colors[i]);
        return out;
    }

    public void fill(int argb) {
        g.setPaint(c(argb));
        g.fill(p);
    }

    public void fillLinear(float x0, float y0, float x1, float y1, int[] colors, float[] stops) {
        if (x0 == x1 && y0 == y1) y1 += 0.01f;
        g.setPaint(new LinearGradientPaint(x0, y0, x1, y1, stops, cs(colors)));
        g.fill(p);
    }

    public void fillRadial(float cx, float cy, float r, int[] colors, float[] stops) {
        g.setPaint(new RadialGradientPaint(cx, cy, Math.max(0.01f, r), stops, cs(colors)));
        g.fill(p);
    }

    public void stroke(int argb, float width) {
        g.setPaint(c(argb));
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);
    }

    public void clip() {
        g.clip(p);
    }

    public void alpha(float a) {
        alpha *= a;
    }
}
