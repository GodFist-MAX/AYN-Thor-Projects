package dev.grokbuild.coronamon;

import static dev.grokbuild.coronamon.Puppet.argb;
import static dev.grokbuild.coronamon.Puppet.clamp;

/**
 * Coronamon's room: a bed, a desk with his computer and gaming chair, a bean bag and a TV, drawn
 * with Gfx in a W x H space (the bottom screen's size) so the Mac preview renders it too. The back
 * layer (walls, window, the furniture he never gets in front of) is drawn once and cached; the
 * front layer (the chair, bean bag and blanket he gets into, the screens, the clock hands and the
 * lighting) is drawn every frame around him. Walls and floor run past both sides for wider screens.
 */
final class Room {
    static final float W = 1240, H = 1080;
    /** Where the back wall meets the floor, and the line his feet walk along. */
    static final float FLOOR_Y = 770, WALK_Y = 1012;
    /** His size in the room: design px per puppet unit. */
    static final float K = 0.82f;

    static final int NONE = 0, BED = 1, CHAIR = 2, BEANBAG = 3;
    static final int SCREEN_SAVER = 0, SCREEN_GAME = 1, SCREEN_CODE = 2, SCREEN_OFF = 3;

    static final float TV_L = 927, TV_T = 482, TV_R = 1213, TV_B = 643;
    static final float MON_L = 612, MON_T = 432, MON_R = 788, MON_B = 546;
    /** Where he stands to look out of the window. */
    static final float WINDOW_X = 260;

    private static final float PILLOW_X = 150, HEAD_Y = 602;
    private static final float QUILT_L = 172, QUILT_R = 414;
    private static final float CHAIR_X = 700, SEAT_Y = 764;
    private static final float BAG_CX = 905, BAG_BASE = 978;
    private static final float LAMP_X = 494, LAMP_Y = 618;
    private static final float CLOCK_X = 480, CLOCK_Y = 214, CLOCK_R = 42;
    private static final float WIN_L = 150, WIN_T = 150, WIN_R = 370, WIN_B = 410;

    private static final int LINE = 0xFF2B1810;
    private static final int WALL_TOP = 0xFF3D5A7C, WALL_BOT = 0xFF58799B;
    private static final int TRIM = 0xFFEADFC8, TRIM_SHADE = 0xFFC9B79A;
    private static final int FLOOR_BACK = 0xFF8A5634, FLOOR_FRONT = 0xFFB0774A, PLANK = 0xFF5E361D;
    private static final int WOOD_LIGHT = 0xFFD39C66, WOOD = 0xFFB47B49, WOOD_SHADE = 0xFF8C5A33, WOOD_DARK = 0xFF6B4026;
    private static final int WALNUT = 0xFF6A4330, WALNUT_SHADE = 0xFF4A2D20;
    private static final int SHEET = 0xFFF7F2EA, SHEET_SHADE = 0xFFD9CFC2;
    private static final int QUILT = 0xFFD9493A, QUILT_SHADE = 0xFFA6301F, QUILT_GOLD = 0xFFF5B940;
    private static final int METAL_DARK = 0xFF17191F, METAL = 0xFF2B2E37, METAL_LIGHT = 0xFF4A4E5A;
    private static final int RED = 0xFFE0432F, RED_SHADE = 0xFFA52A1C, BLACK = 0xFF25252D, BLACK_LIGHT = 0xFF3B3B47;
    private static final int BAG = 0xFF7B52B4, BAG_LIGHT = 0xFFA07BDA, BAG_SHADE = 0xFF543283;
    private static final int RUG_OUT = 0xFFC24E31, RUG_RING = 0xFFF0B24A, RUG_IN = 0xFFDB6A3D;
    private static final int CURTAIN = 0xFFE9A23B, CURTAIN_SHADE = 0xFFBF7820;
    private static final int[] BULBS = {0xFFFFC94A, 0xFFFF6A4A, 0xFF7BE07A, 0xFF6AB8FF, 0xFFFF8AD0};

    /** What the front layer needs each frame. */
    static final class Scene {
        float t;
        /** Local time of day in hours, with minutes and seconds as the fraction. */
        float hour = 12;
        int seat = NONE;
        /** 0 standing on the floor .. 1 settled in the seat. */
        float blend;
        /** His feet on the floor, in room units. */
        float x = W / 2, y = WALK_Y;
        Pose pose;
        float clock;
        int monitor = SCREEN_SAVER;
        /** A video is showing, so the TV glass is left clear for it. */
        boolean tvOn;
        /** Arms in the air at the computer, 0..1. */
        float cheer;
    }

    private Gfx g;

    // ---- where he goes ----

    /** Where he stands on the floor to get into a seat. */
    static float seatX(int seat) {
        switch (seat) {
            case BED:
                return 250;
            case CHAIR:
                return CHAIR_X;
            case BEANBAG:
                return BAG_CX - 12;
            default:
                return W / 2;
        }
    }

    /** Which way he faces getting in: the bed and bean bag want him facing right. */
    static float seatFacing(int seat) {
        return 1;
    }

    private static float anchorX(int seat) {
        switch (seat) {
            case BED:
                return 200;
            case CHAIR:
                return CHAIR_X;
            default:
                return BAG_CX + 4;
        }
    }

    private static float anchorY(int seat) {
        switch (seat) {
            case BED:
                return 645;
            case CHAIR:
                return SEAT_Y;
            default:
                return BAG_BASE - 54;
        }
    }

    /** From here on he's tucked in: just his head on the pillow, the rest under the quilt. */
    private static final float TUCKED = 0.6f;

    static boolean tucked(Scene s) {
        return s.seat == BED && s.blend >= TUCKED;
    }

    private static float hopHeight(int seat) {
        return seat == BED ? 70 : seat == CHAIR ? 40 : 50;
    }

    static float ease(float b) {
        b = clamp(b, 0, 1);
        return b * b * (3 - 2 * b);
    }

    /** His drawn feet position (or hips, in the chair) while getting into or out of a seat. */
    static void petAt(Scene s, float[] out) {
        if (s.seat == NONE || s.blend <= 0) {
            out[0] = s.x;
            out[1] = s.y;
            return;
        }
        float e = ease(s.blend);
        out[0] = s.x + (anchorX(s.seat) - s.x) * e;
        out[1] = s.y + (anchorY(s.seat) - s.y) * e - (float) Math.sin(Math.PI * clamp(s.blend, 0, 1)) * hopHeight(s.seat);
    }

    /** Whether he's drawn from behind, sitting at the computer. */
    static boolean backView(Scene s) {
        return s.seat == CHAIR && s.blend >= 0.5f;
    }

    /** Bends the pose for the seat he's in: lying down, sunk into the bean bag, sitting. */
    static void seatPose(Pose p, int seat, float blend) {
        if (seat == NONE || blend <= 0) return;
        float e = ease(blend);
        float[] v = p.v;
        v[Pose.SHADOW] *= 1 - e;
        if (seat == BEANBAG) {
            v[Pose.SIT] = Math.max(v[Pose.SIT], e);
            v[Pose.TORSO] -= 12 * e;
            v[Pose.FACING] = 1;
        } else if (seat == CHAIR) {
            v[Pose.SIT] = Math.max(v[Pose.SIT], e);
        }
    }

    /** The top of his head and its x, for the speech bubble, in room units. */
    static void headTop(Scene s, float[] out) {
        float[] p = new float[2];
        petAt(s, p);
        float lift = s.pose == null ? 0 : s.pose.v[Pose.LIFT];
        if (tucked(s)) {
            out[0] = PILLOW_X + 4;
            out[1] = HEAD_Y - 72;
        } else if (backView(s)) {
            out[0] = p[0];
            out[1] = p[1] - 160 * K;
        } else {
            float sit = s.pose == null ? 0 : s.pose.v[Pose.SIT];
            out[0] = p[0];
            out[1] = p[1] - (232 - 34 * sit + lift) * K;
        }
    }

    // ---- time of day ----

    /** 0 by day .. 1 at night, ramping through dusk and dawn. */
    static float nightness(float h) {
        if (h >= 7.5f && h < 17.5f) return 0;
        if (h >= 17.5f && h < 20.5f) return (h - 17.5f) / 3f;
        if (h >= 20.5f || h < 5f) return 1;
        return 1 - (h - 5f) / 2.5f;
    }

    /** Sunset and sunrise colour, peaking halfway through each. */
    private static float golden(float h) {
        return Math.max(hump(h, 16.5f, 20.5f), hump(h, 4.6f, 8.4f));
    }

    private static float hump(float x, float a, float b) {
        if (x <= a || x >= b) return 0;
        return (float) Math.sin(Math.PI * (x - a) / (b - a));
    }

    /** How dark the room gets: the lamp and screens light it at night. */
    static float darkness(float h) {
        return 0.42f * nightness(h);
    }

    static boolean lampOn(float h) {
        return nightness(h) > 0.25f;
    }

    static int mix(int a, int b, float t) {
        t = clamp(t, 0, 1);
        int r = 0;
        for (int sh = 0; sh <= 24; sh += 8) {
            int ca = (a >>> sh) & 0xFF, cb = (b >>> sh) & 0xFF;
            r |= (Math.round(ca + (cb - ca) * t) & 0xFF) << sh;
        }
        return r;
    }

