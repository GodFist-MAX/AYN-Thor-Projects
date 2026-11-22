package dev.grokbuild.coronamon;

import java.util.LinkedHashMap;
import java.util.Map;

import static dev.grokbuild.coronamon.Pose.*;

/**
 * Coronamon's moves. Each one writes a pose for time t (seconds since it started) on top of the
 * rest pose. Looping moves run until something else is played; the others last {@code dur}.
 */
final class Moves {
    interface Fn {
        void apply(float[] p, float t, Actor a);
    }

    static final class Move {
        final String name;
        final float dur;
        final boolean loop;
        /** Shows his own gaze, so the actor leaves the eyes alone. */
        final boolean ownsGaze;
        private final Fn fn;

        Move(String name, float dur, boolean ownsGaze, Fn fn) {
            this.name = name;
            this.dur = dur;
            this.loop = dur <= 0;
            this.ownsGaze = ownsGaze;
            this.fn = fn;
        }

        void apply(Pose p, float t, Actor a) {
            fn.apply(p.v, t, a);
        }
    }

    static final Map<String, Move> ALL = new LinkedHashMap<>();

    private static Move loop(String name, Fn fn) {
        return add(new Move(name, 0, false, fn));
    }

    private static Move once(String name, float dur, Fn fn) {
        return add(new Move(name, dur, false, fn));
    }

    private static Move gazing(String name, float dur, Fn fn) {
        return add(new Move(name, dur, true, fn));
    }

    private static Move add(Move m) {
        ALL.put(m.name, m);
        return m;
    }

    static Move get(String name) {
        return ALL.get(name);
    }

    // ---- timing helpers ----

    static final float TAU = (float) (Math.PI * 2);

    static float sn(float t, float hz) {
        return (float) Math.sin(TAU * hz * t);
    }

    static float cs(float t, float hz) {
        return (float) Math.cos(TAU * hz * t);
    }

    /** 0 outside [a, b], a smooth hump peaking at 1 halfway. */
    static float bump(float t, float a, float b) {
        if (t <= a || t >= b) return 0;
        return (float) Math.sin(Math.PI * (t - a) / (b - a));
    }

    /** Smoothstep from 0 at a to 1 at b. */
    static float ramp(float t, float a, float b) {
        if (t <= a) return 0;
        if (t >= b) return 1;
        float x = (t - a) / (b - a);
        return x * x * (3 - 2 * x);
    }

    /** Up from a to b, held, down from c to d. */
    static float hold(float t, float a, float b, float c, float d) {
        return ramp(t, a, b) * (1 - ramp(t, c, d));
    }

    /** A quick out-and-back strike that starts at t0 and lasts len. */
    static float jab(float t, float t0, float len) {
        if (t < t0 || t > t0 + len) return 0;
        float x = (t - t0) / len;
        return x < 0.35f ? ramp(x, 0, 0.35f) : 1 - ramp(x, 0.55f, 1);
    }

    private static void guard(float[] p) {
        p[FS] = 60;
        p[FE] = 80;
        p[BS] = 66;
        p[BE] = 86;
        p[BROW] = 0.8f;
        p[SMILE] = 0.2f;
    }

    private static void gait(float[] p, float phase, float reach, float liftH) {
        float s = (float) Math.sin(phase), c = (float) Math.cos(phase);
        p[FLX] = reach * s;
        p[FLY] = Math.max(0, liftH * c);
        p[BLX] = -reach * s;
        p[BLY] = Math.max(0, -liftH * c);
    }

    // ---- standing about ----

    /** His usual open-mouthed grin from the games. */
    private static void grin(float[] p) {
        p[MOUTH] = 0.36f;
        p[SMILE] = 1;
    }

    static final Move IDLE = loop("idle", (p, t, a) -> {
        grin(p);
        p[FS] = -12 + 3 * sn(t, 0.21f);
        p[BS] = 12 - 3 * sn(t, 0.21f);
        p[TAIL] = 5 * sn(t, 0.15f);
        p[HEAD] = 2 * sn(t, 0.11f);
        p[HIP] = 0.6f * sn(t, 0.33f);
    });

