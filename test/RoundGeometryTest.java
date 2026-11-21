import com.watch.osmo.ui.Round;

import java.util.Locale;

/**
 * Geometry self-check for the round panel. Plain Java, plain {@code main}, no JUnit.
 *
 * <p>The watch reports no safe area at all -- DisplayCutout insets and the rounded-corner frame both
 * measure as empty rectangles and the window is edge-to-edge -- so every layout decision in this app
 * is plain circle arithmetic. That arithmetic is the one thing that must not be guessed, hence this
 * test: it runs without a device, without android.jar and without a Context.
 *
 * <p>Two checks mirror formulas that live in classes needing Android ({@code RoundList.rowWidth} and
 * {@code RoundColumn.allowedWidth}). They are copied rather than called because a ViewGroup needs a
 * Context, and a round-display bug only shows up on hardware. The copies use the real constants:
 * 8dp (17px) and 10dp (21px) margins at 340dpi, and RoundColumn's radius fraction of 0.985.
 *
 * <p>Two facts this test exists to keep straight, because both were wrong on the first pass:
 * <ul>
 *   <li>RoundColumn's effective radius is 0.985*240 = 236.4px, so its guarantee is "inside the
 *       236.4px circle". The panel is then checked separately to confirm it is at least as large.</li>
 *   <li>A 480px-wide box is not impossible everywhere -- it fits in a ~24px-tall slot across the
 *       exact centre of the panel. What is impossible is a 480px-wide box anywhere else, which is
 *       why every band above and below the middle carries its own width limit.</li>
 * </ul>
 */
public final class RoundGeometryTest {

    // ---- the real panel ---------------------------------------------------------------------------
    private static final float W = 480f;
    private static final float H = 480f;
    private static final float CX = 240f;
    private static final float CY = 240f;
    private static final float R = 240f;

    /** RoundColumn's own safety factor, copied from its constructor. */
    private static final float COLUMN_RADIUS_FRACTION = 0.985f;
    private static final float COLUMN_R = R * COLUMN_RADIUS_FRACTION;
    /** RoundColumn's 10dp margin at 340dpi. */
    private static final float COLUMN_MARGIN = 21f;
    /** RoundList's 8dp margin at 340dpi. */
    private static final float LIST_MARGIN = 17f;

    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        System.out.println("=== 圆屏几何自测 480x480 c=(240,240) r=240 ===");

        checkHalfWidths();
        checkRowWidth();
        checkColumn();
        checkScreenBands();

        System.out.println();
        System.out.println("检查 " + checks + " 项，失败 " + failures + " 项");
        if (failures > 0) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    // ---- 1. halfWidthAt ---------------------------------------------------------------------------

    private static void checkHalfWidths() {
        System.out.println();
        System.out.println("--- 1. Round.halfWidthAt ---");
        expect("halfWidthAt(240) == 240", Round.halfWidthAt(240f, CY, R), 240f, 0.001f);
        expect("halfWidthAt(80) ~= 178.885", Round.halfWidthAt(80f, CY, R), 178.885f, 0.01f);
        expect("halfWidthAt(0) == 0", Round.halfWidthAt(0f, CY, R), 0f, 0.001f);
        expect("halfWidthAt(400) ~= 178.885", Round.halfWidthAt(400f, CY, R), 178.885f, 0.01f);
        expect("halfWidthAt(480) == 0", Round.halfWidthAt(480f, CY, R), 0f, 0.001f);
        expect("halfWidthAt(40) ~= 132.665", Round.halfWidthAt(40f, CY, R), 132.665f, 0.01f);
        expect("halfWidthAt(20) ~= 95.917", Round.halfWidthAt(20f, CY, R), 95.917f, 0.01f);

        expect("halfWidthAt 上下对称 (60/420)",
                Round.halfWidthAt(60f, CY, R), Round.halfWidthAt(2 * CY - 60f, CY, R), 0.001f);
        expect("halfWidthAt 上下对称 (150/330)",
                Round.halfWidthAt(150f, CY, R), Round.halfWidthAt(2 * CY - 150f, CY, R), 0.001f);

        boolean rising = true;
        float prev = -1f;
        for (float y = 0f; y <= CY; y += 5f) {
            float w = Round.halfWidthAt(y, CY, R);
            if (w < prev - 0.001f) {
                rising = false;
                break;
            }
            prev = w;
        }
        report("halfWidthAt(0..240) 单调不减", rising, "宽度随 y 接近圆心而增加");
    }

