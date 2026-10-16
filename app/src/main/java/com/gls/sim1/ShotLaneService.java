package com.gls.sim1;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.view.Display;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * A screenshot "lane".
 *
 * AOSP rate-limits AccessibilityService.takeScreenshot() to one request every
 * ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS (333ms), but that
 * budget is tracked per service component, not globally. Registering several of
 * these services and round-robining across them multiplies the ceiling
 * (1 lane ~3fps, 3 lanes ~9fps) while staying entirely inside the accessibility
 * screenshot path - no MediaProjection, so no casting indicator.
 *
 * Lane 1 is RatAccessibilityService itself; RatShotService2/3 are the extras.
 */
public abstract class ShotLaneService extends AccessibilityService {

    private static final CopyOnWriteArrayList<ShotLaneService> LANES = new CopyOnWriteArrayList<>();
    private static volatile int cursor;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (!LANES.contains(this)) LANES.add(this);
        android.util.Log.i("SIM1", "shot lane connected lanes=" + LANES.size());
    }

    @Override
    public void onInterrupt() {
        // lanes never issue gestures, so there is nothing to interrupt
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        // lanes subscribe to no event types; they exist only for their own
        // takeScreenshot() throttle budget
    }

    @Override
    public boolean onUnbind(Intent intent) {
        LANES.remove(this);
        android.util.Log.i("SIM1", "shot lane unbound lanes=" + LANES.size());
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        LANES.remove(this);
        super.onDestroy();
    }

    /** Connected lanes, minimum 1 (callers fall back to their own service). */
    public static int laneCount() {
        return Math.max(1, LANES.size());
    }

    /** Round-robin across the connected lanes; null when none are registered. */
    public static ShotLaneService next() {
        int n = LANES.size();
        if (n == 0) return null;
        int i = (cursor++) & 0x7fffffff;
        return LANES.get(i % n);
    }

    /**
     * Minimum global gap for a round-robin stream: each lane must still see at
     * least 334ms between its own requests, and a lane is reused every
     * laneCount() ticks.
     */
    public static int globalFloorMs() {
        return (334 + laneCount() - 1) / laneCount();
    }

    /** Fire one screenshot through this lane's own throttle budget. */
    public boolean fireShot(Executor exec, AccessibilityService.TakeScreenshotCallback cb) {
        if (Build.VERSION.SDK_INT < 30) return false;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, exec, cb);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
