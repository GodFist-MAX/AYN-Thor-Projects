package dev.grokbuild.coronamon;

import static dev.grokbuild.coronamon.Pose.*;

/**
 * Draws Coronamon from a {@link Pose}, proportioned like his Time Stranger model. Units: about 220
 * from the feet to the tips of the mane; the origin is between the feet on the ground, y points
 * down, and the puppet faces +x before FACING mirrors it. Body parts are filled shapes with a dark
 * outline, like the game's toon shading.
 */
final class Puppet {
    static final float WIDTH = 230, HEIGHT = 285, ROOT_Y = 272;
    private static final float SPIN_Y = 100;
    /** The head is drawn in its own units (face 84 tall) and scaled down onto the body. */
    private static final float HEAD_SCALE = 0.94f;

    private static final int OUTLINE = 0xFF43160E;
    private static final int SKIN_LIGHT = 0xFFF6864F, SKIN = 0xFFE46837, SKIN_SHADE = 0xFFC24E2C, SKIN_DEEP = 0xFF993826;
    private static final int MANE_LIGHT = 0xFFF5824B, MANE = 0xFFDF6234, MANE_SHADE = 0xFFB8472A, MANE_DEEP = 0xFF8E2A18;
    private static final int GOLD_LIGHT = 0xFFF6D85E, GOLD = 0xFFDFB236, GOLD_SHADE = 0xFFAE7F19, GOLD_LINE = 0xFF724E0D;
    private static final int CLAW = 0xFFFFD93D;
    private static final int BAND = 0xFF261917, BAND_LIGHT = 0xFF5E4A44;
    private static final int PUPIL = 0xFF0B1220, NOSE = 0xFFEFA04A, MOUTH_IN = 0xFF651616, TONGUE = 0xFFF07E86;
    private static final int FLAME_OUT = 0xFFFF5A14, FLAME_MID = 0xFFFFA62E, FLAME_IN = 0xFFFFF4C0;
    private static final int[] FLAME_COLS = {FLAME_OUT, FLAME_MID, FLAME_IN, 0xFFFF3A0A};
    private static final float[] FLAME_SIZES = {1f, 0.7f, 0.42f};
    /** The forehead flame burns hotter: a white-yellow core with an orange rim. */
    private static final int[] HOT_COLS = {0xFFFF7A1E, 0xFFFFCF45, 0xFFFFFCEB, 0xFFFF4A12};
    private static final float[] HOT_SIZES = {1f, 0.78f, 0.55f};
    private static final int[] IRIS_BLUE = {0xFF16447F, 0xFF2D98DE, 0xFF92E8F4};
    private static final int[] IRIS_GREEN = {0xFF145A2C, 0xFF35B257, 0xFFA8F5B8};

    /**
     * The left half of the head's outline, traced from his Time Stranger model's front view: a
     * broad flat crown, a flame-tipped mane lock at the top corner, wavy sides that pinch in above
     * the eyes, three cheek spikes and the jaw, from the top centre down to the chin. A Catmull-Rom
     * loop is built from it and its mirror image; repeated points make sharp tips.
     */
    private static final float[] HEAD_L = {
            0, -41.5f, -10, -41.5f, -20, -41.8f, -26, -43.5f, -24, -50, -26.5f, -58, -24, -66, -26, -74,
            -29.5f, -82, -35, -88, -42, -90.5f, -48, -87, -48, -87, -42.5f, -84.5f, -38.5f, -80,
            -41, -75, -45, -76, -50, -81, -50, -81, -48.5f, -73, -46.5f, -66, -50, -66, -56, -68, -56, -68,
            -53.5f, -60, -51.5f, -52, -55, -52, -59.5f, -53, -59.5f, -53, -56.5f, -45, -54, -38,
            -54.5f, -30, -50.5f, -24, -45.5f, -20,
            -48, -15, -45, -10, -39.5f, -6, -38.5f, -1.5f, -44.5f, 0.5f, -44.5f, 0.5f, -44, 6, -50, 10,
            -57, 13, -57, 13, -47.5f, 18.5f, -50.5f, 23.5f, -50.5f, 23.5f, -41, 25.5f, -30, 29, -21, 35.5f,
            -11, 40, 0, 42};
    /** Fur grooves on the left side of the mane, as quadratic strokes: start, control, end. */
    private static final float[][] GROOVES = {{-33, -50, -36, -70, -33, -84}, {-29, -43, -37, -37, -38, -25}, {-45, -52, -44, -62, -41, -70}, {-49, -29, -46, -21, -42, -15}};
    /** Small flame tongues on the crown, behind the head: base x, base y, tip x, tip y, width. */
    private static final float[][] TUFTS = {{-18, -38, -22, -71, 14}, {18, -38, 23, -69, 13}, {-9, -38, -9, -60, 11}, {9, -38, 10, -58, 10}};
    /** Centre of the forehead gem in head coordinates (before the turn offset). */
    private static final float GEM_Y = -12;

    boolean greenEyes;

    private Gfx g;
    private float hipX, hipY, ta, nx, ny, hx, hy, ha, fx;
    private float dir, lean, sqx, sqy, shake, lift, spin;
    private float ex, ey;
    private final float[] lx = new float[37], ly = new float[37], rxs = new float[37], rys = new float[37];

    void draw(Gfx gfx, Pose pose, float t) {
        g = gfx;
        float[] v = pose.v;
        dir = v[FACING] < 0 ? -1 : 1;
        lift = v[LIFT];
        lean = v[LEAN];
        float sq = Math.max(0.5f, v[SQUASH]);
        sqx = 1f / (float) Math.sqrt(sq);
        sqy = sq;
        shake = v[SHAKE] * (float) Math.sin(t * 70);
        spin = v[SPIN];

        float sh = clamp(1 - lift / 150f, 0.25f, 1f);
        if (v[SHADOW] > 0.01f) {
            g.path();
            g.oval(0, 0, 34 * sh, 6.5f * sh);
            g.fill(argb(0.22f * sh * Math.min(1, v[SHADOW]), 0x2A0A05));
        }
        if (v[DUST] > 0.01f) dust(v[DUST], t);

        g.save();
        g.translate(shake, -lift);
        if (spin != 0) {
            g.translate(0, -SPIN_Y);
            g.rotate(spin);
            g.translate(0, SPIN_Y);
        }
        g.scale(dir, 1);
        g.rotate(lean);
        g.scale(sqx, sqy);
        rig(v);
        if (v[AURA] > 0.01f) aura(v[AURA], t, true);
        tail(v, t);
        arm(v, false, t);
        leg(v, false);
        torso();
        leg(v, true);
        head(v, t);
        if (v[BOWL] > 0.01f) bowl(v[BOWL], t);
        arm(v, true, t);
        if (v[AURA] > 0.01f) aura(v[AURA], t, false);
        g.restore();

        effects(v, t);
    }

    // ---- rig ----

    private void rig(float[] v) {
        float sit = v[SIT];
        hipX = -3 * sit;
        hipY = -51 + 1.3f * v[HIP] + 34 * sit;
        ta = v[TORSO];
        nx = hipX + rx(0, -55, ta);
        ny = hipY + ry(0, -55, ta);
        float na = ta * 0.5f;
        hx = nx + rx(1, -36, na);
        hy = ny + ry(1, -36, na) + v[HEAD_Y];
        ha = ta * 0.35f + v[HEAD];
        fx = 5 * v[TURN];
    }