    static final Move WALK = loop("walk", (p, t, a) -> {
        grin(p);
        float ph = TAU * a.stride / 40;
        gait(p, ph, 10, 7);
        float s = (float) Math.sin(ph);
        p[FS] = -10 - 24 * s;
        p[FE] = 22;
        p[BS] = 10 + 24 * s;
        p[BE] = 22;
        p[HIP] = -1.2f * (float) Math.cos(2 * ph);
        p[LEAN] = 4;
        p[TAIL] = 6 * s;
        p[HEAD] = -2 + 1.5f * (float) Math.cos(2 * ph);
    });

    static final Move RUN = loop("run", (p, t, a) -> {
        float ph = TAU * a.stride / 56;
        gait(p, ph, 14, 11);
        float s = (float) Math.sin(ph);
        p[FS] = 10 - 50 * s;
        p[FE] = 85;
        p[BS] = 10 + 50 * s;
        p[BE] = 85;
        p[HIP] = 2 - 2 * (float) Math.cos(2 * ph);
        p[LEAN] = 10;
        p[TORSO] = 4;
        p[DUST] = 0.5f;
        p[BROW] = 0.4f;
        p[MOUTH] = 0.35f;
        p[TAIL] = -12 + 8 * s;
        p[TAIL_CURL] = 0.3f;
    });

    static final Move LOOK_AROUND = gazing("look", 3.2f, (p, t, a) -> {
        grin(p);
        float s = sn(t, 0.3f);
        p[LOOK_X] = s;
        p[LOOK_Y] = -0.2f * bump(t, 0.6f, 2.6f);
        p[TURN] = 0.6f + 0.4f * s;
        p[HEAD] = 3 * s - 3 * bump(t, 1, 2.4f);
        p[TAIL] = 6 * sn(t, 0.6f);
    });

    static final Move SNIFF = gazing("sniff", 2.4f, (p, t, a) -> {
        float e = hold(t, 0, 0.4f, 1.9f, 2.4f);
        p[LEAN] = 9 * e;
        p[TORSO] = 10 * e;
        p[HEAD] = 12 * e;
        p[HEAD_Y] = 0.7f * e * sn(t, 7);
        p[LOOK_Y] = 0.9f * e;
        p[EYES] = 1 - 0.3f * e;
        p[QUESTION] = ramp(t, 1.3f, 1.6f) * (1 - ramp(t, 2.1f, 2.4f));
        p[FS] = -4;
        p[BS] = 6;
    });

    static final Move STRETCH = once("stretch", 2.4f, (p, t, a) -> {
        float e = hold(t, 0, 0.7f, 1.7f, 2.4f);
        p[FS] = -12 - 118 * e;
        p[FE] = 18 - 38 * e;
        p[BS] = 12 + 118 * e;
        p[BE] = 18 + 2 * e;
        p[F_OPEN] = e > 0.5f ? 1 : 0;
        p[B_OPEN] = e > 0.5f ? 1 : 0;
        p[TORSO] = -7 * e;
        p[HEAD] = -8 * e;
        p[LIFT] = 3 * e;
        p[SQUASH] = 1 + 0.06f * e;
        p[EYES] = 1 - 0.95f * e;
        p[SMILE] = 0.6f + 0.4f * e;
    });

    static final Move YAWN = once("yawn", 2.6f, (p, t, a) -> {
        float e = hold(t, 0.1f, 0.8f, 1.8f, 2.6f);
        p[FS] = -12 - 108 * e;
        p[FE] = 18 - 53 * e;
        p[BS] = 12 + 98 * e;
        p[BE] = 18 + 12 * e;
        p[HEAD] = -12 * e;
        p[TORSO] = -5 * e;
        p[MOUTH] = 0.95f * e;
        p[SMILE] = 0.6f - 0.6f * e;
        p[EYES] = 1 - e;
        p[SQUASH] = 1 + 0.04f * e;
        p[SWEAT] = 0;
    });

