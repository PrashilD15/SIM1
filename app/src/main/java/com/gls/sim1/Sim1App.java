package com.gls.sim1;

import android.app.Application;

public class Sim1App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Foreground.set(this);
        DeviceTier.init(this);

        final Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                android.util.Log.e("SIM1", "uncaught on " + t.getName(), e);
            } catch (Throwable ignored) {
            }
            try {
                Watchdog.ensureService(getApplicationContext());
                Watchdog.arm(getApplicationContext());
            } catch (Throwable ignored) {
            }
            if (prior != null) {
                prior.uncaughtException(t, e);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });

        Watchdog.arm(this);
    }
}
