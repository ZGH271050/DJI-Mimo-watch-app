package com.watch.osmo.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.util.SparseArray;

import com.watch.osmo.core.CameraInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * BLE scanner for DJI Osmo cameras. Port of {@code dev.konraditurbe.osmosis.ble.OsmoScanner}
 * (osmosis, MIT), reshaped to the watch app's {@link CameraInfo} list API.
 *
 * <p>Scans with <b>no hardware filter</b> (the Osmo Pocket 3 omits manufacturer data, so a filter
 * could hide it) and classifies in software:
 * <ul>
 *   <li>the advert carries a DJI company id → parse the model id with {@link BleAdvert}, and log the
 *       raw payload hex (this is how unknown models get named);</li>
 *   <li>a known brand by MAC OUI or by name ("osmo"/"nano"/"pocket"/"action"/"xtra"/"edge") → treat
 *       as a camera hit even without manufacturer data;</li>
 *   <li>anything else → counted, never named.</li>
 * </ul>
 *
 * <p><b>Non-camera devices are never reported and never named.</b> A scan with no hardware filter
 * sees the whole room, and the count is an honest "did the radio see anything" that leaks nothing
 * about the wearer's surroundings.
 *
 * <p>Callbacks come off the binder thread; every listener call is posted to the main thread so the UI
 * can touch views directly (which is what {@code core.OsmoClient} assumes).
 */
@SuppressLint("MissingPermission")
public class BleScanner {

    public interface Listener {
        /** A camera was seen. One call per device address per scan. */
        void onCamera(CameraInfo c);

        /** Human-readable progress line. */
        void onLog(String s);

        /** The scan could not run (Bluetooth off, no LE scanner, missing permission). */
        void onScanFailed(String reason);
    }

    private final Context context;
    private final Listener listener;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private volatile boolean scanning;

    /** One report per device address per scan, as the Kotlin scanner does. */
    private final Set<String> seen = new HashSet<String>();
    /** Distinct non-camera devices seen this scan — reported as a number, never as names. */
    private int others;

    public BleScanner(Context ctx, Listener l) {
        this.context = ctx.getApplicationContext();
        this.listener = l;
    }

    public boolean isScanning() {
        return scanning;
    }

    // ---- scan control ----------------------------------------------------------------------------

    /**
     * Start an unfiltered scan. Deliberately passes {@code null} filters rather than
     * {@link #djiCompanyFilters()}: a company-id filter would drop the Pocket 3, which advertises no
     * manufacturer data and can only be matched by name.
     */
    public void start() {
        start(false);
    }