    // ---- 2. RoundList.rowWidth --------------------------------------------------------------------

    /** Verbatim copy of {@code RoundList.rowWidth}, margin included. */
    private static int rowWidth(int width, int height) {
        float cy = height / 2f;
        float r = Math.min(width, height) / 2f;
        float probeY = cy - 0.25f * height;
        float half = Round.halfWidthAt(probeY, cy, r) - LIST_MARGIN;
        int w = (int) (half * 2f);
        if (w > width) {
            w = width;
        }
        return Math.max(0, w);
    }

    /**
     * RoundList hands every row the width available a quarter of the viewport height above the
     * centre, then lays rows out at that fixed width. So the invariants are: the row is fully inside
     * the panel at that probe line, the width is exactly the probe line's allowance, and it is
     * narrower than what the centre of the panel would allow.
     */
    private static void checkRowWidth() {
        System.out.println();
        System.out.println("--- 2. RoundList.rowWidth 算出的行 ---");
        int rowHeight = 56; // a 52dp row, rounded up
        int rw = rowWidth((int) W, (int) H);
        System.out.println(String.format(Locale.US,
                "rowWidth(480,480) = %d px (行高 %d px)", rw, rowHeight));

        report("rowWidth > 0", rw > 0, "rw=" + rw);
        report("rowWidth <= 面板宽度", rw <= (int) W, "rw=" + rw);
        report("rowWidth 略小于面板宽（留有边距）", rw < (int) W - 60, "rw=" + rw);

        // The probe line is where the row is flush: the formula spends its whole margin there.
        float probeY = CY - 0.25f * H;
        float probeAllowance = 2f * (Round.halfWidthAt(probeY, CY, R) - LIST_MARGIN);
        report("rowWidth == 探针线的可用宽度", Math.abs(rw - probeAllowance) < 1.5f,
                String.format(Locale.US, "rw=%d 探针线=%.1f y=%.0f", rw, probeAllowance, probeY));

        // The row must be fully inside the panel where the list pins it.
        report("行置于探针线时四角在圆内", fitsPanel(probeY, rowHeight, rw),
                String.format(Locale.US, "y=[%.0f,%.0f] 四角最大距圆心 %.2f  半径 240",
                        probeY, probeY + rowHeight, maxCornerDistance(probeY, rowHeight, rw)));

        // A full-width row at the top edge is always cut.
        report("整宽 480x56 行放在顶端越界", !fitsPanel(0f, rowHeight, W),
                "这就是顶部/底部不能放整宽控件的原因");

        // Widest 56px rectangle that fits at the top edge vs centred.
        float topEdgeMax = widestFitting(0f, rowHeight);
        float midMax = widestFitting(CY - rowHeight / 2f, rowHeight);
        System.out.println(String.format(Locale.US,
                "56px 高矩形最大全内宽度：顶端 %.1f px，居中 %.1f px", topEdgeMax, midMax));
        report("顶端 56px 行最大宽 < 300px", topEdgeMax < 300f, "topEdgeMax=" + fmt(topEdgeMax));
        // Even dead centre a 56px-tall full-width row loses its corners: the box would span
        // x=[0,480] at y=[212,268], and (0,212) is 28px below the centre line but 240px to the side,
        // so its distance is 241.7 > 240. The circle is only 480 wide along an infinitely thin line.
        report("居中 56px 行最大宽 < 480px", midMax < 480f && midMax > 460f, "midMax=" + fmt(midMax));
        report("rowWidth 比居中行窄", rw < midMax, "rw=" + rw + " < " + fmt(midMax));
    }

    // ---- 3. RoundColumn ---------------------------------------------------------------------------

    /** Mirror of {@code RoundColumn.allowedWidth}, with the constructor's real constants. */
    private static float allowedWidth(float top, float bottom) {
        float half = Round.safeHalfWidth(top, bottom, CY, COLUMN_R, COLUMN_MARGIN);
        float w = half * 2f;
        return w > W ? W : Math.max(0f, w);
    }

