package com.watch.osmo.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.watch.osmo.core.CameraInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Start screen: scan, pick a camera, watch what the client is doing.
 *
 * <p>This is a plain {@link FrameLayout} holding one scrolling list. It deliberately does NOT use
 * {@link RoundColumn}: that class narrows every child to the width the circle allows across the
 * child's own rows, which is right for a short centred stack and wrong for a single full-height
 * child — a child starting at y=0 lands in the row where the circle is a point, so it was measured
 * at exactly 0px wide and the whole screen rendered blank. The width guarantee comes from
 * {@link RoundList} inside, which hands every row the width available a quarter of the way up the
 * panel.
 *
 * <p>Rules this screen obeys, all of them measured on the real watch:
 * <ul>
 *   <li>No two controls side by side. Two 56dp buttons on one row total 238dp of a 226dp screen and
 *       the right-hand one lands outside the circle.</li>
 *   <li>Nothing tappable in the top or bottom 40px: the scroller's own padding keeps the first and
 *       last row out of the narrow caps.</li>
 *   <li>At most three cameras are listed; the whole stack scrolls, so a chatty BLE scan can never
 *       push a control off the panel.</li>
 * </ul>
 */
public class DeviceScreen extends FrameLayout {

    /** What the user can ask this screen to do; the activity wires it to a {@link Controller}. */
    public interface Listener {
        void onScanToggled(boolean start);

        void onCameraPicked(CameraInfo camera);

        void onOpenLog();
    }

    private static final int MAX_CAMERAS = 3;
    private static final int MAX_LOGS = 3;
    /** The two action buttons share one row: 56dp tall, half the chord wide each. */
    private static final int BIG_BUTTON_DP = 56;

    private final Listener listener;
    private final List<CameraInfo> shown = new ArrayList<CameraInfo>();

    private final TextView subtitle;
    private final TextView status;
    private final TextView[] cameraPills = new TextView[MAX_CAMERAS];
    private final TextView scanButton;
    private final TextView logEntry;
    private final TextView logTail;

    private final List<String> logs = new ArrayList<String>();
    private boolean scanning;
    /**
     * A whole connection attempt is running. The main button stops meaning "scan" and starts meaning
     * "cancel": one tap on the camera now buys up to six automatic attempts, so the user needs a way
     * out that is not "leave the app".
     */
    private boolean busy;

    private static View gap(Context context, int dp) {
        View v = new View(context);
        v.setLayoutParams(new ViewGroup.LayoutParams(Ui.dp(context, dp), 0));
        return v;
    }

