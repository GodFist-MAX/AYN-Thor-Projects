package dev.grokbuild.coronamon;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Installs APKs with a PackageInstaller session. Updates to apps whose installer of record is
 * Coronamon (and Coronamon itself) go through without a prompt; anything else shows the system
 * confirmation on the screen he's on. With Shizuku granted, shell `pm install` is used instead so
 * nothing needs a tap.
 */
final class Installer {
    interface Done {
        void done(boolean ok, String message);
    }

    static final class Apk {
        String pkg, versionName;
        long versionCode;
        String label;
    }

    private static final Map<Integer, Done> pending = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> displays = new ConcurrentHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Installer() {
    }

    static Apk inspect(Context ctx, File apk) {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), 0);
        if (info == null) return null;
        Apk a = new Apk();
        a.pkg = info.packageName;
        a.versionName = info.versionName;
        a.versionCode = info.getLongVersionCode();
        try {
            info.applicationInfo.sourceDir = apk.getPath();
            info.applicationInfo.publicSourceDir = apk.getPath();
            a.label = pm.getApplicationLabel(info.applicationInfo).toString();
        } catch (Exception e) {
            a.label = a.pkg;
        }
        return a;
    }

    /** null if the APK is signed like the installed app (or it isn't installed), else why not. */
    static String signatureProblem(Context ctx, File apk, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        try {
            PackageInfo installed = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo incoming = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (incoming == null || incoming.signingInfo == null) return "the APK isn't signed";
            SigningInfo a = installed.signingInfo, b = incoming.signingInfo;
            Signature[] have = a.hasMultipleSigners() ? a.getApkContentsSigners() : a.getSigningCertificateHistory();
            Signature[] got = b.hasMultipleSigners() ? b.getApkContentsSigners() : b.getSigningCertificateHistory();
            for (Signature s : got) {
                for (Signature h : have) if (Arrays.equals(s.toByteArray(), h.toByteArray())) return null;
            }
            return "it's signed with a different key than the installed " + pkg;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Installs on a worker thread; done runs on the main thread. */
    static void install(Context ctx, File apk, int displayId, Done done) {
        new Thread(() -> {
            try {
                if (Shell.granted()) {
                    // Given a path, pm has system_server open the file, and it may not read shared
                    // storage (where downloads are), so the shell hands the APK over on stdin.
                    String path = apk.getAbsolutePath().replace("'", "'\\''");
                    Shell.Result r = Shell.run("pm install -r -i " + Registry.SELF + " -S " + apk.length() + " < '" + path + "'", 180_000);
                    boolean ok = r.code == 0 && r.out.contains("Success");
                    String msg = ok ? "Installed" : reason(r);
                    main.post(() -> done.done(ok, msg));
                    return;
                }
                session(ctx, apk, displayId, done);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> done.done(false, msg));
            }
        }, "install").start();
    }

    /** Why pm install failed. The Thor prints an "avc:" line before every pm command, which isn't it. */
    static String reason(Shell.Result r) {
        String first = null;
        for (String line : (r.err + "\n" + r.out).split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("avc:")) continue;
            if (t.startsWith("Failure") || t.startsWith("Error")) return t;
            if (first == null) first = t;
        }
        if (first != null) return first;
        return r.code == -1 ? "pm install took too long" : "pm install failed (exit " + r.code + ")";
    }

    private static void session(Context ctx, File apk, int displayId, Done done) throws IOException {
        Apk a = inspect(ctx, apk);
        if (a == null) throw new IOException(apk.getName() + " isn't a valid APK");
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(a.pkg);
        p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        p.setInstallReason(PackageManager.INSTALL_REASON_USER);
        p.setSize(apk.length());
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            pending.put(id, done);
            displays.put(id, displayId);
            Intent i = new Intent(ctx, Receiver.class).putExtra("sid", id);
            PendingIntent pend = PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pend.getIntentSender());
        } catch (IOException | RuntimeException e) {
            pending.remove(id);
            try {
                pi.abandonSession(id);
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            int sid = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, intent.getIntExtra("sid", -1));
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    Integer d = displays.get(sid);
                    try {
                        ctx.startActivity(confirm, Screens.on(d == null ? Screens.TOP_ID : d));
                    } catch (Exception e) {
                        ctx.startActivity(confirm);
                    }
                    PetService s = PetService.instance;
                    if (s != null) s.installNeedsTap();
                }
                return;
            }
            Done done = pending.remove(sid);
            displays.remove(sid);
            boolean ok = status == PackageInstaller.STATUS_SUCCESS;
            String text = ok ? "Installed" : explain(status, msg);
            if (done != null) done.done(ok, text);
        }
    }

    static String explain(int status, String msg) {
        String why;
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                why = "the install was cancelled";
                break;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                why = "the install was blocked";
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                why = "it conflicts with what's installed (different signature or a downgrade)";
                break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                why = "it isn't compatible with the Thor";
                break;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                why = "the APK is invalid";
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                why = "there isn't enough storage";
                break;
            default:
                why = "it failed";
        }
        return msg == null || msg.isEmpty() ? why : why + " (" + msg + ")";
    }
}
