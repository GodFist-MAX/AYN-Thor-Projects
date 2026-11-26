package dev.grokbuild.coronamon;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Talking with Coronamon out loud. Your voice streams to xAI's speech-to-text, whose Smart Turn
 * decides when you've finished; the words go to his Grok brain like a chat message; and the answer
 * comes back through xAI's text-to-speech while his mouth moves with it. Then he listens a few
 * seconds more in case you answer back. All of it counts toward the brain's daily limit. No audio
 * is kept, and what you say is only logged for test audio sent from the Mac.
 */
final class Voice {
    static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3;
    private static final String[] STATES = {"idle", "listening", "thinking", "speaking"};
    /** xAI's built-in voices. A custom voice id from the xAI console works too. */
    /** The den's picks: the brightest, most lively of xAI's male voices, Kepler first. coro voice voices lists them all. */
    static final String[] VOICES = {"kepler", "sal", "naksh", "helix", "rex"};
    private static final String HOST = "api.x.ai", TTS_URL = "https://api.x.ai/v1/tts", VOICES_URL = "https://api.x.ai/v1/tts/voices";
    /** US dollars: streaming speech-to-text is $0.20 an hour, text-to-speech $15 per million characters. */
    private static final double STT_PER_SECOND = 0.20 / 3600, TTS_PER_CHAR = 15.0 / 1_000_000;
    private static final int MIC_RATE = 16000, MAX_SAY = 1500;
    private static final long FIRST_WAIT = 8000, FOLLOW_WAIT = 6000, MAX_TURN = 40_000;
    /** Mic level (see loudness) that counts as someone talking rather than room noise. */
    private static final float LOUD = 0.18f;
    static final File TEST_DIR = new File(Paths.ROOT, "voice");
    private static final Pattern TAGS = Pattern.compile(
            "\\[(pause|long-pause|hum-tune|laugh|chuckle|giggle|cry|tsk|tongue-click|lip-smack|breath|inhale|exhale|sigh)]"
                    + "|</?(soft|whisper|loud|build-intensity|decrease-intensity|higher-pitch|lower-pitch|slow|fast|sing-song|singing|emphasis)>",
            Pattern.CASE_INSENSITIVE);
    private static final String[] LISTEN_LINES = {"I'm all ears! Go for it!", "I'm listening, partner!", "What's up? Tell me!"};
    private static final String[] SAMPLES = {
            "Coronamon here! My flames are fired up and ready. Let's burn through whatever comes next!",
            "Hey partner! Whatever's out there, we'll face it together. That's a promise!",
            "This is my voice! Pretty fired up, right? Double-tap me anytime and let's talk!",
    };
    private static final String MIC_MUTED = "Android has my mic muted right now. Try again while I'm on screen!";

    private final PetService s;
    private final Handler main;
    private final Random rnd = new Random();
    private int state = IDLE;
    /** Bumped by every new conversation and every stop, so callbacks from an older one are ignored. */
    private int convo;
    private Listen listen;
    private Speech speech;
    /** The chat message he's answering with, for its buttons. */
    private JSONObject reply;
    /** This conversation is a single turn: no listening again afterwards (coro and the den's voice test). */
    private boolean once, testReply;
    private String[] terms = new String[0];
    private PowerManager.WakeLock awake;
    private AudioFocusRequest focus;
    private String heard = "", error;
    private long rev, thinkAt;
    private volatile float hearLevel;
    /** How the latest turn went: timings, levels and what was heard, for coro voice and the den. */
    private JSONObject last = new JSONObject();

    Voice(PetService s) {
        this.s = s;
        main = s.main;
    }

    boolean active() {
        return state != IDLE;
    }