    static final Move SIT_DOWN = loop("sit", (p, t, a) -> {
        p[SIT] = 1;
        p[FS] = 16;
        p[FE] = 42;
        p[BS] = 22;
        p[BE] = 42;
        p[TAIL] = -22 + 3 * sn(t, 0.2f);
        p[TAIL_CURL] = 0.5f;
        p[HEAD] = 2 * sn(t, 0.09f);
        p[HIP] = 0.5f * sn(t, 0.3f);
    });

    static final Move BASK = loop("bask", (p, t, a) -> {
        p[SIT] = 1;
        p[TORSO] = -10;
        p[HEAD] = -14;
        p[EYES] = 0;
        p[SMILE] = 1;
        p[FS] = -32;
        p[FE] = 8;
        p[BS] = -24;
        p[BE] = 8;
        p[FLAME] = 1.3f + 0.1f * sn(t, 0.5f);
        p[TAIL_FLAME] = 1.25f;
        p[SPARKLE] = 0.6f;
        p[TAIL] = -20 + 4 * sn(t, 0.25f);
        p[BLUSH] = 0.4f;
    });

    static final Move SLEEP = gazing("sleep", 0, (p, t, a) -> {
        float br = sn(t, 0.22f);
        p[SIT] = 1;
        p[EYES] = 0;
        p[ZZZ] = 1;
        p[HEAD] = 11 + 2 * br;
        p[TORSO] = 6;
        p[SQUASH] = 1 + 0.018f * br;
        p[MOUTH] = Math.max(0, 0.12f * br);
        p[SMILE] = 0.3f;
        p[FLAME] = 0.45f + 0.05f * br;
        p[TAIL_FLAME] = 0.6f;
        p[FS] = 10;
        p[FE] = 30;
        p[BS] = 10;
        p[BE] = 30;
        p[TAIL] = -24;
        p[TAIL_CURL] = 0.7f;
    });

    // ---- at home (the room turns, seats and draws him; these are the poses on top) ----

    /** Asleep in bed. The room shows only his head on the pillow, facing out, with the body under the quilt. */
    static final Move LIE = gazing("lie", 0, (p, t, a) -> {
        float br = sn(t, 0.2f);
        p[EYES] = 0;
        p[ZZZ] = 1;
        p[SMILE] = 0.4f;
        p[MOUTH] = Math.max(0, 0.1f * br);
        p[FLAME] = 0.45f + 0.05f * br;
        p[TAIL_FLAME] = 0.6f;
        p[TURN] = 0;
    });

    /** Sunk into the bean bag watching the TV on his right, laughing now and then. */
    static final Move WATCH = gazing("watch", 0, (p, t, a) -> {
        float laugh = bump(t % 11f, 7.5f, 9f);
        p[SIT] = 1;
        p[TORSO] = -8;
        p[HEAD] = -6 - 4 * laugh;
        p[HEAD_Y] = 0.6f * laugh * sn(t, 6);
        p[LOOK_X] = 0.85f;
        p[LOOK_Y] = -0.45f;
        p[SMILE] = 0.7f + 0.3f * laugh;
        p[MOUTH] = 0.1f + 0.5f * laugh;
        p[HAPPY] = laugh > 0.5f ? 1 : 0;
        p[FS] = 26;
        p[FE] = 70;
        p[BS] = 30;
        p[BE] = 70;
        p[FLX] = 4;
        p[FLY] = 12 + 2 * sn(t, 0.4f);
        p[BLX] = 2;
        p[BLY] = 16;
        p[TAIL] = -18 + 3 * sn(t, 0.2f);
        p[TAIL_CURL] = 0.5f;
    });

    /** At the computer. The room shows him from behind; this is the pose while he climbs in. */
    static final Move GAME = gazing("game", 0, (p, t, a) -> {
        p[SIT] = 1;
        p[FS] = 56;
        p[FE] = 84;
        p[BS] = 56;
        p[BE] = 90;
        p[BROW] = 0.6f;
        p[LOOK_X] = 0.2f;
        p[LOOK_Y] = -0.3f;
        p[MOUTH] = 0.2f;
        p[SMILE] = 0.8f;
        p[TAIL] = -10 + 6 * sn(t, 0.5f);
    });

