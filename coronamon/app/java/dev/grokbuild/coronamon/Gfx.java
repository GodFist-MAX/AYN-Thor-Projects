package dev.grokbuild.coronamon;

/**
 * The few drawing calls the puppet needs. Android draws through {@link AndroidGfx}; the desktop
 * preview (preview/AwtGfx.java) draws the same code with Java2D. Fills and strokes use the current
 * path, which stays until {@link #path()} starts a new one.
 */
interface Gfx {
    void save();

    void restore();

    void translate(float x, float y);

    /** Clockwise on screen, in degrees. */
    void rotate(float degrees);

    void scale(float sx, float sy);

    void path();

    void moveTo(float x, float y);

    void lineTo(float x, float y);

    void quadTo(float cx, float cy, float x, float y);

    void cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y);

    void close();

    void oval(float cx, float cy, float rx, float ry);

    void fill(int argb);

    /** Stops must be strictly increasing. */
    void fillLinear(float x0, float y0, float x1, float y1, int[] colors, float[] stops);

    void fillRadial(float cx, float cy, float r, int[] colors, float[] stops);

    /** Round caps and joins. */
    void stroke(int argb, float width);

    /** Intersects the clip with the current path until the next restore. */
    void clip();

    /** Multiplies the alpha of everything drawn until the next restore. */
    void alpha(float a);
}
