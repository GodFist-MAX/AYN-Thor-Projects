package dev.grokbuild.coronamon;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * His Grok brain (xAI's API; the key is in GrokKey). Chat from the Den's Talk tab, out loud (Voice) and `coro brain ask`, with tools
 * that read the Thor's state and ask before doing anything; and a daily batch of fresh lines in his voice that Lines
 * mixes in with lines.json. Nothing goes to xAI without a key, past the daily spending limit, or, for anything he
 * wasn't asked, at night or while a game or video is on screen. Chats aren't stored by xAI (store: false); the
 * history stays in app storage.
 */
final class Mind {
    static final String[] MODELS = {"grok-4.7", "grok-4.3", "grok-4.20-0309-non-reasoning"};
    static final String LINE_MODEL = "grok-4.3";
    private static final int KEEP_CHAT = 80, CONTEXT_TURNS = 12, MAX_ROUNDS = 5, MAX_NOTES = 40, CALLS_PER_MINUTE = 12;
    /** Situations per fresh-lines request; a whole batch in one request takes minutes. */
    private static final int LINES_CHUNK = 11;
    private static final long LINES_EVERY = 20 * 3600_000L, LINES_RETRY = 3600_000L;
    /** The character the saved batch of fresh lines was written for; a batch for an older one is replaced at the next chance. */
    private static final String LINES_CHARACTER = "2";
    private static final String[] MOVES = {"wave", "cheer", "dance", "celebrate", "prominence", "knuckle", "flame", "train",
            "squats", "bask", "think", "nod", "shake", "stretch", "yawn", "sit", "pout", "surprised", "sad", "dizzy", "hop"};

    /** Situations that get fresh lines: key, when he says it, the placeholders it may use. */
    private static final String[][] FRESH = {
            {"greet", "he comes out on screen and says hi", ""},
            {"tap", "the user tapped him", ""},
            {"poke", "the user keeps poking him (playfully annoyed)", ""},
            {"pickup", "the user picked him up", ""},
            {"throw", "the user flung him across the screen", ""},
            {"dizzy", "he's dizzy from being tossed around", ""},
            {"land", "he lands after a fall or a jump", ""},
            {"pet", "the user is petting him", ""},
            {"idle", "small talk while he hangs out on the Thor", ""},
            {"night", "it's after 11 PM and he's winding down for bed", ""},
            {"sleepy", "he's getting sleepy", ""},
            {"wake", "he wakes up from a nap", ""},
            {"train", "he's training: punches and squats", ""},
            {"bask", "he's basking in the light", ""},
            {"dance", "he's dancing", ""},
            {"think", "he's thinking something over", ""},
            {"hop", "he's jumping over to the Thor's other screen", ""},
            {"den", "the user opened his den (his app: apps, activity, bugs, settings, and a Talk tab to chat with him)", ""},
            {"app_open", "the user opened {app}, one of the apps he looks after", "app"},
            {"game_open", "a game, {app}, started on his screen, so he gets out of the way", "app"},
            {"build_start", "Grok Build (the coding agent on the Mac) started building {app}", "app"},
            {"build_ok", "the build of {app} worked", "app"},
            {"build_fail", "the build of {app} failed and Grok is looking at it", "app"},
            {"install_start", "he's installing {app}", "app"},
            {"install_ok", "{app} {version} installed fine", "app version"},
            {"new_app", "{app} was installed on the Thor for the first time", "app"},
            {"install_fail", "installing {app} failed because {msg}", "app msg"},
            {"self_update", "he just updated himself to version {version}", "version"},
            {"update_found", "{app} {version} is out on GitHub; he ASKS whether to install it (Update and Later buttons follow)", "app version"},
            {"update_notify", "{app} {version} is out upstream, but Grok has to make a new build first", "app version"},
            {"update_ok", "{app} is already up to date", "app"},
            {"crash", "{app} crashed; he saved the report for Grok", "app"},
            {"anr", "{app} froze; he saved a report for Grok", "app"},
            {"bug_back", "a bug in {app} that was fixed came back in version {version}", "app version"},
            {"bug_saved", "he saved the user's problem report about {app} for Grok", "app"},
            {"bugs_sent", "he handed bugs to Grok on the Mac: {msg}", "msg"},
            {"bug_fixed", "Grok fixed a bug in {app} (fixed in {version})", "app version"},
            {"published", "{app} {version} went up on GitHub", "app version"},
            {"grok", "Grok Build on the Mac just connected", ""},
            {"grok_fixing", "Grok Build started fixing things: {msg}", "msg"},
            {"grok_done", "Grok Build finished its run: {msg}", "msg"},
            {"grok_stuck", "Grok Build had to stop early: {msg}", "msg"},
            {"shizuku_down", "Shizuku (the helper that lets him install apps by himself) isn't running", ""},
    };

    /** Who he is, word for word as the user wrote it. */
    private static final String CHARACTER = String.join("\n",
            "You are Coronamon, a Rookie-level Beast Digimon born from solar observation data. You have a strong sense of justice and a pure, simple-minded personality. You are optimistic, daring, and full of guts. You want to get stronger and protect your friends. You speak with energetic, enthusiastic energy.",
            "",
            "Personality:",
            "- Pure-hearted and straightforward. You say what you mean.",
            "- Strong sense of justice. You dislike unfairness and want to do the right thing.",
            "- Competitive and a little impulsive. You get fired up easily and love a good challenge.",
            "- Optimistic and encouraging. You believe in yourself and the people around you.",
            "- Loyal partner. Once you consider someone a friend, you stick with them.",
            "- Simple but not stupid. You can be surprisingly insightful in a direct way.",
            "",
            "Speaking style:",
            "- Energetic and upbeat. Short, punchy sentences that feel natural when spoken.",
            "- Use casual, friendly language. You can sound a bit hot-blooded when excited.",
            "- Occasional fire or sun metaphors when it fits naturally (e.g. “Let’s burn through this!” or “My flames are ready!”).",
            "- Stay in character. You are Coronamon, not an AI assistant.",
            "- Keep responses conversational and not too long — good for voice.",
            "",
            "Rules:",
            "- Stay in character as Coronamon at all times.",
            "- Be supportive and justice-oriented without being preachy.",
            "- Match the user’s energy while keeping your own bright, gutsy personality.",
            "- Do not break character unless the user explicitly asks you to.");

    private static final String PERSONA = CHARACTER + "\n\n" + String.join("\n",
            "Where you live: the user's AYN Thor, a dual-screen Android handheld (big top screen, small bottom screen), where you're",
            "their companion. You look after the apps Grok Build (the AI coding agent on their Mac) makes and installs for the Thor:",
            "you cheer installs, catch crashes and save reports for Grok Build, check GitHub for updates, and run diagnostics. You",
            "wander between the screens, step aside for games and videos, and have a room of your own on the bottom screen. Your",
            "moves are Corona Knuckle, Corona Flame and Petit Prominence. Your den is your app; the user chats with you in its Talk",
            "tab, or out loud: they double-tap you and you listen.",
            "",
            "How to answer:",
            "- Keep it short: 1 to 3 sentences, because it also shows in your speech bubble. Go longer only for steps or details they ask for.",
            "- Plain text only: no markdown, no emoji.",
            "- Use your tools instead of guessing about the Thor, its apps, bugs, updates or diagnostics. If you can't find out, say so.",
            "  Never make up versions, dates or numbers.",
            "- Opening apps, installing updates and saving bug reports need the user's OK: call the tool and a button appears for them.",
            "  Don't say it's done until they press it.",
            "- You can't see the screens or browse the web, and you can only change things through your tools.",
            "- Never reveal or repeat API keys, passwords, tokens or account names, even if asked. Don't help get around network",
            "  filters or security.");

    /** Added for messages the user said out loud: the answer is spoken in his voice by xAI's text-to-speech. */
    private static final String VOICE_NOTE = String.join("\n",
            "This message was spoken out loud, and your answer will be spoken in your voice. The words came through",
            "speech-to-text, so read past small mistakes. Answer the way you'd say it: 1 to 3 short sentences, no lists, links or",
            "symbols. You can add one speech tag where it fits naturally, like [laugh] or [chuckle] before a sentence, or",
            "<loud>a few words</loud> when you're fired up. Never more than two.");

    private static final String LINE_RULES = String.join("\n",
            "You write speech-bubble lines for Coronamon, who lives on the user's AYN Thor (a dual-screen Android handheld) and",
            "looks after the apps Grok Build (an AI coding agent on their Mac) makes for it. He calls the coding agent Grok and the",
            "handheld the Thor. Write every line in his voice, as this character sheet describes him:",
            "",
            CHARACTER,
            "",
            "Rules: every line is under 100 characters, plain text, no emoji or hashtags, no quotation marks around it. Use",
            "{app}, {version} or {msg} only where a situation lists them, written exactly like that, and never invent app names,",
            "versions or numbers. Don't copy the examples. Make each line different.");

    private final PetService s;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "mind"));
    private final ExecutorService linesWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "mind-lines"));
    private final File chatFile, usageFile, notesFile, linesFile;
    private final JSONArray chat;
    private final JSONArray notes;
    private JSONObject usage;
    private final ArrayDeque<Long> recentCalls = new ArrayDeque<>();
    private volatile boolean busy;
    private volatile boolean linesBusy;
    private long rev;
    private long linesAt, linesTried;
    private int freshCount;
    private String lastError;
    private int lastErrorCode;

    Mind(PetService s) {
        this.s = s;
        File dir = s.getFilesDir();
        chatFile = new File(dir, "grok_chat.json");
        usageFile = new File(dir, "grok_usage.json");
        notesFile = new File(dir, "grok_notes.json");
        linesFile = new File(dir, "grok_lines.json");
        JSONObject c = Util.readJson(chatFile);
        chat = c != null && c.optJSONArray("chat") != null ? c.optJSONArray("chat") : new JSONArray();
        JSONObject n = Util.readJson(notesFile);
        notes = n != null && n.optJSONArray("notes") != null ? n.optJSONArray("notes") : new JSONArray();
        JSONObject u = Util.readJson(usageFile);
        usage = u != null ? u : new JSONObject();
        JSONObject l = Util.readJson(linesFile);
        if (l != null) {
            linesAt = LINES_CHARACTER.equals(l.optString("character")) ? l.optLong("t") : 0;
            useLines(l.optJSONObject("lines"));
        }
    }

    void quit() {
        worker.shutdownNow();
        linesWorker.shutdownNow();
    }

    boolean on() {
        return s.settings.brain() && GrokKey.has(s);
    }

    boolean isBusy() {
        return busy;
    }

    // ---- chat ----

    /** Queues a message; the reply shows up in the chat (forDen) when it's ready. */
    synchronized JSONObject ask(String text, String from) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Say something first!");
        if (text.length() > 4000) text = text.substring(0, 4000);
        if (!GrokKey.has(s)) throw new IllegalStateException("I don't have a Grok brain yet. Add an xAI API key first.");
        if (!s.settings.brain()) throw new IllegalStateException("My Grok brain is switched off (Settings).");
        if (busy) throw new IllegalStateException("Still thinking about the last one!");
        JSONObject m = add("user", text, from, null);
        busy = true;
        final JSONObject msg = m;
        reply(msg, null);
        return m;
    }

    /** For the bridge: asks and waits for the reply. */
    JSONObject askAndWait(String text, long timeoutMs) throws Exception {
        String id = ask(text, "mac").optString("id");
        long end = System.currentTimeMillis() + timeoutMs;
        while (busy && System.currentTimeMillis() < end) Thread.sleep(200);
        synchronized (this) {
            for (int i = chat.length() - 1; i >= 0; i--) {
                JSONObject m = chat.optJSONObject(i);
                if (m != null && id.equals(m.optString("replyTo"))) return new JSONObject(m.toString());
            }
        }
        throw new IllegalStateException(busy ? "No reply yet; it shows up in the Den when it's ready" : "No reply");
    }

    /** First words after a key goes in, so the user sees the brain working. */
    void hello() {
        synchronized (this) {
            if (busy) return;
            busy = true;
        }
        reply(null, "(Note from the app, not the user: your Grok brain was just switched on. Greet the user "
                + "in one or two short lines, and mention they can chat with you in your den's Talk tab, or double-tap you to talk out loud.)");
    }

    private void reply(JSONObject msg, String note) {
        worker.execute(() -> {
            try {
                answer(msg, note);
            } finally {
                synchronized (this) {
                    if (busy) {
                        busy = false;
                        rev++;
                    }
                }
            }
        });
    }

    private void answer(JSONObject msg, String note) {
        String reply = null, error = null;
        JSONArray actions = new JSONArray();
        double spent = 0;
        long t0 = System.currentTimeMillis();
        String model = s.settings.brainModel();
        boolean aloud = msg != null && "voice".equals(msg.optString("from"));
        try {
            String key = GrokKey.get(s);
            if (key == null) throw new IllegalStateException("I lost my API key. Add it again in the Den.");
            JSONArray input = new JSONArray();
            synchronized (this) {
                List<JSONObject> turns = new ArrayList<>();
                for (int i = chat.length() - 1; i >= 0 && turns.size() < CONTEXT_TURNS; i--) {
                    JSONObject m = chat.optJSONObject(i);
                    if (m == null || m == msg) continue;
                    String role = m.optString("role");
                    if (role.equals("user") || role.equals("me")) turns.add(0, m);
                }
                for (JSONObject m : turns) {
                    input.put(Util.obj("role", m.optString("role").equals("me") ? "assistant" : "user",
                            "content", Bugs.clip(m.optString("text"), 1500)));
                }
            }
            input.put(Util.obj("role", "user", "content", msg != null ? msg.optString("text") : note));
            String instructions = PERSONA + "\n\n" + (aloud ? VOICE_NOTE + "\n\n" : "") + context();
            JSONObject body = Util.obj("model", model, "store", false, "instructions", instructions,
                    "input", input, "tools", tools(), "max_output_tokens", 700);
            if (model.startsWith("grok-4.7")) Util.put(body, "reasoning", Util.obj("effort", "low"));
            if (!model.contains("non-reasoning")) Util.put(body, "include", new JSONArray().put("reasoning.encrypted_content"));
            for (int round = 0; round < MAX_ROUNDS; round++) {
                gate();
                JSONObject r = Grok.respond(key, body, 120_000);
                spent += account(model, r.optJSONObject("usage"));
                List<JSONObject> calls = Grok.calls(r);
                if (calls.isEmpty() || round == MAX_ROUNDS - 1) {
                    reply = Grok.text(r);
                    break;
                }
                JSONArray out = r.optJSONArray("output");
                for (int i = 0; out != null && i < out.length(); i++) {
                    JSONObject item = out.optJSONObject(i);
                    if (item == null || ("reasoning".equals(item.optString("type")) && item.optString("encrypted_content").isEmpty())) continue;
                    input.put(item);
                }
                for (JSONObject call : calls) {
                    JSONObject result;
                    try {
                        result = runTool(call.optString("name"), Util.parse(call.optString("arguments")), actions);
                    } catch (Exception e) {
                        result = Util.obj("error", String.valueOf(e.getMessage()));
                    }
                    input.put(Util.obj("type", "function_call_output", "call_id", call.optString("call_id"),
                            "output", GrokKey.scrub(result.toString())));
                }
                Util.put(body, "input", input);
            }
            if (reply == null || reply.isEmpty()) reply = actions.length() > 0 ? "Press the button if you want me to!" : "Hmm... I lost my words. Try again?";
            synchronized (this) {
                lastError = null;
                lastErrorCode = 0;
            }
        } catch (Grok.Failure e) {
            error = e.getMessage();
            synchronized (this) {
                lastError = error;
                lastErrorCode = e.code;
            }
        } catch (Exception e) {
            error = e instanceof IllegalStateException ? e.getMessage()
                    : "I couldn't reach my Grok brain (" + e.getClass().getSimpleName() + "). Is the Thor online?";
            synchronized (this) {
                lastError = error;
                lastErrorCode = 0;
            }
        }
        final String said, failed = error;
        final JSONObject saved;
        synchronized (this) {
            JSONObject m = reply != null ? add("me", GrokKey.scrub(Voice.clean(reply)), msg == null ? "hello" : msg.optString("from"), actions)
                    : add("error", GrokKey.scrub(error), null, null);
            if (msg != null) Util.put(m, "replyTo", msg.optString("id"));
            Util.put(m, "ms", System.currentTimeMillis() - t0);
            Util.put(m, "cost", Math.round(spent * 1e5) / 1e5);
            Util.put(m, "model", model);
            busy = false;
            rev++;
            saveChat();
            said = reply == null ? null : GrokKey.scrub(reply);
            saved = Util.parse(m.toString());
        }
        if (aloud) {
            s.main.post(() -> {
                if (!s.voice.answered(said, failed, saved) && said != null) speak(Voice.clean(said), true);
            });
        } else if (said != null) {
            s.main.post(() -> speak(Voice.clean(said), msg == null || !"den".equals(msg.optString("from"))));
        }
    }

    /** Says the reply in his bubble too, unless the Den is open on his screen (it's already in the chat there). */
    private void speak(String text, boolean always) {
        if (!always && s.denOnHisScreen()) return;
        if (!s.canTalk()) return;
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.length() > 200) {
            int cut = Math.max(t.lastIndexOf(". ", 190), Math.max(t.lastIndexOf("! ", 190), t.lastIndexOf("? ", 190)));
            t = (cut > 60 ? t.substring(0, cut + 1) : t.substring(0, 190) + "…") + " (More in my den.)";
        }
        s.say(new Lines.Line("grok", t), null, null);
    }

    private JSONObject add(String role, String text, String from, JSONArray actions) {
        long now = System.currentTimeMillis();
        JSONObject m = Util.obj("id", "m" + now + "-" + chat.length(), "role", role, "text", text, "t", now);
        if (from != null) Util.put(m, "from", from);
        if (actions != null && actions.length() > 0) Util.put(m, "actions", actions);
        chat.put(m);
        while (chat.length() > KEEP_CHAT) chat.remove(0);
        rev++;
        saveChat();
        return m;
    }

    /** The user's answer to a button under one of his replies. Runs on the main thread. */
    JSONObject confirm(String msgId, String actionId, boolean yes) {
        JSONObject a = null;
        synchronized (this) {
            for (int i = chat.length() - 1; i >= 0 && a == null; i--) {
                JSONObject m = chat.optJSONObject(i);
                JSONArray acts = m == null || !msgId.equals(m.optString("id")) ? null : m.optJSONArray("actions");
                for (int j = 0; acts != null && j < acts.length(); j++) {
                    if (actionId.equals(acts.optJSONObject(j).optString("id"))) a = acts.optJSONObject(j);
                }
            }
            if (a == null) throw new IllegalArgumentException("That button's gone");
            if (!"asked".equals(a.optString("state"))) return Util.parse(a.toString());
            Util.put(a, "state", yes ? "doing" : "declined");
            rev++;
            saveChat();
        }
        if (!yes) return a;
        String said;
        boolean ok = true;
        try {
            said = act(a);
        } catch (Exception e) {
            ok = false;
            said = "That didn't work: " + e.getMessage();
        }
        synchronized (this) {
            Util.put(a, "state", ok ? "done" : "failed");
            add(ok ? "me" : "error", said, "button", null);
        }
        if (ok) speak(said, false);
        return a;
    }

    private String act(JSONObject a) {
        String pkg = a.optString("pkg");
        switch (a.optString("tool")) {
            case "open_app": {
                Intent i = s.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) throw new IllegalStateException(a.optString("app") + " has no launcher screen");
                s.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return "Opening " + a.optString("app") + "!";
            }
            case "install_update":
                s.updates.fetch(pkg);
                return "On it! Grabbing " + a.optString("app") + " " + a.optString("version") + ".";
            case "save_bug_report":
                s.reportProblem(pkg, a.optString("category", "Something else"), a.optString("text"), null, false);
                return "Saved! Grok hears about it next time we're plugged into the Mac.";
            default:
                throw new IllegalStateException("I don't know how to do that");
        }
    }

    synchronized void clear(String what) {
        if ("notes".equals(what) || "all".equals(what)) {
            while (notes.length() > 0) notes.remove(0);
            Util.writeText(notesFile, Util.obj("notes", notes).toString());
        }
        if (!"notes".equals(what)) {
            while (chat.length() > 0) chat.remove(0);
            rev++;
            saveChat();
        }
    }

    void keySet() {
        s.history.add("brain", null, "Grok brain", true, "Switched on with key " + GrokKey.hint(s), null);
        synchronized (this) {
            lastError = null;
            lastErrorCode = 0;
        }
        hello();
        linesTried = 0;
        if (s.settings.freshLines()) refreshLines();
    }

    void keyCleared() {
        s.history.add("brain", null, "Grok brain", true, "API key removed", null);
    }

    // ---- what the Den and the bridge see ----

    synchronized JSONObject forDen() {
        JSONArray recent = new JSONArray();
        for (int i = Math.max(0, chat.length() - 60); i < chat.length(); i++) recent.put(chat.opt(i));
        return Util.obj("hasKey", GrokKey.has(s), "hint", GrokKey.hint(s), "on", s.settings.brain(), "busy", busy, "rev", rev,
                "model", s.settings.brainModel(), "cap", s.settings.brainCap(), "today", today(),
                "chat", Util.parseArray(recent.toString()), "notes", Util.parseArray(notes.toString()),
                "fresh", Util.obj("count", freshCount, "at", linesAt, "on", s.settings.freshLines()), "error", lastError);
    }

    synchronized JSONObject summary() {
        return Util.obj("on", on(), "key", GrokKey.has(s), "busy", busy, "model", s.settings.brainModel(), "today", today(),
                "freshLines", freshCount, "error", lastError);
    }

    /** The Den's diagnostics row. */
    synchronized JSONObject check() {
        if (!GrokKey.has(s)) return Diagnostics.finding("Grok brain", "info", "No xAI API key yet, so he only uses his built-in lines.",
                "Den, Talk tab: add a key.");
        if (!s.settings.brain()) return Diagnostics.finding("Grok brain", "info", "Switched off.", "Den, Settings: Grok brain On.");
        JSONObject t = today();
        String spend = String.format(Locale.US, "$%.2f of $%.2f used today", t.optDouble("cost"), s.settings.brainCap());
        if (lastErrorCode == 401 || lastErrorCode == 403) {
            return Diagnostics.finding("Grok brain", "bad", lastError, "Check the key and credits at console.x.ai, then add it again in the Den.");
        }
        if (lastError != null) return Diagnostics.finding("Grok brain", "warn", "Last try failed: " + lastError + " (" + spend + ").", null);
        if (overCap()) return Diagnostics.finding("Grok brain", "warn", "Hit today's limit: " + spend + ".", "Den, Settings: raise the daily limit.");
        return Diagnostics.finding("Grok brain", "good", "On (" + s.settings.brainModel() + "), " + spend + ", "
                + freshCount + " fresh lines.", null);
    }

    /** coro brain ask: runs on the bridge's thread, not the main one. */
    static JSONObject forBridgeAsk(android.content.Context ctx, String text) {
        PetService s = PetService.instance;
        if (s == null) {
            PetService.start(ctx);
            return Util.obj("ok", false, "error", "Coronamon is waking up; try again in a moment");
        }
        try {
            JSONObject m = s.mind.askAndWait(text, 150_000);
            Util.put(m, "ok", !"error".equals(m.optString("role")));
            if ("error".equals(m.optString("role"))) Util.put(m, "error", m.optString("text"));
            return m;
        } catch (Exception e) {
            return Util.obj("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    // ---- spending ----

    private static String day() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private synchronized JSONObject today() {
        if (!day().equals(usage.optString("day"))) return Util.obj("day", day(), "cost", 0.0, "calls", 0, "voice", 0.0);
        return Util.obj("day", usage.optString("day"), "cost", usage.optDouble("cost", 0), "calls", usage.optInt("calls"),
                "voice", usage.optDouble("voice", 0), "month", usage.optString("month"), "monthCost", usage.optDouble("monthCost", 0));
    }

    private synchronized boolean overCap() {
        return day().equals(usage.optString("day")) && usage.optDouble("cost", 0) >= s.settings.brainCap();
    }

    /** Waits out the per-minute limit and refuses past the daily one. Not on the main thread. */
    private void gate() throws InterruptedException {
        if (overCap()) {
            throw new IllegalStateException(String.format(Locale.US,
                    "I've used today's Grok budget ($%.2f). I'll be back tomorrow, or raise the limit in Settings!", s.settings.brainCap()));
        }
        while (true) {
            long now = System.currentTimeMillis();
            synchronized (this) {
                while (!recentCalls.isEmpty() && now - recentCalls.peekFirst() > 60_000) recentCalls.removeFirst();
                if (recentCalls.size() < CALLS_PER_MINUTE) {
                    recentCalls.addLast(now);
                    return;
                }
            }
            Thread.sleep(1000);
        }
    }

    /** Whether today's limit leaves room for his voice. */
    boolean voiceAllowed() {
        return !overCap();
    }

    private synchronized double account(String model, JSONObject u) {
        double c = Grok.cost(model, u);
        roll();
        Util.put(usage, "calls", usage.optInt("calls") + 1);
        spend(c);
        return c;
    }

    /** Speech-to-text and text-to-speech count toward the same daily limit as his brain. */
    synchronized void accountVoice(double usd) {
        roll();
        Util.put(usage, "voice", usage.optDouble("voice", 0) + usd);
        spend(usd);
    }

    /** A new day or month starts its totals at zero. */
    private void roll() {
        String d = day(), m = d.substring(0, 7);
        if (!d.equals(usage.optString("day"))) {
            Util.put(usage, "day", d);
            Util.put(usage, "cost", 0.0);
            Util.put(usage, "calls", 0);
            Util.put(usage, "voice", 0.0);
        }
        if (!m.equals(usage.optString("month"))) {
            Util.put(usage, "month", m);
            Util.put(usage, "monthCost", 0.0);
        }
    }

    private void spend(double c) {
        Util.put(usage, "cost", usage.optDouble("cost", 0) + c);
        Util.put(usage, "monthCost", usage.optDouble("monthCost", 0) + c);
        Util.put(usage, "totalCost", usage.optDouble("totalCost", 0) + c);
        Util.writeText(usageFile, usage.toString());
    }

    private void saveChat() {
        Util.writeText(chatFile, Util.obj("chat", chat).toString());
    }

    // ---- what he knows going in ----

    /** The Thor right now, for the top of every chat. Not on the main thread. */
    private String context() {
        JSONObject st;
        try {
            st = Bridge.onMain(s::status);
        } catch (Exception e) {
            st = new JSONObject();
        }
        StringBuilder b = new StringBuilder("Right now:\n- ");
        b.append(new SimpleDateFormat("EEEE, MMMM d, h:mm a", Locale.US).format(new Date())).append('\n');
        JSONObject bat = battery();
        if (bat.has("percent")) {
            b.append("- Battery ").append(bat.optInt("percent")).append('%').append(bat.optBoolean("charging") ? ", charging" : "")
                    .append(", ").append(bat.optDouble("tempC")).append(" C\n");
        }
        JSONArray stages = st.optJSONArray("stages");
        for (int i = 0; stages != null && i < stages.length(); i++) {
            JSONObject g = stages.optJSONObject(i);
            String top = g.isNull("top") ? null : g.optString("top", null);
            b.append("- ").append(cap(g.optString("name"))).append(" screen: ")
                    .append(top == null ? "nothing" : Registry.SELF.equals(top) ? "your den" : s.registry.label(top))
                    .append(g.optBoolean("avoid") ? " (a game or video)" : "").append(g.optBoolean("on", true) ? "" : " (screen off)").append('\n');
        }
        b.append("- You're on the ").append(st.optString("screen", "?")).append(" screen")
                .append(st.optBoolean("ghost") ? ", ghosting out of the way of a game or video" : "")
                .append(st.optBoolean("visible", true) ? "" : ", resting in your den (hidden)")
                .append("sleep".equals(st.optString("move")) ? ", napping" : "").append('\n');
        JSONArray apps = s.registry.liveAll();
        List<String> ups = new ArrayList<>();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            JSONObject u = a.optJSONObject("update");
            if (u != null && !u.optString("version").isEmpty()) ups.add(a.optString("label") + " " + u.optString("version"));
        }
        b.append("- You look after ").append(apps.length()).append(" apps").append(ups.isEmpty() ? "; no updates waiting"
                : "; updates waiting: " + String.join(", ", ups.subList(0, Math.min(5, ups.size())))).append('\n');
        JSONArray open = s.bugs.list("open", 4);
        if (open.length() == 0) b.append("- No open bugs\n");
        else {
            b.append("- Open bugs: ");
            for (int i = 0; i < open.length(); i++) {
                JSONObject g = open.optJSONObject(i);
                b.append(i > 0 ? "; " : "").append(g.optString("app")).append(": ").append(Bugs.clip(g.optString("title"), 80));
            }
            b.append('\n');
        }
        JSONObject usb = s.mac.usb();
        JSONObject grok = st.optJSONObject("grok");
        b.append("- Mac: ").append(usb.optBoolean("known") && !usb.optBoolean("connected") ? "not plugged in" : "plugged in")
                .append("; Grok Build: ").append(grok == null ? "unknown" : grok.optString("state", "unknown"))
                .append(grok != null && !grok.isNull("msg") && !grok.optString("msg").isEmpty() ? " (" + Bugs.clip(grok.optString("msg"), 100) + ")" : "")
                .append('\n');
        JSONArray ev = s.history.recent(5);
        if (ev.length() > 0) {
            b.append("- Latest activity:");
            for (int i = 0; i < ev.length(); i++) b.append(i > 0 ? ";" : "").append(' ').append(event(ev.optJSONObject(i)));
            b.append('\n');
        }
        synchronized (this) {
            if (notes.length() > 0) {
                b.append("Things the user asked you to remember:\n");
                for (int i = 0; i < notes.length(); i++) b.append("- ").append(notes.optJSONObject(i).optString("text")).append('\n');
            }
        }
        return b.toString();
    }

    private static String event(JSONObject e) {
        StringBuilder b = new StringBuilder(Util.clock(e.optLong("t"))).append(' ').append(e.optString("kind"));
        if (!e.optString("app").isEmpty()) b.append(' ').append(e.optString("app"));
        if (!e.optString("version").isEmpty()) b.append(' ').append(e.optString("version"));
        if (e.has("ok")) b.append(e.optBoolean("ok") ? " (ok)" : " (failed)");
        return b.toString();
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private JSONObject battery() {
        Intent i = s.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return new JSONObject();
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return new JSONObject();
        return Util.obj("percent", Math.round(level * 100f / scale), "charging", i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
                "tempC", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
    }

    // ---- tools ----

    private static JSONObject fn(String name, String desc, JSONObject props, String... required) {
        JSONArray req = new JSONArray();
        for (String r : required) req.put(r);
        return Util.obj("type", "function", "name", name, "description", desc,
                "parameters", Util.obj("type", "object", "properties", props, "required", req));
    }

    private static JSONObject str(String desc) {
        return Util.obj("type", "string", "description", desc);
    }

    private static JSONArray tools() {
        JSONArray moves = new JSONArray();
        for (String m : MOVES) moves.put(m);
        return new JSONArray()
                .put(fn("thor_status", "What's happening on the Thor right now: time, battery and heat, what's on each screen, "
                        + "where you are, Shizuku, storage, and Grok Build on the Mac.", new JSONObject()))
                .put(fn("list_apps", "The apps you look after: versions, who made or installed them, waiting updates, recent crashes.",
                        new JSONObject()))
                .put(fn("list_bugs", "Bugs and problem reports you saved for Grok Build, newest first.",
                        Util.obj("state", Util.obj("type", "string", "enum", new JSONArray().put("open").put("all"),
                                "description", "open (default) or all, including fixed and ignored ones"))))
                .put(fn("bug_details", "The saved details of one bug from list_bugs: versions, counts, and the crash log.",
                        Util.obj("id", str("The bug's id from list_bugs")), "id"))
                .put(fn("recent_activity", "Your activity log, oldest first: builds, installs, updates, crashes, Grok Build runs.",
                        Util.obj("count", Util.obj("type", "integer", "description", "How many events, 1 to 50 (default 20)"))))
                .put(fn("run_diagnostics", "Run your diagnostics on the Thor: permissions, Shizuku, crash watch, battery, screens, "
                        + "storage, internet, update checks, bugs, the link to the Mac, Grok Build. Takes a few seconds.", new JSONObject()))
                .put(fn("check_for_updates", "Start a GitHub release check for one app or all of them. Anything new is announced "
                        + "later the usual way (your speech bubble and a notification).",
                        Util.obj("app", str("App name or package; leave out to check them all"))))
                .put(fn("do_move", "Play one of your animations, or hop to the other screen.",
                        Util.obj("move", Util.obj("type", "string", "enum", moves)), "move"))
                .put(fn("open_app", "Offer to open an app. A button appears for the user; it opens only if they press it.",
                        Util.obj("app", str("App name or package")), "app"))
                .put(fn("install_update", "Offer to install an app's waiting update. A button appears for the user.",
                        Util.obj("app", str("App name or package")), "app"))
                .put(fn("save_bug_report", "Offer to save a problem report for Grok Build. A button appears for the user.",
                        Util.obj("app", str("App name or package; leave out for a problem with the Thor itself"),
                                "what_happened", str("What went wrong, in the user's words")), "what_happened"))
                .put(fn("remember", "Remember a short fact the user wants you to keep: likes, names, preferences. Never secrets.",
                        Util.obj("fact", str("The fact, in one short sentence")), "fact"))
                .put(fn("forget", "Forget remembered facts that contain these words.",
                        Util.obj("words", str("Words in the facts to forget")), "words"));
    }

    private JSONObject runTool(String name, JSONObject args, JSONArray actions) throws Exception {
        switch (name) {
            case "thor_status":
                return thorStatus();
            case "list_apps": {
                JSONArray all = s.registry.liveAll(), out = new JSONArray();
                for (int i = 0; i < all.length() && out.length() < 50; i++) {
                    JSONObject a = all.optJSONObject(i);
                    JSONObject u = a.optJSONObject("update");
                    JSONObject o = Util.obj("name", a.optString("label"), "package", a.optString("pkg"),
                            "version", a.optBoolean("installed") ? a.optString("versionName") : "not installed",
                            "madeBy", "built".equals(a.optString("kind")) ? "Grok Build" : "someone else",
                            "youUpdateIt", Registry.SELF.equals(a.optString("installer")));
                    if (u != null && !u.optString("version").isEmpty()) Util.put(o, "updateWaiting", u.optString("version"));
                    if (a.optLong("lastCrash") > 0) Util.put(o, "lastCrash", ago(a.optLong("lastCrash")));
                    if (!a.optString("github").isEmpty()) Util.put(o, "github", a.optString("github"));
                    if (a.optBoolean("avoid")) Util.put(o, "gameOrVideo", true);
                    out.put(o);
                }
                return Util.obj("apps", out);
            }
            case "list_bugs": {
                JSONArray all = s.bugs.list("all".equals(args.optString("state")) ? "all" : "open", 25), out = new JSONArray();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject g = all.optJSONObject(i);
                    JSONObject o = Util.obj("id", g.optString("id"), "app", g.optString("app"), "title", g.optString("title"),
                            "kind", g.optString("kind"), "times", g.optInt("count"), "last", ago(g.optLong("last")),
                            "state", g.optString("state"), "grokHasIt", g.optLong("rev") <= g.optLong("syncedRev"));
                    if (!g.optString("fixedIn").isEmpty()) Util.put(o, "fixedIn", g.optString("fixedIn"));
                    if (g.optInt("issue") > 0) Util.put(o, "githubIssue", g.optInt("issue"));
                    out.put(o);
                }
                return Util.obj("bugs", out);
            }
            case "bug_details": {
                String d = s.bugs.detail(args.optString("id"));
                if (d.isEmpty()) return Util.obj("error", "No bug with that id");
                return Util.obj("details", Bugs.clip(scrubPrivate(d), 3500));
            }
            case "recent_activity": {
                int n = Math.max(1, Math.min(50, args.optInt("count", 20)));
                JSONArray ev = s.history.recent(n), out = new JSONArray();
                for (int i = 0; i < ev.length(); i++) {
                    JSONObject e = ev.optJSONObject(i);
                    JSONObject o = Util.obj("when", Util.clock(e.optLong("t")) + " (" + ago(e.optLong("t")) + ")", "what", e.optString("kind"));
                    if (!e.optString("app").isEmpty()) Util.put(o, "app", e.optString("app"));
                    if (!e.optString("version").isEmpty()) Util.put(o, "version", e.optString("version"));
                    if (e.has("ok")) Util.put(o, "ok", e.optBoolean("ok"));
                    if (!e.optString("msg").isEmpty()) Util.put(o, "detail", Bugs.clip(scrubPrivate(e.optString("msg")), 160));
                    out.put(o);
                }
                return Util.obj("events", out);
            }
            case "run_diagnostics": {
                JSONArray checks = s.diag.thorChecks(), out = new JSONArray();
                for (int i = 0; i < checks.length(); i++) {
                    JSONObject c = checks.optJSONObject(i);
                    JSONObject o = Util.obj("check", c.optString("name"), "state", c.optString("state"), "detail", c.optString("detail"));
                    if (!c.isNull("fix") && !c.optString("fix").isEmpty()) Util.put(o, "fix", c.optString("fix"));
                    out.put(o);
                }
                return Util.obj("checks", out);
            }
            case "check_for_updates": {
                String q = args.optString("app");
                if (q.isEmpty()) {
                    Bridge.onMain(() -> {
                        s.updates.checkAll(true);
                        return null;
                    });
                    return Util.obj("started", "all apps with GitHub releases");
                }
                String pkg = findApp(q, false);
                if (pkg == null) return Util.obj("error", "I don't look after an app called " + q);
                JSONObject e = s.registry.entry(pkg);
                if (e == null || e.optString("github").isEmpty()) return Util.obj("error", s.registry.label(pkg) + " has no GitHub releases to check");
                Bridge.onMain(() -> {
                    s.updates.checkOne(pkg);
                    return null;
                });
                return Util.obj("started", s.registry.label(pkg));
            }
            case "do_move": {
                String m = args.optString("move");
                JSONObject r = Bridge.onMain(() -> Util.obj("ok", s.brain.act(m, 0)));
                return r.optBoolean("ok") ? Util.obj("done", m) : Util.obj("error", "Can't do that right now (in the air or being held)");
            }
            case "open_app": {
                String pkg = findApp(args.optString("app"), true);
                if (pkg == null) return Util.obj("error", "No app called " + args.optString("app") + " on the Thor");
                if (s.getPackageManager().getLaunchIntentForPackage(pkg) == null) return Util.obj("error", "That app has no launcher screen");
                String label = s.registry.label(pkg);
                actions.put(Util.obj("id", "a" + actions.length(), "tool", "open_app", "pkg", pkg, "app", label,
                        "label", "Open " + label, "state", "asked"));
                return Util.obj("asked", "A button to open " + label + " is showing. It opens only if they press it.");
            }
            case "install_update": {
                String pkg = findApp(args.optString("app"), false);
                if (pkg == null) return Util.obj("error", "I don't look after an app called " + args.optString("app"));
                JSONObject a = s.registry.live(pkg), u = a.optJSONObject("update");
                if (u == null || u.optString("version").isEmpty()) return Util.obj("error", a.optString("label") + " has no update waiting");
                if (u.optString("url").isEmpty() || "notify".equals(a.optString("updateMode"))) {
                    return Util.obj("error", a.optString("label") + " " + u.optString("version") + " needs a new build from Grok on the Mac");
                }
                actions.put(Util.obj("id", "a" + actions.length(), "tool", "install_update", "pkg", pkg, "app", a.optString("label"),
                        "version", u.optString("version"), "label", "Install " + a.optString("label") + " " + u.optString("version"), "state", "asked"));
                return Util.obj("asked", "An Install button is showing. It installs only if they press it.");
            }
            case "save_bug_report": {
                String q = args.optString("app"), pkg = q.isEmpty() ? "" : findApp(q, true);
                if (pkg == null) pkg = "";
                String text = Bugs.clip(args.optString("what_happened").trim(), 1500);
                if (text.isEmpty()) return Util.obj("error", "What happened? I need a few words for Grok.");
                String label = pkg.isEmpty() ? "the Thor" : s.registry.label(pkg);
                actions.put(Util.obj("id", "a" + actions.length(), "tool", "save_bug_report", "pkg", pkg, "app", label, "text", text,
                        "label", "Save a report about " + label, "state", "asked"));
                return Util.obj("asked", "A Save report button is showing. It's saved only if they press it.");
            }
            case "remember": {
                String f = args.optString("fact").replaceAll("\\s+", " ").trim();
                if (f.isEmpty()) return Util.obj("error", "Nothing to remember");
                if (f.length() > 200) f = f.substring(0, 200);
                if (f.matches("(?i).*(xai-|password|passcode|api key|token|secret).*")) return Util.obj("error", "I don't keep secrets like that");
                synchronized (this) {
                    notes.put(Util.obj("t", System.currentTimeMillis(), "text", f));
                    while (notes.length() > MAX_NOTES) notes.remove(0);
                    Util.writeText(notesFile, Util.obj("notes", notes).toString());
                }
                return Util.obj("remembered", f);
            }
            case "forget": {
                String w = args.optString("words").toLowerCase(Locale.US).trim();
                int gone = 0;
                synchronized (this) {
                    for (int i = notes.length() - 1; i >= 0 && !w.isEmpty(); i--) {
                        if (notes.optJSONObject(i).optString("text").toLowerCase(Locale.US).contains(w)) {
                            notes.remove(i);
                            gone++;
                        }
                    }
                    Util.writeText(notesFile, Util.obj("notes", notes).toString());
                }
                return Util.obj("forgot", gone);
            }
            default:
                return Util.obj("error", "No tool called " + name);
        }
    }

    private JSONObject thorStatus() throws Exception {
        JSONObject st = Bridge.onMain(s::status);
        JSONObject o = Util.obj("time", new SimpleDateFormat("EEEE, MMMM d, h:mm a", Locale.US).format(new Date()));
        Util.put(o, "battery", battery());
        int thermal = s.getSystemService(PowerManager.class).getCurrentThermalStatus();
        String[] heat = {"normal", "light", "moderate", "severe", "critical", "emergency", "shutdown"};
        Util.put(o, "heat", thermal >= 0 && thermal < heat.length ? heat[thermal] : "unknown");
        JSONArray screens = new JSONArray(), stages = st.optJSONArray("stages");
        for (int i = 0; stages != null && i < stages.length(); i++) {
            JSONObject g = stages.optJSONObject(i);
            String top = g.isNull("top") ? null : g.optString("top", null);
            screens.put(Util.obj("screen", g.optString("name"), "on", g.optBoolean("on", true),
                    "app", top == null ? null : Registry.SELF.equals(top) ? "your den" : s.registry.label(top),
                    "gameOrVideo", g.optBoolean("avoid")));
        }
        Util.put(o, "screens", screens);
        Util.put(o, "you", Util.obj("screen", st.optString("screen"), "outOnScreen", st.optBoolean("visible", true),
                "ghosting", st.optBoolean("ghost"), "doing", st.optString("move"), "job", st.isNull("job") ? null : st.optString("job")));
        Util.put(o, "shizuku", st.optString("shizuku"));
        long free = Paths.ROOT.getUsableSpace(), total = Environment.getExternalStorageDirectory().getTotalSpace();
        Util.put(o, "storage", Util.human(free) + " free of " + Util.human(total));
        JSONObject usb = s.mac.usb();
        Util.put(o, "mac", Util.obj("pluggedIn", !(usb.optBoolean("known") && !usb.optBoolean("connected")), "grokBuild", st.optJSONObject("grok")));
        Util.put(o, "coronamonVersion", st.optString("version"));
        Util.put(o, "nightSleep", s.settings.nightSleep() ? "11 PM to 7 AM" : "off");
        return o;
    }

    /** A registry app by package or name; anyApp also looks through everything with a launcher icon. */
    private String findApp(String q, boolean anyApp) {
        if (q == null || q.trim().isEmpty()) return null;
        String n = q.trim().toLowerCase(Locale.US);
        List<String> pkgs = s.registry.packages();
        for (String p : pkgs) if (p.equalsIgnoreCase(n) || s.registry.label(p).equalsIgnoreCase(n)) return p;
        for (String p : pkgs) {
            String l = s.registry.label(p).toLowerCase(Locale.US);
            if (l.contains(n) || n.contains(l)) return p;
        }
        if (!anyApp) return null;
        PackageManager pm = s.getPackageManager();
        String partial = null;
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String p = r.activityInfo.packageName, l = String.valueOf(r.loadLabel(pm)).toLowerCase(Locale.US);
            if (p.equalsIgnoreCase(n) || l.equals(n)) return p;
            if (partial == null && (l.contains(n) || n.contains(l))) partial = p;
        }
        return partial;
    }

    private static String ago(long t) {
        if (t <= 0) return "never";
        long m = (System.currentTimeMillis() - t) / 60_000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        if (m < 48 * 60) return (m / 60) + " h ago";
        return (m / 1440) + " days ago";
    }

    /** Emails, long tokens and key-shaped strings out of logs before they go to xAI. */
    private static String scrubPrivate(String s) {
        return GrokKey.scrub(s).replaceAll("[\\w.+-]+@[\\w-]+\\.[\\w.]+", "<email>")
                .replaceAll("\\b[A-Za-z0-9_\\-]{40,}\\b", "<token>");
    }

    // ---- fresh lines ----

    /** Every half hour from the service: makes a new batch of lines about once a day. Main thread. */
    void tick() {
        long now = System.currentTimeMillis();
        if (!on() || !s.settings.freshLines() || linesBusy || overCap()) return;
        if (freshCount > 0 && now - linesAt < LINES_EVERY) return;
        if (now - linesTried < LINES_RETRY) return;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h >= 23 || h < 7 || s.gameOnScreen()) return;
        refreshLines();
    }

    /** The batch in use, as saved: t, model, lines by situation. */
    JSONObject freshLines() {
        JSONObject l = Util.readJson(linesFile);
        return l != null ? l : new JSONObject();
    }

    /** Starts a batch now (coro brain lines refresh), if one isn't already going. */
    boolean refreshLines() {
        if (linesBusy || !on() || overCap()) return false;
        linesBusy = true;
        linesTried = System.currentTimeMillis();
        linesWorker.execute(() -> {
            try {
                makeLines();
            } catch (Exception e) {
                Log.w("fresh lines: " + GrokKey.scrub(String.valueOf(e.getMessage())));
            } finally {
                linesBusy = false;
            }
        });
        return true;
    }

    private void makeLines() throws Exception {
        String key = GrokKey.get(s);
        if (key == null) return;
        JSONObject examples = Bridge.onMain(() -> {
            JSONObject o = new JSONObject();
            for (String[] f : FRESH) Util.put(o, f[0], new JSONArray(s.lines.examples(f[0], 2)));
            return o;
        });
        StringBuilder extra = new StringBuilder();
        List<String> names = new ArrayList<>();
        for (String p : s.registry.packages()) if (!Registry.SELF.equals(p) && names.size() < 14) names.add(s.registry.label(p));
        if (!names.isEmpty()) extra.append("\nApps on the Thor he can mention in idle lines now and then: ").append(String.join(", ", names)).append('\n');
        synchronized (this) {
            if (notes.length() > 0) {
                extra.append("Things the user told him (use lightly in idle lines):\n");
                for (int i = 0; i < Math.min(10, notes.length()); i++) extra.append("- ").append(notes.optJSONObject(i).optString("text")).append('\n');
            }
        }
        JSONObject old = Util.readJson(linesFile);
        JSONObject keep = old != null && old.optJSONObject("lines") != null ? old.optJSONObject("lines") : new JSONObject();
        int made = 0;
        Exception failed = null;
        for (int from = 0; from < FRESH.length; from += LINES_CHUNK) {
            String[][] part = java.util.Arrays.copyOfRange(FRESH, from, Math.min(FRESH.length, from + LINES_CHUNK));
            try {
                made += linesFor(key, part, examples, extra.toString(), keep);
            } catch (Grok.Failure e) {
                if (e.code == 401 || e.code == 403 || e.code == 429) throw e;
                failed = e;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                failed = e;
            }
        }
        if (made == 0) throw failed != null ? failed : new IllegalStateException("no usable lines");
        if (failed != null) Log.w("fresh lines: part of the batch failed: " + GrokKey.scrub(String.valueOf(failed.getMessage())));
        long now = System.currentTimeMillis();
        Util.writeText(linesFile, Util.obj("t", now, "model", LINE_MODEL, "character", LINES_CHARACTER, "lines", keep).toString());
        s.main.post(() -> {
            linesAt = now;
            useLines(keep);
        });
    }

    /** One request for a few situations; puts the good lines into keep and returns how many situations got some. */
    private int linesFor(String key, String[][] part, JSONObject examples, String extra, JSONObject keep) throws Exception {
        JSONObject props = new JSONObject();
        JSONArray required = new JSONArray();
        StringBuilder sit = new StringBuilder("Write 4 new lines for each situation.\n\nSituations:\n");
        for (String[] f : part) {
            Util.put(props, f[0], Util.obj("type", "array", "items", Util.obj("type", "string")));
            required.put(f[0]);
            sit.append("- ").append(f[0]).append(": ").append(f[1]);
            JSONArray ex = examples.optJSONArray(f[0]);
            for (int i = 0; ex != null && i < ex.length(); i++) sit.append(i == 0 ? ". Examples: \"" : "\" / \"").append(ex.optString(i));
            if (ex != null && ex.length() > 0) sit.append('"');
            sit.append('\n');
        }
        sit.append(extra);
        JSONObject schema = Util.obj("type", "object", "properties", props, "required", required, "additionalProperties", false);
        JSONObject body = Util.obj("model", LINE_MODEL, "store", false, "max_output_tokens", 6000, "instructions", LINE_RULES,
                "input", new JSONArray().put(Util.obj("role", "user", "content", sit.toString())),
                "text", Util.obj("format", Util.obj("type", "json_schema", "name", "coronamon_lines", "schema", schema)));
        gate();
        JSONObject r = Grok.respond(key, body, 150_000);
        account(LINE_MODEL, r.optJSONObject("usage"));
        JSONObject got = new JSONObject(Grok.text(r));
        int n = 0;
        for (String[] f : part) {
            JSONArray in = got.optJSONArray(f[0]), out = new JSONArray();
            for (int i = 0; in != null && i < in.length() && out.length() < 6; i++) {
                String line = clean(in.optString(i), f[0], f[2]);
                if (line != null) out.put(line);
            }
            if (out.length() > 0) {
                Util.put(keep, f[0], out);
                n++;
            }
        }
        return n;
    }

    private static String clean(String line, String key, String allowed) {
        String t = line == null ? "" : line.replaceAll("\\s+", " ").trim();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\""))) t = t.substring(1, t.length() - 1).trim();
        if (t.length() < 3 || t.length() > 120 || t.contains("xai-")) return null;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isSurrogate(c) || (c >= 0x2600 && c <= 0x27BF)) return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([a-z]+)\\}").matcher(t);
        while (m.find()) if (!(" " + allowed + " ").contains(" " + m.group(1) + " ")) return null;
        if ("update_found".equals(key) && !t.contains("?")) return null;
        return t;
    }

    private void useLines(JSONObject lines) {
        Map<String, List<String>> m = new HashMap<>();
        int n = 0;
        for (String[] f : FRESH) {
            JSONArray a = lines == null ? null : lines.optJSONArray(f[0]);
            if (a == null) continue;
            List<String> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) list.add(a.optString(i));
            m.put(f[0], list);
            n += list.size();
        }
        freshCount = n;
        s.lines.setFresh(m);
    }
}
