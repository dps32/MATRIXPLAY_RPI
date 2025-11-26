package com.piomatter;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

public final class PioMatter {
    static {
        System.loadLibrary("piomatterjni");
    }
    private long handle;

    public static final class FB {
        public final ByteBuffer data;
        public final int width, height, strideBytes, bpp;

        public FB(ByteBuffer data, int w, int h, int strideBytes, int bpp) {
            this.data = data;
            this.width = w;
            this.height = h;
            this.strideBytes = strideBytes;
            this.bpp = bpp;
        }
    }

    private static native long nativeOpen(int w, int h, int addrLines, int lanes, int brightness, int fpsCap);

    private static native void nativeClose(long h);

    private static native int nativeSetBrightness(long h, int v);

    private static native int nativeSwap(long h);

    private static native FB nativeMapFramebuffer(long h);

    private static native int nativePutPixel(long h, int x, int y, int r, int g, int b);

    public PioMatter(int w, int h, int addrLines, int lanes, int brightness, int fpsCap) {
        handle = nativeOpen(w, h, addrLines, lanes, brightness, fpsCap);
        if (handle == 0)
            throw new RuntimeException("No s'ha pogut obrir Piomatter");
    }

    public void close() {
        if (handle != 0) {
            nativeClose(handle);
            handle = 0;
        }
    }

    public void setBrightness(int v) {
        if (nativeSetBrightness(handle, v) != 0)
            throw new RuntimeException("setBrightness failed");
    }

    public void swap() {
        if (nativeSwap(handle) != 0)
            throw new RuntimeException("swap failed");
    }

    public FB mapFramebuffer() {
        return nativeMapFramebuffer(handle);
    }

    public void putPixel(int x, int y, int r, int g, int b) {
        if (nativePutPixel(handle, x, y, r, g, b) != 0)
            throw new RuntimeException("putPixel failed");
    }

    public static void flushBlack(PioMatter pm, PioMatter.FB fb, int frames, int delayMs) throws InterruptedException {
        int total = fb.height * fb.strideBytes;
        for (int i = 0; i < total; i++)
            fb.data.put(i, (byte) 0);
        for (int i = 0; i < frames; i++) {
            pm.swap();
            Thread.sleep(delayMs);
        }
    }

    public static void copyBufferedImageToRGB888(BufferedImage src, ByteBuffer dstFB, int stride, int width, int height,
            int brightness) {
        final int b = Math.max(0, Math.min(255, brightness));
        for (int y = 0; y < height; y++) {
            int rowOff = y * stride;
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y); // 0xFFRRGGBB (INT_RGB)
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int bl = (rgb) & 0xFF;

                if (b != 255) {
                    r = (r * b) / 255;
                    g = (g * b) / 255;
                    bl = (bl * b) / 255;
                }

                int off = rowOff + x * 3;
                dstFB.put(off, (byte) r);
                dstFB.put(off + 1, (byte) g);
                dstFB.put(off + 2, (byte) bl);
            }
        }
    }
}
