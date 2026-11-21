package com.watch.osmo.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.watch.osmo.duml.Bytes;
import com.watch.osmo.duml.DumlFrame;
import com.watch.osmo.duml.OsmoCommands;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The BLE half of a camera session: connect, raise the MTU, discover {@code fff0}, enable
 * notifications, arm pairing, then run the pairing / Wi-Fi-credentials / AP-wake exchange.
 *
 * <p>Port of {@code dev.konraditurbe.osmosis.ble.GattClient} plus the pairing and Wi-Fi parts of
 * {@code ui.MainActivity} (osmosis, MIT). The flow, and who owns which step:
 *
 * <pre>
 *   connect()  -&gt; connectGatt -&gt; MTU 517 -&gt; discoverServices
 *              -&gt; notify on fff4 + fff5 -&gt; write [01 00] to fff4 (arm pairing)
 *              -&gt; 0x00/2b[04 00] session wake, then +120 ms -&gt; onReady()
 *   onReady()  -&gt; the caller calls pair(token)
 *   pair()     -&gt; SetPairingPIN 07/45, retried at +2.5 s / +5 s while unanswered
 *              -&gt; onPairingApprovalNeeded() (07/45 status 00 02) or
 *              -&gt; onPaired() (07/45 status 00 01, or the 07/46 REQUEST that is the real signal)
 *   onPaired() -&gt; the caller calls requestWifiCredentials()
 *   requestWifiCredentials() -&gt; 0x53/10 wake, 07/07, +500 ms 07/0e -&gt; onWifiCredentials(ssid, pass)
 *   wakeAccessPoint(ssid, pass) -&gt; 07/47 ConnectToWiFi -&gt; onApAwake() on the 00 00 reply
 * </pre>
 *
 * <p>Every listener callback is posted to the <b>main thread</b>, because the GATT callbacks arrive
 * on a binder thread and the caller is a UI. Writing to the main thread is what the Kotlin app had to
 * do explicitly after {@code ValueAnimator.cancel()} threw on a Looper-less thread.
 *
 * <p>Writes go out with Write-Without-Response to {@code fff5}, which is why the two credential
 * queries are spaced 500 ms apart: back-to-back frames drop.
 */
@SuppressLint("MissingPermission")
public class BleSession {

    public interface Listener {
        /** Human-readable progress line. */
        void onLog(String s);

        /** GATT ready: MTU raised, {@code fff0} discovered, notifications on, pairing armed. */
        void onReady();

        /** The camera wants the user to tap approve on its screen (07/45 status 00 02). */
        void onPairingApprovalNeeded();

        /** Pairing completed: either 07/45 status 00 01, or the 07/46 REQUEST was answered. */
        void onPaired();

        /** Both Wi-Fi credentials arrived over BLE; never fired with only one of them. */
        void onWifiCredentials(String ssid, String password);

        /** The camera's own Wi-Fi MAC (07/0c). */
        void onWifiMac(String mac);

        /** 07/47 ConnectToWiFi answered {@code 00 00} — the AP is coming up (~15 s). */
        void onApAwake();

        /** Something went wrong and the session cannot continue. */
        void onFailure(String reason);
    }

    // ---- timing ----------------------------------------------------------------------------------

    /** Gap between the session wake and the pairing write, per the Kotlin READY handler. */
    private static final long PAIR_WAKE_GAP_MS = 120L;
    /** SetPairingPIN retries while the camera has not answered at all (fff5 can drop a first write). */
    private static final long PAIR_RETRY_1_MS = 2500L;
    private static final long PAIR_RETRY_2_MS = 5000L;
    /** 0x53/10 — "the one that matters: the camera answers 01 00 00 00 and wakes." */
    private static final long SESSION_WAKE_MS = 100L;
    /** 07/07 GetWifiSsid, from the post-pair sequence. */
    private static final long SSID_QUERY_MS = 900L;
    /** 07/0e GetWifiPassword — 500 ms after the SSID query, so neither write is dropped. */
    private static final long PASSWORD_QUERY_MS = 1400L;
    /** Minimum spacing between two fff5 writes on this link. */
    private static final long QUERY_SPACING_MS = 500L;
    /** Only a log hint: the caller decides when to join the AP. */
    private static final long AP_BRINGUP_HINT_MS = 15000L;

