package com.watch.osmo.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.watch.osmo.proto.MediaFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Transfer screen: one ring on the panel edge, four lines of numbers in the middle, one action.
 *
 * <p>The progress is {@link ArcMeter} — a single ring 3px inside the glass. That is the whole reason
 * this screen exists in this shape: every pixel of a horizontal bar's width is wasted on a round
 * display, while the circumference is free and the centre stays available for the numbers.
 *
 * <p>There is deliberately <b>no file list</b>. An earlier version had one, and it was the source of
 * every layout bug this screen ever had: a scroll container measured with UNSPECIFIED height grows
 * past its allotted band, which pushed the button to y=444 where the circle's chord is 6px wide and
 * it collapsed to a 6px sliver. The information a list would carry is already here — the counter in
 * the header, the current name, the byte totals and the per-file percentage in the byte line.
 *
 * <p>Everything sits in a {@link RoundColumn} at radius 0.91 so the text can never be drawn on top
 * of the ring.
 */
public class QueueScreen extends FrameLayout {

    /** What the user can do here; wired to the {@link Controller} by the activity. */
    public interface Listener {
        void onCancelAll();

        void onBack();
    }

    /**
     * One queued file.
     *
     * <p>Deliberately not {@code proto.MediaFile}: the queue also shows byte counters, and the
     * downloader only has to hand over a name and two numbers.
     */
    public static final class TransferItem {
        private final String name;
        private final String path;
        private final long total;
        private long done;
        private boolean finished;

        public TransferItem(String name, String path, long total) {
            this.name = name == null ? "" : name;
            this.path = path == null ? "" : path;
            this.total = total;
        }

        public String name() {
            return name;
        }

        public String path() {
            return path;
        }

        public long done() {
            return done;
        }

        public long total() {
            return total;
        }

        /** True once the file has been written out in full. */
        public boolean finished() {
            return finished;
        }

        /** Percentage 0..100, or -1 while the total size is not known. */
        public int percent() {
            if (total <= 0) {
                return -1;
            }
            int p = (int) (done * 100L / total);
            return p < 0 ? 0 : (p > 100 ? 100 : p);
        }
    }

    /** Heights in px. Text lines get their natural height above these floors. */
    private static final int HEADER_MIN_PX = 24;
    private static final int NAME_MIN_PX = 21;
    private static final int SPEED_MIN_PX = 24;
    private static final int META_MIN_PX = 15;
    private static final int SPACING_DP = 5;
    /** Sampling window for the throughput estimate, in ms. */
    private static final long SPEED_WINDOW_MS = 450L;

    private final Listener listener;
    private final ArcMeter arc;
    private final TextView header;
    private final TextView currentName;
    private final TextView speedLine;
    private final TextView bytesLine;
    /** One button whose meaning follows the state: cancel while moving, back when finished. */
    private final TextView action;

    private final List<TransferItem> items = new ArrayList<TransferItem>();

    private int totalCount;
    private int doneCount;
    private long doneSum;
    private long totalBytes;
    private double speed;
    private long lastSampleMs;
    private long lastSampleBytes;
    private int lastFilePercent = -1;
    private boolean finished;

