package dev.grokbuild.coronamon;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** xAI's Responses API (docs.x.ai), for his Grok brain. */
final class Grok {
    static final String URL = "https://api.x.ai/v1/responses";

    private Grok() {
    }

    /** A request xAI turned down; code is the HTTP status. */
    static final class Failure extends IOException {
        final int code;

        Failure(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    static JSONObject respond(String key, JSONObject body, int timeoutMs) throws IOException {
        HttpURLConnection c = Util.open(URL, timeoutMs);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + key);
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(out.length);
            try (OutputStream o = c.getOutputStream()) {
                o.write(out);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : new String(Util.readAll(in), StandardCharsets.UTF_8);
            if (code != 200) throw new Failure(code, explain(code, text));
            JSONObject r = new JSONObject(text);
            Object err = r.opt("error");
            if (err != null && err != JSONObject.NULL) throw new Failure(code, "xAI: " + GrokKey.scrub(String.valueOf(err)));
            return r;
        } catch (JSONException e) {
            throw new IOException("xAI sent something I couldn't read");
        } finally {
            c.disconnect();
        }
    }

    static String explain(int code, String body) {
        String msg = body;
        try {
            JSONObject j = new JSONObject(body);
            Object e = j.opt("error");
            if (e instanceof JSONObject) msg = ((JSONObject) e).optString("message", e.toString());
            else if (e != null) msg = String.valueOf(e);
            else msg = j.optString("message", body);
        } catch (JSONException ignored) {
        }
        msg = GrokKey.scrub(msg == null ? "" : msg.trim());
        if (msg.length() > 240) msg = msg.substring(0, 240) + "…";
        String what = code == 401 ? "xAI didn't accept the API key"
                : code == 403 ? "xAI said no (out of credits, or the key can't use this)"
                : code == 429 ? "xAI says slow down (rate limit or spending limit)"
                : code >= 500 ? "xAI is having trouble" : "xAI turned the request down";
        return what + " (HTTP " + code + ")" + (msg.isEmpty() ? "" : ": " + msg);
    }

    /** The reply text: every output_text part of the message items. */
    static String text(JSONObject r) {
        StringBuilder sb = new StringBuilder();
        JSONArray out = r.optJSONArray("output");
        for (int i = 0; out != null && i < out.length(); i++) {
            JSONObject item = out.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("type"))) continue;
            JSONArray content = item.optJSONArray("content");
            for (int j = 0; content != null && j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part != null && "output_text".equals(part.optString("type"))) sb.append(part.optString("text"));
            }
        }
        return sb.toString().trim();
    }

    static List<JSONObject> calls(JSONObject r) {
        List<JSONObject> list = new ArrayList<>();
        JSONArray out = r.optJSONArray("output");
        for (int i = 0; out != null && i < out.length(); i++) {
            JSONObject item = out.optJSONObject(i);
            if (item != null && "function_call".equals(item.optString("type"))) list.add(item);
        }
        return list;
    }

    /** US dollars for one response, from its usage and the model's list prices (docs.x.ai/developers/models). */
    static double cost(String model, JSONObject usage) {
        if (usage == null) return 0;
        double in, cached, out;
        if (model.startsWith("grok-4.3") || model.startsWith("grok-4.20")) {
            in = 1.25;
            cached = 0.20;
            out = 2.50;
        } else {
            in = 2.00;
            cached = 0.50;
            out = 6.00;
        }
        long inTok = usage.optLong("input_tokens");
        JSONObject d = usage.optJSONObject("input_tokens_details");
        long cachedTok = d == null ? 0 : Math.min(inTok, d.optLong("cached_tokens"));
        long outTok = usage.optLong("output_tokens");
        double k = inTok >= 200_000 ? 2 : 1;
        return k * ((inTok - cachedTok) * in + cachedTok * cached + outTok * out) / 1e6;
    }
}
