package com.watch.osmo.ble;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Decodes a DJI BLE advertisement's manufacturer payload into a model id.
 *
 * <p>Verbatim port of {@code dev.konraditurbe.osmosis.ble.BleAdvert} (osmosis, MIT). The comment
 * blocks below are the Kotlin author's, kept because they are the only record of <em>why</em> the
 * new format exists.
 *
 * <p>DJI ships <b>two</b> advertisement formats and the newer one is why an Osmo Pocket 4P
 * scanned as {@code model=unknown(0x0000)}: it leaves the classic model byte at zero and puts a
 * 16-bit <em>product type</em> further in. Every open-source implementation (osmo-download,
 * node-osmo, dji-remote, and the Kotlin app until recently) only reads the classic byte, so any
 * camera using the new format is unidentifiable except by its BLE name.
 *
 * <p>The selector is <b>bit 2 of payload byte 5</b>: set, and a 16-bit little-endian product type at
 * bytes [10:12] carries the model; clear (or the payload too short to hold one), and it is the
 * classic id at [0:2]. Offsets here are payload-relative — Android splits the company id off into
 * the SparseArray key — so anything quoting them against the raw advert, company id included, is two
 * higher.
 *
 * <p>An OsmoPocket4P payload, byte by byte:
 *
 * <pre>
 * index  0  1  2  3  4  5  6  7  8  9 10 11 12 13
 * value 00 00 00 ee 00 04 bd 6e 56 20 da 00 00 10
 *       \___/ classic id = 0     ^ flag  \___/ productType = 0x00da = 218
 * </pre>
 *
 * <p><b>Pure Java 8, no Android APIs</b>, so the parsing is testable on a desktop JVM — the whole
 * point of keeping it out of the scan callback. See {@code test/BleSelfTest.java}.
 */
public final class BleAdvert {

    /** Bit 2 of payload byte 5 ⇒ the 16-bit product type at [10:12] is populated. */
    public static final int NEW_FORMAT_FLAG_INDEX = 5;
    public static final int NEW_FORMAT_FLAG_MASK = 0x04;
    public static final int PRODUCT_TYPE_INDEX = 10;

    private BleAdvert() {
    }

    /**
     * DJI {@code ProductType} → the classic model id this app keys everything on.
     *
     * <p>The two id spaces run in parallel: every camera has a DJI product-type number <em>and</em> a
     * classic advert id, and this maps the former onto the latter so the rest of the app only ever
     * deals in one of them. Codes in the comments (AC103, HG224, …) are DJI's internal names for the
     * models.
     *
     * <p>Only <b>218 (HG224)</b> has been seen on real hardware — a Pocket 4P that reached the
     * grid and moved 43 MB before its AP dropped. The rest are the same table read straight through
     * and are unconfirmed; they cost nothing if wrong, because an unmapped value just falls through
     * to the classic byte and gets logged with its number so the next tester's log names it.
     *
     * <p><b>Shipping products only.</b> DJI's table also carries codes for hardware that has never
     * been announced, and this app is not the place to publish it: nobody can own one, so mapping it
     * buys nothing. If one ever turns up in a scan it logs its raw product type and we deal with it
     * then.
     */
    public static final Map<Integer, Integer> PRODUCT_TYPE_TO_MODEL_ID;

    static {
        Map<Integer, Integer> m = new LinkedHashMap<Integer, Integer>();
        m.put(40, 0x0006);    // OSMO_ACTION      — Osmo Action 1 (classic 0x06/0x07/0x82 all map here)
        m.put(143, 0x0010);   // OSMO_ACTION_2    — AC103
        m.put(231, 0x0012);   // AC202            — Osmo Action 3
        m.put(203, 0x0014);   // AC203            — Osmo Action 4
        m.put(235, 0x0015);   // AC204            — Osmo Action 5 Pro
        m.put(224, 0x0017);   // OQ101            — Osmo 360
        m.put(223, 0x0018);   // AC206            — Osmo Action 6
        m.put(222, 0x0019);   // OW001            — Osmo Nano
        m.put(145, 0x0020);   // HG212            — Osmo Pocket 3
        m.put(219, 0x0021);   // HG214            — Osmo Pocket 4
        m.put(218, 0x0022);   // HG224            — Osmo Pocket 4P  [hardware-observed 2026-08-07]
        m.put(229, 0x0087);   // OSMO_POCKET2     — HG211
        PRODUCT_TYPE_TO_MODEL_ID = Collections.unmodifiableMap(m);
    }