    // ---- back layer ----

    void drawBack(Gfx gfx, float hour) {
        g = gfx;
        wall();
        window(hour);
        curtains();
        clockFace();
        poster();
        shelf();
        floor();
        rug();
        bed();
        nightstand();
        desk();
        tvStand();
        plant(1170, 1004);
    }

    private void plant(float x, float b) {
        g.path();
        g.oval(x, b + 2, 46, 8);
        g.fill(argb(0.3f, 0x2A1005));
        float[][] leaves = {{-70, -150, -40}, {-36, -200, -16}, {4, -214, 6}, {40, -190, 26}, {70, -140, 44}, {-52, -110, -60}, {56, -100, 64}};
        for (float[] l : leaves) {
            float tx = x + l[0], ty = b - 60 + l[1] * 0.75f;
            float mx = x + l[0] * 0.45f, my = b - 70 + l[1] * 0.4f;
            float nx = (float) Math.cos(Math.toRadians(l[2])) * 16, ny = (float) Math.sin(Math.toRadians(l[2])) * 16;
            g.path();
            g.moveTo(x, b - 60);
            g.quadTo(mx - nx, my - ny, tx, ty);
            g.quadTo(mx + nx, my + ny, x, b - 60);
            g.close();
            g.fillLinear(x, b - 60, tx, ty, new int[]{0xFF2F7A3E, 0xFF5BB466}, new float[]{0, 1});
            g.stroke(LINE, 2);
            g.path();
            g.moveTo(x, b - 60);
            g.quadTo(mx, my, tx, ty);
            g.stroke(argb(0.35f, 0x1A3A20), 1.5f);
        }
        g.path();
        g.moveTo(x - 40, b - 66);
        g.lineTo(x + 40, b - 66);
        g.lineTo(x + 32, b);
        g.lineTo(x - 32, b);
        g.close();
        g.fillLinear(x - 40, 0, x + 40, 0, new int[]{0xFFF4EDE2, 0xFFD2C6B4}, new float[]{0, 1});
        g.stroke(LINE, 3);
        g.path();
        rect(x - 38, b - 52, x + 38, b - 44);
        g.fill(0xFFE0643A);
    }

    private void wall() {
        g.path();
        rect(-2000, 0, W + 2000, FLOOR_Y);
        g.fillLinear(0, 0, 0, FLOOR_Y, new int[]{WALL_TOP, WALL_BOT}, new float[]{0, 1});
        g.path();
        for (float x = -2000 + 18; x < W + 2000; x += 72) rect(x, 34, x + 36, FLOOR_Y - 24);
        g.fill(argb(0.05f, 0xFFFFFF));
        g.path();
        for (float x = -2000 + 36; x < W + 2000; x += 72) {
            for (float y = 70; y < FLOOR_Y - 40; y += 64) {
                float yy = y + ((int) ((x + 2000) / 72) % 2) * 32;
                g.moveTo(x, yy - 5);
                g.lineTo(x + 4, yy);
                g.lineTo(x, yy + 5);
                g.lineTo(x - 4, yy);
                g.close();
            }
        }
        g.fill(argb(0.07f, 0xFFFFFF));
        g.path();
        rect(-2000, 0, W + 2000, 20);
        g.fill(0xFF2C4361);
        g.path();
        rect(-2000, 20, W + 2000, 30);
        g.fill(TRIM);
        g.path();
        rect(-2000, 30, W + 2000, 34);
        g.fill(argb(0.25f, 0x000000));
        g.path();
        rect(-2000, FLOOR_Y - 24, W + 2000, FLOOR_Y);
        g.fill(TRIM);
        g.path();
        rect(-2000, FLOOR_Y - 24, W + 2000, FLOOR_Y - 20);
        g.fill(TRIM_SHADE);
    }

    private void floor() {
        g.path();
        rect(-2000, FLOOR_Y, W + 2000, H + 10);
        g.fillLinear(0, FLOOR_Y, 0, H, new int[]{FLOOR_BACK, FLOOR_FRONT}, new float[]{0, 1});
        float y = FLOOR_Y, gap = 17;
        int row = 0;
        g.path();
        while (y < H) {
            float y2 = Math.min(H + 10, y + gap);
            rect(-2000, y, W + 2000, y + 1.8f);
            float off = (row * 211) % 290;
            for (float x = -2030 + off; x < W + 2000; x += 290) rect(x, y, x + 2.2f, y2);
            y = y2;
            gap *= 1.2f;
            row++;
        }
        g.fill(argb(0.4f, PLANK));
        g.path();
        rect(-2000, FLOOR_Y, W + 2000, FLOOR_Y + 30);
        g.fillLinear(0, FLOOR_Y, 0, FLOOR_Y + 30, new int[]{argb(0.35f, 0x1A0A04), argb(0f, 0x1A0A04)}, new float[]{0, 1});
    }

    private void window(float hour) {
        float n = nightness(hour), gold = golden(hour);
        g.save();
        g.path();
        rect(WIN_L, WIN_T, WIN_R, WIN_B);
        g.clip();
        int top = mix(mix(0xFF5FA6E4, 0xFF0B1132, n), 0xFF6A5AA8, gold * 0.6f);
        int bot = mix(mix(0xFFC6E7FA, 0xFF27326C, n), 0xFFF49A58, gold * 0.85f);
        g.path();
        rect(WIN_L, WIN_T, WIN_R, WIN_B);
        g.fillLinear(0, WIN_T, 0, WIN_B, new int[]{top, bot}, new float[]{0, 1});
        if (n > 0.05f) {
            g.path();
            long seed = 7;
            for (int i = 0; i < 34; i++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                float sx = WIN_L + ((seed >>> 33) % 1000) / 1000f * (WIN_R - WIN_L);
                float sy = WIN_T + ((seed >>> 13) % 1000) / 1000f * (WIN_B - WIN_T - 90);
                float r = i % 5 == 0 ? 1.9f : 1.1f;
                g.oval(sx, sy, r, r);
            }
            g.fill(argb(0.9f * n, 0xFFF6D8));
        }
        if (n < 0.95f) {
            float p = clamp((hour - 6f) / 13.5f, 0, 1);
            float sx = WIN_L + 20 + p * (WIN_R - WIN_L - 40);
            float sy = WIN_B - 60 - (float) Math.sin(Math.PI * p) * (WIN_B - WIN_T - 110);
            int sun = mix(0xFFFFE27A, 0xFFFF8A3A, gold);
            g.path();
            g.oval(sx, sy, 46, 46);
            g.fillRadial(sx, sy, 46, new int[]{argb(0.5f * (1 - n), sun), argb(0f, sun)}, new float[]{0, 1});
            g.path();
            g.oval(sx, sy, 17, 17);
            g.fill(argb(1 - n, sun));
        }
        if (n > 0.2f) {
            float p = ((hour + 24 - 19) % 24) / 11f;
            float mx = WIN_L + 30 + clamp(p, 0, 1) * (WIN_R - WIN_L - 60);
            float my = WIN_B - 70 - (float) Math.sin(Math.PI * clamp(p, 0, 1)) * (WIN_B - WIN_T - 120);
            g.path();
            g.oval(mx, my, 30, 30);
            g.fillRadial(mx, my, 30, new int[]{argb(0.35f * n, 0xDDE6FF), argb(0f, 0xDDE6FF)}, new float[]{0, 1});
            g.path();
            g.oval(mx, my, 14, 14);
            g.fill(argb(n, 0xFFF4F0DC));
            g.path();
            g.oval(mx + 6, my - 4, 12, 12);
            g.fill(top);
        }
        if (n < 0.9f) {
            float a = 0.85f * (1 - n);
            cloud(WIN_L + 58, WIN_T + 70, 1f, mix(0xFFFFFFFF, 0xFFFFC9A0, gold), a);
            cloud(WIN_L + 160, WIN_T + 120, 0.75f, mix(0xFFFFFFFF, 0xFFFFC9A0, gold), a * 0.9f);
        }
        g.path();
        g.moveTo(WIN_L - 10, WIN_B);
        g.lineTo(WIN_L - 10, WIN_B - 70);
        g.quadTo(WIN_L + 50, WIN_B - 110, WIN_L + 120, WIN_B - 80);
        g.quadTo(WIN_L + 180, WIN_B - 56, WIN_R + 10, WIN_B - 96);
        g.lineTo(WIN_R + 10, WIN_B);
        g.close();
        g.fill(mix(mix(0xFF7FAF86, 0xFF1B2444, n), 0xFF9A7A8A, gold * 0.4f));
        g.path();
        g.moveTo(WIN_L - 10, WIN_B);
        g.lineTo(WIN_L - 10, WIN_B - 36);
        g.quadTo(WIN_L + 80, WIN_B - 70, WIN_L + 150, WIN_B - 40);
        g.quadTo(WIN_L + 200, WIN_B - 24, WIN_R + 10, WIN_B - 44);
        g.lineTo(WIN_R + 10, WIN_B);
        g.close();
        g.fill(mix(mix(0xFF4F8C5B, 0xFF111a33, n), 0xFF6A5A6A, gold * 0.4f));
        float[][] houses = {{WIN_L + 40, WIN_B - 46}, {WIN_L + 120, WIN_B - 50}, {WIN_L + 182, WIN_B - 34}};
        for (float[] h : houses) {
            g.path();
            rect(h[0] - 9, h[1] - 8, h[0] + 9, h[1] + 6);
            g.moveTo(h[0] - 12, h[1] - 8);
            g.lineTo(h[0], h[1] - 18);
            g.lineTo(h[0] + 12, h[1] - 8);
            g.close();
            g.fill(mix(0xFFE8D8C0, 0xFF1A2038, n));
            g.path();
            rect(h[0] - 4, h[1] - 4, h[0] + 3, h[1] + 2);
            g.fill(n > 0.3f ? argb(n, 0xFFD070) : 0xFF6A88A8);
        }
        g.path();
        g.moveTo(WIN_L, WIN_T);
        g.lineTo(WIN_L + 70, WIN_T);
        g.lineTo(WIN_L, WIN_T + 80);
        g.close();
        g.moveTo(WIN_L + 96, WIN_T);
        g.lineTo(WIN_L + 116, WIN_T);
        g.lineTo(WIN_L, WIN_T + 132);
        g.lineTo(WIN_L, WIN_T + 110);
        g.close();
        g.fill(argb(0.13f, 0xFFFFFF));
        g.restore();

        g.path();
        rect(WIN_L - 14, WIN_T - 14, WIN_R + 14, WIN_B + 12);
        rectCCW(WIN_L, WIN_T, WIN_R, WIN_B);
        g.fill(TRIM);
        g.stroke(LINE, 3);
        float mx = (WIN_L + WIN_R) / 2, my = (WIN_T + WIN_B) / 2;
        g.path();
        rect(mx - 5, WIN_T, mx + 5, WIN_B);
        rect(WIN_L, my - 5, WIN_R, my + 5);
        g.fill(TRIM);
        g.path();
        rect(mx - 5, WIN_T, mx + 5, WIN_B);
        g.stroke(argb(0.5f, LINE), 1.6f);
        g.path();
        rect(WIN_L, my - 5, WIN_R, my + 5);
        g.stroke(argb(0.5f, LINE), 1.6f);
        g.path();
        rect(WIN_L - 30, WIN_B + 8, WIN_R + 30, WIN_B + 24);
        g.fill(TRIM);
        g.stroke(LINE, 3);
        g.path();
        rect(WIN_L - 26, WIN_B + 24, WIN_R + 26, WIN_B + 30);
        g.fill(argb(0.25f, 0x000000));
    }

