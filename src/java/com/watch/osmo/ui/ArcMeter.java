package com.watch.osmo.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Progress drawn as one ring hugging the panel edge.
 *
 * <p>This is the one control a round display does better than a rectangular one: a horizontal bar
 * spends the widest part of the screen on one short line, while an arc can use the whole
 * circumference for free and still leave the middle for text.
 *
 * <p>A single ring, deliberately. The first version drew a second inner arc for the file currently
 * moving; on the panel the two rings are 6px apart and read as one thick smudge, and the same number
 * is already spelled out in the byte line ("本个 42%"). One ring, one meaning: the whole transfer.
 *
 * <p>It is an overlay View: it covers the whole panel but is neither clickable nor focusable, so
 * touches fall through to whatever is underneath (a non-clickable View returns false from
 * {@link #onTouchEvent}, and the parent then offers the event to the next child down).
 */
public class ArcMeter extends View {

    private static final float STROKE = 12f;
    /** Distance from the physical edge, in px, so the stroke is never clipped by the glass. */
    private static final float EDGE_INSET = 3f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private float fraction;
    private int color = Ui.ACCENT;
    private boolean indeterminate;

    public ArcMeter(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
    }

    /** Overall progress, 0..1. A negative value shows the indeterminate sweep. */
    public void setOverall(float value) {
        boolean sweep = value < 0;
        float f = value < 0f ? 0f : (value > 1f ? 1f : value);
        if (!sweep && !indeterminate && Math.abs(f - fraction) < 0.002f) {
            return;
        }
        indeterminate = sweep;
        fraction = f;
        invalidate();
    }

    public void setColor(int value) {
        color = value;
        invalidate();
    }

    public void reset() {
        fraction = 0;
        indeterminate = false;
        color = Ui.ACCENT;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f - EDGE_INSET - STROKE / 2f;

        paint.setStrokeWidth(STROKE);
        paint.setColor(0x22FFFFFF);
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(oval, -90f, 360f, false, paint);

        paint.setColor(color);
        canvas.drawArc(oval, -90f, 360f * (indeterminate ? 0.16f : fraction), false, paint);
    }
}
