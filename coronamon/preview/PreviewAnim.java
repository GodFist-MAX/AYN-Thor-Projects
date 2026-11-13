package dev.grokbuild.coronamon;

import java.util.LinkedHashMap;
import java.util.Map;

final class PreviewAnim {
    /** Frames of one move, evenly spaced over secs. */
    static void strip(String out, String action, float secs, int frames) throws Exception {
        Map<String, Pose> m = new LinkedHashMap<>();
        Actor a = new Actor();
        Moves.Move mv = Moves.get(action);
        if (mv == null) throw new IllegalArgumentException("no move " + action + "; have " + Moves.ALL.keySet());
        a.play(mv, 0);
        float dt = 1 / 60f, step = secs / frames, next = 0;
        int n = 0;
        for (float t = 0; n < frames; t += dt) {
            if (mv == Moves.WALK || mv == Moves.RUN) a.stride += (mv == Moves.RUN ? 260 : 90) * dt;
            a.tick(dt);
            if (t >= next) {
                m.put(String.format("%s %.2fs", action, t), new Pose().set(a.pose));
                next += step;
                n++;
            }
        }
        Preview.render(m, out, 240, Math.min(frames, 6), 0.4f);
    }

    /** Prints how far each move reaches from his feet, in puppet units, over its whole run. */
    static void bounds() {
        int size = 800, ox = 400, oy = 560;
        int[] all = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Moves.Move mv : Moves.ALL.values()) {
            Actor a = new Actor();
            a.play(mv, 0);
            float dur = mv.loop ? 4f : mv.dur;
            int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            int frame = 0;
            for (float t = 0; t < dur; t += 1 / 60f, frame++) {
                if (mv == Moves.WALK || mv == Moves.RUN) a.stride += 120 / 60f;
                if (mv == Moves.TUMBLE) a.spin += 6;
                a.tick(1 / 60f);
                if (frame % 4 != 0) continue;
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = img.createGraphics();
                g.translate(ox, oy);
                new Puppet().draw(new AwtGfx(g), a.pose, a.clock);
                g.dispose();
                for (int y = 0; y < size; y++)
                    for (int x = 0; x < size; x++)
                        if ((img.getRGB(x, y) >>> 24) > 24) {
                            b[0] = Math.min(b[0], x - ox);
                            b[1] = Math.min(b[1], y - oy);
                            b[2] = Math.max(b[2], x - ox);
                            b[3] = Math.max(b[3], y - oy);
                        }
            }
            System.out.printf("%-11s x %4d..%4d  y %4d..%4d%n", mv.name, b[0], b[2], b[1], b[3]);
            for (int i = 0; i < 2; i++) all[i] = Math.min(all[i], b[i]);
            for (int i = 2; i < 4; i++) all[i] = Math.max(all[i], b[i]);
        }
        System.out.printf("ALL         x %4d..%4d  y %4d..%4d%n", all[0], all[2], all[1], all[3]);
    }

    /** One frame from every move (or the comma-separated names in only) at its most telling moment. */
    static void all(String out, String only, int cell) throws Exception {
        Map<String, Pose> m = new LinkedHashMap<>();
        java.util.List<String> pick = only == null ? null : java.util.Arrays.asList(only.split(","));
        for (Moves.Move mv : Moves.ALL.values()) {
            if (pick != null && !pick.contains(mv.name)) continue;
            Actor a = new Actor();
            a.play(mv, 0);
            float at = mv.loop ? 1.3f : mv.dur * 0.45f;
            if (mv == Moves.KNUCKLE) at = 1.6f;
            if (mv == Moves.FLAME_SHOT) at = 1.25f;
            if (mv == Moves.PROMINENCE) at = 1.8f;
            for (float t = 0; t < at; t += 1 / 60f) {
                if (mv == Moves.WALK || mv == Moves.RUN) a.stride += 120 / 60f;
                if (mv == Moves.TUMBLE) a.spin += 6;
                a.tick(1 / 60f);
            }
            m.put(mv.name, new Pose().set(a.pose));
        }
        Preview.render(m, out, cell, Math.min(8, m.size()), 0.4f);
    }
}
