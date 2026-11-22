package dev.grokbuild.coronamon;

import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Decides what Coronamon does: wanders, naps, trains, reacts to touches and to the jobs and events
 * the service reports, and moves him between the two screens. Positions are in pixels of the
 * current stage (display), with (x, y) at his feet.
 */
final class Brain {
    interface Host {
        int stageCount();

        Stage stage(int i);

        /** Shows a line in the bubble. buttons may be null; prompt gets the chosen index or -1. */
        void say(Lines.Line line, String[] buttons, Prompt prompt);

        boolean speaking();

        void stageChanged(int from, int to);

        void ghostChanged(boolean ghost);

        Settings settings();

        Lines lines();

        /** Called every second: sends him home or brings him back. Returns true while he's home. */
        boolean homeCheck();

        /** Whether his TV has something of yours to play. */
        boolean tvReady();
    }

    interface Prompt {
        void chose(int index);
    }

    static final class Stage {
        int displayId;
        String name;
        float w, h, k = 1;
        boolean on = true;
        boolean game;
        String topPkg, topLabel;
        /** Where his feet rest, when it isn't the bottom edge (his room's floor). */
        float floor;
    }

    static final float WALK = 85, RUN = 250, GRAVITY = 2600;

    final Actor actor = new Actor();
    private final Host host;
    private final Random rnd = new Random();

    int st;
    float x, y, vx, vy;
    float facing = 1;
    boolean air, held, ghost;
    private boolean passFloor, hopping, lookBack;
    private float airTime;
    private float grabDX, grabDY;
    private boolean petting;
    private float spinRate;

    float energy = 0.85f;
    private final ArrayDeque<Task> tasks = new ArrayDeque<>();
    private Task cur;
    private float chatIn = 25;
    private int taps;
    private long tapsAt;
    private float gameCheck;
    private final Map<String, Long> lastSaid = new HashMap<>();
    private long lastReaction;

    /** The build or install he is helping with, or null. */
    String job, jobApp;
    private long jobSince;

    /** His room, in room units (see Room), while games and videos have the screens; null when he's out. */
    Stage room;
    /** The seat he's in or getting into, and how far in: 0 standing on the floor .. 1 settled. */
    int seat = Room.NONE;
    float seatBlend;
    private float seatGoal;
    /** What he's doing on his computer while he sits at it. */
    int monitor = Room.SCREEN_GAME;
    /** Arms up at the computer, 0..1: he's winning. */
    float cheer;
    private float cheerIn = 8, cheerFor;

    Brain(Host host) {
        this.host = host;
    }

    Stage stage() {
        return room != null ? room : host.stage(st);
    }

    float ground() {
        Stage s = stage();
        return s.floor > 0 ? s.floor : s.h - 4 * s.k;
    }

    /** Keeps his mane and tail flame on screen. */
    private float margin() {
        return 78 * stage().k;
    }

    void place(int stageIndex, float fx) {
        int from = st;
        st = Math.max(0, Math.min(host.stageCount() - 1, stageIndex));
        Stage s = stage();
        x = Math.max(margin(), Math.min(s.w - margin(), fx));
        y = ground();
        air = false;
        held = false;
        if (from != st) host.stageChanged(from, st);
    }

    /** The screen size changed (rotation, size setting). */
    void clampToStage() {
        Stage s = stage();
        x = Math.max(margin(), Math.min(s.w - margin(), x));
        if (!air && !held) y = ground();
        if (y > s.h + 400 * s.k) y = ground();
    }

    boolean fast() {
        return air || held || actor.move == Moves.RUN || actor.move == Moves.WALK || !actor.move.loop || seatBlend != seatGoal
                || (cheer > 0 && cheer < 1);
    }

    boolean asleep() {
        return actor.move == Moves.SLEEP || actor.move == Moves.LIE;
    }

    // ---- main loop ----

