package dev.grokbuild.coronamon;

import java.util.Random;

import static dev.grokbuild.coronamon.Pose.*;

/**
 * Plays {@link Moves} into a pose: crossfades from one move to the next, turns him around when
 * the facing changes, and layers blinking, breathing, gaze and lip flaps on top.
 */
final class Actor {
    final Pose pose = new Pose();
    private final Pose from = new Pose(), target = new Pose();
    private final Random rnd = new Random();

    Moves.Move move = Moves.IDLE;
    /** Seconds since the current move started. */
    float time;
    /** Seconds since the actor was created; drives flames and sways. */
    float clock;
    private float fade = 1, fadeDur = 0.25f;

    // Set by the brain every frame.
    float facing = 1;
    /** Distance walked in puppet units, which drives the gait. */
    float stride;
    /** Vertical speed in px/s while airborne, + = falling. */
    float vy;
    float spin;
    /** Where he looks, -1..1 on each axis, in screen terms (+x = right). */
    float gazeX, gazeY;
    boolean gazing;
    boolean talking;
    /** How loud his voice is right now while he talks out loud, 0..1; -1 when no voice is playing. */
    float voice = -1;
    /** How loud you are while he listens to you, 0..1. */
    float hear;

    private float shown = 1, turnT = 1;
    private float mouthOpen;
    private float blinkIn = 2.5f, blinkT = 1;
    private float lookX, lookY, wanderIn = 1.5f, wanderX, wanderY;

    void play(Moves.Move m) {
        play(m, 0.25f);
    }

    void play(Moves.Move m, float fadeSeconds) {
        if (m == move && m.loop) return;
        from.set(pose);
        move = m;
        time = 0;
        fade = fadeSeconds <= 0 ? 1 : 0;
        fadeDur = Math.max(0.01f, fadeSeconds);
    }

    /** Restarts the move even if it is already playing. */
    void replay(Moves.Move m) {
        from.set(pose);
        move = m;
        time = 0;
        fade = 0;
        fadeDur = 0.15f;
    }

    boolean finished() {
        return !move.loop && time >= move.dur;
    }

    void tick(float dt) {
        clock += dt;
        time += dt;
        target.rest();
        move.apply(target, time, this);
        float[] p = target.v;

        if (facing != shown && turnT >= 1) turnT = 0;
        float turnMul = 1;
        if (turnT < 1) {
            turnT = Math.min(1, turnT + dt / 0.22f);
            if (turnT >= 0.5f) shown = facing;
            turnMul = Math.abs(1 - 2 * turnT);
        }
        p[FACING] = shown;
        p[TURN] *= turnMul;

        blinkIn -= dt;
        if (blinkIn <= 0) {
            blinkT = 0;
            blinkIn = 1.8f + rnd.nextFloat() * 4.5f;
            if (rnd.nextFloat() < 0.15f) blinkIn = 0.25f;
        }
        if (blinkT < 1) {
            blinkT = Math.min(1, blinkT + dt / 0.16f);
            p[EYES] *= Math.abs(1 - 2 * blinkT);
        }

        p[SQUASH] *= 1 + 0.012f * Moves.sn(clock, 0.35f);

        if (!move.ownsGaze) {
            float tx, ty;
            if (gazing) {
                tx = gazeX * shown;
                ty = gazeY;
            } else {
                wanderIn -= dt;
                if (wanderIn <= 0) {
                    wanderIn = 1.2f + rnd.nextFloat() * 3.5f;
                    boolean centre = rnd.nextFloat() < 0.45f;
                    wanderX = centre ? 0.25f : rnd.nextFloat() * 1.8f - 0.9f;
                    wanderY = centre ? 0 : rnd.nextFloat() * 0.9f - 0.5f;
                }
                tx = wanderX;
                ty = wanderY;
            }
            float k = Math.min(1, dt * 9);
            lookX += (tx - lookX) * k;
            lookY += (ty - lookY) * k;
            p[LOOK_X] += lookX;
            p[LOOK_Y] += lookY;
        }
        p[LOOK_X] = Math.max(-1, Math.min(1, p[LOOK_X]));
        p[LOOK_Y] = Math.max(-1, Math.min(1, p[LOOK_Y]));

        if (voice >= 0) {
            mouthOpen += (voice - mouthOpen) * Math.min(1, dt * (voice > mouthOpen ? 30 : 14));
            p[MOUTH] = Math.max(p[MOUTH] * 0.3f, 0.04f + 0.72f * mouthOpen);
        } else {
            mouthOpen = 0;
            if (talking && p[EYES] > 0.2f) {
                float flap = 0.18f + 0.3f * Math.abs(Moves.sn(clock, 2.7f)) * (0.6f + 0.4f * Moves.sn(clock, 0.9f));
                p[MOUTH] = Math.max(p[MOUTH] * 0.35f, flap);
            }
        }

        if (fade < 1) {
            fade = Math.min(1, fade + dt / fadeDur);
            float e = fade * fade * (3 - 2 * fade);
            pose.lerp(from, target, e);
            pose.v[SPIN] = target.v[SPIN];
        } else {
            pose.set(target);
        }
    }
}
