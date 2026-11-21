package com.watch.osmo.ui;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ScrollView;

/**
 * An overlay bar that lets a vertical drag pass through to the grid behind it.
 *
 * <p>The floating controls sit on top of the scrolling gallery, so without this a swipe that starts
 * on the button row is consumed by the button and the list never moves. That is exactly what the
 * user hit: the button row occupies y=[372,444] of a 480px panel, i.e. the third of the screen a
 * thumb naturally rests on, so "the gallery cannot scroll" was true for the only place it was
 * natural to swipe from.
 *
 * <p>The trick is {@link #onInterceptTouchEvent}: a tap is left alone entirely (the child button sees
 * DOWN and UP and clicks normally), and only once the finger has moved past the system's touch slop
 * does this view steal the gesture — the child receives ACTION_CANCEL, and every further move is
 * translated into {@link ScrollView#scrollBy}. The result is one continuous gesture whether it
 * started on artwork, on the scrim, or on a button.
 */
public class DragThroughLayout extends FrameLayout {

    private final int touchSlop;
    private ScrollView target;
    private float downY;
    private float lastY;
    private boolean dragging;

    public DragThroughLayout(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** The list this bar sits on top of and must not hide from the finger. */
    public void setScrollTarget(ScrollView target) {
        this.target = target;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                lastY = ev.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(ev.getY() - downY) > touchSlop) {
                    dragging = true;
                    lastY = ev.getY();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                int delta = (int) (lastY - ev.getY());
                lastY = ev.getY();
                if (target != null && delta != 0) {
                    target.scrollBy(0, delta);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
            default:
                break;
        }
        return true;
    }
}
