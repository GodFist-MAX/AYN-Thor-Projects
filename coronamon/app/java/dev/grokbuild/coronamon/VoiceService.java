package dev.grokbuild.coronamon;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * Runs only while Coronamon is listening to you. Android hands an app without a screen of its own
 * the microphone only through a foreground service of the microphone type, started while his
 * windows are on screen.
 */
public final class VoiceService extends Service {
    static final int NOTIF = 2;
    static final String ACTION_STOP = "dev.grokbuild.coronamon.VOICE_STOP";
    /** In the foreground, so the mic works. */
    static volatile boolean up;

    static void start(Context ctx) {
        try {
            ctx.startForegroundService(new Intent(ctx, VoiceService.class));
        } catch (Exception e) {
            Log.w("voice service start: " + e);
        }
    }

    static void stop(Context ctx) {
        up = false;
        ctx.stopService(new Intent(ctx, VoiceService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            PetService s = PetService.instance;
            if (s != null) s.voice.stop();
            stopSelf();
            return START_NOT_STICKY;
        }
        Intent stop = new Intent(this, VoiceService.class).setAction(ACTION_STOP);
        Notification n = new Notification.Builder(this, PetService.CH_VOICE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Coronamon is listening")
                .setContentText("Talk to him! Tap him when you're done.")
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "Stop",
                        PendingIntent.getService(this, 7, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build())
                .build();
        try {
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            up = true;
        } catch (Exception e) {
            Log.w("voice service: " + e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        up = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
