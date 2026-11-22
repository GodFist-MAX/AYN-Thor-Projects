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
        int index, out;
        long offset, last = Long.MIN_VALUE, step;
        boolean done;
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
                mux.writeSampleData(next.out, buf, info);
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

    private static Track find(List<Track> tracks, MediaExtractor ex, int index) {
        for (Track t : tracks) if (t.ex == ex && t.index == index) return t;
        return null;
    }
}
