package dev.grokbuild.coronamon;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

final class Settings {
    private final SharedPreferences p;

    Settings(Context ctx) {
        p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (!p.getBoolean("homeAdded", false)) {
            SharedPreferences.Editor e = p.edit().putBoolean("homeAdded", true);
            if ("move".equals(p.getString("games", "move"))) e.putString("games", "home");
            e.apply();
        }
    }

    /** 0.75 small, 1 normal, 1.3 big. */
    float size() {
        return p.getFloat("size", 1f);
    }

    /** 0 quiet, 1 normal, 2 chatty. */
    int chatter() {
        return p.getInt("chatter", 1);
    }

    /**
     * What he does when a game or video is on his screen: home (his room on the bottom screen while
     * something's on top), move, ghost, hide or stay.
     */
    String games() {
        return p.getString("games", "home");
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

    /** His Grok brain (Mind); it also needs an API key. */
    boolean brain() {
        return p.getBoolean("brain", true);
    }

    String brainModel() {
        String m = p.getString("brainModel", Mind.MODELS[0]);
        for (String ok : Mind.MODELS) if (ok.equals(m)) return m;
        return Mind.MODELS[0];
    }

    /** US dollars of Grok a day before he goes back to his built-in lines. */
    float brainCap() {
        return p.getFloat("brainCap", 1f);
    }

    boolean freshLines() {
        return p.getBoolean("freshLines", true);
    }

    /** Talking out loud: double-tap him and he listens, then answers in his voice. Needs his Grok brain. */
    boolean voice() {
        return p.getBoolean("voice", true);
    }

    /** One of xAI's voices (Voice.VOICES), or a custom voice id. */
    String voiceId() {
        return p.getString("voiceId", Voice.VOICES[0]);
    }

    /** 0.7 to 1.5; 1 is xAI's normal pace. */
    float voiceSpeed() {
        return p.getFloat("voiceSpeed", 1.05f);
    }

    /** After he answers, he listens a few more seconds in case you answer back. */
    boolean voiceFollowUp() {
        return p.getBoolean("voiceFollowUp", true);
    }

    /** When Android dims the screens because nobody's touching the Thor, he goes to his room on the bottom screen. */
    boolean dimHome() {
        return p.getBoolean("dimHome", true);
    }

    /** Where you dragged his house button on a screen, as {x, y} in pixels, or null. */
    int[] homeIcon(int displayId) {
        String[] xy = p.getString("homeIcon" + displayId, "").split(",");
        if (xy.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(xy[0]), Integer.parseInt(xy[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    void homeIcon(int displayId, int x, int y) {
        p.edit().putString("homeIcon" + displayId, x + "," + y).apply();
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
                    if (!value.matches("home|move|ghost|hide|stay")) return false;
                    e.putString("games", value);
                    break;
                case "nightSleep":
                case "visible":
                case "showSources":
                case "brain":
                case "freshLines":
                case "voice":
                case "voiceFollowUp":
                case "dimHome":
                    e.putBoolean(key, Boolean.parseBoolean(value));
                    break;
                case "voiceId":
                    if (!value.matches("[A-Za-z0-9_-]{1,64}")) return false;
                    e.putString("voiceId", value);
                    break;
                case "voiceSpeed":
                    e.putFloat("voiceSpeed", Math.max(0.7f, Math.min(1.5f, Float.parseFloat(value))));
                    break;
                case "brainModel":
                    if (!java.util.Arrays.asList(Mind.MODELS).contains(value)) return false;
                    e.putString("brainModel", value);
                    break;
                case "brainCap":
                    e.putFloat("brainCap", Math.max(0.1f, Math.min(50f, Float.parseFloat(value))));
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
                "screen", screenPref(), "visible", visible(), "updates", updates(), "showSources", showSources(),
                "brain", brain(), "brainModel", brainModel(), "brainCap", brainCap(), "freshLines", freshLines(),
                "voice", voice(), "voiceId", voiceId(), "voiceSpeed", voiceSpeed(), "voiceFollowUp", voiceFollowUp(),
                "dimHome", dimHome());
    }
}
