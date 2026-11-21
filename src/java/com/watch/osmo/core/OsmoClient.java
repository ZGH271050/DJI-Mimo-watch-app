package com.watch.osmo.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.watch.osmo.ble.BleScanner;
import com.watch.osmo.ble.BleSession;
import com.watch.osmo.duml.DumlFrame;
import com.watch.osmo.duml.OsmoCommands;
import com.watch.osmo.net.ApJoiner;
import com.watch.osmo.net.Datalink;
import com.watch.osmo.net.HttpFile;
import com.watch.osmo.net.ManifestAssembler;
import com.watch.osmo.proto.ManifestParser;
import com.watch.osmo.proto.MediaFile;
import com.watch.osmo.store.FileSaver;
import com.watch.osmo.util.Diag;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The whole pipeline, in the order the camera demands it:
 *
 * <pre>
 *   BLE scan -> GATT pair -> Wi-Fi credentials over BLE -> wake the camera AP
 *            -> join that AP and bind the process to it -> UDP datalink handshake
 *            -> devinfo / register / subscribe -> media manifest -> HTTP download
 * </pre>
 *
 * <p>Every callback handed to {@link Listener} is posted to the main thread, so the UI can touch
 * views directly without further plumbing.
 *
 * <p>The AP is internet-less and drops when idle, so the client owns the keepalive and the rejoin
 * policy: a session that loses its AP is retried once before the transfer is failed.
 */
