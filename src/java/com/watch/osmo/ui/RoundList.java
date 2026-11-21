package com.watch.osmo.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * Vertical stack for scrolling lists on a round display.
 *
 * <p>Every row gets the <b>same</b> width: the width the circle allows a quarter of the screen
 * height away from the centre. That is a deliberate compromise. Re-computing each row's width from
 * its live on-screen position would make rows reflow while the finger drags, which costs a
 * re-measure per frame on a 5-core watch SoC; using the centre's full width instead would push the
 * corners of the top and bottom rows off the panel. A fixed, slightly conservative width keeps
 * layout stable and readable, and the scroll container adds enough top/bottom padding that any row
 * can be brought into the wide middle band before it is read or tapped.
 */
public class RoundList extends ViewGroup {

    private final float marginPx;
    private final int spacingPx;
    private final float bandFraction = 0.25f;

    public RoundList(Context context) {
        this(context, null);
    }

    public RoundList(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        this.marginPx = 8f * density;
        this.spacingPx = (int) (6f * density);
    }

    /** Width every row is given, derived from the real view size. */
    public int rowWidth(int width, int height) {
        float cx = width / 2f;
        float cy = height / 2f;
        float r = Math.min(width, height) / 2f;
        float probeY = cy - bandFraction * height;
        float half = Round.halfWidthAt(probeY, cy, r) - marginPx;
        int w = (int) (half * 2f);
        if (w > width) {
            w = width;
        }
        return Math.max(0, w);
    }

    /**
     * The row width to actually use: the circle's allowance, clamped to the width this view was
     * actually given.
     *
     * <p>Both halves matter. Inside a {@link android.widget.ScrollView} this view is measured with an
     * UNSPECIFIED height, and {@code MeasureSpec.getSize()} of an UNSPECIFIED spec is 0 — so the width
     * computed during {@code onMeasure} (this view's height = 0) disagreed with the one computed during
     * {@code onLayout} (height = 480). Children were therefore measured for one box and laid out in
     * another, and on the watch every label came out shifted right and clipped at the row's right edge.
     * Deriving the width from the display metrics makes measure and layout agree by construction.
     *
     * <p>The clamp is the second half: a list inside a {@link RoundColumn} (the transfer screen) is
     * already narrowed to the chord there, and computing rows from the <b>panel</b> width let them
     * come out wider than their container, so the last row was drawn over the progress ring.
     */
    private int panelRowWidth(int available) {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int row = rowWidth(metrics.widthPixels, metrics.heightPixels);
        int usable = available - getPaddingLeft() - getPaddingRight();
        if (usable > 0 && row > usable) {
            row = usable;
        }
        return row;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int rowWidth = panelRowWidth(width);

        int total = 0;
        int visible = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            child.measure(MeasureSpec.makeMeasureSpec(rowWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            total += child.getMeasuredHeight();
            visible++;
        }
        if (visible > 1) {
            total += spacingPx * (visible - 1);
        }
        total += getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, resolveSize(total, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int rowWidth = panelRowWidth(width);
        int left = (width - rowWidth) / 2;
        int y = getPaddingTop();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int h = child.getMeasuredHeight();
            child.layout(left, y, left + rowWidth, y + h);
            y += h + spacingPx;
        }
    }
}
