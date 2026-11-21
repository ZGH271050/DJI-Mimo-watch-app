package com.watch.osmo.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * Vertical stack that keeps every child inside the inscribed circle of a round display.
 *
 * <p>Plain LinearLayout is exactly what breaks on this watch: a full-width row at the top or
 * bottom of a 480x480 circle pokes out of the panel and becomes invisible and untappable. Here
 * each child is measured against the width available at its own vertical position, then centred.
 *
 * <p>The layout is deliberately two-pass: a child's own height decides which row of the circle it
 * has to fit into, and its height can change once it is allowed less width (text wraps), so the
 * measurement is repeated with the narrowed width before the final layout.
 */
public class RoundColumn extends ViewGroup {

    private final float marginPx;
    private int spacingPx;
    /** Radius as a fraction of min(width, height) / 2. Kept slightly below 1 for panel tolerance. */
    private float radiusFraction;

    private float lastCx;
    private float lastCy;
    private float lastR;

    public RoundColumn(Context context) {
        this(context, null);
    }

    public RoundColumn(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        this.marginPx = 10f * density;
        this.spacingPx = (int) (8f * density);
        this.radiusFraction = 0.985f;
    }

    /**
     * Gap between children. Defaults to 8dp; a screen with many short children can tighten it, which
     * buys vertical room inside the circle without touching any child's own height.
     */
    public void setSpacingPx(int spacingPx) {
        if (this.spacingPx != spacingPx) {
            this.spacingPx = spacingPx;
            requestLayout();
        }
    }

    /**
     * Shrinks the circle the column lays out against. Used by the transfer screen, whose progress
     * ring hugs the panel edge: content measured against the full radius ends up drawn on top of the
     * ring, and the bottom button's corners poked straight through it.
     */
    public void setRadiusFraction(float fraction) {
        if (fraction > 0.2f && fraction < 1f && Math.abs(fraction - radiusFraction) > 0.001f) {
            this.radiusFraction = fraction;
            requestLayout();
        }
    }

    public float centerX() {
        return lastCx;
    }

    public float centerY() {
        return lastCy;
    }

    public float radius() {
        return lastR;
    }

    private void updateGeometry(int width, int height) {
        lastCx = width / 2f;
        lastCy = height / 2f;
        lastR = Math.min(width, height) / 2f * radiusFraction;
    }

    /** Width allowed for a child that will occupy [top, bottom]. */
    private int allowedWidth(float top, float bottom, int fullWidth) {
        float half = Round.safeHalfWidth(top, bottom, lastCy, lastR, marginPx);
        // A child that starts at y=0 sits in the row where the circle is a point, so the strict rule
        // yields zero and the child would be measured 0px wide: invisible, with no way for the UI to
        // recover. Fall back to what the circle allows across the child's own centre row, which is the
        // best case available. The extremes of such a child are clipped by the panel regardless, and no
        // width would have saved them.
        float centreHalf = Round.halfWidthAt((top + bottom) / 2f, lastCy, lastR) - marginPx;
        if (centreHalf > half) {
            half = centreHalf;
        }
        int w = (int) (half * 2f);
        if (w > fullWidth) {
            w = fullWidth;
        }
        if (w < 0) {
            w = 0;
        }
        return w;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        updateGeometry(width, height);

        int count = getChildCount();
        int visible = 0;
        for (int i = 0; i < count; i++) {
            if (getChildAt(i).getVisibility() != GONE) {
                visible++;
            }
        }
        int gap = visible > 1 ? spacingPx : 0;

        // Pass 1: natural heights at full width, so the stack's total height is known before any
        // child is narrowed.
        int[] heights = new int[count];
        int total = 0;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            heights[i] = child.getMeasuredHeight();
            total += heights[i] + gap;
        }
        if (visible > 0) {
            total -= gap;
        }

        // Pass 2: narrow each child to the chord of the circle **at the position it will actually be
        // laid out at**. Measuring from y=0 instead — which this used to do — evaluates the first
        // child in the row where the circle is a single point, so it came out 0px wide and silently
        // vanished. The header of the transfer screen disappeared exactly that way.
        float startY = Math.max(0f, (height - total) / 2f);
        for (int round = 0; round < 2; round++) {
            float y = startY;
            int newTotal = 0;
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                int prev = heights[i];
                int allowed = allowedWidth(y, y + prev, width);
                if (allowed < width) {
                    child.measure(MeasureSpec.makeMeasureSpec(allowed, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
                    heights[i] = child.getMeasuredHeight();
                } else {
                    heights[i] = prev;
                }
                y += heights[i] + gap;
                newTotal += heights[i] + gap;
            }
            if (visible > 0) {
                newTotal -= gap;
            }
            float nextStart = Math.max(0f, (height - newTotal) / 2f);
            if (Math.abs(nextStart - startY) < 2f) {
                total = newTotal;
                break;
            }
            startY = nextStart;
            total = newTotal;
        }
        setMeasuredDimension(width, resolveSize(total, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int height = b - t;
        updateGeometry(width, height);

        int count = getChildCount();
        int visible = 0;
        int stackHeight = 0;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            visible++;
            stackHeight += child.getMeasuredHeight();
        }
        if (visible > 1) {
            stackHeight += spacingPx * (visible - 1);
        }

        float y = (height - stackHeight) / 2f;
        if (y < 0f) {
            y = 0f;
        }
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int h = child.getMeasuredHeight();
            int allowed = allowedWidth(y, y + h, width);
            int w = Math.min(child.getMeasuredWidth(), allowed);
            int left = (int) (lastCx - w / 2f);
            child.layout(left, (int) y, left + w, (int) (y + h));
            y += h + spacingPx;
        }
    }
}
