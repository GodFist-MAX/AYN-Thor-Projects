package dev.crossbar.launcher;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Emulator platforms and how to hand a game to each emulator. Launch conventions follow
 * the frontend player definitions used by Cocoon/Daijisho/ES-DE for the same apps.
 */
final class Platforms {
    private Platforms() {}

    static final int CLEAR = Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP;
    static final String RA_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture";
    static final String[] RA_PKGS = {"com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32"};

    enum Mode { DATA, EXTRA_URI, EXTRA_PATH, RETROARCH, APP }

    static final class Player {
        final String name, pkg, cls, action, extra, core;
        final Mode mode;
        final int flags;

        Player(String name, String pkg, String cls, String action, Mode mode, String extra, int flags, String core) {
            this.name = name;
            this.pkg = pkg;
            this.cls = cls == null ? null : (cls.startsWith(".") ? pkg + cls : cls);
            this.action = action;
            this.mode = mode;
            this.extra = extra;
            this.flags = flags;
            this.core = core;
        }
    }

    static final class Platform {
        final String id, name, shortName, glyph, accent;
        final String[] folders, libretro, cocoonIds;
        final Set<String> exts;
        final Player[] players;
        final String appPkg;

        Platform(String id, String name, String shortName, String glyph, String accent, String[] folders,
                 String exts, String[] libretro, String[] cocoonIds, String appPkg, Player... players) {
            this.id = id;
            this.name = name;
            this.shortName = shortName;
            this.glyph = glyph;
            this.accent = accent;
            this.folders = folders;
            this.exts = new HashSet<>(Arrays.asList(exts.isEmpty() ? new String[0] : exts.split(",")));
            this.libretro = libretro;
            this.cocoonIds = cocoonIds;
            this.appPkg = appPkg;
            this.players = players;
        }
    }

    static Player data(String name, String pkg, String cls, String action, int flags) {
        return new Player(name, pkg, cls, action, Mode.DATA, null, flags, null);
    }

    static Player extraUri(String name, String pkg, String cls, String action, String extra, int flags) {
        return new Player(name, pkg, cls, action, Mode.EXTRA_URI, extra, flags, null);
    }

    static Player ra(String core) {
        return new Player("RetroArch (" + core + ")", null, RA_ACTIVITY, null, Mode.RETROARCH, null, CLEAR, core);
    }

    static Player app(String name, String pkg) {
        return new Player(name, pkg, null, null, Mode.APP, null, 0, null);
    }

    static final String YUZU_ACT = "org.yuzu.yuzu_emu.activities.EmulationActivity";
    static final String CITRA_ACT = "org.citra.citra_emu.activities.EmulationActivity";
    static final String NFC = "android.nfc.action.TECH_DISCOVERED";

