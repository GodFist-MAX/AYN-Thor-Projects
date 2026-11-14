package dev.crossbar.launcher;

import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Friends and activity from the services that offer it: Steam (Web API key + SteamID),
 * Discord (server widgets, optional Lanyard presence) and YouTube (channel feeds).
 * X has no friends or presence API; it shows up through notifications and shortcuts.
 */
final class Social {
    private final App app;
    private volatile JSONObject cache = new JSONObject();
    private volatile long fetchedAt;
    private volatile boolean fetching;

    Social(App app) {
        this.app = app;
    }

    JSONObject snapshot(boolean force) {
        if (force || System.currentTimeMillis() - fetchedAt > 60_000) refreshAsync();
        JSONObject o = Util.copy(cache);
        Util.put(o, "notifications", NotifListener.list());
        Util.put(o, "listener", NotifListener.granted(app));
        Util.put(o, "updated", fetchedAt);
        return o;
    }

    int onlineCount() {
        int n = 0;
        JSONObject s = cache.optJSONObject("steam");
        if (s != null) n += s.optInt("online");
        JSONObject d = cache.optJSONObject("discord");
        if (d != null) n += d.optInt("friendsOnline");
        return n;
    }

    void refreshAsync() {
        if (fetching) return;
        fetching = true;
        app.io.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                Util.put(o, "steam", steam());
                Util.put(o, "discord", discord());
                Util.put(o, "youtube", youtube());
                cache = o;
                fetchedAt = System.currentTimeMillis();
            } finally {
                fetching = false;
            }
        });
    }

    private JSONObject steam() {
        String key = app.store.str("steamApiKey").trim();
        String id = app.store.str("steamId").trim();
        JSONObject r = Util.obj("configured", !key.isEmpty() && !id.isEmpty());
        if (key.isEmpty() || id.isEmpty()) return r;
        String api = "https://api.steampowered.com/ISteamUser/";
        try {
            if (!id.matches("\\d{17}")) {
                JSONObject v = new JSONObject(Util.httpGet(api + "ResolveVanityURL/v1/?key=" + Util.enc(key)
                        + "&vanityurl=" + Util.enc(id), 10000)).getJSONObject("response");
                if (v.optInt("success") != 1) throw new Exception("SteamID not found");
                id = v.getString("steamid");
            }
            List<String> ids = new ArrayList<>();
            ids.add(id);
            try {
                JSONArray fl = new JSONObject(Util.httpGet(api + "GetFriendList/v1/?relationship=friend&key="
                        + Util.enc(key) + "&steamid=" + id, 10000)).getJSONObject("friendslist").getJSONArray("friends");
                for (int i = 0; i < fl.length(); i++) ids.add(fl.getJSONObject(i).getString("steamid"));
            } catch (Exception e) {
                Util.put(r, "error", "Friend list is private or unavailable");
            }
            List<JSONObject> friends = new ArrayList<>();
            for (int i = 0; i < ids.size(); i += 100) {
                String chunk = String.join(",", ids.subList(i, Math.min(ids.size(), i + 100)));
                JSONArray players = new JSONObject(Util.httpGet(api + "GetPlayerSummaries/v2/?key=" + Util.enc(key)
                        + "&steamids=" + chunk, 10000)).getJSONObject("response").getJSONArray("players");
                for (int j = 0; j < players.length(); j++) {
                    JSONObject p = players.getJSONObject(j);
                    JSONObject f = Util.obj("id", p.optString("steamid"), "name", p.optString("personaname"),
                            "state", p.optInt("personastate"), "game", p.optString("gameextrainfo", ""),
                            "gameId", p.optString("gameid", ""), "avatar", p.optString("avatarfull", p.optString("avatarmedium")),
                            "profile", p.optString("profileurl"), "lastOnline", p.optLong("lastlogoff") * 1000L);
                    if (p.optString("steamid").equals(id)) Util.put(r, "me", f);
                    else friends.add(f);
                }
            }
            Collections.sort(friends, (a, b) -> {
                int ra = rank(a), rb = rank(b);
                if (ra != rb) return ra - rb;
                return a.optString("name").compareToIgnoreCase(b.optString("name"));
            });
            JSONArray arr = new JSONArray();
            int online = 0, inGame = 0;
            for (JSONObject f : friends) {
                arr.put(f);
                if (f.optInt("state") > 0) online++;
                if (!f.optString("game").isEmpty()) inGame++;
            }
            Util.put(r, "friends", arr);
            Util.put(r, "online", online);
            Util.put(r, "inGame", inGame);
        } catch (Exception e) {
            Util.put(r, "error", e.getMessage() == null ? "Steam is unavailable" : e.getMessage());
        }
        return r;
    }

    private static int rank(JSONObject f) {
        if (!f.optString("game").isEmpty()) return 0;
        int s = f.optInt("state");
        if (s == 1 || s == 5 || s == 6) return 1;
        if (s > 0) return 2;
        return 3;
    }

    private JSONObject discord() {
        String servers = app.store.str("discordServers").trim();
        String users = app.store.str("discordUserIds").trim();
        JSONObject r = Util.obj("configured", !servers.isEmpty() || !users.isEmpty());
        JSONArray outServers = new JSONArray();
        for (String sid : split(servers)) {
            try {
                JSONObject w = new JSONObject(Util.httpGet("https://discord.com/api/guilds/" + sid + "/widget.json", 10000));
                Map<String, String> channels = new HashMap<>();
                JSONArray ch = w.optJSONArray("channels");
                for (int i = 0; ch != null && i < ch.length(); i++) {
                    JSONObject c = ch.getJSONObject(i);
                    channels.put(c.optString("id"), c.optString("name"));
                }
                JSONArray members = new JSONArray();
                Map<String, JSONArray> voice = new HashMap<>();
                JSONArray ms = w.optJSONArray("members");
                for (int i = 0; ms != null && i < ms.length(); i++) {
                    JSONObject m = ms.getJSONObject(i);
                    JSONObject game = m.optJSONObject("game");
                    String channelId = m.optString("channel_id", "");
                    JSONObject mm = Util.obj("name", m.optString("username"), "status", m.optString("status"),
                            "avatar", m.optString("avatar_url"), "game", game == null ? "" : game.optString("name"),
                            "channel", channels.containsKey(channelId) ? channels.get(channelId) : "",
                            "muted", m.optBoolean("self_mute") || m.optBoolean("mute"),
                            "deaf", m.optBoolean("self_deaf") || m.optBoolean("deaf"));
                    members.put(mm);
                    if (!channelId.isEmpty() && !"null".equals(channelId)) {
                        String cname = channels.containsKey(channelId) ? channels.get(channelId) : "Voice";
                        JSONArray list = voice.get(cname);
                        if (list == null) voice.put(cname, list = new JSONArray());
                        list.put(m.optString("username"));
                    }
                }
                JSONArray vc = new JSONArray();
                for (Map.Entry<String, JSONArray> e : voice.entrySet()) vc.put(Util.obj("channel", e.getKey(), "members", e.getValue()));
                outServers.put(Util.obj("id", sid, "name", w.optString("name"), "online", w.optInt("presence_count"),
                        "invite", w.optString("instant_invite", ""), "members", members, "voice", vc));
            } catch (Exception e) {
                outServers.put(Util.obj("id", sid, "name", "Server " + sid, "error",
                        "Turn on Server Widget in this server's settings"));
            }
        }
        Util.put(r, "servers", outServers);
        JSONArray friends = new JSONArray();
        int online = 0;
        for (String uid : split(users)) {
            try {
                JSONObject d = new JSONObject(Util.httpGet("https://api.lanyard.rest/v1/users/" + uid, 10000)).getJSONObject("data");
                JSONObject u = d.getJSONObject("discord_user");
                String status = d.optString("discord_status", "offline");
                String activity = "";
                JSONArray acts = d.optJSONArray("activities");
                for (int i = 0; acts != null && i < acts.length(); i++) {
                    JSONObject a = acts.getJSONObject(i);
                    if (a.optInt("type") == 4) continue;
                    activity = (a.optInt("type") == 2 ? "Listening to " : a.optInt("type") == 3 ? "Watching " : "Playing ") + a.optString("name");
                    break;
                }
                String avatar = u.optString("avatar", "");
                friends.put(Util.obj("id", uid, "name", u.optString("global_name", u.optString("username")),
                        "status", status, "game", activity,
                        "avatar", avatar.isEmpty() ? "" : "https://cdn.discordapp.com/avatars/" + uid + "/" + avatar + ".png?size=128"));
                if (!"offline".equals(status)) online++;
            } catch (Exception ignored) {
            }
        }
        Util.put(r, "friends", friends);
        Util.put(r, "friendsOnline", online);
        return r;
    }

    private JSONObject youtube() {
        String channels = app.store.str("youtubeChannels").trim();
        JSONObject r = Util.obj("configured", !channels.isEmpty());
        List<JSONObject> videos = new ArrayList<>();
        for (String cid : split(channels)) {
            try {
                String xml = Util.httpGet("https://www.youtube.com/feeds/videos.xml?channel_id=" + Util.enc(cid), 10000);
                XmlPullParser p = Xml.newPullParser();
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
                p.setInput(new StringReader(xml));
                String author = "", tag = null;
                JSONObject cur = null;
                int count = 0;
                for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT && count < 4; ev = p.next()) {
                    if (ev == XmlPullParser.START_TAG) {
                        tag = p.getName();
                        if ("entry".equals(tag)) cur = new JSONObject();
                    } else if (ev == XmlPullParser.TEXT && tag != null) {
                        String t = p.getText().trim();
                        if (t.isEmpty()) continue;
                        if (cur == null) {
                            if ("name".equals(tag) && author.isEmpty()) author = t;
                        } else if ("yt:videoId".equals(tag)) {
                            Util.put(cur, "id", t);
                        } else if ("title".equals(tag)) {
                            Util.put(cur, "title", t);
                        } else if ("published".equals(tag)) {
                            Util.put(cur, "published", t);
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        if ("entry".equals(p.getName()) && cur != null) {
                            Util.put(cur, "channel", author);
                            Util.put(cur, "thumb", "https://i.ytimg.com/vi/" + cur.optString("id") + "/mqdefault.jpg");
                            videos.add(cur);
                            cur = null;
                            count++;
                        }
                        tag = null;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        Collections.sort(videos, (a, b) -> b.optString("published").compareTo(a.optString("published")));
        JSONArray arr = new JSONArray();
        for (int i = 0; i < Math.min(30, videos.size()); i++) arr.put(videos.get(i));
        Util.put(r, "videos", arr);
        return r;
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String x : s.split("[,\\s]+")) if (!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }
}
