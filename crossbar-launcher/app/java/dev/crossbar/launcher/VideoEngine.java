package dev.crossbar.launcher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Plays one video file. MediaCodec decodes the picture straight onto a Surface; the sound goes to an
 * AudioTrack, and the track's playback position is the clock each picture waits for. The sound comes
 * from FFmpeg (FfAudio), so tracks Android's own extractor skips still play, else from Android's own
 * decoders (PlatformAudio). Without sound, the system clock runs the picture.
 */
final class VideoEngine {
    interface Listener {
        /** The picture's shape: its width and height with the pixel aspect applied. */
        void onVideoSize(int width, int height);
        /** The first picture after opening or seeking is up. */
        void onReady();
        void onEnded();
        void onError(String message);
    }

    /** A decoded sound track: interleaved float samples, one or two channels. */
    interface Audio {
        /** Fills buf from the current position; returns the frame count, 0 at the end. ptsUs[0] is the first frame's time. */
        int read(ByteBuffer buf, long[] ptsUs) throws Exception;
        int rate();
        int channels();
        void seek(long us);
        long durationUs();
        List<Track> tracks();
        void close();
    }

    static final class Track {
        final int stream, channels;
        final String codec, lang, title;
        final boolean selected;

        Track(int stream, String codec, String lang, int channels, boolean selected, String title) {
            this.stream = stream;
            this.codec = codec;
            this.lang = lang;
            this.channels = channels;
            this.selected = selected;
            this.title = title;
        }

        /** Like "English · E-AC3 5.1 · Commentary". */
        String label() {
            List<String> parts = new ArrayList<>();
            String l = lang == null || lang.isEmpty() || lang.equals("und") ? "" : new Locale(lang).getDisplayLanguage();
            if (l.equalsIgnoreCase(lang)) l = lang.toUpperCase(Locale.ROOT);
            if (!l.isEmpty()) parts.add(l);
            String c;
            switch (codec) {
                case "eac3": c = "E-AC3"; break;
                case "truehd": c = "TrueHD"; break;
                case "dca": c = "DTS"; break;
                case "opus": c = "Opus"; break;
                case "vorbis": c = "Vorbis"; break;
                default: c = codec.toUpperCase(Locale.ROOT);
            }
            String layout = channels == 1 ? "Mono" : channels == 2 ? "Stereo" : channels == 6 ? "5.1" : channels == 8 ? "7.1" : channels + " ch";
            parts.add(c + " " + layout);
            if (title != null && !title.isEmpty() && !title.equalsIgnoreCase(l) && !title.equalsIgnoreCase(layout)) parts.add(title);
            return String.join(" · ", parts);
        }
    }

    private static final String TAG = "CrossBarVideo";
    private static final int FROZEN = 0, AUDIO = 1, WALL = 2;
    /** A picture this far behind the clock is skipped, unless several in a row were. */
    private static final long LATE_US = 70_000;

    private final Context ctx;
    private final Uri uri;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final Thread videoThread, audioThread;
    private volatile boolean released;
    private volatile long durationUs;
    private volatile List<Track> tracks = Collections.emptyList();
    private boolean failed;

    // What the player asked for; all guarded by lock, like the clock below.
    private boolean playing;
    private long seekUs;
    private int seekGen;
    private int audioStream = -1, audioGen;
    private Surface surface;
    /** The surface the decoder draws on now. */
    private Surface attached;

    // The clock. FROZEN: paused, or waiting for the first picture or sound after a seek. AUDIO: the
    // AudioTrack's position. WALL: the system clock, from frozenUs at wallNs.
    private int mode = FROZEN;
    private long frozenUs, wallNs;
    private AudioTrack track;
    private boolean trackStarted;
    private int trackRate;
    private long trackBaseUs, trackWritten, lastAudioUs, resumedNs;
    private final AudioTimestamp stamp = new AudioTimestamp();
    private boolean hasAudio = true, hasVideo = true;
    /** For the current seek: the sound has played out (or there is none), the last picture is up. */
    private boolean audioDone, videoDone;
    private int readyGen = -1, endedGen = -1;
    private int shownW, shownH, shownFrames, droppedFrames;

