package com.watch.osmo.ui;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.watch.osmo.core.CameraInfo;
import com.watch.osmo.core.CameraStatus;
import com.watch.osmo.core.OsmoClient;
import com.watch.osmo.proto.MediaFile;
import com.watch.osmo.util.Diag;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The only activity. Owns the client, the three screens and the diagnostics page.
 *
 * <p>Screen switching is a straight swap of the content view rather than a fragment or a pager: the
 * watch screen is 480px across, only one of these screens is ever meaningful at a time, and swapping
 * sidesteps the whole question of animating between two layouts that both have to fit a circle.
 *
 * <p>The client's callbacks all arrive on the main thread, so the listener below touches views
 * directly. Everything that is not a callback -- exporting the log, reading the media directory --
 * is pushed onto a worker thread so the round-screen layout never misses a frame because of file IO.
 */
public class MainActivity extends Activity implements Controller {

    // Permission names as constants. BLUETOOTH_SCAN / BLUETOOTH_CONNECT / POST_NOTIFICATIONS do not
    // exist on API 30, so referring to Manifest.permission.* directly would be a lint error against
    // this project's compileSdk; the strings are identical either way.
    private static final String PERM_BT_SCAN = "android.permission.BLUETOOTH_SCAN";
    private static final String PERM_BT_CONNECT = "android.permission.BLUETOOTH_CONNECT";
    private static final String PERM_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION";
    private static final String PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    private static final int REQ_PERMISSIONS = 1001;
    /**
     * Log page bands, chosen so no corner leaves the panel. Top band y=[20,348] at 308px wide and
     * bottom band y=[348,480] at 296px wide; both are checked in test/RoundGeometryTest.java.
     */
    private static final int LOG_TOP_HEIGHT = 348;
    private static final int LOG_TOP_INSET = 20;
    private static final int LOG_BUTTONS_WIDTH = 300;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<CameraInfo> cameraCache = new ArrayList<CameraInfo>();
    private final List<MediaFile> mediaCache = new ArrayList<MediaFile>();
    private final List<QueueScreen.TransferItem> transferItems =
            new ArrayList<QueueScreen.TransferItem>();

    private FrameLayout root;
    private DeviceScreen deviceScreen;
    private BrowseScreen browseScreen;
    private QueueScreen queueScreen;
    private FrameLayout logScreen;
    private TextView logBody;
    private TextView cleanupButton;
    /** "清理" is a two-tap action: first tap arms it, second tap wipes. Reset on every entry. */
    private boolean cleanupArmed;
    /** Which of the three screens is mounted, so a background state change cannot yank it away. */
    private View currentScreen;

    private Thumbs thumbs;
    private OsmoClient client;
    private CameraStatus cameraStatus;
    private OsmoClient.State state = OsmoClient.State.IDLE;
    /** Camera model kept across READY/LISTING so the browse header survives a null status object. */
    private String connectedModel = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The panel powers itself down mid-transfer otherwise, and a half-written file is worse than
        // no transfer at all.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // The internal copy is what "导出日志" copies out; the external one is what plain adb can pull
        // later, when the watch is back on its own network and the session is long gone.
        Diag.init(getFilesDir(), getExternalFilesDir(null));
        long swept = 0L;
        try {
            swept = com.watch.osmo.store.FileSaver.sweepCache(this);
        } catch (Throwable t) {
            Diag.w("清理缓存失败: " + t);
        }
        if (swept > 0) {
            Diag.i("已清理 " + (swept / 1024) + " KB 过期的未完成下载");
        }
        Diag.i(Diag.usage());
        Diag.i("应用目录：" + (dirSize(getFilesDir()) / 1024) + " KB 文件 / "
                + (dirSize(getCacheDir()) / 1024) + " KB 缓存");

        // Thumbnails are decoded down to a fixed size and held in a bounded in-memory cache; there is
        // no room on the watch for a disk cache of camera imagery.
        thumbs = new Thumbs();

        buildScreens();
        setContentView(root);
        // Show the device screen immediately. Without this the activity starts with an empty root:
        // showDeviceScreen() is only reached from the state machine, and the state machine only runs
        // once something is tapped - which needs the screen that was never shown. On the watch this
        // was a blank panel with no way out.
        switchTo(deviceScreen);
        log("MainActivity onCreate，圆屏 480x480 c=(240,240) r=240");

        requestRuntimePermissions();