    private void cloud(float x, float y, float s, int col, float a) {
        g.path();
        g.oval(x, y, 26 * s, 15 * s);
        g.oval(x + 24 * s, y - 8 * s, 22 * s, 17 * s);
        g.oval(x + 46 * s, y + 2 * s, 22 * s, 13 * s);
        g.oval(x + 22 * s, y + 8 * s, 30 * s, 10 * s);
        g.fill(argb(a * ((col >>> 24) / 255f), col));
    }

    private void curtains() {
        g.path();
        rect(108, 122, 412, 131);
        g.fill(WOOD_DARK);
        g.stroke(LINE, 2);
        g.path();
        g.oval(106, 126, 9, 9);
        g.oval(414, 126, 9, 9);
        g.fill(WOOD_SHADE);
        g.stroke(LINE, 2);
        for (int side = 0; side < 2; side++) {
            g.save();
            if (side == 1) {
                g.translate((WIN_L + WIN_R), 0);
                g.scale(-1, 1);
            }
            g.path();
            g.moveTo(116, 130);
            g.lineTo(190, 130);
            g.cubicTo(194, 200, 170, 280, 174, 330);
            g.cubicTo(178, 372, 202, 410, 206, 448);
            g.quadTo(160, 456, 114, 448);
            g.cubicTo(116, 390, 124, 356, 130, 330);
            g.cubicTo(122, 262, 114, 200, 116, 130);
            g.close();
            g.fillLinear(114, 0, 206, 0, new int[]{CURTAIN, CURTAIN_SHADE, CURTAIN, CURTAIN_SHADE}, new float[]{0, 0.4f, 0.7f, 1});
            g.stroke(LINE, 3);
            g.path();
            g.moveTo(140, 136);
            g.quadTo(136, 240, 148, 326);
            g.moveTo(166, 136);
            g.quadTo(160, 240, 160, 326);
            g.moveTo(146, 340);
            g.quadTo(142, 400, 136, 446);
            g.moveTo(170, 342);
            g.quadTo(176, 400, 182, 448);
            g.stroke(argb(0.45f, CURTAIN_SHADE), 3);
            g.path();
            rrect(124, 322, 182, 338, 7);
            g.fill(RED);
            g.stroke(LINE, 2);
            g.restore();
        }
    }

    private void clockFace() {
        g.path();
        g.oval(CLOCK_X + 4, CLOCK_Y + 5, CLOCK_R + 6, CLOCK_R + 6);
        g.fill(argb(0.25f, 0x0A1020));
        g.path();
        g.oval(CLOCK_X, CLOCK_Y, CLOCK_R + 7, CLOCK_R + 7);
        g.fillLinear(0, CLOCK_Y - CLOCK_R, 0, CLOCK_Y + CLOCK_R, new int[]{WOOD_LIGHT, WOOD_SHADE}, new float[]{0, 1});
        g.stroke(LINE, 3);
        g.path();
        g.oval(CLOCK_X, CLOCK_Y, CLOCK_R - 2, CLOCK_R - 2);
        g.fillRadial(CLOCK_X - 10, CLOCK_Y - 12, CLOCK_R * 1.5f, new int[]{0xFFFFFCF4, 0xFFEDE1CB}, new float[]{0, 1});
        g.stroke(LINE, 2);
        for (int i = 0; i < 12; i++) {
            float a = (float) Math.toRadians(i * 30);
            float r0 = i % 3 == 0 ? CLOCK_R - 15 : CLOCK_R - 10, r1 = CLOCK_R - 6;
            g.path();
            g.moveTo(CLOCK_X + (float) Math.sin(a) * r0, CLOCK_Y - (float) Math.cos(a) * r0);
            g.lineTo(CLOCK_X + (float) Math.sin(a) * r1, CLOCK_Y - (float) Math.cos(a) * r1);
            g.stroke(LINE, i % 3 == 0 ? 3.2f : 1.8f);
        }
    }

    private void poster() {
        float l = 992, t = 128, r = 1148, b = 392;
        g.path();
        rect(l + 6, t + 7, r + 6, b + 7);
        g.fill(argb(0.25f, 0x0A1020));
        g.path();
        rect(l, t, r, b);
        g.fill(0xFF1E1A22);
        g.stroke(LINE, 3);
        g.path();
        rect(l + 10, t + 10, r - 10, b - 10);
        g.fillLinear(0, t, 0, b, new int[]{0xFF2A1D4A, 0xFF8A2A2A, 0xFFE0643A}, new float[]{0, 0.6f, 1});
        float cx = (l + r) / 2, cy = t + 104;
        g.path();
        for (int i = 0; i < 12; i++) {
            float a = (float) Math.toRadians(i * 30 + 15);
            float a0 = a - 0.13f, a1 = a + 0.13f;
            g.moveTo(cx + (float) Math.sin(a0) * 40, cy - (float) Math.cos(a0) * 40);
            g.lineTo(cx + (float) Math.sin(a) * 64, cy - (float) Math.cos(a) * 64);
            g.lineTo(cx + (float) Math.sin(a1) * 40, cy - (float) Math.cos(a1) * 40);
            g.close();
        }
        g.fill(0xFFFFB43A);
        g.path();
        g.oval(cx, cy, 40, 40);
        g.fillRadial(cx - 10, cy - 12, 50, new int[]{0xFFFFF6C8, 0xFFFFC84A, 0xFFFF8A2A}, new float[]{0, 0.5f, 1});
        g.stroke(argb(0.6f, 0x7A2A10), 2);
        g.path();
        g.moveTo(cx - 22, cy + 6);
        g.cubicTo(cx - 20, cy - 14, cx - 6, cy - 28, cx, cy - 40);
        g.cubicTo(cx + 6, cy - 28, cx + 20, cy - 14, cx + 22, cy + 6);
        g.cubicTo(cx + 18, cy + 22, cx - 18, cy + 22, cx - 22, cy + 6);
        g.close();
        g.fill(0xFFFF6A1E);
        g.path();
        g.moveTo(cx - 11, cy + 8);
        g.cubicTo(cx - 10, cy - 4, cx - 3, cy - 14, cx, cy - 22);
        g.cubicTo(cx + 3, cy - 14, cx + 10, cy - 4, cx + 11, cy + 8);
        g.cubicTo(cx + 9, cy + 16, cx - 9, cy + 16, cx - 11, cy + 8);
        g.close();
        g.fill(0xFFFFF3B8);
        g.path();
        rrect(l + 26, b - 92, r - 26, b - 74, 4);
        g.fill(0xFFFFE6B0);
        g.path();
        rrect(l + 40, b - 64, r - 40, b - 54, 3);
        rrect(l + 54, b - 46, r - 54, b - 38, 3);
        g.fill(argb(0.7f, 0xFFE6B0));
    }

