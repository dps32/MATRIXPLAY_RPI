package com.piomatter;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;

/**
 * Simple FPS helper with smoothed FPS (EMA), frame duration, optional FPS
 * capping,
 * and a tiny overlay drawer for on-screen diagnostics.
 */
public class UtilsFPS {

    /**
     * Exponential moving average smoothing factor (0..1). Higher = faster response.
     */
    private final double alpha;
    private long frameStartNs = 0L;
    private long lastFrameDurationNs = 0L; // nanoseconds of the last completed frame
    private double emaFps = -1.0; // smoothed FPS (EMA), -1 indicates uninitialized

    /**
     * Creates an FPS helper with default smoothing (alpha = 0.12).
     */
    public UtilsFPS() {
        this(0.12);
    }

    /**
     * Creates an FPS helper with a custom smoothing factor.
     *
     * @param smoothingAlpha EMA factor in [0.01, 1.0]. Typical range: 0.08–0.2
     */
    public UtilsFPS(double smoothingAlpha) {
        this.alpha = Math.max(0.01, Math.min(1.0, smoothingAlpha));
    }

    /** Marks the beginning of a frame. Call this right before you start drawing. */
    public void beginFrame() {
        frameStartNs = System.nanoTime();
    }

    /**
     * Marks the end of a frame. Updates FPS statistics.
     *
     * @return frame duration in nanoseconds
     */
    public long endFrame() {
        long now = System.nanoTime();
        lastFrameDurationNs = Math.max(1L, now - frameStartNs);
        double instFps = 1_000_000_000.0 / lastFrameDurationNs;
        emaFps = (emaFps < 0.0) ? instFps : (alpha * instFps + (1.0 - alpha) * emaFps);
        return lastFrameDurationNs;
    }

    /**
     * @return the duration of the last frame in seconds.
     *         Can be used as a delta time (dt) for animations.
     */
    public double getDeltaSeconds() {
        return lastFrameDurationNs / 1_000_000_000.0;
    }

    /** @return smoothed FPS (EMA). Returns 0 until the first frame completes. */
    public double getFPS() {
        return emaFps < 0.0 ? 0.0 : emaFps;
    }

    /** @return last frame duration in milliseconds. */
    public double getFrameMs() {
        return lastFrameDurationNs / 1_000_000.0;
    }

    /**
     * Sleeps the remaining time to respect the desired FPS cap, if any.
     * Call this right after {@link #endFrame()}.
     *
     * @param fpsCap target FPS; if <= 0, does nothing
     */
    public void sleepToCap(int fpsCap) {
        if (fpsCap <= 0)
            return;
        long targetNs = 1_000_000_000L / Math.max(1, fpsCap);

        // We want a total frame time ~= targetNs. If we've already exceeded it, skip
        // sleep.
        long elapsedNs = lastFrameDurationNs;
        long remainingNs = targetNs - elapsedNs;
        if (remainingNs > 0L) {
            try {
                // Sleep with nanos precision
                Thread.sleep(remainingNs / 1_000_000L, (int) (remainingNs % 1_000_000L));
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Draws a tiny diagnostic overlay showing FPS and frame time (ms).
     *
     * @param g graphics context (already configured for your back-buffer)
     * @param x left position
     * @param y baseline Y
     */
    public void drawOverlay(Graphics2D g, int x, int y) {
        String text = String.format("FPS: %.1f", getFPS(), getFrameMs());
        Color prevColor = g.getColor();
        Font prevFont = g.getFont();

        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        // soft shadow for readability on LED matrices
        g.setColor(Color.BLACK);
        g.drawString(text, x + 1, y + 1);
        g.setColor(Color.WHITE);
        g.drawString(text, x, y);

        g.setFont(prevFont);
        g.setColor(prevColor);
    }

    /** Resets all statistics. */
    public void reset() {
        frameStartNs = 0L;
        lastFrameDurationNs = 0L;
        emaFps = -1.0;
    }

    /**
     * Ends the frame and (optionally) sleeps to respect FPS cap.
     * The reported FPS includes the sleep time (i.e., total frame time).
     *
     * @param fpsCap target FPS; if <= 0, does not sleep
     * @return total frame duration in nanoseconds (work + sleep)
     */
    public long endFrameAndCap(int fpsCap) {
        long now = System.nanoTime();
        long workNs = Math.max(1L, now - frameStartNs);

        long sleepNs = 0L;
        if (fpsCap > 0) {
            long targetNs = 1_000_000_000L / Math.max(1, fpsCap);
            long remaining = targetNs - workNs;
            if (remaining > 0L) {
                try {
                    Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                } catch (InterruptedException ignored) {
                }
                sleepNs = remaining;
            }
        }

        long totalNs = workNs + sleepNs;
        lastFrameDurationNs = totalNs;

        double instFps = 1_000_000_000.0 / totalNs;
        emaFps = (emaFps < 0.0) ? instFps : (alpha * instFps + (1.0 - alpha) * emaFps);
        return totalNs;
    }
}
