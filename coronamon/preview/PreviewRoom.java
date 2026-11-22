package dev.grokbuild.coronamon;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Renders Coronamon's room. Usage: Preview room OUT.png [scene] [hour] [blend]
 * scene: walk, bed, chair, cheer, bag, all (a contact sheet of them by day and night).
 */
final class PreviewRoom {
    static void render(String out, String scene, float hour, float blend) throws Exception {
        if (scene.equals("all")) {
            String[] scenes = {"walk", "chair", "cheer", "bag", "bed", "walk"};
            float[] hours = {11, 15, 15, 19.2f, 23.5f, 22};
            int cw = 620, ch = 540;
            BufferedImage sheet = new BufferedImage(cw * 2, ch * 3, BufferedImage.TYPE_INT_RGB);
            Graphics2D sg = sheet.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (int i = 0; i < scenes.length; i++) {
                BufferedImage one = frame(scenes[i], hours[i], 1, i * 3.7f + 2);
                sg.drawImage(one.getScaledInstance(cw, ch, Image.SCALE_AREA_AVERAGING), (i % 2) * cw, (i / 2) * ch, null);
                sg.setColor(new Color(255, 255, 255, 220));
                sg.setFont(new Font("SansSerif", Font.BOLD, 18));
                sg.drawString(scenes[i] + " " + hours[i] + "h", (i % 2) * cw + 12, (i / 2) * ch + 26);
            }
            sg.dispose();
            ImageIO.write(sheet, "png", new File(out));
        } else {
            ImageIO.write(frame(scene, hour, blend, 3), "png", new File(out));
        }
        System.out.println(out);
    }

    static BufferedImage frame(String scene, float hour, float blend, float t) {
        int ss = 2;
        int w = (int) Room.W, h = (int) Room.H;
        BufferedImage big = new BufferedImage(w * ss, h * ss, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = big.createGraphics();
        g.scale(ss, ss);
        Room room = new Room();
        room.drawBack(new AwtGfx(g), hour);

        Room.Scene s = new Room.Scene();
        s.t = t;
        s.hour = hour;
        s.blend = blend;
        Actor a = new Actor();
        Moves.Move m = Moves.WALK;
        switch (scene) {
            case "bed":
                s.seat = Room.BED;
                m = Moves.LIE;
                s.monitor = Room.SCREEN_OFF;
                break;
            case "chair":
            case "cheer":
                s.seat = Room.CHAIR;
                m = Moves.GAME;
                s.monitor = Room.SCREEN_GAME;
                s.cheer = scene.equals("cheer") ? 1 : 0;
                break;
            case "bag":
                s.seat = Room.BEANBAG;
                m = Moves.WATCH;
                s.tvOn = true;
                break;
            default:
                s.x = 420;
        }
        if (s.seat != Room.NONE) s.x = Room.seatX(s.seat);
        a.play(m, 0);
        for (int i = 0; i < 40; i++) {
            a.stride += 2;
            a.tick(t / 40);
        }
        s.pose = a.pose;
        s.clock = a.clock;
        Room.seatPose(s.pose, s.seat, s.blend);
        if (s.tvOn) {
            g.setPaint(new GradientPaint(Room.TV_L, Room.TV_T, new Color(0x2B5FA8), Room.TV_R, Room.TV_B, new Color(0xE88A4A)));
            g.fillRect((int) Room.TV_L, (int) Room.TV_T, (int) (Room.TV_R - Room.TV_L), (int) (Room.TV_B - Room.TV_T));
            g.setColor(new Color(0x1C2A1C));
            g.fillOval((int) Room.TV_L + 40, (int) Room.TV_B - 70, 200, 110);
            g.setColor(new Color(255, 255, 255, 200));
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.drawString("Frieren · Episode 7", (int) Room.TV_L + 10, (int) Room.TV_B - 10);
        }
        room.drawFront(new AwtGfx(g), new Puppet(), s);
        g.dispose();
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = small.createGraphics();
        sg.drawImage(big.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        sg.dispose();
        return small;
    }
}
