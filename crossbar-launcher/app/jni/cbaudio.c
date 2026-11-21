/*
 * The native player's sound: FFmpeg demuxes and decodes the audio track of a video file. The Thor's
 * firmware drops E-AC3, DTS and TrueHD tracks from MKV files and has no decoders for them, so Android's
 * MediaExtractor never sees them. Hands back interleaved float PCM (mono, or downmixed to stereo) with
 * timestamps. Built by tools/build_audio.sh; used by FfAudio.java.
 */
#include <errno.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <android/log.h>
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libswresample/swresample.h>

#define TAG "CrossBarAudio"

typedef struct {
    int fd;
    AVFormatContext *fmt;
    AVIOContext *io;
    AVCodecContext *dec;
    SwrContext *swr;
    AVPacket *pkt;
    AVFrame *frame;
    int stream, seekStream;
    int draining;           /* the file is read to the end and the decoder has its flush packet */
    int64_t dropUntil;      /* after a seek, sound before this time (us) is skipped */
    int64_t nextPts;        /* us: where the next sample falls, for frames without a timestamp */
    int inRate, inFmt;
    AVChannelLayout inLayout;
    int rate, channels;     /* of what read() returns */
} Ctx;

static void log_cb(void *avcl, int level, const char *fmt, va_list vl) {
    (void) avcl;
    if (level > av_log_get_level()) return;
    char line[512];
    vsnprintf(line, sizeof line, fmt, vl);
    __android_log_write(level <= AV_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, line);
}

static int io_read(void *op, uint8_t *buf, int size) {
    Ctx *c = op;
    ssize_t n;
    do n = read(c->fd, buf, (size_t) size); while (n < 0 && errno == EINTR);
    if (n == 0) return AVERROR_EOF;
    return n < 0 ? AVERROR(errno) : (int) n;
}

static int64_t io_seek(void *op, int64_t off, int whence) {
    Ctx *c = op;
    if (whence & AVSEEK_SIZE) {
        struct stat st;
        return fstat(c->fd, &st) == 0 ? (int64_t) st.st_size : AVERROR(errno);
    }
    off_t r = lseek(c->fd, (off_t) off, whence & ~AVSEEK_FORCE);
    return r < 0 ? AVERROR(errno) : (int64_t) r;
}

static int playable(const AVStream *s) {
    return s->codecpar->codec_type == AVMEDIA_TYPE_AUDIO && avcodec_find_decoder(s->codecpar->codec_id) != NULL;
}

/* The track to play: the one asked for, else the default one, else the first; commentaries last. */
static int pick(const AVFormatContext *f, int want) {
    if (want >= 0 && want < (int) f->nb_streams && playable(f->streams[want])) return want;
    int best = -1, bestScore = -1;
    for (unsigned i = 0; i < f->nb_streams; i++) {
        const AVStream *s = f->streams[i];
        if (!playable(s)) continue;
        int score = 1;
        if (s->disposition & AV_DISPOSITION_DEFAULT) score += 2;
        if (!(s->disposition & (AV_DISPOSITION_COMMENT | AV_DISPOSITION_VISUAL_IMPAIRED | AV_DISPOSITION_DESCRIPTIONS)))
            score += 4;
        if (score > bestScore) {
            best = (int) i;
            bestScore = score;
        }
    }
    return best;
}

/* Seeks go by the picture's keyframes when there is a picture: MKV cues usually index only the video track. */
static int seek_stream(const AVFormatContext *f, int audio) {
    for (unsigned i = 0; i < f->nb_streams; i++) {
        const AVStream *s = f->streams[i];
        if (s->codecpar->codec_type == AVMEDIA_TYPE_VIDEO && !(s->disposition & AV_DISPOSITION_ATTACHED_PIC)) return (int) i;
    }
    return audio;
}