    /** Maps a point in the mirrored, leaning body frame to the unmirrored root frame (into ex, ey). */
    private void toRoot(float x, float y) {
        x *= sqx;
        y *= sqy;
        float px = rx(x, y, lean) * dir, py = ry(x, y, lean);
        if (spin != 0) {
            float c = cos(spin), sn = sin(spin), qy = py + SPIN_Y;
            float qx = px * c - qy * sn;
            py = px * sn + qy * c - SPIN_Y;
            px = qx;
        }
        ex = px + shake;
        ey = py - lift;
    }

    // ---- body parts ----

    private void torso() {
        g.save();
        g.translate(hipX, hipY);
        g.rotate(ta);
        g.path();
        g.moveTo(0, -62);
        g.cubicTo(12, -62, 19.5f, -60, 21.5f, -54);
        g.cubicTo(24.5f, -45, 20.5f, -33, 19.5f, -23);
        g.cubicTo(18.5f, -14, 23.5f, -7, 22, 1);
        g.cubicTo(19.5f, 7.5f, 10, 9.5f, 0, 9.5f);
        g.cubicTo(-10, 9.5f, -19.5f, 7.5f, -22, 1);
        g.cubicTo(-23.5f, -7, -18.5f, -14, -19.5f, -23);
        g.cubicTo(-20.5f, -33, -24.5f, -45, -21.5f, -54);
        g.cubicTo(-19.5f, -60, -12, -62, 0, -62);
        g.close();
        g.fillLinear(-18, -58, 20, 8, new int[]{SKIN_LIGHT, SKIN, SKIN_SHADE}, new float[]{0, 0.45f, 1});
        g.stroke(OUTLINE, 2.4f);
        g.translate(0, -62);
        g.scale(1.18f, 1.1f);
        g.translate(0, 62);
        g.path();
        g.moveTo(-17.5f, -61);
        g.quadTo(0, -67, 17.5f, -61);
        g.quadTo(22.5f, -51, 19.5f, -40);
        g.lineTo(15.5f, -43);
        g.quadTo(15.5f, -33, 11.5f, -25);
        g.lineTo(8, -31);
        g.quadTo(5.5f, -20, 1, -11);
        g.quadTo(-1.5f, -21, -3, -26);
        g.lineTo(-6.5f, -18);
        g.quadTo(-8.5f, -26, -10, -32);
        g.lineTo(-14.5f, -26);
        g.quadTo(-15.5f, -35, -17, -40);
        g.lineTo(-21, -39);
        g.quadTo(-22.5f, -53, -17.5f, -61);
        g.close();
        g.fillLinear(0, -64, 0, -14, new int[]{GOLD_LIGHT, GOLD, GOLD_SHADE}, new float[]{0, 0.5f, 1});
        g.stroke(GOLD_LINE, 1.6f);
        g.path();
        g.moveTo(-9, -57);
        g.quadTo(-10.5f, -46, -8, -36);
        g.moveTo(-2, -58);
        g.quadTo(-2, -41, 0, -24);
        g.moveTo(6, -57);
        g.quadTo(7.5f, -46, 6.5f, -35);
        g.moveTo(12.5f, -55);
        g.quadTo(14, -49, 12.5f, -43);
        g.stroke(argb(0.5f, GOLD_LINE), 1.1f);
        g.restore();
    }

    private void leg(float[] v, boolean front) {
        float sit = v[SIT];
        float jx = hipX + (front ? -8 : 8), jy = hipY - 2;
        float ax = front ? -12 : 13, ay = -7.5f;
        float sx = front ? 22 : 31, sy = -6.5f;
        ax += (sx - ax) * sit;
        ay += (sy - ay) * sit;
        ax += front ? v[FLX] : v[BLX];
        ay -= front ? v[FLY] : v[BLY];

        float l1 = 24, l2 = 23;
        float dx = ax - jx, dy = ay - jy;
        float d = clamp((float) Math.hypot(dx, dy), 3, l1 + l2 - 0.05f);
        float phi = (float) Math.atan2(dy, dx);
        float a = (float) Math.acos(clamp((l1 * l1 + d * d - l2 * l2) / (2 * l1 * d), -1, 1));
        float kx = jx + l1 * (float) Math.cos(phi - a), ky = jy + l1 * (float) Math.sin(phi - a);
        ax = jx + d * (float) Math.cos(phi);
        ay = jy + d * (float) Math.sin(phi);

        int[] cols = front ? new int[]{SKIN_LIGHT, SKIN, SKIN_SHADE} : new int[]{SKIN, SKIN_SHADE, SKIN_DEEP};
        float[] st = {0, 0.5f, 1};
        limb(jx, jy, kx, ky, ax, ay, 18f, 11.5f);
        g.fillLinear(jx - 10, jy - 8, ax + 10, ay + 6, cols, st);
        g.stroke(OUTLINE, 2.3f);

        g.save();
        g.translate(ax, ay);
        float lifted = front ? v[FLY] : v[BLY];
        g.rotate(-Math.min(lifted, 10) * 1.2f * (1 - sit) - 30 * sit);
        g.path();
        g.moveTo(-7, -4.5f);
        g.cubicTo(2, -9.5f, 16, -7.5f, 21.5f, 0.5f);
        g.cubicTo(24, 5, 20, 7.5f, 13.5f, 7.5f);
        g.lineTo(-4.5f, 7.5f);
        g.cubicTo(-11, 7.5f, -11.5f, -2, -7, -4.5f);
        g.close();
        g.fillLinear(-4, -8, 18, 8, cols, st);
        g.stroke(OUTLINE, 2.3f);
        g.path();
        g.moveTo(15.5f, -3.5f);
        g.quadTo(17.5f, 1.5f, 16.5f, 6.8f);
        g.moveTo(10.5f, -5.5f);
        g.quadTo(12.5f, 0.5f, 11.5f, 7.2f);
        g.moveTo(5.5f, -6.5f);
        g.quadTo(7.5f, 0, 6.5f, 7.3f);
        g.stroke(argb(0.5f, OUTLINE), 1.2f);
        claw(21.8f, 5.0f, 8, 1.25f);
        claw(18.2f, 7.2f, 14, 1.25f);
        claw(13.4f, 7.8f, 20, 1.2f);
        claw(8.6f, 7.9f, 26, 1.1f);
        g.restore();
    }

