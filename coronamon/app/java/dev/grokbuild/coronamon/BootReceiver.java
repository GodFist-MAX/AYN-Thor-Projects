package dev.grokbuild.coronamon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings Coronamon back after a reboot or an update of himself. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            PetService.start(ctx);
        } catch (Exception e) {
            Log.w("start after " + intent.getAction(), e);
        }
    }
}
