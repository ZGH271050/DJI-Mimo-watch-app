package com.watch.osmo.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.watch.osmo.core.CameraStatus;
import com.watch.osmo.proto.MediaFile;
import com.watch.osmo.util.Diag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Media browser: header, type tabs, a full-bleed square thumbnail grid, a selection line, one footer.
 *
 * <p>Modelled on the phone app the user pointed at: **square tiles with a right-angle frame, pushed
 * out to the panel edges**, five across. The round panel crops the outermost column's corners, which
 * is the price of using the whole width — and it is the point: the inner three columns are always
 * inside, and the outer two are always still tappable because their centres are well within the
 * circle even when their corners are shaved.
 *
 * <pre>
 *   header     y=[ 34,  78]   244px   model, then count / battery / free space
 *   tabs       y=[ 78, 118]           全部 / 照片 / 视频
 *   grid       y=[120, 354]   viewport; five 96px tiles, edge to edge (x=[0,480])
 *   selection  y=[354, 372]   300px   "已选 3 项 · 24.1 MB", or the last result
 *   footer     y=[372, 444]   240px   全选 / 队列 / 下载 3, all three 76x72 at radius 14dp
 * </pre>
 *
 * <p>The three footer buttons are built from one code path with one radius and one height. An
 * earlier version had the download button re-styled on every selection change with its own 20dp
 * radius, so it rendered as a pill next to two rounded rectangles — the user's "一个圆的，一个方圆的".
 */
public class BrowseScreen extends FrameLayout {

    /** What the user asked for; the activity forwards these to the {@link Controller}. */
    public interface Listener {
        void onDownload(List<MediaFile> files);

        void onSelectAll();

        void onClearSelection();

        void onOpenQueue();

        /** Long-press on the model name: hand the camera's Wi-Fi back and return to the scan screen. */
        void onDisconnect();
    }

    /** Fired whenever the tick set changes, so the activity can keep its own copy in step. */
    public interface SelectionListener {
        void onSelectionChanged(List<MediaFile> selected);
    }

    /** Grid filters, in tab order. */
    public static final int FILTER_ALL = 0;
    public static final int FILTER_PHOTO = 1;
    public static final int FILTER_VIDEO = 2;

    private static final int COLUMNS = 5;
    private static final int CELL_PX = 96;
    /** Gap between tiles. Zero: the tiles are edge to edge, the frame is the inset around the art. */
    private static final int GAP_PX = 0;
    private static final int HEADER_TOP_PX = 34;
    private static final int HEADER_HEIGHT_PX = 44;
    private static final int TABS_TOP_PX = 78;
    private static final int TABS_HEIGHT_PX = 40;
    private static final int TOP_OVERLAY_HEIGHT_PX = 126;
    private static final int SUMMARY_TOP_PX = 10;
    private static final int SUMMARY_HEIGHT_PX = 18;
    private static final int BOTTOM_OVERLAY_HEIGHT_PX = 136;
    private static final int FOOTER_HEIGHT_PX = 72;
    /** Distance from the panel's bottom edge, so the buttons sit in the widest band that fits. */
    private static final int FOOTER_BOTTOM_PX = 36;
    private static final int FOOTER_WIDTH_PX = 240;
    private static final int FOOTER_GAP_PX = 6;
    private static final float BUTTON_RADIUS_DP = 14f;
    /**
     * Scroll room above and below the tiles.
     *
     * <p>At y=0 the outermost columns' centres are 271px from the centre of the panel — outside the
     * 240px radius, so a tile there cannot be tapped; padding is what lets the first and last row be
     * dragged into the wide band.
     *
     * <p>The top value is <b>132px</b>, not 64: the floating header + type tabs occupy y=[34,118], so
     * with 64px the first row (y=64..160) sat 42px deep under the tab bar — the user's "最顶上那排
     * 照片有一半会被按钮遮到". 132 starts the first row just below the tabs (118) with a 14px gap, and
     * its tiles' centres (y=180) are still well inside the circle.
     */
    private static final int GRID_PAD_TOP_PX = 122;
    /**
     * Bottom room, 150px: at maximum scroll the last row's bottom edge lands at
     * {@code 480 - GRID_PAD_BOTTOM_PX}. With 64 it landed at y=416 — inside the floating footer
     * (372..444) — so the last row was permanently half-hidden and the list looked like it stopped
     * at whatever row 416 cut through ("往下滑也滑不到底"). 150 puts it at 330, clear of both the
     * summary line (354..372) and the buttons.
     */
    private static final int GRID_PAD_BOTTOM_PX = 150;
    /** Above this the grid is truncated: 240 cells is far more than the watch can scroll usefully. */
    private static final int MAX_CELLS = 240;

