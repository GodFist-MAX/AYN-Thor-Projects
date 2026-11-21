package dev.crossbar.launcher;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A video's sound through FFmpeg (libcbaudio.so, app/jni/cbaudio.c). The Thor's MediaExtractor drops
 * E-AC3, DTS and TrueHD tracks from MKV files, and Android has no decoders for them either.
 */
final class FfAudio implements VideoEngine.Audio {
    private static Boolean loaded;

    private long h;

    private FfAudio(long h) {
        this.h = h;
    }

    static synchronized boolean available() {
        if (loaded == null) {
            try {
                System.loadLibrary("cbaudio");
                loaded = true;
            } catch (Throwable t) {
                Log.w(App.TAG, "FFmpeg sound unavailable: " + t);
                loaded = false;
            }
        }
        return loaded;
    }

    /** The file's sound track (a stream index, or -1 for the best one); null if FFmpeg can't play any. */
    static FfAudio open(ParcelFileDescriptor pfd, int stream) {
        if (!available()) return null;
        long h = open(pfd.getFd(), stream);
        return h == 0 ? null : new FfAudio(h);
    }

    @Override
    public synchronized int read(ByteBuffer buf, long[] ptsUs) {
        return h == 0 ? 0 : read(h, buf, ptsUs);
    }

    /** Of what read() returned last; a broadcast recording can change it partway. */
    @Override
    public synchronized int rate() {
        return h == 0 ? 48000 : rate(h);
    }

    @Override
    public synchronized int channels() {
        return h == 0 ? 2 : channels(h);
    }

    @Override
    public synchronized void seek(long us) {
        if (h != 0) seek(h, us);
    }

    @Override
    public synchronized long durationUs() {
        return h == 0 ? 0 : duration(h);
    }

    @Override
    public synchronized List<VideoEngine.Track> tracks() {
        List<VideoEngine.Track> out = new ArrayList<>();
        if (h == 0) return out;
        byte[] b = tracks(h);
        if (b == null) return out;
        for (String line : new String(b, StandardCharsets.UTF_8).split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 6) continue;
            try {
                out.add(new VideoEngine.Track(Integer.parseInt(f[0]), f[1], f[2], Integer.parseInt(f[3]), "1".equals(f[4]), f[5]));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    @Override
    public synchronized void close() {
        if (h != 0) close(h);
        h = 0;
    }

    private static native long open(int fd, int stream);
    private static native byte[] tracks(long h);
    private static native long duration(long h);
    private static native int rate(long h);
    private static native int channels(long h);
    private static native int read(long h, ByteBuffer out, long[] ptsUs);
    private static native int seek(long h, long us);
    private static native void close(long h);
}