    static final Move THINK = gazing("think", 0, (p, t, a) -> {
        p[FS] = 30;
        p[FE] = 136;
        p[BS] = 40;
        p[BE] = 100;
        p[HEAD] = -7;
        p[LOOK_X] = 0.5f;
        p[LOOK_Y] = -0.75f;
        p[QUESTION] = ramp(t, 0.4f, 0.8f);
        p[SMILE] = -0.1f;
        p[TAIL] = 8 * sn(t, 1.5f);
        p[TURN] = 0.7f;
    });

    // ---- talking to you ----

    static final Move WAVE = once("wave", 2.0f, (p, t, a) -> {
        float e = hold(t, 0, 0.25f, 1.7f, 2.0f);
        p[FS] = -12 - 96 * e;
        p[FE] = 18 + (-60 + 28 * sn(t, 2.6f)) * e;
        p[F_OPEN] = e > 0.4f ? 1 : 0;
        p[MOUTH] = 0.45f * e;
        p[SMILE] = 1;
        p[TURN] = 1 - 0.5f * e;
        p[HEAD] = -3 * e;
    });

    static final Move CHEER = once("cheer", 2.0f, (p, t, a) -> {
        float hop = Math.abs(sn(t, 1.6f)) * (1 - ramp(t, 1.5f, 2));
        p[LIFT] = 13 * hop;
        p[SHADOW] = 1;
        p[FS] = -108 + 10 * sn(t, 3.2f);
        p[BS] = 102 - 10 * sn(t, 3.2f);
        p[FE] = -38;
        p[BE] = 24;
        p[HAPPY] = 1;
        p[MOUTH] = 0.8f;
        p[SPARKLE] = 1;
        p[BLUSH] = 0.6f;
        p[FLY] = 4 * hop;
        p[BLY] = 5 * hop;
        p[FLAME] = 1.3f;
    });

    static final Move PETTED = gazing("petted", 0, (p, t, a) -> {
        p[HAPPY] = 1;
        p[BLUSH] = 1;
        p[HEARTS] = 1;
        p[SMILE] = 1;
        p[MOUTH] = 0.3f;
        p[HEAD] = 6 * sn(t, 0.5f) - 4;
        p[SQUASH] = 0.98f + 0.02f * sn(t, 1);
        p[TAIL] = 16 * sn(t, 0.9f);
        p[FS] = 10;
        p[FE] = 60;
        p[BS] = 14;
        p[BE] = 60;
        p[TURN] = 0.4f;
    });

    static final Move POKED = once("poked", 0.7f, (p, t, a) -> {
        float e = 1 - ramp(t, 0.1f, 0.7f);
        p[SQUASH] = 1 - 0.1f * bump(t, 0, 0.25f);
        p[LEAN] = -7 * e;
        p[WIDE] = 0.7f * e;
        p[EXCLAIM] = e;
        p[MOUTH] = 0.4f * e;
        p[FS] = -12 + 40 * e;
        p[BS] = 12 + 30 * e;
        p[TURN] = 1 - 0.6f * e;
    });

    static final Move ANNOYED = once("annoyed", 2.4f, (p, t, a) -> {
        float e = hold(t, 0, 0.2f, 2.0f, 2.4f);
        p[ANGER] = e;
        p[BROW] = e;
        p[SMILE] = -0.8f * e;
        p[MOUTH] = (0.25f + 0.3f * Math.abs(sn(t, 3))) * e;
        p[FS] = -12 + (82 + 10 * sn(t, 6)) * e;
        p[FE] = 18 + 40 * e;
        p[BS] = 12 + 20 * e;
        p[BE] = 18 + 70 * e;
        p[SHAKE] = 0.25f * e;
        p[TURN] = 1 - 0.5f * e;
        p[FLAME] = 1 + 0.5f * e;
    });