static void release(Ctx *c) {
    if (!c) return;
    swr_free(&c->swr);
    avcodec_free_context(&c->dec);
    av_frame_free(&c->frame);
    av_packet_free(&c->pkt);
    if (c->fmt) avformat_close_input(&c->fmt);
    if (c->io) {
        av_freep(&c->io->buffer);
        avio_context_free(&c->io);
    }
    av_channel_layout_uninit(&c->inLayout);
    if (c->fd >= 0) close(c->fd);
    free(c);
}

JNIEXPORT jlong JNICALL Java_dev_crossbar_launcher_FfAudio_open(JNIEnv *env, jclass cls, jint fd, jint want) {
    (void) env;
    (void) cls;
    static int logging;
    if (!logging) {
        logging = 1;
        av_log_set_level(AV_LOG_ERROR);
        av_log_set_callback(log_cb);
    }
    Ctx *c = calloc(1, sizeof *c);
    if (!c) return 0;
    AVDictionary *opts = NULL;
    const AVCodec *codec;
    AVStream *st;
    uint8_t *buf;
    int r;
    c->fd = dup(fd);
    c->dropUntil = AV_NOPTS_VALUE;
    if (c->fd < 0) goto fail;
    lseek(c->fd, 0, SEEK_SET);
    if (!(buf = av_malloc(1 << 16))) goto fail;
    if (!(c->io = avio_alloc_context(buf, 1 << 16, 0, c, io_read, NULL, io_seek))) {
        av_free(buf);
        goto fail;
    }
    if (!(c->fmt = avformat_alloc_context())) goto fail;
    c->fmt->pb = c->io;
    c->fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    if (avformat_open_input(&c->fmt, NULL, NULL, NULL) < 0) goto fail;
    c->fmt->probesize = 4 << 20;
    c->fmt->max_analyze_duration = 3 * AV_TIME_BASE;
    if (avformat_find_stream_info(c->fmt, NULL) < 0) goto fail;
    if ((c->stream = pick(c->fmt, want)) < 0) goto fail;
    c->seekStream = seek_stream(c->fmt, c->stream);
    for (unsigned i = 0; i < c->fmt->nb_streams; i++)
        if ((int) i != c->stream) c->fmt->streams[i]->discard = AVDISCARD_ALL;
    st = c->fmt->streams[c->stream];
    codec = avcodec_find_decoder(st->codecpar->codec_id);
    if (!(c->dec = avcodec_alloc_context3(codec)) || avcodec_parameters_to_context(c->dec, st->codecpar) < 0) goto fail;
    c->dec->pkt_timebase = st->time_base;
    /* AC-3, E-AC3, DTS and TrueHD decoders can mix down to stereo themselves; others go through swr. */
    av_dict_set(&opts, "downmix", "stereo", 0);
    r = avcodec_open2(c->dec, codec, &opts);
    av_dict_free(&opts);
    if (r < 0) goto fail;
    if (!(c->pkt = av_packet_alloc()) || !(c->frame = av_frame_alloc())) goto fail;
    c->rate = c->dec->sample_rate > 0 ? c->dec->sample_rate : 48000;
    c->channels = c->dec->ch_layout.nb_channels == 1 ? 1 : 2;
    c->nextPts = st->start_time != AV_NOPTS_VALUE ? av_rescale_q(st->start_time, st->time_base, AV_TIME_BASE_Q) : 0;
    return (jlong) (intptr_t) c;
fail:
    release(c);
    return 0;
}