    /**
     * Five controls through RoundColumn's real two-pass measure: the stack is centred in the view and
     * each child is given the width the circle allows across its own [top, bottom] band.
     */
    private static void checkColumn() {
        System.out.println();
        System.out.println("--- 3. RoundColumn 一列 5 个控件 ---");

        final float spacing = 17f; // RoundColumn's 8dp spacing at 340dpi
        final float childHeight = 60f;
        final float block = 5 * childHeight + 4 * spacing;
        final float y0 = Math.max(0f, (H - block) / 2f);

        System.out.println(String.format(Locale.US,
                "childHeight=%.0f spacing=%.0f 块高=%.0f 居中 y0=%.0f", childHeight, spacing, block, y0));

        int fitPanel = 0;
        int fitColumn = 0;
        float y = y0;
        for (int i = 0; i < 5; i++) {
            float top = y;
            float bottom = y + childHeight;
            float allowed = allowedWidth(top, bottom);
            // RoundColumn measures the child at exactly `allowed` width; that is the real box.
            // NOTE: the third argument is the box HEIGHT, not the bottom edge.
            boolean inPanel = fitsPanel(top, bottom - top, allowed);
            boolean inColumn = fitsColumn(top, bottom - top, allowed);
            if (inPanel) {
                fitPanel++;
            }
            if (inColumn) {
                fitColumn++;
            }
            System.out.println(String.format(Locale.US,
                    "  child %d y=[%6.1f,%6.1f] allowedWidth=%6.1f 面板圆内=%-5s 列圆内=%s",
                    i, top, bottom, allowed, inPanel, inColumn));
            y = bottom + spacing;
        }
        report("5 个控件全部 fitsInCircle（面板 240px 圆）", fitPanel == 5, fitPanel + "/5 通过");
        report("5 个控件全部 fitsInCircle（列 236.4px 圆）", fitColumn == 5, fitColumn + "/5 通过");

        float firstAllowed = allowedWidth(y0, y0 + childHeight);
        report("第一个控件被收窄到 < 300px", firstAllowed > 0f && firstAllowed < 300f,
                "allowedWidth=" + fmt(firstAllowed));
        float centreAllowed = allowedWidth(CY - childHeight / 2f, CY + childHeight / 2f);
        report("中间控件比第一个宽", centreAllowed > firstAllowed,
                String.format(Locale.US, "%.1f > %.1f", centreAllowed, firstAllowed));

        // A plain LinearLayout would put the first child at y=0 at the full 480px.
        float topAllowed = allowedWidth(0f, childHeight);
        report("y=0 处允许宽度 < 30px（整宽必然越界）", topAllowed < 30f,
                "allowedWidth(0,60)=" + fmt(topAllowed) + "，而 LinearLayout 会给 480");

        // Empirical ceiling: how tall can five centred children be and still all fit?
        int ceiling = 0;
        for (int h = 10; h <= 120; h += 1) {
            float total = 5 * h + 4 * spacing;
            float start = Math.max(0f, (H - total) / 2f);
            boolean ok = true;
            float yy = start;
            for (int i = 0; i < 5; i++) {
                float allowed = allowedWidth(yy, yy + h);
                // third argument is the HEIGHT again, not the bottom edge
                if (!fitsPanel(yy, h, allowed) || allowed <= 0f) {
                    ok = false;
                    break;
                }
                yy += h + spacing;
            }
            if (ok) {
                ceiling = h;
            } else {
                break;
            }
        }
        System.out.println(String.format(Locale.US,
                "5 个控件在 480 圆屏上可行的最大高度 = %d px（间距 %.0f px）", ceiling, spacing));
        report("可行高度上限在 50..80px 之间", ceiling >= 50 && ceiling <= 80, "ceiling=" + ceiling);

        // NOTE: the old check here modelled DeviceScreen as a fixed 9-child RoundColumn stack. That
        // screen now scrolls (see section 4), so the model no longer describes any code path.
    }

    // ---- 4. the bands the screens actually use -----------------------------------------------------