    void tick(float dt) {
        Stage s = stage();
        if (held) {
            // follows the finger
        } else if (air) {
            physics(dt, s);
        } else {
            if (cur == null) {
                cur = tasks.poll();
                if (cur == null) {
                    plan();
                    cur = tasks.poll();
                }
            }
            if (cur != null) {
                if (!cur.started) {
                    cur.started = true;
                    cur.start(this);
                }
                cur.t += dt;
                if (cur.step(this, dt)) cur = null;
            }
        }
        if (job != null && System.currentTimeMillis() - jobSince > 20 * 60_000L) endJob();

        energy = clamp(energy + dt * (asleep() ? 0.004f : -0.00045f), 0, 1);
        seats(dt);

        gameCheck -= dt;
        if (gameCheck <= 0) {
            gameCheck = 1;
            checkGames();
        }
        chatter(dt);

        actor.facing = facing;
        actor.vy = vy;
        if (air && actor.move == Moves.TUMBLE) actor.spin += spinRate * dt;
        actor.gazing = (host.speaking() || lookBack) && !asleep();
        actor.gazeX = lookBack ? -facing : 0;
        actor.gazeY = lookBack ? 0.3f : 0.1f;
        lookBack = false;
        actor.tick(dt);
    }

    /** Getting into and out of seats, and cheering at his computer game. */
    private void seats(float dt) {
        float step = dt / 0.55f;
        seatBlend = seatGoal > seatBlend ? Math.min(seatGoal, seatBlend + step) : Math.max(seatGoal, seatBlend - step);
        if (seatBlend <= 0 && seatGoal <= 0) seat = Room.NONE;
        boolean playing = seat == Room.CHAIR && seatBlend >= 1 && monitor == Room.SCREEN_GAME && actor.move == Moves.GAME;
        if (playing) {
            cheerIn -= dt;
            if (cheerIn <= 0) {
                cheerIn = 7 + rnd.nextFloat() * 12;
                cheerFor = 1.2f + rnd.nextFloat();
            }
        }
        cheerFor = playing ? cheerFor - dt : 0;
        float want = cheerFor > 0 ? 1 : 0;
        cheer = want > cheer ? Math.min(want, cheer + dt / 0.22f) : Math.max(want, cheer - dt / 0.35f);
    }

    private void physics(float dt, Stage s) {
        airTime += dt;
        vy += GRAVITY * s.k * dt;
        x += vx * dt;
        y += vy * dt;
        float m = margin();
        if (x < m) {
            x = m;
            vx = Math.abs(vx) * 0.45f;
            spinRate = -spinRate * 0.6f;
        } else if (x > s.w - m) {
            x = s.w - m;
            vx = -Math.abs(vx) * 0.45f;
            spinRate = -spinRate * 0.6f;
        }
        if (Math.abs(vx) > 30) facing = vx > 0 ? 1 : -1;
        int other = otherStage();
        boolean upward = other >= 0 && s.displayId != Screens.TOP_ID && host.stage(other).on
                && host.stage(other).displayId == Screens.TOP_ID
                && (hopping || host.settings().screenPref().equals("auto"));
        if (upward && y < 0 && vy < 0) {
            Stage t = host.stage(other);
            float nx = mapX(x, s, t);
            transfer(other);
            x = nx;
            y = t.h + 250 * t.k;
            vy = Math.min(vy, -1500 * t.k);
            return;
        }
        if (!upward && vy < 0 && y < 235 * s.k) {
            y = 235 * s.k;
            vy = Math.abs(vy) * 0.3f;
        }
        if (passFloor && other < 0) passFloor = false;
        if (passFloor) {
            if (y > s.h + 300 * s.k) {
                Stage t = host.stage(other);
                float nx = mapX(x, s, t);
                transfer(other);
                x = nx;
                y = -30 * t.k;
                vy = Math.max(vy, 300 * t.k);
                passFloor = false;
            }
            return;
        }
        float gy = ground();
        if (vy > 0 && y >= gy) {
            y = gy;
            float impact = vy / s.k;
            air = false;
            hopping = false;
            vx = 0;
            vy = 0;
            boolean tumbled = actor.move == Moves.TUMBLE;
            actor.spin = 0;
            tasks.clear();
            cur = null;
            if (tumbled && (impact > 1500 || airTime > 0.7f)) {
                actor.replay(Moves.LAND);
                tasks.add(new Play(Moves.LAND, 0));
                tasks.add(new Play(Moves.WOOZY, 0).saying("dizzy", 0.8f));
            } else {
                actor.replay(Moves.LAND);
                tasks.add(new Play(Moves.LAND, 0));
                if (airTime > 0.9f && rnd.nextFloat() < 0.35f) tasks.add(new Play(Moves.CHEER, 0).saying("land", 1));
            }
        }
    }

    private void transfer(int to) {
        int from = st;
        st = to;
        host.stageChanged(from, to);
    }

    private int otherStage() {
        if (host.stageCount() < 2) return -1;
        return st == 0 ? 1 : 0;
    }