    public QueueScreen(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setBackgroundColor(Ui.BG);

        arc = new ArcMeter(context);
        addView(arc, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        RoundColumn column = new RoundColumn(context);
        // 0.91 = 218.4px, i.e. 5px inside the ring's inner edge (223.5px). The ring is drawn at a fixed
        // radius; this only shrinks the box the text is measured in, so nothing can land on the stroke.
        column.setRadiusFraction(0.91f);
        column.setSpacingPx(Ui.dp(context, SPACING_DP));
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Hierarchy: the ring is the hero, then the speed, then the file, then the quiet counter.
        header = Ui.text(context, "0/0", 10f, Ui.TEXT_DIM);
        header.setMinHeight(HEADER_MIN_PX);

        currentName = Ui.text(context, "准备中", 11f, Ui.TEXT);
        currentName.setSingleLine(true);
        currentName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        currentName.setMinHeight(NAME_MIN_PX);

        speedLine = Ui.text(context, "测速中…", 16f, Ui.ACCENT);
        speedLine.setSingleLine(true);
        speedLine.setMinHeight(SPEED_MIN_PX);

        bytesLine = Ui.dim(context, "0 B / 0 B · 0%", 9.5f);
        bytesLine.setSingleLine(true);
        bytesLine.setMinHeight(META_MIN_PX);

        action = Ui.pill(context, "取消全部", Ui.ACCENT, Ui.ON_ACCENT, 12.5f,
                Ui.dp(context, 42), 4f, 16f);
        Ui.setPressable(action);
        action.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (QueueScreen.this.listener == null) {
                    return;
                }
                if (totalCount > 0 && !finished) {
                    QueueScreen.this.listener.onCancelAll();
                } else {
                    QueueScreen.this.listener.onBack();
                }
            }
        });

        // Inset in a wrapper: a RoundColumn hands its child the full chord, and a 390x72 bar is the
        // "thin strip that is hard to hit" this redesign is meant to get rid of. 310x84 reads as a
        // button and leaves 30px of air on each side.
        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), 0);
        actionRow.addView(action, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                Ui.dp(context, 42)));

        column.addView(header);
        column.addView(currentName);
        column.addView(speedLine);
        column.addView(bytesLine);
        column.addView(actionRow);

        refreshNumbers();
    }

    // ---- public API used by the activity -----------------------------------------------------------

    /** Whole-queue counter for the header. */
    public void setOverall(int done, int total) {
        totalCount = total;
        doneCount = done < 0 ? 0 : done;
        header.setText(doneCount + "/" + (total < 0 ? 0 : total));
        if (total <= 0 && !finished) {
            action.setText("返回相册");
            currentName.setText("没有待传文件");
        }
    }

    /** The file currently being written. Its own percentage rides in the byte line. */
    public void setCurrent(String name, long done, long total) {
        String label = name == null || name.isEmpty() ? "准备中" : name;
        currentName.setText(label);
        lastFilePercent = total > 0 ? (int) (done * 100L / total) : -1;
        refreshNumbers();
    }

    /**
     * Replaces the queue contents.
     *
     * <p>Only the totals survive: the screen shows one aggregate figure, so there is nothing per row
     * to keep. The item objects are still tracked so the byte sum is exact when progress callbacks
     * arrive out of order across the two parallel download threads.
     */
    public void setItems(List<TransferItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        totalBytes = 0;
        doneSum = 0;
        for (TransferItem item : items) {
            totalBytes += Math.max(0, item.total);
            doneSum += Math.max(0, item.done);
        }
        speed = 0;
        lastSampleMs = 0;
        lastSampleBytes = doneSum;
        lastFilePercent = -1;
        finished = false;
        arc.reset();
        arc.setOverall(0f);
        refreshNumbers();
    }

    /** Final summary in the middle of the ring; the ring itself turns green and full. */
    public void setDone(int ok, int failed, String summary) {
        String text;
        if (failed > 0) {
            text = "完成 " + ok + " · 失败 " + failed;
        } else if (summary != null && !summary.isEmpty()) {
            text = summary;
        } else {
            text = "全部完成 " + ok + " 项";
        }
        finished = true;
        header.setText(ok + "/" + totalCount);
        header.setTextColor(failed > 0 ? Ui.WARN : Ui.OK);
        currentName.setText(text);
        doneSum = totalBytes;
        speed = 0;
        arc.setColor(failed > 0 ? Ui.WARN : Ui.OK);
        arc.setOverall(1f);
        speedLine.setText(failed > 0 ? "有文件失败" : "已全部存到手表");
        speedLine.setTextColor(failed > 0 ? Ui.WARN : Ui.OK);
        bytesLine.setText(Ui.bytes(totalBytes) + " · " + items.size() + " 项");
        action.setText("返回相册");
        totalCount = 0;
    }

    // ---- per-file progress --------------------------------------------------------------------------

    /** Folds one file's byte counter into the running total. */
    public void onProgress(String name, long done, long total) {
        for (int i = 0; i < items.size(); i++) {
            TransferItem item = items.get(i);
            if (item.path().equals(name) || item.name().equals(name)) {
                long delta = done - item.done;
                if (delta > 0) {
                    doneSum += delta;
                }
                item.done = done;
                if (total > 0 && done >= total) {
                    item.finished = true;
                }
                lastFilePercent = total > 0 ? (int) (done * 100L / total) : -1;
                refreshNumbers();
                return;
            }
        }
    }

    /** Marks one file as finished without a full rebuild, for the downloader's per-file callback. */
    public void onFinished(String name) {
        for (int i = 0; i < items.size(); i++) {
            TransferItem item = items.get(i);
            if (item.name().equals(name) || item.path().equals(name)) {
                if (!item.finished && item.total > 0) {
                    doneSum += Math.max(0, item.total - item.done);
                    item.done = item.total;
                }
                item.finished = true;
                doneCount++;
                refreshNumbers();
                return;
            }
        }
    }

    // ---- numbers -----------------------------------------------------------------------------------

    /** Speed, estimate and the byte line — the two things the ring cannot say by itself. */
    private void refreshNumbers() {
        if (finished) {
            return;
        }
        long now = System.currentTimeMillis();
        if (totalBytes > 0) {
            float fraction = doneSum / (float) totalBytes;
            arc.setOverall(fraction > 1f ? 1f : fraction);
        }

        // Throughput from a short rolling window, so one stalled read does not collapse the estimate.
        if (lastSampleMs == 0) {
            lastSampleMs = now;
            lastSampleBytes = doneSum;
        } else if (now - lastSampleMs >= SPEED_WINDOW_MS) {
            double instant = (doneSum - lastSampleBytes) * 1000.0 / (now - lastSampleMs);
            if (instant < 0) {
                instant = 0;
            }
            speed = speed <= 0 ? instant : speed * 0.55 + instant * 0.45;
            lastSampleMs = now;
            lastSampleBytes = doneSum;
        }

        double remaining = Math.max(0, totalBytes - doneSum);
        double eta = speed > 1 ? remaining / speed : 0;
        if (speed <= 1 && remaining > 0) {
            // No sample yet: an em dash plus "--:--" reads as a broken field, "测速中" reads as a
            // promise. It only lasts the first ~450 ms.
            speedLine.setText("测速中…");
        } else {
            speedLine.setText(Ui.rate(speed) + " · 剩 " + Ui.countdown(eta));
        }
        speedLine.setTextColor(Ui.ACCENT);

        int percent = totalBytes > 0 ? (int) (doneSum * 100L / totalBytes) : 0;
        StringBuilder sb = new StringBuilder(Ui.bytes(doneSum)).append(" / ")
                .append(Ui.bytes(totalBytes)).append(" · ").append(percent).append('%');
        if (lastFilePercent >= 0) {
            sb.append(" · 本个 ").append(lastFilePercent).append('%');
        }
        bytesLine.setText(sb.toString());
    }

    /** Builds a queue item straight from a media file, so the activity does not have to. */
    public static TransferItem itemFor(MediaFile file) {
        return new TransferItem(file.getName(), file.getPath(), file.getSizeBytes());
    }
}