    private void arm(float[] v, boolean front, float t) {
        float s = front ? v[FS] : v[BS], e = front ? v[FE] : v[BE];
        float slx = front ? -15 : 14, sly = 9;
        float sx = nx + rx(slx, sly, ta), sy = ny + ry(slx, sly, ta);
        float ua = ta - s;
        float ux = rx(0, 1, ua), uy = ry(0, 1, ua);
        float exx = sx + ux * 23f, eyy = sy + uy * 23f;
        float fa = ua - e;
        float fdx = rx(0, 1, fa), fdy = ry(0, 1, fa);
        float wx = exx + fdx * 20, wy = eyy + fdy * 20;
        float px = wx + fdx * 11.5f, py = wy + fdy * 11.5f;
        int[] cols = front ? new int[]{SKIN_LIGHT, SKIN, SKIN_SHADE} : new int[]{SKIN, SKIN_SHADE, SKIN_DEEP};
        float[] st = {0, 0.5f, 1};

        limb(sx, sy, exx, eyy, wx, wy, 14f, 12.5f);
        g.fillLinear(sx - 8, sy - 8, wx + 8, wy + 8, cols, st);
        g.stroke(OUTLINE, 2.3f);

        float glow = front ? v[F_GLOW] : v[B_GLOW];
        if (glow > 0.01f) {
            g.path();
            g.oval(px, py, 25 * glow, 25 * glow);
            g.fillRadial(px, py, 25 * glow, new int[]{argb(0.75f, 0xFFD060), argb(0f, 0xFF8020)}, new float[]{0, 1});
        }

        g.save();
        g.translate(wx, wy);
        g.rotate(fa);
        g.path();
        g.moveTo(-8.6f, -9.5f);
        g.lineTo(8.6f, -9.5f);
        g.lineTo(9.2f, 2.5f);
        g.lineTo(-9.2f, 2.5f);
        g.close();
        g.fillLinear(-6, 0, 6, 0, new int[]{BAND_LIGHT, BAND, BAND}, new float[]{0, 0.35f, 1});
        g.stroke(OUTLINE, 1.6f);
        g.restore();

        boolean open = (front ? v[F_OPEN] : v[B_OPEN]) > 0.5f;
        g.save();
        g.translate(px, py);
        g.rotate(fa);
        g.scale(1.2f, 1.2f);
        g.path();
        if (open) g.oval(0, 2.5f, 10.6f, 12.4f);
        else g.oval(0, 1.5f, 11.8f, 11f);
        g.fillLinear(-6, -6, 6, 7, cols, st);
        g.stroke(OUTLINE, 1.9f);
        if (open) {
            claw(-5.4f, 9.4f, 104, 1.05f);
            claw(-1.8f, 11.4f, 94, 1.05f);
            claw(1.8f, 11.4f, 86, 1.05f);
            claw(5.4f, 9.4f, 76, 1.05f);
        } else {
            g.path();
            g.moveTo(-8, 2f);
            g.quadTo(0, 4.8f, 8, 2f);
            g.moveTo(-2.8f, 3.6f);
            g.lineTo(-3.2f, 7.4f);
            g.moveTo(2.8f, 3.6f);
            g.lineTo(3.2f, 7.4f);
            g.stroke(argb(0.6f, OUTLINE), 1f);
            claw(-5.6f, 8.8f, 100, 1.1f);
            claw(0, 10f, 90, 1.1f);
            claw(5.6f, 8.8f, 80, 1.1f);
        }
        g.restore();
        if (glow > 0.01f) {
            flame(px, py - 4, 15 * glow, 8 * glow, t, front ? 11 : 17);
        }
        if (front && v[BOWL] > 0.01f) whisk(px, py, fa, v[BOWL]);
    }

    private void tail(float[] v, float t) {
        float bx = hipX - 15, by = hipY + 1;
        float a = v[TAIL] + 7 * (float) Math.sin(t * 2.1f);
        float curl = v[TAIL_CURL];
        float c1x = bx + rx(-20, 10, a), c1y = by + ry(-20, 10, a);
        float c2x = bx + rx(-50, 2 - 6 * curl, a), c2y = by + ry(-50, 2 - 6 * curl, a);
        float tx = bx + rx(-48 + 10 * curl, -32 + 4 * curl, a), ty = by + ry(-48 + 10 * curl, -32 + 4 * curl, a);
        g.path();
        taper(bx, by, c1x, c1y, c2x, c2y, tx, ty, 9, 5.5f, 0, false, true);
        g.fillLinear(bx, by - 30, bx - 40, by, new int[]{SKIN, SKIN_SHADE}, new float[]{0, 1});
        g.stroke(OUTLINE, 2.2f);
        fireball(tx, ty - 4, 12.5f * (0.8f + 0.2f * v[TAIL_FLAME]), v[TAIL_FLAME], t);
    }

    private void fireball(float x, float y, float r, float power, float t) {
        float pulse = 1 + 0.05f * (float) Math.sin(t * 9);
        g.path();
        g.oval(x, y, r * 2.6f * pulse, r * 2.6f * pulse);
        g.fillRadial(x, y, r * 2.6f * pulse, new int[]{argb(0.55f, 0xFFB84A), argb(0f, 0xFF7A20)}, new float[]{0, 1});
        flame(x + 1, y - r * 0.4f, r * 2.1f * power, r * 1.5f, t, 5);
        g.path();
        g.oval(x, y, r * pulse, r * pulse);
        g.fillRadial(x - r * 0.3f, y - r * 0.35f, r * 1.25f, new int[]{0xFFFFFFFF, 0xFFFFF6B8, 0xFFFFBE40, 0xFFFF7A1A}, new float[]{0, 0.35f, 0.75f, 1});
        g.stroke(argb(0.5f, 0xC4400E), 1.2f);
    }

    private void head(float[] v, float t) {
        g.save();
        g.translate(hx, hy);
        g.rotate(ha);
        g.scale(HEAD_SCALE, HEAD_SCALE);
        mane(t);
        headBase(t);
        face(v, t);
        circletBars();
        float fl = v[FLAME] * (1 - 0.75f * Math.min(1, v[HAT])) + v[CHARGE] * 1.3f;
        float flh = fl <= 1 ? fl : 1 + 0.5f * (fl - 1);
        if (fl > 0.03f) flame(fx, GEM_Y - 3, 66 * flh, 24f * (0.75f + 0.25f * Math.min(fl, 1.6f)), t, 0, HOT_COLS, HOT_SIZES);
        gem(v, t);
        if (v[CHARGE] > 0.01f) {
            g.path();
            g.oval(fx, GEM_Y, 30 * v[CHARGE], 30 * v[CHARGE]);
            g.fillRadial(fx, GEM_Y, 30 * v[CHARGE], new int[]{argb(0.8f, 0xFFF0A0), argb(0f, 0xFF9030)}, new float[]{0, 1});
        }
        if (v[HAT] > 0.01f) hat(v[HAT]);
        g.restore();
    }

    private final float[] headPts = new float[HEAD_L.length * 2 - 4];

    private void mane(float t) {
        for (int i = 0; i < TUFTS.length; i++) {
            float[] k = TUFTS[i];
            float sway = 2.5f * (float) Math.sin(t * 1.7f + i * 2.1f);
            float o = fx * 0.4f;
            float tx = k[2] + sway, ty = k[3];
            g.path();
            taper(o + k[0], k[1], o + k[0] + (tx - k[0]) * 0.2f, k[1] + (ty - k[1]) * 0.45f,
                    o + tx - (tx - k[0]) * 0.35f + 3, k[1] + (ty - k[1]) * 0.75f, o + tx, ty, k[4], 0, 2, false, false);
            g.fillLinear(0, -56, 0, -38, new int[]{MANE_LIGHT, MANE, MANE_SHADE}, new float[]{0, 0.4f, 1});
            g.stroke(OUTLINE, 2.2f);
        }
    }