/* One line per playable track: index, codec, language, channels, selected (0/1), title; tab-separated. */
JNIEXPORT jbyteArray JNICALL Java_dev_crossbar_launcher_FfAudio_tracks(JNIEnv *env, jclass cls, jlong h) {
    (void) cls;
    Ctx *c = (Ctx *) (intptr_t) h;
    char out[4096];
    size_t n = 0;
    out[0] = 0;
    for (unsigned i = 0; i < c->fmt->nb_streams && n < sizeof out - 320; i++) {
        const AVStream *s = c->fmt->streams[i];
        if (!playable(s)) continue;
        const AVDictionaryEntry *lang = av_dict_get(s->metadata, "language", NULL, 0);
        const AVDictionaryEntry *title = av_dict_get(s->metadata, "title", NULL, 0);
        char t[160];
        snprintf(t, sizeof t, "%s", title ? title->value : "");
        for (char *p = t; *p; p++)
            if (*p == '\t' || *p == '\n' || *p == '\r') *p = ' ';
        n += (size_t) snprintf(out + n, sizeof out - n, "%u\t%s\t%s\t%d\t%d\t%s\n", i,
                               avcodec_get_name(s->codecpar->codec_id), lang ? lang->value : "",
                               s->codecpar->ch_layout.nb_channels, (int) i == c->stream, t);
    }
    jbyteArray arr = (*env)->NewByteArray(env, (jsize) n);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, (jsize) n, (const jbyte *) out);
    return arr;
}

JNIEXPORT jlong JNICALL Java_dev_crossbar_launcher_FfAudio_duration(JNIEnv *env, jclass cls, jlong h) {
    (void) env;
    (void) cls;
    Ctx *c = (Ctx *) (intptr_t) h;
    return c->fmt->duration != AV_NOPTS_VALUE ? c->fmt->duration : 0;
}

JNIEXPORT jint JNICALL Java_dev_crossbar_launcher_FfAudio_rate(JNIEnv *env, jclass cls, jlong h) {
    (void) env;
    (void) cls;
    return ((Ctx *) (intptr_t) h)->rate;
}

JNIEXPORT jint JNICALL Java_dev_crossbar_launcher_FfAudio_channels(JNIEnv *env, jclass cls, jlong h) {
    (void) env;
    (void) cls;
    return ((Ctx *) (intptr_t) h)->channels;
}

/* Converts the decoded frame to interleaved float, reconfiguring when the stream's format changes. */
static int convert(Ctx *c, float *out, int cap, int64_t *pts) {
    AVFrame *f = c->frame;
    AVChannelLayout in = {0};
    int r;
    if (f->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC || f->ch_layout.nb_channels <= 0)
        av_channel_layout_default(&in, f->ch_layout.nb_channels > 0 ? f->ch_layout.nb_channels : 2);
    else if ((r = av_channel_layout_copy(&in, &f->ch_layout)) < 0)
        return r;
    if (!c->swr || f->sample_rate != c->inRate || f->format != c->inFmt || av_channel_layout_compare(&in, &c->inLayout)) {
        swr_free(&c->swr);
        int ch = in.nb_channels == 1 ? 1 : 2;
        AVChannelLayout outLayout;
        av_channel_layout_default(&outLayout, ch);
        r = swr_alloc_set_opts2(&c->swr, &outLayout, AV_SAMPLE_FMT_FLT, f->sample_rate, &in, f->format, f->sample_rate, 0, NULL);
        if (r >= 0) r = swr_init(c->swr);
        if (r < 0) {
            swr_free(&c->swr);
            av_channel_layout_uninit(&in);
            return r;
        }
        c->inRate = f->sample_rate;
        c->inFmt = f->format;
        av_channel_layout_uninit(&c->inLayout);
        av_channel_layout_copy(&c->inLayout, &in);
        c->rate = f->sample_rate;
        c->channels = ch;
    }
    av_channel_layout_uninit(&in);
    int64_t ts = f->best_effort_timestamp;
    *pts = ts != AV_NOPTS_VALUE ? av_rescale_q(ts, c->fmt->streams[c->stream]->time_base, AV_TIME_BASE_Q) : c->nextPts;
    uint8_t *o = (uint8_t *) out;
    int n = swr_convert(c->swr, &o, cap, (const uint8_t **) f->extended_data, f->nb_samples);
    for (size_t i = 0, m = n > 0 ? (size_t) n * (size_t) c->channels : 0; i < m; i++) {
        if (out[i] > 1.f) out[i] = 1.f;
        else if (out[i] < -1.f) out[i] = -1.f;
    }
    return n;
}

