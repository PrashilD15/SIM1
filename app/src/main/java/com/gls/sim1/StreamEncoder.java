package com.gls.sim1;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public final class StreamEncoder {

    private static final ThreadLocal<ByteArrayOutputStream> JPEG_BUF =
            new ThreadLocal<ByteArrayOutputStream>() {
                @Override protected ByteArrayOutputStream initialValue() {
                    return new ByteArrayOutputStream(64 * 1024);
                }
            };

    private StreamEncoder() {}

    public static byte[] toJpeg(Bitmap bmp, int quality) {
        // Bitmap.compress() throws IllegalStateException on a HARDWARE bitmap.
        // wrapHardwareBuffer() hands us exactly that, so transparently soft-copy
        // any hardware bitmap before encoding.
        Bitmap src = bmp;
        boolean copied = false;
        if (bmp.getConfig() == Bitmap.Config.HARDWARE) {
            try {
                src = bmp.copy(Bitmap.Config.ARGB_8888, false);
                copied = src != null;
            } catch (Throwable t) {
                src = null;
            }
            if (src == null) return new byte[0];
        }
        try {
            ByteArrayOutputStream os = JPEG_BUF.get();
            os.reset();
            src.compress(Bitmap.CompressFormat.JPEG, quality, os);
            return os.toByteArray();
        } finally {
            if (copied && src != bmp) src.recycle();
        }
    }

    public static Bitmap scale(Bitmap src, int maxW) {
        if (src.getWidth() <= maxW) return src;
        float s = (float) maxW / src.getWidth();
        Bitmap out = Bitmap.createScaledBitmap(src, maxW, Math.max(1, (int) (src.getHeight() * s)), true);
        if (out != src) src.recycle();
        return out;
    }

    public static Bitmap rotate(Bitmap src, int deg) {
        if (deg == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src) src.recycle();
        return out;
    }

    public static byte[] yuv420ToJpeg(Image img, int quality) {
        return yuv420ToJpeg(img, quality, 0);
    }

    public static byte[] yuv420ToJpeg(Image img, int quality, int rotationDeg) {
        byte[] nv21 = yuv420ToNv21(img);
        int w = img.getWidth(), h = img.getHeight();
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, w, h), quality, os);
        byte[] jpeg = os.toByteArray();
        if (rotationDeg % 360 == 0) return jpeg;
        Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bmp == null) return jpeg;
        Bitmap rot = rotate(bmp, rotationDeg);
        ByteArrayOutputStream os2 = new ByteArrayOutputStream();
        rot.compress(Bitmap.CompressFormat.JPEG, quality, os2);
        rot.recycle();
        return os2.toByteArray();
    }

    public static byte[] yuv420ToNv21(Image img) {
        Image.Plane[] p = img.getPlanes();
        ByteBuffer y = p[0].getBuffer();
        ByteBuffer u = p[1].getBuffer();
        ByteBuffer v = p[2].getBuffer();
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] nv21 = new byte[w * h * 3 / 2];
        int yStride = p[0].getRowStride();
        int pos = 0;
        byte[] yRow = new byte[Math.max(1, yStride)];
        for (int r = 0; r < h; r++) {
            int off = r * yStride;
            int avail = y.limit() - off;
            if (avail <= 0) { pos += w; continue; }
            int n = Math.min(yStride, avail);
            if (yRow.length < n) yRow = new byte[n];
            y.position(off);
            y.get(yRow, 0, n);
            System.arraycopy(yRow, 0, nv21, pos, Math.min(w, n));
            pos += w;
        }
        int uStride = p[1].getRowStride();
        int vStride = p[2].getRowStride();
        int uPixStride = p[1].getPixelStride();
        int vPixStride = p[2].getPixelStride();
        int uLimit = u.limit();
        int vLimit = v.limit();
        for (int r = 0; r < h / 2; r++) {
            for (int c = 0; c < w / 2; c++) {
                int vi = r * vStride + c * vPixStride;
                int ui = r * uStride + c * uPixStride;
                // Row/pixel stride padding can push the last samples past the
                // plane limit on some devices; clamp instead of throwing so the
                // rest of the frame survives.
                nv21[pos++] = (vi >= 0 && vi < vLimit) ? v.get(vi) : (byte) 128;
                nv21[pos++] = (ui >= 0 && ui < uLimit) ? u.get(ui) : (byte) 128;
            }
        }
        return nv21;
    }
}
