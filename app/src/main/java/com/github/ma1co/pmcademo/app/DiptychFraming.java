package com.github.ma1co.pmcademo.app;

import android.graphics.Rect;

/**
 * Shared diptych framing math.
 * Keeps the live preview, overlay guides, and AF bracket aligned with the
 * native stitcher, which preserves the center 50% of each source image.
 */
public final class DiptychFraming {
    private DiptychFraming() {}

    public static int getPreviewOffset(int width, boolean thumbOnLeft) {
        int quarter = width / 4;
        return thumbOnLeft ? quarter : -quarter;
    }

    public static int getActiveCenterX(int width, boolean thumbOnLeft) {
        return (width / 2) + getPreviewOffset(width, thumbOnLeft);
    }

    public static Rect getActivePaneRect(int width, int height, boolean thumbOnLeft) {
        int half = width / 2;
        if (thumbOnLeft) {
            return new Rect(half, 0, width, height);
        }
        return new Rect(0, 0, half, height);
    }

    public static Rect getThumbnailPaneRect(int width, int height, boolean thumbOnLeft) {
        int half = width / 2;
        if (thumbOnLeft) {
            return new Rect(0, 0, half, height);
        }
        return new Rect(half, 0, width, height);
    }
}