    private void shelf() {
        float y = 330, l = 572, r = 890;
        g.path();
        rect(l + 6, y + 6, r + 6, y + 20);
        g.fill(argb(0.25f, 0x0A1020));
        float[] bookX = {588, 606, 622, 640, 658}, bookH = {64, 72, 58, 68, 62};
        int[] bookCol = {0xFFD9493A, 0xFF2E8C8A, 0xFFF0B24A, 0xFF7B52B4, 0xFF4F9A54};
        for (int i = 0; i < bookX.length; i++) {
            float bx = bookX[i], bh = bookH[i];
            g.path();
            rrect(bx, y - bh, bx + 16, y, 2);
            g.fill(bookCol[i]);
            g.stroke(LINE, 2);
            g.path();
            rect(bx + 3, y - bh + 10, bx + 13, y - bh + 14);
            rect(bx + 3, y - 16, bx + 13, y - 13);
            g.fill(argb(0.55f, 0xFFF4D8));
        }
        g.save();
        g.translate(690, y);
        g.rotate(16);
        g.path();
        rrect(-8, -60, 8, 0, 2);
        g.fill(0xFF3A6FC4);
        g.stroke(LINE, 2);
        g.restore();
        float tx = 742;
        g.path();
        rect(tx - 15, y - 10, tx + 15, y);
        rect(tx - 5, y - 22, tx + 5, y - 10);
        g.moveTo(tx - 18, y - 56);
        g.lineTo(tx + 18, y - 56);
        g.quadTo(tx + 17, y - 26, tx, y - 22);
        g.quadTo(tx - 17, y - 26, tx - 18, y - 56);
        g.close();
        g.fillLinear(tx - 18, 0, tx + 18, 0, new int[]{0xFFF6D85E, 0xFFDFB236, 0xFFAE7F19}, new float[]{0, 0.5f, 1});
        g.stroke(LINE, 2);
        g.path();
        g.moveTo(tx - 18, y - 50);
        g.quadTo(tx - 30, y - 48, tx - 20, y - 34);
        g.moveTo(tx + 18, y - 50);
        g.quadTo(tx + 30, y - 48, tx + 20, y - 34);
        g.stroke(0xFFAE7F19, 3);
        g.path();
        star(tx, y - 42, 6);
        g.fill(0xFFFFF6C8);
        float px = 800;
        g.path();
        g.moveTo(px - 10, y - 26);
        g.quadTo(px - 24, y - 50, px - 26, y - 64);
        g.quadTo(px - 8, y - 52, px - 2, y - 26);
        g.moveTo(px + 2, y - 26);
        g.quadTo(px + 2, y - 60, px + 12, y - 72);
        g.quadTo(px + 16, y - 48, px + 8, y - 26);
        g.moveTo(px + 8, y - 26);
        g.quadTo(px + 26, y - 44, px + 30, y - 56);
        g.quadTo(px + 24, y - 34, px + 14, y - 24);
        g.close();
        g.fill(0xFF4FA35A);
        g.stroke(LINE, 2);
        g.path();
        g.moveTo(px - 16, y - 28);
        g.lineTo(px + 18, y - 28);
        g.lineTo(px + 14, y);
        g.lineTo(px - 12, y);
        g.close();
        g.fill(0xFFE07A4A);
        g.stroke(LINE, 2);
        float fx = 846;
        g.path();
        rect(fx, y - 46, fx + 36, y);
        g.fill(WOOD_DARK);
        g.stroke(LINE, 2);
        g.path();
        rect(fx + 5, y - 41, fx + 31, y - 5);
        g.fillLinear(0, y - 41, 0, y - 5, new int[]{0xFF8FCBF0, 0xFF5FA35A}, new float[]{0, 1});
        g.path();
        g.oval(fx + 13, y - 16, 5, 6);
        g.fill(0xFFE46837);
        g.path();
        g.oval(fx + 23, y - 15, 4, 5);
        g.fill(0xFFF5F0E6);
        g.path();
        rect(l, y, r, y + 14);
        g.fillLinear(0, y, 0, y + 14, new int[]{WOOD_LIGHT, WOOD_SHADE}, new float[]{0, 1});
        g.stroke(LINE, 2.5f);
        for (float bx : new float[]{604, 858}) {
            g.path();
            g.moveTo(bx - 4, y + 14);
            g.lineTo(bx + 4, y + 14);
            g.lineTo(bx + 4, y + 40);
            g.close();
            g.fill(WOOD_DARK);
            g.stroke(LINE, 2);
        }
    }

    private void rug() {
        float cx = 640, cy = 936;
        g.path();
        g.oval(cx, cy + 4, 336, 64);
        g.fill(argb(0.25f, 0x2A1005));
        g.path();
        g.oval(cx, cy, 330, 60);
        g.fill(RUG_OUT);
        g.stroke(LINE, 2.5f);
        g.path();
        g.oval(cx, cy, 300, 50);
        g.fill(RUG_RING);
        g.path();
        g.oval(cx, cy, 276, 42);
        g.fill(RUG_IN);
        g.path();
        for (int i = 0; i < 28; i++) {
            float a = (float) (i * Math.PI * 2 / 28);
            float dx = cx + (float) Math.cos(a) * 288, dy = cy + (float) Math.sin(a) * 46;
            g.moveTo(dx - 6, dy);
            g.lineTo(dx, dy - 3);
            g.lineTo(dx + 6, dy);
            g.lineTo(dx, dy + 3);
            g.close();
        }
        g.fill(RUG_OUT);
        g.path();
        g.oval(cx, cy, 120, 18);
        g.stroke(argb(0.5f, RUG_RING), 3);
    }

    private void bed() {
        g.path();
        g.oval(236, 802, 216, 14);
        g.fill(argb(0.3f, 0x2A1005));
        g.path();
        rrect(20, 466, 80, 800, 28);
        g.fillLinear(20, 0, 80, 0, new int[]{WOOD, WOOD_SHADE}, new float[]{0, 1});
        g.stroke(LINE, 3);
        g.path();
        rrect(32, 492, 68, 700, 16);
        g.stroke(argb(0.45f, LINE), 2);
        g.path();
        g.oval(50, 520, 10, 10);
        g.fill(0xFFF0B24A);
        g.stroke(LINE, 2);
        g.path();
        rect(70, 640, 422, 714);
        g.fillLinear(0, 640, 0, 714, new int[]{SHEET, SHEET_SHADE}, new float[]{0, 1});
        g.stroke(LINE, 3);
        g.path();
        rect(64, 712, 430, 772);
        g.fillLinear(0, 712, 0, 772, new int[]{WOOD, WOOD_SHADE}, new float[]{0, 1});
        g.stroke(LINE, 3);
        g.path();
        rect(80, 726, 414, 758);
        g.stroke(argb(0.35f, LINE), 2);
        g.path();
        rect(78, 770, 98, 802);
        rect(398, 770, 418, 802);
        g.fill(WOOD_DARK);
        g.stroke(LINE, 2.5f);
        g.path();
        rrect(410, 604, 444, 802, 14);
        g.fillLinear(410, 0, 444, 0, new int[]{WOOD, WOOD_SHADE}, new float[]{0, 1});
        g.stroke(LINE, 3);
        g.path();
        g.moveTo(PILLOW_X - 64, 628);
        g.cubicTo(PILLOW_X - 70, 600, PILLOW_X - 40, 596, PILLOW_X, 598);
        g.cubicTo(PILLOW_X + 40, 596, PILLOW_X + 70, 600, PILLOW_X + 64, 628);
        g.cubicTo(PILLOW_X + 60, 650, PILLOW_X - 60, 650, PILLOW_X - 64, 628);
        g.close();
        g.fillLinear(0, 596, 0, 648, new int[]{0xFFFFFDF8, 0xFFE6DCCD}, new float[]{0, 1});
        g.stroke(LINE, 2.5f);
        g.path();
        g.moveTo(PILLOW_X - 30, 612);
        g.quadTo(PILLOW_X, 622, PILLOW_X + 30, 612);
        g.stroke(argb(0.25f, LINE), 2);
    }

    private void nightstand() {
        g.path();
        g.oval(494, 800, 54, 7);
        g.fill(argb(0.3f, 0x2A1005));
        g.path();
        rect(452, 694, 536, 790);
        g.fillLinear(0, 694, 0, 790, new int[]{WOOD, WOOD_SHADE}, new float[]{0, 1});
        g.stroke(LINE, 3);
        g.path();
        rect(446, 684, 542, 696);
        g.fill(WOOD_LIGHT);
        g.stroke(LINE, 3);
        g.path();
        rect(460, 708, 528, 740);
        rect(460, 748, 528, 780);
        g.stroke(argb(0.45f, LINE), 2);
        g.path();
        g.oval(494, 724, 5, 4);
        g.oval(494, 764, 5, 4);
        g.fill(0xFFF0B24A);
        g.stroke(LINE, 1.5f);
        g.path();
        rect(458, 790, 470, 802);
        rect(518, 790, 530, 802);
        g.fill(WOOD_DARK);
        g.path();
        g.oval(LAMP_X, 682, 22, 6);
        g.fill(0xFF8A5A3A);
        g.stroke(LINE, 2);
        g.path();
        rect(LAMP_X - 3, 640, LAMP_X + 3, 680);
        g.fill(0xFFD8B070);
        g.stroke(LINE, 1.5f);
        g.path();
        rrect(512, 666, 534, 684, 3);
        g.fill(0xFF5A9AD8);
        g.stroke(LINE, 1.5f);
    }

