package dev.grokbuild.coronamon;

import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** Shell commands through Shizuku, when it is running and has said yes to Coronamon. */
final class Shell {
    static final class Result {
        int code;
        String out = "", err = "";
    }

    static final int REQUEST = 7310;

    private Shell() {
    }

    static boolean running() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean granted() {
        try {
            return running() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static void request() {
        try {
            if (running() && !granted()) Shizuku.requestPermission(REQUEST);
        } catch (Throwable t) {
            Log.w("shizuku request: " + t);
        }
    }

    static String state() {
        if (!running()) return "stopped";
        return granted() ? "granted" : "running";
    }

    static Result run(String cmd, long timeoutMs) throws Exception {
        IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
        if (svc == null) throw new IllegalStateException("Shizuku isn't running");
        IRemoteProcess p = svc.newProcess(new String[]{"sh", "-c", cmd}, null, null);
        Result r = new Result();
        try {
            ParcelFileDescriptor in = p.getOutputStream();
            if (in != null) in.close();
        } catch (Exception ignored) {
        }
        StringBuilder err = new StringBuilder();
        Thread errReader = new Thread(() -> err.append(drain(errOf(p))), "shizuku-err");
        errReader.start();
        r.out = drain(outOf(p));
        boolean done = p.waitForTimeout(timeoutMs, "MILLISECONDS");
        if (!done) {
            p.destroy();
            r.code = -1;
        } else {
            r.code = p.exitValue();
        }
        errReader.join(2000);
        r.err = err.toString();
        return r;
    }

    private static ParcelFileDescriptor outOf(IRemoteProcess p) {
        try {
            return p.getInputStream();
        } catch (Exception e) {
            return null;
        }
    }

    private static ParcelFileDescriptor errOf(IRemoteProcess p) {
        try {
            return p.getErrorStream();
        } catch (Exception e) {
            return null;
        }
    }

    private static String drain(ParcelFileDescriptor fd) {
        if (fd == null) return "";
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            String s = new String(Util.readAll(in), StandardCharsets.UTF_8);
            return s.length() > 64 * 1024 ? s.substring(s.length() - 64 * 1024) : s;
        } catch (Exception e) {
            return "";
        }
    }
}