    static final Platform[] ALL = {
        new Platform("psp", "PlayStation Portable", "PSP", "psp", "#2f7bff",
            new String[]{"psp"}, "iso,cso,chd,pbp,elf,prx",
            new String[]{"Sony - PlayStation Portable"}, new String[]{"psp"}, null,
            data("PPSSPP Gold", "org.ppsspp.ppssppgold", "org.ppsspp.ppsspp.PpssppActivity", Intent.ACTION_VIEW, 0),
            data("PPSSPP", "org.ppsspp.ppsspp", ".PpssppActivity", Intent.ACTION_VIEW, 0),
            data("PPSSPP Duo", "org.ppsspp.ppssppduo", "org.ppsspp.ppsspp.PpssppActivity", Intent.ACTION_VIEW, 0),
            ra("ppsspp")),
        new Platform("ps2", "PlayStation 2", "PS2", "ps2", "#3d4cff",
            new String[]{"ps2"}, "iso,chd,gz,cso,bin,mdf,ciso,img",
            new String[]{"Sony - PlayStation 2"}, new String[]{"ps2"}, null,
            extraUri("AetherSX2", "xyz.aethersx2.android", ".EmulationActivity", Intent.ACTION_MAIN, "bootPath", CLEAR),
            extraUri("NetherSX2", "xyz.aethersx2.tturnip", "xyz.aethersx2.android.EmulationActivity", Intent.ACTION_MAIN, "bootPath", CLEAR),
            extraUri("AetherSX2 Custom", "xyz.aethersx2.custom", ".EmulationActivity", Intent.ACTION_MAIN, "bootPath", CLEAR),
            data("ARMSX2", "com.armsx2", ".MainActivity", Intent.ACTION_VIEW, CLEAR),
            ra("play")),
        new Platform("nds", "Nintendo DS", "DS", "nds", "#d8dde6",
            new String[]{"nds", "melonds", "ds"}, "nds,zip,7z,dsi",
            new String[]{"Nintendo - Nintendo DS"}, new String[]{"nds"}, null,
            extraUri("melonDS", "me.magnum.melonds", ".ui.emulator.EmulatorActivity", "me.magnum.melonds.LAUNCH_ROM", "uri", 0),
            extraUri("melonDS Nightly", "me.magnum.melonds.nightly", "me.magnum.melonds.ui.emulator.EmulatorActivity", "me.magnum.melonds.nightly.LAUNCH_ROM", "uri", 0),
            data("DraStic", "com.dsemu.drastic", ".DraSticActivity", null, CLEAR),
            ra("melondsds")),
        new Platform("n3ds", "Nintendo 3DS", "3DS", "n3ds", "#ff5a5f",
            new String[]{"n3ds", "3ds"}, "3ds,cci,cxi,cia,3dsx,app,zcci,zcxi",
            new String[]{"Nintendo - Nintendo 3DS"}, new String[]{"n3ds", "3ds"}, null,
            data("Azahar", "org.azahar_emu.azahar", CITRA_ACT, Intent.ACTION_VIEW, CLEAR),
            data("Azahar (Lime3DS)", "io.github.lime3ds.android", CITRA_ACT, Intent.ACTION_VIEW, CLEAR),
            data("Citra", "org.citra.citra_emu", CITRA_ACT, Intent.ACTION_VIEW, CLEAR),
            data("Mandarine", "io.github.mandarine3ds.mandarine", ".activities.EmulationActivity", Intent.ACTION_VIEW, CLEAR)),
        new Platform("switch", "Nintendo Switch", "Switch", "switch", "#e60012",
            new String[]{"switch", "nsw"}, "nsp,xci,nca,nro,nso",
            null, new String[]{"switch"}, null,
            data("Eden Duo", "dev.igawa6.edenduo", YUZU_ACT, NFC, 0),
            data("Eden", "dev.eden.eden_emulator", YUZU_ACT, NFC, 0),
            data("Eden Nightly", "dev.eden.eden_emulator.nightly", YUZU_ACT, NFC, 0),
            data("Citron", "org.citron.citron_emu", ".activities.EmulationActivity", NFC, 0)),
        new Platform("psx", "PlayStation", "PS1", "psx", "#9aa3b5",
            new String[]{"psx", "ps1", "ps"}, "chd,cue,pbp,m3u,iso,ecm",
            new String[]{"Sony - PlayStation"}, new String[]{"psx", "ps"}, null,
            extraUri("DuckStation", "com.github.stenzek.duckstation", ".EmulationActivity", Intent.ACTION_MAIN, "bootPath", CLEAR),
            ra("swanstation"), ra("pcsx_rearmed")),
        new Platform("gba", "Game Boy Advance", "GBA", "gba", "#6a5acd",
            new String[]{"gba"}, "gba,zip,7z", new String[]{"Nintendo - Game Boy Advance"}, new String[]{"gba"}, null,
            ra("mgba")),
        new Platform("gbc", "Game Boy Color", "GBC", "gb", "#8bc34a",
            new String[]{"gbc"}, "gbc,zip,7z", new String[]{"Nintendo - Game Boy Color"}, new String[]{"gbc"}, null,
            ra("gambatte")),
        new Platform("gb", "Game Boy", "GB", "gb", "#9e9e5e",
            new String[]{"gb"}, "gb,zip,7z", new String[]{"Nintendo - Game Boy"}, new String[]{"gb"}, null,
            ra("gambatte")),
        new Platform("nes", "Nintendo Entertainment System", "NES", "retro", "#c62828",
            new String[]{"nes", "famicom"}, "nes,fds,zip,7z", new String[]{"Nintendo - Nintendo Entertainment System"}, new String[]{"nes"}, null,
            ra("mesen"), ra("fceumm")),
        new Platform("snes", "Super Nintendo", "SNES", "retro", "#7e57c2",
            new String[]{"snes", "sfc"}, "sfc,smc,zip,7z", new String[]{"Nintendo - Super Nintendo Entertainment System"}, new String[]{"snes"}, null,
            ra("snes9x")),
        new Platform("n64", "Nintendo 64", "N64", "retro", "#2e7d32",
            new String[]{"n64"}, "z64,n64,v64,zip,7z", new String[]{"Nintendo - Nintendo 64"}, new String[]{"n64"}, null,
            ra("mupen64plus_next")),
        new Platform("genesis", "Sega Genesis", "Genesis", "retro", "#37474f",
            new String[]{"genesis", "megadrive", "md"}, "md,gen,bin,smd,zip,7z", new String[]{"Sega - Mega Drive - Genesis"}, new String[]{"genesis", "megadrive"}, null,
            ra("genesis_plus_gx")),
        new Platform("dreamcast", "Sega Dreamcast", "Dreamcast", "retro", "#ff7043",
            new String[]{"dreamcast", "dc"}, "chd,gdi,cdi,m3u", new String[]{"Sega - Dreamcast"}, new String[]{"dc", "dreamcast"}, null,
            data("Redream", "io.recompiled.redream", ".MainActivity", Intent.ACTION_VIEW, CLEAR),
            ra("flycast")),
        new Platform("retroarch", "RetroArch", "RetroArch", "retroarch", "#9c27b0",
            new String[0], "", null, new String[0], "com.retroarch.aarch64"),
        new Platform("rpgmaker", "RPG Maker", "JoiPlay", "joiplay", "#ffb74d",
            new String[0], "", null, new String[0], "cyou.joiplay.joiplay"),
    };