    private void desk() {
        g.path();
        g.oval(730, 798, 190, 10);
        g.fill(argb(0.3f, 0x2A1005));
        g.path();
        rect(574, 610, 590, 792);
        g.fill(WOOD_SHADE);
        g.stroke(LINE, 2.5f);
        g.path();
        rect(800, 610, 892, 792);
        g.fillLinear(0, 610, 0, 792, new int[]{WOOD, WOOD_SHADE}, new float[]{0, 1});
        g.stroke(LINE, 3);
        g.path();
        rect(810, 622, 882, 678);
        rect(810, 690, 882, 746);
        g.stroke(argb(0.45f, LINE), 2);
        g.path();
        rrect(834, 646, 858, 652, 3);
        rrect(834, 714, 858, 720, 3);
        g.fill(0xFFF0B24A);
        g.stroke(LINE, 1.5f);
        g.path();
        rect(558, 596, 904, 612);
        g.fillLinear(0, 596, 0, 612, new int[]{WOOD_LIGHT, WOOD}, new float[]{0, 1});
        g.stroke(LINE, 3);

        g.path();
        g.oval(700, 594, 44, 6);
        g.fill(METAL);
        g.stroke(LINE, 2);
        g.path();
        rect(692, 552, 708, 594);
        g.fillLinear(692, 0, 708, 0, new int[]{METAL_LIGHT, METAL}, new float[]{0, 1});
        g.stroke(LINE, 2);
        g.path();
        rrect(600, 420, 800, 558, 9);
        g.fill(METAL_DARK);
        g.stroke(LINE, 3);
        g.path();
        rect(MON_L, MON_T, MON_R, MON_B);
        g.fill(0xFF06070A);
        g.path();
        g.oval(700, 552, 2.5f, 2.5f);
        g.fill(0xFF8AD0FF);

        g.path();
        rrect(632, 580, 768, 594, 4);
        g.fill(METAL);
        g.stroke(LINE, 2);
        g.path();
        for (float kx = 640; kx < 760; kx += 9) {
            rect(kx, 584, kx + 6, 587);
            rect(kx + 3, 589, kx + 9, 591);
        }
        g.fill(argb(0.5f, 0x9AA4B8));
        g.path();
        g.oval(788, 589, 9, 6);
        g.fill(METAL);
        g.stroke(LINE, 2);
        g.path();
        rrect(570, 560, 596, 596, 4);
        g.fillLinear(570, 0, 596, 0, new int[]{0xFFF07A4A, 0xFFC4502E}, new float[]{0, 1});
        g.stroke(LINE, 2);
        g.path();
        g.moveTo(570, 568);
        g.quadTo(558, 570, 560, 580);
        g.quadTo(562, 590, 570, 588);
        g.stroke(LINE, 2.5f);
        g.path();
        g.oval(583, 576, 5, 6);
        g.fill(0xFFFFF0C8);

        g.path();
        rrect(816, 454, 886, 596, 6);
        g.fillLinear(816, 0, 886, 0, new int[]{0xFF2E323C, METAL_DARK}, new float[]{0, 1});
        g.stroke(LINE, 3);
        g.path();
        rrect(824, 466, 862, 586, 4);
        g.fill(0xFF0D0F16);
        g.stroke(argb(0.6f, METAL_LIGHT), 1.5f);
        g.path();
        g.oval(874, 470, 3, 3);
        g.fill(0xFF8AD0FF);
    }

    private void tvStand() {
        g.path();
        g.oval(1072, 800, 160, 9);
        g.fill(argb(0.3f, 0x2A1005));
        g.path();
        rect(940, 786, 954, 800);
        rect(1192, 786, 1206, 800);
        g.fill(WALNUT_SHADE);
        g.path();
        rrect(924, 692, 1222, 790, 8);
        g.fillLinear(0, 692, 0, 790, new int[]{WALNUT, WALNUT_SHADE}, new float[]{0, 1});
        g.stroke(LINE, 3);
        g.path();
        rect(936, 704, 1022, 780);
        rect(1124, 704, 1210, 780);
        g.stroke(argb(0.5f, LINE), 2);
        g.path();
        rrect(1010, 736, 1016, 752, 2);
        rrect(1130, 736, 1136, 752, 2);
        g.fill(0xFFF0B24A);
        g.path();
        rect(1032, 704, 1114, 780);
        g.fill(0xFF2A1A12);
        g.stroke(argb(0.5f, LINE), 2);
        g.path();
        rrect(1040, 744, 1106, 776, 4);
        g.fillLinear(0, 744, 0, 776, new int[]{0xFFF4F4F8, 0xFFC8CAD4}, new float[]{0, 1});
        g.stroke(LINE, 2);
        g.path();
        rect(1046, 766, 1060, 768);
        g.fill(0xFF4AA8FF);
        g.path();
        g.moveTo(962, 690);
        g.cubicTo(958, 676, 976, 672, 984, 678);
        g.lineTo(1004, 678);
        g.cubicTo(1012, 672, 1030, 676, 1026, 690);
        g.close();
        g.fill(0xFF3A3F4C);
        g.stroke(LINE, 2);
        g.path();
        g.oval(978, 684, 2.5f, 2.5f);
        g.oval(1010, 684, 2.5f, 2.5f);
        g.fill(0xFFE0432F);

        g.path();
        rect(1028, 684, 1116, 692);
        g.fill(METAL);
        g.stroke(LINE, 2);
        g.path();
        rect(1062, 650, 1082, 686);
        g.fillLinear(1062, 0, 1082, 0, new int[]{METAL_LIGHT, METAL}, new float[]{0, 1});
        g.stroke(LINE, 2);
        g.path();
        rrect(TV_L - 12, TV_T - 12, TV_R + 12, TV_B + 13, 8);
        g.fill(METAL_DARK);
        g.stroke(LINE, 3);
        g.path();
        rect(TV_L, TV_T, TV_R, TV_B);
        g.fill(0xFF000000);
    }

    // ---- front layer ----

    private final float[] at = new float[2];

    void drawFront(Gfx gfx, Puppet puppet, Scene s) {
        g = gfx;
        clockHands(s.hour);
        monitor(s.monitor, s.t);
        pcLights(s.t, s.monitor != SCREEN_OFF);
        if (!s.tvOn) tvGlass();
        lampShade(lampOn(s.hour));
        stringLights(s.t, darkness(s.hour));

        petAt(s, at);
        boolean inBed = tucked(s);
        boolean inBag = s.seat == BEANBAG && s.blend > 0.5f;
        boolean back = backView(s);
        if (inBed) sleeper(puppet, s, ease((s.blend - TUCKED) / (1 - TUCKED)));
        else blanket(0, 0);
        if (back) {
            petBack(puppet, s, false);
            chair();
            petBack(puppet, s, true);
        } else {
            chair();
        }
        if (inBag) {
            bag(1);
            pet(puppet, s);
            bag(2);
        } else {
            bag(0);
        }
        if (!back && !inBag && !inBed) pet(puppet, s);
        lighting(s);
    }

    /** Tucked in: his head on the pillow, his body a bump under the quilt, the tail's fireball out by his feet. */
    private void sleeper(Puppet puppet, Scene s, float e) {
        if (s.pose == null) return;
        g.save();
        g.translate(PILLOW_X + 4, HEAD_Y - 24 * (1 - e));
        g.scale(K, K);
        puppet.drawHead(g, s.pose, s.clock, -6);
        g.restore();
        blanket(e, 236);
        g.save();
        g.translate(318, 640 - 24 * e);
        g.scale(K * 0.85f, K * 0.85f);
        g.alpha(e);
        puppet.drawTail(g, s.clock);
        g.restore();
    }

    private void pet(Puppet puppet, Scene s) {
        if (s.pose == null) return;
        g.save();
        g.translate(at[0], at[1]);
        g.scale(K, K);
        puppet.draw(g, s.pose, s.clock);
        g.restore();
    }

    private void petBack(Puppet puppet, Scene s, boolean over) {
        g.save();
        g.translate(at[0], at[1]);
        g.scale(K, K);
        float sway = (float) (Math.sin(s.t * 0.9f) * 0.6f + Math.sin(s.t * 2.3f) * 0.25f) * (1 - s.cheer);
        puppet.drawBack(g, s.clock, s.cheer, sway, over);
        g.restore();
    }

