package com.gls.sim1;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class WatchdogReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.gls.sim1.WATCHDOG";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        Foreground.set(app);
        Watchdog.ensureService(app);
        Watchdog.scheduleAlarm(app, 60_000L);
    }
}