    /**
     * The screens themselves.
     *
     * <p>Two different guarantees are at work, and they need different checks:
     *
     * <ul>
     *   <li>Device, queue and log are {@code RoundColumn}s. The column narrows every child to the
     *       width the circle allows at that child's own vertical position, so the guarantee is
     *       structural and per child: simulate the stack exactly as the column lays it out and every
     *       box must land inside.</li>
     *   <li>BrowseScreen pins fixed bands instead (a header, a weighted scroller, a footer), so the
     *       meaningful check is on the interactive boxes inside those bands: all four corners inside
     *       the circle. A band touching y=0 or y=480 can never satisfy that — the circle is a point
     *       there — which is exactly why the screen insets itself 40px top and bottom before pinning
     *       anything, and why the width caps are derived from the band rather than guessed.</li>
     * </ul>
     */
    private static void checkScreenBands() {
        System.out.println();
        System.out.println("--- 4. 各界面实际控件栈 / band ---");

        // RoundColumn screens: heights in px, taken from the classes' own constants.
        //
        // DeviceScreen is deliberately NOT checked here. Its stack (a title, a subtitle, a status
        // line, up to three camera pills, a scan button, a log entry and a log tail) is taller than the
        // 480px panel as soon as two cameras appear, and it used to lay the tail off the glass. It now
        // scrolls through Ui.scroller + RoundList, so its rows are governed by RoundList.rowWidth,
        // which section 2 checks.
        //
        // QueueScreen is one RoundColumn at radius 0.91 (218.4px, i.e. 5px inside the ring's stroke):
        // 10sp counter, 11sp name, 16sp speed, 9.5sp byte line, one 84px action, with 5dp gaps.
        columnStack("QueueScreen", new float[]{28, 28, 40, 25, 84}, 11f);

        // BrowseScreen: the bars float over a full-bleed grid, so the only fixed bands are the
        // controls themselves.
        //   header   y=[ 34,  78] 244px
        //   tabs     y=[ 78, 118] 300px
        //   grid     full screen behind everything, five 96px tiles, x=[0,480]
        //   summary  y=[354, 372] 302px
        //   footer   y=[372, 444] 240px  (3 x 76px buttons + 2 x 6px gutters)
        circleBox("BrowseScreen 头部", 244f, 34f, 78f);
        circleBox("BrowseScreen 类型页签", 300f, 78f, 118f);
        circleBox("BrowseScreen 选中行", 302f, 354f, 372f);
        circleBox("BrowseScreen 底部按钮", 240f, 372f, 444f);

        // The grid is deliberately full-bleed: the panel is supposed to crop the outer columns. What
        // must hold is the tappability criterion — every tile's centre inside the circle — plus a
        // visible area that is still recognisable artwork. The 132px of scroll padding puts the first
        // row *below* the floating header/tabs (which end at y=118), so none of it is hidden at rest.
        fullBleedRow("BrowseScreen 栅格首行", 122f, 96f);
        fullBleedRow("BrowseScreen 栅格次行", 218f, 96f);

        // The formal statement of the panel's own edges.
        float w40 = Round.halfWidthAt(40f, CY, R) * 2f;
        report("顶部 40px 内全宽 < 270px", w40 < 270f,
                String.format(Locale.US, "y=40 处 %.1f px", w40));

        float maxHalfHeight = (float) Math.sqrt(Math.max(0f, R * R - (W / 2f) * (W / 2f)));
        report("整宽矩形只有 ~24px 高的中间缝", 2 * maxHalfHeight < 25f,
                String.format(Locale.US, "480 宽矩形居中时最大高度 %.1f px", 2 * maxHalfHeight));
        report("480x30 整宽条放不下", widestFittingAnywhere(30f) <= 0f,
                "所以每个上下 band 都带回自己的宽度上限");

        float side = (float) (R * Math.sqrt(2));
        System.out.println(String.format(Locale.US,
                "内切正方形边长 %.1f px = 直径的 %.0f%%", side, side / (2 * R) * 100f));
        report("内切正方形 < 340px", side < 340f, "side=" + fmt(side));
    }

    /** Simulates a RoundColumn stack and asserts every child box is inside the column's circle. */
    private static void columnStack(String name, float[] heights, float spacing) {
        float total = 0f;
        for (float h : heights) {
            total += h;
        }
        total += spacing * (heights.length - 1);
        float y = Math.max(0f, (H - total) / 2f);
        int inside = 0;
        float narrowest = Float.MAX_VALUE;
        for (float h : heights) {
            float allowed = allowedWidth(y, y + h);
            boolean ok = allowed > 0f && fitsColumn(y, h, allowed);
            if (ok) {
                inside++;
            }
            narrowest = Math.min(narrowest, allowed);
            y += h + spacing;
        }
        System.out.println(String.format(Locale.US,
                "  %-14s %d 个子控件 总高 %6.0f 最窄宽度 %6.1f px",
                name, heights.length, total, narrowest));
        report(name + " 全部子控件在圆内（RoundColumn 运行时保证）",
                inside == heights.length, inside + "/" + heights.length + " 通过");
    }