    private void clockHands(float hour) {
        float h = hour % 12, m = (hour * 60) % 60, sec = (float) Math.floor((hour * 3600) % 60);
        hand(h * 30, 21, 5, LINE);
        hand(m * 6, 31, 3.2f, LINE);
        hand(sec * 6, 33, 1.4f, 0xFFE0432F);
        g.path();
        g.oval(CLOCK_X, CLOCK_Y, 4, 4);
        g.fill(LINE);
    }

    private void hand(float deg, float len, float w, int col) {
        float a = (float) Math.toRadians(deg);
        g.path();
        g.moveTo(CLOCK_X - (float) Math.sin(a) * 6, CLOCK_Y + (float) Math.cos(a) * 6);
        g.lineTo(CLOCK_X + (float) Math.sin(a) * len, CLOCK_Y - (float) Math.cos(a) * len);
        g.stroke(col, w);
    }

    private void monitor(int mode, float t) {
        g.save();
        g.path();
        rect(MON_L, MON_T, MON_R, MON_B);
        g.clip();
        switch (mode) {
            case SCREEN_GAME:
                game(t);
                break;
            case SCREEN_CODE:
                code(t);
                break;
            case SCREEN_OFF:
                g.path();
                rect(MON_L, MON_T, MON_R, MON_B);
                g.fill(0xFF050608);
                break;
            default:
                saver(t);
        }
        g.path();
        g.moveTo(MON_L, MON_T);
        g.lineTo(MON_L + 56, MON_T);
        g.lineTo(MON_L, MON_T + 46);
        g.close();
        g.fill(argb(0.08f, 0xFFFFFF));
        g.restore();
    }

    private void game(float t) {
        float w = MON_R - MON_L;
        g.path();
        rect(MON_L, MON_T, MON_R, MON_B);
        g.fillLinear(0, MON_T, 0, MON_B, new int[]{0xFF3B6FD6, 0xFF9AD6F6}, new float[]{0, 1});
        float far = (t * 14) % 70;
        g.path();
        for (float x = MON_L - far; x < MON_R + 70; x += 70) g.oval(x, MON_B - 22, 40, 26);
        g.fill(0xFF7FCB7A);
        cloudSmall(MON_L + 30 + ((-t * 8) % w + w) % w, MON_T + 20);
        cloudSmall(MON_L + ((90 - t * 8) % w + w) % w, MON_T + 36);
        float gy = MON_B - 20, scroll = (t * 46) % 24;
        g.path();
        rect(MON_L, gy, MON_R, MON_B);
        g.fill(0xFFC8773A);
        g.path();
        rect(MON_L, gy, MON_R, gy + 5);
        g.fill(0xFF4FB04F);
        g.path();
        for (float x = MON_L - scroll; x < MON_R; x += 24) rect(x, gy + 9, x + 2, MON_B);
        rect(MON_L, gy + 13, MON_R, gy + 14.5f);
        g.fill(argb(0.4f, 0x5A2A10));
        float bx = MON_L + w - ((t * 46) % (w + 60));
        g.path();
        rect(bx, gy - 46, bx + 14, gy - 32);
        rect(bx + 14, gy - 46, bx + 28, gy - 32);
        g.fill(0xFFE0A030);
        g.stroke(0xFF7A4A10, 1.2f);
        for (int i = 0; i < 5; i++) {
            float cx = MON_L + ((i * 41 - t * 46) % w + w) % w;
            float cy = gy - 26 - (i % 2) * 14;
            g.path();
            g.oval(cx, cy, 3.4f * Math.abs((float) Math.cos(t * 4 + i)) + 0.8f, 4);
            g.fill(0xFFFFD84A);
        }
        float ex = MON_L + w - ((t * 30 + 40) % (w + 30));
        g.path();
        g.oval(ex, gy - 6, 7, 6);
        g.fill(0xFF8A4A2A);
        g.path();
        g.oval(ex - 2, gy - 8, 1.5f, 1.5f);
        g.oval(ex + 2, gy - 8, 1.5f, 1.5f);
        g.fill(0xFFFFFFFF);
        float jump = Math.abs((float) Math.sin(t * 2.6f)) * 30;
        float hx = MON_L + 46, hy = gy - 9 - jump;
        g.path();
        g.moveTo(hx - 7, hy);
        g.cubicTo(hx - 7, hy - 9, hx - 2, hy - 14, hx + 1, hy - 20);
        g.cubicTo(hx + 3, hy - 13, hx + 8, hy - 9, hx + 7, hy);
        g.cubicTo(hx + 6, hy + 8, hx - 6, hy + 8, hx - 7, hy);
        g.close();
        g.fill(0xFFFF7A2A);
        g.path();
        g.oval(hx, hy + 1, 4, 4);
        g.fill(0xFFFFE07A);
        g.path();
        g.oval(hx + 2, hy - 2, 1.3f, 1.6f);
        g.fill(0xFF1A1020);
        g.path();
        for (int i = 0; i < 3; i++) heart(MON_L + 10 + i * 12, MON_T + 10, 4);
        g.fill(0xFFFF4A6A);
        g.path();
        int score = (int) (t * 10) % 10000;
        for (int i = 0; i < 4; i++) {
            int d = (score / (int) Math.pow(10, 3 - i)) % 10;
            float x0 = MON_R - 48 + i * 10;
            digit(x0, MON_T + 6, d);
        }
        g.fill(0xFFFFFFFF);
    }

    private void cloudSmall(float x, float y) {
        g.path();
        g.oval(x, y, 12, 6);
        g.oval(x + 9, y - 4, 9, 6);
        g.oval(x + 17, y, 10, 5);
        g.fill(argb(0.9f, 0xFFFFFF));
    }

    private void heart(float x, float y, float s) {
        g.moveTo(x, y + s);
        g.cubicTo(x - s * 1.6f, y - s * 0.2f, x - s * 0.6f, y - s * 1.4f, x, y - s * 0.5f);
        g.cubicTo(x + s * 0.6f, y - s * 1.4f, x + s * 1.6f, y - s * 0.2f, x, y + s);
        g.close();
    }

    /** A tiny seven-segment digit, 7 x 11, added to the current path. */
    private void digit(float x, float y, int d) {
        int[] seg = {0x77, 0x24, 0x5D, 0x6D, 0x2E, 0x6B, 0x7B, 0x25, 0x7F, 0x6F};
        int m = seg[d];
        if ((m & 1) != 0) rect(x + 1, y, x + 6, y + 1.5f);
        if ((m & 2) != 0) rect(x, y + 1, x + 1.5f, y + 5);
        if ((m & 4) != 0) rect(x + 5.5f, y + 1, x + 7, y + 5);
        if ((m & 8) != 0) rect(x + 1, y + 4.75f, x + 6, y + 6.25f);
        if ((m & 16) != 0) rect(x, y + 6, x + 1.5f, y + 10);
        if ((m & 32) != 0) rect(x + 5.5f, y + 6, x + 7, y + 10);
        if ((m & 64) != 0) rect(x + 1, y + 9.5f, x + 6, y + 11);
    }

    private void code(float t) {
        g.path();
        rect(MON_L, MON_T, MON_R, MON_B);
        g.fill(0xFF161A24);
        g.path();
        rect(MON_L, MON_T, MON_R, MON_T + 10);
        g.fill(0xFF262C3A);
        g.path();
        g.oval(MON_L + 7, MON_T + 5, 2.4f, 2.4f);
        g.fill(0xFFFF5F56);
        g.path();
        g.oval(MON_L + 15, MON_T + 5, 2.4f, 2.4f);
        g.fill(0xFFFFBD2E);
        float scroll = (t * 9) % 11;
        int first = (int) (t * 9 / 11);
        int[] cols = {0xFFFF7AB8, 0xFF8AD0FF, 0xFFB8E986, 0xFFE6E6F0, 0xFFFFC66A};
        for (int i = 0; i < 10; i++) {
            int n = first + i;
            float y = MON_T + 16 + i * 11 - scroll;
            float x = MON_L + 10 + ((n * 7) % 4) * 9;
            int parts = 2 + (n * 5) % 3;
            for (int p = 0; p < parts; p++) {
                float len = 10 + ((n * 13 + p * 7) % 30);
                if (x + len > MON_R - 8) break;
                g.path();
                rrect(x, y, x + len, y + 4, 1.5f);
                g.fill(cols[(n + p * 2) % cols.length]);
                x += len + 5;
            }
        }
        float p = (t * 0.07f) % 1f;
        g.path();
        rect(MON_L + 8, MON_B - 9, MON_R - 8, MON_B - 4);
        g.fill(0xFF2E3546);
        g.path();
        rect(MON_L + 8, MON_B - 9, MON_L + 8 + (MON_R - MON_L - 16) * p, MON_B - 4);
        g.fill(0xFFFF8A3A);
    }