public class OsmoClient implements BleScanner.Listener, BleSession.Listener,
        ApJoiner.Listener, Datalink.Listener {

    public enum State {
        IDLE, SCANNING, PAIRING, WIFI, LINK, LISTING, READY, TRANSFERRING, ERROR
    }

    public interface Listener {
        void onState(State state, String detail);
        void onCameras(List<CameraInfo> cameras);
        void onCameraList(List<MediaFile> files, CameraStatus status);
        void onTransfer(int doneCount, int totalCount, String currentName,
                        long bytesDone, long bytesTotal);
        void onTransferDone(int ok, int failed, String summary);
        void onError(String message);
        void onLog(String line);
    }

    /**
     * Pairing identity. A camera remembers its approval against this exact string, so we reuse the
     * constant the reference implementation sends: a camera that already approved it takes the
     * "already paired" fast path instead of asking the user to confirm again.
     */
    private static final String PAIR_IDENTIFIER = OsmoCommands.PairMessagePayload.DEFAULT_IDENTIFIER;
    /** Sent as the pairing token; the camera prints it uppercased on its screen ("OSMO"). */
    private static final String PAIR_TOKEN = "osmo";
    /** The camera's own gateway once its AP is up. */
    private static final String CAMERA_HOST = "192.168.2.1";
    /** Manifest cursor meaning "start from the newest file on the SD card". */
    private static final int LIST_FROM_NEWEST_SD = 1;
    private static final int HANDSHAKE_TIMEOUT_MS = 4000;
    /** Keep this much room free so a transfer never fills the watch completely. */
    private static final long RESERVE_BYTES = 200L * 1024 * 1024;
    private static final int PARALLEL_DOWNLOADS = 2;
    /**
     * Just enough time for the {@code 0x07/47} BLE write to reach the camera before we start asking
     * the system for its AP.
     *
     * <p>This used to be <b>16 seconds</b>, on the theory that the AP needs that long to come up. It
     * does not, and the wait was pure loss: {@link android.net.wifi.WifiNetworkSpecifier} keeps
     * scanning until a matching AP appears, so the join request can be issued immediately and simply
     * completes when the AP shows up. The measured session on the watch went from 31s to ~17s on this
     * one constant. The reference implementation has no delay here at all.
     */
    private static final long AP_WAKE_DELAY_MS = 700L;
    /** How many times a lost camera AP is re-joined before the session is given up on. */
    private static final int AP_REJOIN_LIMIT = 3;
    /**
     * One user tap is allowed this many whole attempts (1 initial + retries). After the camera's
     * 2026-11 firmware update a single attempt only succeeds when the camera happens not to drop its
     * BLE link inside the Wi-Fi association window, so retrying by hand ("几十次才连上一次") is what
     * the watch was being used for. The client now retries itself, with a delay long enough for the
     * camera to let go of the session the failed attempt left behind.
     */
    private static final int MAX_ATTEMPTS = 6;
    /** Gap before retry n is {@code n × this}: 1.6 s, 3.2 s, … — the camera needs time to reset. */
    private static final long RETRY_DELAY_MS = 1600L;
    /** One attempt that makes no progress for this long is abandoned and retried. */
    private static final long ATTEMPT_WATCHDOG_MS = 75000L;
    /** While the camera waits for a tap on its own screen, the watchdog gets longer. */
    private static final long APPROVAL_WATCHDOG_MS = 120000L;
    /** Datalink sessions tried inside one attempt before the attempt itself is declared failed. */
    private static final int DATALINK_ROUNDS = 3;
    private static final long DATALINK_RETRY_DELAY_MS = 1500L;

    /** Pipeline stages, used to decide whether a component's failure is still worth acting on. */
    private static final int STAGE_BLE = 0;
    private static final int STAGE_WIFI = 1;
    private static final int STAGE_LINK = 2;
    private static final int STAGE_READY = 3;

    private final Context appContext;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(PARALLEL_DOWNLOADS);

    private final List<CameraInfo> cameras = Collections.synchronizedList(new ArrayList<CameraInfo>());
    private volatile List<MediaFile> media = new ArrayList<MediaFile>();
    private volatile CameraStatus status = CameraStatus.unknown();
    private volatile State state = State.IDLE;

    private BleScanner scanner;
    private BleSession ble;
    private ApJoiner wifi;
    private Datalink link;
    private volatile boolean cancelled;
    private int apLosses;

    // ---- one-attempt-at-a-time ---------------------------------------------------------------------
    /**
     * True from the tap until the list is up, the user cancels, or every retry is spent. While it is
     * true, {@link #connect} refuses to start a second session: the watch used to open four BLE links
     * and four {@code WifiNetworkSpecifier} requests for one SSID within a second (each tap made a new
     * {@code OsmoClient} session without closing the previous one), and those requests cancel each
     * other. That pile-up, not the camera, was most of the "dozens of tries" behaviour.
     */
    private volatile boolean busy;
    /** 1 for the first attempt, then 2..{@link #MAX_ATTEMPTS}. */
    private int attempt;
    private int stage = STAGE_BLE;
    private CameraInfo target;
    /** Model of the camera being connected, used for the gallery header (not a constant). */
    private volatile String connectedModelName;
    private Runnable attemptWatchdog;
    /** Guards against two {@link #onNetwork} callbacks opening two datalinks for one join. */
    private volatile boolean openingLink;
    /**
     * Bumped by {@link #cleanup()}. A manifest parsed on the worker thread must not deliver a READY
     * that belongs to an attempt which has already been abandoned (watchdog timeouts can fire while
     * the collect loop is still finishing).
     */
    private volatile int linkGeneration;

    private String pendingSsid;
    private String pendingPassword;
    private int storage = 1;
    private boolean storageKnown;
    /**
     * When {@link #connect} was called. Every state change logs the offset, so "how long does a
     * connection take" is answerable from the log without a stopwatch: {@code [READY] 共 9 个文件 +16.9s}.
     */
    private volatile long sessionStartMs;

    /** Guards {@link #assembler}: frames arrive on the datalink thread, resets come from the UI. */
    private final Object manifestLock = new Object();
    private ManifestAssembler assembler = new ManifestAssembler();
    private volatile boolean manifestComplete;

    public OsmoClient(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    // ------------------------------------------------------------------ public API

    public State state() {
        return state;
    }

    public boolean isConnected() {
        return link != null && state != State.IDLE && state != State.ERROR;
    }

    public List<MediaFile> media() {
        return media;
    }

    public CameraStatus status() {
        return status;
    }

    public List<CameraInfo> cameras() {
        return new ArrayList<CameraInfo>(cameras);
    }

    public void startScan() {
        try {
            if (scanner == null) {
                scanner = new BleScanner(appContext, this);
            }
            cameras.clear();
            postCameras();
            setState(State.SCANNING, "正在扫描相机…");
            scanner.start();
        } catch (SecurityException e) {
            fail("缺少蓝牙权限：" + e.getMessage());
        } catch (RuntimeException e) {
            fail("蓝牙无法启动：" + e);
        }
    }

    public void stopScan() {
        if (scanner != null) {
            try {
                scanner.stop();
            } catch (RuntimeException e) {
                Diag.w("stopScan: " + e);
            }
        }
        if (state == State.SCANNING) {
            setState(State.IDLE, "已停止扫描");
        }
    }

    public void connect(CameraInfo camera) {
        if (camera == null) {
            return;
        }
        if (busy) {
            Diag.i("已经在连接中，忽略这次点击（不会再开第二个 BLE 会话 / WiFi 请求）");
            return;
        }
        stopScan();
        cancelled = false;
        target = camera;
        connectedModelName = camera.modelName != null && !camera.modelName.isEmpty()
                ? camera.modelName : camera.name;
        attempt = 0;
        sessionStartMs = System.currentTimeMillis();
        busy = true;
        connectAttempt();
    }

    /**
     * One whole attempt. Everything the previous attempt built is torn down first — most importantly
     * its {@code WifiNetworkSpecifier} request, because two live requests for the same SSID make the
     * framework cancel both ("WiFi 掉线了" ×4 in the 2026-11 logs).
     */
    private void connectAttempt() {
        attempt++;
        cleanup();
        stage = STAGE_BLE;
        openingLink = false;
        apLosses = 0;
        if (attempt == 1) {
            setState(State.PAIRING, "正在连接 " + target.modelName + "…");
        } else {
            setState(State.PAIRING, "第 " + attempt + "/" + MAX_ATTEMPTS + " 次尝试：正在连接 "
                    + target.modelName + "…");
        }
        try {
            ble = new BleSession(appContext, this);
            ble.connect(target.address, PAIR_IDENTIFIER);
        } catch (SecurityException e) {
            attemptFailed("缺少蓝牙权限：" + e.getMessage());
            return;
        } catch (RuntimeException e) {
            attemptFailed("蓝牙连接失败：" + e);
            return;
        }
        armWatchdog(ATTEMPT_WATCHDOG_MS);
    }

    /** True while an attempt (including the gap before an automatic retry) is running. */
    public boolean isBusy() {
        return busy;
    }

    /**
     * Stop trying. The current attempt is torn down and the Wi-Fi network handed back; no retry is
     * scheduled. Idempotent.
     */
    public void cancelConnect() {
        if (!busy) {
            return;
        }
        cancelled = true;
        busy = false;
        cleanup();
        setState(State.IDLE, "已取消连接");
    }

    public void disconnect() {
        cancelled = true;
        busy = false;
        cleanup();
        storageKnown = false;
        setState(State.IDLE, "已断开");
    }

    public void refreshMedia() {
        final Datalink d = link;
        if (d == null) {
            fail("还没连上相机");
            return;
        }
        setState(State.LISTING, "正在重新读取相机文件列表…");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // The collect loop owns the socket while it runs; the keep-alive would otherwise
                    // interleave with it and reorder the manifest chunks. fetchManifest restarts it.
                    d.stopKeepalive();
                    fetchManifest(d);
                } catch (RuntimeException e) {
                    fail("请求文件列表失败：" + e);
                }
            }
        });
    }

    public void cancelTransfers() {
        cancelled = true;
        setState(State.READY, "已取消传输");
    }

    // ------------------------------------------------------------------ download

    public void download(final List<MediaFile> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        if (link == null) {
            fail("还没连上相机");
            return;
        }
        long needed = 0;
        for (MediaFile f : files) {
            needed += Math.max(0, f.getSizeBytes());
        }
        long free = FileSaver.freeBytes(appContext);
        if (free > 0 && needed + RESERVE_BYTES > free) {
            fail("手表空间不足：需要 " + (needed / 1048576) + " MB，可用 " + (free / 1048576) + " MB");
            return;
        }

        cancelled = false;
        setState(State.TRANSFERRING, "开始传输 " + files.size() + " 个文件");
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger finished = new AtomicInteger();
        final long totalBytes = needed;
        final long[] doneBytes = new long[]{0};
        final int fileCount = files.size();

        for (final MediaFile file : files) {
            final int index = started.incrementAndGet();
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    String saved = transferOne(file, index, fileCount, totalBytes, doneBytes);
                    if (saved != null) {
                        ok.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                    int done = finished.incrementAndGet();
                    if (done == fileCount) {
                        final int o = ok.get();
                        final int f = failed.get();
                        final String summary = "完成 " + o + " 个"
                                + (f > 0 ? "，失败 " + f + " 个" : "");
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                setState(f > 0 ? State.ERROR : State.READY, summary);
                                listener.onTransferDone(o, f, summary);
                            }
                        });
                    }
                }
            });
        }
    }

    private String transferOne(MediaFile file, int index, int total, long totalBytes,
                               long[] doneBytes) {
        if (cancelled) {
            return null;
        }
        String name = safeName(file);
        File part = new File(appContext.getCacheDir(), name + ".part");
        final String url = HttpFile.buildV2Url(CAMERA_HOST, storage, file.getPath());
        final long announced = file.getSizeBytes();
        // Captured as finals so the progress listeners below can name the file they belong to.
        final int fIndex = index;
        final int fTotal = total;
        final String fName = name;

        long existing = part.exists() ? part.length() : 0;
        if (announced > 0 && existing >= announced) {
            existing = 0;
            part.delete();
        }
        try {
            reportProgress(fIndex, fTotal, fName, existing, announced);
            boolean complete;
            if (existing > 0) {
                Diag.i("续传 " + name + " 从 " + existing + " 字节");
                OutputStream out = new FileOutputStream(part, true);
                try {
                    final long base = existing;
                    final long[] last = new long[]{base};
                    complete = HttpFile.downloadRange(url, existing, -1, out,
                            new HttpFile.ProgressListener() {
                                @Override
                                public void onProgress(long done, long total) {
                                    long absolute = base + done;
                                    if (absolute - last[0] > 256 * 1024) {
                                        last[0] = absolute;
                                        reportProgress(fIndex, fTotal, fName, absolute, announced);
                                    }
                                }
                            });
                } finally {
                    out.close();
                }
            } else {
                OutputStream out = new FileOutputStream(part, false);
                try {
                    final long[] last = new long[]{0};
                    complete = HttpFile.downloadRange(url, 0, -1, out,
                            new HttpFile.ProgressListener() {
                                @Override
                                public void onProgress(long done, long total) {
                                    if (done - last[0] > 256 * 1024) {
                                        last[0] = done;
                                        reportProgress(fIndex, fTotal, fName, done, announced);
                                    }
                                }
                            });
                } finally {
                    out.close();
                }
            }
            if (!complete || (announced > 0 && part.length() < announced)) {
                Diag.w("下载不完整：" + name + " 本地 " + part.length() + " / 期望 " + announced);
                return null;
            }
            doneBytes[0] += part.length();
            String where = FileSaver.publish(appContext, part, name, file.isVideo());
            part.delete();
            if (where == null) {
                Diag.e("文件已下载但保存失败：" + name);
                return null;
            }
            Diag.i("已保存 " + name + " -> " + where);
            reportProgress(fIndex, fTotal, fName, announced, announced);
            return where;
        } catch (Exception e) {
            Diag.e("下载失败 " + name, e);
            return null;
        }
    }

    private void reportProgress(int index, int total, String name, long done, long totalBytes) {
        final int i = index;
        final int t = total;
        final String n = name;
        final long d = done;
        final long tb = totalBytes;
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onTransfer(i, t, n, d, tb);
            }
        });
    }

    private static String safeName(MediaFile f) {
        String n = f.getName();
        if (n == null || n.length() == 0) {
            String p = f.getPath();
            int slash = p == null ? -1 : p.lastIndexOf('/');
            n = slash >= 0 ? p.substring(slash + 1) : String.valueOf(System.currentTimeMillis());
        }
        return n.replace('/', '_').replace('\\', '_');
    }

    // ------------------------------------------------------------------ BLE scanner

    @Override
    public void onCamera(CameraInfo camera) {
        for (CameraInfo existing : cameras) {
            if (existing.equals(camera)) {
                return;
            }
        }
        cameras.add(camera);
        postCameras();
    }

    @Override
    public void onScanFailed(String reason) {
        fail("扫描失败：" + reason);
    }

    private void postCameras() {
        final List<CameraInfo> snapshot = new ArrayList<CameraInfo>(cameras);
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onCameras(snapshot);
            }
        });
    }

    // ------------------------------------------------------------------ BLE session

    @Override
    public void onReady() {
        if (!busy) {
            return;
        }
        setState(State.PAIRING, "蓝牙已连接，正在配对…");
        if (ble != null) {
            ble.pair(PAIR_TOKEN);
        }
    }

    @Override
    public void onPairingApprovalNeeded() {
        if (!busy) {
            return;
        }
        setState(State.PAIRING, "请在相机屏幕上点确认（显示 OSMO）");
        // A human has to walk over to the camera; do not let the attempt watchdog kill that wait.
        armWatchdog(APPROVAL_WATCHDOG_MS);
    }

    @Override
    public void onPaired() {
        if (!busy) {
            return;
        }
        setState(State.PAIRING, "配对成功，正在取热点信息…");
        if (ble != null) {
            ble.requestWifiCredentials();
        }
    }

    @Override
    public void onWifiCredentials(String ssid, String password) {
        if (!busy) {
            return;
        }
        pendingSsid = ssid;
        pendingPassword = password;
        stage = STAGE_WIFI;
        setState(State.WIFI, "正在加入热点 " + ssid + "…");
        if (ble != null) {
            // From here the BLE link is expendable: the camera tears it down ~5 s after 07/47 to
            // switch its radio to the AP, and that must not abort the Wi-Fi join in progress.
            ble.markHandedOff();
            ble.wakeAccessPoint(ssid, password);
        }
        armWatchdog(ATTEMPT_WATCHDOG_MS);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (busy && !cancelled && stage == STAGE_WIFI) {
                    joinWifi();
                }
            }
        }, AP_WAKE_DELAY_MS);
    }

    @Override
    public void onWifiMac(String mac) {
        Diag.i("相机 WiFi MAC: " + mac);
    }

    @Override
    public void onApAwake() {
        Diag.i("相机热点已唤醒");
    }

    /**
     * Shared failure entry point. The BLE session, the Wi-Fi joiner and the datalink all declare
     * this same callback, so one implementation reports a failure from any stage of the pipeline.
     */
    @Override
    public void onFailure(String reason) {
        if (!busy) {
            Diag.w("连接已结束，忽略组件失败回调：" + reason);
            return;
        }
        // The datalink retries internally and reports through connectDataSession()'s return value.
        // Handling the same failure twice left `link` null underneath the worker thread — that is the
        // NullPointerException on Datalink.pump()/playbackKnown() in the 2026-11 logs.
        if (stage == STAGE_LINK || stage == STAGE_READY) {
            Diag.w("链路层报告：" + reason + "（由链路重试流程处理）");
            return;
        }
        attemptFailed(reason);
    }

    private void joinWifi() {
        if (pendingSsid == null) {
            attemptFailed("没有拿到相机热点信息");
            return;
        }
        wifi = new ApJoiner(appContext, this);
        wifi.join(pendingSsid, pendingPassword, false);
    }

    // ------------------------------------------------------------------ Wi-Fi

    @Override
    public void onNetwork(android.net.Network network, android.net.LinkProperties linkProperties) {
        if (!busy || cancelled) {
            return;
        }
        if (openingLink && stage >= STAGE_LINK) {
            Diag.i("重复的网络可用回调，忽略（数据链已经在建）");
            return;
        }
        apLosses = 0;
        stage = STAGE_LINK;
        openingLink = true;
        if (linkProperties != null) {
            Diag.i("相机网络：" + linkProperties);
        }
        setState(State.LINK, "已加入相机热点，正在建立数据链路…");
        armWatchdog(ATTEMPT_WATCHDOG_MS);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                openDatalink();
            }
        });
    }

    @Override
    public void onApLost() {
        if (!busy || cancelled) {
            return;
        }
        if (state == State.TRANSFERRING) {
            Diag.w("传输中相机热点掉线");
        }
        if (wifi == null) {
            return;
        }
        if (apLosses < AP_REJOIN_LIMIT) {
            apLosses++;
            stage = STAGE_WIFI;
            openingLink = false;
            Datalink old = link;
            link = null;
            if (old != null) {
                try {
                    old.close();
                } catch (RuntimeException e) {
                    Diag.w("datalink close: " + e);
                }
            }
            setState(State.WIFI, "热点掉线，正在重连（第 " + apLosses + " 次）…");
            wifi.rejoin();
            return;
        }
        attemptFailed("相机热点断开（已重连 " + AP_REJOIN_LIMIT + " 次）");
    }

    // ------------------------------------------------------------------ datalink

    /**
     * Bring the data link up, retrying with a brand-new session if the camera answers nothing.
     *
     * <p>Two distinct hardware failures motivate the retries. (1) The handshake succeeds, but the peer
     * answers on <b>its own</b> sequence channel instead of the one we proposed — "it may be holding a
     * session from a previous connection" in the log — and from then on it answers no command at all:
     * the playback switch never takes, and the media list comes back with zero frames while telemetry
     * keeps flowing (watch, 2026-10-18: 15 windows of "收 81 包", "帧 0"). A fresh session id plus
     * 1.5 s clears it. (2) After the 2026-11 firmware the camera's UDP stack is sometimes not listening
     * yet when the AP association completes; the same retry covers that, so it is no longer fatal to
     * the attempt.
     *
     * <p>Only the datalink is re-opened, never the Wi-Fi: the AP is up and bound, and re-joining it
     * costs several seconds for no reason.
     */
    private void openDatalink() {
        String error = null;
        for (int round = 1; round <= DATALINK_ROUNDS && busy && !cancelled; round++) {
            error = connectDataSession();
            if (error == null) {
                return;
            }
            Diag.w("数据链路第 " + round + "/" + DATALINK_ROUNDS + " 次没成（" + error + "）"
                    + (round < DATALINK_ROUNDS ? "，" + DATALINK_RETRY_DELAY_MS + " ms 后重开" : ""));
            Datalink old = link;
            link = null;
            if (old != null) {
                try {
                    old.close();
                } catch (RuntimeException e) {
                    Diag.w("datalink close: " + e);
                }
            }
            if (round < DATALINK_ROUNDS) {
                sleep(DATALINK_RETRY_DELAY_MS);
            }
        }
        if (!cancelled) {
            attemptFailed(error == null ? "数据链路没有建立" : error);
        }
    }

    /**
     * One whole datalink session. Returns null on success, or the reason it failed.
     *
     * <p>Everything goes through the local {@code d}, never the {@code link} field: a failure callback
     * from another thread can null the field at any moment, and the old code dereferenced it anyway
     * (the {@code NullPointerException ... Datalink.pump()} in the 2026-11 logs).
     */
    private String connectDataSession() {
        final Datalink d = new Datalink(this);
        link = d;
        try {
            if (!d.handshake(CAMERA_HOST, HANDSHAKE_TIMEOUT_MS)) {
                return "连不上相机的数据链路（9004/10004 都没回应）";
            }
            if (link != d) {
                return "会话已被取消";
            }
            Diag.i("数据链路已建立，端口 " + d.port() + elapsed());

            // Registration, in the reference's exact order and with its drain after each step. The
            // camera gates later commands on this having happened; skipping the gimbal init in
            // particular leaves a Pocket answering list queries with nothing.
            setState(State.LINK, "数据链路已建立，正在注册…");
            d.requestDevInfo();  // 0x00/0x81 device info
            d.register();        // 0x00/0x88 presence/registration
            d.initGimbal();      // 0x03/0xDA gimbal init
            d.subscribe();       // 8 x 0x00/0x99 status subscriptions

            // Playback is a camera-wide mode and it decides whether the list arrives whole. A Pocket
            // still in capture declares the right count and then serves a couple of records — the
            // watch's "got one file, then got dropped" is that. Enter it before asking for anything.
            boolean playback = enterPlayback(d);
            if (!playback) {
                Diag.w("相机没有确认进入播放模式 —— 文件列表可能只回来一部分");
            }
            return fetchManifest(d);
        } catch (Exception e) {
            return "数据链路出错：" + e;
        }
    }

    /**
     * Put the camera into playback and wait for its own status bit to say so.
     *
     * <p>Ask before telling: playback outlives a session, so a re-connect usually finds it already
     * held, and that costs one 120 ms drain instead of three pointless commands. The {@code 0x02/0x0C}
     * ack only means "received" — a Pocket 3 answers status 0 and stays in capture — so the decision
     * is made on the camera's {@code 0x02/0x80} flags bit, never on the reply.
     */
    private boolean enterPlayback(Datalink d) {
        d.pump(120, 100);
        if (d.playbackKnown() && d.playbackReported()) {
            Diag.i("相机本来就处于播放模式");
            return true;
        }
        for (int round = 0; round < 3 && !cancelled; round++) {
            d.enterPlayback();
            long deadline = System.currentTimeMillis() + 900;
            int got = 0;
            while (System.currentTimeMillis() < deadline) {
                got += d.pump(100, 100);
                if (d.playbackKnown() && d.playbackReported()) {
                    Diag.i("播放模式已确认（第 " + (round + 1) + " 次尝试）");
                    return true;
                }
            }
            Diag.i("0x02/0x0C 第 " + (round + 1) + " 次没有生效（900 ms 内收到 " + got + " 包）");
        }
        // The Pocket 3 route: 0x01/0x01 control frames, no reply, status bit only.
        Diag.i("改用 0x01/0x01 再试播放模式");
        long deadline = System.currentTimeMillis() + 2500;
        int sent = 0;
        while (System.currentTimeMillis() < deadline && !cancelled) {
            d.enterPlaybackViaControl(sent < 6);
            sent++;
            d.pump(50, 100);
            if (d.playbackKnown() && d.playbackReported()) {
                Diag.i("播放模式已确认（0x01/0x01，共 " + sent + " 帧）");
                return true;
            }
        }
        return d.playbackKnown() && d.playbackReported();
    }

    /**
     * Collect the media manifest: three queries interleaved with drains, exactly the sequence the
     * reference uses for the newest page.
     *
     * <ol>
     *   <li>the SD page query, counter 1, cursor {@code 0x00000001};</li>
     *   <li>after one drain window, the {@code 4a040e10…} trigger that makes the camera stream the
     *       rest of the page — <b>without it the answer stops after the first chunk</b>;</li>
     *   <li>after the next, the built-in store query, counter 2, cursor {@code 0x40000001}.</li>
     * </ol>
     *
     * <p>The loop ends when the camera has closed both streams, or when the record count has stopped
     * moving for two windows. Runs on the worker thread and owns the socket — the keep-alive is only
     * started afterwards, because two threads receiving from one {@link java.net.DatagramSocket}
     * reorders the manifest chunks.
     *
     * @return null once the list has been handed to {@link #completeManifest}, or the reason it never
     *         arrived — the caller decides whether that is worth a second, fresh session.
     */
    private String fetchManifest(Datalink d) {
        setState(State.LISTING, "正在读取相机文件列表…");
        assemblerReset();
        d.requestMediaListPage(Datalink.SD_QUERY_CTR, Datalink.NEWEST_SD);
        int lastCount = -1;
        int stable = 0;
        for (int batch = 0; batch < 15 && !cancelled; batch++) {
            int got = d.pump(800, 200);
            d.sendPresence();
            if (batch == 1) {
                d.sendMediaListTrigger();
            }
            if (batch == 2) {
                d.requestMediaListPage(Datalink.INTERNAL_QUERY_CTR, Datalink.NEWEST_INTERNAL);
            }
            int count;
            boolean ended;
            int frameCount;
            int bytes;
            synchronized (manifestLock) {
                count = assembler.distinctPathCount();
                ended = assembler.streamsEnded();
                frameCount = assembler.frameCount();
                bytes = assembler.payloadBytes();
            }
            Diag.i("清单批次 " + batch + "：收 " + got + " 包，帧 " + frameCount
                    + "，载荷 " + bytes + "B，路径 " + count + (ended ? "，两流已结束" : ""));
            if (batch >= 3 && ended) {
                break;
            }
            if (batch >= 4 && count > 0 && count == lastCount) {
                if (++stable >= 2) {
                    break;
                }
            } else {
                stable = 0;
            }
            lastCount = count;
        }

        byte[] raw;
        synchronized (manifestLock) {
            raw = assembler.manifestBytes();
        }
        String census;
        synchronized (manifestLock) {
            census = assembler.census();
        }
        Diag.i("清单统计：" + census);
        if (raw == null || raw.length == 0) {
            return "相机没有返回文件列表（数据链通了，但 0x00/0x26 没有应答）";
        }
        Diag.i("清单字节数 " + raw.length);
        d.startKeepalive();
        completeManifest(raw);
        return null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onChannelLearned(int channel) {
        Diag.i("相机通道 " + channel);
    }

    @Override
    public void onFrame(DumlFrame frame) {
        if (frame == null) {
            return;
        }
        if (frame.cmdSet() == 0x00 && frame.cmdId() == 0x27) {
            handleManifestFrame(frame);
        }
    }

    @Override
    public void onLost() {
        if (state == State.TRANSFERRING) {
            Diag.w("传输中数据链路断开，保留会话等待热点重连");
            return;
        }
        if (!busy) {
            return;
        }
        attemptFailed("相机数据链路断开");
    }

    @Override
    public void onLog(String line) {
        Diag.i(line);
    }

    // ------------------------------------------------------------------ manifest

    private void assemblerReset() {
        synchronized (manifestLock) {
            assembler = new ManifestAssembler();
        }
        manifestComplete = false;
    }

    /**
     * Feeds one {@code 0x00/0x27} frame to the reassembler. The per-frame 10-byte sub-header
     * stripping and the arrival-order stitching live in {@link ManifestAssembler}, which is verified
     * against the hardware captures; this method only takes the lock and hands the frame over.
     *
     * <p>Completion is decided by the collect loop in {@link #fetchManifest()}, not here: a page is
     * done when the camera closed both streams or the record count stopped moving, and only the loop
     * that sent the trigger frames knows that.
     */
    private void handleManifestFrame(DumlFrame frame) {
        synchronized (manifestLock) {
            assembler.feedFrame(frame);
        }
    }

    private void completeManifest(final byte[] raw) {
        if (manifestComplete) {
            return;
        }
        manifestComplete = true;
        final int gen = linkGeneration;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<MediaFile> files = ManifestParser.parse(raw);
                    media = files;
                    if (!storageKnown && !files.isEmpty()) {
                        storage = HttpFile.detectStorage(CAMERA_HOST, files.get(0).getPath());
                        storageKnown = storage >= 0;
                        if (storage < 0) {
                            storage = 1;
                        }
                        Diag.i("媒体存储在 storage=" + storage);
                    }
                    final List<MediaFile> snapshot = files;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != linkGeneration) {
                                Diag.w("清单解析完成时这次连接已经被放弃，丢弃这份列表");
                                return;
                            }
                            stage = STAGE_READY;
                            busy = false;
                            cancelWatchdog();
                            status = new CameraStatus(
                                    connectedModelName != null ? connectedModelName : status.modelName,
                                    status.batteryPercent, status.freeBytes, status.totalBytes,
                                    storage == 1);
                            Diag.i("机型：" + status.modelName + "（来自 BLE 广播）");
                            setState(State.READY, "共 " + snapshot.size() + " 个文件");
                            listener.onCameraList(snapshot, status);
                        }
                    });
                } catch (Exception e) {
                    fail("解析文件列表失败：" + e);
                }
            }
        });
    }

    // ------------------------------------------------------------------ plumbing

    private void setState(final State newState, final String detail) {
        state = newState;
        Diag.i("[" + newState + "] " + detail + elapsed());
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onState(newState, detail);
                listener.onLog(Diag.tail(30));
            }
        });
    }

    /** " +12.3s" since {@link #connect}, or "" before the first one. */
    private String elapsed() {
        long start = sessionStartMs;
        if (start <= 0) {
            return "";
        }
        return String.format(java.util.Locale.US, "  +%.1fs",
                (System.currentTimeMillis() - start) / 1000.0);
    }

    private void fail(final String message) {
        busy = false;
        cancelWatchdog();
        state = State.ERROR;
        Diag.e(message);
        // Give the camera's AP back to the system. While we hold it the watch cannot reach its own
        // network at all, so a session that has given up must not keep it hostage — that is also what
        // lets the watch fall back to its home Wi-Fi (and adb) on its own.
        cleanup();
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onError(message);
                listener.onLog(Diag.tail(30));
            }
        });
    }

    /**
     * One attempt failed. Tear down what it built and either retry it — a single tap now buys
     * {@link #MAX_ATTEMPTS} tries, which is what the user was doing by hand — or give up with the
     * last reason.
     */
    private void attemptFailed(final String reason) {
        if (!busy) {
            Diag.w("连接已结束，忽略失败：" + reason);
            return;
        }
        Diag.w("第 " + attempt + "/" + MAX_ATTEMPTS + " 次尝试失败：" + reason);
        cleanup();
        if (cancelled) {
            return;
        }
        if (attempt < MAX_ATTEMPTS) {
            final int forAttempt = attempt;
            final long delay = RETRY_DELAY_MS * attempt;
            setState(State.PAIRING, "没连上（" + brief(reason) + "），" + (delay / 1000)
                    + " 秒后自动重试（" + (attempt + 1) + "/" + MAX_ATTEMPTS + "）");
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (busy && !cancelled && attempt == forAttempt && target != null) {
                        connectAttempt();
                    }
                }
            }, delay);
            return;
        }
        busy = false;
        fail("连续 " + MAX_ATTEMPTS + " 次都没连上：" + reason);
    }

    /** A reason short enough for the 226 dp status line; the full text stays in the log. */
    private static String brief(String reason) {
        if (reason == null) {
            return "未知原因";
        }
        String r = reason.replace('\n', ' ');
        return r.length() > 24 ? r.substring(0, 24) + "…" : r;
    }

    /** Close everything this attempt built. Idempotent, and safe from any thread. */
    private void cleanup() {
        cancelWatchdog();
        linkGeneration++;
        Datalink l = link;
        link = null;
        if (l != null) {
            try {
                l.close();
            } catch (RuntimeException e) {
                Diag.w("datalink close: " + e);
            }
        }
        ApJoiner w = wifi;
        wifi = null;
        if (w != null) {
            try {
                w.release();
            } catch (RuntimeException e) {
                Diag.w("wifi release: " + e);
            }
        }
        BleSession b = ble;
        ble = null;
        if (b != null) {
            try {
                b.close();
            } catch (RuntimeException e) {
                Diag.w("ble close: " + e);
            }
        }
        openingLink = false;
    }

    /**
     * Abandon an attempt that stops making progress. Without it a camera that accepts GATT but never
     * answers SetPairingPIN leaves the UI on "正在配对" forever — the 20 s wait before the camera's own
     * 19/8 disconnect in the 2026-11 logs.
     */
    private void armWatchdog(final long ms) {
        cancelWatchdog();
        final int forAttempt = attempt;
        attemptWatchdog = new Runnable() {
            @Override
            public void run() {
                if (busy && attempt == forAttempt) {
                    attemptFailed("这一步 " + (ms / 1000) + " 秒没有进展");
                }
            }
        };
        main.postDelayed(attemptWatchdog, ms);
    }

    private void cancelWatchdog() {
        if (attemptWatchdog != null) {
            main.removeCallbacks(attemptWatchdog);
            attemptWatchdog = null;
        }
    }
}
