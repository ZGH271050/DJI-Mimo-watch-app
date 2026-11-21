package com.watch.osmo.net;

import java.util.Locale;

/**
 * The two fixed headers of the DUML-over-UDP datalink, and nothing else.
 *
 * <p>Wire layering of one datagram:
 * {@code [8B udp hdr][12B routing hdr][DUML frame]}.
 *
 * <pre>
 * udp hdr  : (0x8000|totlen):u16le  session:u16le  seq:u16le  type:u8  xor:u8
 *   type   0x00 handshake / 0x01 telemetry / 0x03 acked-data / 0x04 ack / 0x05 command
 * routing  : last_cam_seq:u16le  this_seq:u16le  00 00 00 00  counter:u8  01  (drone?0x60:0x00)  00
 * </pre>
 *
 * <p>Port of the pure members of {@code dev.konraditurbe.osmosis.net.DumlTransport} (osmosis, MIT) —
 * {@code udpHeader} and {@code routingHeader} — plus the matching decoders, which the Kotlin original
 * never needed because it only ever built these headers and read the fields by literal offset.
 *
 * <p>Pure Java 8, {@code java.util} only, no Android APIs, so the whole datalink layer is desktop
 * unit-testable. Deliberately <b>not</b> a socket wrapper: the two outages in the reference project's
 * history were both wrong values in these twenty bytes, and neither was catchable while the builders
 * were tangled up with a socket.
 *
 * <p>All values are unsigned 0..65535 / 0..255; nothing here interprets them as signed.
 */
public final class UdpPacket {

    /** Transport header length. */
    public static final int UDP_HEADER_BYTES = 8;
    /** Command routing header length. */
    public static final int ROUTING_HEADER_BYTES = 12;
    /** Both fixed headers ahead of the DUML frame. */
    public static final int HEADER_BYTES = UDP_HEADER_BYTES + ROUTING_HEADER_BYTES;

    /** pktType: handshake (peer is asked to answer with one of these). */
    public static final int TYPE_HANDSHAKE = 0x00;
    /** pktType: telemetry / heartbeat; the sender's own seq lives in the routing header. */
    public static final int TYPE_TELEMETRY = 0x01;
    /** pktType: acked data. */
    public static final int TYPE_ACKED_DATA = 0x03;
    /** pktType: window acknowledgement (see {@link Datalink#sendAck()}). */
    public static final int TYPE_ACK = 0x04;
    /** pktType: DUML command (routing header + frame). */
    public static final int TYPE_COMMAND = 0x05;

    /** Bit 15 of word 0 is always set on every captured packet. */
    public static final int LENGTH_FLAG = 0x8000;
    /** Bits 14..0 of word 0 carry the total datagram length. */
    public static final int LENGTH_MASK = 0x3FFF;

    /** Byte 9 of the routing header is the low half of a little-endian 16-bit constant. */
    public static final int ROUTING_CONST_9 = 0x01;
    /** Byte 10 of the routing header: {@code 0x00} for a camera, {@code 0x60} for a DJI drone. */
    public static final int ROUTING_MARK_CAMERA = 0x00;
    /** Byte 10 of the routing header for a drone command. */
    public static final int ROUTING_MARK_DRONE = 0x60;

    /** Every send advances our sequence by this much, never by 1. */
    public static final int SEQ_STEP = 8;

    /**
     * Sessions are proposed in the range {@code 0x1000..0xFFFD}, and the base sequence is 8-aligned
     * ({@code 0x1000..0xF000}) — the same ranges the Kotlin
     * {@code Random.nextInt(0x1000, 0xFFFE)} / {@code Random.nextInt(0x1000, 0xF000) and 0xFFF8} draw
     * from. Kept as named constants so the self-test can assert the bounds.
     */
    public static final int SESSION_MIN = 0x1000;
    /** Exclusive upper bound for a session id. */
    public static final int SESSION_MAX_EXCLUSIVE = 0xFFFE;
    /** Lowest base sequence a session may propose. */
    public static final int BASE_SEQ_MIN = 0x1000;
    /** Exclusive upper bound for a base sequence. */
    public static final int BASE_SEQ_MAX_EXCLUSIVE = 0xF000;

    private UdpPacket() {
    }

    // ---- transport header -------------------------------------------------------------------------