/*
 * Decodes the next stretch of sound into out (a direct buffer of interleaved floats). Returns the number
 * of frames, 0 at the end of the track, or a negative error; pts[0] gets the time (us) of the first one.
 */
JNIEXPORT jint JNICALL Java_dev_crossbar_launcher_FfAudio_read(JNIEnv *env, jclass cls, jlong h, jobject buf, jlongArray ptsOut) {
    (void) cls;
    Ctx *c = (Ctx *) (intptr_t) h;
    float *out = (*env)->GetDirectBufferAddress(env, buf);
    int cap = (int) ((*env)->GetDirectBufferCapacity(env, buf) / (jlong) (2 * sizeof(float)));
    if (!out || cap <= 0) return AVERROR(EINVAL);
    for (;;) {
        int64_t pts;
        int n;
        if (c->swr && swr_get_out_samples(c->swr, 0) > 0) {
            uint8_t *o = (uint8_t *) out;
            pts = c->nextPts;
            n = swr_convert(c->swr, &o, cap, NULL, 0);
        } else {
            int r = avcodec_receive_frame(c->dec, c->frame);
            if (r == AVERROR_EOF) return 0;
            if (r == AVERROR(EAGAIN)) {
                if (c->draining) return 0;
                if (av_read_frame(c->fmt, c->pkt) < 0) {
                    avcodec_send_packet(c->dec, NULL);
                    c->draining = 1;
                } else {
                    /* A damaged packet is refused here and skipped. */
                    if (c->pkt->stream_index == c->stream) avcodec_send_packet(c->dec, c->pkt);
                    av_packet_unref(c->pkt);
                }
                continue;
            }
            if (r < 0) continue;
            n = convert(c, out, cap, &pts);
            av_frame_unref(c->frame);
            if (n < 0) return n;
        }
        if (n <= 0) continue;
        c->nextPts = pts + av_rescale(n, 1000000, c->rate);
        if (c->dropUntil != AV_NOPTS_VALUE) {
            if (c->nextPts <= c->dropUntil) continue;
            if (pts < c->dropUntil) {
                int skip = (int) av_rescale(c->dropUntil - pts, c->rate, 1000000);
                if (skip > 0 && skip < n) {
                    memmove(out, out + (size_t) skip * (size_t) c->channels, (size_t) (n - skip) * (size_t) c->channels * sizeof(float));
                    n -= skip;
                    pts += av_rescale(skip, 1000000, c->rate);
                }
            }
            c->dropUntil = AV_NOPTS_VALUE;
        }
        jlong p = pts;
        (*env)->SetLongArrayRegion(env, ptsOut, 0, 1, &p);
        return n;
    }
}

/* Goes to a time (us); the next read() starts there exactly. */
JNIEXPORT jint JNICALL Java_dev_crossbar_launcher_FfAudio_seek(JNIEnv *env, jclass cls, jlong h, jlong us) {
    (void) env;
    (void) cls;
    Ctx *c = (Ctx *) (intptr_t) h;
    const AVStream *s = c->fmt->streams[c->seekStream];
    int r = av_seek_frame(c->fmt, c->seekStream, av_rescale_q(us, AV_TIME_BASE_Q, s->time_base), AVSEEK_FLAG_BACKWARD);
    if (r < 0 && c->seekStream != c->stream) {
        s = c->fmt->streams[c->stream];
        r = av_seek_frame(c->fmt, c->stream, av_rescale_q(us, AV_TIME_BASE_Q, s->time_base), AVSEEK_FLAG_BACKWARD);
    }
    if (r < 0) r = avformat_seek_file(c->fmt, -1, INT64_MIN, us, us, 0);
    avcodec_flush_buffers(c->dec);
    swr_free(&c->swr);
    c->draining = 0;
    c->dropUntil = us;
    c->nextPts = us;
    return r;
}

JNIEXPORT void JNICALL Java_dev_crossbar_launcher_FfAudio_close(JNIEnv *env, jclass cls, jlong h) {
    (void) env;
    (void) cls;
    release((Ctx *) (intptr_t) h);
}