        // Constructing the client can touch Bluetooth and Wi-Fi; keep it off the first frame.
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    client = new OsmoClient(MainActivity.this, listener);
                    Diag.i("OsmoClient 已创建");
                } catch (Throwable t) {
                    Diag.e("OsmoClient 创建失败", t);
                    deviceScreen.setStatus("客户端不可用\n" + t);
                }
                maybePreview();
            }
        });
    }

    /**
     * Layout preview, for developing the round-screen design without a camera on the bench.
     *
     * <pre>
     *   adb shell am start -n com.watch.osmo/.ui.MainActivity --es preview grid
     *   adb shell am start -n com.watch.osmo/.ui.MainActivity --es preview queue
     * </pre>
     *
     * <p>Fake data only: no camera, no BLE, no network. It exists because the browse and transfer
     * screens are otherwise unreachable until a real camera has been paired and listed, and both have
     * shipped layout bugs that only a screenshot could have caught (a zero-width row, text clipped on
     * the right).
     */
    private void maybePreview() {
        String which = null;
        try {
            which = getIntent() == null ? null : getIntent().getStringExtra("preview");
        } catch (RuntimeException ignored) {
            return;
        }
        if (which == null) {
            return;
        }
        Diag.i("预览模式：" + which);
        if ("queue".equals(which)) {
            previewQueue();
        } else {
            previewGrid();
        }
    }

    private void previewGrid() {
        browseScreen.setPreviewMode(true);
        mediaCache.clear();
        // 23 entries = five full rows, so the grid fills the panel and the floating bars really do sit
        // on top of artwork instead of over empty space.
        String[] names = {
                "DJI_20261011214912_0013_D.JPG", "DJI_20260923151209_0011_D.MP4",
                "DJI_20260923154803_0012_D.JPG", "DJI_20260922105207_0010_D.MP4",
                "DJI_20260922105143_0008_D.JPG", "DJI_20260922105136_0006_D.JPG",
                "DJI_20260911194652_0004_D.JPG", "DJI_20260910172128_0003_D.MP4",
                "DJI_20260907193723_0001_D.JPG", "DJI_20260906113723_0002_D.JPG",
                "DJI_20260905193723_0003_D.JPG", "DJI_20260904193723_0004_D.MP4",
                "DJI_20260903193723_0005_D.JPG", "DJI_20260902193723_0006_D.JPG",
                "DJI_20260901193723_0007_D.JPG", "DJI_20260831193723_0008_D.MP4",
                "DJI_20260830193723_0009_D.JPG", "DJI_20260829193723_0010_D.JPG",
                "DJI_20260828193723_0011_D.JPG", "DJI_20260827193723_0012_D.JPG",
                "DJI_20260826193723_0013_D.MP4", "DJI_20260825193723_0014_D.JPG",
                "DJI_20260824193723_0015_D.JPG",
        };
        for (int i = 0; i < names.length; i++) {
            boolean video = names[i].endsWith(".MP4");
            mediaCache.add(new MediaFile.Builder()
                    .path("DCIM/DJI_001/" + names[i])
                    .sizeBytes(video ? 48L * 1024 * 1024 + i * 1024L * 1024 : 4_100_000L + i * 137_000L)
                    .durationSec(video ? 12 + i * 7 : 0)
                    .build());
        }
        cameraStatus = new CameraStatus("Osmo Pocket 4P", 82,
                21L * 1024 * 1024 * 1024, 32L * 1024 * 1024 * 1024, true);
        browseScreen.setModel("Osmo Pocket 4P");
        browseScreen.setStatus(cameraStatus);
        browseScreen.setMedia(mediaCache);
        browseScreen.setNote("");
        switchTo(browseScreen);
        // A couple of ticks so the selected border, the tick badge and the total are all visible.
        browseScreen.clearSelection();
        browseScreen.setNote("");
        Diag.i("预览：相册 " + mediaCache.size() + " 项");
    }

    private void previewQueue() {
        transferItems.clear();
        String[] names = {"DJI_20260923151209_0011_D.MP4", "DJI_20261011214912_0013_D.JPG",
                "DJI_20260922105207_0010_D.MP4"};
        long[] totals = {48L * 1024 * 1024, 4_100_000L, 12L * 1024 * 1024};
        for (int i = 0; i < names.length; i++) {
            transferItems.add(new QueueScreen.TransferItem(names[i], "DCIM/DJI_001/" + names[i],
                    totals[i]));
        }
        queueScreen.setItems(transferItems);
        queueScreen.setOverall(1, transferItems.size());
        queueScreen.setCurrent(names[1], (long) (totals[1] * 0.42), totals[1]);
        queueScreen.onProgress(names[1], (long) (totals[1] * 0.42), totals[1]);
        queueScreen.onFinished(names[0]);
        switchTo(queueScreen);
        Diag.i("预览：传输页");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sessionPower(false);
        if (thumbs != null) {
            thumbs.shutdown();
            thumbs = null;
        }
        if (client != null) {
            try {
                client.disconnect();
            } catch (Throwable t) {
                Diag.w("断开连接失败: " + t);
            }
        }
    }

    /** Recursive size of one app-private directory, for the "why is it bigger than the APK" line. */
    private static long dirSize(java.io.File dir) {
        if (dir == null) {
            return 0L;
        }
        java.io.File[] children = dir.listFiles();
        if (children == null) {
            return dir.length();
        }
        long total = 0L;
        for (java.io.File child : children) {
            total += child.isDirectory() ? dirSize(child) : child.length();
        }
        return total;
    }

    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock cpuLock;

    /**
     * Hold the radio and the CPU open for as long as a camera session runs.
     *
     * <p>Wear OS turns Wi-Fi off to save power when the watch is off its charger, and it treats a
     * network with no internet as not worth keeping. Both are exactly wrong here: the camera's AP is
     * internet-less by definition and a transfer runs for minutes. Without these two locks the session
     * is dropped mid-listing with no error anywhere — which is the "connects, then disconnects" the
     * watch showed.
     */
    private void sessionPower(boolean on) {
        try {
            PowerManager pm = (PowerManager) getApplicationContext()
                    .getSystemService(Context.POWER_SERVICE);
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (on) {
                if (cpuLock == null && pm != null) {
                    cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "osmo:cpu");
                    cpuLock.setReferenceCounted(false);
                }
                if (cpuLock != null && !cpuLock.isHeld()) {
                    cpuLock.acquire(30L * 60L * 1000L);
                    Diag.i("已持有 CPU 锁");
                }
                if (wifiLock == null && wm != null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "osmo:wifi");
                    wifiLock.setReferenceCounted(false);
                }
                if (wifiLock != null && !wifiLock.isHeld()) {
                    wifiLock.acquire();
                    Diag.i("已持有 WiFi 锁（防止系统把相机热点断掉）");
                }
            } else {
                if (wifiLock != null && wifiLock.isHeld()) {
                    wifiLock.release();
                    Diag.i("已释放 WiFi 锁");
                }
                if (cpuLock != null && cpuLock.isHeld()) {
                    cpuLock.release();
                    Diag.i("已释放 CPU 锁");
                }
            }
        } catch (Throwable t) {
            Diag.w("电源锁失败: " + t);
        }
    }

    // ---- screens -----------------------------------------------------------------------------------

    private void buildScreens() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        deviceScreen = new DeviceScreen(this, new DeviceScreen.Listener() {
            @Override
            public void onScanToggled(boolean start) {
                scan(start);
            }

            @Override
            public void onCameraPicked(CameraInfo camera) {
                connect(camera);
            }

            @Override
            public void onOpenLog() {
                showLogScreen();
            }
        });

        browseScreen = new BrowseScreen(this, new BrowseScreen.Listener() {
            @Override
            public void onDownload(List<MediaFile> files) {
                if (files == null || files.isEmpty()) {
                    deviceScreen.setStatus("没有选中任何文件");
                    return;
                }
                transferItems.clear();
                for (MediaFile f : files) {
                    transferItems.add(QueueScreen.itemFor(f));
                }
                queueScreen.setItems(transferItems);
                queueScreen.setOverall(0, transferItems.size());
                switchTo(queueScreen);
                download(files);
            }

            @Override
            public void onSelectAll() {
                // BrowseScreen already applied the flip; nothing else to do here.
            }

            @Override
            public void onClearSelection() {
                // Same: the screen owns the tick set, the activity only hears about it.
            }

            @Override
            public void onOpenQueue() {
                queueScreen.setItems(transferItems);
                switchTo(queueScreen);
            }

            @Override
            public void onDisconnect() {
                Diag.i("用户长按标题，主动断开相机");
                disconnect();
            }
        }, thumbs);
        browseScreen.setSelectionListener(new BrowseScreen.SelectionListener() {
            @Override
            public void onSelectionChanged(List<MediaFile> selected) {
                Diag.i("已选中 " + selected.size() + " 项");
            }
        });

        queueScreen = new QueueScreen(this, new QueueScreen.Listener() {
            @Override
            public void onCancelAll() {
                cancelTransfers();
            }

            @Override
            public void onBack() {
                cancelTransfers();
                // Straight back to the gallery, NOT through a re-list: the manifest has not changed,
                // and re-reading it used to drop the user on the connect screen and re-handshake the
                // camera after every single file.
                switchTo(browseScreen);
            }
        });

        showDeviceScreen();
    }

    private void showDeviceScreen() {
        deviceScreen.setCameras(cameraCache);
        switchTo(deviceScreen);
    }

    private void switchTo(View screen) {
        currentScreen = screen;
        root.removeAllViews();
        if (screen != null) {
            root.addView(screen, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    // ---- Controller --------------------------------------------------------------------------------

    @Override
    public void scan(boolean start) {
        if (!start && client != null && client.isBusy()) {
            // The button reads 「取消连接」 while an attempt runs.
            client.cancelConnect();
            deviceScreen.setScanning(false);
            return;
        }
        deviceScreen.setScanning(start);
        if (client == null) {
            deviceScreen.setStatus("客户端还没准备好");
            return;
        }
        if (start) {
            cameraCache.clear();
            deviceScreen.setCameras(cameraCache);
            client.startScan();
        } else {
            client.stopScan();
        }
    }

    @Override
    public void connect(CameraInfo camera) {
        if (client == null) {
            deviceScreen.setStatus("客户端还没准备好");
            return;
        }
        if (client.isBusy()) {
            // Repeated taps used to open a second BLE session and a second Wi-Fi request for the same
            // SSID, and the two then cancelled each other. One tap = one connection, with its own
            // internal retries.
            deviceScreen.setStatus("正在连接中…（点「取消连接」可以停下）");
            return;
        }
        deviceScreen.setScanning(false);
        deviceScreen.setSubtitle(camera.modelName + " → 手表");
        deviceScreen.setStatus("正在连接 " + camera.modelName + " …");
        client.connect(camera);
    }

    @Override
    public void disconnect() {
        if (client != null) {
            client.disconnect();
        }
        cameraStatus = null;
        deviceScreen.setSubtitle("DJI 相机 → 手表");
        mediaCache.clear();
        browseScreen.setMedia(mediaCache);
        deviceScreen.setStatus("已断开");
        showDeviceScreen();
    }

    @Override
    public void refreshMedia() {
        if (client != null) {
            client.refreshMedia();
        }
    }

    @Override
    public void download(List<MediaFile> files) {
        if (client == null) {
            deviceScreen.setStatus("客户端还没准备好");
            return;
        }
        try {
            client.download(files);
        } catch (Throwable t) {
            Diag.e("开始下载失败", t);
            queueScreen.setDone(0, files == null ? 0 : files.size(), "开始下载失败");
        }
    }

    @Override
    public void cancelTransfers() {
        if (client != null) {
            client.cancelTransfers();
        }
    }

    @Override
    public List<CameraInfo> cameras() {
        return new ArrayList<CameraInfo>(cameraCache);
    }

    @Override
    public List<MediaFile> media() {
        return new ArrayList<MediaFile>(mediaCache);
    }

    @Override
    public String logTail(int lines) {
        return Diag.tail(lines);
    }

    // ---- OsmoClient callbacks ----------------------------------------------------------------------

    private final OsmoClient.Listener listener = new OsmoClient.Listener() {
        @Override
        public void onState(OsmoClient.State newState, String detail) {
            handleState(newState, detail);
        }

        @Override
        public void onCameras(List<CameraInfo> cameras) {
            cameraCache.clear();
            if (cameras != null) {
                cameraCache.addAll(cameras);
            }
            deviceScreen.setCameras(cameraCache);
            if (cameraCache.size() == 1) {
                deviceScreen.setSubtitle(cameraCache.get(0).modelName + " → 手表");
            } else {
                deviceScreen.setSubtitle("DJI 相机 → 手表");
            }
            deviceScreen.setStatus(cameraCache.isEmpty()
                    ? "还没找到相机，确认 DJI 相机已开机" : "找到 " + cameraCache.size() + " 台相机");
        }

        @Override
        public void onCameraList(List<MediaFile> files, CameraStatus status) {
            // Real data: drop the dev preview tint if this process ever showed the fake grid.
            browseScreen.setPreviewMode(false);
            mediaCache.clear();
            if (files != null) {
                mediaCache.addAll(files);
            }
            cameraStatus = status;
            if (status != null && status.modelName != null) {
                connectedModel = status.modelName;
            }
            browseScreen.setStatus(status);
            browseScreen.setModel(connectedModel);
            browseScreen.setMedia(mediaCache);
            Diag.i("媒体列表 " + mediaCache.size() + " 项");
        }

        @Override
        public void onTransfer(int doneCount, int totalCount, String currentName,
                               long bytesDone, long bytesTotal) {
            queueScreen.setOverall(doneCount, totalCount);
            queueScreen.setCurrent(currentName, bytesDone, bytesTotal);
            queueScreen.onProgress(currentName, bytesDone, bytesTotal);
        }

        @Override
        public void onTransferDone(int ok, int failed, String summary) {
            queueScreen.setDone(ok, failed, summary);
            for (QueueScreen.TransferItem item : transferItems) {
                queueScreen.onFinished(item.name());
            }
            // Stay in the gallery. Re-reading the manifest here is what sent the user back to the
            // connect screen after every download, and it is not even needed: nothing on the card
            // changed. The tick set is cleared so the next pick starts clean.
            browseScreen.setNote(failed > 0
                    ? "完成 " + ok + " · 失败 " + failed
                    : "已保存 " + ok + " 个到相册");
            browseScreen.clearSelection();
            switchTo(browseScreen);
            Diag.i("传输结束，已回到相册：" + summary);
        }

        @Override
        public void onError(String message) {
            Diag.e("客户端错误: " + message);
            deviceScreen.setStatus("出错：" + message);
            deviceScreen.onLog("E " + message);
            browseScreen.setStatus("出错：" + message);
        }

        @Override
        public void onLog(String line) {
            deviceScreen.onLog(line);
        }
    };

    private void handleState(OsmoClient.State newState, String detail) {
        if (newState == null) {
            return;
        }
        state = newState;
        boolean connecting = client != null && client.isBusy()
                && (newState == OsmoClient.State.PAIRING || newState == OsmoClient.State.WIFI
                || newState == OsmoClient.State.LINK || newState == OsmoClient.State.LISTING);
        deviceScreen.setBusy(connecting);
        if (newState == OsmoClient.State.WIFI || newState == OsmoClient.State.LINK
                || newState == OsmoClient.State.LISTING || newState == OsmoClient.State.READY
                || newState == OsmoClient.State.TRANSFERRING) {
            sessionPower(true);
        } else if (newState == OsmoClient.State.IDLE) {
            // Only IDLE gives the radio back. ERROR is frequently recoverable — a single failed
            // download lands there while the camera link is still perfectly alive — and releasing the
            // lock on it is what let Wear OS power the Wi-Fi down mid-session (the "kicked off" the
            // watch showed). The client itself hands the camera AP back when the session really ends.
            sessionPower(false);
        }
        switch (newState) {
            case IDLE:
                deviceScreen.setScanning(false);
                deviceScreen.setStatus(detail != null && !detail.isEmpty() ? detail : "点「扫描相机」开始");
                showDeviceScreen();
                break;
            case SCANNING:
                deviceScreen.setScanning(true);
                deviceScreen.setStatus(stage(detail, "正在扫描附近的相机"));
                switchTo(deviceScreen);
                break;
            case PAIRING:
                deviceScreen.setScanning(false);
                deviceScreen.setStatus(stage(detail, "正在配对"));
                switchTo(deviceScreen);
                break;
            case WIFI:
                deviceScreen.setStatus(stage(detail, "正在连相机 Wi-Fi"));
                switchTo(deviceScreen);
                break;
            case LINK:
                deviceScreen.setStatus(stage(detail, "正在建立链路"));
                switchTo(deviceScreen);
                break;
            case LISTING:
                deviceScreen.setStatus(stage(detail, "正在读取文件列表"));
                // Only show the connect screen if the user is not already looking at the gallery:
                // a background re-list must never yank the grid out from under a thumb.
                if (currentScreen != browseScreen) {
                    switchTo(deviceScreen);
                }
                break;
            case READY:
                browseScreen.setStatus(cameraStatus);
                switchTo(browseScreen);
                break;
            case TRANSFERRING:
                queueScreen.setOverall(0, transferItems.size());
                switchTo(queueScreen);
                break;
            case ERROR:
                deviceScreen.setScanning(false);
                deviceScreen.setStatus(stage(detail, "出错了"));
                showDeviceScreen();
                break;
            default:
                break;
        }
    }

    private static String stage(String detail, String fallback) {
        return detail != null && !detail.isEmpty() ? detail : fallback;
    }

    // ---- permissions -------------------------------------------------------------------------------

    private String[] requiredPermissions() {
        List<String> wanted = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            wanted.add(PERM_BT_SCAN);
            wanted.add(PERM_BT_CONNECT);
        }
        wanted.add(PERM_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) {
            wanted.add(PERM_POST_NOTIFICATIONS);
        }
        List<String> missing = new ArrayList<String>();
        for (String p : wanted) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        return missing.toArray(new String[0]);
    }

    private void requestRuntimePermissions() {
        try {
            String[] missing = requiredPermissions();
            if (missing.length == 0) {
                Diag.i("运行时权限已齐备");
                return;
            }
            Diag.i("请求权限: " + java.util.Arrays.toString(missing));
            requestPermissions(missing, REQ_PERMISSIONS);
        } catch (Throwable t) {
            Diag.e("请求权限失败", t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMISSIONS) {
            return;
        }
        StringBuilder denied = new StringBuilder();
        for (int i = 0; i < permissions.length && i < results.length; i++) {
            if (results[i] != PackageManager.PERMISSION_GRANTED) {
                if (denied.length() > 0) {
                    denied.append('\n');
                }
                denied.append(permissions[i]);
            }
        }
        if (denied.length() > 0) {
            Diag.w("权限被拒: " + denied);
            deviceScreen.setStatus("权限未授予，扫描会失败：" + denied);
        } else {
            Diag.i("权限全部授予");
        }
    }

    // ---- diagnostics screen ------------------------------------------------------------------------

    /**
     * Full-screen log page.
     *
     * <p>A {@link RoundColumn} would size its children to the circle but would also stack them from
     * the middle of the content, and a log viewer needs its scroll area pinned. So this page uses two
     * explicit bands with the circle's own width limits instead: text at the top, buttons below it.
     */
    private void showLogScreen() {
        if (logScreen == null) {
            logScreen = buildLogScreen();
        }
        resetCleanupButton();
        refreshLog();
        switchTo(logScreen);
    }

    private FrameLayout buildLogScreen() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Ui.BG);

        logBody = Ui.text(this, "", 9f, Ui.TEXT_DIM);
        logBody.setTypeface(Typeface.MONOSPACE);
        logBody.setGravity(Gravity.LEFT | Gravity.TOP);
        logBody.setSingleLine(false);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 14)));
        int pad = Ui.dp(this, 8);
        logBody.setPadding(pad, pad, pad, pad);
        scroll.addView(logBody, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = Ui.row(this);
        buttons.setGravity(Gravity.CENTER);

        TextView export = Ui.pill(this, "导出", Ui.CARD, Ui.TEXT, 11f,
                Ui.dp(this, Ui.COMPACT_DP), 4f, 12f);
        Ui.setPressable(export);
        export.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportLog();
            }
        });

        cleanupButton = Ui.pill(this, "清理", Ui.CARD, Ui.TEXT, 11f,
                Ui.dp(this, Ui.COMPACT_DP), 4f, 12f);
        Ui.setPressable(cleanupButton);
        cleanupButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onCleanupTapped();
            }
        });

        TextView back = Ui.pill(this, "返回", Ui.CARD, Ui.TEXT, 11f,
                Ui.dp(this, Ui.COMPACT_DP), 4f, 12f);
        Ui.setPressable(back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetCleanupButton();
                showDeviceScreen();
            }
        });

        // Three compact buttons in one row. 96px each is enough for two Han characters and keeps the
        // row inside the same y=[372,444] band the browser's footer uses.
        int compact = Ui.dp(this, Ui.COMPACT_DP);
        int gap = Ui.dp(this, 6);
        buttons.addView(export, new LinearLayout.LayoutParams(0, compact, 1f));
        buttons.addView(new View(this), new LinearLayout.LayoutParams(gap, compact));
        buttons.addView(cleanupButton, new LinearLayout.LayoutParams(0, compact, 1f));
        buttons.addView(new View(this), new LinearLayout.LayoutParams(gap, compact));
        buttons.addView(back, new LinearLayout.LayoutParams(0, compact, 1f));

        // Top band: y=[20,348]. At y=20 the circle is only 160px wide, and 308px fits there with the
        // corners inside; a full-width log box would be cut at both top corners.
        //
        // NOTE: ROUND_COLUMN_LIMIT / LOG_TOP_HEIGHT / LOG_BUTTONS_WIDTH are already PIXELS (the whole
        // band table in Ui is). Wrapping them in Ui.dp() multiplied them by the 2.125 density again -
        // the log box came out 654px wide and 739px tall on a 480px panel, so the page was centred
        // off-screen and every log line was clipped on the left.
        FrameLayout top = new FrameLayout(this);
        top.addView(scroll, new FrameLayout.LayoutParams(Ui.ROUND_COLUMN_LIMIT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
        page.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                LOG_TOP_HEIGHT, Gravity.TOP));

        // Bottom band: the two buttons used to stack in a 256px column running to y=480, where the
        // chord is zero — their corners were being shaved. One 72px row, lifted 36px off the bottom
        // edge, puts it in the same y=[372,444] band the browser's footer uses.
        FrameLayout bottom = new FrameLayout(this);
        bottom.addView(buttons, new FrameLayout.LayoutParams(LOG_BUTTONS_WIDTH,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        bottomParams.bottomMargin = Ui.dp(this, 17);
        page.addView(bottom, bottomParams);

        return page;
    }

    /** First tap arms the wipe and says what it will delete; the second tap actually does it. */
    private void onCleanupTapped() {
        if (state == OsmoClient.State.TRANSFERRING) {
            // Deleting the .part file under a running download would destroy the transfer it is
            // describing; the queue page owns that decision.
            Diag.w("传输进行中，先取消下载再清理");
            return;
        }
        if (!cleanupArmed) {
            cleanupArmed = true;
            cleanupButton.setText("确认");
            cleanupButton.setBackground(Ui.rounded(Ui.WARN, Ui.dp(this, 12)));
            Diag.i("清理：再点一次「确认清理」将清空日志与未完成的下载缓存");
            refreshLog();
            return;
        }
        resetCleanupButton();
        long cache = 0L;
        try {
            cache = com.watch.osmo.store.FileSaver.clearCache(this);
        } catch (Throwable t) {
            Diag.w("清缓存失败: " + t);
        }
        long logs = Diag.clearAll();
        Diag.i("已清理：缓存 " + (cache / 1024) + " KB，日志 " + (logs / 1024) + " KB");
        refreshLog();
    }

    private void resetCleanupButton() {
        cleanupArmed = false;
        if (cleanupButton == null) {
            return;
        }
        cleanupButton.setText("清理");
        cleanupButton.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 12)));
    }

    private void refreshLog() {
        if (logBody != null) {
            logBody.setText(Diag.tail(30));
        }
    }

    /** Copies the diagnostic file into the app's external directory so it can be pulled off the watch. */
    private void exportLog() {
        final File source = Diag.file();
        if (source == null || !source.exists()) {
            logBody.setText("还没有日志文件");
            return;
        }
        logBody.setText("正在导出 " + source.getName() + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    File dir = getExternalFilesDir(null);
                    if (dir == null) {
                        dir = getFilesDir();
                    }
                    if (!dir.exists() && !dir.mkdirs()) {
                        message = "导出失败：无法创建目录";
                    } else {
                        File target = new File(dir, source.getName());
                        copy(source, target);
                        message = "已导出 " + target.getAbsolutePath()
                                + " (" + target.length() + "B)";
                    }
                } catch (Throwable t) {
                    Diag.e("导出日志失败", t);
                    message = "导出失败：" + t;
                }
                // A final copy, so the callback below can capture it.
                final String result = message;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        Diag.i(result);
                        refreshLog();
                        logBody.append("\n" + result);
                    }
                });
            }
        }, "osmo-log-export").start();
    }

    private static void copy(File from, File to) throws IOException {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to, false);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // A failed close on a log copy is not worth reporting.
            }
        }
    }

    /** Seeds the three-line log tail with whatever the diagnostics ring already holds. */
    private void log(String message) {
        Diag.i(message);
        String tail = Diag.tail(3);
        if (tail == null || tail.isEmpty()) {
            return;
        }
        // The device screen keeps its own rolling three lines, so feed it only the newest.
        String[] lines = tail.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isEmpty()) {
                deviceScreen.onLog(lines[i]);
            }
        }
    }
}