    /**
     * Build the 8-byte transport header. Port of {@code DumlTransport.udpHeader}: the total length is
     * {@code 8 + payloadLen} with bit 15 set, and byte 7 is the XOR of the preceding seven.
     *
     * @param pktType   one of the {@code TYPE_*} constants
     * @param payloadLen bytes that follow this header (routing header included, for a command)
     * @param sessionId session id, low 16 bits used
     * @param seq       our UDP sequence at the moment of sending
     */
    public static byte[] encodeUdpHeader(int pktType, int payloadLen, int sessionId, int seq) {
        int total = UDP_HEADER_BYTES + payloadLen;
        int w0 = LENGTH_FLAG | (total & LENGTH_MASK);
        byte[] b = new byte[UDP_HEADER_BYTES];
        b[0] = (byte) (w0 & 0xFF);
        b[1] = (byte) ((w0 >>> 8) & 0xFF);
        b[2] = (byte) (sessionId & 0xFF);
        b[3] = (byte) ((sessionId >>> 8) & 0xFF);
        b[4] = (byte) (seq & 0xFF);
        b[5] = (byte) ((seq >>> 8) & 0xFF);
        b[6] = (byte) (pktType & 0xFF);
        b[7] = (byte) xor7(b);
        return b;
    }

    /** Total datagram length carried in word 0, flag bit removed. */
    public static int totalLength(byte[] datagram) {
        if (datagram == null || datagram.length < UDP_HEADER_BYTES) {
            throw new IllegalArgumentException("datagram shorter than the udp header");
        }
        return (((datagram[0] & 0xFF) | ((datagram[1] & 0xFF) << 8)) & LENGTH_MASK);
    }

    /** The high flag bit of word 0; true on every packet the reference ever captured. */
    public static boolean lengthFlagSet(byte[] datagram) {
        if (datagram == null || datagram.length < 2) return false;
        return (((datagram[0] & 0xFF) | ((datagram[1] & 0xFF) << 8)) & LENGTH_FLAG) != 0;
    }

    /** True when word 0's length and the XOR trailer both agree with {@code datagram.length}. */
    public static boolean verify(byte[] datagram) {
        if (datagram == null || datagram.length < UDP_HEADER_BYTES) return false;
        if (totalLength(datagram) != datagram.length) return false;
        return (datagram[7] & 0xFF) == xor7(datagram);
    }

    /** Session id from bytes 2-3. */
    public static int sessionOf(byte[] datagram) {
        require(datagram, 4, "session");
        return u16le(datagram, 2);
    }

    /** Sequence from bytes 4-5 — the sender's own sequence position. */
    public static int seqOf(byte[] datagram) {
        require(datagram, 6, "seq");
        return u16le(datagram, 4);
    }

    /** pktType from byte 6. */
    public static int typeOf(byte[] datagram) {
        require(datagram, 7, "type");
        return datagram[6] & 0xFF;
    }

    /** Trailing XOR byte, byte 7. */
    public static int xorOf(byte[] datagram) {
        require(datagram, 8, "xor");
        return datagram[7] & 0xFF;
    }

    /** XOR of bytes 0..6 of the current buffer — what byte 7 must equal. */
    public static int xor7(byte[] datagram) {
        int xor = 0;
        for (int i = 0; i < 7 && i < datagram.length; i++) xor ^= (datagram[i] & 0xFF);
        return xor & 0xFF;
    }

    /** The payload after the 8-byte header (the routing header, for a command packet). */
    public static byte[] stripUdpHeader(byte[] datagram) {
        require(datagram, UDP_HEADER_BYTES, "udp header");
        byte[] out = new byte[datagram.length - UDP_HEADER_BYTES];
        System.arraycopy(datagram, UDP_HEADER_BYTES, out, 0, out.length);
        return out;
    }

    // ---- routing header ---------------------------------------------------------------------------

    /**
     * Build the 12-byte command routing header. Port of {@code DumlTransport.routingHeader}.
     *
     * <p>Bytes 0-1 are our <b>own previous</b> sequence ({@code seq - 8}) — never the peer's channel.
     * Bytes 2-3 are this command's sequence. Byte 8 is a command counter, byte 9 is {@code 0x01},
     * byte 10 is {@code 0x00} for a camera and {@code 0x60} for a drone.
     */
    public static byte[] encodeRoutingHeader(int seq, int cmdCounter, boolean drone) {
        int ack = (seq - SEQ_STEP) & 0xFFFF;
        byte[] b = new byte[ROUTING_HEADER_BYTES];
        b[0] = (byte) (ack & 0xFF);
        b[1] = (byte) ((ack >>> 8) & 0xFF);
        b[2] = (byte) (seq & 0xFF);
        b[3] = (byte) ((seq >>> 8) & 0xFF);
        b[4] = 0;
        b[5] = 0;
        b[6] = 0;
        b[7] = 0;
        b[8] = (byte) (cmdCounter & 0xFF);
        b[9] = (byte) ROUTING_CONST_9;
        b[10] = (byte) (drone ? ROUTING_MARK_DRONE : ROUTING_MARK_CAMERA);
        b[11] = 0;
        return b;
    }

