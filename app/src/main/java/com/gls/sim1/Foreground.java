package com.gls.sim1;

import android.content.Context;

public final class Foreground {
    private static volatile Context APP;

    private Foreground() {}

    public static void set(Context ctx) {
        APP = ctx.getApplicationContext();
    }

    public static Context ctx() {
        if (APP == null) {
            synchronized (Foreground.class) {
                if (APP == null) {
                    try {
                        Class<?> c = Class.forName("android.app.ActivityThread");
                        APP = (Context) c.getMethod("currentApplication").invoke(null);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return APP;
    }
}