    /** Builds the full head outline from HEAD_L and its mirror image, letting the lock tips ripple. */
    private void headPoints(float t) {
        int n = HEAD_L.length / 2;
        for (int i = 0; i < n; i++) {
            float x = HEAD_L[2 * i], y = HEAD_L[2 * i + 1];
            headPts[2 * i] = x + ripple(y, t, 0);
            headPts[2 * i + 1] = y;
        }
        for (int i = n - 2, j = n; i >= 1; i--, j++) {
            float x = HEAD_L[2 * i], y = HEAD_L[2 * i + 1];
            headPts[2 * j] = -x - ripple(y, t, 1.7f);
            headPts[2 * j + 1] = y;
        }
    }

    private static float ripple(float y, float t, float phase) {
        return 1.6f * clamp((-y - 48) / 30f, 0, 1) * (float) Math.sin(t * 2.2f + y * 0.12f + phase);
    }

    /**
     * The head and mane as one shape. The mane round the edge is shaded deeper than the face, with
     * fur grooves where it meets the forehead.
     */
    private void headBase(float t) {
        headPoints(t);
        g.path();
        loop(headPts);
        g.fillLinear(-26, -42, 30, 40, new int[]{SKIN_LIGHT, SKIN, SKIN, SKIN_SHADE}, new float[]{0, 0.4f, 0.7f, 1});
        g.save();
        g.clip();
        g.path();
        g.oval(fx, 8, 120, 120);
        g.fillRadial(fx, 8, 74, new int[]{argb(0f, MANE_DEEP), argb(0f, MANE_DEEP), argb(0.5f, MANE_DEEP)}, new float[]{0, 0.55f, 1});
        g.restore();
        g.path();
        loop(headPts);
        g.stroke(OUTLINE, 2.4f);
        g.path();
        for (int side = -1; side <= 1; side += 2) {
            for (float[] gr : GROOVES) {
                g.moveTo(-side * gr[0], gr[1]);
                g.quadTo(-side * gr[2], gr[3], -side * gr[4], gr[5]);
            }
        }
        g.stroke(argb(0.55f, SKIN_DEEP), 1.4f);
        g.path();
        g.oval(fx - 8, -27, 24, 15);
        g.fillRadial(fx - 8, -27, 24, new int[]{argb(0.28f, 0xFFE2C4), argb(0f, 0xFFE2C4)}, new float[]{0, 1});
    }

    private void face(float[] v, float t) {
        float lookX = v[LOOK_X], lookY = v[LOOK_Y];
        eye(fx - 19.5f, 12.5f, 10f, 7.9f, 18, v, lookX, lookY);
        eye(fx + 18.5f, 12.5f, 9.4f * (1 - 0.07f * v[TURN]), 7.7f, -18, v, lookX, lookY);

        float b = v[BROW];
        if (Math.abs(b) > 0.05f) {
            g.save();
            g.alpha(Math.min(1, Math.abs(b) * 1.5f));
            g.path();
            float lift = b > 0 ? 0 : -3 * b;
            g.moveTo(fx - 29, -1 - (b > 0 ? 2 * b : -lift * 0.3f));
            g.quadTo(fx - 21, -4, fx - 13, 1 + 3 * b - lift);
            g.moveTo(fx + 12, 1 + 3 * b - lift);
            g.quadTo(fx + 20, -4, fx + 28, -1 - (b > 0 ? 2 * b : -lift * 0.3f));
            g.stroke(OUTLINE, 2.6f);
            g.restore();
        }

        if (v[BLUSH] > 0.01f) {
            g.path();
            g.oval(fx - 29, 24, 6.5f, 3.4f);
            g.oval(fx + 27, 24, 6f, 3.2f);
            g.fill(argb(0.4f * Math.min(1, v[BLUSH]), 0xFF5A5A));
        }
        g.path();
        g.oval(fx - 31, 23.5f, 1.7f, 1.7f);
        g.oval(fx + 29, 23.5f, 1.6f, 1.6f);
        g.fill(argb(0.85f, 0xFFE2B0));

        float nxo = fx + 1;
        g.path();
        g.moveTo(nxo - 2.9f, 23.6f);
        g.quadTo(nxo, 22.8f, nxo + 2.9f, 23.6f);
        g.quadTo(nxo + 1.5f, 26.6f, nxo, 27f);
        g.quadTo(nxo - 1.5f, 26.6f, nxo - 2.9f, 23.6f);
        g.close();
        g.fill(NOSE);
        g.stroke(OUTLINE, 1.3f);
        g.path();
        g.oval(nxo - 0.9f, 24f, 0.9f, 0.5f);
        g.fill(argb(0.7f, 0xFFFFFF));

        float m = v[MOUTH], s = v[SMILE];
        float my = 30f;
        if (m < 0.07f) {
            g.path();
            g.moveTo(nxo, 27f);
            g.lineTo(nxo, my);
            g.moveTo(nxo - 6.5f, my - 0.6f - 0.4f * s);
            g.quadTo(nxo - 3.2f, my + 3.2f * s + 0.6f, nxo, my);
            g.quadTo(nxo + 3.2f, my + 3.2f * s + 0.6f, nxo + 6.5f, my - 0.6f - 0.4f * s);
            g.stroke(OUTLINE, 1.9f);
        } else {
            float d = 3 + 9 * m;
            float wide = 6.5f + 1.5f * m;
            g.path();
            g.moveTo(nxo - wide, my - 0.5f);
            g.quadTo(nxo - wide * 0.5f, my + 2.8f, nxo, my + 0.3f);
            g.quadTo(nxo + wide * 0.5f, my + 2.8f, nxo + wide, my - 0.5f);
            g.cubicTo(nxo + wide * 0.95f, my + d * (0.75f + 0.1f * s), nxo + 3, my + d, nxo, my + d);
            g.cubicTo(nxo - 3, my + d, nxo - wide * 0.95f, my + d * (0.75f + 0.1f * s), nxo - wide, my - 0.5f);
            g.close();
            g.fill(MOUTH_IN);
            g.save();
            g.clip();
            g.path();
            g.oval(nxo, my + d - 0.5f, 5.4f, 2.2f + 3.4f * m);
            g.fill(TONGUE);
            g.restore();
            g.path();
            g.moveTo(nxo - wide, my - 0.5f);
            g.quadTo(nxo - wide * 0.5f, my + 2.8f, nxo, my + 0.3f);
            g.quadTo(nxo + wide * 0.5f, my + 2.8f, nxo + wide, my - 0.5f);
            g.cubicTo(nxo + wide * 0.95f, my + d * (0.75f + 0.1f * s), nxo + 3, my + d, nxo, my + d);
            g.cubicTo(nxo - 3, my + d, nxo - wide * 0.95f, my + d * (0.75f + 0.1f * s), nxo - wide, my - 0.5f);
            g.stroke(OUTLINE, 1.8f);
            g.path();
            g.moveTo(nxo, 27f);
            g.lineTo(nxo, my + 0.3f);
            g.stroke(OUTLINE, 1.6f);
        }
    }

    /** The eye: a flat, heavy top lid over a round bottom. */
    private void eyePath(float ew, float eh) {
        g.path();
        g.moveTo(-ew, -eh * 0.45f);
        g.cubicTo(-ew * 0.85f, -eh * 1.05f, ew * 0.85f, -eh * 1.05f, ew, -eh * 0.45f);
        g.cubicTo(ew * 1.08f, eh * 1.45f, -ew * 1.08f, eh * 1.45f, -ew, -eh * 0.45f);
        g.close();
    }

