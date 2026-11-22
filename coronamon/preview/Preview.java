package dev.grokbuild.coronamon;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Desktop preview of the puppet. Usage:
 *   Preview sheet OUT.png [cellPx]          - contact sheet of sample poses
 *   Preview anim OUT_PREFIX ACTION [secs]   - frames of a behavior (see Actions) for a strip
 */
public class Preview {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "sheet";
        if (mode.equals("moves")) {
            PreviewAnim.all(args[1], args.length > 2 ? args[2] : null, args.length > 3 ? Integer.parseInt(args[3]) : 220);
            return;
        }
        if (mode.equals("strip")) {
            strip(args[1], args[2], args.length > 3 ? Float.parseFloat(args[3]) : 2f, args.length > 4 ? Integer.parseInt(args[4]) : 8);
            return;
        }
        if (mode.equals("icon")) {
            icon(args[1], args[2], Integer.parseInt(args[3]));
            return;
        }
        if (mode.equals("bounds")) {
            PreviewAnim.bounds();
            return;
        }
        if (mode.equals("room")) {
            PreviewRoom.render(args[1], args.length > 2 ? args[2] : "all", args.length > 3 ? Float.parseFloat(args[3]) : 14,
                    args.length > 4 ? Float.parseFloat(args[4]) : 1);
            return;
        }
        if (mode.equals("one")) {
            Map<String, Pose> one = new LinkedHashMap<>();
            one.put(args[2], samples().get(args[2]));
            render(one, args[1], args.length > 3 ? Integer.parseInt(args[3]) : 700, 1, 0.4f);
            return;
        }
        String out = args.length > 1 ? args[1] : "/tmp/coro_sheet.png";
        int cell = args.length > 2 ? Integer.parseInt(args[2]) : 320;
        render(samples(), out, cell, 4, 0.4f);
    }

    static Map<String, Pose> samples() {
        Map<String, Pose> m = new LinkedHashMap<>();
        Pose p;
        m.put("rest", new Pose());
        p = new Pose();
        p.v[Pose.FACING] = -1;
        p.v[Pose.LOOK_X] = 0.6f;
        m.put("left", p);
        p = new Pose();
        p.v[Pose.TURN] = 0;
        m.put("front", p);
        p = new Pose();
        p.v[Pose.MOUTH] = 0.36f;
        p.v[Pose.SMILE] = 1;
        m.put("grin", p);
        p = new Pose();
        p.v[Pose.HAPPY] = 1;
        p.v[Pose.MOUTH] = 0.7f;
        p.v[Pose.BLUSH] = 1;
        p.v[Pose.HEARTS] = 1;
        p.v[Pose.FS] = -108;
        p.v[Pose.FE] = -38;
        p.v[Pose.BS] = 102;
        p.v[Pose.BE] = 24;
        m.put("happy", p);
        p = new Pose();
        p.v[Pose.FS] = 95;
        p.v[Pose.FE] = 0;
        p.v[Pose.BS] = 40;
        p.v[Pose.BE] = 110;
        p.v[Pose.F_GLOW] = 1;
        p.v[Pose.BROW] = 1;
        p.v[Pose.MOUTH] = 0.5f;
        p.v[Pose.TORSO] = 8;
        p.v[Pose.FLX] = -6;
        p.v[Pose.BLX] = 6;
        m.put("punch", p);
        p = new Pose();
        p.v[Pose.SIT] = 1;
        p.v[Pose.EYES] = 0;
        p.v[Pose.ZZZ] = 1;
        p.v[Pose.HEAD] = 8;
        p.v[Pose.FLAME] = 0.5f;
        p.v[Pose.FS] = 20;
        p.v[Pose.FE] = 40;
        p.v[Pose.BS] = 10;
        p.v[Pose.BE] = 40;
        m.put("sleep", p);
        p = new Pose();
        p.v[Pose.FLX] = 9;
        p.v[Pose.FLY] = 4;
        p.v[Pose.BLX] = -9;
        p.v[Pose.FS] = -25;
        p.v[Pose.BS] = 25;
        p.v[Pose.LEAN] = 4;
        m.put("walk", p);
        p = new Pose();
        p.v[Pose.HAT] = 1;
        p.v[Pose.BOWL] = 1;
        p.v[Pose.FS] = 50;
        p.v[Pose.FE] = 70;
        p.v[Pose.BS] = 30;
        p.v[Pose.BE] = 80;
        p.v[Pose.HAPPY] = 1;
        p.v[Pose.MOUTH] = 0.3f;
        p.v[Pose.NOTES] = 1;
        m.put("bake", p);
        p = new Pose();
        p.v[Pose.CHARGE] = 1;
        p.v[Pose.BROW] = 1;
        p.v[Pose.FS] = 20;
        p.v[Pose.FE] = 100;
        p.v[Pose.BS] = 20;
        p.v[Pose.BE] = 100;
        p.v[Pose.HIP] = 4;
        m.put("charge", p);
        p = new Pose();
        p.v[Pose.AURA] = 1;
        p.v[Pose.BROW] = 1;
        p.v[Pose.TORSO] = 14;
        p.v[Pose.FS] = -40;
        p.v[Pose.BS] = -50;
        m.put("prominence", p);
        p = new Pose();
        p.v[Pose.LIFT] = 40;
        p.v[Pose.WIDE] = 1;
        p.v[Pose.MOUTH] = 0.6f;
        p.v[Pose.SMILE] = -0.5f;
        p.v[Pose.EXCLAIM] = 1;
        p.v[Pose.FS] = -112;
        p.v[Pose.FE] = -40;
        p.v[Pose.BS] = 104;
        p.v[Pose.BE] = 26;
        p.v[Pose.F_OPEN] = 1;
        p.v[Pose.B_OPEN] = 1;
        p.v[Pose.FLY] = -6;
        p.v[Pose.BLY] = -8;
        p.v[Pose.SWEAT] = 1;
        m.put("held", p);
        p = new Pose();
        p.v[Pose.BROW] = -1;
        p.v[Pose.SMILE] = -1;
        p.v[Pose.HEAD] = 10;
        p.v[Pose.LOOK_Y] = 0.6f;
        p.v[Pose.FLAME] = 0.4f;
        p.v[Pose.SWEAT] = 1;
        p.v[Pose.TAIL_FLAME] = 0.6f;
        m.put("sad", p);
        return m;
    }

    static void render(Map<String, Pose> poses, String out, int cell, int cols, float t) throws Exception {
        List<String> names = new ArrayList<>(poses.keySet());
        int rows = (names.size() + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(cols * cell, rows * cell, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = sheet.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        for (int i = 0; i < names.size(); i++) {
            BufferedImage img = cellImage(poses.get(names.get(i)), cell, t, i % 2 == 0 ? 0xFF2B3047 : 0xFF3A3550);
            sg.drawImage(img, (i % cols) * cell, (i / cols) * cell, null);
            sg.setColor(new Color(255, 255, 255, 190));
            sg.setFont(new Font("SansSerif", Font.BOLD, Math.max(12, cell / 18)));
            sg.drawString(names.get(i), (i % cols) * cell + 10, (i / cols) * cell + cell / 14 + 8);
        }
        sg.dispose();
        ImageIO.write(sheet, "png", new File(out));
        System.out.println(out);
    }

    static BufferedImage cellImage(Pose pose, int cell, float t, int bg) {
        int ss = 3;
        BufferedImage big = new BufferedImage(cell * ss, cell * ss, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = big.createGraphics();
        g.setColor(new Color(bg, true));
        g.fillRect(0, 0, cell * ss, cell * ss);
        float k = cell * ss / (Puppet.HEIGHT + 10);
        g.translate(cell * ss / 2.0, cell * ss * 0.95);
        g.scale(k, k);
        new Puppet().draw(new AwtGfx(g), pose, t);
        g.dispose();
        BufferedImage small = new BufferedImage(cell, cell, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = small.createGraphics();
        sg.drawImage(big.getScaledInstance(cell, cell, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        sg.dispose();
        return small;
    }

    /** Adaptive launcher icon layers: his head centred in the safe zone, and a night-sky background. */
    static void icon(String fgOut, String bgOut, int size) throws Exception {
        int ss = 3, s = size * ss;
        BufferedImage big = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = big.createGraphics();
        float k = s * (276f / 432f) / 190f;
        g.translate(s / 2.0 - 3 * k, s / 2.0 + 164 * k);
        g.scale(k, k);
        Pose p = new Pose();
        p.v[Pose.SHADOW] = 0;
        p.v[Pose.SMILE] = 1;
        p.v[Pose.MOUTH] = 0.35f;
        p.v[Pose.HEAD] = -5;
        p.v[Pose.TAIL] = -30;
        new Puppet().draw(new AwtGfx(g), p, 0.4f);
        g.dispose();
        ImageIO.write(shrink(big, size), "png", new File(fgOut));

        BufferedImage bg = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D b = bg.createGraphics();
        b.setPaint(new java.awt.RadialGradientPaint(s * 0.5f, s * 0.42f, s * 0.78f, new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(0x4B4FA8), new Color(0x272861), new Color(0x121331)}));
        b.fillRect(0, 0, s, s);
        b.setPaint(new java.awt.RadialGradientPaint(s * 0.5f, s * 0.47f, s * 0.36f, new float[]{0f, 1f},
                new Color[]{new Color(255, 170, 60, 150), new Color(255, 170, 60, 0)}));
        b.fillRect(0, 0, s, s);
        b.dispose();
        ImageIO.write(shrink(bg, size), "png", new File(bgOut));
        System.out.println(fgOut + " " + bgOut);
    }

    static BufferedImage shrink(BufferedImage big, int size) {
        BufferedImage small = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = small.createGraphics();
        sg.drawImage(big.getScaledInstance(size, size, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        sg.dispose();
        return small;
    }

    static void strip(String out, String action, float secs, int frames) throws Exception {
        PreviewAnim.strip(out, action, secs, frames);
    }
}