    /**
     * @param djiOnly when true, hand the platform the DJI company-id filters. Cheaper on the radio,
     *                but it hides every camera that does not broadcast manufacturer data — see
     *                {@link #start()}.
     */
    public void start(boolean djiOnly) {
        if (scanning) {
            log("扫描：已在扫描中");
            return;
        }
        BluetoothAdapter a = adapter();
        if (a == null) {
            fail("这台设备没有蓝牙适配器");
            return;
        }
        if (!a.isEnabled()) {
            fail("蓝牙未开启");
            return;
        }
        BluetoothLeScanner le;
        try {
            le = a.getBluetoothLeScanner();
        } catch (SecurityException e) {
            fail("缺少蓝牙权限：" + e.getMessage());
            return;
        }
        if (le == null) {
            fail("没有 LE 扫描器（蓝牙开了吗？）");
            return;
        }

        seen.clear();
        others = 0;
        scanner = le;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            le.startScan(djiOnly ? djiCompanyFilters() : null, settings, callback);
        } catch (SecurityException e) {
            scanner = null;
            fail("缺少蓝牙扫描权限：" + e.getMessage());
            return;
        } catch (RuntimeException e) {
            scanner = null;
            fail("扫描启动失败：" + e);
            return;
        }
        scanning = true;
        log("扫描：已开始" + (djiOnly ? "（仅 DJI 厂商数据）" : ""));
    }

    /** Stop the scan. Safe to call when not scanning. */
    public void stop() {
        if (!scanning) {
            return;
        }
        scanning = false;
        try {
            BluetoothLeScanner s = scanner;
            if (s != null) {
                s.stopScan(callback);
            }
        } catch (RuntimeException e) {
            // Stopping a scan that the stack already tore down is not an error worth surfacing.
            log("扫描：停止时报错 " + e);
        }
        scanner = null;
        log("扫描：已停止（另外看到 " + others + " 个非相机 BLE 设备，未记录名称）");
    }

    /**
     * The three DJI company-id filters. Only useful for {@link #start(boolean)}; a hardware filter
     * cannot see a camera that advertises no manufacturer data.
     */
    public static List<ScanFilter> djiCompanyFilters() {
        List<ScanFilter> filters = new ArrayList<ScanFilter>(3);
        // Match on the manufacturer id alone, with an empty mask so any payload is accepted: the
        // advert is identified by its company id, not by service UUIDs (the Pocket 3 broadcasts no
        // fff0 service either, which is why the unfiltered path exists at all).
        filters.add(new ScanFilter.Builder()
                .setManufacturerData(BleConstants.DJI_COMPANY_ID, new byte[0], new byte[0])
                .build());
        filters.add(new ScanFilter.Builder()
                .setManufacturerData(BleConstants.DJI_COMPANY_ID_ALT, new byte[0], new byte[0])
                .build());
        filters.add(new ScanFilter.Builder()
                .setManufacturerData(BleConstants.DJI_COMPANY_ID_E5C0, new byte[0], new byte[0])
                .build());
        return filters;
    }

    // ---- callback --------------------------------------------------------------------------------

    private final ScanCallback callback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            try {
                handle(result);
            } catch (RuntimeException e) {
                // One malformed advert must not kill the scan.
                log("扫描：解析广播出错 " + e);
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) {
                return;
            }
            for (ScanResult r : results) {
                try {
                    handle(r);
                } catch (RuntimeException e) {
                    log("扫描：解析广播出错 " + e);
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            scanner = null;
            fail("扫描失败，错误码 " + errorCode + "（" + scanErrorName(errorCode) + "）");
        }
    };

    private void handle(ScanResult result) {
        if (result == null) {
            return;
        }
        BluetoothDevice dev = result.getDevice();
        if (dev == null) {
            return;
        }
        String address = dev.getAddress();
        ScanRecord rec = result.getScanRecord();
        String name = rec == null ? null : rec.getDeviceName();
        if (name == null) {
            name = safeName(dev);
        }
        int rssi = result.getRssi();

        BleAdvert.Advert advert = null;
        SparseArray<byte[]> msd = rec == null ? null : rec.getManufacturerSpecificData();
        if (msd != null) {
            for (int i = 0; i < msd.size(); i++) {
                int cid = msd.keyAt(i);
                byte[] data = msd.valueAt(i);
                if (data != null && BleConstants.isDjiCompanyId(cid)) {
                    advert = BleAdvert.of(cid, data, name, rssi);
                    break;
                }
            }
        }
        if (advert == null) {
            advert = BleAdvert.of((byte[]) null, name, rssi);
        }

        // Recognize DJI *and* Xtra (rebrand): by OUI (EC:9E:EA) / name, or — most robustly — the DJI
        // company id in the mfr data, which survives a user-renamed device.
        String brand = brandOf(address, name, advert.hasDjiManufacturerData());
        String modelGuess = advert.modelGuess();
        boolean looksCamera = !"UNKNOWN".equals(brand) || modelGuess != null;

        // One line per device per scan.
        if (!seen.add(address)) {
            return;
        }
        if (looksCamera) {
            log(String.format(Locale.ROOT, "HIT [%s] %s rssi=%d %s",
                    brand, address, rssi, advert.describe()));
            listener.onCamera(new CameraInfo(
                    name != null ? name : address,
                    address,
                    advert.modelIdOrZero(),
                    modelGuess != null ? modelGuess : "unknown",
                    rssi));
        } else {
            // Counted, not named — see the class comment. Deliberately covers unnamed devices too, so
            // the number is an honest "how much did the radio see".
            others++;
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * Port of {@code Brand.of} (osmosis, MIT). {@code djiCid} is the definitive DJI tell — every DJI
     * product broadcasts it, across many OUIs and even under a user-renamed device — but it is
     * checked <em>after</em> the Xtra branches so the rebrand (its own OUI) still wins.
     */
    static String brandOf(String address, String name, boolean djiCid) {
        String oui = address == null ? "" : address.toUpperCase(Locale.ROOT);
        if (oui.length() > 8) {
            oui = oui.substring(0, 8);
        }
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (oui.equals(BleConstants.XTRA_OUI)) {
            return "XTRA";
        }
        if (n.contains("xtra") || n.contains("edge")) {
            return "XTRA";
        }
        if (djiCid) {
            return "DJI";
        }
        if (n.contains("osmo") || n.contains("nano") || n.contains("dji")
                || n.contains("pocket") || n.contains("action")) {
            return "DJI";
        }
        return "UNKNOWN";
    }

    private static String scanErrorName(int code) {
        switch (code) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                return "已经在扫描";
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                return "应用注册失败";
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                return "内部错误";
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                return "不支持该扫描特性";
            default:
                return "未知";
        }
    }

    private BluetoothAdapter adapter() {
        if (adapter != null) {
            return adapter;
        }
        try {
            BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm != null) {
                adapter = bm.getAdapter();
            }
        } catch (RuntimeException e) {
            log("扫描：取蓝牙适配器失败 " + e);
        }
        return adapter;
    }

    private String safeName(BluetoothDevice dev) {
        try {
            return dev.getName();
        } catch (SecurityException e) {
            return null;
        }
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
        log("扫描：" + reason);
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onScanFailed(reason);
            }
        });
    }
}