    private void eye(float cx, float cy, float ew, float eh, float tilt, float[] v, float lookX, float lookY) {
        float open = clamp(v[EYES], 0, 1), happy = v[HAPPY], wide = v[WIDE];
        ew *= 1 + 0.1f * wide;
        eh *= 1 + 0.22f * wide;
        g.save();
        g.translate(cx, cy);
        g.rotate(tilt);
        if (happy > 0.5f) {
            g.path();
            g.moveTo(-ew, 3);
            g.quadTo(0, -eh * 1.4f, ew, 3);
            g.stroke(OUTLINE, 3.4f);
            g.restore();
            return;
        }
        if (open < 0.12f) {
            g.path();
            g.moveTo(-ew, 0);
            g.quadTo(0, 5, ew, 0);
            g.stroke(OUTLINE, 3f);
            g.path();
            g.moveTo(ew * (tilt > 0 ? -1 : 1), 0);
            g.lineTo(ew * (tilt > 0 ? -1.35f : 1.35f), -2.5f);
            g.stroke(OUTLINE, 2.2f);
            g.restore();
            return;
        }
        eyePath(ew, eh);
        g.fill(0xFFFFFFFF);
        g.save();
        g.clip();
        float ix = lookX * 3f, iy = lookY * 2.4f + 1.2f;
        float iw = 8.1f * (1 - 0.14f * wide), ih = 9.8f * (1 - 0.14f * wide);
        int[] iris = greenEyes ? IRIS_GREEN : IRIS_BLUE;
        g.path();
        g.oval(ix, iy, iw, ih);
        g.fillLinear(ix, iy - ih, ix, iy + ih, iris, new float[]{0.12f, 0.55f, 1});
        g.stroke(argb(0.85f, 0x0A2040), 1.1f);
        float pr = 1 - 0.35f * wide;
        g.path();
        g.oval(ix, iy + 0.4f, 3.6f * pr, 5.2f * pr);
        g.fill(PUPIL);
        g.path();
        g.oval(ix + 2.7f, iy - 3.4f, 2.9f, 2.9f);
        g.fill(0xFFFFFFFF);
        g.path();
        g.oval(ix - 2.7f, iy + 4f, 1.4f, 1.4f);
        g.fill(0xE6FFFFFF);
        g.path();
        g.moveTo(-ew - 1, -eh - 2);
        g.lineTo(ew + 1, -eh - 2);
        g.lineTo(ew + 1, -eh * 0.45f + 2.4f);
        g.quadTo(0, -eh * 1.35f + 4f, -ew - 1, -eh * 0.45f + 2.4f);
        g.close();
        g.fill(argb(0.22f, 0x0A1430));
        float lid = -eh + (1 - open) * 2 * eh;
        if (open < 0.98f) {
            g.path();
            g.moveTo(-ew - 2, -eh - 2);
            g.lineTo(ew + 2, -eh - 2);
            g.lineTo(ew + 2, lid);
            g.quadTo(0, lid + 3, -ew - 2, lid);
            g.close();
            g.fill(SKIN);
        }
        float b = v[BROW];
        if (b > 0.05f) {
            float inner = tilt > 0 ? 1 : -1;
            g.path();
            g.moveTo(inner * (ew + 2), -eh - 2);
            g.lineTo(inner * (ew + 2), -eh + eh * 0.9f * b);
            g.lineTo(-inner * ew * 0.3f, -eh - 2);
            g.close();
            g.fill(SKIN);
        }
        g.restore();
        eyePath(ew, eh);
        g.stroke(argb(0.5f, OUTLINE), 1.1f);
        float w = open >= 0.98f ? ew : ew * (float) Math.sqrt(Math.max(0, 1 - (lid / eh) * (lid / eh)));
        g.path();
        if (open >= 0.98f) {
            g.moveTo(-ew - 0.5f, -eh * 0.45f + 1.2f);
            g.cubicTo(-ew * 0.85f - 0.5f, -eh * 1.05f - 0.6f, ew * 0.85f + 0.5f, -eh * 1.05f - 0.6f, ew + 0.5f, -eh * 0.45f + 1.2f);
        } else {
            g.moveTo(-w, lid);
            g.quadTo(0, lid + 3 * (w / ew), w, lid);
        }
        g.stroke(OUTLINE, 3.8f);
        float outer = tilt > 0 ? -1 : 1;
        float oy = open >= 0.98f ? -eh * 0.45f : lid;
        g.path();
        g.moveTo(outer * ew * 0.7f, oy - (open >= 0.98f ? eh * 0.32f : 0));
        g.quadTo(outer * (ew + 1), oy - 0.6f, outer * (ew + 3.4f), oy - 1.8f);
        g.lineTo(outer * (ew + 0.2f), oy + 1.4f);
        g.close();
        g.fill(OUTLINE);
        g.stroke(OUTLINE, 1.2f);
        g.restore();
    }

    /** The two black bars of the circlet, rising from the gem's ring up into the mane. */
    private void circletBars() {
        float gx = fx, gy = GEM_Y;
        for (int sd = -1; sd <= 1; sd += 2) {
            g.path();
            taper(gx + sd * 9.5f, gy - 6.5f, gx + sd * 12f, gy - 15, gx + sd * 17f, gy - 24, gx + sd * 25f, gy - 30, 11f, 9f, 0, true, true);
            g.fillLinear(gx, gy - 32, gx + sd * 24, gy, new int[]{0xFF4E3A34, BAND, 0xFF140C0A}, new float[]{0, 0.45f, 1});
            g.stroke(OUTLINE, 1.6f);
            g.path();
            taper(gx + sd * 11.4f, gy - 11f, gx + sd * 13.4f, gy - 17f, gx + sd * 17.6f, gy - 24f, gx + sd * 23f, gy - 28.4f, 1.9f, 1.5f, 0, true, true);
            g.fill(argb(0.7f, 0x8A746C));
        }
    }

    /** The glowing forehead gem in its black ring; drawn over the base of the forehead flame. */
    private void gem(float[] v, float t) {
        float gx = fx, gy = GEM_Y;
        float glow = clamp(0.6f + 0.4f * (float) Math.sin(t * 2.6f) + v[CHARGE], 0, 1.5f);
        g.path();
        g.oval(gx, gy, 27, 27);
        g.fillRadial(gx, gy, 27, new int[]{argb(0.55f * glow, 0xFFB45A), argb(0f, 0xFF8A3A)}, new float[]{0, 1});
        g.path();
        arc(gx, gy, 14.8f, -60, 240, true);
        arc(gx, gy, 9.9f, 240, -60, false);
        g.close();
        g.fillLinear(gx, gy - 13, gx, gy + 13, new int[]{0xFF4E3A34, BAND, 0xFF140C0A}, new float[]{0, 0.5f, 1});
        g.stroke(OUTLINE, 1.6f);
        g.path();
        arc(gx, gy, 11.6f, -40, 220, true);
        g.stroke(0xFFE8783C, 1.6f);
        g.path();
        g.oval(gx, gy, 9.7f, 9.7f);
        g.fillRadial(gx - 2f, gy - 2f, 11.5f, new int[]{0xFFFFFFFF, 0xFFFFE27A, 0xFFFF8A1E, 0xFFE2380E}, new float[]{0, 0.25f, 0.6f, 1});
        g.stroke(argb(0.85f, OUTLINE), 1.2f);
    }

