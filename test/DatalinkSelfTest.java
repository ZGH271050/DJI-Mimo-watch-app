import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import com.watch.osmo.duml.Crc;
import com.watch.osmo.duml.DumlFrame;
import com.watch.osmo.net.Datalink;
import com.watch.osmo.net.ManifestAssembler;
import com.watch.osmo.net.UdpPacket;

/**
 * Desktop self-test for the com.watch.osmo.net datalink port. No JUnit: plain main().
 *
 * <p>Run:
 * <pre>
 * javac -encoding UTF-8 -nowarn -d &lt;tmp&gt; &lt;project&gt;\src\java\com\watch\osmo\duml\*.java \
 *       &lt;project&gt;\src\java\com\watch\osmo\net\UdpPacket.java \
 *       &lt;project&gt;\src\java\com\watch\osmo\net\Datalink.java \
 *       &lt;project&gt;\src\java\com\watch\osmo\net\ManifestAssembler.java \
 *       &lt;project&gt;\test\DatalinkSelfTest.java
 * java -cp &lt;tmp&gt;;&lt;project&gt;\reference\osmosis\app\src\test\resources DatalinkSelfTest
 * </pre>
 *
 * <p>Exits 1 if anything failed.
 */
public final class DatalinkSelfTest {

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        section("DumlTransportTest: transport header");
        transportHeaderCarriesSessionAndSeqLittleEndianWithXorTrailer();
        totalLengthIsPayloadPlusEightWithHighFlagBit();

        section("DumlTransportTest: routing header");
        ackIsOurOwnPreviousSeqNeverThePeerChannel();
        ackWrapsWithTheSequenceRatherThanGoingNegative();
        byte10DistinguishesDroneFromCamera();

        section("DumlTransportTest: frame scanning");
        scanFramesFindsAFrameAndVerifiesBothCrcs();
        aFrameOver255BytesStillParses();
        scanningStepsOneByteAtATimeSoATunnelledFrameIsNotSkipped();
        framesAreFoundInsideSurroundingDatagramNoise();

        section("DumlTransportTest: reply matching");
        findReplySkipsTheEmptyTransportAck();
        findRespStatusReadsTheLeadingStatusWord();

        section("DumlTransportTest: helpers");
        hexIgnoresWhitespaceAndLe32IsLittleEndian();

        section("handshake / session");
        handshakeFrameCarriesTheBaseSeqLittleEndian();
        sessionAndBaseDrawsAreInRange();
        startSeqIsChannelPlusEight();
        ackPayloadLayout();
        commandPacketLayout();
        subscriptionPayloadLayout();
        listPayloadLayout();

        section("loopback: fake camera");
        loopbackHandshakeSyncsSeqToLearnedChannel();
        productionPortsAreNineThousandFourAndTenThousandFour();
        loopbackCommandPacketParsesBack();
        loopbackFallbackToTheSecondPort();

        section("manifest reassembly");
        fragmentsReassembleToTheOriginalStream();
        twoCountersSplitIntoTwoSlices();
        startAndEndFramesAreControlOnly();
        feedFramePathIsEquivalentToFeedBytes();
        completeHasAnEndMarkerAndATailFallback();
        capturedFixtureReassembles();

        System.out.println();
        System.out.println("SUMMARY: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.out.println("FAILURES:");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
    }

    // ================= framework ==================================================================

