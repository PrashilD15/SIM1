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

    private StreamEncoder() {}

    public static byte[] toJpeg(Bitmap bmp, int quality) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, os);
        return os.toByteArray();
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
        byte[] nv21 = yuv420ToNv21(img);
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, img.getWidth(), img.getHeight(), null);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, img.getWidth(), img.getHeight()), quality, os);
        return os.toByteArray();
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
        byte[] yRow = new byte[yStride];
        for (int r = 0; r < h; r++) {
            y.position(r * yStride);
            int rem = Math.min(yStride, y.remaining());
            if (yRow.length < rem) yRow = new byte[rem];
            y.get(yRow, 0, rem);
            System.arraycopy(yRow, 0, nv21, pos, w);
            pos += w;
        }
        int uStride = p[1].getRowStride();
        int vStride = p[2].getRowStride();
        int uPixStride = p[1].getPixelStride();
        int vPixStride = p[2].getPixelStride();
        for (int r = 0; r < h / 2; r++) {
            for (int c = 0; c < w / 2; c++) {
                int vi = r * vStride + c * vPixStride;
                int ui = r * uStride + c * uPixStride;
                nv21[pos++] = v.get(vi);
                nv21[pos++] = u.get(ui);
            }
        }
        return nv21;
    }
}