    /** Same physical spot across screens, measured from each screen's centre. */
    private static float mapX(float x, Stage a, Stage b) {
        float rel = (x - a.w / 2) / a.k;
        return b.w / 2 + rel * b.k;
    }

    // ---- touch ----

    void touchDown() {
        actor.gazing = true;
    }

    void tap() {
        long now = System.currentTimeMillis();
        if (now - tapsAt > 4000) taps = 0;
        taps++;
        tapsAt = now;
        interrupt();
        if (asleep()) {
            tasks.add(new Play(Moves.SURPRISED, 0).saying("wake", 1));
            energy = Math.max(energy, 0.35f);
            return;
        }
        if (taps >= 5) {
            taps = 0;
            tasks.add(new Play(Moves.ANNOYED, 0).saying("poke", 1));
            tasks.add(new Play(Moves.POUT, 6));
        } else if (taps >= 2) {
            tasks.add(new Play(Moves.POKED, 0).saying(taps >= 3 ? "poke" : null, 1));
        } else {
            Moves.Move m = rnd.nextFloat() < 0.5f ? Moves.WAVE : Moves.NOD;
            tasks.add(new Play(m, 0).saying("tap", 1));
        }
    }

    void longPress() {
        interrupt();
        petting = true;
        actor.play(Moves.PETTED, 0.2f);
        tasks.add(new Play(Moves.PETTED, 30));
    }

    void rub() {
        if (petting) energy = Math.min(1, energy + 0.01f);
    }

    void dragStart(float rawX, float rawY) {
        petting = false;
        tasks.clear();
        cur = null;
        held = true;
        air = false;
        grabDX = x - rawX;
        grabDY = y - rawY;
        actor.play(Moves.HELD, 0.12f);
        if (rnd.nextFloat() < 0.6f) say("pickup", null, 0.9f);
    }

    void drag(float rawX, float rawY) {
        if (!held) return;
        Stage s = stage();
        float nx = rawX + grabDX, ny = rawY + grabDY;
        if (Math.abs(nx - x) > 1) facing = nx > x ? 1 : -1;
        x = Math.max(margin() * 0.5f, Math.min(s.w - margin() * 0.5f, nx));
        y = Math.max(170 * s.k, Math.min(ground(), ny));
    }

    void release(float fvx, float fvy) {
        if (petting && !held) {
            petting = false;
            tasks.clear();
            cur = null;
            tasks.add(new Play(Moves.CHEER, 0).saying("pet", 1));
            return;
        }
        petting = false;
        if (!held) return;
        held = false;
        Stage s = stage();
        vx = clamp(fvx, -4200, 4200);
        vy = clamp(fvy, -5200, 4200);
        float speed = (float) Math.hypot(vx, vy);
        if (y >= ground() - 2 && speed < 300) {
            air = false;
            y = ground();
            tasks.add(new Play(Moves.LAND, 0));
            return;
        }
        air = true;
        airTime = 0;
        passFloor = false;
        if (speed > 1300 * s.k) {
            spinRate = clamp(vx / s.k * 0.35f, -900, 900);
            if (Math.abs(spinRate) < 240) spinRate = 240 * (vx >= 0 ? 1 : -1);
            actor.spin = 0;
            actor.play(Moves.TUMBLE, 0.08f);
            if (rnd.nextFloat() < 0.6f) say("throw", null, 0.9f);
        } else {
            actor.play(Moves.AIR, 0.12f);
        }
    }

    // ---- planning ----

    private void interrupt() {
        tasks.clear();
        cur = null;
        petting = false;
    }

    private boolean night() {
        if (!host.settings().nightSleep()) return false;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 23 || h < 7;
    }