    /** Arc from angle a0 to a1 (degrees, clockwise from +x), joined to the current path. */
    private void arc(float cx, float cy, float r, float a0, float a1, boolean move) {
        int n = 20;
        for (int i = 0; i <= n; i++) {
            float a = a0 + (a1 - a0) * i / n;
            float x = cx + r * cos(a), y = cy + r * sin(a);
            if (i == 0 && move) g.moveTo(x, y);
            else g.lineTo(x, y);
        }
    }

    private void hat(float h) {
        g.save();
        g.translate(fx * 0.6f, -36);
        g.rotate(-6);
        g.scale(h, h);
        g.path();
        g.oval(-14, -26, 15, 14);
        g.oval(2, -32, 16, 15);
        g.oval(16, -24, 13, 12);
        g.oval(0, -20, 20, 12);
        g.fillLinear(0, -46, 0, -10, new int[]{0xFFFFFFFF, 0xFFF1F1F6, 0xFFD7D9E6}, new float[]{0, 0.6f, 1});
        g.stroke(OUTLINE, 2.2f);
        g.path();
        g.moveTo(-26, -12);
        g.quadTo(0, -16, 26, -12);
        g.lineTo(25, 1);
        g.quadTo(0, -2, -25, 1);
        g.close();
        g.fillLinear(0, -14, 0, 1, new int[]{0xFFFFFFFF, 0xFFDCDDE8}, new float[]{0, 1});
        g.stroke(OUTLINE, 2.2f);
        g.path();
        g.moveTo(-8, -13);
        g.lineTo(-8, -1);
        g.moveTo(8, -13);
        g.lineTo(8, -1);
        g.stroke(argb(0.25f, OUTLINE), 1.2f);
        g.restore();
    }

    private void bowl(float b, float t) {
        float cx = hipX + 7, cy = hipY - 24;
        g.save();
        g.translate(cx, cy);
        g.scale(b, b);
        g.path();
        g.oval(0, -6, 19, 5.5f);
        g.fill(0xFFFFE6A8);
        g.path();
        g.moveTo(-19, -6);
        g.cubicTo(-18, 10, 18, 10, 19, -6);
        g.quadTo(0, -1, -19, -6);
        g.close();
        g.fillLinear(-19, 0, 19, 0, new int[]{0xFFFFFFFF, 0xFFE4EEFF, 0xFFB9C9E6}, new float[]{0, 0.5f, 1});
        g.stroke(OUTLINE, 2.2f);
        g.path();
        g.oval(0, -6, 19, 5.5f);
        g.stroke(OUTLINE, 1.8f);
        g.path();
        g.moveTo(-12, 1);
        g.quadTo(0, 4, 12, 1);
        g.stroke(argb(0.5f, 0xFF7A4C), 2.2f);
        g.restore();
    }

    private void whisk(float px, float py, float fa, float b) {
        g.save();
        g.translate(px, py);
        g.rotate(fa + 150);
        g.scale(b, b);
        g.path();
        g.moveTo(0, 0);
        g.lineTo(0, 14);
        g.stroke(0xFF8A5A2A, 3.4f);
        g.path();
        g.moveTo(0, 13);
        g.cubicTo(-8, 22, -4, 30, 0, 30);
        g.cubicTo(4, 30, 8, 22, 0, 13);
        g.moveTo(0, 13);
        g.cubicTo(-3, 22, -1.5f, 30, 0, 30);
        g.cubicTo(1.5f, 30, 3, 22, 0, 13);
        g.stroke(0xFFB8C2D6, 1.4f);
        g.restore();
    }

    private void aura(float a, float t, boolean back) {
        g.save();
        g.alpha(back ? a : a * 0.55f);
        if (back) {
            g.path();
            g.oval(hipX, hipY - 50, 95, 110);
            g.fillRadial(hipX, hipY - 50, 105, new int[]{argb(0.5f, 0xFFB040), argb(0.25f, 0xFF6A20), argb(0f, 0xFF4A10)}, new float[]{0, 0.6f, 1});
        }
        float[][] spots = back
                ? new float[][]{{-34, -40, -30}, {34, -42, 30}, {-46, -110, -40}, {48, -112, 40}, {0, -180, 0}, {-30, -168, -20}, {30, -170, 20}, {-20, 0, -15}, {22, 0, 15}}
                : new float[][]{{-26, -20, -20}, {26, -24, 20}, {-12, 2, -8}, {14, 2, 8}};
        for (int i = 0; i < spots.length; i++) {
            float[] s = spots[i];
            float h = (back ? 46 : 30) * (0.8f + 0.25f * (float) Math.sin(t * 7 + i * 2.1f));
            g.save();
            g.translate(hipX + s[0], hipY + s[1] + 30);
            g.rotate(s[2]);
            flame(0, 0, h, h * 0.42f, t, i * 3.3f);
            g.restore();
        }
        g.restore();
    }

    // ---- effects in the root frame ----