    static Platform byId(String id) {
        for (Platform p : ALL) if (p.id.equals(id)) return p;
        return null;
    }

    static Platform byFolder(String folder) {
        String f = folder.toLowerCase(Locale.ROOT);
        for (Platform p : ALL) for (String a : p.folders) if (a.equals(f)) return p;
        return null;
    }

    static boolean installed(Context c, String pkg) {
        if (pkg == null) return false;
        try {
            c.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static String retroArchPkg(Context c) {
        for (String p : RA_PKGS) if (installed(c, p)) return p;
        return null;
    }

    static boolean available(Context c, Player pl) {
        if (pl.mode == Mode.RETROARCH) return retroArchPkg(c) != null;
        return installed(c, pl.pkg);
    }

    static Player resolve(Context c, Platform p, String preferredPkg) {
        if (preferredPkg != null && !preferredPkg.isEmpty()) {
            for (Player pl : p.players) {
                String key = pl.mode == Mode.RETROARCH ? "retroarch:" + pl.core : pl.pkg;
                if (preferredPkg.equals(key) && available(c, pl)) return pl;
            }
        }
        for (Player pl : p.players) if (available(c, pl)) return pl;
        return null;
    }

    static String playerKey(Player pl) {
        return pl.mode == Mode.RETROARCH ? "retroarch:" + pl.core : pl.pkg;
    }

    /** Platforms as JSON for the UI, with which emulator would run each one. */
    static JSONArray json(Context c) {
        JSONArray arr = new JSONArray();
        for (Platform p : ALL) {
            JSONObject o = Util.obj("id", p.id, "name", p.name, "short", p.shortName, "glyph", p.glyph, "accent", p.accent);
            JSONArray players = new JSONArray();
            for (Player pl : p.players) {
                if (!available(c, pl)) continue;
                players.put(Util.obj("key", playerKey(pl), "name", pl.name,
                        "pkg", pl.mode == Mode.RETROARCH ? retroArchPkg(c) : pl.pkg));
            }
            Util.put(o, "players", players);
            Player def = resolve(c, p, App.I.store.preferredPlayer(p.id));
            if (def != null) {
                Util.put(o, "emulator", def.name);
                Util.put(o, "emulatorPkg", def.mode == Mode.RETROARCH ? retroArchPkg(c) : def.pkg);
            }
            if (p.appPkg != null) {
                Util.put(o, "appPkg", p.appPkg);
                Util.put(o, "appInstalled", installed(c, p.appPkg));
            }
            arr.put(o);
        }
        return arr;
    }

    /** Launches a scanned game. Returns null on success or a message for the user. */
    static String launch(Context c, JSONObject game, String preferred) {
        Platform p = byId(game.optString("platform"));
        if (p == null) return "Unknown platform";
        if (preferred == null || preferred.isEmpty()) preferred = App.I.store.preferredPlayer(p.id);
        Player pl = resolve(c, p, preferred);
        if (pl == null) return "No emulator for " + p.name + " is installed";
        File rom = new File(game.optString("path"));
        if (!rom.exists()) return "Game file not found";
        Intent i = new Intent();
        int grant = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        switch (pl.mode) {
            case DATA: {
                Uri u = romUri(c, rom);
                i.setComponent(new ComponentName(pl.pkg, pl.cls));
                if (pl.action != null) i.setAction(pl.action);
                i.setDataAndType(u, "application/octet-stream");
                i.setClipData(ClipData.newRawUri("", u));
                i.addFlags(grant);
                break;
            }
            case EXTRA_URI: {
                Uri u = romUri(c, rom);
                i.setComponent(new ComponentName(pl.pkg, pl.cls));
                if (pl.action != null) i.setAction(pl.action);
                i.putExtra(pl.extra, u.toString());
                i.setClipData(ClipData.newRawUri("", u));
                i.addFlags(grant);
                c.grantUriPermission(pl.pkg, u, grant);
                break;
            }
            case EXTRA_PATH: {
                i.setComponent(new ComponentName(pl.pkg, pl.cls));
                if (pl.action != null) i.setAction(pl.action);
                i.putExtra(pl.extra, rom.getAbsolutePath());
                break;
            }
            case RETROARCH: {
                String ra = retroArchPkg(c);
                if (ra == null) return "RetroArch is not installed";
                String sd = Environment.getExternalStorageDirectory().getAbsolutePath();
                String ext = sd + "/Android/data/" + ra + "/files";
                i.setComponent(new ComponentName(ra, RA_ACTIVITY));
                i.setAction(Intent.ACTION_MAIN);
                i.putExtra("ROM", rom.getAbsolutePath());
                i.putExtra("LIBRETRO", "/data/data/" + ra + "/cores/" + pl.core + "_libretro_android.so");
                i.putExtra("CONFIGFILE", ext + "/retroarch.cfg");
                i.putExtra("IME", "com.android.inputmethod.latin/.LatinIME");
                i.putExtra("DATADIR", "/data/data/" + ra);
                i.putExtra("SDCARD", sd);
                i.putExtra("EXTERNAL", ext);
                try {
                    ApplicationInfo ai = c.getPackageManager().getApplicationInfo(ra, 0);
                    i.putExtra("APK", ai.sourceDir);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                break;
            }
            case APP: {
                return App.I.apps.launch(pl.pkg) ? null : "Couldn't open " + pl.name;
            }
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | pl.flags);
        try {
            c.startActivity(i, Screens.top());
            return null;
        } catch (Exception e) {
            return "Couldn't start " + pl.name + ": " + e.getMessage();
        }
    }

    /**
     * A content URI the emulator can open. Prefers a document URI under a folder the user
     * granted through the system picker (what other frontends use); otherwise falls back
     * to this app's own read-only provider.
     */
    static Uri romUri(Context c, File f) {
        String path = f.getAbsolutePath();
        String sd = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
        if (path.startsWith(sd)) {
            String docId = "primary:" + path.substring(sd.length());
            List<UriPermission> perms = c.getContentResolver().getPersistedUriPermissions();
            for (UriPermission up : perms) {
                Uri tree = up.getUri();
                if (!up.isReadPermission() || !DocumentsContract.isTreeUri(tree)) continue;
                if (!"com.android.externalstorage.documents".equals(tree.getAuthority())) continue;
                String treeId = DocumentsContract.getTreeDocumentId(tree);
                if (docId.startsWith(treeId + "/")) return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
            }
        }
        return RomProvider.uriFor(f);
    }

    static boolean hasRomTree(Context c, String romRoot) {
        String sd = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
        if (!romRoot.startsWith(sd)) return false;
        String want = "primary:" + romRoot.substring(sd.length());
        for (UriPermission up : c.getContentResolver().getPersistedUriPermissions()) {
            Uri tree = up.getUri();
            if (!DocumentsContract.isTreeUri(tree)) continue;
            String id = DocumentsContract.getTreeDocumentId(tree);
            if (want.equals(id) || want.startsWith(id + "/")) return true;
        }
        return false;
    }
}