    static final Move POUT = gazing("pout", 0, (p, t, a) -> {
        p[FS] = 30;
        p[FE] = 100;
        p[BS] = 30;
        p[BE] = 96;
        p[ANGER] = ramp(t, 0, 0.3f) * (0.6f + 0.4f * sn(t, 0.5f));
        p[BROW] = 0.9f;
        p[SMILE] = -0.7f;
        p[EYES] = 0.65f;
        p[LOOK_X] = 0.8f;
        p[HEAD] = -6;
        p[TURN] = 1;
        p[TAIL] = 10 * sn(t, 1.2f);
        p[BLUSH] = 0.5f;
    });

    static final Move SURPRISED = gazing("surprised", 1.1f, (p, t, a) -> {
        float e = 1 - ramp(t, 0.6f, 1.1f);
        p[LIFT] = 12 * bump(t, 0, 0.42f);
        p[WIDE] = e;
        p[EXCLAIM] = e;
        p[MOUTH] = 0.6f * e;
        p[SMILE] = -0.4f * e;
        p[BROW] = -0.3f * e;
        p[FS] = -12 - 92 * e;
        p[BS] = 12 + 88 * e;
        p[FE] = 18 - 58 * e;
        p[F_OPEN] = e > 0.3f ? 1 : 0;
        p[B_OPEN] = e > 0.3f ? 1 : 0;
        p[LEAN] = -8 * e;
        p[TURN] = 1 - 0.7f * e;
    });

    static final Move SAD = gazing("sad", 0, (p, t, a) -> {
        p[HEAD] = 13;
        p[BROW] = -1;
        p[SMILE] = -1;
        p[EYES] = 0.7f;
        p[LOOK_Y] = 0.7f;
        p[FLAME] = 0.45f + 0.05f * sn(t, 0.4f);
        p[TAIL_FLAME] = 0.5f;
        p[TAIL] = -26;
        p[FS] = -4;
        p[FE] = 6;
        p[BS] = 4;
        p[BE] = 6;
        p[HIP] = 2;
        p[TORSO] = 5;
    });

    static final Move WOOZY = once("dizzy", 2.8f, (p, t, a) -> {
        float e = 1 - ramp(t, 2.2f, 2.8f);
        p[DIZZY] = e;
        p[LEAN] = 8 * sn(t, 0.8f) * e;
        p[HEAD] = 6 * sn(t, 0.65f) * e;
        p[EYES] = 1 - 0.5f * e;
        p[MOUTH] = 0.3f * e;
        p[SMILE] = -0.3f * e;
        p[FS] = -12 + 25 * sn(t, 0.8f) * e;
        p[BS] = 12 - 25 * sn(t, 0.8f) * e;
        p[SWEAT] = 0.6f * e;
    });

    // ---- being handled ----

    static final Move HELD = loop("held", (p, t, a) -> {
        p[FLY] = -10 + 3 * sn(t, 1.4f);
        p[BLY] = -12 + 3 * sn(t, 1.4f + 0.5f);
        p[FLX] = 3 * sn(t, 1.4f);
        p[BLX] = -3 * sn(t, 1.4f);
        p[FS] = -112;
        p[BS] = 104;
        p[FE] = -40;
        p[BE] = 26;
        p[F_OPEN] = 1;
        p[B_OPEN] = 1;
        p[WIDE] = 0.7f;
        p[MOUTH] = 0.45f + 0.1f * sn(t, 1.3f);
        p[SWEAT] = 1;
        p[SMILE] = -0.3f;
        p[BROW] = -0.4f;
        p[TAIL] = 25 + 10 * sn(t, 1);
        p[SHADOW] = 0;
        p[TURN] = 0.4f;
    });

    static final Move TUMBLE = loop("tumble", (p, t, a) -> {
        p[SPIN] = a.spin;
        p[FS] = -140;
        p[BS] = 130;
        p[FE] = -20;
        p[BE] = 20;
        p[FLY] = 4;
        p[BLY] = 6;
        p[FLX] = 8;
        p[BLX] = -10;
        p[WIDE] = 1;
        p[MOUTH] = 0.7f;
        p[SWEAT] = 1;
        p[SHADOW] = 0;
    });