    VideoEngine(Context ctx, Uri uri, long startUs, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.uri = uri;
        this.listener = listener;
        seekUs = frozenUs = Math.max(0, startUs);
        videoThread = new Thread(this::videoLoop, "CrossBar video");
        audioThread = new Thread(this::audioLoop, "CrossBar sound");
    }

    void start(boolean play) {
        synchronized (lock) {
            playing = play;
        }
        videoThread.start();
        audioThread.start();
    }

    /** Where the picture goes; null before the surface is destroyed, which waits for the decoder to let go of it. */
    void setSurface(Surface s) {
        synchronized (lock) {
            surface = s;
            lock.notifyAll();
            long until = System.nanoTime() + 700_000_000L;
            while (s == null && attached != null && !released && System.nanoTime() < until) {
                try {
                    lock.wait(50);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    void play() {
        synchronized (lock) {
            if (playing || released) return;
            playing = true;
            if (track != null && trackStarted && !audioDone) {
                track.play();
                resumedNs = System.nanoTime();
                lastAudioUs = frozenUs;
                mode = AUDIO;
            } else if (audioDone && readyGen == seekGen) {
                wallNs = System.nanoTime();
                mode = WALL;
            }
            lock.notifyAll();
        }
    }

    void pause() {
        synchronized (lock) {
            if (!playing) return;
            frozenUs = positionLocked();
            playing = false;
            mode = FROZEN;
            if (track != null && trackStarted) track.pause();
            lock.notifyAll();
        }
    }

    boolean playing() {
        synchronized (lock) {
            return playing;
        }
    }

    void seekTo(long us) {
        synchronized (lock) {
            long d = durationUs;
            if (d > 0) us = Math.min(us, Math.max(0, d - 1_000_000));
            seekUs = frozenUs = Math.max(0, us);
            seekGen++;
            mode = FROZEN;
            audioDone = !hasAudio;
            videoDone = !hasVideo;
            if (track != null) track.pause();
            trackStarted = false;
            lock.notifyAll();
        }
    }

    /** Seeks from where the last seek was going while it's still underway, so repeated presses add up. */
    void seekBy(long deltaUs) {
        synchronized (lock) {
            seekTo((readyGen != seekGen ? seekUs : positionLocked()) + deltaUs);
        }
    }

    long positionUs() {
        synchronized (lock) {
            return positionLocked();
        }
    }

    long durationUs() {
        return durationUs;
    }

    List<Track> audioTracks() {
        return tracks;
    }

    void selectAudio(int stream) {
        synchronized (lock) {
            audioStream = stream;
            audioGen++;
            seekTo(positionLocked());
        }
    }

    /** Stops both threads; the decoder lets go of the surface before this returns. */
    void release() {
        synchronized (lock) {
            if (released) return;
            released = true;
            if (track != null) track.pause();
            lock.notifyAll();
        }
        audioThread.interrupt();
        try {
            videoThread.join(1500);
        } catch (InterruptedException ignored) {
        }
    }

    // ---------- the clock ----------

    private long positionLocked() {
        switch (mode) {
            case WALL:
                return frozenUs + (System.nanoTime() - wallNs) / 1000;
            case AUDIO:
                return audioPositionLocked();
            default:
                return frozenUs;
        }
    }

    private long audioPositionLocked() {
        AudioTrack t = track;
        if (t == null || trackRate <= 0) return frozenUs;
        long frames;
        if (t.getTimestamp(stamp) && stamp.nanoTime > resumedNs) {
            frames = stamp.framePosition + (System.nanoTime() - stamp.nanoTime) * trackRate / 1_000_000_000L;
        } else {
            frames = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        }
        long us = trackBaseUs + Math.min(frames, trackWritten) * 1_000_000L / trackRate;
        if (us < lastAudioUs) us = lastAudioUs;
        lastAudioUs = us;
        return us;
    }

    private void checkEndedLocked() {
        if (!videoDone || !audioDone || endedGen == seekGen) return;
        endedGen = seekGen;
        main.post(() -> {
            if (!released) listener.onEnded();
        });
    }

    private void fail(String what, Throwable e) {
        Log.w(TAG, what + ": " + uri, e);
        synchronized (lock) {
            if (failed || released) return;
            failed = true;
        }
        main.post(() -> {
            if (!released) listener.onError(what);
        });
    }

    private ParcelFileDescriptor open() throws IOException {
        ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("can't open " + uri);
        return pfd;
    }

    // ---------- the picture ----------

    private void videoLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            try (ParcelFileDescriptor pfd = open()) {
                ex.setDataSource(pfd.getFileDescriptor());
            }
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount() && fmt == null; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    ex.selectTrack(i);
                    fmt = f;
                }
            }
            if (fmt == null) {
                noVideo();
                return;
            }
            if (fmt.containsKey(MediaFormat.KEY_DURATION)) durationUs = Math.max(durationUs, fmt.getLong(MediaFormat.KEY_DURATION));
            int dispW = intOf(fmt, "display-width", 0), dispH = intOf(fmt, "display-height", 0);
            int rotation = intOf(fmt, MediaFormat.KEY_ROTATION, 0);
            reportShape(fmt, dispW, dispH, rotation);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int gen = -1, late = 0;
            long dropBefore = 0;
            boolean inEos = false, outEos = false, first = true, resync = false;
            while (!released) {
                Surface want, cur;
                int sg;
                long target;
                synchronized (lock) {
                    want = surface;
                    cur = attached;
                    sg = seekGen;
                    target = seekUs;
                }
                if (codec == null || want != cur) {
                    if (codec != null && want != null) {
                        try {
                            codec.setOutputSurface(want);
                            attach(want);
                            continue;
                        } catch (RuntimeException e) {
                            Log.w(TAG, "couldn't move the picture to the new surface; restarting the decoder");
                        }
                    }
                    if (codec != null) {
                        codec.release();
                        codec = null;
                        attach(null);
                    }
                    if (want == null || !want.isValid()) {
                        synchronized (lock) {
                            if (surface == want && !released) lock.wait(100);
                        }
                        continue;
                    }
                    codec = createCodec(fmt, want);
                    attach(want);
                    resync = gen == sg;
                    gen = resync ? gen : -1;
                    continue;
                }
                if (sg != gen || resync) {
                    long to = sg != gen ? target : positionUs();
                    gen = sg;
                    resync = false;
                    codec.flush();
                    ex.seekTo(to, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    dropBefore = to;
                    inEos = outEos = false;
                    first = true;
                    late = 0;
                }
                while (!inEos) {
                    int ii = codec.dequeueInputBuffer(0);
                    if (ii < 0) break;
                    ByteBuffer in = codec.getInputBuffer(ii);
                    int n = in == null ? -1 : ex.readSampleData(in, 0);
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inEos = true;
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
                if (outEos) {
                    synchronized (lock) {
                        if (seekGen == gen && surface == cur && !released) lock.wait(100);
                    }
                    continue;
                }
                int oi = codec.dequeueOutputBuffer(info, 10_000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    reportShape(codec.getOutputFormat(), dispW, dispH, rotation);
                    continue;
                }
                if (oi < 0) continue;
                boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if ((end && info.size == 0) || info.presentationTimeUs < dropBefore) {
                    codec.releaseOutputBuffer(oi, false);
                } else if (first) {
                    codec.releaseOutputBuffer(oi, true);
                    first = false;
                    shownFrames++;
                    pictureReady(gen);
                } else {
                    int before = late;
                    late = show(codec, oi, info.presentationTimeUs, gen, cur, late);
                    if (late > before) droppedFrames++;
                    else if (late == 0) shownFrames++;
                }
                if (end) {
                    outEos = true;
                    videoEnded(gen);
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable e) {
            if (!released) fail("This video's picture can't be played", e);
            synchronized (lock) {
                videoDone = true;
                hasVideo = false;
                readyGen = seekGen;
                checkEndedLocked();
                lock.notifyAll();
            }
        } finally {
            if (codec != null) codec.release();
            attach(null);
            ex.release();
            Log.i(TAG, "pictures shown " + shownFrames + ", dropped late " + droppedFrames);
        }
    }

    /** Holds a decoded picture until the clock reaches it, then shows it; drops it when it's late or a seek came. */
    private int show(MediaCodec codec, int oi, long pts, int gen, Surface s, int late) throws InterruptedException {
        for (;;) {
            long early;
            synchronized (lock) {
                if (released || seekGen != gen || surface != s) {
                    codec.releaseOutputBuffer(oi, false);
                    return late;
                }
                if (mode == FROZEN) {
                    lock.wait(50);
                    continue;
                }
                early = pts - positionLocked();
            }
            if (early > 30_000) {
                Thread.sleep(Math.min((early - 20_000) / 1000, 30));
                continue;
            }
            if (early < -LATE_US && late < 8) {
                codec.releaseOutputBuffer(oi, false);
                return late + 1;
            }
            codec.releaseOutputBuffer(oi, System.nanoTime() + Math.max(early, 0) * 1000);
            return 0;
        }
    }

    private void attach(Surface s) {
        synchronized (lock) {
            attached = s;
            lock.notifyAll();
        }
    }

    private void pictureReady(int gen) {
        synchronized (lock) {
            if (gen != seekGen || readyGen == gen) return;
            readyGen = gen;
            if (audioDone && playing && mode == FROZEN) {
                wallNs = System.nanoTime();
                mode = WALL;
            }
            lock.notifyAll();
        }
        main.post(() -> {
            if (!released) listener.onReady();
        });
    }

    private void videoEnded(int gen) {
        synchronized (lock) {
            if (gen != seekGen) return;
            videoDone = true;
            checkEndedLocked();
            lock.notifyAll();
        }
    }

    private void noVideo() {
        synchronized (lock) {
            hasVideo = false;
            videoDone = true;
            readyGen = seekGen;
            lock.notifyAll();
        }
        main.post(() -> {
            if (!released) listener.onReady();
        });
    }

    private static MediaCodec createCodec(MediaFormat fmt, Surface s) throws IOException {
        String name = null;
        try {
            name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(fmt);
        } catch (RuntimeException ignored) {
        }
        MediaCodec c = name != null ? MediaCodec.createByCodecName(name) : MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        try {
            c.configure(fmt, s, null, 0);
            c.start();
        } catch (RuntimeException e) {
            c.release();
            throw e;
        }
        return c;
    }

    private void reportShape(MediaFormat f, int dispW, int dispH, int rotation) {
        int w = intOf(f, MediaFormat.KEY_WIDTH, 0), h = intOf(f, MediaFormat.KEY_HEIGHT, 0);
        if (f.containsKey("crop-left") && f.containsKey("crop-right")) w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
        if (f.containsKey("crop-top") && f.containsKey("crop-bottom")) h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
        if (w <= 0 || h <= 0) return;
        float par = 1;
        int sarW = intOf(f, "sar-width", 0), sarH = intOf(f, "sar-height", 0);
        if (sarW > 0 && sarH > 0) par = sarW / (float) sarH;
        else if (dispW > 0 && dispH > 0) par = (dispW / (float) dispH) / (w / (float) h);
        int dw = Math.max(1, Math.round(w * par)), dh = h;
        if (rotation == 90 || rotation == 270) {
            int t = dw;
            dw = dh;
            dh = t;
        }
        if (dw == shownW && dh == shownH) return;
        shownW = dw;
        shownH = dh;
        int fw = dw, fh = dh;
        main.post(() -> {
            if (!released) listener.onVideoSize(fw, fh);
        });
    }

    private static int intOf(MediaFormat f, String key, int def) {
        try {
            return f.containsKey(key) ? f.getInteger(key) : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    // ---------- the sound ----------

    private void audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        Audio src = null;
        AudioTrack t = null;
        ByteBuffer buf = ByteBuffer.allocateDirect(16384 * 2 * 4).order(ByteOrder.nativeOrder());
        buf.limit(0);
        long[] pts = new long[1];
        int gen = -1, opened = -1;
        boolean eos = false;
        long fullSince = 0, stalledSince = 0, lastHead = -1;
        try {
            while (!released) {
                int sg, ag, stream;
                long target;
                boolean want;
                synchronized (lock) {
                    sg = seekGen;
                    ag = audioGen;
                    stream = audioStream;
                    target = seekUs;
                    want = playing;
                }
                if (ag != opened) {
                    opened = ag;
                    if (src != null) src.close();
                    src = openAudio(stream);
                    if (src == null) {
                        noAudio();
                        return;
                    }
                    tracks = src.tracks();
                    durationUs = Math.max(durationUs, src.durationUs());
                    gen = -1;
                }
                if (sg != gen) {
                    gen = sg;
                    dropTrack(t);
                    t = null;
                    src.seek(target);
                    buf.limit(0);
                    eos = false;
                    fullSince = 0;
                    lastHead = -1;
                    continue;
                }
                if (!buf.hasRemaining() && !eos) {
                    buf.clear();
                    int n = src.read(buf, pts);
                    if (n <= 0) {
                        eos = true;
                        buf.limit(0);
                    } else {
                        buf.position(0).limit(n * src.channels() * 4);
                        if (t == null || t.getSampleRate() != src.rate() || t.getChannelCount() != src.channels()) {
                            dropTrack(t);
                            t = newTrack(src.rate(), src.channels(), sg, pts[0]);
                            if (t == null) buf.limit(0);
                        }
                    }
                    continue;
                }
                if (t == null) {
                    if (eos) {
                        finishAudio(sg);
                        waitForChange(sg, ag);
                    }
                    continue;
                }
                if (buf.hasRemaining()) {
                    int w = t.write(buf, buf.remaining(), AudioTrack.WRITE_NON_BLOCKING);
                    if (w < 0) throw new IllegalStateException("AudioTrack.write returned " + w);
                    synchronized (lock) {
                        if (track == t) trackWritten += w / (4L * t.getChannelCount());
                    }
                }
                boolean full = buf.hasRemaining();
                boolean started;
                synchronized (lock) {
                    started = trackStarted && track == t;
                }
                if (!started) {
                    if (full || eos) {
                        if (fullSince == 0) fullSince = System.nanoTime();
                        boolean picture;
                        synchronized (lock) {
                            picture = !hasVideo || videoDone || readyGen == sg;
                        }
                        if (want && (picture || System.nanoTime() - fullSince > 2_000_000_000L)) startTrack(t, sg);
                        else idle(want ? 10 : 100);
                    }
                    continue;
                }
                if (eos && !buf.hasRemaining()) {
                    long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                    long written;
                    synchronized (lock) {
                        written = trackWritten;
                    }
                    if (head != lastHead || !want) {
                        lastHead = head;
                        stalledSince = System.nanoTime();
                    }
                    if (head >= written - t.getSampleRate() / 50 || System.nanoTime() - stalledSince > 1_000_000_000L) {
                        finishAudio(sg);
                        dropTrack(t);
                        t = null;
                        waitForChange(sg, ag);
                    } else {
                        Thread.sleep(20);
                    }
                    continue;
                }
                if (full) idle(want ? 10 : 100);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable e) {
            if (!released) Log.w(TAG, "sound stopped: " + uri, e);
            noAudio();
        } finally {
            dropTrack(t);
            if (src != null) src.close();
        }
    }

    private Audio openAudio(int stream) {
        try (ParcelFileDescriptor pfd = open()) {
            Audio a = FfAudio.open(pfd, stream);
            if (a != null) return a;
            return PlatformAudio.open(pfd);
        } catch (Exception e) {
            Log.w(TAG, "no sound: " + uri, e);
            return null;
        }
    }

    private AudioTrack newTrack(int rate, int channels, int gen, long baseUs) {
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_FLOAT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build())
                .setBufferSizeInBytes(Math.max(min * 2, rate * channels * 4 / 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        synchronized (lock) {
            if (gen != seekGen || released) {
                t.release();
                return null;
            }
            track = t;
            trackStarted = false;
            trackRate = rate;
            trackBaseUs = baseUs;
            trackWritten = 0;
            lastAudioUs = baseUs;
        }
        return t;
    }

    private void startTrack(AudioTrack t, int gen) {
        synchronized (lock) {
            if (released || gen != seekGen || track != t || !playing || trackStarted) return;
            t.play();
            trackStarted = true;
            resumedNs = System.nanoTime();
            lastAudioUs = trackBaseUs;
            mode = AUDIO;
            lock.notifyAll();
        }
    }

    private void dropTrack(AudioTrack t) {
        if (t == null) return;
        synchronized (lock) {
            if (track == t) {
                if (mode == AUDIO) {
                    frozenUs = positionLocked();
                    wallNs = System.nanoTime();
                    mode = playing && audioDone ? WALL : FROZEN;
                }
                track = null;
                trackStarted = false;
            }
        }
        try {
            t.pause();
            t.flush();
        } catch (IllegalStateException ignored) {
        }
        t.release();
    }

    /** The sound for this seek has played out: the system clock takes over for the rest of the picture. */
    private void finishAudio(int gen) {
        synchronized (lock) {
            if (gen != seekGen || audioDone) return;
            if (mode == AUDIO) frozenUs = positionLocked();
            audioDone = true;
            wallNs = System.nanoTime();
            mode = playing && readyGen == seekGen ? WALL : FROZEN;
            checkEndedLocked();
            lock.notifyAll();
        }
    }

    private void noAudio() {
        synchronized (lock) {
            hasAudio = false;
            if (mode == AUDIO) frozenUs = positionLocked();
            audioDone = true;
            wallNs = System.nanoTime();
            mode = playing && readyGen == seekGen ? WALL : FROZEN;
            checkEndedLocked();
            lock.notifyAll();
        }
    }

    /** Sleeps until a play, pause or seek wakes the thread, or the time runs out. */
    private void idle(long ms) throws InterruptedException {
        synchronized (lock) {
            if (!released) lock.wait(ms);
        }
    }

    private void waitForChange(int gen, int agen) throws InterruptedException {
        synchronized (lock) {
            while (!released && seekGen == gen && audioGen == agen) lock.wait();
        }
    }

    /** Android's own extractor and decoders; for files FFmpeg can't open. */
    static final class PlatformAudio implements Audio {
        private final MediaExtractor ex = new MediaExtractor();
        private final MediaCodec codec;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private final long durationUs;
        private final Track only;
        private int rate, inChannels, encoding = AudioFormat.ENCODING_PCM_16BIT;
        private boolean inEos, outEos;
        private long dropUntil = -1;

        private PlatformAudio(MediaFormat f, String mime) throws IOException {
            f.setInteger(MediaFormat.KEY_AAC_MAX_OUTPUT_CHANNEL_COUNT, 2);
            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            inChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            durationUs = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
            String lang = f.containsKey(MediaFormat.KEY_LANGUAGE) ? f.getString(MediaFormat.KEY_LANGUAGE) : "";
            only = new Track(0, mime.substring(mime.indexOf('/') + 1), lang, inChannels, true, "");
            codec = MediaCodec.createDecoderByType(mime);
            try {
                codec.configure(f, null, null, 0);
                codec.start();
            } catch (RuntimeException e) {
                codec.release();
                throw e;
            }
        }

        static PlatformAudio open(ParcelFileDescriptor pfd) throws IOException {
            MediaExtractor probe = new MediaExtractor();
            boolean ok = false;
            try {
                probe.setDataSource(pfd.getFileDescriptor());
                for (int i = 0; i < probe.getTrackCount(); i++) {
                    MediaFormat f = probe.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime == null || !mime.startsWith("audio/")) continue;
                    PlatformAudio a = new PlatformAudio(f, mime);
                    a.ex.setDataSource(pfd.getFileDescriptor());
                    a.ex.selectTrack(i);
                    ok = true;
                    return a;
                }
                return null;
            } finally {
                probe.release();
                if (!ok) Log.i(TAG, "no sound track Android can play");
            }
        }

        @Override
        public synchronized int read(ByteBuffer out, long[] ptsUs) {
            while (!outEos) {
                while (!inEos) {
                    int ii = codec.dequeueInputBuffer(0);
                    if (ii < 0) break;
                    ByteBuffer in = codec.getInputBuffer(ii);
                    int n = in == null ? -1 : ex.readSampleData(in, 0);
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inEos = true;
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10_000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat o = codec.getOutputFormat();
                    rate = o.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    inChannels = o.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    encoding = o.containsKey(MediaFormat.KEY_PCM_ENCODING) ? o.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    continue;
                }
                if (oi < 0) continue;
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                ByteBuffer b = codec.getOutputBuffer(oi);
                int frames = 0;
                long pts = info.presentationTimeUs;
                if (b != null && info.size > 0) {
                    b.position(info.offset).limit(info.offset + info.size);
                    frames = convert(b.slice().order(ByteOrder.nativeOrder()), out);
                }
                codec.releaseOutputBuffer(oi, false);
                if (frames <= 0) continue;
                if (dropUntil >= 0) {
                    int skip = (int) Math.min(frames, Math.max(0, (dropUntil - pts) * rate / 1_000_000L));
                    if (skip >= frames) continue;
                    if (skip > 0) {
                        int ch = channels();
                        ByteBuffer tail = out.duplicate().order(ByteOrder.nativeOrder());
                        tail.position(skip * ch * 4).limit(frames * ch * 4);
                        out.clear();
                        out.put(tail);
                        frames -= skip;
                        pts += skip * 1_000_000L / rate;
                    }
                    dropUntil = -1;
                }
                ptsUs[0] = pts;
                return frames;
            }
            return 0;
        }

        /** Decoder output (16-bit or float, any channel count) to interleaved float, mono or stereo. */
        private int convert(ByteBuffer in, ByteBuffer out) {
            boolean flt = encoding == AudioFormat.ENCODING_PCM_FLOAT;
            int bytes = flt ? 4 : 2, ch = Math.max(1, inChannels), outCh = channels();
            int frames = Math.min(in.remaining() / (bytes * ch), out.capacity() / (outCh * 4));
            out.clear();
            float[] s = new float[ch];
            for (int i = 0; i < frames; i++) {
                for (int c = 0; c < ch; c++) s[c] = flt ? in.getFloat() : in.getShort() / 32768f;
                if (outCh == 1) {
                    out.putFloat(s[0]);
                } else if (ch == 2) {
                    out.putFloat(s[0]).putFloat(s[1]);
                } else {
                    // WAV channel order: FL FR FC LFE BL BR (SL SR).
                    float c = ch > 2 ? s[2] * 0.707f : 0, bl = ch > 4 ? s[4] : 0, br = ch > 5 ? s[5] : 0;
                    float sl = ch > 6 ? s[6] : 0, sr = ch > 7 ? s[7] : 0;
                    float l = (s[0] + c + 0.707f * (bl + sl)) / 2.4f, r = (s[1] + c + 0.707f * (br + sr)) / 2.4f;
                    out.putFloat(Math.max(-1f, Math.min(1f, l))).putFloat(Math.max(-1f, Math.min(1f, r)));
                }
            }
            return frames;
        }

        @Override
        public int rate() {
            return rate;
        }

        @Override
        public int channels() {
            return inChannels == 1 ? 1 : 2;
        }

        @Override
        public synchronized void seek(long us) {
            ex.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec.flush();
            inEos = outEos = false;
            dropUntil = us;
        }

        @Override
        public long durationUs() {
            return durationUs;
        }

        @Override
        public List<Track> tracks() {
            return Collections.singletonList(only);
        }

        @Override
        public synchronized void close() {
            codec.release();
            ex.release();
        }
    }
}
