package dev.anideck.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;

/** Works through the download queue in the foreground, one episode at a time, with the screen on or off. */
public final class DownloadService extends Service {
    private static final int NOTE_ID = 7301;

    private Thread worker;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private long lastNote;

    @Override public IBinder onBind(Intent i) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTE_ID, note("Getting ready to download", null, -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::work, "downloads");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    private void work() {
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AniDeck:downloads");
        wake.acquire(6 * 3600_000L);
        wifi = getSystemService(WifiManager.class).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AniDeck:downloads");
        wifi.acquire();
        try {
            Downloads dl = Downloads.get(this);
            JSONObject job;
            while ((job = dl.next()) != null) {
                String id = App.str(job, "id");
                new Downloader(this, job, this::progress).run();
                JSONObject r = dl.get(id);
                if (r != null && "done".equals(App.str(r, "state"))) notifyDone(r);
            }
        } catch (RuntimeException e) {
            Log.w(App.TAG, "download queue stopped: " + e);
        } finally {
            if (wifi.isHeld()) wifi.release();
            if (wake.isHeld()) wake.release();
            App.I.main.post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                // Something queued just as the last one finished.
                Downloads.get(this).run();
            });
        }
    }

    private void progress(JSONObject job, int pct, String text) {
        long now = System.currentTimeMillis();
        if (text == null && now - lastNote < 1000) return;
        lastNote = now;
        int left = Downloads.get(this).queued();
        String title = App.str(job, "show") + " · Episode " + num(job.optDouble("ep"));
        String line = text != null ? text : pct >= 0 ? pct + "%" : "Downloading";
        if (left > 1) line += " · " + (left - 1) + " more in the queue";
        getSystemService(NotificationManager.class).notify(NOTE_ID, note(title, line, pct));
    }

    private Notification note(String title, String text, int pct) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_ROUTE, "#/downloads");
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, App.CHANNEL_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (text != null) b.setContentText(text);
        if (pct >= 0) b.setProgress(100, pct, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void notifyDone(JSONObject r) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        String id = App.str(r, "id");
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_DOWNLOAD, id);
        PendingIntent pi = PendingIntent.getActivity(this, id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, App.CHANNEL_DOWNLOADS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(App.str(r, "show"))
                .setContentText("Episode " + num(r.optDouble("ep")) + " is downloaded. Tap to watch it")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setGroup("downloads");
        File cover = Shelf.get(this).cover(App.str(r, "key"));
        Bitmap art = cover.isFile() ? BitmapFactory.decodeFile(cover.getPath()) : null;
        if (art != null) b.setLargeIcon(art);
        getSystemService(NotificationManager.class).notify("download", id.hashCode(), b.build());
    }

    static String num(double ep) {
        return ep == Math.floor(ep) ? String.valueOf((long) ep) : String.valueOf(ep);
    }

    @Override public void onDestroy() {
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }
}