    private void effects(float[] v, float t) {
        toRoot(hx, hy);
        float hxr = ex, hyr = ey;
        if (v[ZZZ] > 0.01f) {
            for (int i = 0; i < 3; i++) {
                float ph = (t * 0.45f + i / 3f) % 1f;
                float s = 4 + ph * 6;
                float x = hxr + 26 + ph * 26 + (float) Math.sin(ph * 6 + i) * 5;
                float y = hyr - 48 - ph * 52;
                g.save();
                g.alpha(v[ZZZ] * (ph < 0.15f ? ph / 0.15f : 1 - (ph - 0.15f) / 0.85f));
                g.path();
                g.moveTo(x - s, y - s);
                g.lineTo(x + s, y - s);
                g.lineTo(x - s, y + s);
                g.lineTo(x + s, y + s);
                g.stroke(0xFF3A2A5A, 4.6f);
                g.stroke(0xFFFFFFFF, 2.4f);
                g.restore();
            }
        }
        if (v[HEARTS] > 0.01f) {
            for (int i = 0; i < 3; i++) {
                float ph = (t * 0.6f + i / 3f) % 1f;
                float x = hxr + (i - 1) * 26 + (float) Math.sin(ph * 7 + i * 2) * 6;
                float y = hyr - 46 - ph * 58;
                g.save();
                g.alpha(v[HEARTS] * (1 - ph));
                heart(x, y, 6 + 3 * (1 - ph));
                g.restore();
            }
        }
        if (v[NOTES] > 0.01f) {
            for (int i = 0; i < 3; i++) {
                float ph = (t * 0.5f + i / 3f) % 1f;
                float x = hxr + (i % 2 == 0 ? -1 : 1) * (34 + ph * 20);
                float y = hyr - 30 - ph * 60;
                g.save();
                g.alpha(v[NOTES] * Math.min(1, (1 - ph) * 2));
                note(x, y, 1 + 0.2f * i);
                g.restore();
            }
        }
        if (v[SPARKLE] > 0.01f) {
            for (int i = 0; i < 6; i++) {
                float ph = (t * 0.9f + i * 0.37f) % 1f;
                float ang = i * 60 + t * 20;
                float r = 70 + 20 * (float) Math.sin(i * 3 + t);
                float x = hxr + cos(ang) * r, y = hyr + 30 + sin(ang) * r * 0.9f;
                float s = 7 * (float) Math.sin(ph * Math.PI);
                g.save();
                g.alpha(v[SPARKLE]);
                star(x, y, s, 0xFFFFF4B0);
                g.restore();
            }
        }
        if (v[SWEAT] > 0.01f) {
            float x = hxr + dir * 44, y = hyr - 26 + 6 * ((t * 0.8f) % 1f);
            g.save();
            g.alpha(v[SWEAT]);
            g.path();
            g.moveTo(x, y - 9);
            g.cubicTo(x + 6, y - 1, x + 6, y + 5, x, y + 5);
            g.cubicTo(x - 6, y + 5, x - 6, y - 1, x, y - 9);
            g.close();
            g.fillLinear(x, y - 9, x, y + 5, new int[]{0xFFE6F8FF, 0xFF8ED8FF}, new float[]{0, 1});
            g.stroke(0xFF2A6A9A, 1.6f);
            g.restore();
        }
        if (v[QUESTION] > 0.01f) {
            float x = hxr + dir * 30, y = hyr - 80 - 3 * (float) Math.sin(t * 4);
            g.save();
            g.alpha(v[QUESTION]);
            g.path();
            g.moveTo(x - 7, y - 6);
            g.cubicTo(x - 7, y - 16, x + 8, y - 16, x + 8, y - 7);
            g.cubicTo(x + 8, y - 1, x, y, x, y + 5);
            g.stroke(0xFF3A2A5A, 7);
            g.stroke(0xFFFFFFFF, 3.6f);
            g.path();
            g.oval(x, y + 12, 3, 3);
            g.fill(0xFFFFFFFF);
            g.stroke(0xFF3A2A5A, 1.6f);
            g.restore();
        }
        if (v[EXCLAIM] > 0.01f) {
            float x = hxr + dir * 26, y = hyr - 86;
            float pop = 1 + 0.15f * (float) Math.sin(t * 14);
            g.save();
            g.alpha(v[EXCLAIM]);
            g.translate(x, y);
            g.scale(pop, pop);
            g.path();
            g.moveTo(-3.5f, -16);
            g.lineTo(3.5f, -16);
            g.lineTo(1.8f, 4);
            g.lineTo(-1.8f, 4);
            g.close();
            g.fill(0xFFFFE04A);
            g.stroke(0xFF3A2A5A, 2);
            g.path();
            g.oval(0, 11, 3.2f, 3.2f);
            g.fill(0xFFFFE04A);
            g.stroke(0xFF3A2A5A, 2);
            g.restore();
        }
        if (v[ANGER] > 0.01f) {
            float x = hxr + dir * 32, y = hyr - 40;
            float s = 7 * (1 + 0.12f * (float) Math.sin(t * 10));
            g.save();
            g.alpha(v[ANGER]);
            g.path();
            for (int q = 0; q < 4; q++) {
                float a = q * 90 + 45;
                float cx = x + cos(a) * s, cy = y + sin(a) * s;
                g.moveTo(cx + cos(a + 60) * s * 0.8f, cy + sin(a + 60) * s * 0.8f);
                g.quadTo(cx, cy, cx + cos(a - 60) * s * 0.8f, cy + sin(a - 60) * s * 0.8f);
            }
            g.stroke(0xFFE8302A, 3);
            g.restore();
        }
        if (v[DIZZY] > 0.01f) {
            for (int i = 0; i < 3; i++) {
                float a = t * 200 + i * 120;
                float x = hxr + cos(a) * 34, y = hyr - 46 + sin(a) * 9;
                g.save();
                g.alpha(v[DIZZY]);
                star(x, y, 6, 0xFFFFE04A);
                g.restore();
            }
        }
    }

    private void dust(float d, float t) {
        for (int i = 0; i < 4; i++) {
            float ph = (t * 1.6f + i * 0.25f) % 1f;
            float x = (i % 2 == 0 ? -1 : 1) * (14 + ph * 30);
            float r = 4 + ph * 8;
            g.path();
            g.oval(x, -r * 0.6f, r, r * 0.8f);
            g.fill(argb(0.45f * d * (1 - ph), 0xD8C8B8));
        }
    }

    private void heart(float x, float y, float s) {
        g.path();
        g.moveTo(x, y + s * 0.9f);
        g.cubicTo(x - s * 1.6f, y - s * 0.2f, x - s * 0.6f, y - s * 1.5f, x, y - s * 0.55f);
        g.cubicTo(x + s * 0.6f, y - s * 1.5f, x + s * 1.6f, y - s * 0.2f, x, y + s * 0.9f);
        g.close();
        g.fill(0xFFFF5C7A);
        g.stroke(0xFFFFFFFF, 1.6f);
    }

    private void note(float x, float y, float s) {
        g.save();
        g.translate(x, y);
        g.scale(s, s);
        g.path();
        g.oval(0, 8, 4.6f, 3.4f);
        g.fill(0xFF3A2A6A);
        g.stroke(0xFFFFFFFF, 1.2f);
        g.path();
        g.moveTo(4, 7);
        g.lineTo(4, -9);
        g.quadTo(9, -6, 10, -1);
        g.stroke(0xFFFFFFFF, 4);
        g.stroke(0xFF3A2A6A, 2);
        g.restore();
    }

    private void star(float x, float y, float s, int color) {
        if (s <= 0.2f) return;
        g.path();
        g.moveTo(x, y - s);
        g.quadTo(x, y, x + s, y);
        g.quadTo(x, y, x, y + s);
        g.quadTo(x, y, x - s, y);
        g.quadTo(x, y, x, y - s);
        g.close();
        g.fill(color);
        g.stroke(argb(0.6f, 0xFF9A20), 1);
    }

    /** Three-layer flame rising from (x, y). */
    void flame(float x, float y, float h, float w, float t, float seed) {
        flame(x, y, h, w, t, seed, FLAME_COLS, FLAME_SIZES);
    }

    /**
     * Three-layer flame. cols: outer layer at the base, middle layer, inner layer, outer layer at
     * the tip; sizes: each layer's size relative to the outer one.
     */
    private void flame(float x, float y, float h, float w, float t, float seed, int[] cols, float[] sizes) {
        for (int i = 0; i < 3; i++) {
            float hh = h * sizes[i] * (1 + 0.13f * (float) Math.sin(t * 13 + seed + i));
            float ww = w * sizes[i] * (i == 2 ? 1.1f : 1f);
            float sway = (float) (Math.sin(t * 5.3 + seed) * 0.18 + Math.sin(t * 11.7 + seed * 2.3) * 0.07) * h;
            float by = y + (i == 0 ? 0 : 1.5f * i);
            g.path();
            taper(x, by, x - sway * 0.25f, by - hh * 0.35f, x + sway * 0.6f, by - hh * 0.7f, x + sway, by - hh, ww, 0, 1, true, false);
            if (i == 0) {
                g.fillLinear(x, by - hh, x, by, new int[]{argb(0.9f, cols[3]), cols[0]}, new float[]{0, 1});
                g.stroke(argb(0.35f, 0xB0300A), 1);
            } else {
                g.fill(cols[i]);
            }
        }
    }

