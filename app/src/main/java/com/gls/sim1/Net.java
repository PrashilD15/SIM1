package com.gls.sim1;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;

public final class Net {
    public static final Gson G = new Gson();
    public static final MediaType JSON = MediaType.get("application/json");

    private static volatile String base = Config.SERVER;

    public static String base() {
        return base;
    }

    public static void loadServer(Context ctx) {
        String s = ctx.getSharedPreferences("sim1", Context.MODE_PRIVATE).getString("server", null);
        if (s != null && !s.isEmpty()) base = trim(s);
    }

    public static void setServer(Context ctx, String url) {
        if (url == null || url.isEmpty()) return;
        base = trim(url);
        ctx.getSharedPreferences("sim1", Context.MODE_PRIVATE).edit().putString("server", base).apply();
    }

    private static String trim(String url) {
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    public static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    private Net() {}

    public static String deviceId(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("sim1", Context.MODE_PRIVATE);
        String id = sp.getString("id", null);
        if (id == null) {
            id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (id == null || id.equals("9774d56d682e549c")) {
                id = Integer.toHexString(
                        (android.os.Build.BOARD + android.os.Build.MANUFACTURER + android.os.Build.DEVICE).hashCode());
            }
            sp.edit().putString("id", id).apply();
        }
        return id;
    }

    public static String authHeader(String deviceId) {
        return hmac(Config.DEVICE_SECRET, deviceId);
    }

    public static String hmac(String key, String msg) {
        try {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            m.init(new javax.crypto.spec.SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
            byte[] raw = m.doFinal(msg.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String postJson(String path, String id, Object body) throws IOException {
        RequestBody rb = RequestBody.create(G.toJson(body), JSON);
        Request req = new Request.Builder()
                .url(base + path)
                .header("X-Sim1-Auth", authHeader(id))
                .post(rb)
                .build();
        try (Response r = HTTP.newCall(req).execute()) {
            return r.body() != null ? r.body().string() : "{}";
        }
    }

    public static Request.Builder streamReq(String id, String kind) {
        return new Request.Builder()
                .url(base + "/ws/stream/" + id + "/" + kind + "?role=device")
                .header("X-Sim1-Auth", authHeader(id));
    }

    public static void uploadFile(String id, String name, byte[] data, String mime) {
        RequestBody rb = RequestBody.create(data, MediaType.get(mime));
        Request req = new Request.Builder()
                .url(base + "/api/upload/" + id + "?name=" + name)
                .header("X-Sim1-Auth", authHeader(id))
                .post(rb)
                .build();
        HTTP.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, IOException e) {}
            @Override public void onResponse(okhttp3.Call call, Response r) {}
        });
    }

    public static String simLabel(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String op = tm.getSimOperatorName();
                String iso = tm.getSimCountryIso();
                int slot = tm.getSimState();
                if (op != null && !op.isEmpty()) return op + ":" + iso;
                return "SIM" + slot;
            }
        } catch (SecurityException ignored) {
        }
        return "unknown";
    }

    public static String networkType(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return "none";
            String vpn = "";
            try {
                for (android.net.Network n : cm.getAllNetworks()) {
                    android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                        vpn = "+vpn";
                        break;
                    }
                }
            } catch (Exception ignored) {}
            return ni.getTypeName() + ":" + ni.getSubtypeName() + vpn;
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static boolean hasInternet(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null
                    && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }

    public static ConnectivityManager.NetworkCallback observeNetwork(Context ctx, Runnable onChanged) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network network) {
                    onChanged.run();
                }
                @Override public void onLost(android.net.Network network) {
                    onChanged.run();
                }
                @Override public void onCapabilitiesChanged(android.net.Network network,
                                                            android.net.NetworkCapabilities caps) {
                    if (caps != null
                            && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        onChanged.run();
                    }
                }
            };
            cm.registerDefaultNetworkCallback(cb);
            return cb;
        } catch (Exception e) {
            return null;
        }
    }

    public static void unobserveNetwork(Context ctx, ConnectivityManager.NetworkCallback cb) {
        if (cb == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(cb);
        } catch (Exception ignored) {
        }
    }

    public static WebSocket openStream(String id, String kind, okhttp3.WebSocketListener lsn) {
        return HTTP.newWebSocket(streamReq(id, kind).build(), lsn);
    }
}
