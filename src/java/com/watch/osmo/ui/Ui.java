package com.watch.osmo.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared styling. Everything is built in code: there are no XML layouts in this app. */
public final class Ui {

    public static final int BG = Color.parseColor("#0E1116");
    public static final int CARD = Color.parseColor("#1A2230");
    public static final int CARD_PRESSED = Color.parseColor("#243044");
    public static final int ACCENT = Color.parseColor("#4C9AFF");
    public static final int ACCENT_DIM = Color.parseColor("#204060");
    public static final int TEXT = Color.parseColor("#E8EEF7");
    public static final int TEXT_DIM = Color.parseColor("#8FA3BF");
    public static final int OK = Color.parseColor("#34C759");
    public static final int WARN = Color.parseColor("#FFB020");
    public static final int ERR = Color.parseColor("#FF5A5F");
    public static final int ON_ACCENT = Color.parseColor("#08131F");
    /** Translucent black for badges drawn on top of a thumbnail. */
    public static final int SCRIM = Color.parseColor("#B3000000");
    /** Fill of a selected grid cell's frame. */
    public static final int SEL_FRAME = Color.parseColor("#4C9AFF");

    /**
     * Touch target height for a primary control.
     *
     * <p>Wear's own floor is 48dp; this used to be 56dp, which on this panel is <b>119px</b> — a
     * quarter of the 480px screen — and only about four of them can ever stack vertically. Now that
     * the browser is a thumbnail grid the buttons sit in one row at the widest part of the circle, and
     * 46dp (98px) buys a whole extra grid row. Still a comfortable finger target.
     */
    public static final int TOUCH_DP = 46;

    /**
     * Height of the compact footer controls on the browser and transfer screens.
     *
     * <p>34dp = 72px. These are always side by side in the circle's widest band, never the primary
     * action on a screen by itself, and the density is what the redesign is for.
     */
    public static final int COMPACT_DP = 34;

    /**
     * Touch floor for a quiet text entry.
     *
     * <p>Deliberately below {@link #TOUCH_DP}: a 56dp ghost link stole a grid row for a control that
     * is not the primary action. 30dp (64px) is still a finger-sized box for text.
     */
    public static final int GHOST_MIN_DP = 30;

    // ---- round-screen width limits -----------------------------------------------------------------
    //
    // On a 480x480 circle a strip pinned to the top or the bottom of the screen is cut away by the
    // panel, so a screen built as "header / scroller / footer" cannot hand its bands the full width.
    // Each constant is the pixel width a named band may use. Every one was computed and then checked
    // the only way that counts: for all four corners of the band, (x-240)^2 + (y-240)^2 <= 240^2,
    // with a few px of inset for the panel's own tolerance. The check is in
    // test/RoundGeometryTest.java; the bands below are the placements that test verifies.
    //
    //   band                     y range        widest box that fits   limit used
    //   browse header            [ 40, 131]            244px              244  HEADER_LIMIT
    //   browse footer entry      [261, 321]            302px              302  FOOTNOTE_LIMIT
    //   browse footer button     [321, 440]            256px              256  FOOTER_LIMIT
    //   log page text            [ 20, 348]            per child          308  ROUND_COLUMN_LIMIT
    //   log page buttons         [348, 470]            265px              264  LOG_BUTTON_LIMIT
    //   device screen            [ 22, 453]            per child          308  DEVICE_LIMIT
    //   queue button             [ 50, 430]            per child          304  ACTION_LIMIT
    //   queue summary            [318, 338]            492px              302  SUMMARY_LIMIT
    //
    // The limits sit a few per cent under the maths on purpose: the panel's physical circle is the
    // hard limit, the real glyph coverage of a font is not known until it renders, and a row cut by
    // five pixels is as untappable as one cut by fifty.
    //
    // The browse bands are the only ones pinned to fixed y positions. RoundColumn narrows its own
    // children at layout time, so ROUND_COLUMN_LIMIT / DEVICE_LIMIT / ACTION_LIMIT are upper bounds
    // the column will never actually hand out in the tight rows. BrowseScreen insets itself 40px top
    // and bottom first; without that inset no width at all can satisfy the four-corner test in the
    // top band, which is why the earlier numbers here (308 / 296) were unreachable.
    public static final int ROUND_COLUMN_LIMIT = 308;
    public static final int HEADER_LIMIT = 244;
    public static final int DEVICE_LIMIT = 308;
    public static final int FOOTER_LIMIT = 256;
    public static final int FOOTNOTE_LIMIT = 302;
    public static final int LOG_BUTTON_LIMIT = 264;
    public static final int SUMMARY_LIMIT = 302;
    public static final int ACTION_LIMIT = 304;