    /** What the camera prints on its screen, uppercased, when the token is "osmo". */
    private static final String DEFAULT_TOKEN = "osmo";

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothGatt gatt;
    private final Deque<BluetoothGattCharacteristic> cccdQueue = new ArrayDeque<BluetoothGattCharacteristic>();
    private final List<Runnable> pending = new ArrayList<Runnable>();

    private volatile int negotiatedMtu = 23;
    private String macAddress;
    private String identifier;
    private String lastToken = DEFAULT_TOKEN;

    private boolean ready;
    private boolean armed;
    private volatile boolean closed;

    private boolean paired;
    private boolean pairingReplySeen;
    private int unknownPairStatuses;
    /**
     * True once the Wi-Fi credentials are in the caller's hands. The camera drops its BLE link 5-6 s
     * after {@code 07/47 ConnectToWiFi} so it can switch the radio to the AP — measured on the
     * 2026-11 firmware, 44 times, always status 8 — and that teardown says nothing about the session:
     * the Wi-Fi association is usually still in flight. Treating it as a failure is what aborted the
     * join and made one success take dozens of attempts.
     */
    private volatile boolean handedOff;

    private String ssid;
    private String password;
    private boolean credentialsDelivered;
    private volatile boolean apAwake;
    private long lastQueryAt;

    public BleSession(Context ctx, Listener l) {
        this.context = ctx.getApplicationContext();
        this.listener = l;
    }

    /** MTU the camera granted, 23 until {@code onMtuChanged} says otherwise. */
    public int negotiatedMtu() {
        return negotiatedMtu;
    }

    /** True once {@link Listener#onReady()} has been delivered for this session. */
    public boolean isReady() {
        return ready;
    }

    // ---- connection ------------------------------------------------------------------------------

