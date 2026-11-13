package dev.grokbuild.coronamon;

/**
 * One frame of Coronamon's body language, as named slots in a float array so poses can be
 * blended in a loop. Angles are degrees; lengths are puppet units (about 220 = standing height).
 */
final class Pose {
    static final int
            FACING = 0,     // +1 right, -1 left (never blended)
            LIFT = 1,       // whole body raised off the ground (jumps, being held)
            HIP = 2,        // hips lowered (crouch, breathing)
            LEAN = 3,       // whole body tilt around the feet, + = forward
            SQUASH = 4,     // vertical scale around the feet, 1 = normal
            TORSO = 5,      // torso tilt at the hips, + = forward
            HEAD = 6,       // head tilt, + = forward/down
            HEAD_Y = 7,     // head bob
            TURN = 8,       // 0 = facing the viewer, 1 = three-quarter view toward FACING
            LOOK_X = 9,     // pupils, -1..1
            LOOK_Y = 10,
            EYES = 11,      // 1 open .. 0 shut
            HAPPY = 12,     // 1 = ^^ eyes
            WIDE = 13,      // surprise
            BROW = 14,      // -1 worried .. +1 determined
            MOUTH = 15,     // 0 shut .. 1 wide open
            SMILE = 16,     // -1 frown .. +1 smile
            BLUSH = 17,
            FS = 18,        // front arm shoulder, + = forward, 0 = hanging
            FE = 19,        // front arm elbow bend
            BS = 20,        // back arm
            BE = 21,
            F_GLOW = 22,    // burning fists (Corona Knuckle)
            B_GLOW = 23,
            F_OPEN = 24,    // 1 = open paw (waving)
            B_OPEN = 25,
            FLX = 26,       // front foot offset from rest: x forward, y up
            FLY = 27,
            BLX = 28,
            BLY = 29,
            SIT = 30,       // 0 standing .. 1 sitting
            TAIL = 31,      // tail swing
            TAIL_CURL = 32,
            FLAME = 33,     // forehead flame size, 1 = normal
            TAIL_FLAME = 34,
            AURA = 35,      // body clad in flames (Petit Prominence)
            HAT = 36,       // chef's hat
            BOWL = 37,      // mixing bowl and whisk
            ZZZ = 38,
            SWEAT = 39,
            HEARTS = 40,
            NOTES = 41,
            SPARKLE = 42,
            QUESTION = 43,
            EXCLAIM = 44,
            ANGER = 45,
            DIZZY = 46,
            DUST = 47,
            SHAKE = 48,
            CHARGE = 49,    // gathering flame in the brow (Corona Flame)
            SPIN = 50,      // tumble around the middle of the body, clockwise
            SHADOW = 51,    // ground shadow, 0 while airborne
            N = 52;

    final float[] v = new float[N];

    Pose() {
        rest();
    }

    Pose rest() {
        java.util.Arrays.fill(v, 0);
        v[FACING] = 1;
        v[SQUASH] = 1;
        v[TURN] = 1;
        v[EYES] = 1;
        v[SMILE] = 0.6f;
        v[FLAME] = 1;
        v[TAIL_FLAME] = 1;
        v[SHADOW] = 1;
        v[FS] = -12;
        v[FE] = 18;
        v[BS] = 12;
        v[BE] = 18;
        return this;
    }

    Pose set(Pose o) {
        System.arraycopy(o.v, 0, v, 0, N);
        return this;
    }

    /** this = a + (b - a) * t, keeping b's facing. */
    Pose lerp(Pose a, Pose b, float t) {
        for (int i = 0; i < N; i++) v[i] = a.v[i] + (b.v[i] - a.v[i]) * t;
        v[FACING] = b.v[FACING];
        return this;
    }

    float get(int i) {
        return v[i];
    }
}
