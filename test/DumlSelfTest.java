import java.util.Arrays;

import com.watch.osmo.duml.Bytes;
import com.watch.osmo.duml.Crc;
import com.watch.osmo.duml.DumlFrame;
import com.watch.osmo.duml.OsmoCommands;

/**
 * Desktop self-test for the com.watch.osmo.duml port: every assertion from the four Kotlin tests
 * (DjiCrcTest, DjiMessageTest, SessionCommandsTest, FileOpPayloadTest), without JUnit.
 *
 * <p>Run: javac -encoding UTF-8 -d &lt;tmp&gt; ...java ; java -cp &lt;tmp&gt; DumlSelfTest
 * Exits 1 if anything failed.
 */
public final class DumlSelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // ---- DjiCrcTest ---------------------------------------------------------------------------
        crc8CrossVerify();
        crc16CrossVerify();
        crc8KnownValue();
        pairMessageStructure();

        // ---- DjiMessageTest -----------------------------------------------------------------------
        encodeDecodeRoundtrip();
        typeAccessorsDecomposeCorrectly();
        headerCrcMismatchThrows();

        // ---- SessionCommandsTest ------------------------------------------------------------------
        sessionPingTargetsSessionNotCamera();
        keepaliveUsesSameTarget();
        session5310Targets1CNotCamera();
        wifiCommandsStillAddressWifiSubsystem();

        // ---- FileOpPayloadTest --------------------------------------------------------------------
        deleteMatchesMimoByteForByte();
        deleteCounterIsNotHandleCount();
        trailingSelectorIdenticalOnBothStores();
        favouriteMatchesMimoByteForByte();
        unfavouritingFlipsExactlyOneByte();

        // ---- registration / camera payload shapes -------------------------------------------------
        appDeviceInfoShape();

        System.out.println();
        System.out.println("SUMMARY: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ================= framework =================================================================

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name + "   " + detail);
        }
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

    private static void assertEquals(String name, byte expected, byte actual) {
        assertEquals(name, expected & 0xFF, actual & 0xFF);
    }

    private static void assertArrayEquals(String name, byte[] expected, byte[] actual) {
        check(name, Arrays.equals(expected, actual),
                "expected=" + Bytes.hex(expected) + " actual=" + Bytes.hex(actual));
    }

    private static void assertNotEquals(String name, String unexpected, String actual) {
        check(name, !unexpected.equals(actual), "both were " + actual);
    }

    private static void assertThrows(String name, Class<? extends Throwable> type, Runnable body) {
        try {
            body.run();
            check(name, false, "no exception thrown, expected " + type.getName());
        } catch (Throwable t) {
            check(name, type.isInstance(t), "threw " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private static String hex(byte[] b) {
        return Bytes.hex(b);
    }

    private static String tail(String s, int n) {
        return s.substring(s.length() - n);
    }

    // ================= DjiCrcTest ================================================================

    private static final int[] CRC8_TABLE = {
            0x00, 0x5E, 0xBC, 0xE2, 0x61, 0x3F, 0xDD, 0x83,
            0xC2, 0x9C, 0x7E, 0x20, 0xA3, 0xFD, 0x1F, 0x41,
            0x9D, 0xC3, 0x21, 0x7F, 0xFC, 0xA2, 0x40, 0x1E,
            0x5F, 0x01, 0xE3, 0xBD, 0x3E, 0x60, 0x82, 0xDC,
            0x23, 0x7D, 0x9F, 0xC1, 0x42, 0x1C, 0xFE, 0xA0,
            0xE1, 0xBF, 0x5D, 0x03, 0x80, 0xDE, 0x3C, 0x62,
            0xBE, 0xE0, 0x02, 0x5C, 0xDF, 0x81, 0x63, 0x3D,
            0x7C, 0x22, 0xC0, 0x9E, 0x1D, 0x43, 0xA1, 0xFF,
            0x46, 0x18, 0xFA, 0xA4, 0x27, 0x79, 0x9B, 0xC5,
            0x84, 0xDA, 0x38, 0x66, 0xE5, 0xBB, 0x59, 0x07,
            0xDB, 0x85, 0x67, 0x39, 0xBA, 0xE4, 0x06, 0x58,
            0x19, 0x47, 0xA5, 0xFB, 0x78, 0x26, 0xC4, 0x9A,
            0x65, 0x3B, 0xD9, 0x87, 0x04, 0x5A, 0xB8, 0xE6,
            0xA7, 0xF9, 0x1B, 0x45, 0xC6, 0x98, 0x7A, 0x24,
            0xF8, 0xA6, 0x44, 0x1A, 0x99, 0xC7, 0x25, 0x7B,
            0x3A, 0x64, 0x86, 0xD8, 0x5B, 0x05, 0xE7, 0xB9,
            0x8C, 0xD2, 0x30, 0x6E, 0xED, 0xB3, 0x51, 0x0F,
            0x4E, 0x10, 0xF2, 0xAC, 0x2F, 0x71, 0x93, 0xCD,
            0x11, 0x4F, 0xAD, 0xF3, 0x70, 0x2E, 0xCC, 0x92,
            0xD3, 0x8D, 0x6F, 0x31, 0xB2, 0xEC, 0x0E, 0x50,
            0xAF, 0xF1, 0x13, 0x4D, 0xCE, 0x90, 0x72, 0x2C,
            0x6D, 0x33, 0xD1, 0x8F, 0x0C, 0x52, 0xB0, 0xEE,
            0x32, 0x6C, 0x8E, 0xD0, 0x53, 0x0D, 0xEF, 0xB1,
            0xF0, 0xAE, 0x4C, 0x12, 0x91, 0xCF, 0x2D, 0x73,
            0xCA, 0x94, 0x76, 0x28, 0xAB, 0xF5, 0x17, 0x49,
            0x08, 0x56, 0xB4, 0xEA, 0x69, 0x37, 0xD5, 0x8B,
            0x57, 0x09, 0xEB, 0xB5, 0x36, 0x68, 0x8A, 0xD4,
            0x95, 0xCB, 0x29, 0x77, 0xF4, 0xAA, 0x48, 0x16,
            0xE9, 0xB7, 0x55, 0x0B, 0x88, 0xD6, 0x34, 0x6A,
            0x2B, 0x75, 0x97, 0xC9, 0x4A, 0x14, 0xF6, 0xA8,
            0x74, 0x2A, 0xC8, 0x96, 0x15, 0x4B, 0xA9, 0xF7,
            0xB6, 0xE8, 0x0A, 0x54, 0xD7, 0x89, 0x6B, 0x35,
    };

    private static final int[] CRC16_TABLE = {
            0x0000, 0x1189, 0x2312, 0x329B, 0x4624, 0x57AD, 0x6536, 0x74BF,
            0x8C48, 0x9DC1, 0xAF5A, 0xBED3, 0xCA6C, 0xDBE5, 0xE97E, 0xF8F7,
            0x1081, 0x0108, 0x3393, 0x221A, 0x56A5, 0x472C, 0x75B7, 0x643E,
            0x9CC9, 0x8D40, 0xBFDB, 0xAE52, 0xDAED, 0xCB64, 0xF9FF, 0xE876,
            0x2102, 0x308B, 0x0210, 0x1399, 0x6726, 0x76AF, 0x4434, 0x55BD,
            0xAD4A, 0xBCC3, 0x8E58, 0x9FD1, 0xEB6E, 0xFAE7, 0xC87C, 0xD9F5,
            0x3183, 0x200A, 0x1291, 0x0318, 0x77A7, 0x662E, 0x54B5, 0x453C,
            0xBDCB, 0xAC42, 0x9ED9, 0x8F50, 0xFBEF, 0xEA66, 0xD8FD, 0xC974,
            0x4204, 0x538D, 0x6116, 0x709F, 0x0420, 0x15A9, 0x2732, 0x36BB,
            0xCE4C, 0xDFC5, 0xED5E, 0xFCD7, 0x8868, 0x99E1, 0xAB7A, 0xBAF3,
            0x5285, 0x430C, 0x7197, 0x601E, 0x14A1, 0x0528, 0x37B3, 0x263A,
            0xDECD, 0xCF44, 0xFDDF, 0xEC56, 0x98E9, 0x8960, 0xBBFB, 0xAA72,
            0x6306, 0x728F, 0x4014, 0x519D, 0x2522, 0x34AB, 0x0630, 0x17B9,
            0xEF4E, 0xFEC7, 0xCC5C, 0xDDD5, 0xA96A, 0xB8E3, 0x8A78, 0x9BF1,
            0x7387, 0x620E, 0x5095, 0x411C, 0x35A3, 0x242A, 0x16B1, 0x0738,
            0xFFCF, 0xEE46, 0xDCDD, 0xCD54, 0xB9EB, 0xA862, 0x9AF9, 0x8B70,
            0x8408, 0x9581, 0xA71A, 0xB693, 0xC22C, 0xD3A5, 0xE13E, 0xF0B7,
            0x0840, 0x19C9, 0x2B52, 0x3ADB, 0x4E64, 0x5FED, 0x6D76, 0x7CFF,
            0x9489, 0x8500, 0xB79B, 0xA612, 0xD2AD, 0xC324, 0xF1BF, 0xE036,
            0x18C1, 0x0948, 0x3BD3, 0x2A5A, 0x5EE5, 0x4F6C, 0x7DF7, 0x6C7E,
            0xA50A, 0xB483, 0x8618, 0x9791, 0xE32E, 0xF2A7, 0xC03C, 0xD1B5,
            0x2942, 0x38CB, 0x0A50, 0x1BD9, 0x6F66, 0x7EEF, 0x4C74, 0x5DFD,
            0xB58B, 0xA402, 0x9699, 0x8710, 0xF3AF, 0xE226, 0xD0BD, 0xC134,
            0x39C3, 0x284A, 0x1AD1, 0x0B58, 0x7FE7, 0x6E6E, 0x5CF5, 0x4D7C,
            0xC60C, 0xD785, 0xE51E, 0xF497, 0x8028, 0x91A1, 0xA33A, 0xB2B3,
            0x4A44, 0x5BCD, 0x6956, 0x78DF, 0x0C60, 0x1DE9, 0x2F72, 0x3EFB,
            0xD68D, 0xC704, 0xF59F, 0xE416, 0x90A9, 0x8120, 0xB3BB, 0xA232,
            0x5AC5, 0x4B4C, 0x79D7, 0x685E, 0x1CE1, 0x0D68, 0x3FF3, 0x2E7A,
            0xE70E, 0xF687, 0xC41C, 0xD595, 0xA12A, 0xB0A3, 0x8238, 0x93B1,
            0x6B46, 0x7ACF, 0x4854, 0x59DD, 0x2D62, 0x3CEB, 0x0E70, 0x1FF9,
            0xF78F, 0xE606, 0xD49D, 0xC514, 0xB1AB, 0xA022, 0x92B9, 0x8330,
            0x7BC7, 0x6A4E, 0x58D5, 0x495C, 0x3DE3, 0x2C6A, 0x1EF1, 0x0F78,
    };

    private static int tableCrc8(byte[] data) {
        int crc = 0x77;
        for (int i = 0; i < data.length; i++) {
            crc = CRC8_TABLE[(crc ^ (data[i] & 0xFF)) & 0xFF];
        }
        return crc;
    }

    private static int tableCrc16(byte[] data) {
        int crc = 0x3692;
        for (int i = 0; i < data.length; i++) {
            crc = (crc >>> 8) ^ CRC16_TABLE[(crc ^ (data[i] & 0xFF)) & 0xFF];
        }
        return crc & 0xFFFF;
    }

    private static void crc8CrossVerify() {
        byte[][] vectors = new byte[][] {
                new byte[] { 0x55, 0x33, 0x04 },
                new byte[] { 0x55, 0x0D, 0x04 },
                new byte[] { 0x00 },
                new byte[] { (byte) 0xFF },
                new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05 },
                allBytes(),
        };
        for (int i = 0; i < vectors.length; i++) {
            assertEquals("DjiCrcTest.crc8CrossVerify[vector " + i + "]",
                    tableCrc8(vectors[i]), Crc.crc8(vectors[i]));
        }
    }

    private static void crc16CrossVerify() {
        byte[][] vectors = new byte[][] {
                new byte[] { 0x55, 0x0D, 0x04, 0x69, 0x02, 0x08, 0x2C, (byte) 0x8C, 0x40, 0x08, 0x78 },
                new byte[] { 0x00 },
                new byte[] { (byte) 0xFF },
                allBytes(),
        };
        for (int i = 0; i < vectors.length; i++) {
            assertEquals("DjiCrcTest.crc16CrossVerify[vector " + i + "]",
                    tableCrc16(vectors[i]), Crc.crc16(vectors[i]));
        }
    }

    private static byte[] allBytes() {
        byte[] b = new byte[256];
        for (int i = 0; i < 256; i++) b[i] = (byte) i;
        return b;
    }

    private static void crc8KnownValue() {
        assertEquals("DjiCrcTest.crc8KnownValue", 0xC2,
                Crc.crc8(new byte[] { 0x55, 0x33, 0x04 }));
    }

    private static void pairMessageStructure() {
        byte[] pairPayload = OsmoCommands.pairMessagePayload("osmo");
        DumlFrame msg = new DumlFrame(0x0702, 0x8092, 0x450740, pairPayload);
        byte[] bytes = msg.encode();
        assertEquals("DjiCrcTest.pairMessageStructure.size", 51, bytes.length);
        assertEquals("DjiCrcTest.pairMessageStructure.byte0", (byte) 0x55, bytes[0]);
        assertEquals("DjiCrcTest.pairMessageStructure.byte1", 51, bytes[1] & 0xFF);
        assertEquals("DjiCrcTest.pairMessageStructure.byte2", (byte) 0x04, bytes[2]);

        DumlFrame decoded = DumlFrame.decode(bytes);
        assertEquals("DjiCrcTest.pairMessageStructure.target", 0x0702, decoded.target);
        assertEquals("DjiCrcTest.pairMessageStructure.id", 0x8092, decoded.id);
        assertEquals("DjiCrcTest.pairMessageStructure.type", 0x450740, decoded.type);
        assertEquals("DjiCrcTest.pairMessageStructure.cmdSet", 0x07, decoded.cmdSet());
        assertEquals("DjiCrcTest.pairMessageStructure.cmdId", 0x45, decoded.cmdId());
        assertArrayEquals("DjiCrcTest.pairMessageStructure.payload", pairPayload, decoded.payload);

        // The packstring itself: [len=0x20][32 hex chars][len=0x04]["osmo"].
        assertArrayEquals("DjiCrcTest.pairMessageStructure.packStringDefaultIdentifier",
                OsmoCommands.PairMessagePayload.identifierBlob,
                Bytes.packString(OsmoCommands.PairMessagePayload.DEFAULT_IDENTIFIER));
        assertArrayEquals("DjiCrcTest.pairMessageStructure.payloadBytes",
                Bytes.fromHex("203238346165356238643736623333373561303461363431376164373162656133046f736d6f"),
                pairPayload);
    }

    // ================= DjiMessageTest ============================================================

    private static void encodeDecodeRoundtrip() {
        byte[] payload = new byte[] { 0x01, 0x02, 0x03 };
        DumlFrame msg = new DumlFrame(0x0702, 0x1234, 0x112233, payload);
        DumlFrame decoded = DumlFrame.decode(msg.encode());
        assertEquals("DjiMessageTest.encodeDecodeRoundtrip.target", msg.target, decoded.target);
        assertEquals("DjiMessageTest.encodeDecodeRoundtrip.id", msg.id, decoded.id);
        assertEquals("DjiMessageTest.encodeDecodeRoundtrip.type", msg.type, decoded.type);
        assertArrayEquals("DjiMessageTest.encodeDecodeRoundtrip.payload", msg.payload, decoded.payload);
    }

    private static void typeAccessorsDecomposeCorrectly() {
        // type 0x450740 (LE bytes 40 07 45) => flags=0x40, cmdSet=0x07, cmdId=0x45
        DumlFrame msg = new DumlFrame(0x0702, 1, 0x450740, new byte[0]);
        assertEquals("DjiMessageTest.typeAccessors.flags", 0x40, msg.flags());
        assertEquals("DjiMessageTest.typeAccessors.cmdSet", 0x07, msg.cmdSet());
        assertEquals("DjiMessageTest.typeAccessors.cmdId", 0x45, msg.cmdId());
    }

    private static void headerCrcMismatchThrows() {
        DumlFrame msg = new DumlFrame(1, 2, 3, new byte[] { 0x01 });
        final byte[] encoded = msg.encode();
        encoded[2] = 0x05; // corrupt version/len-hi byte
        assertThrows("DjiMessageTest.headerCrcMismatchThrows", IllegalArgumentException.class,
                new Runnable() {
                    @Override
                    public void run() {
                        DumlFrame.decode(encoded);
                    }
                });
    }

    // ================= SessionCommandsTest =======================================================

    private static int sender(byte[] f) {
        return f[4] & 0xFF;
    }

    private static int receiver(byte[] f) {
        return f[5] & 0xFF;
    }

    private static int cmdSet(byte[] f) {
        return f[9] & 0xFF;
    }

    private static int cmdId(byte[] f) {
        return f[10] & 0xFF;
    }

    private static byte[] payloadOf(byte[] f) {
        return Arrays.copyOfRange(f, 11, f.length - 2);
    }

    private static String payloadList(byte[] f) {
        byte[] p = payloadOf(f);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(p[i] & 0xFF);
        }
        return sb.toString();
    }

    private static void sessionPingTargetsSessionNotCamera() {
        byte[] f = OsmoCommands.sessionPing(OsmoCommands.SESSION_WAKE);
        assertEquals("SessionCommandsTest.sessionPing.sender", 0x02, sender(f));
        assertEquals("SessionCommandsTest.sessionPing.receiver (must be 0xF0; 0x01 gets rejected with e0)",
                0xF0, receiver(f));
        assertEquals("SessionCommandsTest.sessionPing.cmdSet", 0x00, cmdSet(f));
        assertEquals("SessionCommandsTest.sessionPing.cmdId", 0x2B, cmdId(f));
        assertEquals("SessionCommandsTest.sessionPing.receiverType", 0x10, receiver(f) & 0x1F);
        assertEquals("SessionCommandsTest.sessionPing.receiverId", 7, receiver(f) >>> 5);
        assertEquals("SessionCommandsTest.sessionPing.payload", "4, 0", payloadList(f));
    }

    private static void keepaliveUsesSameTarget() {
        byte[] f = OsmoCommands.sessionPing(OsmoCommands.SESSION_KEEPALIVE);
        assertEquals("SessionCommandsTest.keepalive.receiver", 0xF0, receiver(f));
        assertEquals("SessionCommandsTest.keepalive.payload", "1, 1", payloadList(f));
    }

    private static void session5310Targets1CNotCamera() {
        byte[] f = OsmoCommands.session5310();
        assertEquals("SessionCommandsTest.session5310.sender", 0x02, sender(f));
        assertEquals("SessionCommandsTest.session5310.receiver (must be 0x1C; 0x01 gets rejected with e0)",
                0x1C, receiver(f));
        assertEquals("SessionCommandsTest.session5310.cmdSet", 0x53, cmdSet(f));
        assertEquals("SessionCommandsTest.session5310.cmdId", 0x10, cmdId(f));
        assertEquals("SessionCommandsTest.session5310.receiverType", 0x1C, receiver(f) & 0x1F);
        assertEquals("SessionCommandsTest.session5310.receiverId", 0, receiver(f) >>> 5);
        assertEquals("SessionCommandsTest.session5310.payload", "0, 0, 0, 0", payloadList(f));
    }

    private static void wifiCommandsStillAddressWifiSubsystem() {
        byte[] f = OsmoCommands.wifiQuery(0x07);
        assertEquals("SessionCommandsTest.wifiQuery.receiver", 0x07, receiver(f));
        assertEquals("SessionCommandsTest.wifiQuery.cmdSet", 0x07, cmdSet(f));
        assertEquals("SessionCommandsTest.wifiQuery.cmdId", 0x07, cmdId(f));
    }

    // ================= FileOpPayloadTest ========================================================

    private static void deleteMatchesMimoByteForByte() {
        // Two consecutive deletes in one Mimo session. The count stays 01; the field after the handle
        // goes 01 then 02.
        assertEquals("FileOpPayloadTest.delete[handle 0x40105200, counter 1]",
                "010052104001000000000100000001010000",
                hex(OsmoCommands.deletePayload(new long[] { 0x40105200L }, 1)));
        assertEquals("FileOpPayloadTest.delete[handle 0x40105240, counter 2]",
                "014052104002000000000100000001010000",
                hex(OsmoCommands.deletePayload(new long[] { 0x40105240L }, 2)));
    }

    private static void deleteCounterIsNotHandleCount() {
        // The regression this guards: sending the count where the counter belongs.
        assertNotEquals("FileOpPayloadTest.deleteCounterIsNotHandleCount",
                hex(OsmoCommands.deletePayload(new long[] { 0x40105200L }, 1)),
                hex(OsmoCommands.deletePayload(new long[] { 0x40105200L }, 2)));
    }

    private static void trailingSelectorIdenticalOnBothStores() {
        assertEquals("FileOpPayloadTest.trailingSelector[handle 0x400403C0, counter 1]",
                "01010000", tail(hex(OsmoCommands.deletePayload(new long[] { 0x400403C0L }, 1)), 8));
        assertEquals("FileOpPayloadTest.trailingSelector[handle 0x40105200, counter 9]",
                "01010000", tail(hex(OsmoCommands.deletePayload(new long[] { 0x40105200L }, 9)), 8));
    }

    private static void favouriteMatchesMimoByteForByte() {
        assertEquals("FileOpPayloadTest.favourite[handle 0x40104040, counter 1, on]",
                "010140401040010000000001000000",
                hex(OsmoCommands.favoritePayload(0x40104040L, 1, true)));
        assertEquals("FileOpPayloadTest.favourite[handle 0x40103fc0, counter 2, on]",
                "0101c03f1040020000000001000000",
                hex(OsmoCommands.favoritePayload(0x40103fc0L, 2, true)));
    }

    private static void unfavouritingFlipsExactlyOneByte() {
        String on = hex(OsmoCommands.favoritePayload(0x40104040L, 1, true));
        String off = hex(OsmoCommands.favoritePayload(0x40104040L, 1, false));
        assertEquals("FileOpPayloadTest.unfavourite.length", on.length(), off.length());
        int differing = 0;
        for (int i = 0; i < on.length(); i++) {
            if (on.charAt(i) != off.charAt(i)) differing++;
        }
        assertEquals("FileOpPayloadTest.unfavourite.only the flag differs", 1, differing);
    }

    // ================= registration payload shape ================================================

    /**
     * Not from a Kotlin test — there is none — but the shape is pinned by a comment in the Kotlin
     * source itself ("62 bytes ... mirrors file_list.py"), so it is worth locking down: a BOM or any
     * non-ASCII byte creeping into the source shifts every index after {@code b[1]} and silently
     * grows the frame, which is exactly the kind of change that looks harmless and is not.
     */
    private static void appDeviceInfoShape() {
        byte[] b = OsmoCommands.appDeviceInfo();
        assertEquals("OsmoCommands.appDeviceInfo.length", 62, b.length);
        assertEquals("OsmoCommands.appDeviceInfo[0] (must be 0x00)", 0x00, b[0] & 0xFF);
        assertEquals("OsmoCommands.appDeviceInfo[1] ('A')", 'A', b[1] & 0xFF);
        assertEquals("OsmoCommands.appDeviceInfo[2] ('P')", 'P', b[2] & 0xFF);
        assertEquals("OsmoCommands.appDeviceInfo[3] ('P')", 'P', b[3] & 0xFF);
        assertEquals("OsmoCommands.appDeviceInfo[41]", 0x02, b[41] & 0xFF);
        assertEquals("OsmoCommands.appDeviceInfo[50]", 0x02, b[50] & 0xFF);
        assertEquals("OsmoCommands.appDeviceInfo[51]", 0x08, b[51] & 0xFF);

        // The 0x00/0x81 frame is 0x55, len=13+62=75, 0x04, crc8, target, id, type, payload, crc16.
        byte[] frame = OsmoCommands.deviceInfo();
        assertEquals("OsmoCommands.deviceInfo.frameLength", 75, frame.length);
        assertEquals("OsmoCommands.deviceInfo.lenByte", 75, frame[1] & 0xFF);
        DumlFrame d = DumlFrame.decode(frame);
        assertEquals("OsmoCommands.deviceInfo.target", OsmoCommands.TARGET_APP_TO_SYSTEM, d.target);
        assertEquals("OsmoCommands.deviceInfo.cmdSet", 0x00, d.cmdSet());
        assertEquals("OsmoCommands.deviceInfo.cmdId", 0x81, d.cmdId());
        assertEquals("OsmoCommands.deviceInfo.flags (cmdType 4 => 0x80)", 0x80, d.flags());
        assertArrayEquals("OsmoCommands.deviceInfo.payload", b, d.payload);
    }
}