    /**
     * Open the GATT link and bring up the DUML channel.
     *
     * @param macAddress the camera's Bluetooth address.
     * @param identifier the app identity presented with SetPairingPIN. A camera keys its remembered
     *                   approval on this string, so every retry (and every later session) must pass
     *                   the same one. Null falls back to the well-known dji-remote identity.
     */
    public void connect(String macAddress, String identifier) {
        this.macAddress = macAddress;
        this.identifier = identifier == null
                ? OsmoCommands.PairMessagePayload.DEFAULT_IDENTIFIER : identifier;

        // A session object is per-connection (core.OsmoClient builds a new one per camera), but reset
        // anyway so a re-used instance cannot inherit a previous camera's state.
        closed = false;
        ready = false;
        armed = false;
        paired = false;
        pairingReplySeen = false;
        unknownPairStatuses = 0;
        handedOff = false;
        ssid = null;
        password = null;
        credentialsDelivered = false;
        apAwake = false;
        negotiatedMtu = 23;
        cccdQueue.clear();
        cancelPending();

        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            fail("没有蓝牙适配器");
            return;
        }
        if (!adapter.isEnabled()) {
            fail("蓝牙未开启");
            return;
        }
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(macAddress);
        } catch (IllegalArgumentException e) {
            fail("蓝牙地址不合法：" + macAddress);
            return;
        }
        log("GATT: 正在连接 " + macAddress + " …");
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            fail("缺少蓝牙权限：" + e.getMessage());
            return;
        } catch (RuntimeException e) {
            fail("connectGatt 失败：" + e);
            return;
        }
        if (gatt == null) {
            fail("connectGatt 返回空");
        }
    }

    // ---- commands --------------------------------------------------------------------------------

    /**
     * Send SetPairingPIN (07/45, payload {@code PackString(identifier) + PackString(token)}).
     *
     * <p>Retries twice while the camera has answered nothing — {@code fff5} is
     * Write-Without-Response, so the first write can drop. Both retries carry the <b>same</b>
     * identifier: a retry with a different one reads as a second app asking to pair.
     */
    public void pair(String token) {
        final String pin = (token == null || token.length() == 0) ? DEFAULT_TOKEN : token;
        lastToken = pin;
        pairingReplySeen = false;
        final String id = identifier != null
                ? identifier : OsmoCommands.PairMessagePayload.DEFAULT_IDENTIFIER;
        writeCommand(OsmoCommands.setPairingPin(pin, OsmoCommands.PAIR_MSG_ID, id));
        log("发送 SetPairingPIN(pin=\"" + pin + "\" id=\"" + shortId(id) + "…\")");

        schedule(new Runnable() {
            @Override
            public void run() {
                if (paired || pairingReplySeen) {
                    return;
                }
                log("配对：相机仍未回复 — 重发 SetPairingPIN");
                writeCommand(OsmoCommands.setPairingPin(lastToken, OsmoCommands.PAIR_MSG_ID, id));
            }
        }, PAIR_RETRY_1_MS);
        schedule(new Runnable() {
            @Override
            public void run() {
                if (paired || pairingReplySeen) {
                    return;
                }
                log("配对：相机仍未回复 — 最后重发一次 SetPairingPIN");
                writeCommand(OsmoCommands.setPairingPin(lastToken, OsmoCommands.PAIR_MSG_ID, id));
            }
        }, PAIR_RETRY_2_MS);
    }

    /**
     * Read the camera's own AP SSID and passphrase over BLE.
     *
     * <p>The whole Mimo post-pair sequence lives here, not just the two reads, because the wake in
     * front of them is what makes the camera answer at all:
     * <pre>
     *   +100 ms  0x53/10  -> 0x1C   (the command that actually correlates with the camera waking)
     *   +900 ms  07/07             GetWifiSsid
     *   +1400 ms 07/0e             GetWifiPassword, 500 ms after the SSID query
     * </pre>
     * Those absolute timings are the Kotlin app's; the 500 ms the protocol map insists on is the
     * 900 → 1400 gap. {@link Listener#onWifiCredentials} fires only once both strings are in hand.
     */
    public void requestWifiCredentials() {
        ssid = null;
        password = null;
        credentialsDelivered = false;

        schedule(new Runnable() {
            @Override
            public void run() {
                writeCommand(OsmoCommands.session5310());
                log("发送 0x53/10（会话唤醒）");
            }
        }, SESSION_WAKE_MS);

        schedule(new Runnable() {
            @Override
            public void run() {
                lastQueryAt = System.currentTimeMillis();
                writeCommand(OsmoCommands.wifiSsidQuery());
                log("发送 07/07 GetWifiSsid");
            }
        }, SSID_QUERY_MS);

        schedule(new Runnable() {
            @Override
            public void run() {
                lastQueryAt = System.currentTimeMillis();
                writeCommand(OsmoCommands.wifiPasswordQuery());
                log("发送 07/0e GetWifiPassword");
            }
        }, PASSWORD_QUERY_MS);
    }

    /**
     * Read the camera's own Wi-Fi MAC (07/0c). Not part of the Kotlin flow — the protocol map lists
     * the getter, so this is provided on the same terms as the other two. It paces itself: if a
     * credentials query went out less than 500 ms ago, this waits out the remainder rather than
     * racing it onto {@code fff5}.
     */
    public void requestWifiMac() {
        long wait = Math.max(0L, lastQueryAt + QUERY_SPACING_MS - System.currentTimeMillis());
        if (wait > 0) {
            log("07/0c 延后 " + wait + " ms 发送（避免和上一次查询撞在 fff5 上）");
        }
        schedule(new Runnable() {
            @Override
            public void run() {
                lastQueryAt = System.currentTimeMillis();
                writeCommand(OsmoCommands.wifiMacQuery());
                log("发送 07/0c GetWifiMac");
            }
        }, wait);
    }

    /**
     * Wake the camera's access point by sending it its own SSID and passphrase back (07/47
     * ConnectToWiFi). The AP takes roughly 15 s to appear; the reply {@code 00 00} arrives long
     * before that and is reported through {@link Listener#onApAwake()}.
     */
    public void wakeAccessPoint(String ssid, String password) {
        if (ssid == null) {
            fail("没有 SSID，无法唤醒热点");
            return;
        }
        final String pass = password == null ? "" : password;
        writeCommand(OsmoCommands.connectWifi(ssid, pass, OsmoCommands.WIFI_MSG_ID));
        log("发送 07/47 ConnectToWiFi（\"" + ssid + "\"）— 热点约 " + (AP_BRINGUP_HINT_MS / 1000) + " 秒后可用");
        // The 2026-11 firmware sometimes ignores the first 07/47 and the AP never appears, which then
        // looks like "Wi-Fi 连不上". One repeat at 1.2 s is still well inside the 5-6 s the camera
        // needs before it drops BLE, and an already-awake AP simply gets the same instruction twice.
        schedule(new Runnable() {
            @Override
            public void run() {
                if (closed || apAwake) {
                    return;
                }
                log("07/47 还没确认，补发一次 ConnectToWiFi");
                writeCommand(OsmoCommands.connectWifi(ssid, pass, OsmoCommands.WIFI_MSG_ID));
            }
        }, 1200);
    }

    /** Disconnect, drop every scheduled write and stop reporting. Idempotent. */
    public void close() {
        closed = true;
        cancelPending();
        BluetoothGatt g = gatt;
        gatt = null;
        ready = false;
        if (g != null) {
            try {
                g.disconnect();
            } catch (RuntimeException e) {
                // Already gone.
            }
            try {
                g.close();
            } catch (RuntimeException e) {
                // Already gone.
            }
        }
        log("BLE 会话已关闭");
    }

    // ---- GATT callback ---------------------------------------------------------------------------

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("GATT: 已连接 (status=" + status + ")，请求 MTU " + BleConstants.REQUEST_MTU);
                boolean asked = g.requestMtu(BleConstants.REQUEST_MTU);
                if (!asked) {
                    log("GATT: requestMtu 未被接受，继续用默认 MTU");
                    g.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("GATT: 已断开 (status=" + status + ")");
                handleDisconnect(status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            negotiatedMtu = mtu;
            log("GATT: MTU=" + mtu + " (status=" + status + ")，开始发现服务");
            g.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("服务发现失败 (status=" + status + ")");
                return;
            }
            android.bluetooth.BluetoothGattService svc = g.getService(BleConstants.SERVICE_FFF0);
            if (svc == null) {
                StringBuilder present = new StringBuilder();
                for (android.bluetooth.BluetoothGattService s : g.getServices()) {
                    if (present.length() > 0) {
                        present.append(", ");
                    }
                    present.append(BleConstants.shortUuid(s.getUuid()));
                }
                fail("找不到服务 fff0；实际有：" + present);
                return;
            }
            StringBuilder chars = new StringBuilder();
            for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                if (chars.length() > 0) {
                    chars.append(", ");
                }
                chars.append(BleConstants.shortUuid(c.getUuid()));
            }
            log("GATT: fff0 的特征：" + chars);

            cccdQueue.clear();
            // fff4 carries the pairing-arm write and the notifications; fff5 carries DUML writes and,
            // on some models, notifications too. fff7 is present but has no known use.
            enableNotify(g, svc, BleConstants.CHAR_FFF4);
            enableNotify(g, svc, BleConstants.CHAR_FFF5);
            writeNextCccd(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            log("GATT: " + BleConstants.shortUuid(descriptor.getCharacteristic().getUuid())
                    + " 通知已开启 (status=" + status + ")");
            writeNextCccd(g);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (ch.getUuid().equals(BleConstants.CHAR_FFF4)) {
                log("GATT: fff4 配对臂写入完成 (status=" + status + ")");
                becomeReady();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] value = ch.getValue();
            onNotify(ch.getUuid(), value == null ? new byte[0] : value);
        }
    };

    private void enableNotify(BluetoothGatt g, android.bluetooth.BluetoothGattService svc, UUID uuid) {
        BluetoothGattCharacteristic ch = svc.getCharacteristic(uuid);
        if (ch == null) {
            log("GATT: " + BleConstants.shortUuid(uuid) + " 不存在，跳过");
            return;
        }
        g.setCharacteristicNotification(ch, true);
        cccdQueue.add(ch);
    }

    private void writeNextCccd(BluetoothGatt g) {
        BluetoothGattCharacteristic ch = cccdQueue.poll();
        if (ch == null) {
            armPairing(g);
            return;
        }
        BluetoothGattDescriptor d = ch.getDescriptor(BleConstants.CCCD);
        if (d == null) {
            log("GATT: " + BleConstants.shortUuid(ch.getUuid()) + " 没有 CCCD，跳过");
            writeNextCccd(g);
            return;
        }
        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (!g.writeDescriptor(d)) {
            log("GATT: CCCD 写入未被接受 (" + BleConstants.shortUuid(ch.getUuid()) + ")，继续");
            writeNextCccd(g);
        }
    }

    /**
     * Write {@code [01 00]} to fff4 with a response, so pairing can be sequenced strictly after it
     * completes (see {@code onCharacteristicWrite}).
     */
    private void armPairing(BluetoothGatt g) {
        BluetoothGattCharacteristic ch = g.getService(BleConstants.SERVICE_FFF0) == null
                ? null : g.getService(BleConstants.SERVICE_FFF0).getCharacteristic(BleConstants.CHAR_FFF4);
        if (ch == null) {
            becomeReady();
            return;
        }
        ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        ch.setValue(BleConstants.ARM_PAIRING);
        boolean ok = g.writeCharacteristic(ch);
        log("GATT: 向 fff4 写入 [01 00] 以臂上配对，已提交=" + ok);
        if (!ok && !armed) {
            // Without the arm write there is no onCharacteristicWrite to hang readiness on.
            becomeReady();
        }
    }

    /**
     * The link is up and armed: send the session wake immediately and only then tell the caller, so
     * its {@link #pair(String)} cannot land inside the 120 ms the wake needs.
     */
    private void becomeReady() {
        if (armed || closed) {
            return;
        }
        armed = true;
        final boolean woke = writeCommand(OsmoCommands.sessionPing(OsmoCommands.SESSION_WAKE));
        log("就绪 — 已发送会话唤醒 0x00/2b[04 00] 已提交=" + woke);
        schedule(new Runnable() {
            @Override
            public void run() {
                if (closed) {
                    return;
                }
                ready = true;
                listener.onReady();
            }
        }, PAIR_WAKE_GAP_MS);
    }

    private void handleDisconnect(int status) {
        if (closed) {
            return;
        }
        if (handedOff) {
            // Expected: the camera frees its radio for the AP it just woke up. The Wi-Fi joiner owns the
            // session from here — the BLE link has no further role.
            log("GATT: 相机断开 BLE (status=" + status + ") — 热点信息已取到，属正常交接，忽略");
            return;
        }
        if (!ready) {
            fail("GATT 在就绪前断开 (status=" + status + ")");
            return;
        }
        if (status == 19) {
            // Documented behaviour: the camera tears the link down ~5 s after pairing if the app
            // idles, because you are meant to move to Wi-Fi. Not an error.
            log("GATT: 相机主动断开 (status=19) — 配对后闲置约 5 秒即断，属正常设计");
        } else if (status == 0) {
            log("GATT: 对端正常断开 (status=0)");
        } else {
            fail("GATT 断开 (status=" + status + ")");
        }
    }

    // ---- notifications ---------------------------------------------------------------------------

    private void onNotify(UUID source, byte[] value) {
        if (closed) {
            return;
        }
        DumlFrame frame;
        try {
            frame = DumlFrame.decode(value);
        } catch (RuntimeException e) {
            log("BLE <- " + BleConstants.shortUuid(source) + " "
                    + Bytes.hex(value) + "（不是有效 DUML：" + e.getMessage() + "）");
            return;
        }
        log("BLE <- " + BleConstants.shortUuid(source) + " " + frame.format());

        // The camera sends some messages as REQUESTS (flags=0x40) and drops us (~6 s) if we do not
        // answer. Auto-reply with a matching response (flags=0xC0, swapped target, echoed id) — this
        // is what keeps a paired BLE session alive. Port of MainActivity.onNotification.
        if (frame.flags() == 0x40) {
            autoAck(frame);
            // First-time pairing: the approval arrives as a 07/46 REQUEST, not a response, so it is
            // handled here before the CmdSet 07 block. The REQUEST *is* the pairing-complete signal.
            if (frame.cmdSet() == 0x07 && frame.cmdId() == 0x46) {
                log("配对 <- 0x07/46 已批准（REQUEST 帧）");
                markPaired();
            }
            return;
        }

        if (frame.cmdSet() != 0x07) {
            // Session traffic (0x00/0x2b, 0x00/0x32 activation push, …) is not needed for the BLE
            // credentials path. The frame itself is logged above.
            return;
        }

        byte[] p = frame.payload;
        switch (frame.cmdId()) {
            case 0x45: {
                int status = p.length >= 2 ? (p[1] & 0xFF) : -1;
                if (status == 0x01) {
                    pairingReplySeen = true;
                    log("配对 <- 0x07/45 已配对过（ALREADY PAIRED）");
                    markPaired();
                } else if (status == 0x02) {
                    pairingReplySeen = true;
                    log("配对 <- 0x07/45 需要在相机屏幕上确认（显示 OSMO）");
                    listener.onPairingApprovalNeeded();
                } else {
                    // 0x03 and 0x06 are new with the 2026-11 firmware (they never appeared before it).
                    // They are not approval and not success: the camera is still holding the previous
                    // link, or is busy. Re-ask a couple of times on the same link, then hand the whole
                    // attempt back so it can be retried with a fresh GATT connection — which is what
                    // actually clears it.
                    pairingReplySeen = true;
                    unknownPairStatuses++;
                    log(String.format(Locale.ROOT, "配对 <- 0x07/45 status=0x%02x [%s]（未配对，第 %d 次）",
                            status, Bytes.hex(p), unknownPairStatuses));
                    if (unknownPairStatuses <= 2) {
                        schedule(new Runnable() {
                            @Override
                            public void run() {
                                if (paired || closed) {
                                    return;
                                }
                                log("配对：相机没给 0x01/0x02，重发 SetPairingPIN");
                                writeCommand(OsmoCommands.setPairingPin(lastToken,
                                        OsmoCommands.PAIR_MSG_ID, identifier));
                            }
                        }, 1200);
                    } else {
                        fail(String.format(Locale.ROOT,
                                "相机拒绝配对（0x07/45 status=0x%02x），多半还连着上一次会话", status));
                    }
                }
                break;
            }
            case 0x46:
                log("配对 <- 0x07/46 已批准 [ " + Bytes.hex(p) + " ]");
                markPaired();
                break;
            case 0x47: {
                if (p.length >= 2 && (p[0] & 0xFF) == 0 && (p[1] & 0xFF) == 0) {
                    apAwake = true;
                    log("WiFi <- 0x07/47 结果为 ok，热点正在起来");
                    listener.onApAwake();
                } else {
                    log("WiFi <- 0x07/47 结果异常 [ " + Bytes.hex(p) + " ]");
                }
                break;
            }
            case 0x07: {
                String s = OsmoCommands.parseStatusPackString(p);
                if (s != null && s.length() > 0) {
                    ssid = s;
                    log("WiFi <- 0x07/07 SSID = \"" + s + "\"");
                    maybeDeliverCredentials();
                } else {
                    log("WiFi <- 0x07/07 回复里没有 SSID [ " + Bytes.hex(p) + " ]");
                }
                break;
            }
            case 0x0E: {
                String pass = OsmoCommands.parseStatusPackString(p);
                if (pass != null && pass.length() > 0) {
                    password = pass;
                    // Never log the passphrase itself — only its length.
                    log("WiFi <- 0x07/0e 已取回密码（" + pass.length() + " 个字符）");
                    maybeDeliverCredentials();
                } else {
                    log("WiFi <- 0x07/0e 回复里没有密码");
                }
                break;
            }
            case 0x0C: {
                String mac = parseMacPayload(p);
                if (mac != null) {
                    log("WiFi <- 0x07/0c MAC = " + mac);
                    listener.onWifiMac(mac);
                } else {
                    log("WiFi <- 0x07/0c 回复长度不对 [ " + Bytes.hex(p) + " ]");
                }
                break;
            }
            default:
                log(String.format(Locale.ROOT, "CMD07 <- 0x07/%02x  [%s]", frame.cmdId(), Bytes.hex(p)));
                break;
        }
    }

    /**
     * Reply to a camera REQUEST: swap the sender/receiver bytes, echo the message id, set flags to
     * 0xC0 and echo the payload — except for 0x00/0x81, which answers with the app device-info blob.
     * Port of {@code MainActivity.onNotification}.
     */
    private void autoAck(DumlFrame request) {
        int respTarget = ((request.target & 0xFF) << 8) | ((request.target >>> 8) & 0xFF);
        int respType = (request.type & 0xFFFF00) | 0xC0;
        byte[] payload = (request.cmdSet() == 0x00 && request.cmdId() == 0x81)
                ? OsmoCommands.appDeviceInfo() : request.payload;
        byte[] resp = new DumlFrame(respTarget, request.id, respType, payload).encode();
        final boolean ok = writeCommand(resp);
        log(String.format(Locale.ROOT, "REQ <- 0x%02x/%02x (flags40) -> 已回 ack 提交=%s",
                request.cmdSet(), request.cmdId(), String.valueOf(ok)));
    }

    /** Fire {@link Listener#onPaired()} at most once per session; both 07/45 and 07/46 lead here. */
    private void markPaired() {
        if (paired) {
            return;
        }
        paired = true;
        listener.onPaired();
    }

    private void maybeDeliverCredentials() {
        if (credentialsDelivered || ssid == null || password == null) {
            return;
        }
        credentialsDelivered = true;
        // From this instant the BLE link is a bonus, not a dependency: the camera will drop it while
        // the watch is still associating with the AP.
        handedOff = true;
        listener.onWifiCredentials(ssid, password);
    }

    /**
     * Tell the session that its caller has taken over the next stage, so a camera-side BLE teardown
     * is logged rather than reported as a failure. Idempotent.
     */
    public void markHandedOff() {
        handedOff = true;
    }

    /**
     * 07/0c reply is {@code [status:1][6-byte MAC]}. Accepts a bare 6-byte MAC too, because the reply
     * shape has never been captured (the Kotlin app never queried it).
     */
    static String parseMacPayload(byte[] p) {
        if (p == null) {
            return null;
        }
        int off;
        if (p.length >= 7) {
            off = 1;
        } else if (p.length == 6) {
            off = 0;
        } else {
            return null;
        }
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format(Locale.ROOT, "%02X", p[off + i] & 0xFF));
        }
        return sb.toString();
    }

    // ---- writes ----------------------------------------------------------------------------------

    /**
     * Write a DUML frame to fff5 (Write-Without-Response).
     *
     * @return whether {@code writeCharacteristic} accepted the buffer. Calls made off the main thread
     *         (the auto-ack, which arrives on a binder thread) are posted instead and report true.
     */
    private boolean writeCommand(final byte[] bytes) {
        if (closed) {
            return false;
        }
        if (Looper.myLooper() != main.getLooper()) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    doWriteCommand(bytes);
                }
            });
            return true;
        }
        return doWriteCommand(bytes);
    }

    private boolean doWriteCommand(byte[] bytes) {
        BluetoothGatt g = gatt;
        if (g == null) {
            log("写入失败：没有 GATT 连接");
            return false;
        }
        android.bluetooth.BluetoothGattService svc = g.getService(BleConstants.SERVICE_FFF0);
        BluetoothGattCharacteristic ch = svc == null ? null : svc.getCharacteristic(BleConstants.CHAR_FFF5);
        if (ch == null) {
            log("写入失败：找不到 fff5");
            return false;
        }
        ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        ch.setValue(bytes);
        boolean ok = g.writeCharacteristic(ch);
        log("BLE -> fff5 " + Bytes.hex(bytes) + (ok ? "" : "  (未被接受)"));
        return ok;
    }

    // ---- plumbing --------------------------------------------------------------------------------

    private void schedule(Runnable body, long delayMs) {
        final Runnable[] holder = new Runnable[1];
        Runnable wrapped = new Runnable() {
            @Override
            public void run() {
                pending.remove(holder[0]);
                body.run();
            }
        };
        holder[0] = wrapped;
        pending.add(wrapped);
        if (delayMs <= 0) {
            main.post(wrapped);
        } else {
            main.postDelayed(wrapped, delayMs);
        }
    }

    private void cancelPending() {
        for (Runnable r : new ArrayList<Runnable>(pending)) {
            main.removeCallbacks(r);
        }
        pending.clear();
    }

    private BluetoothAdapter adapter() {
        try {
            BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            return bm == null ? null : bm.getAdapter();
        } catch (RuntimeException e) {
            log("取蓝牙适配器失败 " + e);
            return null;
        }
    }

    private static String shortId(String id) {
        if (id == null) {
            return "?";
        }
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private void log(final String s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onLog(s);
            }
        });
    }

    private void fail(final String reason) {
        if (closed) {
            return;
        }
        log("失败：" + reason);
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onFailure(reason);
            }
        });
    }
}
