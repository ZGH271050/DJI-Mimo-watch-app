package com.watch.osmo.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * A fixed-column thumbnail grid for a round panel.
 *
 * <p>Unrolled on purpose. The watch has no {@code RecyclerView} in its clip of the support library
 * (the APK carries no support library at all) and a nine-to-forty-item grid does not need recycling;
 * what it does need is a row width that provably fits the glass and a height the parent
 * {@link android.widget.ScrollView} can use.
 *
 * <p>The width is <b>cell-derived, not viewport-derived</b>: {@code columns * cell + gaps}, centred.
 * That is the whole point — the grid is measured with an UNSPECIFIED height inside a ScrollView, and
 * deriving anything from that spec is how {@link RoundList} once ended up measuring children for one
 * box and laying them out in another.
 *
 * <p>The caller picks a cell size that fits the narrowest row of the band the grid occupies. For the
 * 480px panel's middle ~274px, four 84px cells with 4px gaps come to 348px, whose corner distance at
 * the top of that band is 232px &lt; 240 — see {@code test/RoundGeometryTest.java}.
 */
public class RoundGrid extends ViewGroup {

    private final int columns;
    private final int cellPx;
    private final int gapPx;

    public RoundGrid(Context context, int columns, int cellPx, int gapPx) {
        this(context, null, columns, cellPx, gapPx);
    }

    public RoundGrid(Context context, AttributeSet attrs, int columns, int cellPx, int gapPx) {
        super(context, attrs);
        this.columns = Math.max(1, columns);
        this.cellPx = Math.max(1, cellPx);
        this.gapPx = Math.max(0, gapPx);
        setClipChildren(false);
    }

    public int cellPx() {
        return cellPx;
    }

    /** Total width of one row of cells, gaps included. */
    public int rowWidthPx() {
        return columns * cellPx + (columns - 1) * gapPx;
    }

    private int visibleChildren() {
        int n = 0;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i).getVisibility() != GONE) {
                n++;
            }
        }
        return n;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int count = visibleChildren();
        int rows = (count + columns - 1) / columns;
        int rowWidth = rowWidthPx();

        int exact = MeasureSpec.makeMeasureSpec(cellPx, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            child.measure(exact, exact);
        }

        int content = rows * cellPx + Math.max(0, rows - 1) * gapPx
                + getPaddingTop() + getPaddingBottom();
        // Never report more than the row width allows: if a caller hands us a narrower viewport than
        // the cells need, the grid still centres and clips rather than shifting every cell sideways.
        if (rowWidth > width) {
            rowWidth = width;
        }
        // **Never report less than the content either.** This is the whole "the gallery cannot
        // scroll" bug: the ScrollView has fillViewport set (so a two-row page can be centred in the
        // panel), and fillViewport re-measures its child with an EXACT height of
        // viewport - padding = 480 - 128 = 352px. resolveSize() politely obeyed it, so a grid holding
        // five 96px rows claimed to be 352px tall: the fifth row was laid out at y=384..480 — outside
        // its own bounds, clipped and unreachable — and the scroller saw content 352+128 = 480 in a
        // 480px viewport, i.e. a scroll range of zero. Measured on the watch: the rows moved 32px once
        // and then stopped forever.
        int measuredHeight = Math.max(content, resolveSize(content, heightMeasureSpec));
        com.watch.osmo.util.Diag.i("栅格 measure：子项 " + count + " 行 " + rows + "，内容高 "
                + content + "，规格 " + MeasureSpec.getSize(heightMeasureSpec)
                + "/模式" + MeasureSpec.getMode(heightMeasureSpec) + "，采用 " + measuredHeight);
        setMeasuredDimension(width, measuredHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int height = b - t;
        int rowWidth = Math.min(rowWidthPx(), width);
        int baseLeft = getPaddingLeft() + (width - getPaddingLeft() - getPaddingRight() - rowWidth) / 2;

        int count = visibleChildren();
        int rows = (count + columns - 1) / columns;
        int contentHeight = rows * cellPx + Math.max(0, rows - 1) * gapPx;
        int inner = height - getPaddingTop() - getPaddingBottom();
        // Centre the block when the grid has been stretched to its viewport (fillViewport) and the
        // cells do not fill it: a two-row page in a 276px viewport otherwise sits at the top and
        // leaves a hole above the footer.
        int y = getPaddingTop() + Math.max(0, (inner - contentHeight) / 2);

        int total = getChildCount();
        int index = 0;
        for (int row = 0; row < rows; row++) {
            int inThisRow = Math.min(columns, count - row * columns);
            int rowOccupied = inThisRow * cellPx + Math.max(0, inThisRow - 1) * gapPx;
            // A page that is a single short row (a filter with three videos) is centred, because it
            // reads as one group. A short row under a full one is left-aligned instead, so the columns
            // still line up — which is what an album grid has to do.
            int x = rows == 1 ? baseLeft + (rowWidth - rowOccupied) / 2 : baseLeft;
            for (int col = 0; col < inThisRow; col++) {
                View child = null;
                while (index < total) {
                    View candidate = getChildAt(index++);
                    if (candidate.getVisibility() != GONE) {
                        child = candidate;
                        break;
                    }
                }
                if (child == null) {
                    break;
                }
                child.layout(x, y, x + cellPx, y + cellPx);
                x += cellPx + gapPx;
            }
            y += cellPx + gapPx;
        }
    }
}