    /** Asserts a width-capped, band-pinned box has all four corners inside the panel. */
    private static void circleBox(String name, float width, float top, float bottom) {
        boolean ok = fitsPanel(top, bottom - top, width);
        report(name + " 四角在圆内", ok, String.format(Locale.US,
                "w=%.0f y=[%.0f,%.0f]", width, top, bottom));
    }

    /**
     * A deliberately full-bleed row of 96px tiles: x from 0 to 480.
     *
     * <p>The four-corner rule cannot apply — the outermost tiles are <b>meant</b> to be cropped by the
     * panel, that is what "顶到屏幕边缘" costs on a circle. The two properties that do matter:
     *
     * <ul>
     *   <li>every tile's <b>centre</b> must be inside the circle, or the tile cannot be tapped;</li>
     *   <li>no tile may be so far out that less than half of it is visible.</li>
     * </ul>
     */
    private static void fullBleedRow(String name, float top, float cell) {
        float bottom = top + cell;
        int columns = 5;
        float worstVisible = 1f;
        float worstCentre = 0f;
        boolean centresIn = true;
        for (int c = 0; c < columns; c++) {
            float left = c * cell;
            float right = left + cell;
            float cx = (left + right) / 2f;
            float cy = (top + bottom) / 2f;
            float visible = Round.visibleFraction(left, top, right, bottom, W / 2f, H / 2f, R);
            float dist = (float) Math.sqrt((cx - W / 2f) * (cx - W / 2f) + (cy - H / 2f) * (cy - H / 2f));
            if (dist > worstCentre) {
                worstCentre = dist;
            }
            if (dist > R) {
                centresIn = false;
            }
            if (visible < worstVisible) {
                worstVisible = visible;
            }
        }
        report(name + " 每块中心在圆内", centresIn,
                String.format(Locale.US, "最远中心 %.1f px / R=%.0f", worstCentre, R));
        report(name + " 每块至少一半可见", worstVisible >= 0.5f,
                String.format(Locale.US, "最差可见 %.0f%%", worstVisible * 100f));
        System.out.println(String.format(Locale.US,
                "  %-24s y=[%.0f,%.0f] 最外块可见 %.0f%%", name, top, bottom, worstVisible * 100f));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static boolean fitsPanel(float top, float height, float width) {
        return Round.fitsInCircle(CX - width / 2f, top, CX + width / 2f, top + height, CX, CY, R);
    }

    /** Same box, checked against the smaller circle RoundColumn actually reasons about. */
    private static boolean fitsColumn(float top, float height, float width) {
        return Round.fitsInCircle(CX - width / 2f, top, CX + width / 2f, top + height, CX, CY, COLUMN_R);
    }

    /** Widest centred rectangle of the given height and top edge that fits the panel. */
    private static float widestFitting(float top, float height) {
        if (height <= 0f || top < 0f || top + height > H) {
            return 0f;
        }
        for (float w = W; w > 0f; w -= 0.5f) {
            if (fitsPanel(top, height, w)) {
                return w;
            }
        }
        return 0f;
    }

    /** 480 if a full-width rectangle of that height fits somewhere, else -1. */
    private static float widestFittingAnywhere(float height) {
        for (float top = 0f; top + height <= H; top += 1f) {
            if (widestFitting(top, height) >= W - 0.5f) {
                return W;
            }
        }
        return -1f;
    }

    private static float maxCornerDistance(float top, float height, float width) {
        float left = CX - width / 2f;
        float right = CX + width / 2f;
        float bottom = top + height;
        return Math.max(Math.max(dist(left, top), dist(right, top)),
                Math.max(dist(left, bottom), dist(right, bottom)));
    }

    private static float dist(float x, float y) {
        return (float) Math.sqrt(sq(x - CX) + sq(y - CY));
    }

    private static float sq(float v) {
        return v * v;
    }

    private static void expect(String name, float actual, float wanted, float tolerance) {
        checks++;
        boolean ok = Math.abs(actual - wanted) <= tolerance;
        if (!ok) {
            failures++;
        }
        System.out.println(String.format(Locale.US, "  [%s] %-34s actual %10.4f  want %10.4f (+-%s)",
                ok ? "OK" : "XX", name, actual, wanted, fmt(tolerance)));
    }

    private static void report(String name, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println(String.format("  [%s] %-34s %s", ok ? "OK" : "XX", name, detail));
    }

    private static String fmt(float v) {
        return String.format(Locale.US, "%.3f", v);
    }
}
