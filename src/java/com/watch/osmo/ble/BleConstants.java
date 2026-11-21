package com.watch.osmo.ble;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * BLE identifiers, DJI company ids and the model-id to name table.
 *
 * <p>Port of {@code dev.konraditurbe.osmosis.ble.BleConstants} (osmosis, MIT), plus three constants
 * the watch layer needs but the Kotlin object did not carry: {@link #CHAR_FFF7} (present in the
 * protocol map's GATT list, never used by the Kotlin scanner), {@link #REQUEST_MTU} and
 * {@link #ARM_PAIRING}.
 *
 * <p>Pure Java 8 and Android-free on purpose: {@link BleAdvert} references the name table, and that
 * class must stay runnable on a desktop JVM for the self-test.
 */
public final class BleConstants {

    private BleConstants() {
    }

    // ---- GATT ------------------------------------------------------------------------------------

    /** Service the camera's DUML control channel lives on. */
    public static final UUID SERVICE_FFF0 = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    /** Notifications + the pairing-arm write. */
    public static final UUID CHAR_FFF4 = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb");
    /** DUML command writes (Write-Without-Response). */
    public static final UUID CHAR_FFF5 = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb");
    /** Third camera characteristic, listed by the protocol map. No known use in this app. */
    public static final UUID CHAR_FFF7 = UUID.fromString("0000fff7-0000-1000-8000-00805f9b34fb");
    /** Client Characteristic Configuration descriptor, for enabling notifications. */
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** MTU the camera wants; notifications cap at ~23 B without the raise. */
    public static final int REQUEST_MTU = 517;
    /** Bytes written to {@link #CHAR_FFF4} to arm app-level pairing. */
    public static final byte[] ARM_PAIRING = new byte[] { 0x01, 0x00 };

    // ---- manufacturer ids ------------------------------------------------------------------------

    // DJI BLE manufacturer company IDs. On the wire the id is little-endian (AA 08 / AA F7), so
    // Android's SparseArray key is 0x08AA / 0xF7AA.
    public static final int DJI_COMPANY_ID = 0x08AA;
    public static final int DJI_COMPANY_ID_ALT = 0xF7AA;
    // A second DJI id (wire C0 E5), used by the older Osmo Pocket generation — a public teardown of
    // that dock logs it as "Reserved ID <0xE5C0>", and it is not a Bluetooth SIG assignment at all
    // (the registry tops out around 0x10F4). No device we've scanned uses it; matching it costs
    // nothing and stops one showing up as an unnamed "other".
    public static final int DJI_COMPANY_ID_E5C0 = 0xE5C0;

    /** The Xtra rebrand's MAC OUI (a covert DJI shell company). Used by the scanner's name/OUI pass. */
    public static final String XTRA_OUI = "EC:9E:EA";

    public static boolean isDjiCompanyId(int cid) {
        return cid == DJI_COMPANY_ID || cid == DJI_COMPANY_ID_ALT || cid == DJI_COMPANY_ID_E5C0;
    }

    // ---- model ids -------------------------------------------------------------------------------

    /**
     * Model id (classic advert id space) to name, for the scan log and the camera list.
     *
     * <p>Port of {@code BleConstants.MODEL_NAMES}: identical keys and values, same order. Newer
     * cameras leave the classic id at zero and carry a product type instead — {@link BleAdvert}
     * folds that second format back into this id space, which is why {@code 0x0022} can appear here
     * even though no camera advertises it directly.
     */
    public static final Map<Integer, String> MODEL_NAMES;

    static {
        Map<Integer, String> m = new LinkedHashMap<Integer, String>();
        m.put(0x0006, "OsmoAction");      // Action 1 — unconfirmed here, no hardware
        m.put(0x0010, "OsmoAction2");
        m.put(0x0012, "OsmoAction3");
        m.put(0x0014, "OsmoAction4");
        m.put(0x0015, "OsmoAction5Pro");
        m.put(0x0017, "Osmo360");
        m.put(0x0018, "OsmoAction6");
        m.put(0x0019, "OsmoNano");        // verified 2026-07-09: OsmoNano-C2D8 advertised model 0x0019
        m.put(0x0020, "OsmoPocket3");
        m.put(0x0021, "OsmoPocket4");
        // Verified 2026-08-07: OsmoPocket4P-6E55 advertised the new format with product type 218
        // (HG224), which corresponds to classic id 0x22.
        m.put(0x0022, "OsmoPocket4P");
        m.put(0x0070, "Mavic3");          // drone — offload confirmed end-to-end on hardware
        m.put(0x007E, "Neo2");            // drone — never offloaded; datalink port unconfirmed
        MODEL_NAMES = Collections.unmodifiableMap(m);
    }

    /** The name for {@code modelId}, or null when the table has no entry. */
    public static String modelName(int modelId) {
        return MODEL_NAMES.get(Integer.valueOf(modelId & 0xFFFF));
    }

    /** {@link #modelName(int)}, or the {@code unknown(0x0023)} form the scan log uses. */
    public static String describeModel(int modelId) {
        String name = modelName(modelId);
        if (name != null) {
            return name;
        }
        return String.format(Locale.ROOT, "unknown(0x%04x)", modelId & 0xFFFF);
    }

    /** Lower-case hex with no separator, matching Kotlin's {@code "%02x".format(it)} join. */
    public static String hex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            sb.append(HEX[(bytes[i] >>> 4) & 0x0F]);
            sb.append(HEX[bytes[i] & 0x0F]);
        }
        return sb.toString();
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** The four-character short form the Kotlin scanner logs ({@code 0000fff4-… -> fff4}). */
    public static String shortUuid(UUID uuid) {
        if (uuid == null) {
            return "?";
        }
        String s = uuid.toString();
        return s.length() >= 8 ? s.substring(4, 8) : s;
    }
}
