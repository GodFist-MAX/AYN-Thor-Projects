package dev.grokbuild.coronamon;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

final class Settings {
    private final SharedPreferences p;

    Settings(Context ctx) {
        p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** 0.75 small, 1 normal, 1.3 big. */
    float size() {
        return p.getFloat("size", 1f);
    }

    /** 0 quiet, 1 normal, 2 chatty. */
    int chatter() {
        return p.getInt("chatter", 1);
    }

    /** What he does when a game or video is on his screen: move, ghost, hide or stay. */
    String games() {
        return p.getString("games", "move");
    }

    boolean nightSleep() {
        return p.getBoolean("nightSleep", true);
    }

    /** auto, top or bottom. */
    String screenPref() {
        return p.getString("screen", "auto");
    }

    boolean visible() {
        return p.getBoolean("visible", true);
    }

    /** ask, auto or off. */
    String updates() {
        return p.getString("updates", "ask");
    }

    boolean showSources() {
        return p.getBoolean("showSources", true);
    }

    /** Returns false for an unknown key or a bad value. */
    boolean set(String key, String value) {
        if (key == null || value == null) return false;
        SharedPreferences.Editor e = p.edit();
        try {
            switch (key) {
                case "size":
                    e.putFloat("size", Math.max(0.5f, Math.min(1.6f, Float.parseFloat(value))));
                    break;
                case "chatter":
                    e.putInt("chatter", Math.max(0, Math.min(2, Integer.parseInt(value))));
                    break;
                case "games":
                    if (!value.matches("move|ghost|hide|stay")) return false;
                    e.putString("games", value);
                    break;
                case "nightSleep":
                case "visible":
                case "showSources":
                    e.putBoolean(key, Boolean.parseBoolean(value));
                    break;
                case "screen":
                    if (!value.matches("auto|top|bottom")) return false;
                    e.putString("screen", value);
                    break;
                case "updates":
                    if (!value.matches("ask|auto|off")) return false;
                    e.putString("updates", value);
                    break;
                default:
                    return false;
            }
        } catch (NumberFormatException ex) {
            return false;
        }
        e.apply();
        return true;
    }

    JSONObject toJson() {
        return Util.obj("size", size(), "chatter", chatter(), "games", games(), "nightSleep", nightSleep(),
                "screen", screenPref(), "visible", visible(), "updates", updates(), "showSources", showSources());
    }
}