    static final Move AIR = loop("air", (p, t, a) -> {
        float up = Math.max(-1, Math.min(1, -a.vy / 1500f));
        p[FLX] = 4;
        p[FLY] = 7;
        p[BLX] = -6;
        p[BLY] = 4;
        p[FS] = -100 + 10 * sn(t, 2);
        p[BS] = 100;
        p[FE] = -40;
        p[BE] = 30;
        p[MOUTH] = 0.35f;
        p[SQUASH] = 1 + 0.08f * Math.abs(up);
        p[SHADOW] = 0;
        p[LEAN] = -4 * up;
        p[TAIL] = 20 * up;
    });

    static final Move CROUCH = once("crouch", 0.2f, (p, t, a) -> {
        float e = ramp(t, 0, 0.2f);
        p[HIP] = 10 * e;
        p[SQUASH] = 1 - 0.1f * e;
        p[FS] = -12 - 30 * e;
        p[BS] = 12 - 38 * e;
        p[LEAN] = 8 * e;
        p[BROW] = 0.5f * e;
    });

    static final Move LAND = once("land", 0.35f, (p, t, a) -> {
        float e = 1 - ramp(t, 0, 0.35f);
        p[SQUASH] = 1 - 0.16f * e * e;
        p[HIP] = 9 * e;
        p[DUST] = e;
        p[FS] = -12 + 50 * e;
        p[BS] = 12 + 40 * e;
        p[F_OPEN] = e > 0.5f ? 1 : 0;
    });

    // ---- training and Corona moves ----

    static final Move KNUCKLE = gazing("knuckle", 2.4f, (p, t, a) -> {
        guard(p);
        float j1 = jab(t, 0.15f, 0.28f), j2 = jab(t, 0.45f, 0.28f), j3 = jab(t, 0.75f, 0.28f);
        float wind = hold(t, 1.05f, 1.3f, 1.3f, 1.42f);
        float big = hold(t, 1.32f, 1.45f, 1.95f, 2.3f);
        float fj = Math.max(j1, j3), bj = j2;
        p[FS] = 60 + 37 * fj - 75 * wind + 67 * big;
        p[FE] = 80 - 80 * fj + 60 * wind - 80 * big;
        p[BS] = 66 + 26 * bj + 10 * big;
        p[BE] = 86 - 86 * bj + 5 * big;
        p[F_GLOW] = Math.max(ramp(t, 1.0f, 1.35f) * (1 - ramp(t, 2.0f, 2.4f)), 0.4f * j3);
        p[LEAN] = 5 + 4 * (fj + bj) - 6 * wind + 10 * big;
        p[TORSO] = 3 * (fj + bj) + 6 * big - 4 * wind;
        p[FLX] = 7 * big;
        p[BLX] = -5 * big;
        p[MOUTH] = 0.2f + 0.3f * (fj + bj) + 0.7f * big;
        p[BROW] = 1;
        p[LOOK_X] = 0.8f;
        p[SHAKE] = 0.6f * bump(t, 1.4f, 1.6f);
        p[SPARKLE] = bump(t, 1.4f, 2.1f);
        p[FLAME] = 1 + 0.6f * big;
    });

    static final Move FLAME_SHOT = gazing("flame", 2.8f, (p, t, a) -> {
        float charge = ramp(t, 0, 1.4f) * (1 - ramp(t, 1.45f, 1.6f));
        float shot = hold(t, 1.4f, 1.5f, 2.1f, 2.7f);
        p[CHARGE] = charge;
        p[HIP] = 4 * charge;
        p[FS] = -12 - 30 * charge + 20 * shot;
        p[BS] = 12 - 40 * charge + 10 * shot;
        p[FE] = 18 + 60 * charge;
        p[BE] = 18 + 60 * charge;
        p[HEAD] = 6 * charge - 10 * shot;
        p[LEAN] = -3 * charge + 8 * shot;
        p[BROW] = 1;
        p[MOUTH] = 0.15f * charge + 0.9f * shot;
        p[FLAME] = 1 + 1.5f * shot;
        p[SHAKE] = 0.3f * charge * (t > 0.9f ? 1 : 0) + 0.5f * bump(t, 1.4f, 1.7f);
        p[SPARKLE] = bump(t, 1.45f, 2.3f);
        p[LOOK_X] = 0.8f;
        p[LOOK_Y] = -0.2f;
    });