    // ---- decode ----------------------------------------------------------------------------------

    /** What {@link #decode(byte[])} found, so the scan log can say <em>why</em> a camera is unidentified. */
    public static final class Decoded {

        /** The classic model id, or null when neither format yields one. */
        public final Integer modelId;
        /** True when the 16-bit product type was the field that carried the model. */
        public final boolean newFormat;
        /** The raw product type when the payload carried one, else null. */
        public final Integer rawProductType;

        Decoded(Integer modelId, boolean newFormat, Integer rawProductType) {
            this.modelId = modelId;
            this.newFormat = newFormat;
            this.rawProductType = rawProductType;
        }

        /** The name for {@link #modelId}, or null when it is unmapped/absent. */
        public String modelName() {
            return modelId == null ? null : BleConstants.modelName(modelId.intValue());
        }

        /**
         * The scanner's one-word answer, exactly as the Kotlin scanner builds it: a mapped name, the
         * product-type number for a new-format camera we cannot name yet, or null when nothing was
         * decoded at all.
         */
        public String modelGuess() {
            if (modelId != null) {
                String name = modelName();
                return name != null ? name
                        : String.format(Locale.ROOT, "unknown(0x%04x)", modelId.intValue() & 0xFFFF);
            }
            if (newFormat && rawProductType != null) {
                return String.format(Locale.ROOT, "unknown(productType=%d)", rawProductType.intValue());
            }
            return null;
        }

        /** {@link #modelId} as a plain int, with 0 for "no model" (the DJI advert's own zero). */
        public int modelIdOrZero() {
            return modelId == null ? 0 : (modelId.intValue() & 0xFFFF);
        }

        @Override
        public String toString() {
            return "Decoded{modelId=" + (modelId == null ? "null"
                    : String.format(Locale.ROOT, "0x%04x", modelId.intValue()))
                    + ", newFormat=" + newFormat
                    + ", rawProductType=" + rawProductType + "}";
        }
    }

    /**
     * @param payload the manufacturer-specific data for a DJI company id, company id already stripped
     *                (which is what {@code ScanRecord.getManufacturerSpecificData()} hands back).
     * @return the classic model id, or null when neither format yields one.
     */
    public static Decoded decode(byte[] payload) {
        if (payload == null) {
            payload = new byte[0];
        }
        Integer pt = productType(payload);
        if (pt != null) {
            Integer mapped = PRODUCT_TYPE_TO_MODEL_ID.get(pt);
            // A product type we can read but haven't mapped is still worth reporting as new-format:
            // the classic byte is zero on these, so falling back would only ever say "unknown(0x0000)"
            // and lose the one number that identifies the camera.
            if (mapped != null || legacyModelId(payload) == null) {
                return new Decoded(mapped, true, pt);
            }
        }
        return new Decoded(legacyModelId(payload), false, pt);
    }

    /** Convenience for callers that only want the id. */
    public static Integer modelId(byte[] payload) {
        return decode(payload).modelId;
    }

    /** The 16-bit product type, or null when this advert doesn't carry one. */
    private static Integer productType(byte[] payload) {
        if (payload.length <= NEW_FORMAT_FLAG_INDEX) {
            return null;
        }
        if ((payload[NEW_FORMAT_FLAG_INDEX] & NEW_FORMAT_FLAG_MASK) == 0) {
            return null;
        }
        if (payload.length < PRODUCT_TYPE_INDEX + 2) {
            return null;
        }
        return Integer.valueOf((payload[PRODUCT_TYPE_INDEX] & 0xFF)
                | ((payload[PRODUCT_TYPE_INDEX + 1] & 0xFF) << 8));
    }

