package dev.anideck.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Refreshes the AniList lists and posts new-episode alerts every 30 minutes. It only runs on
 * Wi-Fi (an unmetered network), so it pauses by itself while the connection is down.
 */
public class SyncJob extends JobService {
    private static final int ID = 1;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(ID) != null) return;
        js.schedule(new JobInfo.Builder(ID, new ComponentName(c, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPeriodic(30 * 60_000L, 10 * 60_000L)
                .setPersisted(true)
                .build());
    }

    @Override public boolean onStartJob(JobParameters params) {
        App.I.io.execute(() -> jobFinished(params, !App.I.library.syncNow(false)));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return true;
    }
}