    static final Move PROMINENCE = gazing("prominence", 3.2f, (p, t, a) -> {
        float gather = ramp(t, 0, 1.2f);
        float leap = bump(t, 1.25f, 2.35f);
        float land = bump(t, 2.3f, 2.7f);
        float aura = gather * (1 - ramp(t, 2.5f, 3.2f));
        p[AURA] = aura;
        p[HIP] = 8 * gather * (1 - ramp(t, 1.2f, 1.3f)) + 8 * land;
        p[LIFT] = 46 * leap;
        p[SHADOW] = 1 - leap;
        p[FS] = -12 - 28 * gather - 92 * leap;
        p[BS] = 12 - 34 * gather + 112 * leap;
        p[FE] = 30 * (1 - leap) - 50 * leap;
        p[BE] = 30 * (1 - leap);
        p[F_GLOW] = leap;
        p[B_GLOW] = leap;
        p[TORSO] = 10 * gather * (1 - leap) - 8 * leap;
        p[BROW] = 1;
        p[MOUTH] = 0.2f + 0.7f * leap;
        p[FLAME] = 1 + aura;
        p[TAIL_FLAME] = 1 + aura;
        p[FLY] = 6 * leap;
        p[BLY] = 8 * leap;
        p[DUST] = land;
        p[LOOK_Y] = -0.3f * leap;
    });

    static final Move TRAIN = gazing("train", 0, (p, t, a) -> {
        guard(p);
        float cyc = t % 1.6f;
        float fj = jab(cyc, 0.1f, 0.3f), bj = jab(cyc, 0.5f, 0.3f), up = jab(cyc, 1.0f, 0.4f);
        p[FS] = 60 + 37 * fj + 45 * up;
        p[FE] = 80 - 80 * fj - 50 * up;
        p[BS] = 66 + 26 * bj;
        p[BE] = 86 - 86 * bj;
        p[HIP] = 1.5f + 1.5f * sn(t, 2.5f) - 3 * up;
        p[LIFT] = 4 * up;
        p[LEAN] = 6 + 3 * (fj + bj);
        p[MOUTH] = 0.15f + 0.35f * (fj + bj + up);
        p[LOOK_X] = 0.8f;
        p[SWEAT] = ramp(t, 4, 6) * 0.7f;
    });

    static final Move SQUATS = gazing("squats", 0, (p, t, a) -> {
        float d = 0.5f - 0.5f * cs(t, 0.7f);
        p[HIP] = 13 * d;
        p[SQUASH] = 1 - 0.04f * d;
        p[FS] = 88;
        p[BS] = 84;
        p[FE] = 8;
        p[BE] = 8;
        p[TORSO] = 8 * d;
        p[BROW] = 0.8f;
        p[MOUTH] = 0.25f * d;
        p[SMILE] = 0.2f;
        p[LOOK_X] = 0.6f;
        p[SWEAT] = ramp(t, 3, 5) * 0.6f;
    });

    // ---- fun ----

    static final Move DANCE = loop("dance", (p, t, a) -> {
        float s = sn(t, 1);
        p[HIP] = 2 + 2.5f * sn(t, 2);
        p[LEAN] = 7 * s;
        p[FS] = -70 - 40 * s;
        p[BS] = 70 - 40 * s;
        p[FE] = -30 - 15 * s;
        p[BE] = 30 - 15 * s;
        p[F_OPEN] = 1;
        p[B_OPEN] = 1;
        p[HAPPY] = (t % 4) < 2.4f ? 1 : 0;
        p[NOTES] = 1;
        p[MOUTH] = 0.3f + 0.2f * sn(t, 2);
        p[SMILE] = 1;
        p[FLX] = 5 * Math.max(0, s);
        p[FLY] = 5 * Math.max(0, s);
        p[BLY] = 5 * Math.max(0, -s);
        p[TURN] = 0.5f + 0.5f * sn(t, 0.5f);
        p[TAIL] = 18 * s;
        p[HEAD] = 4 * sn(t, 2);
    });