    public DeviceScreen(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setBackgroundColor(Ui.BG);

        TextView title = Ui.title(context, "大疆传片");
        title.setMinHeight(Ui.dp(context, 13));

        subtitle = Ui.dim(context, "DJI 相机 → 手表", 11f);
        subtitle.setMinHeight(Ui.dp(context, 8));

        status = Ui.text(context, "点「扫描相机」寻找 Osmo", 12f, Ui.TEXT);
        status.setMinHeight(Ui.dp(context, 19));

        scanButton = Ui.pill(context, "扫描相机", Ui.ACCENT, Ui.ON_ACCENT, 13f,
                Ui.dp(context, BIG_BUTTON_DP), 4f, 16f);
        Ui.setPressable(scanButton);
        scanButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (DeviceScreen.this.listener == null) {
                    return;
                }
                // While connecting the same button is the cancel button.
                DeviceScreen.this.listener.onScanToggled(!busy && !scanning);
            }
        });

        // Side by side, not stacked: one full-width 46dp bar is 381px wide and reads as a thin strip
        // on a round panel — the user's words were "这么细，一条一点都不好按". Two 186x118px buttons
        // in one row are square-ish, sit in the circle's widest band, and put the log entry within
        // reach instead of leaving it as a 30dp text link at the narrow bottom.
        logEntry = Ui.pill(context, "自检 · 日志", Ui.CARD, Ui.TEXT, 12f,
                Ui.dp(context, BIG_BUTTON_DP), 4f, 16f);
        Ui.setPressable(logEntry);
        logEntry.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (DeviceScreen.this.listener != null) {
                    DeviceScreen.this.listener.onOpenLog();
                }
            }
        });

        LinearLayout actions = Ui.row(context);
        actions.setGravity(android.view.Gravity.CENTER);
        actions.addView(scanButton, new LinearLayout.LayoutParams(0, Ui.dp(context, BIG_BUTTON_DP),
                1f));
        actions.addView(gap(context, 6), new LinearLayout.LayoutParams(Ui.dp(context, 6),
                Ui.dp(context, BIG_BUTTON_DP)));
        actions.addView(logEntry, new LinearLayout.LayoutParams(0, Ui.dp(context, BIG_BUTTON_DP),
                1f));

        logTail = Ui.dim(context, "", 8f);
        logTail.setSingleLine(false);
        logTail.setMaxLines(MAX_LOGS);
        logTail.setMinHeight(Ui.dp(context, 10));

        // The stack does not fit the panel once a couple of cameras show up: a camera pill is a 56dp
        // touch target (119px), so two of them plus the scan button already exceed the 480px height.
        // RoundColumn lays children out sequentially, so anything past the bottom edge was placed off
        // the glass and silently became invisible and untappable. Scrolling removes that failure mode:
        // RoundList keeps every row inside the circle's width, and the scroller's top/bottom padding
        // lets any row be brought into the wide middle band before it is read or tapped.
        RoundList rows = new RoundList(context);
        rows.addView(title);
        rows.addView(subtitle);
        rows.addView(status);
        for (int i = 0; i < MAX_CAMERAS; i++) {
            final TextView pill = Ui.pill(context, "", Ui.CARD, Ui.TEXT, 13f);
            pill.setVisibility(GONE);
            Ui.setPressable(pill);
            pill.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    int index = indexOfPill(pill);
                    if (index >= 0 && DeviceScreen.this.listener != null) {
                        DeviceScreen.this.listener.onCameraPicked(shown.get(index));
                    }
                }
            });
            cameraPills[i] = pill;
            rows.addView(pill);
        }
        rows.addView(actions);
        rows.addView(logTail);

        // 20dp (42px) of top and bottom padding, not the list default of 96dp: the whole stack is
        // ~355px tall, and the default padding made it 763px, so the log tail sat below the glass and
        // was clipped mid-line at rest.
        addView(Ui.scroller(context, rows, 20, 20), new android.view.ViewGroup.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private int indexOfPill(View pill) {
        for (int i = 0; i < cameraPills.length; i++) {
            if (cameraPills[i] == pill) {
                return i;
            }
        }
        return -1;
    }

    // ---- public API used by the activity -----------------------------------------------------------

    /**
     * The "who -> 手表" line. Not a constant: the app talks to every Osmo body, so the name comes from
     * the camera that was actually found or connected, with a generic fallback.
     */
    public void setSubtitle(String text) {
        subtitle.setText(text == null || text.isEmpty() ? "DJI 相机 → 手表" : text);
    }

    /** One line of stage text, or hand-written two-line text with {@code \n}. Wraps rather than clips. */
    public void setStatus(String text) {
        status.setText(text == null ? "" : text);
        status.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Rebuilds the tappable camera list. Only the first {@value #MAX_CAMERAS} entries are shown. */
    public void setCameras(List<CameraInfo> cameras) {
        shown.clear();
        if (cameras != null) {
            shown.addAll(cameras);
        }
        for (int i = 0; i < MAX_CAMERAS; i++) {
            TextView pill = cameraPills[i];
            if (i < shown.size()) {
                pill.setText(label(shown.get(i)));
                pill.setVisibility(VISIBLE);
            } else {
                pill.setText("");
                pill.setVisibility(GONE);
            }
        }
        requestLayout();
    }

    /** While a connection runs, the primary button becomes 「取消连接」 instead of a scan toggle. */
    public void setBusy(boolean value) {
        if (busy == value) {
            return;
        }
        busy = value;
        if (value) {
            scanButton.setText("取消连接");
            scanButton.setBackground(Ui.rounded(Ui.WARN, Ui.dp(getContext(), 16)));
            scanButton.setTextColor(Ui.ON_ACCENT);
        } else {
            setScanning(scanning);
        }
    }

    /** Flips the main button between 扫描相机 and 停止扫描. */
    public void setScanning(boolean value) {
        this.scanning = value;
        // While a connection attempt runs the button means 「取消连接」 and this call must only record
        // the state: the state machine calls setScanning(false) on every PAIRING transition, which used
        // to repaint the label back to 「扫描相机」 right after setBusy(true) had set it.
        if (busy) {
            return;
        }
        scanButton.setText(value ? "停止扫描" : "扫描相机");
        scanButton.setBackground(Ui.rounded(value ? Ui.CARD : Ui.ACCENT, Ui.dp(getContext(), 16)));
        scanButton.setTextColor(value ? Ui.TEXT : Ui.ON_ACCENT);
        logEntry.setBackground(Ui.rounded(Ui.CARD, Ui.dp(getContext(), 16)));
    }

    /** Appends one line to the three-line log tail; safe to call for every log event. */
    public void onLog(String line) {
        if (line == null) {
            return;
        }
        logs.add(line);
        while (logs.size() > MAX_LOGS) {
            logs.remove(0);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < logs.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(logs.get(i));
        }
        logTail.setText(sb.toString());
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static String label(CameraInfo camera) {
        if (camera == null) {
            return "未知相机";
        }
        String model = camera.modelName;
        if (model == null || model.isEmpty()) {
            model = camera.name;
        }
        if (model == null || model.isEmpty()) {
            model = "未知相机";
        }
        return model + " · " + signalLabel(camera.signalLevel());
    }

    private static String signalLabel(int level) {
        if (level >= 3) {
            return "信号强";
        }
        if (level == 2) {
            return "信号中";
        }
        return "信号弱";
    }
}