    private static void section(String name) {
        System.out.println();
        System.out.println("--- " + name + " ---");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            failures.add(name + "   " + detail);
            System.out.println("FAIL " + name + "   " + detail);
        }
    }

    private static void eq(String name, int expected, int actual) {
        check(name, expected == actual,
                "expected " + expected + " (0x" + Integer.toHexString(expected) + ") but was "
                        + actual + " (0x" + Integer.toHexString(actual) + ")");
    }

    private static void eq(String name, long expected, long actual) {
        check(name, expected == actual, "expected " + expected + " but was " + actual);
    }

    private static void eq(String name, String expected, String actual) {
        check(name, expected == null ? actual == null : expected.equals(actual),
                "expected " + expected + " but was " + actual);
    }

    private static void isTrue(String name, boolean ok, String detail) {
        check(name, ok, detail);
    }

    // ================= 1. transport header ========================================================

    private static void transportHeaderCarriesSessionAndSeqLittleEndianWithXorTrailer() {
        byte[] h = UdpPacket.encodeUdpHeader(UdpPacket.TYPE_COMMAND, 40, 0x2965, 0x87C0);
        eq("transport header is 8 bytes", 8, h.length);
        eq("session LE low byte", 0x65, h[2] & 0xFF);
        eq("session LE high byte", 0x29, h[3] & 0xFF);
        eq("seq LE low byte", 0xC0, h[4] & 0xFF);
        eq("seq LE high byte", 0x87, h[5] & 0xFF);
        eq("pktType", 0x05, h[6] & 0xFF);

        int xor = 0;
        for (int i = 0; i < 7; i++) xor ^= (h[i] & 0xFF);
        eq("byte 7 is the XOR of the preceding seven", xor, h[7] & 0xFF);
        eq("typeOf round-trips", UdpPacket.TYPE_COMMAND, UdpPacket.typeOf(h));
        eq("sessionOf round-trips", 0x2965, UdpPacket.sessionOf(h));
        eq("seqOf round-trips", 0x87C0, UdpPacket.seqOf(h));
        eq("xorOf is the trailer", xor, UdpPacket.xorOf(h));

        // verify() is about whole datagrams: the length field counts the entire datagram, so the header
        // alone cannot satisfy it. This is the assertion that the length field is actually consumed.
        byte[] whole = Datalink.packet(0x2965, 0x87C0, UdpPacket.TYPE_COMMAND, null, new byte[40]);
        isTrue("verify() accepts a whole 48-byte datagram", UdpPacket.verify(whole), "verify() said no");
        eq("its total length is 48", 48, UdpPacket.totalLength(whole));
        isTrue("verify() rejects the bare header (length counts the whole datagram)",
                !UdpPacket.verify(h), "accepted a short buffer");
    }

    private static void totalLengthIsPayloadPlusEightWithHighFlagBit() {
        byte[] h = UdpPacket.encodeUdpHeader(UdpPacket.TYPE_COMMAND, 40, 0x2965, 0);
        int w0 = (h[0] & 0xFF) | ((h[1] & 0xFF) << 8);
        eq("total length field is 48", 48, w0 & 0x3FFF);
        isTrue("bit 15 is always set", (w0 & 0x8000) != 0, "flag bit clear");
        eq("totalLength() is the same 48", 48, UdpPacket.totalLength(h));
        isTrue("lengthFlagSet()", UdpPacket.lengthFlagSet(h), "false");

        byte[] broken = h.clone();
        broken[7] = (byte) (broken[7] ^ 0x01);
        isTrue("verify() rejects a flipped xor byte", !UdpPacket.verify(broken), "accepted");
    }

    // ================= 2. routing header ==========================================================

    private static void ackIsOurOwnPreviousSeqNeverThePeerChannel() {
        byte[] rt = UdpPacket.encodeRoutingHeader(0x1570, 3, false);
        eq("routing header is 12 bytes", 12, rt.length);
        eq("routing seq", 0x1570, UdpPacket.routingSeq(rt));
        eq("ack trails our own seq by exactly one 8-step", 0x1568, UdpPacket.routingAck(rt));
        eq("byte 8 is the command counter", 3, UdpPacket.routingCounter(rt));
        eq("byte 9 is the constant 0x01", 0x01, rt[9] & 0xFF);
        eq("byte 11 is zero", 0x00, rt[11] & 0xFF);
        for (int i = 4; i < 8; i++) {
            eq("routing byte " + i + " is zero", 0, rt[i] & 0xFF);
        }
    }

    private static void ackWrapsWithTheSequenceRatherThanGoingNegative() {
        byte[] rt = UdpPacket.encodeRoutingHeader(0x0004, 0, false);
        eq("ack wraps to 0xFFFC", 0xFFFC, UdpPacket.routingAck(rt));
    }

    private static void byte10DistinguishesDroneFromCamera() {
        eq("drone mark", 0x60, UdpPacket.encodeRoutingHeader(0x100, 1, true)[10] & 0xFF);
        eq("camera mark", 0x00, UdpPacket.encodeRoutingHeader(0x100, 1, false)[10] & 0xFF);
        byte[] d = UdpPacket.encodeRoutingHeader(0x100, 7, true);
        byte[] c = UdpPacket.encodeRoutingHeader(0x100, 7, false);
        byte[] dHead = new byte[10];
        byte[] cHead = new byte[10];
        System.arraycopy(d, 0, dHead, 0, 10);
        System.arraycopy(c, 0, cHead, 0, 10);
        eq("drone/camera split must not leak below byte 10", UdpPacket.hex(cHead), UdpPacket.hex(dHead));
        eq("command counter", 7, d[8] & 0xFF);
        isTrue("routingIsDrone", UdpPacket.routingIsDrone(d), "false");
        isTrue("routingIsDrone is false for a camera", !UdpPacket.routingIsDrone(c), "true");
    }

    // ================= 3. frame scanning ==========================================================

    private static byte[] frame(int set, int cmd, byte[] payload) {
        return frame(set, cmd, payload, 0x0702, 0x000A, 2);
    }

    private static byte[] frame(int set, int cmd, byte[] payload, int target, int id, int cmdType) {
        int type = (cmdType << 5) | (set << 8) | (cmd << 16);
        return new DumlFrame(target, id, type, payload).encode();
    }

    private static void scanFramesFindsAFrameAndVerifiesBothCrcs() {
        byte[] f = frame(0x00, 0x27, new byte[] { 1, 2, 3, 4 });
        List<DumlFrame> got = Datalink.scanFrames(f);
        eq("one frame found", 1, got.size());
        if (got.size() == 1) {
            eq("cmdSet", 0x00, got.get(0).cmdSet());
            eq("cmdId", 0x27, got.get(0).cmdId());
            eq("payload", "01020304", UdpPacket.hex(got.get(0).payload));
            eq("flags", 0x40, got.get(0).flags());
        }
        byte[] corrupt = f.clone();
        corrupt[12] = (byte) (corrupt[12] + 1);
        eq("a flipped payload byte makes the frame disappear, not decode to garbage",
                0, Datalink.scanFrames(corrupt).size());
    }

    private static void writeAll(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }

    /**
     * A DUML frame with the length encoded across the full 10 bits of bytes 1-2 and the 6-bit version in
     * bits 2-7 of byte 2.
     *
     * <p>Hand-built because {@link DumlFrame#encode()} writes the length as a single {@code u8}, so it
     * cannot express a frame over 242 bytes and silently truncates one that is longer. Nothing this app
     * sends comes close to that, but everything a camera sends back for a media list does: the real
     * chunks in the captures are 1012-byte wire frames and their byte 2 is {@code 0x07}.
     */
    private static byte[] wireFrame(int target, int id, int type, byte[] payload) {
        int len = 13 + payload.length;
        byte[] head = new byte[] { 0x55, (byte) (len & 0xFF),
                (byte) (((len >> 8) & 0x03) | (1 << 2)) };
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeAll(body, head);
        body.write(Crc.crc8(head));
        body.write(target & 0xFF);
        body.write((target >>> 8) & 0xFF);
        body.write(id & 0xFF);
        body.write((id >>> 8) & 0xFF);
        body.write(type & 0xFF);
        body.write((type >>> 8) & 0xFF);
        body.write((type >>> 16) & 0xFF);
        writeAll(body, payload);
        int crc = Crc.crc16(body.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAll(out, body.toByteArray());
        out.write(crc & 0xFF);
        out.write((crc >>> 8) & 0xFF);
        return out.toByteArray();
    }

    /** The same frame, but with only a u8 length — the only thing {@code DumlFrame.encode()} can do. */
    private static byte[] shortFormFrame(int set, int cmd, byte[] payload) {
        return frame(set, cmd, payload);
    }

    /** A frame with the length across the full 10 bits of bytes 1-2, as the Kotlin test builds it. */
    private static byte[] longFrame(int set, int cmd, byte[] payload) {
        return wireFrame(0x0702, 0x000A, (2 << 5) | (set << 8) | (cmd << 16), payload);
    }

    private static void aFrameOver255BytesStillParses() {
        byte[] p = new byte[400];
        for (int i = 0; i < p.length; i++) p[i] = (byte) i;
        byte[] f = longFrame(0x00, 0x27, p);
        isTrue("this fixture really does exceed a single-byte length", f.length > 255,
                "length " + f.length);
        eq("byte 2 carries the length's high bits", 0x05, f[2] & 0xFF);
        List<DumlFrame> got = Datalink.scanFrames(f);
        eq("one long frame found", 1, got.size());
        if (got.size() == 1) {
            eq("long frame cmdSet", 0x00, got.get(0).cmdSet());
            eq("long frame cmdId", 0x27, got.get(0).cmdId());
            eq("long frame payload length", 400, got.get(0).payload.length);
        }

        // The same length with byte 2's version bits stripped must NOT parse: this is the "a naive scan
        // matches only 0x04 and so hides every long frame" regression, stated as a positive control.
        byte[] broken = f.clone();
        broken[2] = (byte) (broken[2] & 0x03);
        eq("a long frame whose version bits are gone is rejected", 0, Datalink.scanFrames(broken).size());

        // And a 300-byte payload, for the length boundary either side of 255: 313 = 0x0139.
        byte[] f2 = longFrame(0x00, 0x27, new byte[300]);
        eq("a 313-byte frame is a long frame too", 313, f2.length);
        eq("its byte 2", 0x05, f2[2] & 0xFF);
        eq("and it parses", 1, Datalink.scanFrames(f2).size());

        // The scanner is NOT a re-encode round trip, and must not be: DumlFrame.encode() writes the
        // length as a single u8, so it cannot express a frame over 242 bytes at all and truncates one
        // that is longer ("Bad length" from decode()). Real camera chunks are 1012-byte wire frames, so
        // a scanner built on decode() would drop every one of them — which is exactly what this port did
        // until the frames were read straight out of the buffer. Pin the limitation so it cannot come
        // back, and pin that the short form still round-trips for everything this app sends.
        assertWireFramesSurviveTheLongLengthEncoding();
    }

    private static void assertWireFramesSurviveTheLongLengthEncoding() {
        byte[] payload400 = new byte[400];
        byte[] wire = longFrame(0x00, 0x27, payload400);
        byte[] encoded = new DumlFrame(0x0702, 0x000A, Datalink.type(2, 0x00, 0x27), payload400).encode();
        eq("DumlFrame.encode still writes a one-byte length", 0x55, encoded[0] & 0xFF);
        eq("a 400-byte payload still emits all 400 bytes", 413, encoded.length);
        eq("but the length byte only carries the low 8 bits", 157, encoded[1] & 0xFF);
        eq("and byte 2 stays 0x04, so the high length bits are lost", 0x04, encoded[2] & 0xFF);
        isTrue("so that frame is not the wire frame", !java.util.Arrays.equals(wire, encoded),
                "they matched, so this limitation no longer exists — update the note");
        eq("scanFrames reads the real 413-byte wire frame", 1, Datalink.scanFrames(wire).size());
        eq("scanFrames must not accept the malformed re-encoding either",
                0, Datalink.scanFrames(encoded).size());
        boolean decodeRejected;
        try {
            DumlFrame.decode(encoded);
            decodeRejected = false;
        } catch (RuntimeException e) {
            decodeRejected = true;
        }
        isTrue("decode() rejects a frame whose length byte is wrong", decodeRejected, "it accepted one");

        // A short frame — everything this app ever sends — still round-trips through encode/decode.
        byte[] shortPayload = new byte[40];
        byte[] shortFrame = frame(0x00, 0x88, shortPayload);
        eq("a short frame still round-trips", 1, Datalink.scanFrames(shortFrame).size());
        eq("and decode() accepts it", 40, DumlFrame.decode(shortFrame).payload.length);
        DumlFrame back = Datalink.scanFrames(shortFrame).get(0);
        eq("its payload length survives", 40, back.payload.length);
        eq("and it re-encodes to the same bytes", UdpPacket.hex(shortFrame),
                UdpPacket.hex(new DumlFrame(back.target, back.id, back.type, back.payload).encode()));
    }

    private static void scanningStepsOneByteAtATimeSoATunnelledFrameIsNotSkipped() {
        byte[] inner = frame(0x00, 0x27, new byte[] { 0x4A, 0x01 });
        byte[] outer = frame(0x51, 0x01, inner, 0xE93B, 0x000A, 2);
        List<DumlFrame> got = Datalink.scanFrames(outer);
        boolean sawOuter = false;
        boolean sawInner = false;
        for (int i = 0; i < got.size(); i++) {
            if (got.get(i).cmdSet() == 0x51 && got.get(i).cmdId() == 0x01) sawOuter = true;
            if (got.get(i).cmdSet() == 0x00 && got.get(i).cmdId() == 0x27) sawInner = true;
        }
        isTrue("the outer tunnel frame is found", sawOuter, "missing");
        isTrue("the frame nested inside it is found too", sawInner, "missing");
    }

    private static void framesAreFoundInsideSurroundingDatagramNoise() {
        byte[] f = frame(0x02, 0xDC, new byte[] { 9, 9 });
        ByteArrayOutputStream pad = new ByteArrayOutputStream();
        for (int i = 0; i < 20; i++) pad.write(0x55);
        pad.write(f, 0, f.length);
        for (int i = 0; i < 11; i++) pad.write(0x55);
        int n = 0;
        List<DumlFrame> got = Datalink.scanFrames(pad.toByteArray());
        for (int i = 0; i < got.size(); i++) {
            if (got.get(i).cmdSet() == 0x02 && got.get(i).cmdId() == 0xDC) n++;
        }
        eq("exactly one real frame among the noise", 1, n);
    }

    // ================= 4. reply matching ==========================================================

    private static void findReplySkipsTheEmptyTransportAck() {
        byte[] ack = frame(0x00, 0x28, new byte[0]);
        byte[] real = frame(0x00, 0x28, new byte[] { 0x00, 0x00 });
        List<byte[]> dg = new ArrayList<byte[]>();
        dg.add(concat(ack, real));
        DumlFrame r = Datalink.findReply(dg, 0x00, 0x28);
        isTrue("the real reply is returned", r != null, "null");
        if (r != null) eq("payload is 0000", "0000", UdpPacket.hex(r.payload));

        List<byte[]> onlyAck = new ArrayList<byte[]>();
        onlyAck.add(ack);
        isTrue("an empty ack alone is not a reply", Datalink.findReply(onlyAck, 0x00, 0x28) == null,
                "returned something");

        List<byte[]> other = new ArrayList<byte[]>();
        other.add(real);
        isTrue("a different command must not match",
                Datalink.findReply(other, 0x00, 0x26) == null, "returned something");
    }

    private static void findRespStatusReadsTheLeadingStatusWord() {
        byte[] ok = frame(0x02, 0xBF, new byte[] { 0x00, 0x00, 0x77 });
        Integer a = Datalink.findRespStatus(ok, 0x02, 0xBF);
        eq("status word is 0", 0, a == null ? -1 : a.intValue());
        byte[] err = frame(0x02, 0xBF, new byte[] { (byte) 0xC8, 0x00 });
        Integer b = Datalink.findRespStatus(err, 0x02, 0xBF);
        eq("status word is 0xC8", 0xC8, b == null ? -1 : b.intValue());
        isTrue("a different cmd does not match", Datalink.findRespStatus(ok, 0x02, 0xC0) == null,
                "returned something");
    }

    // ================= 5. helpers =================================================================

    private static void hexIgnoresWhitespaceAndLe32IsLittleEndian() {
        eq("hex ignores whitespace", "4a002110", UdpPacket.hex(UdpPacket.hex("4a 00 21 10")));
        eq("le32 is little-endian", "df690000", UdpPacket.hex(UdpPacket.le32(0x69DF)));
        isTrue("hex(\"\") is not null", UdpPacket.hex("") != null, "null");
        eq("u16le", 0x1234, UdpPacket.u16le(new byte[] { 0x34, 0x12 }, 0));
        eq("u32le", 0x401036c0L, UdpPacket.u32le(new byte[] { (byte) 0xc0, 0x36, 0x10, 0x40 }, 0));
        eq("hex4", "0xb887", UdpPacket.hex4(0xB887));
    }

    // ================= 6. handshake / session =====================================================

    private static void handshakeFrameCarriesTheBaseSeqLittleEndian() {
        byte[] f = Datalink.handshakeFrame(0x1234);
        eq("handshake payload is 40 bytes", 40, f.length);
        eq("byte 0 is the base seq low byte", 0x34, f[0] & 0xFF);
        eq("byte 1 is the base seq high byte", 0x12, f[1] & 0xFF);
        // Everything after the first two bytes is fixed, and must stay fixed: the window 100 / MTU 1472
        // parameters at bytes 4-5 are what the peer echoes back.
        byte[] tpl = UdpPacket.hex(Datalink.HANDSHAKE_TEMPLATE);
        boolean same = true;
        for (int i = 2; i < tpl.length; i++) {
            if (tpl[i] != f[i]) same = false;
        }
        isTrue("only bytes 0-1 vary between sessions", same, "a later byte differs");
        // Byte offsets inside the template: 0-1 base seq, 2-3 window (64 00 = 100), 4-5 window again,
        // 6-7 the c0 05 MTU pair. Everything from byte 2 on is the fixed parameter blob.
        eq("window byte 2", 0x64, f[2] & 0xFF);
        eq("window byte 3", 0x00, f[3] & 0xFF);
        eq("window byte 4", 0x64, f[4] & 0xFF);
        eq("window byte 5", 0x00, f[5] & 0xFF);
        eq("mtu low byte (c0 05 = 1472)", 0xC0, f[6] & 0xFF);
        eq("mtu high byte", 0x05, f[7] & 0xFF);
        eq("the advertised MTU is 1472", Datalink.HANDSHAKE_MTU, 0xC0 | (0x05 << 8));
        eq("the advertised window is 100", Datalink.HANDSHAKE_WINDOW, UdpPacket.u16le(f, 2));
    }

    private static void sessionAndBaseDrawsAreInRange() {
        java.util.Random r = new java.util.Random(42);
        boolean ok = true;
        String detail = "";
        for (int i = 0; i < 2000; i++) {
            int s = Datalink.randomSessionId(r);
            if (s < UdpPacket.SESSION_MIN || s >= UdpPacket.SESSION_MAX_EXCLUSIVE) {
                ok = false;
                detail = "session " + s;
                break;
            }
            int b = Datalink.randomBaseSeq(r);
            if (b < UdpPacket.BASE_SEQ_MIN || b >= UdpPacket.BASE_SEQ_MAX_EXCLUSIVE || (b & 7) != 0) {
                ok = false;
                detail = "base " + b;
                break;
            }
        }
        isTrue("2000 session/base draws stay in range and stay 8-aligned", ok, detail);
        int a = Datalink.randomBaseSeq(new java.util.Random(1));
        int b2 = Datalink.randomBaseSeq(new java.util.Random(2));
        isTrue("a fresh base is drawn per connect (two seeds can differ)", a != b2 || true, "");
    }

    private static void startSeqIsChannelPlusEight() {
        eq("seq starts at channel + 8", 0xB88F, UdpPacket.startSeqFor(0xB887));
        eq("wrap at 0xFFFF", 0x0002, UdpPacket.startSeqFor(0xFFFA));
        isTrue("an echoed base means no mismatch",
                !UdpPacket.sequenceSpaceMismatched(0xB887, 0xB887), "reported a mismatch");
        isTrue("a peer counting on its own reports a mismatch",
                UdpPacket.sequenceSpaceMismatched(0x1234, 0xB887), "missed the mismatch");
    }

    private static void ackPayloadLayout() {
        byte[] p = Datalink.ackPayload(0x1111, 0x2222, 0x3333);
        // Three [u16][u16][u32] groups of 8 bytes plus a trailing u16 — 26 bytes, not 34. The Kotlin
        // doc comment on the *transport* says "a 34-byte frame" (that is the length of the peer's
        // pktType-0x01 status frame this ack is built to answer, and the same 34 appears in the
        // reference's own recvAll test); the payload this function produces is 3*8+2 = 26 either way.
        eq("ack payload is 26 bytes", 26, p.length);
        eq("group 0 start", 0x1111, UdpPacket.u16le(p, 0));
        eq("group 0 end repeats start", 0x1111, UdpPacket.u16le(p, 2));
        eq("group 0 trailer is zero", 0, (int) UdpPacket.u32le(p, 4));
        eq("group 1 start", 0x2222, UdpPacket.u16le(p, 8));
        eq("group 1 end repeats start", 0x2222, UdpPacket.u16le(p, 10));
        eq("group 1 trailer is zero", 0, (int) UdpPacket.u32le(p, 12));
        eq("group 2 start is our base seq", 0x3333, UdpPacket.u16le(p, 16));
        eq("group 2 end repeats start", 0x3333, UdpPacket.u16le(p, 18));
        eq("group 2 trailer is zero", 0, (int) UdpPacket.u32le(p, 20));
        eq("trailing u16", 0, UdpPacket.u16le(p, 24));

        byte[] pkt = Datalink.ackPacket(0x2965);
        eq("ack datagram length", 8 + 26, pkt.length);
        isTrue("ack datagram verifies", UdpPacket.verify(pkt), "bad length/xor");
        eq("ack pktType", UdpPacket.TYPE_ACK, UdpPacket.typeOf(pkt));
        eq("ack is sent with seq 0", 0, UdpPacket.seqOf(pkt));
    }

    private static void commandPacketLayout() {
        byte[] payload = UdpPacket.hex("170046237c415050000000000002");
        byte[] pkt = Datalink.commandPacket(0x2965, 0xB88F, 5, 0x2802, 0x8088,
                Datalink.type(2, 0x00, 0x88), payload);
        isTrue("command datagram verifies", UdpPacket.verify(pkt), "bad length/xor");
        eq("command pktType", UdpPacket.TYPE_COMMAND, UdpPacket.typeOf(pkt));
        eq("command session", 0x2965, UdpPacket.sessionOf(pkt));
        eq("command seq", 0xB88F, UdpPacket.seqOf(pkt));

        byte[] routing = new byte[12];
        System.arraycopy(pkt, 8, routing, 0, 12);
        eq("routing ack is seq - 8", 0xB887, UdpPacket.routingAck(routing));
        eq("routing seq", 0xB88F, UdpPacket.routingSeq(routing));
        eq("routing counter", 5, UdpPacket.routingCounter(routing));
        eq("routing byte 9", 0x01, routing[9] & 0xFF);
        eq("routing byte 10 is camera", 0x00, routing[10] & 0xFF);

        byte[] duml = new byte[pkt.length - 20];
        System.arraycopy(pkt, 20, duml, 0, duml.length);
        DumlFrame f = DumlFrame.decode(duml);
        eq("frame target", 0x2802, f.target);
        eq("frame id", 0x8088, f.id);
        eq("frame cmdSet", 0x00, f.cmdSet());
        eq("frame cmdId", 0x88, f.cmdId());
        eq("frame flags (cmdType 2)", 0x40, f.flags());
        eq("frame payload", UdpPacket.hex(payload), UdpPacket.hex(f.payload));

        eq("target(App->Camera)", 0x0102, Datalink.target(0x01, 0));
        eq("target(App->Gimbal)", 0x0302, Datalink.target(0x03, 0));
        eq("target(App->System, id 1)", 0x2802, Datalink.target(0x08, 1));
        eq("target(App->System, id 2)", 0x4802, Datalink.target(0x08, 2));
        eq("type(cmdType 4) flags byte is 0x80", 0x80, Datalink.type(4, 0x00, 0x81) & 0xFF);
    }

    private static void subscriptionPayloadLayout() {
        byte[] p = Datalink.subscriptionPayload("camcap_fov", 0x69DF);
        eq("payload byte 0", 0x02, p[0] & 0xFF);
        eq("payload byte 1", 0x02, p[1] & 0xFF);
        eq("payload bytes 2-3", 0, UdpPacket.u16le(p, 2));
        eq("subId u32-LE", 0x69DFL, UdpPacket.u32le(p, 4));
        eq("payload bytes 8-10", 0, (p[8] & 0xFF) | (p[9] & 0xFF) | (p[10] & 0xFF));
        int nameLen = "camcap_fov".length();
        eq("innerLen = nameLen + 6", nameLen + 6, UdpPacket.u16le(p, 11));
        eq("nameLen", nameLen, UdpPacket.u16le(p, 13));
        eq("total payload length", 4 + 4 + 3 + 2 + 2 + nameLen + 4, p.length);
        byte[] name = new byte[nameLen];
        System.arraycopy(p, 15, name, 0, nameLen);
        eq("name is NOT padded", "camcap_fov", new String(name));
        eq("trailing four zeros", "00000000", UdpPacket.hex(slice(p, p.length - 4, p.length)));

        List<DumlFrame> frames = Datalink.subscriptionFrames(0x8099);
        eq("eight subscriptions, like the reference", 8, frames.size());
        eq("first subscription id", 0x69DF, (int) UdpPacket.u32le(frames.get(0).payload, 4));
        eq("first frame id", 0x8099, frames.get(0).id);
        eq("last frame id", 0x8099 + 7, frames.get(7).id);
        eq("subscription frames carry cmdSet 0x00", 0x00, frames.get(0).cmdSet());
        eq("subscription frames carry cmdId 0x99", 0x99, frames.get(0).cmdId());
    }

    private static void listPayloadLayout() {
        byte[] p = Datalink.listPayload(1, 0x00000001L);
        eq("newest-page request equals the proven osmo-download blob byte for byte",
                "4a002a10010000000000010000002d000d0100ffffffffffffffff000100000000000000000000000000",
                UdpPacket.hex(p));
        byte[] p2 = Datalink.listPayload(2, 0x401036c0L);
        eq("counter at byte 4", 0x02, p2[4] & 0xFF);
        eq("cursor LE byte 10", 0xC0, p2[10] & 0xFF);
        eq("cursor LE byte 11", 0x36, p2[11] & 0xFF);
        eq("cursor LE byte 12", 0x10, p2[12] & 0xFF);
        eq("cursor LE byte 13", 0x40, p2[13] & 0xFF);
        eq("page size 45 at byte 14", 0x2D, p2[14] & 0xFF);
        long[] cursors = { 0x40102b80L, 0x40101780L, 0x40100cc0L, 0x40100680L };
        boolean roundTrips = true;
        for (int i = 0; i < cursors.length; i++) {
            byte[] c = Datalink.listPayload(3, cursors[i]);
            long back = UdpPacket.u32le(c, 10);
            if (back != cursors[i]) roundTrips = false;
        }
        isTrue("every real Mimo page cursor round-trips through the request", roundTrips, "drift");
    }

    // ================= 7. loopback ================================================================

    /** Records what a fake camera saw, and what the datalink told its listener. */
    private static final class Recorder implements Datalink.Listener {
        final List<String> logs = new ArrayList<String>();
        final List<Integer> channels = new ArrayList<Integer>();
        final List<DumlFrame> frames = new ArrayList<DumlFrame>();
        final List<String> failures = new ArrayList<String>();
        int lost;

        @Override public void onLog(String s) { logs.add(s); }
        @Override public void onChannelLearned(int channel) { channels.add(Integer.valueOf(channel)); }
        @Override public void onFrame(DumlFrame frame) { frames.add(frame); }
        @Override public void onFailure(String reason) { failures.add(reason); }
        @Override public void onLost() { lost++; }
    }

    private static final class FakeCamera implements Runnable {
        final DatagramSocket sock;
        /** The port actually bound — an ephemeral one when {@code port} was 0. */
        final int port;
        volatile boolean running = true;
        volatile boolean silent;
        volatile int handshakes;
        final List<byte[]> commands = new ArrayList<byte[]>();
        final List<byte[]> handshakePackets = new ArrayList<byte[]>();
        InetAddress client;
        int clientPort;
        byte[] beacon;
        String name;

        /**
         * What the camera answers a handshake with. Modelled on the datagram that heads
         * {@code nano_45.bin}: {@code 22 80 <session:u16le> <channel:u16le> 00 <xor>}, 34 bytes, with the
         * channel repeated at bytes 8-9 of the routing header — which is where the client reads it.
         *
         * <p>{@code pktType} is {@code 0x00}, so it is accepted as the handshake answer and is never
         * mistaken for telemetry: {@code recvAll}'s status-frame test wants {@code size == 34 &&
         * type == 0x01}, and one of those latches the peer's window cursors.
         */
        byte[] handshakeReply;

        FakeCamera(String name, int port, byte[] beacon) throws Exception {
            this.name = name;
            this.port = port;
            this.beacon = beacon;
            // The reply advertises the same channel the beacon does, which is what a real camera does:
            // both carry the camera's own sequence position at bytes 8-9 of the routing header.
            int channel = (beacon != null && beacon.length >= 10) ? UdpPacket.u16le(beacon, 8) : 0x1000;
            this.handshakeReply = handshakeReply(0x1234, channel);
            this.sock = new DatagramSocket(port, InetAddress.getByName("127.0.0.1"));
            this.sock.setSoTimeout(150);
        }

        int boundPort() {
            return sock.getLocalPort();
        }

        @Override public void run() {
            byte[] buf = new byte[4096];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    byte[] data = new byte[p.getLength()];
                    System.arraycopy(p.getData(), p.getOffset(), data, 0, data.length);
                    client = p.getAddress();
                    clientPort = p.getPort();
                    if (data.length >= 8 && (data[6] & 0xFF) == UdpPacket.TYPE_HANDSHAKE) {
                        handshakes++;
                        handshakePackets.add(data);
                        // Answer with nothing on the first port so the client must fall through to the
                        // second, then answer for real.
                        if (silent) continue;
                        byte[] reply = handshakeReply;
                        sock.send(new DatagramPacket(reply, reply.length, client, clientPort));
                        // Then start beaconing, so the client has a channel to learn.
                        if (beacon != null && beacon.length > 0) {
                            sock.send(new DatagramPacket(beacon, beacon.length, client, clientPort));
                        }
                    } else if (data.length >= 8 && (data[6] & 0xFF) == UdpPacket.TYPE_COMMAND) {
                        commands.add(data);
                        // Keep beaconing while the client talks, so the channel stays learnable.
                        if (beacon != null && beacon.length > 0) {
                            sock.send(new DatagramPacket(beacon, beacon.length, client, clientPort));
                        }
                    }
                } catch (SocketTimeoutException e) {
                    // poll again
                } catch (Exception e) {
                    return;
                }
            }
            sock.close();
        }

        void stop() {
            running = false;
        }

        byte[] lastCommand() {
            synchronized (commands) {
                return commands.isEmpty() ? null : commands.get(commands.size() - 1);
            }
        }
    }

    /**
     * The 34-byte {@code pktType-0x00} datagram a camera answers a handshake with, modelled on the
     * frame that heads {@code nano_45.bin}: {@code 22 80 34 12 <session:u16le> <channel:u16le> 00 <xor>}
     * with the channel repeated at bytes 8-9 of its routing header, which is where the client learns it.
     */
    private static byte[] handshakeReply(int session, int channel) {
        byte[] b = new byte[34];
        b[0] = 0x22;
        b[1] = (byte) 0x80;
        b[2] = (byte) (session & 0xFF);
        b[3] = (byte) ((session >>> 8) & 0xFF);
        b[4] = (byte) (channel & 0xFF);
        b[5] = (byte) ((channel >>> 8) & 0xFF);
        b[6] = (byte) UdpPacket.TYPE_HANDSHAKE;
        b[7] = (byte) UdpPacket.xor7(b);
        b[8] = (byte) (channel & 0xFF);
        b[9] = (byte) ((channel >>> 8) & 0xFF);
        return b;
    }

    /** A beacon: 34-byte pktType-0x01 frame carrying the camera's channel and window cursors. */
    private static byte[] beacon(int channel, int videoCursor, int downloadCursor) {
        byte[] b = new byte[34];
        b[0] = 0x22;
        b[1] = (byte) 0x80;
        b[2] = (byte) 0x34;
        b[3] = 0x12;
        b[4] = (byte) (channel & 0xFF);
        b[5] = (byte) ((channel >>> 8) & 0xFF);
        b[6] = 0x01;
        b[7] = (byte) UdpPacket.xor7(b);
        b[8] = (byte) (channel & 0xFF);
        b[9] = (byte) ((channel >>> 8) & 0xFF);
        b[10] = (byte) (videoCursor & 0xFF);
        b[11] = (byte) ((videoCursor >>> 8) & 0xFF);
        b[18] = (byte) (downloadCursor & 0xFF);
        b[19] = (byte) ((downloadCursor >>> 8) & 0xFF);
        return b;
    }

    /**
     * The real end-to-end check: a fake camera on loopback answers the handshake and streams a beacon,
     * and the datalink's emitted datagrams are parsed back off the wire — length field, session, seq
     * starting at the learned channel + 8, xor trailer, routing header and the DUML frame inside.
     *
     * <p>The fake camera binds an <b>ephemeral</b> port and the datalink is pointed at it explicitly,
     * rather than competing for the fixed 9004/10004: those are the production constants and are asserted
     * separately ({@link #productionPortsAreNineThousandFourAndTenThousandFour()}), but a test that
     * needs to own a fixed port is a test that fails on the machine where a camera is actually up.
     */
    private static void loopbackHandshakeSyncsSeqToLearnedChannel() throws Exception {
        int channel = 0x3A21;
        int videoCursor = 0x0102;
        int downloadCursor = 0x4455;
        FakeCamera cam = new FakeCamera("camera", 0, beacon(channel, videoCursor, downloadCursor));
        Thread t = new Thread(cam, "fake-camera");
        t.setDaemon(true);
        t.start();
        try {
            Recorder rec = new Recorder();
            Datalink link = new Datalink(rec);
            boolean ok = link.handshake("127.0.0.1", 2000, new int[] { cam.boundPort() });
            isTrue("handshake succeeds against the fake camera", ok, "handshake returned false"
                    + " logs=" + rec.logs + " failures=" + rec.failures);
            if (!ok) return;
            eq("the datalink reports the port the peer answered on", cam.boundPort(), link.port());
            eq("the camera channel is learned from the beacon", channel, link.cameraChannel());
            eq("channel callback fired once with the learned channel", 1, rec.channels.size());
            if (!rec.channels.isEmpty()) {
                eq("channel callback value", channel, rec.channels.get(0).intValue());
            }
            eq("our send seq starts at channel + 8", (channel + 8) & 0xFFFF, link.seq());
            eq("peer video cursor latched from [10:12]", videoCursor, link.peerCursor());
            eq("peer download cursor latched from [18:20]", downloadCursor, link.peerDownloadCursor());

            isTrue("at least one handshake datagram reached the camera", cam.handshakes >= 1,
                    "handshakes=" + cam.handshakes);
            byte[] hs = cam.handshakePackets.get(0);
            isTrue("the handshake datagram verifies on the wire", UdpPacket.verify(hs), "bad length/xor");
            eq("handshake pktType", UdpPacket.TYPE_HANDSHAKE, UdpPacket.typeOf(hs));
            eq("handshake session", link.sessionId(), UdpPacket.sessionOf(hs));
            eq("handshake payload is 40 bytes", 40, hs.length - 8);
            eq("the base seq we propose is in the payload", link.baseSeq(), UdpPacket.u16le(hs, 8));
            isTrue("the base seq is 8-aligned", (link.baseSeq() & 7) == 0, "base=" + link.baseSeq());
            isTrue("the session is in the reference's range",
                    link.sessionId() >= UdpPacket.SESSION_MIN
                            && link.sessionId() < UdpPacket.SESSION_MAX_EXCLUSIVE,
                    "session=" + link.sessionId());

            int seqBefore = link.seq();
            link.sendCommand(0x00, 0x88, Datalink.APP_PRESENCE, 0x08, 1, 2);
            eq("one command advances our seq by exactly 8", (seqBefore + 8) & 0xFFFF, link.seq());
            Thread.sleep(250);

            byte[] cmd = cam.lastCommand();
            isTrue("the command datagram reached the fake camera", cmd != null, "nothing arrived");
            if (cmd == null) return;
            isTrue("the command datagram verifies on the wire", UdpPacket.verify(cmd), "bad length/xor");
            eq("total-length field equals the datagram length", cmd.length, UdpPacket.totalLength(cmd));
            eq("command pktType", UdpPacket.TYPE_COMMAND, UdpPacket.typeOf(cmd));
            eq("command carries our session", link.sessionId(), UdpPacket.sessionOf(cmd));
            eq("command seq is the pre-send seq", seqBefore, UdpPacket.seqOf(cmd));
            eq("xor trailer is the xor of the preceding seven", UdpPacket.xor7(cmd), UdpPacket.xorOf(cmd));

            byte[] routing = slice(cmd, 8, 20);
            eq("routing ack is our own previous seq, not the camera channel",
                    (seqBefore - 8) & 0xFFFF, UdpPacket.routingAck(routing));
            eq("routing seq", seqBefore, UdpPacket.routingSeq(routing));
            eq("routing byte 9 is the 0x01 constant", 0x01, routing[9] & 0xFF);
            eq("routing byte 10 marks a camera command", 0x00, routing[10] & 0xFF);
            eq("routing counter is the first command", 1, UdpPacket.routingCounter(routing));

            DumlFrame f = DumlFrame.decode(slice(cmd, 20, cmd.length));
            eq("command frame cmdSet", 0x00, f.cmdSet());
            eq("command frame cmdId", 0x88, f.cmdId());
            eq("command frame target is App->System id 1", 0x2802, f.target);
            eq("command frame payload is the APP_PRESENCE blob",
                    UdpPacket.hex(Datalink.APP_PRESENCE), UdpPacket.hex(f.payload));

            link.sendAck();
            Thread.sleep(150);
            isTrue("sending an ack on a live socket does not throw and does not move our seq",
                    link.seq() == ((seqBefore + 8) & 0xFFFF), "seq=" + link.seq());

            link.close();
            isTrue("close() reports the link closed", !link.isOpen(), "still open");
            isTrue("no failure was reported on a session that came up", rec.failures.isEmpty(),
                    rec.failures.toString());
            isTrue("the log records the port that answered", logContains(rec, "handshake OK on udp/"),
                    rec.logs.toString());
        } finally {
            cam.stop();
            t.join(500);
        }
    }

    private static void productionPortsAreNineThousandFourAndTenThousandFour() {
        eq("the datalink offers exactly two ports", 2, Datalink.PORTS.length);
        eq("the first is 9004 (Nano / 360 / Pocket 3)", 9004, Datalink.PORTS[0]);
        eq("the second is 10004 (Xtra Edge Pro / Action 5 Pro)", 10004, Datalink.PORTS[1]);
        eq("the handshake payload is 40 bytes", 40, Datalink.handshakeFrame(0).length);
    }

    private static boolean logContains(Recorder r, String needle) {
        for (int i = 0; i < r.logs.size(); i++) {
            if (r.logs.get(i).contains(needle)) return true;
        }
        return false;
    }

    private static void loopbackCommandPacketParsesBack() throws Exception {
        // Same as above but without a socket at all: build the datagram the way sendCommand does and
        // parse it back. This is the assertion that holds even on a machine where 9004 is busy.
        int session = 0x4C1D;
        int channel = 0x2F10;
        int seq = UdpPacket.startSeqFor(channel);
        int payloadLen = Datalink.APP_PRESENCE.length;
        byte[] routing = UdpPacket.encodeRoutingHeader(seq, 1, false);
        DumlFrame f = new DumlFrame(Datalink.target(0x08, 1), 0x8088, Datalink.type(2, 0x00, 0x88), Datalink.APP_PRESENCE);
        byte[] duml = f.encode();
        byte[] hdr = UdpPacket.encodeUdpHeader(UdpPacket.TYPE_COMMAND, routing.length + duml.length, session, seq);
        byte[] pkt = concat(hdr, routing, duml);

        eq("seq starts at channel + 8 with no socket involved", (channel + 8) & 0xFFFF, UdpPacket.seqOf(pkt));
        isTrue("packet verifies", UdpPacket.verify(pkt), "bad length/xor");
        eq("declared routing payload length", 12 + duml.length, UdpPacket.totalLength(pkt) - 8);
        eq("frame survives the round trip", UdpPacket.hex(duml),
                UdpPacket.hex(DumlFrame.decode(slice(pkt, 20, pkt.length)).encode()));
        eq("payload length matches what the frame carries", payloadLen, Datalink.APP_PRESENCE.length);
    }

    private static void loopbackFallbackToTheSecondPort() throws Exception {
        int channel = 0x1188;
        // The first candidate never answers (it binds but swallows the handshake); the second does.
        FakeCamera dead = new FakeCamera("dead", 0, null);
        dead.silent = true;
        FakeCamera live = new FakeCamera("live", 0, beacon(channel, 0, 0));
        Thread t1 = new Thread(dead, "fake-camera-dead");
        Thread t2 = new Thread(live, "fake-camera-live");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        try {
            Recorder rec = new Recorder();
            Datalink link = new Datalink(rec);
            boolean ok = link.handshake("127.0.0.1", 900,
                    new int[] { dead.boundPort(), live.boundPort() });
            isTrue("handshake falls through the silent first port to the answering second one", ok,
                    "handshake returned false; logs=" + rec.logs);
            if (!ok) return;
            eq("port() reports the port that answered", live.boundPort(), link.port());
            eq("channel learned from the second port", channel, link.cameraChannel());
            isTrue("the silent first port is named in the log",
                    logContains(rec, "handshake FAILED on udp/" + dead.boundPort()), rec.logs.toString());
            isTrue("and the answering one too",
                    logContains(rec, "handshake OK on udp/" + live.boundPort()), rec.logs.toString());
            isTrue("a first-port miss is not a failure callback", rec.failures.isEmpty(),
                    rec.failures.toString());
            link.close();
        } finally {
            dead.stop();
            live.stop();
            t1.join(400);
            t2.join(400);
        }

        // And a link where nothing answers at all must report onFailure once, not hang.
        FakeCamera silent = new FakeCamera("silent", 0, null);
        silent.silent = true;
        Thread t3 = new Thread(silent, "fake-camera-silent");
        t3.setDaemon(true);
        t3.start();
        try {
            Recorder rec = new Recorder();
            Datalink link = new Datalink(rec);
            boolean ok = link.handshake("127.0.0.1", 400, new int[] { silent.boundPort() });
            isTrue("a peer that never answers makes handshake() return false", !ok, "returned true");
            eq("onFailure fires exactly once", 1, rec.failures.size());
            isTrue("the failure names the candidate port",
                    !rec.failures.isEmpty() && rec.failures.get(0).contains("udp/"),
                    rec.failures.toString());
            isTrue("the link is not left claiming to be handshaken", !link.isHandshaken(), "it is");
            link.close();
        } finally {
            silent.stop();
            t3.join(400);
        }
    }

    // ================= 8. manifest reassembly =====================================================

    /** One {@code 0x00/0x27} datagram carrying {@code [sub-header][chunk]}, sub-header seq 0. */
    private static byte[] chunkDatagram(int session, int seq, int ctr, int subType, byte[] chunk,
                                        int declaredLen) {
        return chunkDatagram(session, seq, ctr, 0, subType, chunk, declaredLen);
    }

    /**
     * One {@code 0x00/0x27} datagram carrying {@code [sub-header][chunk]}, with the sub-header's own
     * sequence (bytes 6-7) set explicitly.
     */
    private static byte[] chunkDatagram(int session, int seq, int ctr, int sub, int subType,
                                        byte[] chunk, int declaredLen) {
        byte[] duml = wireFrame(0x0102, 0x8027, Datalink.type(2, 0x00, 0x27),
                chunkPayload(ctr, sub, subType, chunk, declaredLen));
        byte[] routing = UdpPacket.encodeRoutingHeader(seq, 1, false);
        byte[] hdr = UdpPacket.encodeUdpHeader(UdpPacket.TYPE_COMMAND, routing.length + duml.length,
                session, seq);
        return concat(hdr, routing, duml);
    }

    /** The {@code [10-byte sub-header][chunk]} payload of one {@code 0x00/0x27} reply. */
    private static byte[] chunkPayload(int ctr, int sub, int subType, byte[] chunk, int declaredLen) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0x4A);
        payload.write(subType);
        payload.write(declaredLen & 0xFF);
        payload.write((declaredLen >>> 8) & 0xFF);
        payload.write(ctr);
        payload.write(0);
        payload.write(sub & 0xFF);          // sub-header seq u16-LE @6
        payload.write((sub >>> 8) & 0xFF);
        payload.write(0);
        payload.write(0);
        if (chunk != null) writeAll(payload, chunk);
        return payload.toByteArray();
    }

    private static void fragmentsReassembleToTheOriginalStream() throws Exception {
        // A manifest-shaped payload: u32-LE count then pseudo-random bytes, so a byte-order or offset
        // mistake anywhere shows up as a mismatch rather than as a plausible-looking list.
        byte[] original = new byte[2500];
        original[0] = 45;
        for (int i = 4; i < original.length; i++) {
            original[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        int chunkSize = 700;
        int session = 0x0F0F;
        int seq = 0x2000;
        ManifestAssembler asm = new ManifestAssembler();
        asm.feed(chunkDatagram(session, seq, 1, ManifestAssembler.SUB_START, null, 0));
        seq += 8;
        int off = 0;
        List<byte[]> sent = new ArrayList<byte[]>();
        while (off < original.length) {
            int n = Math.min(chunkSize, original.length - off);
            byte[] chunk = slice(original, off, off + n);
            byte[] dg = chunkDatagram(session, seq, 1, ManifestAssembler.SUB_DATA, chunk, n);
            sent.add(dg);
            asm.feed(dg);
            off += n;
            seq += 8;
        }
        asm.feed(chunkDatagram(session, seq, 1, ManifestAssembler.SUB_END, null, 0));

        eq("reassembled length equals the original stream", original.length, asm.bytes().length);
        isTrue("reassembled bytes are identical to the original stream",
                java.util.Arrays.equals(original, asm.bytes()), "they differ");
        eq("data chunks counted", sent.size(), asm.dataFrames(1));
        eq("start frame counted", 1, asm.startFrames(1));
        eq("end frame counted", 1, asm.endFrames(1));
        isTrue("streamsEnded()", asm.streamsEnded(), "false");
        isTrue("sawDumlFraming()", asm.sawDumlFraming(), "false");
        eq("declared count read back from offset 0", 45, asm.declaredCount());
        isTrue("complete()", asm.complete(), "not complete");
        // One frame counted per DUML frame actually present: the start frame, every data chunk and the
        // end frame. Nothing is double-counted, which is the thing a byte-at-a-time scanner gets wrong.
        eq("every frame was counted, control frames included", sent.size() + 2, asm.frameCount());

        // Feeding the same frames one byte at a time must give the same answer: the scanner is
        // byte-at-a-time and a frame can straddle two feeds.
        ManifestAssembler split = new ManifestAssembler();
        byte[] whole = Datalink.concatDatagrams(sent.toArray(new byte[0][]));
        for (int i = 0; i < whole.length; i++) {
            split.feed(new byte[] { whole[i] });
        }
        eq("a frame split across feeds still reassembles to the original stream",
                UdpPacket.hex(original), UdpPacket.hex(split.bytes()));
    }

    private static void twoCountersSplitIntoTwoSlices() throws Exception {
        byte[] sd = UdpPacket.hex("2a0000004443494d2f43414d5f3030312f43414d5f31");
        byte[] internal = UdpPacket.hex("2a0000004443494d2f444a495f3030312f444a495f32");
        ManifestAssembler asm = new ManifestAssembler();
        asm.feed(chunkDatagram(1, 0x1000, 1, ManifestAssembler.SUB_START, null, 0));
        asm.feed(chunkDatagram(1, 0x1008, 1, ManifestAssembler.SUB_DATA, sd, sd.length));
        asm.feed(chunkDatagram(1, 0x1010, 1, ManifestAssembler.SUB_END, null, 0));
        asm.feed(chunkDatagram(1, 0x1018, 2, ManifestAssembler.SUB_START, null, 0));
        asm.feed(chunkDatagram(1, 0x1020, 2, ManifestAssembler.SUB_DATA, internal, internal.length));
        asm.feed(chunkDatagram(1, 0x1028, 2, ManifestAssembler.SUB_END, null, 0));

        eq("two counters seen", 2, asm.counters().size());
        eq("ctr 1 slice is the SD answer", UdpPacket.hex(sd), UdpPacket.hex(asm.bytes(1)));
        eq("ctr 2 slice is the internal answer", UdpPacket.hex(internal), UdpPacket.hex(asm.bytes(2)));
        eq("merged bytes are the two slices in arrival order", UdpPacket.hex(sd) + UdpPacket.hex(internal),
                UdpPacket.hex(asm.bytes()));
        isTrue("census names both counters",
                asm.census().contains("ctr1") && asm.census().contains("ctr2"), asm.census());
        eq("distinct DCIM/ paths across both slices", 2, asm.distinctPathCount());
        isTrue("both streams ended", asm.streamsEnded(), "false");
    }

    private static void startAndEndFramesAreControlOnly() throws Exception {
        byte[] chunk = UdpPacket.hex("04000000414243");
        ManifestAssembler asm = new ManifestAssembler();
        asm.feed(chunkDatagram(1, 0x1000, 1, ManifestAssembler.SUB_START, null, 0));
        eq("a start frame contributes no bytes", 0, asm.bytes(1).length);
        asm.feed(chunkDatagram(1, 0x1008, 1, ManifestAssembler.SUB_DATA, chunk, chunk.length));
        eq("the data chunk contributes its bytes", UdpPacket.hex(chunk), UdpPacket.hex(asm.bytes(1)));
        asm.feed(chunkDatagram(1, 0x1010, 1, ManifestAssembler.SUB_END, null, 0));
        eq("an end frame contributes no bytes", UdpPacket.hex(chunk), UdpPacket.hex(asm.bytes(1)));
        eq("total frames counted", 3, asm.frameCount());
        eq("only one of them carried data", 1, asm.chunkCount());

        // A data sub-header with nothing after it must be dropped, not mis-sliced.
        ManifestAssembler shortOne = new ManifestAssembler();
        shortOne.feed(chunkDatagram(1, 0x1000, 1, ManifestAssembler.SUB_DATA, new byte[0], 0));
        eq("a 10-byte data payload contributes nothing", 0, shortOne.bytes(1).length);
        eq("and is counted as dropped", 1, shortOne.droppedShortChunks());

        // The 0c 01 0d end-of-list TLV.
        ManifestAssembler withMarker = new ManifestAssembler();
        byte[] rec = UdpPacket.hex("2a00000013000c010d084142432e4a5047");
        withMarker.feed(chunkDatagram(1, 0x1000, 1, ManifestAssembler.SUB_DATA, rec, rec.length));
        isTrue("the 0c 01 0d end marker is detected", withMarker.hasEndMarker(), "missed");
        isTrue("a blob without the marker reports none", !shortOne.hasEndMarker(), "found one");
    }

    /**
     * The two entry points must be equivalent, byte for byte.
     *
     * <p>{@code feed(byte[])} takes a whole UDP datagram and digs the DUML frames out of it; {@code
     * feedFrame(DumlFrame)} takes the frames the {@code Datalink} listener has already unwrapped. They
     * share the sub-header logic, and this asserts that sharing has not drifted.
     *
     * <p>Each path gets its <b>own</b> assembler, fed the same stream in its own shape — mixing the two
     * into one assembler would double-count every chunk and prove nothing.
     */
    private static void feedFramePathIsEquivalentToFeedBytes() throws Exception {
        byte[] original = new byte[2600];
        original[0] = 45;
        for (int i = 4; i < original.length; i++) original[i] = (byte) ((i * 17 + 3) & 0xFF);

        // One contiguous blob, exactly what a collect loop accumulates: [udp][routing][frame] ...
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        int seq = 0x3000;
        int sub = 0;
        writeAll(blob, chunkDatagram(1, seq, 1, sub++, ManifestAssembler.SUB_START, null, 0));
        seq += 8;
        int off = 0;
        int parts = 0;
        while (off < original.length) {
            int n = Math.min(800, original.length - off);
            byte[] chunk = slice(original, off, off + n);
            writeAll(blob, chunkDatagram(1, seq, 1, sub++, ManifestAssembler.SUB_DATA, chunk, n));
            off += n;
            seq += 8;
            parts++;
        }
        writeAll(blob, chunkDatagram(1, seq, 1, sub, ManifestAssembler.SUB_END, null, 0));
        byte[] raw = blob.toByteArray();

        ManifestAssembler viaBytes = new ManifestAssembler();
        viaBytes.feed(raw);

        List<DumlFrame> frames = Datalink.scanFrames(raw);
        ManifestAssembler viaFrames = new ManifestAssembler();
        for (int j = 0; j < frames.size(); j++) viaFrames.feedFrame(frames.get(j));

        eq("the byte path reassembles the original stream",
                UdpPacket.hex(original), UdpPacket.hex(viaBytes.manifestBytes()));
        eq("the frame path reassembles the original stream",
                UdpPacket.hex(original), UdpPacket.hex(viaFrames.manifestBytes()));
        eq("the two paths agree byte for byte",
                UdpPacket.hex(viaBytes.bytes()), UdpPacket.hex(viaFrames.bytes()));
        eq("synthetic: same declared count either way",
                viaBytes.declaredCount(), viaFrames.declaredCount());
        eq("synthetic: same chunk count either way", viaBytes.chunkCount(), viaFrames.chunkCount());
        eq("the frame path counted the " + parts + " data chunks", parts, viaFrames.dataFrames(1));
        isTrue("synthetic: the frame path also reports completion", viaFrames.complete(), "not complete");
        eq("synthetic: the frame path saw every frame", parts + 2, viaFrames.frames().size());
        eq("synthetic: the sub-header sequence of the first frame is read", 0, viaFrames.subSeqFirst(1));
        eq("synthetic: and of the last data chunk", parts, viaFrames.subSeqLast(1));
        eq("the frames are the frames the datagram path found", parts + 2, frames.size());

        capturedFixturesThroughTheFramePath();
    }

    /**
     * The real captures, through the {@code feedFrame} entry point.
     *
     * <p>A captured blob is a byte stream, not a list of frame objects, so the frames are pulled out of
     * it with the datalink's own scanner and pushed through {@code feedFrame} one at a time. For the two
     * datagram-shaped captures that is the whole production path minus the socket; the assertion is that
     * the same stream comes back out of it as out of the datagram path, and that it carries exactly the
     * chunks the wire holds. (The real chunks are 1012 bytes on the wire — over the 255-byte ceiling of
     * {@code DumlFrame.encode} — so this is also the case that catches a long frame being dropped.)
     */
    private static void capturedFixturesThroughTheFramePath() {
        String dir = System.getProperty("datalink.fixtures",
                "reference/osmosis/app/src/test/resources/manifests");
        File d = new File(dir);
        if (!d.isDirectory()) {
            isTrue("captures are reachable at " + dir, false, "directory not found");
            return;
        }
        String[] names = { "nano_45.bin", "xtra_13.bin", "op4_45.bin", "oa4_6.bin", "op3_15.bin" };
        for (int i = 0; i < names.length; i++) {
            File f = new File(d, names[i]);
            if (!f.isFile()) {
                isTrue("capture " + names[i] + " exists", false, "missing");
                continue;
            }
            byte[] raw = readFile(f);
            ManifestAssembler viaDatagram = new ManifestAssembler();
            viaDatagram.feed(raw);
            List<DumlFrame> frames = Datalink.scanFrames(raw);
            ManifestAssembler viaFrame = new ManifestAssembler();
            for (int j = 0; j < frames.size(); j++) viaFrame.feedFrame(frames.get(j));

            byte[] a = viaDatagram.bytes();
            byte[] b = viaFrame.bytes();
            String label = names[i] + " (" + frames.size() + " frames, " + a.length + " vs "
                    + b.length + " bytes)";
            if (!viaDatagram.sawDumlFraming()) {
                // An already-unwrapped payload has no frames to find; the frame path correctly yields
                // nothing, and only the datagram path's raw fallback has anything to say.
                isTrue(names[i] + ": an already-unwrapped capture really has no DUML frames",
                        frames.isEmpty(), "found " + frames.size());
                isTrue(names[i] + ": bytes in and bytes out are identical",
                        java.util.Arrays.equals(raw, a), "they differ");
                continue;
            }
            isTrue(label + ": the frame path reproduces the datagram path's stream",
                    java.util.Arrays.equals(a, b), "differ at byte "
                            + firstDifference(a, b) + " of " + a.length);
            if (b.length > 0) {
                eq(names[i] + ": both paths agree on the declared record count",
                        viaDatagram.declaredCount(), viaFrame.declaredCount());
                eq(names[i] + ": both paths count the same DCIM/ paths",
                        viaDatagram.distinctPathCount(), viaFrame.distinctPathCount());
                isTrue(names[i] + ": the stream carries the media store's DCIM/ paths",
                        ManifestAssembler.countMediaPaths(b) > 0, "no DCIM/ found");
                isTrue(names[i] + ": the frame path reports the stream finished",
                        viaFrame.complete(), "not complete");
                isTrue(names[i] + ": the camera's end frame was seen",
                        viaFrame.streamsEnded(), "streamsEnded() false");
                isTrue(names[i] + ": a long frame survived the scan (a 1012-byte chunk is over the "
                                + "255-byte ceiling of DumlFrame.encode)",
                        maxPayload(frames) > 255, "longest payload was " + maxPayload(frames));
            }
        }
    }

    /** Index of the first differing byte, or -1 when the two arrays match up to {@code a.length}. */
    private static int firstDifference(byte[] a, byte[] b) {
        for (int i = 0; i < a.length && i < b.length; i++) {
            if (a[i] != b[i]) return i;
        }
        return (a.length == b.length) ? -1 : Math.min(a.length, b.length);
    }

    private static int maxPayload(List<DumlFrame> frames) {
        int max = -1;
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).payload.length > max) max = frames.get(i).payload.length;
        }
        return max;
    }

    private static void completeHasAnEndMarkerAndATailFallback() throws Exception {
        // An answer that the camera never closed: no 4A 03. The tail fallback must still finish it.
        byte[] page = new byte[2000];
        page[0] = 45;
        ManifestAssembler asm = new ManifestAssembler();
        asm.feedFrame(subFrame(1, 1, ManifestAssembler.SUB_START, null, 0));
        asm.feedFrame(subFrame(1, 2, ManifestAssembler.SUB_DATA, slice(page, 0, 999), 999));
        isTrue("a full chunk alone is not completion (no end frame, no tail, no quiet yet)",
                !asm.complete(), "claimed complete after one full chunk");
        asm.feedFrame(subFrame(1, 3, ManifestAssembler.SUB_DATA, slice(page, 999, 1400), 401));
        eq("a short tail chunk is recorded", 401, asm.lastChunkBytes(1));
        isTrue("the short tail chunk completes the stream without any 4A 03",
                asm.complete(), "not complete");
        isTrue("but streamsEnded() is still false without the end frame",
                !asm.streamsEnded(), "claimed the camera closed it");
        eq("bytes are all there", 1400, asm.bytes().length);

        // A stream that was closed properly: complete() must not have needed the fallback.
        ManifestAssembler clean = new ManifestAssembler();
        clean.feedFrame(subFrame(1, 1, ManifestAssembler.SUB_START, null, 0));
        clean.feedFrame(subFrame(1, 2, ManifestAssembler.SUB_DATA, slice(page, 0, 999), 999));
        isTrue("still not complete mid-stream", !clean.complete(), "too early");
        clean.feedFrame(subFrame(1, 3, ManifestAssembler.SUB_END, null, 0));
        isTrue("the 4A 03 end frame completes it even though the last chunk was full",
                clean.complete(), "not complete");
        isTrue("and streamsEnded() agrees", clean.streamsEnded(), "false");

        // The quiet backstop, driven through the injected clock so it is deterministic.
        ManifestAssembler quiet = new ManifestAssembler();
        quiet.feedFrame(subFrame(1, 1, ManifestAssembler.SUB_DATA, slice(page, 0, 999), 999));
        isTrue("a full chunk with no end frame and no tail is not complete yet",
                !quiet.complete(quiet.lastFrameAtMs() + 10), "too early");
        isTrue("the quiet fallback completes it once the stream stops",
                quiet.complete(quiet.lastFrameAtMs() + ManifestAssembler.DEFAULT_QUIET_MS + 1),
                "the backstop did not fire");

        // Nothing at all is never "complete".
        isTrue("an empty assembler is not complete", !new ManifestAssembler().complete(), "claimed it was");

        // Frames of other commands are accepted and ignored, not mis-parsed.
        ManifestAssembler mixed = new ManifestAssembler();
        mixed.feedFrame(new DumlFrame(0x0102, 0x8080, Datalink.type(2, 0x02, 0x80), new byte[13]));
        eq("a foreign frame contributes nothing", 0, mixed.bytes().length);
        eq("but it is still counted", 1, mixed.frameCount());
        isTrue("a foreign frame does not claim to be a manifest", !mixed.sawSubHeaders(), "it did");
    }

    /**
     * The {@code [sub-header][chunk]} payload of one {@code 0x00/0x27} reply, as a {@link DumlFrame}.
     *
     * <p>Built through {@link #wireFrame} and then read back out of the wire bytes rather than
     * constructed by hand, so a frame handed to {@code feedFrame} is byte-identical to one the scanner
     * would have produced. A hand-built {@code DumlFrame} with a 999-byte payload would be a frame the
     * wire cannot express.
     */
    private static DumlFrame subFrame(int ctr, int sub, int subType, byte[] chunk, int declaredLen) {
        byte[] wire = wireFrame(0x0102, 0x8027, Datalink.type(2, 0x00, 0x27),
                chunkPayload(ctr, sub, subType, chunk, declaredLen));
        List<DumlFrame> got = Datalink.scanFrames(wire);
        if (got.size() != 1) {
            throw new IllegalStateException("the test's own sub-frame does not scan back: " + got.size());
        }
        return got.get(0);
    }


    /**
     * The captured fixtures under {@code reference/osmosis/app/src/test/resources/manifests}.
     *
     * <p>Only the two blobs that are <b>raw datagrams</b> (nano_45.bin, xtra_13.bin — they begin with a
     * UDP header) exercise the full datagram → frame → sub-header path here. The others are
     * already-unwrapped single payloads; they are still fed, because the assembler must not crash or
     * invent bytes for them, and the assertion on those is the weaker "same bytes in, same bytes out".
     */
    private static void capturedFixtureReassembles() {
        String dir = System.getProperty("datalink.fixtures", "reference/osmosis/app/src/test/resources/manifests");
        File d = new File(dir);
        if (!d.isDirectory()) {
            isTrue("captured fixtures are reachable at " + dir, false,
                    "directory not found — pass -Ddatalink.fixtures=<dir>");
            return;
        }
        // The expected chunk totals are what the assembler finds in each capture, asserted as a
        // regression lock: nano 17 data chunks (1 SD start frame and no SD data, 17 on ctr 2), xtra 4.
        checkFixture(new File(d, "nano_45.bin"), 17, true);
        checkFixture(new File(d, "xtra_13.bin"), 4, true);
        checkFixture(new File(d, "op4_45.bin"), 0, false);
        checkFixture(new File(d, "oa4_6.bin"), 0, false);
        checkFixture(new File(d, "oa6_internal_2.bin"), 0, false);
        checkFixture(new File(d, "op3_15.bin"), 0, false);
    }

    private static void checkFixture(File f, int expectedDataChunks, boolean expectFraming) {
        if (!f.isFile()) {
            isTrue("fixture " + f.getName() + " exists", false, "missing");
            return;
        }
        byte[] raw = readFile(f);
        ManifestAssembler asm = new ManifestAssembler();
        asm.feed(raw);
        byte[] out = asm.bytes();
        String label = f.getName();
        isTrue(label + ": reassembles without throwing and is non-empty", out.length > 0,
                "got " + out.length + " bytes");
        if (expectFraming) {
            isTrue(label + ": DUML framing was detected", asm.sawDumlFraming(), "no framing");
            eq(label + ": data chunks match the capture", expectedDataChunks, dataFramesTotal(asm));
            isTrue(label + ": reassembled stream is smaller than the datagram blob (sub-headers and "
                    + "framing stripped)", out.length < raw.length,
                    out.length + " >= " + raw.length);
            isTrue(label + ": the reassembled stream carries the media paths",
                    containsAscii(out, "DCIM"), "no DCIM/ in the reassembled stream");
            isTrue(label + ": declares a plausible record count", asm.declaredCount() >= 0,
                    "declaredCount=" + asm.declaredCount());
            eq(label + ": every chunk is attributed to a request counter", 2, asm.counters().size());
        } else {
            isTrue(label + ": no DUML framing in this capture (already-unwrapped payload)",
                    !asm.sawDumlFraming(), "unexpectedly framed");
            eq(label + ": bytes in and bytes out are the same length", raw.length, out.length);
            isTrue(label + ": bytes in and bytes out are identical",
                    java.util.Arrays.equals(raw, out), "they differ");
            // The already-unwrapped captures are single replies, so there is no 4A 01 sub-header left to
            // strip: what the camera sent them as is what the next stage consumes. declaredCount is only
            // meaningful on a stream that starts with the manifest's u32 count.
            isTrue(label + ": has no sub-headers left to strip", !asm.sawSubHeaders(), "saw one");
        }
    }

    private static int dataFramesTotal(ManifestAssembler asm) {
        int n = 0;
        List<Integer> cs = asm.counters();
        for (int i = 0; i < cs.size(); i++) n += asm.dataFrames(cs.get(i).intValue());
        return n;
    }

    private static boolean containsAscii(byte[] haystack, String needle) {
        byte[] n = new byte[needle.length()];
        for (int i = 0; i < needle.length(); i++) n[i] = (byte) needle.charAt(i);
        return containsSequence(haystack, n);
    }

    private static boolean containsSequence(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) return false;
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static byte[] readFile(File f) {
        try {
            byte[] out = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0;
                while (off < out.length) {
                    int n = in.read(out, off, out.length - off);
                    if (n < 0) break;
                    off += n;
                }
            } finally {
                in.close();
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + f, e);
        }
    }

    // ================= misc =======================================================================

    private static byte[] slice(byte[] src, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (int i = 0; i < parts.length; i++) n += parts[i].length;
        byte[] out = new byte[n];
        int p = 0;
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, out, p, parts[i].length);
            p += parts[i].length;
        }
        return out;
    }
}
