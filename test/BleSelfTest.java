import com.watch.osmo.ble.BleAdvert;
import com.watch.osmo.ble.BleConstants;

/**
 * Desktop self-test for the com.watch.osmo.ble port.
 *
 * <p>Every assertion from {@code BleAdvertTest.kt} (osmosis, MIT) is reproduced here, byte for byte
 * against the same real captured advertisements, without JUnit. The three assertions that also need
 * {@code CameraModel.resolve} (datalink port / tcpPoke / singleSdStorage) are NOT ported: the watch
 * app's camera-profile layer is a different class and is not part of the BLE layer.
 *
 * <p>Run:
 * <pre>
 *   javac -encoding UTF-8 -nowarn -d &lt;tmp&gt; BleConstants.java BleAdvert.java BleSelfTest.java
 *   java -cp &lt;tmp&gt; BleSelfTest
 * </pre>
 * Exits 1 if anything failed. Needs no android.jar: {@link BleAdvert} and {@link BleConstants} are
 * Android-free on purpose.
 */
public final class BleSelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // ---- the real captures, dumped so the run shows what was actually parsed ------------------
        System.out.println("=== real captures (from BleAdvertTest.kt) ===");
        dump("OsmoPocket4P-6E55  0x08AA", pocket4P);
        dump("Osmo Pocket 4      0x08AA", pocket4);
        dump("OsmoNano-C2D8      0x08AA", nano);
        dump("OsmoAction4-ABCD   0x08AA", action4);
        System.out.println();

        // ---- BleAdvertTest.kt ---------------------------------------------------------------------
        pocket4PResolvesThroughTheNewFormat();
        pocket4PIsNamed();
        nanoStillResolvesThroughTheClassicFormat();
        shortPayloadWithTheFlagBitSetFallsBackToTheClassicId();
        unknownProductTypeIsReportedRatherThanSwallowed();
        allZeroesIsNotAModel();
        pocket4ResolvesByIdOnARenamedCamera();
        pocket4AndPocket4PAreTheSameLengthButDifferentFormats();
        action4ResolvesThroughTheClassicFormat();
        thirdCompanyIdIsAccepted();

        // ---- additions: the raw-advert wrapper and the flag's load-bearing bit --------------------
        flagBitIsLoadBearing();
        advertWrapperCarriesNameAndRssi();
        advertWrapperWithoutManufacturerData();

        System.out.println();
        System.out.println("SUMMARY: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ================= real captured advertisements ==============================================
    // Verbatim from reference/.../app/src/test/java/dev/konraditurbe/osmosis/ble/BleAdvertTest.kt.
    // The Pocket 4P payload is from a tester's scan on 2026-08-07 (OsmoPocket4P-6E55,
    // 9C:5A:8A:BD:6E:56, company id 0x08AA) — the advert that scanned as model=unknown(0x0000) and
    // started all this.

    /** Classic bytes are zero; the id lives at [10:12]. */
    private static final byte[] pocket4P = hex("000000ee0004bd6e5620da000010");

    /**
     * A plain Osmo Pocket 4, tester's scan 2026-08-08. Same payload *length* as its Pro sibling, but
     * the classic format: model id populated, new-format flag clear.
     *
     * <pre>
     * OP4   21 00 00 be 00 00 ee 8d d9 a0 00 00 00 00   classic 0x0021, flag clear
     * OP4P  00 00 00 ee 00 04 bd 6e 56 20 da 00 00 10   classic 0,      flag set -> 218
     * </pre>
     */
    private static final byte[] pocket4 = hex("210000be0000ee8dd9a000000000");

    /** Nano shape: model 0x0019 at [0:2], then MAC, then a trailing byte. */
    private static final byte[] nano = hex("190000c25a8abdc2d803");

    /**
     * Osmo Action 4, tester's scan 2026-08-24. Byte 2 is {@code 0xfa} where the Nano's is
     * {@code 0x00}, which is the point of keeping it: the classic id is a u16 LE at [0:2], so anything
     * that widened the read past two bytes would resolve this as garbage rather than as an Action 4.
     */
    private static final byte[] action4 = hex("1400faaabbccddeeff");

    // ================= BleAdvertTest.kt ==========================================================

    private static void pocket4PResolvesThroughTheNewFormat() {
        BleAdvert.Decoded d = BleAdvert.decode(pocket4P);
        assertTrue("BleAdvertTest.pocket4P.newFormat (flag bit at payload[5] selects the new format)",
                d.newFormat);
        assertEquals("BleAdvertTest.pocket4P.rawProductType (LE u16 at payload[10:12])",
                218, d.rawProductType);
        assertEquals("BleAdvertTest.pocket4P.modelId (HG224 maps to classic id 0x22)",
                0x0022, d.modelId);
    }

    private static void pocket4PIsNamed() {
        Integer id = BleAdvert.modelId(pocket4P);
        assertEquals("BleAdvertTest.pocket4P.nameIsOsmoPocket4P", "OsmoPocket4P",
                BleConstants.modelName(id));
        assertEquals("BleConstants.MODEL_NAMES[0x0022]", "OsmoPocket4P",
                BleConstants.modelName(0x0022));
        assertEquals("BleConstants.describeModel(0x0022)", "OsmoPocket4P",
                BleConstants.describeModel(0x0022));
    }

    /** The classic path must not regress: byte 1 is zero on every camera seen, so the u16 read holds. */
    private static void nanoStillResolvesThroughTheClassicFormat() {
        BleAdvert.Decoded d = BleAdvert.decode(nano);
        assertFalse("BleAdvertTest.nano.newFormat", d.newFormat);
        assertEquals("BleAdvertTest.nano.modelId", 0x0019, d.modelId);
        assertEquals("BleAdvertTest.nano.nameIsOsmoNano", "OsmoNano", BleConstants.modelName(d.modelId));
    }

    /**
     * The classic id sits where the new format's MAC does, so a legacy advert can trip the flag bit by
     * accident. It is length that saves us — [BleAdvert] only reads a product type when the payload is
     * long enough to actually hold one, and otherwise falls back.
     */
    private static void shortPayloadWithTheFlagBitSetFallsBackToTheClassicId() {
        BleAdvert.Decoded d = BleAdvert.decode(hex("1900000405"));   // payload[5] absent entirely
        assertFalse("BleAdvertTest.short[5 bytes].newFormat", d.newFormat);
        assertEquals("BleAdvertTest.short[5 bytes].modelId", 0x0019, d.modelId);

        BleAdvert.Decoded d2 = BleAdvert.decode(hex("190000040504")); // flag set at [5], no room for [10:12]
        assertFalse("BleAdvertTest.short[6 bytes].newFormat", d2.newFormat);
        assertNull("BleAdvertTest.short[6 bytes].rawProductType (no room for [10:12])",
                d2.rawProductType);
        assertEquals("BleAdvertTest.short[6 bytes].modelId", 0x0019, d2.modelId);
    }

    /** An unmapped product type still reports its number — that is how the next model gets named. */
    private static void unknownProductTypeIsReportedRatherThanSwallowed() {
        BleAdvert.Decoded d = BleAdvert.decode(hex("000000ee0004bd6e562099990010"));
        assertTrue("BleAdvertTest.unknownProductType.newFormat", d.newFormat);
        assertEquals("BleAdvertTest.unknownProductType.rawProductType", 0x9999, d.rawProductType);
        assertNull("BleAdvertTest.unknownProductType.modelId", d.modelId);
        assertEquals("BleAdvertTest.unknownProductType.modelGuess", "unknown(productType=39321)",
                d.modelGuess());
    }

    private static void allZeroesIsNotAModel() {
        assertNull("BleAdvertTest.allZeroes.modelId(0000)", BleAdvert.modelId(hex("0000")));
        assertNull("BleAdvertTest.allZeroes.modelId(empty)", BleAdvert.modelId(new byte[0]));
        assertNull("BleAdvertTest.allZeroes.modelId(null)", BleAdvert.modelId(null));
    }

    /**
     * The Pocket 4 resolves by id even though the tester had **renamed** the camera to {@code MEGG-OP4}.
     * Nothing in that name matches the model table, so only the advert's classic id identifies it.
     */
    private static void pocket4ResolvesByIdOnARenamedCamera() {
        BleAdvert.Decoded d = BleAdvert.decode(pocket4);
        assertFalse("BleAdvertTest.pocket4 (MEGG-OP4).newFormat (flag bit at payload[5] is clear)",
                d.newFormat);
        assertEquals("BleAdvertTest.pocket4 (MEGG-OP4).modelId", 0x0021, d.modelId);
        assertEquals("BleAdvertTest.pocket4 (MEGG-OP4).nameIsOsmoPocket4", "OsmoPocket4",
                BleConstants.modelName(d.modelId));
    }

    /** The pair differ only in which field carries the model — length alone can't tell them apart. */
    private static void pocket4AndPocket4PAreTheSameLengthButDifferentFormats() {
        assertEquals("BleAdvertTest.pocket4.length", pocket4P.length, pocket4.length);
        assertEquals("BleAdvertTest.pocket4.modelId", 0x0021, BleAdvert.modelId(pocket4));
        assertEquals("BleAdvertTest.pocket4P.modelId", 0x0022, BleAdvert.modelId(pocket4P));
    }

    private static void action4ResolvesThroughTheClassicFormat() {
        BleAdvert.Decoded d = BleAdvert.decode(action4);
        assertFalse("BleAdvertTest.action4.newFormat (flag bit at payload[5] is clear)", d.newFormat);
        assertEquals("BleAdvertTest.action4.modelId", 0x0014, d.modelId);
        assertEquals("BleAdvertTest.action4.nameIsOsmoAction4", "OsmoAction4",
                BleConstants.modelName(d.modelId));
    }

    private static void thirdCompanyIdIsAccepted() {
        assertTrue("BleAdvertTest.companyId.DJI_COMPANY_ID", BleConstants.isDjiCompanyId(BleConstants.DJI_COMPANY_ID));
        assertTrue("BleAdvertTest.companyId.DJI_COMPANY_ID_ALT", BleConstants.isDjiCompanyId(BleConstants.DJI_COMPANY_ID_ALT));
        assertTrue("BleAdvertTest.companyId.0xE5C0", BleConstants.isDjiCompanyId(0xE5C0));
        assertFalse("BleAdvertTest.companyId.0x004C is Apple, not DJI", BleConstants.isDjiCompanyId(0x004C));
        assertEquals("BleAdvertTest.companyId.wire byte order", 0x08AA, BleConstants.DJI_COMPANY_ID);
        assertEquals("BleAdvertTest.companyId.alt wire byte order", 0xF7AA, BleConstants.DJI_COMPANY_ID_ALT);
    }

    // ================= added coverage =============================================================

    /**
     * Without bit 2 of payload[5] the Pocket 4P's product type is invisible and the advert decodes
     * as "no model at all" (classic byte is zero on these cameras) — proof the flag, not the length,
     * is what selects the format.
     */
    private static void flagBitIsLoadBearing() {
        byte[] withoutFlag = pocket4P.clone();
        withoutFlag[BleAdvert.NEW_FORMAT_FLAG_INDEX] &= (byte) ~BleAdvert.NEW_FORMAT_FLAG_MASK;
        BleAdvert.Decoded d = BleAdvert.decode(withoutFlag);
        assertFalse("BleAdvert.flag.clearFlag.newFormat", d.newFormat);
        assertNull("BleAdvert.flag.clearFlag.rawProductType", d.rawProductType);
        assertNull("BleAdvert.flag.clearFlag.modelId (classic byte is 0 on the new format)",
                d.modelId);
    }

    /** The raw manufacturer data + name + RSSI entry point the scanner uses. */
    private static void advertWrapperCarriesNameAndRssi() {
        BleAdvert.Advert a = BleAdvert.of(BleConstants.DJI_COMPANY_ID, pocket4P,
                "OsmoPocket4P-6E55", -61);
        assertTrue("BleAdvert.of.hasDjiManufacturerData", a.hasDjiManufacturerData());
        assertEquals("BleAdvert.of.companyId", BleConstants.DJI_COMPANY_ID, a.companyId);
        assertEquals("BleAdvert.of.rssi", -61, a.rssi);
        assertEquals("BleAdvert.of.name", "OsmoPocket4P-6E55", a.name);
        assertEquals("BleAdvert.of.modelId", 0x0022, a.modelId());
        assertEquals("BleAdvert.of.modelGuess", "OsmoPocket4P", a.modelGuess());
        assertEquals("BleAdvert.of.manufacturerHex",
                "cid=08aa 000000ee0004bd6e5620da000010", a.manufacturerHex());
        assertEquals("BleAdvert.of.describe",
                "name=OsmoPocket4P-6E55 model=OsmoPocket4P  mfr[cid=08aa 000000ee0004bd6e5620da000010]",
                a.describe());

        // A renamed camera still resolves: only the advert carries the model.
        BleAdvert.Advert renamed = BleAdvert.of(BleConstants.DJI_COMPANY_ID, pocket4, "MEGG-OP4", -70);
        assertEquals("BleAdvert.of(renamed).modelId", 0x0021, renamed.modelId());
        assertEquals("BleAdvert.of(renamed).modelGuess", "OsmoPocket4", renamed.modelGuess());
    }

    /**
     * The Osmo Pocket 3 broadcasts no manufacturer data, so the advert wrapper has to stay usable with
     * an empty payload: model 0, no guess, no mfr hex — the scanner then falls back to the BLE name.
     */
    private static void advertWrapperWithoutManufacturerData() {
        BleAdvert.Advert a = BleAdvert.of(null, "OsmoPocket3-ABCD", -80);
        assertEquals("BleAdvert.of(noMfr).companyId", BleAdvert.NO_COMPANY_ID, a.companyId);
        assertFalse("BleAdvert.of(noMfr).hasDjiManufacturerData", a.hasDjiManufacturerData());
        assertEquals("BleAdvert.of(noMfr).modelIdOrZero", 0, a.modelIdOrZero());
        assertNull("BleAdvert.of(noMfr).modelGuess", a.modelGuess());
        assertNull("BleAdvert.of(noMfr).manufacturerHex", a.manufacturerHex());
        assertEquals("BleAdvert.of(noMfr).describe", "name=OsmoPocket3-ABCD model=?", a.describe());
    }

    // ================= framework =================================================================

    private static void dump(String label, byte[] payload) {
        BleAdvert.Decoded d = BleAdvert.decode(payload);
        System.out.println(String.format("%-22s %-30s newFormat=%-5s productType=%-6s model=%s",
                label, hexStr(payload), String.valueOf(d.newFormat),
                d.rawProductType == null ? "-" : String.valueOf(d.rawProductType),
                d.modelId == null ? "null" : String.format("0x%04x (%s)", d.modelId, d.modelGuess())));
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name + "   " + detail);
        }
    }

    private static void assertTrue(String name, boolean actual) {
        check(name, actual, "expected=true actual=false");
    }

    private static void assertFalse(String name, boolean actual) {
        check(name, !actual, "expected=false actual=true");
    }

    private static void assertEquals(String name, int expected, int actual) {
        check(name, expected == actual,
                "expected=" + expected + " (0x" + Integer.toHexString(expected) + ")"
                        + " actual=" + actual + " (0x" + Integer.toHexString(actual) + ")");
    }

    private static void assertEquals(String name, String expected, String actual) {
        check(name, expected == null ? actual == null : expected.equals(actual),
                "expected=" + expected + " actual=" + actual);
    }

    /** Integer overload; the second argument is expected, third actual. */
    private static void assertEquals(String name, int expected, Integer actual) {
        check(name, actual != null && expected == actual.intValue(),
                "expected=" + expected + " actual=" + actual);
    }

    private static void assertNull(String name, Object actual) {
        check(name, actual == null, "expected=null actual=" + actual);
    }

    /** Hex string, whitespace ignored — the Kotlin test's {@code hex()} helper. */
    private static byte[] hex(String s) {
        String clean = s.replaceAll("\\s", "");
        if ((clean.length() & 1) != 0) {
            throw new IllegalArgumentException("odd hex length: " + s);
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("bad hex digit in " + s);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static String hexStr(byte[] b) {
        return BleConstants.hex(b);
    }
}