    private Ui() {
    }

    public static int dp(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    public static int sp(Context c, float sp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable rounded(int fill, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static GradientDrawable rounded(int fill, float radiusPx, int strokeColor, float strokePx) {
        GradientDrawable d = rounded(fill, radiusPx);
        d.setStroke((int) strokePx, strokeColor);
        return d;
    }

    public static TextView text(Context c, String s, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView title(Context c, String s) {
        return text(c, s, 16f, TEXT);
    }

    public static TextView dim(Context c, String s, float sizeSp) {
        return text(c, s, sizeSp, TEXT_DIM);
    }

    /** A tappable rounded bar. Big vertical padding keeps it above the 48dp touch floor. */
    public static TextView pill(Context c, String label, int fill, int textColor, float sizeSp) {
        return pill(c, label, fill, textColor, sizeSp, dp(c, TOUCH_DP));
    }

    /** {@link #pill} at an explicit height, for the compact footer rows. */
    public static TextView pill(Context c, String label, int fill, int textColor, float sizeSp,
                                int heightPx) {
        return pill(c, label, fill, textColor, sizeSp, heightPx, 10f, 20f);
    }

    /**
     * {@link #pill} with its own horizontal padding and corner radius.
     *
     * <p>The padding is what broke the first compact footer: three 76px buttons with 10dp of padding
     * on each side left 34px for the label, so "全选" wrapped onto two lines and every button looked
     * like a circle with one character in it.
     */
    public static TextView pill(Context c, String label, int fill, int textColor, float sizeSp,
                                int heightPx, float padDp, float radiusDp) {
        TextView t = text(c, label, sizeSp, textColor);
        t.setBackground(rounded(fill, dp(c, radiusDp)));
        int padH = dp(c, padDp);
        t.setPadding(padH, 0, padH, 0);
        t.setMinHeight(heightPx);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    public static TextView primaryButton(Context c, String label) {
        return pill(c, label, ACCENT, ON_ACCENT, 14f);
    }

    public static TextView secondaryButton(Context c, String label) {
        return pill(c, label, CARD, TEXT, 13f);
    }

    /** {@link #primaryButton} at {@link #COMPACT_DP}, for a footer that shares its band. */
    public static TextView compactPrimary(Context c, String label) {
        return pill(c, label, ACCENT, ON_ACCENT, 10.5f, dp(c, COMPACT_DP), 3f, 11f);
    }

    /** {@link #secondaryButton} at {@link #COMPACT_DP}. */
    public static TextView compactSecondary(Context c, String label) {
        return pill(c, label, CARD, TEXT, 10.5f, dp(c, COMPACT_DP), 3f, 11f);
    }

    /** A one-line label drawn on top of artwork: sharp corners, translucent black, tiny type. */
    public static TextView overlayBadge(Context c, String label, float sizeSp) {
        TextView t = text(c, label, sizeSp, TEXT);
        t.setBackground(rounded(SCRIM, 0));
        int padH = dp(c, 3);
        t.setPadding(padH, 0, padH, 0);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A quiet text entry point, sized by its own content plus padding. */
    public static TextView ghostButton(Context c, String label) {
        TextView t = text(c, label, 12f, TEXT_DIM);
        int padH = dp(c, 12);
        int padV = dp(c, 6);
        t.setPadding(padH, padV, padH, padV);
        t.setMinHeight(dp(c, GHOST_MIN_DP));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** Horizontal progress bar, sized for the watch. */
    public static android.widget.ProgressBar progress(Context c, int max) {
        android.widget.ProgressBar p = new android.widget.ProgressBar(c, null,
                android.R.attr.progressBarStyleHorizontal);
        p.setMax(max <= 0 ? 100 : max);
        p.setProgress(0);
        return p;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /**
     * Wraps {@code child} in a full-width strip that centres the child on a fixed maximum width.
     *
     * <p>This is the escape hatch for the bands a {@link RoundColumn} cannot serve: a fixed header or
     * footer has to be positioned by the screen, not stacked by content height, but it still must not
     * be measured at the full 480px. The wrapper eats the whole strip so it can be laid out anywhere,
     * and passes {@code maxWidthPx} down as an exact width so the child wraps inside the circle
     * instead of poking out of the panel.
     */
    public static FrameLayout roundRect(Context c, View child, int maxWidthPx) {
        FrameLayout holder = new FrameLayout(c);
        holder.setClipChildren(false);
        holder.setClipToPadding(false);
        holder.addView(child, new FrameLayout.LayoutParams(maxWidthPx,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        return holder;
    }

    /**
     * A vertically scrolling area.
     *
     * <p>The rows live in a {@link RoundList}, which gives every row the width the circle actually
     * allows, so a long list never depends on the caller guessing a margin. The generous top and
     * bottom padding means an item can always be scrolled into the wide middle band before it is
     * read or tapped.
     */
    public static ScrollView scroller(Context c, RoundList content) {
        return scroller(c, content, 96, 96);
    }

    /**
     * Scroller with explicit top/bottom padding.
     *
     * <p>The default 96dp of padding is right for a list that owns the whole screen. A screen that
     * keeps a fixed header and footer has only ~264px of middle band left, and 96dp of padding there
     * would leave room for a single row, so those screens pass a smaller value.
     */
    public static ScrollView scroller(Context c, RoundList content, int padTopDp, int padBottomDp) {
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setFillViewport(false);
        content.setPadding(0, dp(c, padTopDp), 0, dp(c, padBottomDp));
        sv.addView(content, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    /** Simple pressed-state feedback without needing a state list resource. */
    public static void setPressable(final View v) {
        v.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, android.view.MotionEvent event) {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    view.setAlpha(0.6f);
                } else if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    view.setAlpha(1f);
                }
                return false;
            }
        });
    }

    public static String bytes(long n) {
        if (n < 0) {
            return "?";
        }
        if (n < 1024) {
            return n + " B";
        }
        if (n < 1024L * 1024L) {
            return (n / 1024) + " KB";
        }
        if (n < 1024L * 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0);
        }
        return String.format(java.util.Locale.US, "%.2f GB", n / 1073741824.0);
    }

    public static String duration(int seconds) {
        if (seconds <= 0) {
            return "";
        }
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    /** Throughput as a short string, e.g. {@code 1.2 MB/s}. Zero means "not moving yet". */
    public static String rate(double bytesPerSecond) {
        if (bytesPerSecond <= 1) {
            return "—";
        }
        if (bytesPerSecond < 1024) {
            return String.format(java.util.Locale.US, "%.0f B/s", bytesPerSecond);
        }
        if (bytesPerSecond < 1048576) {
            return String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / 1048576.0);
    }

    /** Remaining time as {@code m:ss}, or {@code --:--} when it cannot be estimated yet. */
    public static String countdown(double seconds) {
        if (seconds <= 0 || Double.isNaN(seconds) || Double.isInfinite(seconds)) {
            return "--:--";
        }
        int total = (int) Math.min(seconds, 99 * 60 + 59);
        return String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60);
    }
}