    private void saver(float t) {
        g.path();
        rect(MON_L, MON_T, MON_R, MON_B);
        g.fillLinear(0, MON_T, 0, MON_B, new int[]{0xFF101634, 0xFF2A1A44}, new float[]{0, 1});
        float w = MON_R - MON_L - 40, h = MON_B - MON_T - 40;
        float x = MON_L + 20 + tri(t * 0.09f) * w, y = MON_T + 20 + tri(t * 0.13f + 0.3f) * h;
        g.path();
        g.oval(x, y, 26, 26);
        g.fillRadial(x, y, 26, new int[]{argb(0.5f, 0xFFB050), argb(0f, 0xFF8030)}, new float[]{0, 1});
        g.path();
        g.moveTo(x - 9, y + 3);
        g.cubicTo(x - 9, y - 8, x - 2, y - 13, x, y - 19);
        g.cubicTo(x + 2, y - 13, x + 9, y - 8, x + 9, y + 3);
        g.cubicTo(x + 8, y + 11, x - 8, y + 11, x - 9, y + 3);
        g.close();
        g.fill(0xFFFF7A2A);
        g.path();
        g.oval(x, y + 3, 4.5f, 5);
        g.fill(0xFFFFF0B0);
    }

    private static float tri(float x) {
        float f = x - (float) Math.floor(x);
        return f < 0.5f ? f * 2 : 2 - f * 2;
    }

    private void pcLights(float t, boolean on) {
        float[][] fans = {{843, 498}, {843, 552}};
        for (int i = 0; i < fans.length; i++) {
            float cx = fans[i][0], cy = fans[i][1];
            int col = on ? hsv((t * 40 + i * 120) % 360, 0.75f, 1) : 0xFF2A2E38;
            g.path();
            g.oval(cx, cy, 14, 14);
            g.fill(0xFF0D0F16);
            g.stroke(col, 2.6f);
            float spin = on ? t * 400 + i * 30 : 0;
            g.path();
            for (int b = 0; b < 4; b++) {
                double a = Math.toRadians(spin + b * 90);
                float c = (float) Math.cos(a), sn = (float) Math.sin(a);
                g.moveTo(cx, cy);
                g.quadTo(cx + 7 * c + 3 * sn, cy + 7 * sn - 3 * c, cx + 9 * c + 9 * sn, cy + 9 * sn - 9 * c);
                g.quadTo(cx + 3 * c + 7 * sn, cy + 3 * sn - 7 * c, cx, cy);
                g.close();
            }
            g.fill(argb(0.8f, 0x5A6070));
            if (on) {
                g.path();
                g.oval(cx, cy, 22, 22);
                g.fillRadial(cx, cy, 22, new int[]{argb(0.25f, col), argb(0f, col)}, new float[]{0, 1});
            }
        }
        if (on) {
            g.path();
            rect(826, 578, 860, 580);
            g.fill(hsv((t * 40 + 240) % 360, 0.75f, 1));
        }
    }

    private void tvGlass() {
        g.path();
        rect(TV_L, TV_T, TV_R, TV_B);
        g.fillLinear(TV_L, TV_T, TV_R, TV_B, new int[]{0xFF14171E, 0xFF07080B}, new float[]{0, 1});
        g.path();
        g.moveTo(TV_L, TV_T);
        g.lineTo(TV_L + 120, TV_T);
        g.lineTo(TV_L + 40, TV_B);
        g.lineTo(TV_L, TV_B);
        g.close();
        g.fill(argb(0.06f, 0xFFFFFF));
        g.path();
        g.oval(TV_R - 6, TV_B + 7, 2.2f, 2.2f);
        g.fill(0xFFE0432F);
    }

    private void lampShade(boolean on) {
        g.path();
        g.moveTo(LAMP_X - 30, 642);
        g.lineTo(LAMP_X + 30, 642);
        g.lineTo(LAMP_X + 20, 598);
        g.lineTo(LAMP_X - 20, 598);
        g.close();
        if (on) g.fillLinear(0, 598, 0, 642, new int[]{0xFFFFF4D0, 0xFFFFD070}, new float[]{0, 1});
        else g.fillLinear(0, 598, 0, 642, new int[]{0xFFF6E2BC, 0xFFD9B98A}, new float[]{0, 1});
        g.stroke(LINE, 2.5f);
    }

    private void stringLights(float t, float dark) {
        float[] nails = {-20, 300, 620, 940, 1260};
        g.path();
        for (int i = 0; i + 1 < nails.length; i++) {
            float a = nails[i], b = nails[i + 1];
            if (i == 0) g.moveTo(a, 44);
            g.quadTo((a + b) / 2, 92, b, 44);
        }
        g.stroke(0xFF2A2420, 2);
        int n = 0;
        for (int i = 0; i + 1 < nails.length; i++) {
            float a = nails[i], b = nails[i + 1];
            for (int k = 1; k < 8; k++) {
                float u = k / 8f;
                float x = a + (b - a) * u, y = 44 + (1 - (2 * u - 1) * (2 * u - 1)) * 24;
                int col = BULBS[n++ % BULBS.length];
                float tw = 0.75f + 0.25f * (float) Math.sin(t * 2.2f + n * 1.7f);
                if (dark > 0.02f) {
                    g.path();
                    g.oval(x, y + 8, 22, 22);
                    g.fillRadial(x, y + 8, 22, new int[]{argb(1.4f * dark * tw, col), argb(0f, col)}, new float[]{0, 1});
                }
                g.path();
                g.moveTo(x - 4, y + 2);
                g.cubicTo(x - 6, y + 10, x - 3, y + 15, x, y + 15);
                g.cubicTo(x + 3, y + 15, x + 6, y + 10, x + 4, y + 2);
                g.close();
                g.fill(dark > 0.02f ? mix(col, 0xFFFFFFFF, 0.35f * tw) : mix(col, 0xFF808080, 0.25f));
                g.path();
                rect(x - 3, y - 1, x + 3, y + 3);
                g.fill(0xFF3A3430);
            }
        }
    }

    private void chair() {
        float cx = CHAIR_X;
        g.path();
        g.oval(cx, 942, 92, 10);
        g.fill(argb(0.3f, 0x2A1005));
        g.path();
        rrect(cx - 78, 924, cx + 78, 932, 4);
        g.fill(METAL);
        g.stroke(LINE, 2);
        for (float wx : new float[]{cx - 72, cx, cx + 72}) {
            g.path();
            g.oval(wx, 938, 8, 7);
            g.fill(METAL_DARK);
            g.stroke(LINE, 2);
        }
        g.path();
        rect(cx - 7, 846, cx + 7, 926);
        g.fillLinear(cx - 7, 0, cx + 7, 0, new int[]{0xFFC8CDD8, 0xFF6A7080}, new float[]{0, 1});
        g.stroke(LINE, 2);
        for (int side = -1; side <= 1; side += 2) {
            float ax = cx + side * 80;
            g.path();
            rect(ax - 5, 790, ax + 5, 832);
            g.fill(METAL);
            g.stroke(LINE, 2);
            g.path();
            rrect(ax - 13, 782, ax + 13, 794, 5);
            g.fill(BLACK);
            g.stroke(LINE, 2);
        }
        g.path();
        g.moveTo(cx - 64, 812);
        g.lineTo(cx - 70, 744);
        g.quadTo(cx - 74, 704, cx - 56, 694);
        g.lineTo(cx - 42, 700);
        g.quadTo(cx, 688, cx + 42, 700);
        g.lineTo(cx + 56, 694);
        g.quadTo(cx + 74, 704, cx + 70, 744);
        g.lineTo(cx + 64, 812);
        g.close();
        g.fillLinear(cx - 70, 0, cx + 70, 0, new int[]{BLACK_LIGHT, BLACK, BLACK, 0xFF1A1A20}, new float[]{0, 0.3f, 0.7f, 1});
        g.stroke(LINE, 3);
        g.path();
        g.moveTo(cx - 62, 806);
        g.lineTo(cx - 66, 746);
        g.quadTo(cx - 69, 712, cx - 54, 702);
        g.lineTo(cx - 46, 706);
        g.quadTo(cx - 54, 730, cx - 50, 806);
        g.close();
        g.moveTo(cx + 62, 806);
        g.lineTo(cx + 66, 746);
        g.quadTo(cx + 69, 712, cx + 54, 702);
        g.lineTo(cx + 46, 706);
        g.quadTo(cx + 54, 730, cx + 50, 806);
        g.close();
        g.fillLinear(0, 700, 0, 806, new int[]{RED, RED_SHADE}, new float[]{0, 1});
        g.path();
        rect(cx - 20, 722, cx - 12, 806);
        rect(cx + 12, 722, cx + 20, 806);
        g.fill(RED);
        g.path();
        rrect(cx - 26, 704, cx + 26, 724, 9);
        g.fillLinear(0, 704, 0, 724, new int[]{RED, RED_SHADE}, new float[]{0, 1});
        g.stroke(LINE, 2);
        g.path();
        star(cx, 714, 5);
        g.fill(0xFFFFE07A);
        g.path();
        rrect(cx - 76, 806, cx + 76, 846, 14);
        g.fillLinear(0, 806, 0, 846, new int[]{BLACK_LIGHT, BLACK}, new float[]{0, 1});
        g.stroke(LINE, 3);
        g.path();
        rrect(cx - 70, 838, cx + 70, 846, 4);
        g.fill(RED);
    }

