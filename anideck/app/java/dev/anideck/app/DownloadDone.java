package dev.anideck.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The download manager finished (or gave up on) one of AniDeck's downloads, maybe with the app closed. */
public final class DownloadDone extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (id >= 0) Downloads.get(c).completed(id);
    }
}