    private void claw(float x, float y, float angle, float s) {
        g.save();
        g.translate(x, y);
        g.rotate(angle - 90);
        g.scale(s, s);
        g.path();
        g.moveTo(-1.6f, -1.4f);
        g.quadTo(2.4f, -1.2f, 4.2f, 0.6f);
        g.quadTo(1.8f, 1.6f, -1.4f, 1.6f);
        g.close();
        g.fill(CLAW);
        g.stroke(OUTLINE, 0.9f);
        g.restore();
    }

    // ---- shape helpers ----

    /** Adds a closed Catmull-Rom loop through the points (x, y pairs); a repeated point makes a sharp tip. */
    private void loop(float[] p) {
        int n = p.length / 2;
        g.moveTo(p[0], p[1]);
        for (int i = 0; i < n; i++) {
            int a = (i - 1 + n) % n * 2, b = i * 2, c = (i + 1) % n * 2, d = (i + 2) % n * 2;
            g.cubicTo(p[b] + (p[c] - p[a]) / 6, p[b + 1] + (p[c + 1] - p[a + 1]) / 6,
                    p[c] - (p[d] - p[b]) / 6, p[c + 1] - (p[d + 1] - p[b + 1]) / 6, p[c], p[c + 1]);
        }
        g.close();
    }

    /** A soft limb from a to c whose spine passes through the joint b. */
    private void limb(float ax, float ay, float bx, float by, float cx, float cy, float w0, float w1) {
        float qx = 2 * bx - (ax + cx) / 2, qy = 2 * by - (ay + cy) / 2;
        g.path();
        taper(ax, ay, ax + (qx - ax) * 2 / 3, ay + (qy - ay) * 2 / 3, cx + (qx - cx) * 2 / 3, cy + (qy - cy) * 2 / 3,
                cx, cy, w0, w1, 0, true, true);
    }

    private final float[] one = new float[8];

    /**
     * Adds a closed shape around a cubic Bézier spine to the current path. Width runs w0 to w1
     * (profile 0), is a flame tongue that bulges near the base and comes to a point (profile 1), or
     * a fur lock that stays full for a while and then curves to a point (profile 2).
     */
    private void taper(float x0, float y0, float c1x, float c1y, float c2x, float c2y, float x1, float y1,
                       float w0, float w1, int profile, boolean capStart, boolean capEnd) {
        one[0] = x0;
        one[1] = y0;
        one[2] = c1x;
        one[3] = c1y;
        one[4] = c2x;
        one[5] = c2y;
        one[6] = x1;
        one[7] = y1;
        taperChain(one, 4, w0, w1, profile, capStart, capEnd);
    }

    private void taperChain(float[] sp, int points, float w0, float w1, int profile) {
        taperChain(sp, points, w0, w1, profile, false, false);
    }

    /** Like taper, along a chain of cubic segments: sp holds start, then (control, control, end) per segment. */
    private void taperChain(float[] sp, int points, float w0, float w1, int profile, boolean capStart, boolean capEnd) {
        int segs = (points - 1) / 3;
        int per = segs == 1 ? 16 : 12;
        int n = segs * per;
        float tx0 = 0, ty0 = 0, tx1 = 0, ty1 = 0;
        for (int i = 0; i <= n; i++) {
            int sgi = Math.min(segs - 1, i / per);
            float t = (i - sgi * per) / (float) per, u = 1 - t;
            int b = sgi * 6;
            float x0 = sp[b], y0 = sp[b + 1], c1x = sp[b + 2], c1y = sp[b + 3], c2x = sp[b + 4], c2y = sp[b + 5], x1 = sp[b + 6], y1 = sp[b + 7];
            float px = u * u * u * x0 + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * x1;
            float py = u * u * u * y0 + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * y1;
            float dx = 3 * u * u * (c1x - x0) + 6 * u * t * (c2x - c1x) + 3 * t * t * (x1 - c2x);
            float dy = 3 * u * u * (c1y - y0) + 6 * u * t * (c2y - c1y) + 3 * t * t * (y1 - c2y);
            float len = (float) Math.hypot(dx, dy);
            if (len < 1e-4f) {
                dx = x1 - x0;
                dy = y1 - y0;
                len = Math.max(1e-4f, (float) Math.hypot(dx, dy));
            }
            dx /= len;
            dy /= len;
            if (i == 0) {
                tx0 = dx;
                ty0 = dy;
            }
            if (i == n) {
                tx1 = dx;
                ty1 = dy;
            }
            float tt = i / (float) n;
            float w;
            if (profile == 1) {
                w = w0 * (float) Math.pow(1 - tt, 0.8) * (0.62f + 0.38f * (float) Math.sin(Math.PI * Math.min(1, tt * 2.4f + 0.3f)));
            } else if (profile == 2) {
                w = w0 * (tt < 0.22f ? 1 - tt * 0.5f : 0.89f * (float) Math.pow((1 - tt) / 0.78f, 0.85));
            } else {
                w = w0 + (w1 - w0) * tt;
            }
            w *= 0.5f;
            lx[i] = px - dy * w;
            ly[i] = py + dx * w;
            rxs[i] = px + dy * w;
            rys[i] = py - dx * w;
        }
        float ex0 = sp[0], ey0 = sp[1], ex1 = sp[segs * 6], ey1 = sp[segs * 6 + 1];
        g.moveTo(lx[0], ly[0]);
        smooth(lx, ly, n, true);
        if (capEnd && w1 > 0.1f) {
            float r = w1 * 0.5f;
            g.quadTo(ex1 + tx1 * r * 1.6f, ey1 + ty1 * r * 1.6f, rxs[n], rys[n]);
        } else {
            g.lineTo(rxs[n], rys[n]);
        }
        smooth(rxs, rys, n, false);
        if (capStart) {
            float r = w0 * 0.5f;
            g.quadTo(ex0 - tx0 * r * 1.6f, ey0 - ty0 * r * 1.6f, lx[0], ly[0]);
        }
        g.close();
    }

    /** Smooth curve through sample points (quadratics between midpoints), forward or backward. */
    private void smooth(float[] xs, float[] ys, int n, boolean forward) {
        if (forward) {
            for (int i = 1; i < n; i++) g.quadTo(xs[i], ys[i], (xs[i] + xs[i + 1]) / 2, (ys[i] + ys[i + 1]) / 2);
            g.lineTo(xs[n], ys[n]);
        } else {
            for (int i = n - 1; i > 0; i--) g.quadTo(xs[i], ys[i], (xs[i] + xs[i - 1]) / 2, (ys[i] + ys[i - 1]) / 2);
            g.lineTo(xs[0], ys[0]);
        }
    }

    static int argb(float a, int rgb) {
        int al = Math.round(clamp(a, 0, 1) * 255);
        return (al << 24) | (rgb & 0xFFFFFF);
    }

    static float clamp(float x, float lo, float hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float cos(float deg) {
        return (float) Math.cos(Math.toRadians(deg));
    }

    private static float sin(float deg) {
        return (float) Math.sin(Math.toRadians(deg));
    }

    private static float rx(float x, float y, float deg) {
        double r = Math.toRadians(deg);
        return (float) (x * Math.cos(r) - y * Math.sin(r));
    }

    private static float ry(float x, float y, float deg) {
        double r = Math.toRadians(deg);
        return (float) (x * Math.sin(r) + y * Math.cos(r));
    }
}