    static final Move BAKE = gazing("bake", 0, (p, t, a) -> {
        p[HAT] = 1;
        p[BOWL] = 1;
        p[FS] = 46 + 9 * sn(t, 2.2f);
        p[FE] = 74 + 18 * cs(t, 2.2f);
        p[BS] = 34;
        p[BE] = 86;
        p[NOTES] = 0.8f;
        p[MOUTH] = 0.15f + 0.12f * Math.abs(sn(t, 0.8f));
        p[SMILE] = 1;
        p[HEAD] = 5 + 3 * sn(t, 0.4f);
        p[LOOK_Y] = 0.6f;
        p[LOOK_X] = 0.3f;
        p[HAPPY] = (t % 7) > 5.2f ? 1 : 0;
        p[TAIL] = 8 * sn(t, 0.5f);
        p[HIP] = 1 + 1 * sn(t, 1.1f);
    });

    static final Move WORK = gazing("work", 0, (p, t, a) -> {
        guard(p);
        p[CHARGE] = 0.35f + 0.15f * sn(t, 0.7f);
        p[F_GLOW] = 0.45f + 0.25f * sn(t, 1.1f);
        p[B_GLOW] = 0.45f - 0.25f * sn(t, 1.1f);
        p[HIP] = 3 + 0.8f * sn(t, 1.5f);
        p[BROW] = 0.7f;
        p[LOOK_X] = 0.3f;
        p[LOOK_Y] = 0.3f;
        p[TAIL] = 6 * sn(t, 0.9f);
        p[SMILE] = 0.4f;
    });

    static final Move CELEBRATE = once("celebrate", 2.4f, (p, t, a) -> {
        float up = bump(t, 0.1f, 0.75f);
        float pump = hold(t, 0.05f, 0.25f, 1.9f, 2.4f);
        p[LIFT] = 26 * up;
        p[SHADOW] = 1 - up;
        p[FS] = -12 - 98 * pump;
        p[FE] = 18 - 64 * pump;
        p[F_GLOW] = pump;
        p[BS] = 12 + 40 * pump;
        p[BE] = 18 + 90 * pump;
        p[HAPPY] = 1;
        p[MOUTH] = 0.8f;
        p[SPARKLE] = 1;
        p[FLY] = 6 * up;
        p[BLY] = 8 * up;
        p[FLAME] = 1 + 0.6f * pump;
        p[TURN] = 0.6f;
    });

    static final Move OOPS = once("oops", 2.8f, (p, t, a) -> {
        float e = hold(t, 0, 0.3f, 2.3f, 2.8f);
        p[SWEAT] = e;
        p[HEAD] = 10 * e;
        p[BROW] = -e;
        p[MOUTH] = 0.2f * e;
        p[SMILE] = -0.2f - 0.6f * e;
        p[FS] = -12 - 138 * e;
        p[FE] = 18 - 33 * e;
        p[F_OPEN] = e > 0.5f ? 1 : 0;
        p[BLUSH] = 0.6f * e;
        p[EYES] = 1 - 0.3f * e;
        p[TURN] = 1 - 0.4f * e;
        p[FLAME] = 1 - 0.4f * e;
        p[HEAD_Y] = e * 0.8f * sn(t, 2);
    });

    static final Move NOD = once("nod", 1.0f, (p, t, a) -> {
        p[HEAD] = 9 * bump(t, 0, 0.35f) + 7 * bump(t, 0.4f, 0.75f);
        p[SMILE] = 1;
        p[MOUTH] = 0.2f;
        p[TURN] = 0.6f;
    });

    static final Move SHAKE_HEAD = once("shake", 1.2f, (p, t, a) -> {
        float e = 1 - ramp(t, 0.9f, 1.2f);
        p[TURN] = 0.5f + 0.5f * sn(t, 2.5f) * e;
        p[HEAD] = 2 * sn(t, 2.5f) * e;
        p[SMILE] = -0.4f;
        p[BROW] = -0.3f;
    });

    private Moves() {
    }
}