    /** part 0: the whole bean bag; 1: everything behind him; 2: the front lip he sinks behind. */
    private void bag(int part) {
        float cx = BAG_CX, b = BAG_BASE;
        if (part != 2) {
            g.path();
            g.oval(cx, b - 2, 134, 13);
            g.fill(argb(0.32f, 0x2A1005));
            g.path();
            g.moveTo(cx - 126, b - 8);
            g.cubicTo(cx - 142, b - 92, cx - 112, b - 178, cx - 52, b - 194);
            g.cubicTo(cx - 8, b - 204, cx + 40, b - 182, cx + 58, b - 150);
            g.cubicTo(cx + 76, b - 122, cx + 118, b - 108, cx + 128, b - 66);
            g.cubicTo(cx + 136, b - 28, cx + 118, b - 4, cx + 92, b);
            g.quadTo(cx, b + 9, cx - 126, b - 8);
            g.close();
            g.fillRadial(cx - 60, b - 150, 250, new int[]{BAG_LIGHT, BAG, BAG_SHADE}, new float[]{0, 0.45f, 1});
            g.stroke(LINE, 3);
            g.path();
            g.moveTo(cx - 84, b - 172);
            g.quadTo(cx - 52, b - 120, cx - 62, b - 50);
            g.moveTo(cx + 30, b - 180);
            g.quadTo(cx + 40, b - 150, cx + 60, b - 130);
            g.stroke(argb(0.35f, 0x2A1040), 2.5f);
            g.path();
            g.oval(cx + 10, b - 90, 74, 30);
            g.fill(argb(0.3f, 0x2A1040));
            g.path();
            g.oval(cx - 62, b - 166, 24, 11);
            g.fill(argb(0.25f, 0xFFFFFF));
        }
        if (part == 1) return;
        g.path();
        g.moveTo(cx - 132, b - 44);
        g.cubicTo(cx - 110, b - 70, cx - 50, b - 72, cx - 6, b - 60);
        g.cubicTo(cx + 30, b - 52, cx + 90, b - 74, cx + 130, b - 56);
        g.cubicTo(cx + 136, b - 26, cx + 118, b - 4, cx + 92, b);
        g.quadTo(cx, b + 9, cx - 126, b - 8);
        g.cubicTo(cx - 134, b - 18, cx - 136, b - 32, cx - 132, b - 44);
        g.close();
        g.fillLinear(0, b - 80, 0, b, new int[]{BAG, BAG_SHADE}, new float[]{0, 1});
        g.stroke(LINE, 3);
        g.path();
        g.moveTo(cx - 104, b - 54);
        g.quadTo(cx - 50, b - 64, cx - 6, b - 50);
        g.stroke(argb(0.3f, 0xFFFFFF), 3);
    }

    /** bump: 0 lying flat on the bed .. 1 pulled up over him; x: his middle. */
    private void blanket(float bump, float x) {
        float top = 638, l = QUILT_L - 14 * bump;
        g.path();
        g.moveTo(l, top);
        if (bump > 0.01f) {
            float h = 30 * bump;
            g.cubicTo(l + 10, top - h * 0.8f, x - 6, top - h * 1.2f, x + 30, top - h);
            g.cubicTo(x + 70, top - h * 0.8f, x + 90, top - h * 0.5f, x + 110, top - 4);
        }
        g.lineTo(QUILT_R, top);
        g.lineTo(QUILT_R + 6, 756);
        g.quadTo(QUILT_R - 40, 764, QUILT_R - 80, 756);
        g.quadTo(QUILT_R - 120, 750, QUILT_R - 160, 758);
        g.quadTo(QUILT_R - 200, 764, l + 6, 754);
        g.lineTo(l - 4, 712);
        g.close();
        g.fillLinear(0, top - 30, 0, 760, new int[]{QUILT, QUILT, QUILT_SHADE}, new float[]{0, 0.55f, 1});
        g.stroke(LINE, 3);
        g.path();
        g.moveTo(l, top + 1);
        if (bump > 0.01f) {
            float h = 30 * bump;
            g.cubicTo(l + 10, top - h * 0.8f + 1, x - 6, top - h * 1.2f + 1, x + 30, top - h + 1);
            g.cubicTo(x + 70, top - h * 0.8f, x + 90, top - h * 0.5f, x + 110, top - 3);
        }
        g.lineTo(QUILT_R, top + 1);
        g.lineTo(QUILT_R + 1, top + 16);
        g.lineTo(l + 2, top + 16);
        g.close();
        g.fill(SHEET);
        g.stroke(LINE, 2);
        g.path();
        for (int i = 0; i < 3; i++) {
            float sx = l + 60 + i * 80, sy = 704 + (i % 2) * 18;
            star(sx, sy, 9);
        }
        g.fill(QUILT_GOLD);
        g.path();
        g.moveTo(l + 4, 690);
        g.quadTo(l + 120, 698, QUILT_R + 2, 690);
        g.stroke(argb(0.35f, QUILT_GOLD), 3);
    }

    private void lighting(Scene s) {
        float d = darkness(s.hour);
        if (d <= 0.01f) return;
        float k = d / 0.42f;
        g.path();
        rect(-2000, -20, W + 2000, H + 20);
        if (s.tvOn) rectCCW(TV_L, TV_T, TV_R, TV_B);
        if (s.monitor != SCREEN_OFF) rectCCW(MON_L, MON_T, MON_R, MON_B);
        g.fill(argb(d, 0x0A0E28));
        if (lampOn(s.hour)) {
            g.path();
            g.oval(LAMP_X, 640, 330, 290);
            g.fillRadial(LAMP_X, 640, 330, new int[]{argb(0.34f * k, 0xFFC070), argb(0.12f * k, 0xFFA050), argb(0f, 0xFFA040)}, new float[]{0, 0.45f, 1});
        }
        if (s.tvOn) {
            float cx = (TV_L + TV_R) / 2, cy = (TV_T + TV_B) / 2;
            g.path();
            g.oval(cx, cy + 120, 340, 330);
            g.fillRadial(cx, cy + 120, 340, new int[]{argb(0.13f * k, 0x8AB4FF), argb(0f, 0x8AB4FF)}, new float[]{0, 1});
        }
        if (s.monitor != SCREEN_OFF) {
            float cx = (MON_L + MON_R) / 2, cy = (MON_T + MON_B) / 2;
            g.path();
            g.oval(cx, cy + 90, 230, 220);
            g.fillRadial(cx, cy + 90, 230, new int[]{argb(0.12f * k, 0x9AC8FF), argb(0f, 0x9AC8FF)}, new float[]{0, 1});
        }
        float[] h = new float[2];
        headTop(s, h);
        g.path();
        g.oval(h[0], h[1] + 70, 190, 190);
        g.fillRadial(h[0], h[1] + 70, 190, new int[]{argb(0.2f * k, 0xFFB050), argb(0f, 0xFF9030)}, new float[]{0, 1});
    }

    // ---- shapes ----

    private void rect(float l, float t, float r, float b) {
        g.moveTo(l, t);
        g.lineTo(r, t);
        g.lineTo(r, b);
        g.lineTo(l, b);
        g.close();
    }

    /** A rectangle wound the other way: a hole in a rect() under the non-zero rule. */
    private void rectCCW(float l, float t, float r, float b) {
        g.moveTo(l, t);
        g.lineTo(l, b);
        g.lineTo(r, b);
        g.lineTo(r, t);
        g.close();
    }

    private void rrect(float l, float t, float r, float b, float rad) {
        rad = Math.min(rad, Math.min((r - l) / 2, (b - t) / 2));
        float c = 0.5523f * rad;
        g.moveTo(l + rad, t);
        g.lineTo(r - rad, t);
        g.cubicTo(r - rad + c, t, r, t + rad - c, r, t + rad);
        g.lineTo(r, b - rad);
        g.cubicTo(r, b - rad + c, r - rad + c, b, r - rad, b);
        g.lineTo(l + rad, b);
        g.cubicTo(l + rad - c, b, l, b - rad + c, l, b - rad);
        g.lineTo(l, t + rad);
        g.cubicTo(l, t + rad - c, l + rad - c, t, l + rad, t);
        g.close();
    }

    private void star(float x, float y, float r) {
        for (int i = 0; i < 10; i++) {
            float a = (float) Math.toRadians(i * 36);
            float rr = i % 2 == 0 ? r : r * 0.45f;
            float px = x + (float) Math.sin(a) * rr, py = y - (float) Math.cos(a) * rr;
            if (i == 0) g.moveTo(px, py);
            else g.lineTo(px, py);
        }
        g.close();
    }

    static int hsv(float h, float s, float v) {
        float c = v * s, x = c * (1 - Math.abs((h / 60f) % 2 - 1)), m = v - c;
        float r, gg, b;
        if (h < 60) { r = c; gg = x; b = 0; }
        else if (h < 120) { r = x; gg = c; b = 0; }
        else if (h < 180) { r = 0; gg = c; b = x; }
        else if (h < 240) { r = 0; gg = x; b = c; }
        else if (h < 300) { r = x; gg = 0; b = c; }
        else { r = c; gg = 0; b = x; }
        return 0xFF000000 | (Math.round((r + m) * 255) << 16) | (Math.round((gg + m) * 255) << 8) | Math.round((b + m) * 255);
    }
}