    /**
     * The classic id: payload bytes [0:2] little-endian.
     *
     * <p>Only the low byte actually varies; we keep the 16-bit read the Kotlin app has always used,
     * which agrees on every camera seen so far (byte 1 is zero on all of them) and avoids churning
     * ids that are hardware-verified. Zero is not a model — it is what the new format leaves behind.
     */
    private static Integer legacyModelId(byte[] payload) {
        if (payload.length < 2) {
            return null;
        }
        int id = (payload[0] & 0xFF) | ((payload[1] & 0xFF) << 8);
        return id == 0 ? null : Integer.valueOf(id);
    }

    // ---- raw advert wrapper ----------------------------------------------------------------------

    /**
     * One scan hit: the manufacturer data plus the two fields the advert alone cannot supply, the
     * advertised name and the RSSI. The Kotlin side kept these three apart (the scanner held the name
     * and RSSI and passed {@code BleAdvert.decode} a bare byte array); bundling them keeps the
     * device-name fallback and the log formatting out of the scanner.
     */
    public static final class Advert {

        /** Company id the manufacturer data arrived under; {@link #NO_COMPANY_ID} when there was none. */
        public final int companyId;
        /** The manufacturer-specific data, company id stripped. Empty when the advert carried none. */
        public final byte[] manufacturerData;
        /** Advertised (or cached) device name; may be null. */
        public final String name;
        /** RSSI in dBm. */
        public final int rssi;
        /** The decoded model, never null. */
        public final Decoded decoded;

        Advert(int companyId, byte[] manufacturerData, String name, int rssi, Decoded decoded) {
            this.companyId = companyId;
            this.manufacturerData = manufacturerData;
            this.name = name;
            this.rssi = rssi;
            this.decoded = decoded;
        }

        /** True when the advert carried a DJI company id, i.e. the strongest "this is a DJI" tell. */
        public boolean hasDjiManufacturerData() {
            return BleConstants.isDjiCompanyId(companyId);
        }

        public Integer modelId() {
            return decoded.modelId;
        }

        public int modelIdOrZero() {
            return decoded.modelIdOrZero();
        }

        public String modelGuess() {
            return decoded.modelGuess();
        }

        /** The reference scanner's {@code mfr[cid=08aa 000000…]} suffix, or null when there is none. */
        public String manufacturerHex() {
            if (manufacturerData == null || manufacturerData.length == 0) {
                return null;
            }
            return String.format(Locale.ROOT, "cid=%04x %s",
                    companyId & 0xFFFF, BleConstants.hex(manufacturerData));
        }

        /** {@code name=… model=… mfr[…]}, the tail of the reference scanner's HIT line. */
        public String describe() {
            String mfr = manufacturerHex();
            return "name=" + (name == null ? "?" : name)
                    + " model=" + (modelGuess() == null ? "?" : modelGuess())
                    + (mfr == null ? "" : "  mfr[" + mfr + "]");
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /** {@link Advert#companyId} when the advert carried no manufacturer data at all. */
    public static final int NO_COMPANY_ID = 0;

    /** Build an {@link Advert} from a matched DJI company id, its payload, the name and the RSSI. */
    public static Advert of(int companyId, byte[] manufacturerData, String deviceName, int rssi) {
        byte[] data = manufacturerData == null ? new byte[0] : manufacturerData;
        return new Advert(companyId, data, deviceName, rssi, decode(data));
    }

    /**
     * {@link #of(int, byte[], String, int)} for an advert with no usable manufacturer data — the
     * Osmo Pocket 3 broadcasts none, so it can only ever be recognised by its BLE name.
     */
    public static Advert of(byte[] manufacturerData, String deviceName, int rssi) {
        return of(NO_COMPANY_ID, manufacturerData, deviceName, rssi);
    }
}
