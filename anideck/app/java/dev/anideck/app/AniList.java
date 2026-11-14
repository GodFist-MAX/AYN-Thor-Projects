package dev.anideck.app;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Minimal client for AniList's GraphQL API. */
final class AniList {
    private static final String API = "https://graphql.anilist.co";

    static final class Error extends IOException {
        final int code;

        Error(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private AniList() {
    }

    static JSONObject query(String gql, JSONObject vars, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            JSONObject body = new JSONObject();
            App.put(body, "query", gql);
            App.put(body, "variables", vars == null ? new JSONObject() : vars);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String text = in == null ? "" : new String(App.readAll(in), StandardCharsets.UTF_8);
            if (code == 429) throw new Error(429, "AniList is busy, trying again later");
            JSONObject j;
            try {
                j = new JSONObject(text);
            } catch (JSONException e) {
                throw new Error(code, "AniList answered " + code);
            }
            JSONArray errors = j.optJSONArray("errors");
            if (code >= 400 || (errors != null && j.isNull("data"))) {
                JSONObject first = errors != null ? errors.optJSONObject(0) : null;
                int status = first != null ? first.optInt("status", code) : code;
                throw new Error(status, first != null ? first.optString("message", "AniList error") : "AniList answered " + code);
            }
            return j.optJSONObject("data");
        } finally {
            c.disconnect();
        }
    }

    /** The claims of an AniList access token (a JWT), or null if the string isn't one. */
    static JSONObject claims(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return null;
        try {
            byte[] raw = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new JSONObject(new String(raw, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | JSONException e) {
            return null;
        }
    }
}