    private boolean day() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 8 && h < 18;
    }

    private void plan() {
        Stage s = stage();
        if (room != null) {
            planHome();
            return;
        }
        if (ghost) {
            float edge = x < s.w / 2 ? margin() : s.w - margin();
            if (Math.abs(x - edge) > 4 * s.k) tasks.add(new WalkTo(edge, false));
            tasks.add(new Play(rnd.nextFloat() < 0.5f ? Moves.SIT_DOWN : Moves.SLEEP, 12 + rnd.nextFloat() * 10));
            return;
        }
        if (job != null) {
            planJob();
            return;
        }
        if (night() && rnd.nextFloat() < 0.8f) {
            if (!asleep() && rnd.nextFloat() < 0.4f) tasks.add(new Play(Moves.YAWN, 0).saying("night", 0.5f));
            tasks.add(new Play(Moves.SLEEP, 60 + rnd.nextFloat() * 120));
            return;
        }
        if (energy < 0.2f) {
            tasks.add(new Play(Moves.YAWN, 0).saying("sleepy", 0.7f));
            tasks.add(new Play(Moves.SLEEP, 40 + rnd.nextFloat() * 60));
            return;
        }
        float tired = 1 - energy;
        float[] w = {
                28,                        // 0 wander
                16,                        // 1 stand about
                10,                        // 2 sit
                energy > 0.4f ? 7 : 1,     // 3 train
                energy > 0.5f ? 4 : 0,     // 4 special move
                day() ? 5 : 0,             // 5 bask
                4,                         // 6 dance
                3 + 6 * tired,             // 7 stretch / yawn
                3,                         // 8 think
                tired > 0.5f ? 6 : 1,      // 9 nap
                canHop() ? 4 : 0,          // 10 hop screens
                energy > 0.5f ? 3 : 0,     // 11 tail chase
        };
        switch (pickWeighted(w)) {
            case 0: {
                float tx = margin() + rnd.nextFloat() * (s.w - 2 * margin());
                boolean run = rnd.nextFloat() < 0.18f;
                tasks.add(new WalkTo(tx, run));
                tasks.add(new Play(Moves.IDLE, 1 + rnd.nextFloat() * 3));
                break;
            }
            case 1: {
                float r = rnd.nextFloat();
                Moves.Move m = r < 0.35f ? Moves.LOOK_AROUND : r < 0.55f ? Moves.SNIFF : Moves.IDLE;
                tasks.add(new Play(m, m.loop ? 3 + rnd.nextFloat() * 4 : 0));
                break;
            }
            case 2:
                tasks.add(new Play(Moves.SIT_DOWN, 6 + rnd.nextFloat() * 10));
                break;
            case 3:
                tasks.add(new Play(rnd.nextFloat() < 0.65f ? Moves.TRAIN : Moves.SQUATS, 6 + rnd.nextFloat() * 4).saying("train", 0.5f));
                break;
            case 4: {
                float r = rnd.nextFloat();
                Moves.Move m = r < 0.45f ? Moves.KNUCKLE : r < 0.8f ? Moves.FLAME_SHOT : Moves.PROMINENCE;
                String name = m == Moves.KNUCKLE ? "Corona Knuckle!" : m == Moves.FLAME_SHOT ? "Corona Flame!" : "Petit Prominence!";
                tasks.add(new Play(m, 0).shouting(name, m == Moves.KNUCKLE ? 1.2f : 1.3f));
                break;
            }
            case 5:
                tasks.add(new Play(Moves.BASK, 10 + rnd.nextFloat() * 10).saying("bask", 0.4f));
                break;
            case 6:
                tasks.add(new Play(Moves.DANCE, 6 + rnd.nextFloat() * 4).saying("dance", 0.4f));
                break;
            case 7:
                tasks.add(new Play(rnd.nextFloat() < 0.4f + tired * 0.4f ? Moves.YAWN : Moves.STRETCH, 0));
                break;
            case 8:
                tasks.add(new Play(Moves.THINK, 4 + rnd.nextFloat() * 3).saying("think", 0.5f));
                break;
            case 9:
                tasks.add(new Play(Moves.YAWN, 0));
                tasks.add(new Play(Moves.SLEEP, 25 + rnd.nextFloat() * 35));
                break;
            case 10:
                tasks.add(new Hop().saying("hop", 0.5f));
                break;
            case 11:
                for (int i = 0; i < 4; i++) {
                    float d = (90 + rnd.nextFloat() * 60) * s.k * (i % 2 == 0 ? 1 : -1) * (x > s.w / 2 ? -1 : 1);
                    tasks.add(new WalkTo(x + d, true).lookingBack());
                }
                tasks.add(new Play(Moves.WOOZY, 0));
                break;
        }
    }

    private boolean canHop() {
        int o = otherStage();
        if (o < 0 || !host.settings().screenPref().equals("auto")) return false;
        Stage t = host.stage(o);
        return t.on && !t.game;
    }

    // ---- at home ----

    /** Into his room: he walks in from the right. The service shows the room, or a house button. */
    void goHome(Stage r) {
        if (ghost) {
            ghost = false;
            host.ghostChanged(false);
        }
        interrupt();
        held = air = hopping = passFloor = false;
        vx = vy = 0;
        actor.spin = 0;
        room = r;
        seat = Room.NONE;
        seatBlend = seatGoal = 0;
        x = r.w + 90 * r.k;
        y = ground();
        facing = -1;
        tasks.add(new WalkTo(r.w * (0.5f + rnd.nextFloat() * 0.2f), false).saying("home_enter", 0.7f));
    }

    /** Back out onto a screen. */
    void leaveHome(int stageIndex) {
        if (room == null) return;
        room = null;
        seat = Room.NONE;
        seatBlend = seatGoal = 0;
        cheer = cheerFor = 0;
        interrupt();
        actor.play(Moves.IDLE, 0.2f);
        Stage s = host.stage(stageIndex);
        place(stageIndex, s.w * (0.3f + rnd.nextFloat() * 0.4f));
        tasks.add(new Play(Moves.WAVE, 0).saying("home_leave", 0.8f));
    }

    /** You tapped his bed, chair or bean bag: he goes and uses it. */
    void visit(int which) {
        if (room == null || held) return;
        interrupt();
        if (which == Room.BED) sitAt(Room.BED, Moves.LIE, 30 + rnd.nextFloat() * 30, "home_bed");
        else if (which == Room.CHAIR) sitAt(Room.CHAIR, Moves.GAME, 40 + rnd.nextFloat() * 40, "home_game");
        else if (which == Room.BEANBAG) sitAt(Room.BEANBAG, Moves.WATCH, 60 + rnd.nextFloat() * 60, "home_tv");
    }

    private void planHome() {
        Stage s = room;
        if (job != null) {
            monitor = Room.SCREEN_CODE;
            sitAt(Room.CHAIR, Moves.GAME, 20 + rnd.nextFloat() * 20, null);
            return;
        }
        if ((night() && rnd.nextFloat() < 0.85f) || energy < 0.2f) {
            sitAt(Room.BED, Moves.LIE, 90 + rnd.nextFloat() * 120, seat == Room.BED ? null : "home_bed");
            return;
        }
        boolean tv = host.tvReady();
        float tired = 1 - energy;
        float[] w = {
                tv ? 30 : 5,                   // 0 TV from the bean bag
                energy > 0.3f ? 20 : 6,        // 1 games on his computer
                6,                             // 2 coding on his computer
                tired > 0.5f ? 16 : 3,         // 3 nap
                12,                            // 4 wander
                day() ? 5 : 3,                 // 5 look out of the window
                3 + 4 * tired,                 // 6 stretch / yawn
                energy > 0.5f ? 4 : 0,         // 7 dance
                energy > 0.4f ? 4 : 0,         // 8 train
                3,                             // 9 think
        };
        switch (pickWeighted(w)) {
            case 0:
                sitAt(Room.BEANBAG, Moves.WATCH, 60 + rnd.nextFloat() * 120, seat == Room.BEANBAG ? null : "home_tv");
                break;
            case 1:
                monitor = Room.SCREEN_GAME;
                sitAt(Room.CHAIR, Moves.GAME, 40 + rnd.nextFloat() * 80, seat == Room.CHAIR ? null : "home_game");
                break;
            case 2:
                monitor = Room.SCREEN_CODE;
                sitAt(Room.CHAIR, Moves.GAME, 25 + rnd.nextFloat() * 35, null);
                break;
            case 3:
                if (seat != Room.BED) tasks.add(new Stand());
                if (seat != Room.BED) tasks.add(new Play(Moves.YAWN, 0));
                sitAt(Room.BED, Moves.LIE, 40 + rnd.nextFloat() * 60, null);
                break;
            case 4: {
                float tx = margin() + rnd.nextFloat() * (s.w - 2 * margin());
                tasks.add(new WalkTo(tx, false));
                tasks.add(new Play(Moves.IDLE, 2 + rnd.nextFloat() * 3));
                break;
            }
            case 5:
                tasks.add(new WalkTo(Room.WINDOW_X, false));
                tasks.add(new Play(day() ? Moves.BASK : Moves.LOOK_AROUND, 6 + rnd.nextFloat() * 6).saying(day() ? "bask" : null, 0.3f));
                break;
            case 6:
                tasks.add(new Stand());
                tasks.add(new Play(rnd.nextFloat() < 0.4f + tired * 0.4f ? Moves.YAWN : Moves.STRETCH, 0));
                break;
            case 7:
                tasks.add(new WalkTo(s.w * 0.36f, false));
                tasks.add(new Play(Moves.DANCE, 6 + rnd.nextFloat() * 4).saying("dance", 0.3f));
                break;
            case 8:
                tasks.add(new WalkTo(s.w * 0.36f, false));
                tasks.add(new Play(rnd.nextFloat() < 0.65f ? Moves.TRAIN : Moves.SQUATS, 6 + rnd.nextFloat() * 4));
                break;
            case 9:
                tasks.add(new Stand());
                tasks.add(new Play(Moves.THINK, 4 + rnd.nextFloat() * 3).saying("think", 0.4f));
                break;
        }
    }

    /** Walks over to a seat and settles into it for secs, or just keeps at it if he's already there. */
    private void sitAt(int which, Moves.Move m, float secs, String sayKey) {
        if (seat == which && seatGoal >= 1) {
            tasks.add(new Play(m, secs).saying(sayKey, 0.35f));
            return;
        }
        tasks.add(new WalkTo(Room.seatX(which), false));
        tasks.add(new Sit(which, m).saying(sayKey, 0.35f));
        tasks.add(new Play(m, secs));
    }

    private void standUp() {
        if (seatGoal <= 0) return;
        seatGoal = 0;
        actor.play(Moves.IDLE, 0.35f);
    }

    private void planJob() {
        if ("build".equals(job)) {
            if (rnd.nextFloat() < 0.25f) tasks.add(new Play(Moves.THINK, 3));
            tasks.add(new Play(Moves.BAKE, 10 + rnd.nextFloat() * 8));
        } else {
            tasks.add(new Play(Moves.WORK, 8 + rnd.nextFloat() * 6));
            if (rnd.nextFloat() < 0.3f) tasks.add(new Play(Moves.LOOK_AROUND, 0));
        }
    }

    private int pickWeighted(float[] w) {
        float sum = 0;
        for (float f : w) sum += f;
        float r = rnd.nextFloat() * sum;
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r <= 0) return i;
        }
        return 0;
    }

    private void chatter(float dt) {
        int level = host.settings().chatter();
        if (level <= 0 || ghost || asleep() || held || air || job != null) return;
        chatIn -= dt;
        if (chatIn > 0) return;
        chatIn = level >= 2 ? 40 + rnd.nextFloat() * 80 : 100 + rnd.nextFloat() * 160;
        // At home you're watching or playing something on top: he talks half as much.
        if (host.speaking() || (room != null && rnd.nextFloat() < 0.5f)) return;
        String key = night() ? "night" : rnd.nextFloat() < 0.55f ? "idle" : "quote";
        say(key, null, 1);
    }

    private void checkGames() {
        if (host.homeCheck()) return;
        Stage s = stage();
        Settings set = host.settings();
        boolean game = s.game && !"stay".equals(set.games());
        boolean hopPending = cur instanceof Hop || hopping;
        for (Task t : tasks) hopPending |= t instanceof Hop;
        boolean moves = "move".equals(set.games()) || "home".equals(set.games());
        if (game && moves && canHop() && !held && !air && !hopPending) {
            if (ghost) {
                ghost = false;
                host.ghostChanged(false);
            }
            interrupt();
            Map<String, String> v = vars(s.topLabel, null, null);
            tasks.add(new Hop().saying("game_open", v, 1));
            return;
        }
        boolean wantGhost = game && !hopPending && !air;
        if (wantGhost != ghost) {
            ghost = wantGhost;
            host.ghostChanged(ghost);
            if (ghost) {
                interrupt();
                tasks.add(new WalkTo(x < s.w / 2 ? margin() : s.w - margin(), false));
                tasks.add(new Play(Moves.SIT_DOWN, 10));
            }
        }
    }

    // ---- events from the service ----

    void greet() {
        interrupt();
        tasks.add(new Play(Moves.WAVE, 0).saying(night() ? "night" : "greet", 1));
    }

    void startJob(String kind, String app, Map<String, String> v) {
        job = kind;
        jobApp = app;
        jobSince = System.currentTimeMillis();
        if (held || air) return;
        interrupt();
        lastReaction = jobSince;
        if ("build".equals(kind)) {
            tasks.add(new Play(Moves.SURPRISED, 0));
            tasks.add(new Play(Moves.BAKE, 12).saying("build_start", v, 1));
        } else {
            tasks.add(new Play(Moves.WORK, 10).saying("install_start", v, 1));
        }
    }

    void endJob() {
        job = null;
        jobApp = null;
    }

    void finishJob(String kind, boolean ok, Map<String, String> v, boolean isNew, boolean self) {
        endJob();
        if (held || air) return;
        interrupt();
        lastReaction = System.currentTimeMillis();
        if (ok) {
            if ("build".equals(kind)) {
                tasks.add(new Play(Moves.CELEBRATE, 0).saying("build_ok", v, 1));
            } else if (self) {
                tasks.add(new Play(Moves.PROMINENCE, 0).saying("self_update", v, 1));
            } else {
                tasks.add(new Play(Moves.KNUCKLE, 0).shouting("Corona Knuckle!", 1));
                tasks.add(new Play(Moves.CELEBRATE, 0).saying(isNew ? "new_app" : "install_ok", v, 1));
            }
        } else {
            tasks.add(new Play(Moves.OOPS, 0).saying("build".equals(kind) ? "build_fail" : "install_fail", v, 1));
            tasks.add(new Play(Moves.SAD, 4));
        }
    }

    void crashed(String label, boolean anr) {
        if (held || air) return;
        interrupt();
        lastReaction = System.currentTimeMillis();
        Map<String, String> v = vars(label, null, null);
        tasks.add(new Play(Moves.SURPRISED, 0));
        tasks.add(new Play(Moves.OOPS, 0).saying(anr ? "anr" : "crash", v, 1));
    }

    /** News about bugs and GitHub, queued behind a reaction that's still playing instead of cutting it off. */
    void news(String key, Map<String, String> v, boolean happy) {
        if (held || air || ghost) return;
        long now = System.currentTimeMillis();
        if (!host.speaking() && now - lastReaction > 8000) interrupt();
        lastReaction = now;
        tasks.add(new Play(happy ? Moves.CHEER : Moves.THINK, happy ? 0 : 3).saying(key, v, 1));
    }

    void updateFound(String label, String version, boolean canInstall, Prompt prompt) {
        if (held || air) return;
        interrupt();
        Map<String, String> v = vars(label, version, null);
        if (canInstall) tasks.add(new Play(Moves.WAVE, 0).asking("update_found", v, new String[]{"Update", "Later"}, prompt));
        else tasks.add(new Play(Moves.THINK, 4).saying("update_notify", v, 1));
    }

    void appOpened(String pkg, String label) {
        if (asleep() || ghost || held || air || job != null) return;
        Long last = lastSaid.get(pkg);
        long now = System.currentTimeMillis();
        if (last != null && now - last < 15 * 60_000L) return;
        lastSaid.put(pkg, now);
        interrupt();
        tasks.add(new Play(Moves.CHEER, 0).saying("app_open", vars(label, null, null), 1));
    }

    /** Plays a move by name for secs (loops) or once. */
    boolean act(String name, float secs) {
        Moves.Move m = Moves.get(name);
        if (name.equals("hop")) {
            if (otherStage() < 0 || room != null) return false;
            interrupt();
            tasks.add(new Hop());
            return true;
        }
        if (m == null || held || air) return false;
        interrupt();
        tasks.add(new Play(m, secs > 0 ? secs : m.loop ? 6 : 0));
        return true;
    }

    void sayNow(Lines.Line line) {
        host.say(line, null, null);
    }

    void moveTo(int stageIndex) {
        if (stageIndex == st) return;
        interrupt();
        tasks.add(new Hop());
    }

    // ---- speech ----

    static Map<String, String> vars(String app, String version, String msg) {
        Map<String, String> v = new HashMap<>();
        if (app != null) v.put("app", app);
        if (version != null) v.put("version", version);
        if (msg != null) v.put("msg", msg);
        return v;
    }

    private void say(String key, Map<String, String> v, float chance) {
        if (key == null || rnd.nextFloat() > chance) return;
        Lines.Line l = host.lines().pick(key, v);
        if (l != null) host.say(l, null, null);
    }

    private static float clamp(float v, float a, float b) {
        return Math.max(a, Math.min(b, v));
    }

    // ---- tasks ----

    abstract static class Task {
        boolean started;
        float t;
        String sayKey, shout;
        Map<String, String> sayVars;
        float sayChance = 1;
        String[] buttons;
        Prompt prompt;

        Task saying(String key, float chance) {
            sayKey = key;
            sayChance = chance;
            return this;
        }

        Task saying(String key, Map<String, String> v, float chance) {
            sayKey = key;
            sayVars = v;
            sayChance = chance;
            return this;
        }

        Task shouting(String text, float chance) {
            shout = text;
            sayChance = chance;
            return this;
        }

        Task asking(String key, Map<String, String> v, String[] buttons, Prompt prompt) {
            sayKey = key;
            sayVars = v;
            this.buttons = buttons;
            this.prompt = prompt;
            return this;
        }

        void start(Brain b) {
            if (shout != null && b.rnd.nextFloat() <= sayChance) {
                b.host.say(new Lines.Line("move", shout), null, null);
            } else if (sayKey != null && b.rnd.nextFloat() <= sayChance) {
                Lines.Line l = b.host.lines().pick(sayKey, sayVars);
                if (l != null) b.host.say(l, buttons, prompt);
            }
        }

        abstract boolean step(Brain b, float dt);
    }

    static final class Play extends Task {
        final Moves.Move move;
        final float secs;

        Play(Moves.Move move, float secs) {
            this.move = move;
            this.secs = secs;
        }

        @Override
        void start(Brain b) {
            if (b.actor.move == move && !move.loop) b.actor.replay(move);
            else b.actor.play(move, move == Moves.SLEEP || move == Moves.SIT_DOWN ? 0.6f : 0.25f);
            super.start(b);
        }

        @Override
        boolean step(Brain b, float dt) {
            if (move.loop) return t >= secs;
            return b.actor.finished() || b.actor.move != move || (secs > 0 && t >= secs);
        }
    }

    static final class WalkTo extends Task {
        final float tx;
        final boolean run;
        boolean back;

        WalkTo(float tx, boolean run) {
            this.tx = tx;
            this.run = run;
        }

        WalkTo lookingBack() {
            back = true;
            return this;
        }

        private boolean walking;

        @Override
        boolean step(Brain b, float dt) {
            if (b.seat != Room.NONE) {
                b.standUp();
                t = 0;
                return false;
            }
            if (!walking) {
                walking = true;
                b.actor.play(run ? Moves.RUN : Moves.WALK, 0.2f);
            }
            Stage s = b.stage();
            float target = Math.max(b.margin(), Math.min(s.w - b.margin(), tx));
            float d = target - b.x;
            if (Math.abs(d) < 3 || t > 30) return true;
            b.facing = d > 0 ? 1 : -1;
            float speed = (run ? RUN : WALK) * s.k * Math.min(1, t * 4 + 0.2f);
            float stepPx = Math.min(Math.abs(d), speed * dt);
            b.x += Math.signum(d) * stepPx;
            b.actor.stride += stepPx / s.k;
            if (back) b.lookBack = true;
            return false;
        }
    }

    /** Into a seat at home: a little hop in while the move fades in, ending up facing the room. */
    final class Sit extends Task {
        final int which;
        final Moves.Move move;

        Sit(int which, Moves.Move move) {
            this.which = which;
            this.move = move;
        }

        @Override
        void start(Brain b) {
            seat = which;
            seatGoal = 1;
            facing = Room.seatFacing(which);
            actor.play(move, 0.35f);
            super.start(b);
        }

        @Override
        boolean step(Brain b, float dt) {
            return seatBlend >= 1;
        }
    }

    /** Out of whatever seat he's in. */
    final class Stand extends Task {
        @Override
        void start(Brain b) {
            standUp();
            super.start(b);
        }

        @Override
        boolean step(Brain b, float dt) {
            return seat == Room.NONE;
        }
    }

    final class Hop extends Task {
        private int phase;

        @Override
        void start(Brain b) {
            b.actor.play(Moves.CROUCH, 0.1f);
            super.start(b);
        }

        @Override
        boolean step(Brain b, float dt) {
            if (phase == 0 && t >= 0.45f) {
                phase = 1;
                Stage s = stage();
                int o = otherStage();
                if (o < 0) return true;
                boolean down = s.displayId == Screens.TOP_ID;
                air = true;
                hopping = true;
                airTime = 0;
                vx = (s.w / 2 - x) * 0.25f;
                if (down) {
                    vy = -1100 * s.k;
                    passFloor = true;
                } else {
                    vy = -(float) Math.sqrt(2 * GRAVITY * s.k * (y + 40 * s.k)) * 1.28f;
                    passFloor = false;
                }
                actor.play(Moves.AIR, 0.1f);
                return true;
            }
            return phase > 0;
        }
    }
}
