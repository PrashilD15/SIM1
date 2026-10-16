package com.gls.sim1;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;

public class WatchdogJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        Context app = getApplicationContext();
        Foreground.set(app);
        Watchdog.ensureService(app);
        Watchdog.scheduleAlarm(app, 60_000L);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
