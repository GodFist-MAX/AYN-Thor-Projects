package dev.grokbuild.coronamon;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Mac's side of things as its watcher last told Coronamon, and the Den's requests for it. adb only goes from
 * the Mac to the Thor, so a request waits here and control.rev changes; while the Thor is plugged in, the watcher
 * checks that file every few seconds, takes the requests over the bridge and answers each one.
 */
final class Mac {
    static final File REV = new File(Paths.ROOT, "control.rev");
    private static final List<String> ACTIONS = Arrays.asList("status", "hold", "resume", "off", "on", "stop-run",
            "watch-stop", "diagnostics");
    /** A request the watcher hasn't taken in this long is dropped, and so is one it took but never answered. */
    private static final long TAKE_WITHIN = 60_000, ANSWER_WITHIN = 180_000;
    private static final int KEEP = 20;

    private final Context ctx;
    private final File file;
    private final List<JSONObject> requests = new ArrayList<>();
    private JSONObject status;
    private long statusAt;
    private int seq;

    Mac(Context ctx) {
        this.ctx = ctx;
        file = new File(ctx.getFilesDir(), "mac.json");
        JSONObject o = Util.readJson(file);
        if (o != null) {
            status = o.optJSONObject("status");
            statusAt = o.optLong("statusAt");
        }
    }

    static String label(String action, String arg) {
        switch (action) {
            case "status": return "Check status";
            case "hold": return "Pause runs " + (arg == null || arg.isEmpty() || "60".equals(arg) ? "1 hour" : arg + " minutes");
            case "resume": return "Resume runs";
            case "off": return "Turn runs off";
            case "on": return "Turn runs on";
            case "stop-run": return "Stop current run";
            case "watch-stop": return "Stop watcher";
            case "diagnostics": return "Mac diagnostics";
            default: return action;
        }
    }

    /** A button in the Den. Pressing it again while the first is still waiting gives the same request. */
    synchronized JSONObject request(String action, String arg) {
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("the Mac can't do " + action);
        if (arg != null && arg.isEmpty()) arg = null;
        expire();
        for (JSONObject r : requests) {
            if (action.equals(r.optString("action")) && open(r)) return copy(r);
        }
        long now = System.currentTimeMillis();
        JSONObject r = Util.obj("id", Long.toString(now, 36) + "-" + (++seq), "action", action, "arg", arg, "t", now,
                "state", "waiting");
        requests.add(r);
        while (requests.size() > KEEP) requests.remove(0);
        Util.writeText(REV, r.optString("id"));
        return copy(r);
    }

    /** The watcher takes what's waiting; those requests are working until it answers them. */
    synchronized JSONArray take() {
        expire();
        JSONArray out = new JSONArray();
        for (JSONObject r : requests) {
            if (!"waiting".equals(r.optString("state"))) continue;
            Util.put(r, "state", "working");
            Util.put(r, "taken", System.currentTimeMillis());
            out.put(Util.obj("id", r.optString("id"), "action", r.optString("action"), "arg", r.opt("arg")));
        }
        return out;
    }

    /** result: {ok, msg, status?, checks?}. Returns the answered request, or null if it's gone. */
    synchronized JSONObject done(String id, JSONObject result) {
        JSONObject st = result.optJSONObject("status");
        if (st != null) setStatus(st);
        for (JSONObject r : requests) {
            if (!r.optString("id").equals(id)) continue;
            Util.put(r, "state", result.optBoolean("ok", true) ? "done" : "failed");
            Util.put(r, "msg", result.optString("msg"));
            Util.put(r, "done", System.currentTimeMillis());
            return copy(r);
        }
        return null;
    }

    synchronized JSONObject get(String id) {
        expire();
        for (JSONObject r : requests) if (r.optString("id").equals(id)) return copy(r);
        return null;
    }

    synchronized void setStatus(JSONObject st) {
        status = st;
        statusAt = System.currentTimeMillis();
        Util.writeText(file, Util.obj("status", status, "statusAt", statusAt).toString());
    }

    synchronized long statusAt() {
        return statusAt;
    }

    /** The watcher said it's stopping, and it won't be around to say so afterwards. */
    synchronized void watcherStopped() {
        if (status == null) return;
        JSONObject w = status.optJSONObject("watcher");
        if (w == null) Util.put(status, "watcher", w = new JSONObject());
        Util.put(w, "pid", 0);
        Util.put(w, "stopped", System.currentTimeMillis());
        Util.writeText(file, Util.obj("status", status, "statusAt", statusAt).toString());
    }

    /** Whether a computer is on the USB port, and with adb on, from the sticky USB_STATE broadcast. */
    JSONObject usb() {
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter("android.hardware.usb.action.USB_STATE"));
            if (i == null) return Util.obj("known", false);
            return Util.obj("known", true, "connected", i.getBooleanExtra("connected", false), "adb",
                    i.getBooleanExtra("adb", false));
        } catch (RuntimeException e) {
            return Util.obj("known", false);
        }
    }

    /** True unless the Thor is known not to be on a computer with adb, so a request couldn't be picked up. */
    boolean reachable() {
        JSONObject u = usb();
        return !u.optBoolean("known") || (u.optBoolean("connected") && u.optBoolean("adb"));
    }

    synchronized JSONObject forDen() {
        expire();
        JSONArray rs = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = requests.size() - 1; i >= 0; i--) {
            JSONObject r = requests.get(i);
            if (now - r.optLong("t") < 10 * 60_000) rs.put(copy(r));
        }
        return Util.obj("status", status == null ? null : copy(status), "statusAt", statusAt, "requests", rs, "usb", usb());
    }

    private static boolean open(JSONObject r) {
        String s = r.optString("state");
        return "waiting".equals(s) || "working".equals(s);
    }

    private void expire() {
        long now = System.currentTimeMillis();
        for (JSONObject r : requests) {
            String s = r.optString("state");
            String msg = null;
            if ("waiting".equals(s) && now - r.optLong("t") > TAKE_WITHIN) {
                msg = "The Mac didn't pick this up. Is the Thor plugged into the Mac, with the watcher running?";
            } else if ("working".equals(s) && now - r.optLong("taken") > ANSWER_WITHIN) {
                msg = "The Mac started on this but never answered.";
            }
            if (msg == null) continue;
            Util.put(r, "state", "expired");
            Util.put(r, "msg", msg);
            Util.put(r, "done", now);
        }
    }

    /** The Den turns these into JSON on another thread, while the bridge may be answering them on the main one. */
    private static JSONObject copy(JSONObject r) {
        return Util.parse(r.toString());
    }
}
