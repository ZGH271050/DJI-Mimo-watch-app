package com.watch.osmo.ui;

/**
 * Geometry helpers for a round (circular) display.
 *
 * <p>The watch reports no usable safe area at all: DisplayCutout insets and the rounded-corner
 * frame both measure as empty rectangles, and the window is edge-to-edge. Anything drawn outside
 * the inscribed circle is physically cut off by the panel, so every layout decision here is made
 * from plain circle arithmetic: a point (x, y) is visible only while
 * (x-cx)^2 + (y-cy)^2 &lt;= r^2.
 */
public final class Round {

    private Round() {
    }

    /** Half width usable at vertical position {@code y} for a circle centered at cy with radius r. */
    public static float halfWidthAt(float y, float cy, float r) {
        float dy = y - cy;
        float v = r * r - dy * dy;
        return v <= 0f ? 0f : (float) Math.sqrt(v);
    }

    /**
     * Safe half width for a box spanning [top, bottom]. The tightest row wins, so a tall box is
     * constrained by whichever of its ends sits closer to the top or bottom of the circle.
     */
    public static float safeHalfWidth(float top, float bottom, float cy, float r, float margin) {
        float a = halfWidthAt(top, cy, r);
        float b = halfWidthAt(bottom, cy, r);
        float w = Math.min(a, b) - margin;
        return w < 0f ? 0f : w;
    }

    /** True when the whole rectangle lies inside the circle. Used by the geometry self-check. */
    public static boolean fitsInCircle(float left, float top, float right, float bottom,
                                       float cx, float cy, float r) {
        return inside(left, top, cx, cy, r) && inside(right, top, cx, cy, r)
                && inside(left, bottom, cx, cy, r) && inside(right, bottom, cx, cy, r);
    }

    private static boolean inside(float x, float y, float cx, float cy, float r) {
        float dx = x - cx;
        float dy = y - cy;
        return dx * dx + dy * dy <= r * r;
    }

    /** Visible fraction of a rectangle, 0..1. Useful for logging how badly a control is cut. */
    public static float visibleFraction(float left, float top, float right, float bottom,
                                       float cx, float cy, float r) {
        int steps = 16;
        int insideCount = 0;
        int total = 0;
        for (int i = 0; i < steps; i++) {
            float x = left + (right - left) * (i + 0.5f) / steps;
            for (int j = 0; j < steps; j++) {
                float y = top + (bottom - top) * (j + 0.5f) / steps;
                total++;
                if (inside(x, y, cx, cy, r)) {
                    insideCount++;
                }
            }
        }
        return total == 0 ? 0f : (float) insideCount / total;
    }
}
