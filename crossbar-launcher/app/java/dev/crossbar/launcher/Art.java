package dev.crossbar.launcher;

import android.graphics.Bitmap;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Pulls the icon, background art and title that ship inside ROM images. */
final class Art {
    private Art() {}

    static void extract(String platform, File f, File out, JSONObject art, JSONObject meta) throws IOException {
        String ext = Util.ext(f.getName());
        switch (platform) {
            case "psp":
                if (ext.equals("iso")) {
                    try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                        pspIso(new PlainSource(raf), out, art, meta);
                    }
                } else if (ext.equals("cso")) {
                    try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                        pspIso(new CsoSource(raf), out, art, meta);
                    }
                } else if (ext.equals("pbp")) {
                    pbp(f, out, art, meta);
                }
                break;
            case "nds":
                nds(f, ext, out, art, meta);
                break;
            case "n3ds":
                if (ext.equals("3ds") || ext.equals("cci") || ext.equals("cxi")) n3ds(f, out, art, meta);
                break;
            default:
                break;
        }
    }

    interface Source {
        void read(long offset, byte[] buf, int off, int len) throws IOException;
    }

    static final class PlainSource implements Source {
        final RandomAccessFile raf;

        PlainSource(RandomAccessFile raf) {
            this.raf = raf;
        }

        @Override
        public void read(long offset, byte[] buf, int off, int len) throws IOException {
            raf.seek(offset);
            raf.readFully(buf, off, len);
        }
    }

    /** Compressed ISO (CISO v1): deflate-compressed blocks with an offset index. */
    static final class CsoSource implements Source {
        final RandomAccessFile raf;
        final int blockSize, align;
        final long[] index;
        int cachedBlock = -1;
        byte[] cached;

        CsoSource(RandomAccessFile raf) throws IOException {
            this.raf = raf;
            byte[] h = new byte[24];
            raf.readFully(h);
            if (h[0] != 'C' || h[1] != 'I' || h[2] != 'S' || h[3] != 'O') throw new IOException("not a CSO");
            long total = Util.le64(h, 8);
            blockSize = (int) Util.le32(h, 16);
            align = h[21] & 0xff;
            if (blockSize <= 0 || blockSize > 1 << 20) throw new IOException("bad CSO block size");
            int n = (int) ((total + blockSize - 1) / blockSize);
            byte[] idx = new byte[(n + 1) * 4];
            raf.seek(24);
            raf.readFully(idx);
            index = new long[n + 1];
            for (int i = 0; i <= n; i++) index[i] = Util.le32(idx, i * 4);
        }

        byte[] block(int b) throws IOException {
            if (b == cachedBlock) return cached;
            long e0 = index[b], e1 = index[b + 1];
            boolean plain = (e0 & 0x80000000L) != 0;
            long start = (e0 & 0x7fffffffL) << align;
            long end = (e1 & 0x7fffffffL) << align;
            int len = (int) Math.max(0, Math.min(end - start, blockSize * 2L));
            byte[] raw = new byte[len];
            raf.seek(start);
            raf.readFully(raw);
            byte[] outBuf;
            if (plain) {
                outBuf = Arrays.copyOf(raw, blockSize);
            } else {
                Inflater inf = new Inflater(true);
                inf.setInput(raw);
                outBuf = new byte[blockSize];
                try {
                    inf.inflate(outBuf);
                } catch (DataFormatException e) {
                    throw new IOException(e);
                } finally {
                    inf.end();
                }
            }
            cachedBlock = b;
            cached = outBuf;
            return outBuf;
        }

        @Override
        public void read(long offset, byte[] buf, int off, int len) throws IOException {
            while (len > 0) {
                int b = (int) (offset / blockSize);
                int inBlock = (int) (offset % blockSize);
                if (b + 1 >= index.length) throw new IOException("read past end of CSO");
                byte[] data = block(b);
                int n = Math.min(len, blockSize - inBlock);
                System.arraycopy(data, inBlock, buf, off, n);
                offset += n;
                off += n;
                len -= n;
            }
        }
    }

    // ---- PSP (ISO 9660 with /PSP_GAME/ICON0.PNG, PIC1.PNG, PARAM.SFO) ----

    static void pspIso(Source src, File out, JSONObject art, JSONObject meta) throws IOException {
        byte[] pvd = new byte[2048];
        src.read(16L * 2048, pvd, 0, 2048);
        if (pvd[1] != 'C' || pvd[2] != 'D' || pvd[3] != '0' || pvd[4] != '0' || pvd[5] != '1') return;
        long rootLba = Util.le32(pvd, 156 + 2);
        long rootLen = Util.le32(pvd, 156 + 10);
        long[] game = findEntry(src, rootLba, rootLen, "PSP_GAME");
        if (game == null) return;
        String[][] files = {{"ICON0.PNG", "icon0"}, {"PIC1.PNG", "pic1"}, {"PIC0.PNG", "pic0"}, {"PARAM.SFO", null}};
        for (String[] spec : files) {
            long[] e = findEntry(src, game[0], game[1], spec[0]);
            if (e == null || e[1] <= 0 || e[1] > 8_000_000) continue;
            byte[] data = new byte[(int) e[1]];
            src.read(e[0] * 2048, data, 0, data.length);
            if (spec[1] == null) {
                sfo(data, meta);
            } else {
                File f = new File(out, spec[1] + ".png");
                if (Util.writeBytes(f, data)) Util.put(art, spec[1], f.getAbsolutePath());
            }
        }
    }

    private static long[] findEntry(Source src, long lba, long len, String name) throws IOException {
        if (len <= 0 || len > 1 << 20) return null;
        byte[] dir = new byte[(int) len];
        src.read(lba * 2048, dir, 0, dir.length);
        int pos = 0;
        while (pos < dir.length) {
            int rl = dir[pos] & 0xff;
            if (rl == 0) {
                pos = (pos / 2048 + 1) * 2048;
                continue;
            }
            if (pos + 33 > dir.length) break;
            int nl = dir[pos + 32] & 0xff;
            if (pos + 33 + nl > dir.length) break;
            String n = new String(dir, pos + 33, nl, StandardCharsets.ISO_8859_1);
            int semi = n.indexOf(';');
            if (semi >= 0) n = n.substring(0, semi);
            if (n.equalsIgnoreCase(name)) return new long[]{Util.le32(dir, pos + 2), Util.le32(dir, pos + 10)};
            pos += rl;
        }
        return null;
    }

    static void sfo(byte[] d, JSONObject meta) {
        if (d.length < 20 || d[0] != 0 || d[1] != 'P' || d[2] != 'S' || d[3] != 'F') return;
        int keyTable = (int) Util.le32(d, 8);
        int dataTable = (int) Util.le32(d, 12);
        int count = (int) Util.le32(d, 16);
        for (int i = 0; i < count && 20 + i * 16 + 16 <= d.length; i++) {
            int e = 20 + i * 16;
            int keyOff = Util.le16(d, e);
            int len = (int) Util.le32(d, e + 4);
            int dataOff = (int) Util.le32(d, e + 12);
            String key = Util.ascii(d, keyTable + keyOff, Math.min(32, d.length - keyTable - keyOff));
            int start = dataTable + dataOff;
            if (start < 0 || start + len > d.length) continue;
            if (key.equals("TITLE") || key.equals("DISC_ID")) {
                String v = new String(d, start, len, StandardCharsets.UTF_8).replace("\0", "").trim();
                Util.put(meta, key.equals("TITLE") ? "title" : "serial", v);
            }
        }
    }

    /** PSP EBOOT.PBP: header with offsets to PARAM.SFO, ICON0.PNG, ..., PIC1.PNG. */
    static void pbp(File f, File out, JSONObject art, JSONObject meta) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[40];
            raf.readFully(h);
            if (h[0] != 0 || h[1] != 'P' || h[2] != 'B' || h[3] != 'P') return;
            long[] off = new long[9];
            for (int i = 0; i < 8; i++) off[i] = Util.le32(h, 8 + i * 4);
            off[8] = raf.length();
            int[][] want = {{0, -1}, {1, 0}, {4, 1}, {3, 2}};
            String[] names = {"icon0", "pic1", "pic0"};
            for (int[] w : want) {
                long start = off[w[0]], len = off[w[0] + 1] - start;
                if (len <= 0 || len > 8_000_000) continue;
                byte[] data = new byte[(int) len];
                raf.seek(start);
                raf.readFully(data);
                if (w[1] < 0) {
                    sfo(data, meta);
                } else {
                    File o = new File(out, names[w[1]] + ".png");
                    if (Util.writeBytes(o, data)) Util.put(art, names[w[1]], o.getAbsolutePath());
                }
            }
        }
    }

    // ---- Nintendo DS banner (32x32 4bpp tiled icon + titles) ----

    static void nds(File f, String ext, File out, JSONObject art, JSONObject meta) throws IOException {
        ZipFile zf = null;
        InputStream in;
        if (ext.equals("zip")) {
            zf = new ZipFile(f);
            ZipEntry entry = null;
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".nds") || n.endsWith(".dsi") || n.endsWith(".srl")) {
                    entry = e;
                    break;
                }
            }
            if (entry == null) {
                zf.close();
                return;
            }
            in = new BufferedInputStream(zf.getInputStream(entry), 65536);
        } else if (ext.equals("nds") || ext.equals("dsi")) {
            in = new BufferedInputStream(new FileInputStream(f), 65536);
        } else {
            return;
        }
        try {
            byte[] hdr = new byte[0x200];
            Util.readFully(in, hdr);
            Util.put(meta, "serial", Util.ascii(hdr, 0x0C, 4));
            long bannerOff = Util.le32(hdr, 0x68);
            if (bannerOff < 0x200 || bannerOff > 0x20000000L) return;
            Util.skipFully(in, bannerOff - 0x200);
            byte[] b = new byte[0x840];
            Util.readFully(in, b);
            int[] pal = new int[16];
            for (int i = 1; i < 16; i++) {
                int c = Util.le16(b, 0x220 + i * 2);
                int r = (c & 0x1f) << 3, g = ((c >> 5) & 0x1f) << 3, bl = ((c >> 10) & 0x1f) << 3;
                pal[i] = 0xff000000 | (r | r >> 5) << 16 | (g | g >> 5) << 8 | (bl | bl >> 5);
            }
            int[] px = new int[32 * 32];
            for (int tile = 0; tile < 16; tile++) {
                int tx = (tile % 4) * 8, ty = (tile / 4) * 8;
                for (int i = 0; i < 32; i++) {
                    int v = b[0x20 + tile * 32 + i] & 0xff;
                    int x = (i % 4) * 2, y = i / 4;
                    px[(ty + y) * 32 + tx + x] = pal[v & 0xf];
                    px[(ty + y) * 32 + tx + x + 1] = pal[v >> 4];
                }
            }
            Bitmap small = Bitmap.createBitmap(px, 32, 32, Bitmap.Config.ARGB_8888);
            Bitmap big = Bitmap.createScaledBitmap(small, 128, 128, false);
            File o = new File(out, "dsicon.png");
            if (Util.writeBytes(o, Util.png(big))) Util.put(art, "pixelicon", o.getAbsolutePath());
            String en = Util.utf16(b, 0x340, 0x100);
            String[] lines = en.split("\n");
            int titleLines = lines.length >= 3 ? lines.length - 1 : 1;
            StringBuilder title = new StringBuilder();
            for (int i = 0; i < titleLines && i < lines.length; i++) {
                if (title.length() > 0) title.append(' ');
                title.append(lines[i].trim());
            }
            if (title.length() > 0) Util.put(meta, "title", title.toString());
            if (lines.length > 1) Util.put(meta, "publisher", lines[lines.length - 1].trim());
        } finally {
            in.close();
            if (zf != null) zf.close();
        }
    }

    // ---- Nintendo 3DS (NCSD -> NCCH -> ExeFS "icon" -> SMDH) ----

    static void n3ds(File f, File out, JSONObject art, JSONObject meta) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[0x200];
            raf.readFully(h);
            long ncch;
            if (magic(h, 0x100, "NCSD")) ncch = Util.le32(h, 0x120) * 0x200L;
            else if (magic(h, 0x100, "NCCH")) ncch = 0;
            else return;
            byte[] n = new byte[0x200];
            raf.seek(ncch);
            raf.readFully(n);
            if (!magic(n, 0x100, "NCCH")) return;
            Util.put(meta, "serial", Util.ascii(n, 0x150, 16));
            boolean noCrypto = (n[0x188 + 7] & 0x4) != 0;
            if (!noCrypto) {
                Util.put(meta, "encrypted", true);
                return;
            }
            long exefs = ncch + Util.le32(n, 0x1A0) * 0x200L;
            if (Util.le32(n, 0x1A4) == 0) return;
            byte[] eh = new byte[0x200];
            raf.seek(exefs);
            raf.readFully(eh);
            for (int i = 0; i < 10; i++) {
                if (!"icon".equals(Util.ascii(eh, i * 16, 8))) continue;
                long off = Util.le32(eh, i * 16 + 8), size = Util.le32(eh, i * 16 + 12);
                if (size < 0x36C0 || size > 0x10000) return;
                byte[] s = new byte[(int) size];
                raf.seek(exefs + 0x200 + off);
                raf.readFully(s);
                if (!magic(s, 0, "SMDH")) return;
                int t = 0x8 + 0x200; // English titles
                Util.put(meta, "title", Util.utf16(s, t, 0x80));
                Util.put(meta, "longTitle", Util.utf16(s, t + 0x80, 0x100).replace('\n', ' '));
                Util.put(meta, "publisher", Util.utf16(s, t + 0x180, 0x80));
                int[] px = new int[48 * 48];
                int p = 0x24C0;
                for (int ty = 0; ty < 48; ty += 8) {
                    for (int tx = 0; tx < 48; tx += 8) {
                        for (int k = 0; k < 64; k++) {
                            int x = (k & 1) | ((k >> 1) & 2) | ((k >> 2) & 4);
                            int y = ((k >> 1) & 1) | ((k >> 2) & 2) | ((k >> 3) & 4);
                            int c = Util.le16(s, p);
                            p += 2;
                            int r = (c >> 11) & 0x1f, g = (c >> 5) & 0x3f, b = c & 0x1f;
                            px[(ty + y) * 48 + tx + x] = 0xff000000 | (r << 3 | r >> 2) << 16 | (g << 2 | g >> 4) << 8 | (b << 3 | b >> 2);
                        }
                    }
                }
                Bitmap small = Bitmap.createBitmap(px, 48, 48, Bitmap.Config.ARGB_8888);
                Bitmap big = Bitmap.createScaledBitmap(small, 144, 144, true);
                File o = new File(out, "3dsicon.png");
                if (Util.writeBytes(o, Util.png(big))) Util.put(art, "sqicon", o.getAbsolutePath());
                return;
            }
        }
    }

    private static boolean magic(byte[] b, int o, String m) {
        for (int i = 0; i < m.length(); i++) if (b[o + i] != m.charAt(i)) return false;
        return true;
    }
}