    /** {@link #encodeRoutingHeader(int, int, boolean)} for a camera command. */
    public static byte[] encodeRoutingHeader(int seq, int cmdCounter) {
        return encodeRoutingHeader(seq, cmdCounter, false);
    }

    /** Bytes 0-1: the ack of our own previous sequence. */
    public static int routingAck(byte[] routing) {
        require(routing, 2, "routing ack");
        return u16le(routing, 0);
    }

    /** Bytes 2-3: this packet's sequence. */
    public static int routingSeq(byte[] routing) {
        require(routing, 4, "routing seq");
        return u16le(routing, 2);
    }

    /** Byte 8: the command counter. */
    public static int routingCounter(byte[] routing) {
        require(routing, 9, "routing counter");
        return routing[8] & 0xFF;
    }

    /** True when byte 10 marks a drone command ({@code 0x60}) rather than a camera one. */
    public static boolean routingIsDrone(byte[] routing) {
        require(routing, 11, "routing mark");
        return (routing[10] & 0xFF) == ROUTING_MARK_DRONE;
    }

    // ---- peer-beacon helpers ----------------------------------------------------------------------

    /**
     * The peer's own sequence channel, read from bytes 8-9 of <b>its</b> routing header — the value
     * {@code DumlTransport.recvAll} learns from every datagram it receives and the whole session then
     * syncs to ({@code startSeq = channel + 8}).
     *
     * @return the channel, or -1 when this datagram is too short to carry one
     */
    public static int cameraChannelOf(byte[] datagram) {
        if (datagram == null || datagram.length < 10) return -1;
        return u16le(datagram, 8);
    }

    /**
     * First UDP sequence a session may use against a peer whose channel is {@code channel}. The Kotlin
     * original is {@code DumlTransport.syncSeqToPeerChannel}: {@code udpSeq = (camChannel + 8) & 0xFFFF}.
     */
    public static int startSeqFor(int channel) {
        return (channel + SEQ_STEP) & 0xFFFF;
    }

    /**
     * True when the peer answered on a sequence space of its own rather than echoing the base we
     * proposed in the handshake — {@code DumlSession.sequenceSpaceMismatched}. Reported, never acted on.
     */
    public static boolean sequenceSpaceMismatched(int cameraChannel, int baseSeq) {
        return cameraChannel != baseSeq;
    }

    // ---- shared little-endian helpers ---------------------------------------------------------------

    /** Unaligned unsigned 16-bit little-endian read. */
    public static int u16le(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8);
    }

    /** Unaligned unsigned 32-bit little-endian read. */
    public static long u32le(byte[] b, int offset) {
        return (b[offset] & 0xFFL)
                | ((b[offset + 1] & 0xFFL) << 8)
                | ((b[offset + 2] & 0xFFL) << 16)
                | ((b[offset + 3] & 0xFFL) << 24);
    }

    /** {@code (v and 0xFF), ((v shr 8) and 0xFF), ...} — Kotlin's {@code le32}. */
    public static byte[] le32(int v) {
        return new byte[] {
            (byte) (v & 0xFF), (byte) ((v >>> 8) & 0xFF),
            (byte) ((v >>> 16) & 0xFF), (byte) ((v >>> 24) & 0xFF),
        };
    }

    /** Parse a hex string, ignoring whitespace. Kotlin's {@code DumlTransport.hex}. */
    public static byte[] hex(String s) {
        StringBuilder clean = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) clean.append(c);
        }
        if ((clean.length() & 1) != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex digit");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Lower-case hex, Kotlin's {@code "%02x".format(it)} join. */
    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            sb.append(Character.forDigit((b[i] >>> 4) & 0x0F, 16));
            sb.append(Character.forDigit(b[i] & 0x0F, 16));
        }
        return sb.toString();
    }

    /** {@code 0x%04x} formatting used by the reference logs. */
    public static String hex4(int v) {
        return String.format(Locale.ROOT, "0x%04x", v & 0xFFFF);
    }

    private static void require(byte[] b, int len, String what) {
        if (b == null || b.length < len) {
            throw new IllegalArgumentException("datagram shorter than the " + what);
        }
    }
}