    boolean micGranted() {
        return s.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Takes speech tags like [laugh] and <whisper> out, for the bubble and the chat. */
    static String clean(String t) {
        if (t == null) return null;
        return TAGS.matcher(t).replaceAll(" ").replaceAll("[ \\t]{2,}", " ").replaceAll(" ([.,!?;:])", "$1").trim();
    }

    // ---- starting and stopping ----

    /** Double-tap, the den or coro: he listens. Returns why he can't, or null. Main thread. */
    String start(String from) {
        if (state != IDLE) return null;
        String why = cant(true);
        if (why != null) return why;
        begin();
        listenNow(false, null);
        Log.i("voice: listening (" + from + ")");
        return null;
    }

    /** coro voice hear: a WAV file stands in for the mic. With reply, he answers it out loud too. */
    String startTest(File f, boolean reply) {
        if (state != IDLE) stop();
        String why = cant(false);
        if (why != null) return why;
        begin();
        once = true;
        testReply = reply;
        listenNow(false, f);
        return null;
    }

    /** Says text in his voice without asking his brain. keep saves the audio for coro to check. */
    String sayOnly(String text, boolean keep) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) return "Nothing to say!";
        if (!GrokKey.has(s)) return "I need an xAI API key for my voice. Add one in my den's Talk tab!";
        if (!s.mind.voiceAllowed()) return "I've used up today's Grok budget, so I'm resting my voice.";
        if (state != IDLE) stop();
        begin();
        once = true;
        speakNow(text, keep);
        return null;
    }

    /** The den's "Hear my voice" button. */
    String sample() {
        return sayOnly(SAMPLES[rnd.nextInt(SAMPLES.length)], false);
    }

    private String cant(boolean mic) {
        if (!s.settings.voice()) return "Voice chat is switched off. Turn it on in my den's Settings!";
        if (!GrokKey.has(s)) return "I need an xAI API key before I can hear you. Add one in my den's Talk tab!";
        if (!s.settings.brain()) return "My Grok brain is switched off, so I can't answer yet. Flip it on in Settings!";
        if (!s.mind.voiceAllowed()) return "I've used up today's Grok budget. Let's talk tomorrow, or raise the limit in Settings!";
        if (s.mind.isBusy()) return "Hang on, I'm still thinking about the last thing!";
        if (mic && !micGranted()) {
            s.askForMic();
            return "I need your OK to use the mic. Say yes and I'm all ears!";
        }
        return null;
    }

    private void begin() {
        convo++;
        error = null;
        heard = "";
        once = testReply = false;
        reply = null;
        last = new JSONObject();
        terms = terms();
        keepAwake();
        takeFocus();
    }

    /** A tap on him or his bubble mid-conversation: done talking, or hush. */
    void tap() {
        if (state == LISTENING && listen != null && !heard.isEmpty()) {
            listen.finish = true;
            s.voiceNote("Got it!");
        } else {
            stop();
        }
    }

    /** Ends the conversation now: a tap, the Stop button, the den, coro, or the screens going off. */
    void stop() {
        if (state == IDLE) return;
        Log.i("voice: stopped");
        end(state == SPEAKING ? 1500 : 0);
    }

    void quit() {
        convo++;
        state = IDLE;
        if (listen != null) listen.cancel = true;
        if (speech != null) speech.cancel();
        VoiceService.stop(s);
        dropFocus();
        if (awake != null && awake.isHeld()) awake.release();
    }

    /** Back to idle. The bubble goes after keepMs, after the usual reading time if keepMs < 0, or right away. */
    private void end(long keepMs) {
        state = IDLE;
        rev++;
        convo++;
        Listen l = listen;
        listen = null;
        if (l != null) l.cancel = true;
        Speech sp = speech;
        speech = null;
        if (sp != null) sp.cancel();
        reply = null;
        VoiceService.stop(s);
        dropFocus();
        if (awake != null && awake.isHeld()) awake.release();
        s.brain.voice(Brain.V_NONE);
        s.voiceRelease(keepMs);
    }

    private void fail(String msg) {
        error = msg;
        Log.w("voice: " + msg);
        s.voiceBubble(msg, null, null, null);
        end(-1);
    }

    // ---- listening ----

    /** Out of his room first if the dimmed screens were all that kept him there, so his bubble shows where he'll be. */
    private void stepOut() {
        if (s.brain.room != null) s.home.check();
    }

    private void listenNow(boolean followUp, File file) {
        state = LISTENING;
        rev++;
        heard = "";
        stepOut();
        s.brain.voice(Brain.V_LISTEN);
        if (followUp) s.voiceNote("I'm listening...");
        else s.voiceBubble(LISTEN_LINES[rnd.nextInt(LISTEN_LINES.length)], "Tap me when you're done.", null, null);
        if (file == null) VoiceService.start(s);
        listen = new Listen(convo, followUp, file, terms);
        new Thread(listen, "voice-in").start();
    }

    /** Words so far, while you talk. */
    private void heard(int id, String text) {
        if (id != convo || state != LISTENING || text.isEmpty()) return;
        heard = text;
        rev++;
        s.voiceNote(quote(text));
    }

    private void listened(int id, Listen l, String text) {
        if (listen == l) listen = null;
        if (listen == null && l.file == null) VoiceService.stop(s);
        Util.put(last, "sttReadyMs", l.readyMs);
        Util.put(last, "listenMs", l.ms);
        Util.put(last, "audioSec", Math.round(l.sent / (l.rate * 2.0) * 10) / 10.0);
        Util.put(last, "peak", Math.round(l.peak * 100) / 100.0);
        Util.put(last, "silenced", l.silenced);
        if (id != convo) return;
        text = text == null ? "" : text.trim();
        if (l.err != null) {
            error = l.err;
            Log.w("voice: " + l.err);
            if (l.followUp) end(2500);
            else fail(l.err);
            return;
        }
        if (text.isEmpty()) {
            if (l.followUp) {
                end(2500);
            } else if (l.file != null) {
                Util.put(last, "heard", "");
                end(0);
            } else {
                s.voiceBubble("Huh? I didn't catch that. Double-tap me and try again!", null, null, null);
                end(4000);
            }
            return;
        }
        heard = text;
        Util.put(last, "heard", text);
        if (l.file != null && !testReply) {
            end(0);
            return;
        }
        think(text);
    }

    private void think(String text) {
        state = THINKING;
        rev++;
        thinkAt = SystemClock.uptimeMillis();
        s.brain.voice(Brain.V_THINK);
        s.voiceBubble("Hmm...", quote(text), null, null);
        try {
            s.mind.ask(text, "voice");
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    // ---- answering ----

    /**
     * His brain's answer to something said out loud: raw is the reply with any speech tags, m its chat
     * message. Returns false if the conversation has moved on, so the answer shows the usual way.
     */
    boolean answered(String raw, String err, JSONObject m) {
        if (state != THINKING) return false;
        Util.put(last, "thinkMs", SystemClock.uptimeMillis() - thinkAt);
        if (raw == null || raw.trim().isEmpty()) {
            fail(err != null ? err : "Hmm... I lost my words. Try again?");
            return true;
        }
        reply = m;
        Util.put(last, "reply", clean(raw));
        speakNow(raw, false);
        return true;
    }

    private void speakNow(String raw, boolean keep) {
        state = SPEAKING;
        rev++;
        stepOut();
        s.brain.voice(Brain.V_SPEAK);
        speech = new Speech(convo, raw, keep);
        new Thread(speech, "voice-out").start();
    }

    /** His voice starts coming out: the words go up in his bubble. */
    private void started(int id, Speech sp) {
        if (id == convo && sp == speech) showReply(sp);
    }

    private void showReply(Speech sp) {
        if (sp.shown) return;
        sp.shown = true;
        String text = bubbleText(clean(sp.text));
        JSONObject a = firstAsk(reply);
        if (a == null) {
            s.voiceBubble(text, null, null, null);
            return;
        }
        String msgId = reply.optString("id"), actId = a.optString("id");
        s.voiceBubble(text, null, new String[]{a.optString("label", "Do it"), "Not now"}, i -> {
            if (i < 0) return;
            try {
                s.mind.confirm(msgId, actId, i == 0);
            } catch (Exception e) {
                Log.w("voice button: " + e.getMessage());
            }
        });
    }

    private void spoken(int id, Speech sp) {
        Util.put(last, "ttsFirstAudioMs", sp.firstAudioMs);
        Util.put(last, "speakMs", sp.ms);
        Util.put(last, "ttsChars", sp.chars);
        Util.put(last, "audioFrames", sp.frames);
        Util.put(last, "sampleRate", sp.rate);
        Util.put(last, "ttsError", sp.err);
        if (id != convo || sp != speech) return;
        speech = null;
        if (!sp.shown) showReply(sp);
        if (sp.cancelled) {
            end(1500);
            return;
        }
        if (sp.err != null) {
            error = sp.err;
            Log.w("voice: " + sp.err);
        }
        boolean asks = firstAsk(reply) != null;
        if (sp.err != null || asks || once || !s.settings.voiceFollowUp()) {
            end(asks || sp.err != null ? -1 : 3500);
            return;
        }
        listenNow(true, null);
    }

    private static JSONObject firstAsk(JSONObject m) {
        JSONArray acts = m == null ? null : m.optJSONArray("actions");
        for (int i = 0; acts != null && i < acts.length(); i++) {
            JSONObject a = acts.optJSONObject(i);
            if (a != null && "asked".equals(a.optString("state"))) return a;
        }
        return null;
    }

    // ---- what the rest of him reads ----

    /** How loud his voice is right now, 0..1, for his mouth; -1 while no audio is playing. */
    float mouth() {
        Speech sp = speech;
        return state == SPEAKING && sp != null ? sp.level() : -1;
    }

    /** How loud you are while he listens, 0..1. */
    float hearing() {
        return state == LISTENING ? hearLevel : 0;
    }

    JSONObject status() {
        return Util.obj("on", s.settings.voice(), "state", STATES[state], "heard", heard, "voice", s.settings.voiceId(),
                "speed", s.settings.voiceSpeed(), "followUp", s.settings.voiceFollowUp(), "mic", micGranted(),
                "error", error, "rev", rev, "last", Util.parse(last.toString()));
    }

    /** The den's diagnostics row. */
    JSONObject check() {
        String name = "Voice chat";
        if (!s.settings.voice()) return Diagnostics.finding(name, "info", "Switched off.", "Den, Settings: Voice chat On.");
        if (!GrokKey.has(s)) return Diagnostics.finding(name, "info", "Needs his Grok brain's xAI API key.", "Den, Talk tab: add a key.");
        if (!micGranted()) {
            return Diagnostics.finding(name, "warn", "No microphone permission yet.",
                    "Double-tap him and allow the mic, or run coro setup on the Mac.");
        }
        if (error != null) return Diagnostics.finding(name, "warn", "Last try: " + error, null);
        return Diagnostics.finding(name, "good", "Double-tap him to talk. His voice: " + s.settings.voiceId() + ".", null);
    }

    // ---- small helpers ----

    /** Names the speech-to-text should expect: his, and the apps he looks after. */
    private String[] terms() {
        Set<String> k = new LinkedHashSet<>();
        k.add("Coronamon");
        k.add("Digimon");
        k.add("Grok");
        k.add("Thor");
        JSONArray apps = s.registry.liveAll();
        for (int i = 0; i < apps.length() && k.size() < 24; i++) {
            JSONObject a = apps.optJSONObject(i);
            String l = a == null ? "" : a.optString("label").trim();
            if (!l.isEmpty() && l.length() <= 40) k.add(l);
        }
        return k.toArray(new String[0]);
    }

    private void keepAwake() {
        if (awake == null) {
            awake = s.getSystemService(PowerManager.class).newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "Coronamon:voice");
            awake.setReferenceCounted(false);
        }
        awake.acquire(10 * 60_000L);
    }

    private static AudioAttributes attrs() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    }

    /** Other sound ducks while you talk to him and while he answers. */
    private void takeFocus() {
        if (focus != null) return;
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs())
                .setOnAudioFocusChangeListener(change -> { }).build();
        s.getSystemService(AudioManager.class).requestAudioFocus(focus);
    }

    private void dropFocus() {
        if (focus == null) return;
        s.getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);
        focus = null;
    }

    private static String quote(String t) {
        t = t.replaceAll("\\s+", " ").trim();
        if (t.length() > 150) t = "…" + t.substring(t.length() - 149);
        return "“" + t + "”";
    }

    private static String bubbleText(String t) {
        t = t.replaceAll("\\s+", " ").trim();
        if (t.length() <= 220) return t;
        int cut = Math.max(t.lastIndexOf(". ", 210), Math.max(t.lastIndexOf("! ", 210), t.lastIndexOf("? ", 210)));
        return (cut > 60 ? t.substring(0, cut + 1) : t.substring(0, 210) + "…") + " (More in my den.)";
    }

    /** Text for the speech: no markdown symbols, and not too long to say. */
    private static String forSpeech(String t, int max) {
        t = t.replaceAll("[*#`]", "").replaceAll("\\s+", " ").trim();
        if (t.length() <= max) return t;
        int cut = Math.max(t.lastIndexOf(". ", max), Math.max(t.lastIndexOf("! ", max), t.lastIndexOf("? ", max)));
        return cut > max / 3 ? t.substring(0, cut + 1) : t.substring(0, max);
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : Bugs.clip(GrokKey.scrub(m), 120);
    }

    /** 0..1 from an RMS level (1 = full scale): about 0.1 for a quiet room, 0.4 to 0.7 for speech. */
    private static float loudness(double rms) {
        return (float) Math.min(1, Math.sqrt(rms) * 1.6);
    }

    private static float loudness16(byte[] b, int n) {
        long sum = 0;
        int count = n / 2;
        for (int i = 0; i + 1 < n; i += 2) {
            int v = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            sum += (long) v * v;
        }
        return count == 0 ? 0 : loudness(Math.sqrt(sum / (double) count) / 32768.0);
    }

    // ---- WAV files ----

    /** Reads a WAV header up to the audio data: {format (1 PCM, 3 float), channels, sample rate, bits}. */
    static int[] wavHeader(InputStream in) throws IOException {
        byte[] h = readN(in, 12);
        if (!"RIFF".equals(ascii(h, 0)) || !"WAVE".equals(ascii(h, 8))) throw new IOException("not a WAV file");
        int[] fmt = null;
        while (true) {
            byte[] c = readN(in, 8);
            String id = ascii(c, 0);
            long size = le32(c, 4) & 0xFFFFFFFFL;
            if ("fmt ".equals(id)) {
                if (size < 16 || size > 1024) throw new IOException("bad WAV format chunk");
                byte[] f = readN(in, (int) size);
                fmt = new int[]{le16(f, 0), le16(f, 2), le32(f, 4), le16(f, 14)};
                if (fmt[0] == 0xFFFE && size >= 26) fmt[0] = le16(f, 24);
                if ((size & 1) == 1) readN(in, 1);
            } else if ("data".equals(id)) {
                if (fmt == null) throw new IOException("WAV audio before its format");
                return fmt;
            } else {
                long skip = size + (size & 1);
                while (skip > 0) {
                    long k = in.skip(skip);
                    if (k <= 0) {
                        if (in.read() < 0) throw new EOFException("WAV ended early");
                        k = 1;
                    }
                    skip -= k;
                }
            }
        }
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int k = in.read(b, off, n - off);
            if (k < 0) throw new EOFException("WAV ended early");
            off += k;
        }
        return b;
    }

    private static String ascii(byte[] b, int off) {
        return new String(b, off, 4, StandardCharsets.US_ASCII);
    }

    private static int le16(byte[] b, int i) {
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8;
    }

    private static int le32(byte[] b, int i) {
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF) << 16 | (b[i + 3] & 0xFF) << 24;
    }

    private static void saveWav(File f, byte[] pcm, int rate, int ch, int bits, boolean flt) {
        File dir = f.getParentFile();
        if (dir != null) dir.mkdirs();
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length).put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) (flt ? 3 : 1)).putShort((short) ch)
                .putInt(rate).putInt(rate * ch * bits / 8).putShort((short) (ch * bits / 8)).putShort((short) bits)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(h.array());
            o.write(pcm);
        } catch (IOException e) {
            Log.w("voice wav: " + e);
        }
    }

    // ---- the bridge ----

    /** coro voice hear: runs on the bridge's thread, waits for the turn to finish, and reports it. */
    static JSONObject forBridgeHear(Context ctx, String path, boolean reply) {
        PetService s = PetService.instance;
        if (s == null) {
            PetService.start(ctx);
            return Util.obj("ok", false, "error", "Coronamon is waking up; try again in a moment");
        }
        File f = new File(path == null ? "" : path);
        if (!f.isFile()) return Util.obj("ok", false, "error", "no such file: " + path);
        try {
            JSONObject r = Bridge.onMain(() -> Util.obj("why", s.voice.startTest(f, reply)));
            if (!r.isNull("why")) return Util.obj("ok", false, "error", r.optString("why"));
            long end = System.currentTimeMillis() + 150_000;
            while (System.currentTimeMillis() < end) {
                Thread.sleep(250);
                JSONObject st = Bridge.onMain(s.voice::status);
                if ("idle".equals(st.optString("state"))) {
                    Util.put(st, "ok", st.isNull("error"));
                    return st;
                }
            }
            return Util.obj("ok", false, "error", "no answer after 150 seconds");
        } catch (Exception e) {
            return Util.obj("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    /** coro voice voices: the voices xAI offers. Bridge thread. */
    static JSONObject forBridgeVoices(Context ctx) {
        String key = GrokKey.get(ctx);
        if (key == null) return Util.obj("ok", false, "error", "no xAI API key yet");
        HttpURLConnection c = null;
        try {
            c = Util.open(VOICES_URL, 15_000);
            c.setRequestProperty("Authorization", "Bearer " + key);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : new String(Util.readAll(in), StandardCharsets.UTF_8);
            if (code != 200) return Util.obj("ok", false, "error", Grok.explain(code, text));
            JSONObject r = Util.parse(text);
            return Util.obj("ok", true, "voices", r.has("voices") ? r.opt("voices") : r);
        } catch (Exception e) {
            return Util.obj("ok", false, "error", describe(e));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- the two halves: hearing you, and talking back ----

    /** Mic (or a test file) to xAI's streaming speech-to-text, until Smart Turn says you're done. */
    private final class Listen implements Runnable, Ws.Listener {
        final int id;
        final boolean followUp;
        final File file;
        final String[] keyterms;
        volatile boolean finish, cancel;
        private final CountDownLatch ready = new CountDownLatch(1);
        private final StringBuilder locked = new StringBuilder();
        private String interim = "";
        private volatile String utterance, failed;
        private volatile boolean done;
        private volatile long spokeAt;
        // Read on the main thread once the run has posted its result.
        String err;
        float peak;
        boolean silenced;
        long readyMs, ms, sent;
        int rate = MIC_RATE;

        Listen(int id, boolean followUp, File file, String[] keyterms) {
            this.id = id;
            this.followUp = followUp;
            this.file = file;
            this.keyterms = keyterms;
        }

        @Override
        public void run() {
            long t0 = SystemClock.uptimeMillis();
            AudioRecord rec = null;
            InputStream wav = null;
            Ws ws = null;
            try {
                String key = GrokKey.get(s);
                if (key == null) throw new IllegalStateException("I lost my API key. Add it again in my den!");
                if (file != null) {
                    wav = new BufferedInputStream(new FileInputStream(file), 32768);
                    int[] f = wavHeader(wav);
                    if (f[0] != 1 || f[1] != 1 || f[3] != 16) throw new IllegalStateException("Test audio has to be a 16-bit mono WAV file");
                    rate = f[2];
                } else {
                    for (int i = 0; i < 40 && !VoiceService.up && !cancel; i++) Thread.sleep(50);
                    rec = openMic();
                }
                ws = Ws.connect(HOST, sttPath(rate, keyterms), key, 10_000, this);
                if (!ready.await(10, TimeUnit.SECONDS)) throw new IOException("xAI's speech-to-text didn't answer");
                if (failed != null) throw new IllegalStateException(failed);
                readyMs = SystemClock.uptimeMillis() - t0;
                byte[] buf = new byte[rate / 5];
                long fileStart = SystemClock.uptimeMillis(), fileBytes = 0, loudAt = 0, endedAt = 0;
                boolean checked = false;
                while (!done && !cancel) {
                    long now = SystemClock.uptimeMillis();
                    if (endedAt > 0) {
                        if (now - endedAt > 5000) break;
                        Thread.sleep(40);
                        continue;
                    }
                    int n;
                    if (wav != null) {
                        long wait = fileStart + fileBytes * 1000 / (rate * 2L) - now;
                        if (wait > 0) Thread.sleep(wait);
                        n = readSome(wav, buf);
                        if (n > 0) fileBytes += n;
                    } else {
                        n = rec.read(buf, 0, buf.length);
                        if (n < 0) throw new IOException("the mic stopped working (" + n + ")");
                    }
                    now = SystemClock.uptimeMillis();
                    if (n > 0) {
                        float lvl = loudness16(buf, n);
                        hearLevel = lvl;
                        if (lvl > peak) peak = lvl;
                        if (lvl > LOUD) loudAt = now;
                        ws.sendBinary(buf, 0, n);
                        sent += n;
                    }
                    if (rec != null && !checked && now - t0 > 1500) {
                        checked = true;
                        AudioRecordingConfiguration rc = rec.getActiveRecordingConfiguration();
                        silenced = rc != null && rc.isClientSilenced();
                        if (silenced) throw new IllegalStateException(MIC_MUTED);
                    }
                    boolean talked = spokeAt > 0;
                    if (n < 0 || finish || now - t0 > MAX_TURN || (talked && now - spokeAt > 8000)) {
                        ws.sendText("{\"type\":\"audio.done\"}");
                        endedAt = now;
                        hearLevel = 0;
                        continue;
                    }
                    if (file == null && !talked && now - t0 > (followUp ? FOLLOW_WAIT : FIRST_WAIT) && now - loudAt > 1500) break;
                }
                if (failed != null && utterance == null) throw new IllegalStateException(failed);
            } catch (IllegalStateException | Grok.Failure e) {
                err = e.getMessage();
            } catch (InterruptedException e) {
                cancel = true;
            } catch (Exception e) {
                err = "I couldn't hear you (" + describe(e) + ")";
            } finally {
                hearLevel = 0;
                if (rec != null) {
                    try {
                        rec.stop();
                    } catch (Exception ignored) {
                    }
                    rec.release();
                }
                if (ws != null) ws.close();
                if (wav != null) {
                    try {
                        wav.close();
                    } catch (IOException ignored) {
                    }
                }
                if (sent > 0) s.mind.accountVoice(sent / (rate * 2.0) * STT_PER_SECOND);
            }
            ms = SystemClock.uptimeMillis() - t0;
            String text = result();
            main.post(() -> listened(id, this, text));
        }

        @Override
        public void text(String msg) {
            JSONObject o = Util.parse(msg);
            if (file != null) Log.i("stt: " + Bugs.clip(msg, 300));
            switch (o.optString("type")) {
                case "transcript.created":
                    ready.countDown();
                    break;
                case "transcript.partial": {
                    String t = o.optString("text").trim();
                    String live;
                    synchronized (this) {
                        if (!t.isEmpty()) spokeAt = SystemClock.uptimeMillis();
                        if (o.optBoolean("speech_final")) {
                            String u = stitched(t);
                            if (!u.isEmpty()) {
                                utterance = u;
                                done = true;
                            }
                            interim = "";
                        } else if (o.optBoolean("is_final")) {
                            if (!t.isEmpty()) locked.append(locked.length() > 0 ? " " : "").append(t);
                            interim = "";
                        } else {
                            interim = t;
                        }
                        live = utterance != null ? utterance : joined();
                    }
                    main.post(() -> heard(id, live));
                    break;
                }
                case "transcript.done": {
                    String t = o.optString("text").trim();
                    synchronized (this) {
                        if (utterance == null) utterance = t.isEmpty() ? joined() : t;
                    }
                    done = true;
                    break;
                }
                case "error": {
                    JSONObject e = o.optJSONObject("error");
                    String m = e != null ? e.optString("message", e.toString()) : o.optString("message", "something went wrong");
                    failed = "xAI's speech-to-text said: " + Bugs.clip(GrokKey.scrub(m), 160);
                    done = true;
                    ready.countDown();
                    break;
                }
                default:
                    break;
            }
        }

        @Override
        public void closed(int code, String reason) {
            if (!done && !cancel && failed == null && code != 1000) {
                failed = "xAI's speech-to-text hung up" + (reason == null || reason.isEmpty() ? "" : " (" + Bugs.clip(GrokKey.scrub(reason), 120) + ")");
            }
            done = true;
            ready.countDown();
        }

        synchronized String result() {
            return utterance != null ? utterance : joined();
        }

        private String joined() {
            return (locked + " " + interim).trim();
        }

        /** The whole utterance: the final event normally has all of it, but not if it only repeats the last part. */
        private String stitched(String t) {
            String before = locked.toString().trim();
            if (before.isEmpty()) return t;
            if (t.isEmpty()) return before;
            return letters(t).startsWith(letters(before)) ? t : before + " " + t;
        }
    }

    private static String letters(String t) {
        return t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String sttPath(int rate, String[] keyterms) {
        StringBuilder q = new StringBuilder("/v1/stt?model=grok-voice-transcribe-2.0&encoding=pcm&sample_rate=").append(rate)
                .append("&language=en&format=true&interim_results=true&smart_turn=0.6&smart_turn_timeout=2500");
        for (String k : keyterms) q.append("&keyterm=").append(Uri.encode(k));
        return q.toString();
    }

    private static AudioRecord openMic() throws IOException {
        int min = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r;
        try {
            // Room for a few seconds, so what you say while the connection opens waits here instead of being lost.
            r = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(MIC_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min, MIC_RATE * 2 * 4)).build();
        } catch (SecurityException e) {
            throw new IllegalStateException("I need your OK to use the mic first. Say yes in my den!");
        } catch (UnsupportedOperationException e) {
            throw new IOException("the mic wouldn't open");
        }
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            throw new IOException("the mic wouldn't open");
        }
        r.startRecording();
        if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            r.release();
            throw new IllegalStateException("Something else is using the mic right now.");
        }
        return r;
    }

    /** Fills buf from a test file as far as it goes; -1 at the end. Whole 16-bit samples only. */
    private static int readSome(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int k = in.read(buf, off, buf.length - off);
            if (k < 0) break;
            off += k;
        }
        off -= off & 1;
        return off == 0 ? -1 : off;
    }

    /** xAI's text-to-speech, streamed into the speaker as it arrives, with a loudness track for his mouth. */
    private final class Speech implements Runnable {
        final int id;
        final String text;
        final boolean keep;
        volatile boolean cancelled;
        /** Main thread: the words are up in his bubble. */
        boolean shown;
        private volatile AudioTrack track;
        /** Loudness per 20 ms of audio, indexed by the playback position. */
        private final float[] levels = new float[50 * 300];
        private volatile int count;
        private int win = 480;
        private double acc;
        private int accN;
        // Read on the main thread once the run has posted its result.
        String err;
        long firstAudioMs, ms;
        int chars, frames, rate;

        Speech(int id, String text, boolean keep) {
            this.id = id;
            this.text = text;
            this.keep = keep;
        }

        float level() {
            AudioTrack t = track;
            if (t == null) return -1;
            try {
                int i = t.getPlaybackHeadPosition() / win;
                return i < count ? levels[i] : 0;
            } catch (Exception e) {
                return -1;
            }
        }

        void cancel() {
            cancelled = true;
            AudioTrack t = track;
            if (t == null) return;
            try {
                t.pause();
                t.flush();
            } catch (Exception ignored) {
            }
        }

        @Override
        public void run() {
            long t0 = SystemClock.uptimeMillis();
            HttpURLConnection c = null;
            AudioTrack t = null;
            ByteArrayOutputStream kept = keep ? new ByteArrayOutputStream() : null;
            int ch = 1, bits = 16;
            boolean flt = false;
            try {
                String key = GrokKey.get(s);
                if (key == null) throw new IllegalStateException("I lost my API key. Add it again in my den!");
                if (!s.mind.voiceAllowed()) throw new IllegalStateException("I've used up today's Grok budget, so I'm resting my voice.");
                String say = forSpeech(text, MAX_SAY);
                JSONObject body = Util.obj("text", say, "voice_id", s.settings.voiceId(), "language", "en",
                        "output_format", Util.obj("codec", "wav", "sample_rate", 24000), "speed", (double) s.settings.voiceSpeed());
                c = Util.open(TTS_URL, 30_000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Authorization", "Bearer " + key);
                byte[] req = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(req.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(req);
                }
                int code = c.getResponseCode();
                if (code != 200) {
                    InputStream es = c.getErrorStream();
                    throw new Grok.Failure(code, Grok.explain(code, es == null ? "" : new String(Util.readAll(es), StandardCharsets.UTF_8)));
                }
                chars = say.length();
                s.mind.accountVoice(chars * TTS_PER_CHAR);
                InputStream in = new BufferedInputStream(c.getInputStream(), 32768);
                if (String.valueOf(c.getContentType()).contains("json")) {
                    JSONObject j = new JSONObject(new String(Util.readAll(in), StandardCharsets.UTF_8));
                    in = new ByteArrayInputStream(Base64.decode(j.optString("audio"), Base64.DEFAULT));
                }
                int[] f = wavHeader(in);
                flt = f[0] == 3;
                ch = f[1];
                rate = f[2];
                bits = f[3];
                if (!((f[0] == 1 && bits == 16) || (flt && bits == 32)) || ch < 1 || ch > 2 || rate < 8000) {
                    throw new IOException("xAI sent audio I can't play (format " + f[0] + ", " + bits + "-bit)");
                }
                int frameBytes = ch * bits / 8;
                win = Math.max(1, rate / 50);
                AudioFormat af = new AudioFormat.Builder().setSampleRate(rate)
                        .setChannelMask(ch == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(flt ? AudioFormat.ENCODING_PCM_FLOAT : AudioFormat.ENCODING_PCM_16BIT).build();
                int min = AudioTrack.getMinBufferSize(rate, af.getChannelMask(), af.getEncoding());
                // Half a second or more: playback starts at a quarter second, before a write could block.
                t = new AudioTrack.Builder().setAudioAttributes(attrs()).setAudioFormat(af).setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(Math.max(min * 2, rate * frameBytes / 2)).build();
                byte[] buf = new byte[rate / 10 * frameBytes];
                int have = 0;
                long written = 0;
                boolean playing = false;
                while (!cancelled) {
                    int n = in.read(buf, have, buf.length - have);
                    if (n < 0) break;
                    have += n;
                    int whole = have - have % frameBytes;
                    if (whole == 0) continue;
                    measure(buf, whole, frameBytes, ch, flt);
                    if (kept != null) kept.write(buf, 0, whole);
                    for (int off = 0; off < whole && !cancelled; ) {
                        int w = t.write(buf, off, whole - off);
                        if (w < 0) throw new IOException("the speaker said no (" + w + ")");
                        off += w;
                    }
                    written += whole / frameBytes;
                    System.arraycopy(buf, whole, buf, 0, have - whole);
                    have -= whole;
                    if (!playing && !cancelled && written >= rate / 4) {
                        playing = true;
                        play(t, t0);
                    }
                }
                if (!cancelled && written > 0) {
                    if (!playing) play(t, t0);
                    long until = SystemClock.uptimeMillis() + written * 1000 / rate + 2500;
                    while (!cancelled && t.getPlaybackHeadPosition() < written && SystemClock.uptimeMillis() < until) Thread.sleep(20);
                    Thread.sleep(120);
                }
                frames = (int) written;
                if (written == 0 && !cancelled) throw new IOException("xAI sent no audio");
            } catch (IllegalStateException | Grok.Failure e) {
                err = e.getMessage();
            } catch (InterruptedException e) {
                cancelled = true;
            } catch (Exception e) {
                err = "My voice didn't come out (" + describe(e) + ")";
            } finally {
                track = null;
                if (t != null) {
                    try {
                        t.stop();
                    } catch (Exception ignored) {
                    }
                    t.release();
                }
                if (c != null) c.disconnect();
            }
            ms = SystemClock.uptimeMillis() - t0;
            if (kept != null && kept.size() > 0) saveWav(new File(TEST_DIR, "last.wav"), kept.toByteArray(), rate, ch, bits, flt);
            main.post(() -> spoken(id, this));
        }

        private void play(AudioTrack t, long t0) {
            t.play();
            track = t;
            firstAudioMs = SystemClock.uptimeMillis() - t0;
            main.post(() -> started(id, this));
        }

        private void measure(byte[] b, int len, int frameBytes, int ch, boolean flt) {
            int step = flt ? 4 : 2;
            for (int i = 0; i + frameBytes <= len; i += frameBytes) {
                double v = 0;
                for (int k = 0; k < ch; k++) {
                    int j = i + k * step;
                    v += flt ? Float.intBitsToFloat(le32(b, j)) : (short) ((b[j] & 0xFF) | (b[j + 1] << 8)) / 32768.0;
                }
                v /= ch;
                acc += v * v;
                if (++accN >= win) {
                    if (count < levels.length) {
                        levels[count] = loudness(Math.sqrt(acc / accN));
                        count++;
                    }
                    acc = 0;
                    accN = 0;
                }
            }
        }
    }
}