    private final Context context;
    private final Listener listener;
    private final Thumbs thumbs;

    private final TextView headerLine1;
    private final TextView headerLine2;
    private final TextView summary;
    private final TextView selectButton;
    private final TextView queueButton;
    private final TextView downloadButton;
    private final TextView[] tabs = new TextView[3];
    private final RoundGrid grid;
    private final ScrollView scroller;

    /** Everything the camera reported, in manifest order. */
    private final List<MediaFile> allMedia = new ArrayList<MediaFile>();
    /** What the grid currently shows, i.e. {@link #allMedia} through {@link #filter}. */
    private final List<MediaFile> media = new ArrayList<MediaFile>();
    private final Set<String> selectedPaths = new LinkedHashSet<String>();
    private final List<Cell> cells = new ArrayList<Cell>();

    private SelectionListener selectionListener;
    private CameraStatus cameraStatus;
    private String statusText = "";
    private String note = "";
    private String model = "";
    private int filter = FILTER_ALL;
    private boolean truncated;
    /** Dev-only: paint placeholder tiles with photo-like colours so screenshots show the real density. */
    private boolean previewMode;

    public BrowseScreen(Context context, Listener listener, Thumbs thumbs) {
        super(context);
        this.context = context;
        this.listener = listener;
        this.thumbs = thumbs;
        setBackgroundColor(Ui.BG);

        // ---- grid first: it owns the whole panel ------------------------------------------------
        // The tiles are the content, so they get the full circle and the controls float on top of
        // them. An earlier version reserved a 44px band at the top and a 126px band at the bottom for
        // the header and the buttons, which left the photos squeezed into the middle and no picture
        // ever reaching the panel's edges.
        grid = new RoundGrid(context, COLUMNS, CELL_PX, GAP_PX);
        scroller = new ScrollView(context);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // **Not** fillViewport. It re-measures its child with EXACTLY(viewport - padding) = 352px, so a
        // grid of five 96px rows spent its life clipped to four rows with a scroll range of 32px —
        // which is exactly what "the gallery cannot scroll" was. Short pages are centred with padding
        // instead, see updateScrollPadding().
        scroller.setFillViewport(false);
        // THE important line: without it the padding also *clips* the child, so a 150px bottom pad
        // turned the photo area into a 208px window (y=122..330) with a black band below it — the
        // user's "图片都只能在中间一部分看到". With clipToPadding=false the grid draws all the way to
        // the panel edge (and fades under the floating buttons), while the padding still buys the
        // scroll range that lets the last row rise above them.
        scroller.setClipToPadding(false);
        scroller.setPadding(0, GRID_PAD_TOP_PX, 0, GRID_PAD_BOTTOM_PX);
        scroller.addView(grid, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
        // Thumbs.clear() cancels every queued download on touch, and nothing used to ask again — so
        // after one swipe the cells that had not loaded yet stayed placeholders forever ("只能加载23
        // 张"). Re-request whatever is visible once the finger has been still for a moment.
        scroller.getViewTreeObserver().addOnScrollChangedListener(
                new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        scheduleBindVisible();
                    }
                });
        scroller.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)
                        && thumbs != null) {
                    // A finger is dragging: stop fetching thumbnails that are sliding off the panel.
                    thumbs.clear();
                }
                return false;
            }
        });
        addView(scroller, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));

        // ---- floating top bar --------------------------------------------------------------------
        DragThroughLayout topOverlay = new DragThroughLayout(context);
        topOverlay.setScrollTarget(scroller);
        topOverlay.setBackground(scrim(context, true));
        addView(topOverlay, new LayoutParams(LayoutParams.MATCH_PARENT, TOP_OVERLAY_HEIGHT_PX,
                Gravity.TOP));

        headerLine1 = Ui.text(context, "已连接相机", 10.5f, Ui.TEXT);
        headerLine1.setShadowLayer(3f, 0f, 1f, 0xCC000000);
        headerLine2 = Ui.text(context, "共 0 项", 9f, Ui.TEXT_DIM);
        headerLine2.setShadowLayer(3f, 0f, 1f, 0xCC000000);
        // The only way out of the gallery. While the session is alive the watch is bound to the
        // camera's AP on purpose — that is what the whole app is for — so leaving through the
        // system's Wi-Fi switch fights the app for ~28 s. A long press here releases the network and
        // unpins the process, which lets the watch fall back to its own Wi-Fi immediately.
        headerLine1.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (BrowseScreen.this.listener != null) {
                    BrowseScreen.this.listener.onDisconnect();
                }
                return true;
            }
        });
        LinearLayout head = Ui.column(context);
        head.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        head.addView(headerLine1, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
        head.addView(headerLine2, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
        // Ui.HEADER_LIMIT &c. are already PIXELS; running them through Ui.dp() again is what made a
        // 654px-wide log box on a 480px panel once.
        FrameLayout.LayoutParams headParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, HEADER_HEIGHT_PX, Gravity.TOP);
        headParams.topMargin = HEADER_TOP_PX;
        topOverlay.addView(Ui.roundRect(context, head, Ui.HEADER_LIMIT), headParams);

        LinearLayout tabRow = Ui.row(context);
        tabRow.setGravity(Gravity.CENTER);
        String[] labels = {"全部", "照片", "视频"};
        int tabWidth = 92;
        for (int i = 0; i < tabs.length; i++) {
            final int which = i;
            TextView tab = Ui.pill(context, labels[i], Ui.CARD, Ui.TEXT_DIM, 10.5f,
                    Ui.dp(context, 18), 3f, 9f);
            Ui.setPressable(tab);
            tab.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    setFilter(which);
                }
            });
            tabs[i] = tab;
            if (i > 0) {
                tabRow.addView(new View(context), new LinearLayout.LayoutParams(Ui.dp(context, 3), 0));
            }
            tabRow.addView(tab, new LinearLayout.LayoutParams(tabWidth, TABS_HEIGHT_PX));
        }
        FrameLayout.LayoutParams tabParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, TABS_HEIGHT_PX, Gravity.TOP);
        tabParams.topMargin = TABS_TOP_PX;
        topOverlay.addView(Ui.roundRect(context, tabRow, 3 * tabWidth + 2 * Ui.dp(context, 3)),
                tabParams);

        // ---- floating bottom bar -----------------------------------------------------------------
        DragThroughLayout bottomOverlay = new DragThroughLayout(context);
        bottomOverlay.setScrollTarget(scroller);
        bottomOverlay.setBackground(scrim(context, false));
        addView(bottomOverlay, new LayoutParams(LayoutParams.MATCH_PARENT, BOTTOM_OVERLAY_HEIGHT_PX,
                Gravity.BOTTOM));

        summary = Ui.text(context, "", 9.5f, Ui.TEXT);
        // It now floats over artwork (clipToPadding=false), so give it a translucent chip rather than
        // relying on a shadow alone: at 9.5sp a shadow is not enough over a bright photo.
        summary.setShadowLayer(3.5f, 0f, 1f, 0xE6000000);
        summary.setBackground(Ui.rounded(0xA60E1116, Ui.dp(context, 9)));
        int summaryPadH = Ui.dp(context, 8);
        // No vertical padding: the band is only 18px tall and the buttons start at y=372 — any extra
        // height pushes the line's descenders under them.
        summary.setPadding(summaryPadH, 0, summaryPadH, 0);
        summary.setSingleLine(true);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams summaryParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, SUMMARY_HEIGHT_PX, Gravity.TOP);
        summaryParams.topMargin = SUMMARY_TOP_PX;
        bottomOverlay.addView(Ui.roundRect(context, summary, Ui.SUMMARY_LIMIT), summaryParams);

        // ---- footer ------------------------------------------------------------------------------
        LinearLayout footer = Ui.row(context);
        footer.setGravity(Gravity.CENTER);

        selectButton = compactButton("全选", Ui.CARD, Ui.TEXT);
        Ui.setPressable(selectButton);
        selectButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSelectAll();
                if (BrowseScreen.this.listener != null) {
                    if (selectedPaths.isEmpty()) {
                        BrowseScreen.this.listener.onClearSelection();
                    } else {
                        BrowseScreen.this.listener.onSelectAll();
                    }
                }
            }
        });

        queueButton = compactButton("队列", Ui.CARD, Ui.TEXT);
        Ui.setPressable(queueButton);
        queueButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (BrowseScreen.this.listener != null) {
                    BrowseScreen.this.listener.onOpenQueue();
                }
            }
        });

        downloadButton = compactButton("下载", Ui.ACCENT, Ui.ON_ACCENT);
        Ui.setPressable(downloadButton);
        downloadButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (BrowseScreen.this.listener != null) {
                    BrowseScreen.this.listener.onDownload(selected());
                }
            }
        });

        int buttonWidth = (FOOTER_WIDTH_PX - 2 * FOOTER_GAP_PX) / 3;
        footer.addView(selectButton, new LinearLayout.LayoutParams(buttonWidth, FOOTER_HEIGHT_PX));
        footer.addView(new View(context), new LinearLayout.LayoutParams(FOOTER_GAP_PX, 0));
        footer.addView(queueButton, new LinearLayout.LayoutParams(buttonWidth, FOOTER_HEIGHT_PX));
        footer.addView(new View(context), new LinearLayout.LayoutParams(FOOTER_GAP_PX, 0));
        footer.addView(downloadButton, new LinearLayout.LayoutParams(buttonWidth, FOOTER_HEIGHT_PX));
        FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, FOOTER_HEIGHT_PX, Gravity.BOTTOM);
        footerParams.bottomMargin = FOOTER_BOTTOM_PX;
        bottomOverlay.addView(Ui.roundRect(context, footer, FOOTER_WIDTH_PX), footerParams);

        if (thumbs != null) {
            thumbs.setObserver(new Thumbs.Observer() {
                @Override
                public void onThumbReady(MediaFile file) {
                    rebindSoon(file);
                }
            });
        }
        updateHeader();
        updateTabs();
        updateSummary();
    }

    private static int previewTint(String path) {
        int[] palette = {0xFF3B5A4A, 0xFF5A4A3B, 0xFF3B465A, 0xFF5A3B47, 0xFF4A5A3B,
                0xFF2F4F4F, 0xFF4F2F3F, 0xFF3F3F5F};
        int h = path == null ? 0 : path.hashCode();
        return palette[Math.abs(h) % palette.length];
    }

    /** One footer button. Every one of the three goes through here, so they cannot drift apart. */
    private TextView compactButton(String label, int fill, int textColor) {
        return Ui.pill(context, label, fill, textColor, 10.5f, FOOTER_HEIGHT_PX, 3f,
                BUTTON_RADIUS_DP);
    }

    /**
     * The dark scrim the floating bars sit on.
     *
     * <p>A plain alpha fade, not a blur: this is a 5-core Exynos and the panel is 480px, so a
     * {@code RenderEffect} blur would cost a full-screen offscreen pass for every scroll frame. Four
     * colour stops make the solid part reach the controls and only the last third fade out, so the
     * text is always on near-opaque background and the photos still bleed under the edge.
     */
    private static android.graphics.drawable.Drawable scrim(Context c, boolean top) {
        // Four of five stops used to be fully opaque, so a photo scrolling under the bar disappeared
        // at a hard line instead of fading — the user's "图片往上拖，直接就消失了". These ramps keep
        // the edge solid (text stays readable) but spend the whole bar height fading, so the rows
        // visibly slide *under* the floating controls.
        // The bottom ramp is deliberately light: the photos are supposed to run to the panel edge and
        // show *through* the button band (the user's "按钮悬浮在图片上方 / 延伸到整个屏幕"). The three
        // buttons carry their own opaque pills, so only the small summary line needs help — it gets a
        // text shadow instead of a heavy scrim.
        int[] colors = top
                ? new int[]{0xFF0E1116, 0xE80E1116, 0xB80E1116, 0x580E1116, 0x000E1116}
                : new int[]{0xE60E1116, 0xB80E1116, 0x880E1116, 0x400E1116, 0x000E1116};
        // Index 0 is the bar's own edge for both: TOP_BOTTOM starts at the top, BOTTOM_TOP at the
        // bottom, so a solid edge fades toward the middle of the panel.
        return new android.graphics.drawable.GradientDrawable(
                top ? android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                        : android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                colors);
    }

    // ---- public API used by the activity -----------------------------------------------------------

    /** Replaces the list. Ticks are kept by path, so a refresh does not lose the user's selection. */
    public void setMedia(List<MediaFile> files) {
        allMedia.clear();
        if (files != null) {
            allMedia.addAll(files);
        }
        note = "";
        if (thumbs != null) {
            thumbs.clear();
        }
        applyFilter();
    }

    /** Dev-only: the preview screen calls this so its fake tiles read as artwork, not dark squares. */
    public void setPreviewMode(boolean value) {
        previewMode = value;
    }

    /** Free-form stage text or error, shown in place of the count line. */
    public void setStatus(String text) {
        statusText = text == null ? "" : text;
        updateHeader();
    }

    /** Battery and free space from the camera snapshot; the model name comes from {@link #setModel}. */
    public void setStatus(CameraStatus status) {
        this.cameraStatus = status;
        updateHeader();
    }

    /** Camera model shown at the top of the header. */
    public void setModel(String modelName) {
        model = modelName == null ? "" : modelName;
        updateHeader();
    }

    /**
     * A one-shot result line ("已保存 1 个文件") that replaces the selection summary until the next
     * tick change. Empty clears it.
     */
    public void setNote(String text) {
        note = text == null ? "" : text;
        updateSummary();
    }

    /** Installs the selection callback. Replaces any previous one, so it is safe to call twice. */
    public void setSelectionListener(SelectionListener selectionListener) {
        this.selectionListener = selectionListener;
    }

    /** Snapshot of the ticked files, in manifest order — including ones the current tab hides. */
    public List<MediaFile> selected() {
        List<MediaFile> out = new ArrayList<MediaFile>();
        for (MediaFile f : allMedia) {
            if (selectedPaths.contains(f.getPath())) {
                out.add(f);
            }
        }
        return out;
    }

    /** Switches the 全部 / 照片 / 视频 tab. */
    public void setFilter(int which) {
        int next = which < 0 || which > FILTER_VIDEO ? FILTER_ALL : which;
        if (next == filter) {
            return;
        }
        filter = next;
        note = "";
        if (thumbs != null) {
            thumbs.clear();
        }
        applyFilter();
    }

    /** Ticks everything visible in the current tab, or clears everything if it is already ticked. */
    public void toggleSelectAll() {
        List<MediaFile> visible = visible();
        if (visible.isEmpty()) {
            selectedPaths.clear();
        } else {
            boolean allTicked = true;
            for (MediaFile f : visible) {
                if (!selectedPaths.contains(f.getPath())) {
                    allTicked = false;
                    break;
                }
            }
            if (allTicked) {
                for (MediaFile f : visible) {
                    selectedPaths.remove(f.getPath());
                }
            } else {
                for (MediaFile f : visible) {
                    selectedPaths.add(f.getPath());
                }
            }
        }
        note = "";
        refreshSelection();
        notifySelection();
    }

    public void clearSelection() {
        selectedPaths.clear();
        note = "";
        refreshSelection();
        notifySelection();
    }

    // ---- filtering / cells -------------------------------------------------------------------------

    private List<MediaFile> visible() {
        List<MediaFile> out = new ArrayList<MediaFile>();
        for (MediaFile f : allMedia) {
            if (filter == FILTER_PHOTO && f.isVideo()) {
                continue;
            }
            if (filter == FILTER_VIDEO && !f.isVideo()) {
                continue;
            }
            out.add(f);
            if (out.size() >= MAX_CELLS) {
                break;
            }
        }
        return out;
    }

    private void applyFilter() {
        List<MediaFile> visible = visible();
        truncated = visible.size() >= MAX_CELLS;
        media.clear();
        media.addAll(visible);
        buildCells();
        pruneSelection();
        updateScrollPadding();
        updateHeader();
        updateTabs();
        updateSummary();
        scroller.scrollTo(0, 0);
    }

    /**
     * Vertical padding for the scroller: enough to clear the floating bars when there is content to
     * scroll, or exactly enough to centre the grid when there is not.
     *
     * <p>This replaces {@code setFillViewport(true)}. The two jobs were conflated: fillViewport is
     * about stretching a short child, and its mechanism — re-measuring the child with an exact
     * viewport-minus-padding height — silently clipped any grid taller than that. Doing the centring
     * with padding leaves the child's real height alone, so the scroller's range is honest.
     */
    private void updateScrollPadding() {
        int rows = (media.size() + COLUMNS - 1) / COLUMNS;
        int content = rows * CELL_PX;
        int screen = getResources().getDisplayMetrics().heightPixels;
        // Centre only the *spare* space, so the top padding never drops below the safe area: the old
        // "centre everything" form pushed a three-row page (96px pad) back under the tab bar.
        int spare = screen - content - GRID_PAD_TOP_PX - GRID_PAD_BOTTOM_PX;
        int extra = spare > 0 ? spare / 2 : 0;
        scroller.setPadding(0, GRID_PAD_TOP_PX + extra, 0, GRID_PAD_BOTTOM_PX + extra);
    }

    private static final class Cell {
        final MediaFile file;
        /** True once a real bitmap has landed. Drawable introspection is not reliable here: the
         *  ImageView can report a non-null drawable before any image was set, which made every cell
         *  look "already loaded" and silently disabled the scroll-time re-request. */
        boolean loaded;
        final FrameLayout frame;
        final ImageView thumb;
        final TextView glyph;
        final TextView badge;
        final TextView check;

        Cell(MediaFile file, FrameLayout frame, ImageView thumb, TextView glyph, TextView badge,
             TextView check) {
            this.file = file;
            this.frame = frame;
            this.thumb = thumb;
            this.glyph = glyph;
            this.badge = badge;
            this.check = check;
        }
    }

    private void buildCells() {
        cells.clear();
        grid.removeAllViews();
        // The frame is the border: 2px of it shows around every tile, which at GAP_PX = 0 is what
        // separates two neighbours and what turns blue on selection.
        int art = CELL_PX - 4;
        for (MediaFile file : media) {
            Cell cell = buildCell(file, art);
            cells.add(cell);
            grid.addView(cell.frame, new RoundGrid.LayoutParams(CELL_PX, CELL_PX));
        }
        refreshAll();
    }

    private Cell buildCell(final MediaFile file, int artPx) {
        FrameLayout frame = new FrameLayout(context);
        frame.setClickable(true);
        Ui.setPressable(frame);
        frame.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle(file);
            }
        });

        // Right-angle corners, like the phone app's album: no rounded outline, no clipping needed.
        FrameLayout art = new FrameLayout(context);
        art.setBackgroundColor(previewMode ? previewTint(file.getPath()) : 0xFF0B0F14);
        FrameLayout.LayoutParams artParams =
                new FrameLayout.LayoutParams(artPx, artPx, Gravity.CENTER);
        frame.addView(art, artParams);

        ImageView thumb = new ImageView(context);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(thumb, new FrameLayout.LayoutParams(artPx, artPx));

        TextView glyph = Ui.text(context, file.isVideo() ? "▶" : "▣", 15f, Ui.TEXT_DIM);
        art.addView(glyph, new FrameLayout.LayoutParams(artPx, artPx, Gravity.CENTER));

        // A small corner chip, not a full-width caption bar: on a square edge-to-edge tile a black
        // strip across the bottom turns the whole grid into rows of labels.
        TextView badge = Ui.overlayBadge(context, badgeOf(file), 7f);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, Ui.dp(context, 9), Gravity.BOTTOM | Gravity.START);
        badgeParams.leftMargin = Ui.dp(context, 1);
        badgeParams.bottomMargin = Ui.dp(context, 1);
        art.addView(badge, badgeParams);

        TextView check = Ui.text(context, "✓", 9f, Ui.ON_ACCENT);
        check.setBackground(Ui.rounded(Ui.ACCENT, 0));
        check.setVisibility(GONE);
        FrameLayout.LayoutParams checkParams =
                new FrameLayout.LayoutParams(Ui.dp(context, 9), Ui.dp(context, 9),
                        Gravity.TOP | Gravity.END);
        checkParams.topMargin = Ui.dp(context, 3);
        checkParams.rightMargin = Ui.dp(context, 3);
        frame.addView(check, checkParams);

        return new Cell(file, frame, thumb, glyph, badge, check);
    }

    private static String badgeOf(MediaFile file) {
        if (file.isVideo()) {
            int seconds = file.getDurationSec();
            return seconds > 0 ? "▶" + Ui.duration(seconds) : "▶ 视频";
        }
        return "照片";
    }

    private void toggle(MediaFile file) {
        String path = file.getPath();
        if (!selectedPaths.remove(path)) {
            selectedPaths.add(path);
        }
        note = "";
        // Only the frame and tick change: re-binding here would blank and re-fetch on every tap.
        refreshSelection();
        notifySelection();
    }

    /** Debounced "finger has stopped": ask Thumbs for the rows that are on screen now. */
    private final Runnable bindVisibleTask = new Runnable() {
        @Override
        public void run() {
            bindVisibleThumbs();
        }
    };

    private void scheduleBindVisible() {
        removeCallbacks(bindVisibleTask);
        postDelayed(bindVisibleTask, 180L);
    }

    /**
     * Re-issues thumbnail requests for the cells that are currently visible and still have no bitmap.
     * Cells that already show art are skipped, so this never blanks a loaded tile; duplicates are
     * deduplicated inside {@link Thumbs}.
     */
    private void bindVisibleThumbs() {
        if (thumbs == null || cells.isEmpty()) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        int asked = 0;
        for (Cell cell : cells) {
            if (cell.loaded) {
                continue;
            }
            if (Thumbs.visible(cell.frame, w, h, 0.05f)) {
                thumbs.bind(cell.file, cell.thumb);
                asked++;
            }
        }
        if (asked > 0) {
            Diag.i("滚动后补取缩略图 " + asked + " 张 — " + thumbs.stats());
        }
    }

    /** Full pass: used when the grid is (re)built, so every cell gets its thumbnail request. */
    private void refreshAll() {
        for (Cell cell : cells) {
            applySelection(cell);
            // bind() clears the image, so the glyph comes back until a real bitmap lands.
            cell.glyph.setVisibility(VISIBLE);
            cell.loaded = false;
            if (thumbs != null) {
                thumbs.bind(cell.file, cell.thumb);
            }
        }
    }

    /** Cheap pass: frame, tick and summary only. */
    private void refreshSelection() {
        for (Cell cell : cells) {
            applySelection(cell);
        }
        updateSummary();
    }

    private void applySelection(Cell cell) {
        boolean on = selectedPaths.contains(cell.file.getPath());
        // Square frame, square corners: colour only.
        cell.frame.setBackgroundColor(on ? Ui.SEL_FRAME : 0xFF0B0F14);
        cell.check.setVisibility(on ? VISIBLE : GONE);
    }

    private void pruneSelection() {
        Set<String> live = new LinkedHashSet<String>();
        for (MediaFile f : allMedia) {
            live.add(f.getPath());
        }
        selectedPaths.retainAll(live);
    }

    /**
     * Rebinds one cell when its thumbnail lands, instead of redrawing the whole grid.
     *
     * <p>The caller is {@link Thumbs.Observer#onThumbReady}, i.e. a bitmap really did decode — which
     * is the only reliable signal to drop the placeholder.
     */
    private void rebindSoon(MediaFile file) {
        if (file == null || thumbs == null) {
            return;
        }
        for (Cell cell : cells) {
            if (cell.file == file) {
                thumbs.bind(file, cell.thumb);
                cell.glyph.setVisibility(GONE);
                cell.loaded = true;
                return;
            }
        }
    }

    // ---- header / tabs / footer --------------------------------------------------------------------

    private void updateHeader() {
        headerLine1.setText(model.isEmpty() ? "已连接相机" : model);
        // Two short lines only: a third would not fit the band, so a status message takes the count's
        // place instead of adding one.
        if (!statusText.isEmpty()) {
            headerLine2.setText(statusText);
            return;
        }
        StringBuilder sb = new StringBuilder("共 ").append(media.size()).append(" 项");
        if (truncated) {
            sb.append("(前 ").append(MAX_CELLS).append(')');
        }
        if (cameraStatus != null) {
            if (cameraStatus.hasBattery()) {
                sb.append(" · 电 ").append(cameraStatus.batteryPercent).append('%');
            }
            if (cameraStatus.hasStorage()) {
                sb.append(" · 剩 ").append(Ui.bytes(cameraStatus.freeBytes));
            }
        }
        headerLine2.setText(sb.toString());
    }

    private void updateTabs() {
        int[] counts = {allMedia.size(), 0, 0};
        for (MediaFile f : allMedia) {
            counts[f.isVideo() ? FILTER_VIDEO : FILTER_PHOTO]++;
        }
        String[] labels = {"全部", "照片", "视频"};
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == filter;
            tabs[i].setText(labels[i] + (counts[i] > 0 ? " " + counts[i] : ""));
            tabs[i].setTextColor(on ? Ui.ON_ACCENT : Ui.TEXT_DIM);
            tabs[i].setBackground(Ui.rounded(on ? Ui.ACCENT : Ui.CARD, Ui.dp(context, 9)));
        }
    }

    private void updateSummary() {
        int n = selectedPaths.size();
        long bytes = 0;
        for (MediaFile f : allMedia) {
            if (selectedPaths.contains(f.getPath())) {
                bytes += Math.max(0, f.getSizeBytes());
            }
        }

        if (!note.isEmpty()) {
            summary.setText(note);
            summary.setTextColor(Ui.OK);
        } else if (n > 0) {
            // The figure the user asked for: how many are ticked and how much they will cost.
            summary.setText("已选 " + n + " 项 · " + Ui.bytes(bytes));
            summary.setTextColor(Ui.ACCENT);
        } else if (media.isEmpty()) {
            summary.setText(filter == FILTER_PHOTO ? "没有照片"
                    : filter == FILTER_VIDEO ? "没有视频" : "相机里没有文件");
            summary.setTextColor(Ui.TEXT_DIM);
        } else {
            summary.setText(media.isEmpty() ? "相机里没有文件" : "点图选择 · 长按标题断开");
            summary.setTextColor(Ui.TEXT_DIM);
        }

        boolean enabled = n > 0;
        downloadButton.setText(enabled ? "下载 " + n : "下载");
        downloadButton.setEnabled(enabled);
        downloadButton.setTextColor(enabled ? Ui.ON_ACCENT : Ui.TEXT_DIM);
        downloadButton.setBackground(Ui.rounded(enabled ? Ui.ACCENT : Ui.CARD,
                Ui.dp(context, BUTTON_RADIUS_DP)));

        // "全选"/"清空" follows what this tab shows, not the global tick count: a photo ticked in 全部
        // must not turn the button into 清空 while the 视频 tab has nothing ticked.
        int visibleSelected = 0;
        for (MediaFile f : media) {
            if (selectedPaths.contains(f.getPath())) {
                visibleSelected++;
            }
        }
        boolean all = !media.isEmpty() && visibleSelected >= media.size();
        selectButton.setText(all ? "清空" : "全选");
    }

    private void notifySelection() {
        SelectionListener l = selectionListener;
        if (l != null) {
            l.onSelectionChanged(selected());
        }
    }
}
