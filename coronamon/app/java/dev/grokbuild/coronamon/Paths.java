package dev.grokbuild.coronamon;

import android.os.Environment;

import java.io.File;

/** Shared storage that Grok Build reaches over adb as /sdcard/Coronamon. */
final class Paths {
    static final File ROOT = new File(Environment.getExternalStorageDirectory(), "Coronamon");
    static final File INBOX = new File(ROOT, "inbox");
    static final File APKS = new File(ROOT, "apks");
    static final File REPORTS = new File(ROOT, "reports");
    static final File DOWNLOADS = new File(ROOT, "downloads");
    static final File REGISTRY_MIRROR = new File(ROOT, "registry.json");
    static final File STATUS_MIRROR = new File(ROOT, "status.json");

    private Paths() {
    }
}
