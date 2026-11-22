package dev.anideck.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies the video and audio of downloaded HLS segments (MPEG-TS or fragmented MP4, the audio
 * possibly in its own file) into a plain MP4 without re-encoding, so any player can open it.
 */
final class Remux {
    /** A timestamp this far behind the last one is a splice (EXT-X-DISCONTINUITY), not B-frame reordering. */
    private static final long JUMP_US = 1_000_000;

    interface Progress {
        /** Fraction of the input done; false to stop. */
        boolean at(double fraction);
    }

    private static final class Track {
        MediaExtractor ex;
        int index, out, rate;
        long offset, last = Long.MIN_VALUE, step, lastFrame = Long.MIN_VALUE;
        boolean done, aac;
    }

    private Remux() {
    }

    /** Throws when Android's MP4 muxer can't take a track (the caller keeps the TS instead). */
    static void toMp4(List<File> inputs, FileDescriptor out, Progress progress) throws IOException {
        List<Track> tracks = new ArrayList<>();
        List<MediaExtractor> extractors = new ArrayList<>();
        MediaMuxer mux = null;
        boolean started = false;
        try {
            boolean video = false, audio = false;
            for (File f : inputs) {
                MediaExtractor ex = new MediaExtractor();
                extractors.add(ex);
                ex.setDataSource(f.getPath());
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat fmt = ex.getTrackFormat(i);
                    String mime = fmt.getString(MediaFormat.KEY_MIME);
                    if (mime == null) continue;
                    boolean isVideo = mime.startsWith("video/"), isAudio = mime.startsWith("audio/");
                    if ((isVideo && video) || (isAudio && audio) || (!isVideo && !isAudio)) continue;
                    Track t = new Track();
                    t.ex = ex;
                    t.index = i;
                    t.step = isVideo ? 33_000 : 21_000;
                    t.aac = MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime);
                    t.rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
                    tracks.add(t);
                    ex.selectTrack(i);
                    if (isVideo) video = true;
                    else audio = true;
                }
            }
            if (!video) throw new IOException("no video track");
            mux = new MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            for (Track t : tracks) t.out = mux.addTrack(t.ex.getTrackFormat(t.index));
            mux.start();
            started = true;
            long end = 1;
            for (Track t : tracks) {
                MediaFormat fmt = t.ex.getTrackFormat(t.index);
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) end = Math.max(end, fmt.getLong(MediaFormat.KEY_DURATION));
            }
            ByteBuffer buf = ByteBuffer.allocateDirect(16 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long n = 0, first = Long.MIN_VALUE;
            while (true) {
                // Each extractor gives its file's samples in order; between files, the earliest goes first.
                Track next = null;
                long nextTime = Long.MAX_VALUE;
                for (MediaExtractor ex : extractors) {
                    int ti = ex.getSampleTrackIndex();
                    Track t = ti < 0 ? null : find(tracks, ex, ti);
                    if (t == null) continue;
                    long time = ex.getSampleTime();
                    if (time + t.offset < nextTime) {
                        next = t;
                        nextTime = time + t.offset;
                    }
                }
                if (next == null) break;
                MediaExtractor ex = next.ex;
                int size = ex.readSampleData(buf, 0);
                if (size < 0) {
                    ex.advance();
                    continue;
                }
                long time = ex.getSampleTime() + next.offset;
                if (next.last != Long.MIN_VALUE && time < next.last - JUMP_US) {
                    next.offset += next.last + next.step - time;
                    time = next.last + next.step;
                }
                if (next.last != Long.MIN_VALUE && time > next.last) next.step = Math.max(1000, Math.min(next.step, time - next.last));
                next.last = Math.max(next.last, time);
                int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, size, Math.max(0, time), flags);
                if (!next.aac || !writeAdts(mux, next, buf, size, Math.max(0, time), info)) mux.writeSampleData(next.out, buf, info);
                ex.advance();
                if (first == Long.MIN_VALUE) first = time;
                if (++n % 400 == 0 && progress != null && !progress.at(Math.min(1, (double) (time - first) / end))) {
                    throw new IOException("canceled");
                }
            }
            if (n == 0) throw new IOException("no samples");
            started = false;
            mux.stop();
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new IOException("remux: " + e.getMessage(), e);
        } finally {
            if (mux != null) {
                try {
                    if (started) mux.stop();
                } catch (IllegalStateException ignored) {
                    // Already failing; the caller deletes the file.
                }
                mux.release();
            }
            for (MediaExtractor ex : extractors) ex.release();
        }
    }

    /**
     * MPEG-TS hands AAC over as ADTS frames, several to a sample; an MP4 needs each frame on its own without
     * its header, or players can't decode it. False when the sample isn't a run of whole ADTS frames.
     */
    private static boolean writeAdts(MediaMuxer mux, Track t, ByteBuffer buf, int size, long time, MediaCodec.BufferInfo info) {
        if (t.rate <= 0) return false;
        List<int[]> frames = new ArrayList<>(); // {start, length, header length, raw blocks}
        int pos = 0;
        while (pos + 7 <= size) {
            int b1 = buf.get(pos + 1) & 0xFF;
            if ((buf.get(pos) & 0xFF) != 0xFF || (b1 & 0xF6) != 0xF0) return false;
            int header = (b1 & 1) != 0 ? 7 : 9;
            int len = ((buf.get(pos + 3) & 0x03) << 11) | ((buf.get(pos + 4) & 0xFF) << 3) | ((buf.get(pos + 5) & 0xFF) >>> 5);
            if (len <= header || pos + len > size) return false;
            frames.add(new int[] {pos, len, header, (buf.get(pos + 6) & 0x03) + 1});
            pos += len;
        }
        if (pos != size || frames.isEmpty()) return false;
        long samples = 0;
        for (int[] f : frames) {
            // The muxer wants each track's times to rise, and TS timestamps can jitter back a little.
            long at = Math.max(time + samples * 1_000_000L / t.rate, t.lastFrame + 1);
            info.set(f[0] + f[2], f[1] - f[2], at, MediaCodec.BUFFER_FLAG_KEY_FRAME);
            mux.writeSampleData(t.out, buf, info);
            t.lastFrame = at;
            samples += 1024L * f[3];
        }
        return true;
    }

    private static Track find(List<Track> tracks, MediaExtractor ex, int index) {
        for (Track t : tracks) if (t.ex == ex && t.index == index) return t;
        return null;
    }
}
