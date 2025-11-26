package com.piomatter;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import javax.imageio.ImageIO;

/**
 * Utility class for drawing images using different fitting modes.
 *
 * Similar to CSS "object-fit": cover, contain, fill, center, etc.
 * 
 * It allows flexible image placement and scaling inside a target rectangle.
 */
public class UtilsImage {

    /**
     * Available fitting modes for drawing an image inside a destination rectangle.
     */
    public enum FitMode {
        COVER, // Fills the entire area (may crop)
        CONTAIN, // Shows the full image (may leave empty margins)
        STRETCH, // Fills by stretching (ignores aspect ratio)
        CENTER, // Centers the image without scaling
        TILE, // Repeats the image in a tiled pattern
        NONE // Draws as-is, no scaling or centering
    }

    /**
     * Loads an image from either the local file system or the application
     * classpath.
     *
     * @param filename the image filename or resource name to load
     * @return a {@link BufferedImage} if successfully loaded; {@code null} if the
     *         image
     *         could not be found or read
     */
    public static BufferedImage loadImage(String filename) {
        try {
            File f = new File(filename);
            if (f.exists())
                return ImageIO.read(f);

            // Use the current class loader instead of hardcoding DemoIETI
            URL url = Thread.currentThread()
                    .getContextClassLoader()
                    .getResource(filename);
            if (url != null)
                return ImageIO.read(url);

        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Draws an image inside a destination rectangle according to the specified
     * mode.
     *
     * @param g    the graphics context
     * @param img  the image to draw
     * @param dstX destination rectangle start X coordinate
     * @param dstY destination rectangle start Y coordinate
     * @param dstW destination rectangle width
     * @param dstH destination rectangle height
     * @param mode fitting mode to use ({@link FitMode})
     */
    public static void drawImageFit(Graphics2D g, BufferedImage img,
            int dstX, int dstY, int dstW, int dstH,
            FitMode mode) {

        int srcW = img.getWidth();
        int srcH = img.getHeight();

        switch (mode) {
            case COVER -> {
                // Scale image to fully cover the destination area (cropping as needed)
                double scale = Math.max(dstW / (double) srcW, dstH / (double) srcH);
                int scaledW = (int) Math.ceil(srcW * scale);
                int scaledH = (int) Math.ceil(srcH * scale);
                int sx = (scaledW - dstW) / 2;
                int sy = (scaledH - dstH) / 2;

                double inv = 1.0 / scale;
                int srcX1 = (int) Math.floor(sx * inv);
                int srcY1 = (int) Math.floor(sy * inv);
                int srcX2 = Math.min(srcW, srcX1 + (int) Math.ceil(dstW * inv));
                int srcY2 = Math.min(srcH, srcY1 + (int) Math.ceil(dstH * inv));

                g.drawImage(img,
                        dstX, dstY, dstX + dstW, dstY + dstH,
                        srcX1, srcY1, srcX2, srcY2, null);
            }

            case CONTAIN -> {
                // Scale image to fit completely inside the destination (may leave borders)
                double scale = Math.min(dstW / (double) srcW, dstH / (double) srcH);
                int newW = (int) Math.round(srcW * scale);
                int newH = (int) Math.round(srcH * scale);
                int x = dstX + (dstW - newW) / 2;
                int y = dstY + (dstH - newH) / 2;

                g.drawImage(img, x, y, x + newW, y + newH, 0, 0, srcW, srcH, null);
            }

            case STRETCH -> {
                // Stretch the image to exactly fill the rectangle (distorts aspect ratio)
                g.drawImage(img, dstX, dstY, dstX + dstW, dstY + dstH, 0, 0, srcW, srcH, null);
            }

            case CENTER -> {
                // Draw at original size, centered in the rectangle
                int x = dstX + (dstW - srcW) / 2;
                int y = dstY + (dstH - srcH) / 2;
                g.drawImage(img, x, y, null);
            }

            case TILE -> {
                // Repeat (tile) the image to fill the destination area
                for (int y = dstY; y < dstY + dstH; y += srcH) {
                    for (int x = dstX; x < dstX + dstW; x += srcW) {
                        g.drawImage(img, x, y, null);
                    }
                }
            }

            case NONE -> {
                // Draw without scaling or centering, top-left aligned
                g.drawImage(img, dstX, dstY, null);
            }
        }
    }
}
