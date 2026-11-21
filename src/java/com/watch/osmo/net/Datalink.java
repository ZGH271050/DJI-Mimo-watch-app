package com.watch.osmo.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import com.watch.osmo.duml.Bytes;
import com.watch.osmo.duml.Crc;
import com.watch.osmo.duml.DumlFrame;
import com.watch.osmo.duml.OsmoCommands;

/**
 * The DUML-over-UDP datalink for an Osmo camera: socket, session, sequencing and framing.
 *
 * <p>Port of {@code dev.konraditurbe.osmosis.net.DumlTransport} + {@code DumlSession} (osmosis, MIT),
 * plus the camera half of {@code CameraSession}'s registration flow. Layering is the same as there:
 * this class owns the wire and the session preamble, {@link ManifestAssembler} owns the media-list
 * reassembly, and the record decode lives elsewhere ({@code com.watch.osmo.proto}).
 *
 * <p>Wire layers per datagram: {@code [8B udp hdr][12B routing hdr][DUML frame]} — see
 * {@link UdpPacket} for the layout and the two documented regressions the routing header caused.
 *
 * <p>Sequence discipline, which is the one thing that has to be right:
 * <ul>
 *   <li>every datagram the peer sends carries <b>its</b> sequence at bytes 8-9 of the routing header.
 *       That value is the camera channel.</li>
 *   <li>our own send sequence must <b>start</b> at {@code cameraChannel + 8} ({@link #syncSeqToPeerChannel()}).</li>
 *   <li>we then advance by 8 per datagram ({@link UdpPacket#SEQ_STEP}).</li>
 *   <li>the routing ack (bytes 0-1) is our <b>own previous</b> sequence, never the camera's telemetry
 *       seq — that mistake forced a fresh registered session for every write.</li>
 * </ul>
 *
 * <p>Blocking; the caller is expected to run {@link #handshake} and the registration calls on a
 * background thread. Pure Java 8: {@code java.net.DatagramSocket} only, no Android APIs, so the whole
 * layer is desktop-testable — {@code DatalinkSelfTest} drives it against a loopback fake camera.
 *
 * <p>Not ported, deliberately: the drone path ({@code bindLocalPort}, {@code droneRouting},
 * {@code sendDumlRaw}, the {@code 0x51} tunnel re-stamping) and the TCP-7001 pre-poke. This client
 * only ever talks to a camera, whose AP needs no poke.
 */
public final class Datalink {

    // ---- protocol constants ------------------------------------------------------------------------

    /**
     * Datagram ports the datalink may live on. The handshake payload is identical on both, so the
     * client tries each in turn and uses whichever answers: Nano/360/Pocket 3 answer on 9004, the Xtra
     * Edge Pro / Action 5 Pro on 10004.
     */
    public static final int[] PORTS = { 9004, 10004 };

    /** The 40-byte handshake (SYN) payload, with a 16-bit placeholder at bytes 0-1 for the base seq. */
    public static final String HANDSHAKE_TEMPLATE =
            "000064006400c005140000640000019001c005140000640014006400c00514000064000101040102";

    /**
     * Sub-command 0x17 of {@code 0x00/0x88} — the app telling the camera it is still here, ASCII
     * {@code APP} at bytes 5-7. Sent at registration and about once a second for the whole session:
     * the reference found the camera drops its browsing state without the beat.
     */
    public static final byte[] APP_PRESENCE = UdpPacket.hex("170046237c415050000000000002");

    /** Keep-alive period. The camera AP sleeps after roughly 10 s of silence. */
    public static final int KEEPALIVE_INTERVAL_MS = 2000;
    /** Presence beats per keep-alive interval — the reference's ~1 Hz cadence. */
    public static final int KEEPALIVE_TICKS_PER_BEAT = 2;
    /** Window/message size we advertise in the handshake: window 100, MTU 1472 (the {@code c0 05}). */
    public static final int HANDSHAKE_WINDOW = 100;
    /** MTU advertised in the handshake. */
    public static final int HANDSHAKE_MTU = 1472;
    /** Read timeout on the socket, matching the reference. */
    public static final int SO_TIMEOUT_MS = 200;
    /** Handshake attempts per port. */
    public static final int HANDSHAKE_ATTEMPTS = 20;
    /** Per-attempt listen window during the handshake. */
    public static final int HANDSHAKE_RECV_MS = 350;
    /** Registration / media-list drain windows. */
    public static final int DRAIN_MS = 400;
    /** Heartbeat-drain rounds after the handshake; the reference does 5. */
    public static final int CHANNEL_DRAIN_ROUNDS = 5;
    /** First subscription id, as Mimo/moblin use it. */
    public static final int FIRST_SUB_ID = 0x69DF;

    private static final String[] DEFAULT_SUBS = {
        "camcap_mode_profile", "camcap_video_format", "camcap_fov", "camcap_iso",
        "camcap_photo_storage_format", "camcap_color_mode", "cam_storage", "cam_status",
    };

    /** Callbacks. Every one may be invoked from the thread that calls into this class. */
    public interface Listener {
        /** Free-form progress/diagnostic line. Never null-checked by the caller. */
        void onLog(String s);

        /** The peer's sequence channel, learned from the first datagram that carried one. */
        void onChannelLearned(int channel);

        /** Every DUML frame found in an inbound datagram, CRCs already verified. */
        void onFrame(DumlFrame frame);

        /** The link could not be brought up. Terminal for the attempt. */
        void onFailure(String reason);

        /** The link was up and has gone quiet, or the socket failed. */
        void onLost();
    }

    private final Listener listener;
    private final Random random = new Random();

    /** Guards every counter and both cursors: the keep-alive thread and the caller both send. */
    private final Object lock = new Object();
    /** Guards {@link #sock} against a concurrent {@link #close()}. */
    private final Object socketLock = new Object();

    private DatagramSocket sock;
    private InetAddress peer;
    private String host;

    private boolean closed;
    private boolean handshaken;
    private int port = -1;
    private int sessionId;
    private int baseSeq = 0xB887;
    private int udpSeq;
    private int dumlSeq = 0xA000;
    private int camChannel;
    private int cmdCounter;
    private int channelLogState;
    private boolean channelReported;
    private int peerCursor;
    private int peerDownloadCursor;
    private boolean sequenceMismatchReported;

    private volatile boolean keepaliveOn;
    private Thread keepaliveThread;

    /**
     * The camera's own answer to "am I in playback", from bit 30 of the flags word in its
     * {@code 0x02/0x80} status frame. Entering playback is a command we send and the camera's ack only
     * means "received" — this bit is the only proof it actually switched. A camera left in capture
     * serves part of the file list and then goes quiet, which is exactly what the watch saw.
     */
    private volatile boolean playbackKnown;
    private volatile boolean playbackIn;

    /** Wire tracing budget: the first few hundred packets of a session, then it shuts up. */
    private static final java.util.concurrent.atomic.AtomicInteger TRACE_LEFT =
            new java.util.concurrent.atomic.AtomicInteger(1200);

    private byte[] lastSentPacket;

    public Datalink(Listener l) {
        if (l == null) throw new IllegalArgumentException("listener is null");
        this.listener = l;
    }

    // ---- pure, socket-free helpers -----------------------------------------------------------------

    /**
     * The handshake (SYN) frame: the fixed 40-byte payload with our freshly drawn base sequence in its
     * first two bytes. Port of {@code DumlSession.handshakeFrame}.
     *
     * <p>Only bytes 0-1 vary. The base used to be the constant {@code 0xb887} on every connect, which
     * an independent reverse-engineering of this transport reports can leave the peer's session state
     * wedged — a link that handshakes and then answers nothing. The official app draws a fresh one.
     */
    public static byte[] handshakeFrame(int baseSeq) {
        byte[] f = UdpPacket.hex(HANDSHAKE_TEMPLATE);
        f[0] = (byte) (baseSeq & 0xFF);
        f[1] = (byte) ((baseSeq >>> 8) & 0xFF);
        return f;
    }

    /** A fresh session id in the reference's range. */
    public static int randomSessionId(Random r) {
        int span = UdpPacket.SESSION_MAX_EXCLUSIVE - UdpPacket.SESSION_MIN;
        return UdpPacket.SESSION_MIN + r.nextInt(span);
    }

    /** A fresh, 8-aligned base sequence in the reference's range. */
    public static int randomBaseSeq(Random r) {
        int span = UdpPacket.BASE_SEQ_MAX_EXCLUSIVE - UdpPacket.BASE_SEQ_MIN;
        return (UdpPacket.BASE_SEQ_MIN + r.nextInt(span)) & 0xFFF8;
    }

    /** Is this datagram the peer's answer to our handshake? {@code pktType == 0x00} and 8 bytes up. */
    public static boolean isHandshakeReply(byte[] datagram) {
        return datagram != null && datagram.length >= 8
                && (datagram[6] & 0xFF) == UdpPacket.TYPE_HANDSHAKE;
    }

    /**
     * The 8-byte transport header for one packet. Instance form of {@link UdpPacket#encodeUdpHeader}.
     */
    public byte[] udpHeader(int pktType, int payloadLen, int session, int seq) {
        return UdpPacket.encodeUdpHeader(pktType, payloadLen, session, seq);
    }

    /** The instance's transport header for the current session and sequence. */
    public byte[] udpHeader(int pktType, int payloadLen) {
        synchronized (lock) {
            return UdpPacket.encodeUdpHeader(pktType, payloadLen, sessionId, udpSeq);
        }
    }

    /** The 12-byte routing header for our current sequence and command counter. */
    public byte[] routingHeader() {
        synchronized (lock) {
            return UdpPacket.encodeRoutingHeader(udpSeq, cmdCounter, false);
        }
    }

    /**
     * The 34-byte {@code pktType-0x04} window acknowledgement.
     *
     * <p>Three {@code [u16 start][u16 end][u32 zero]} groups — video, download, control — plus a
     * trailing {@code [u16 0]}. The camera runs each as a sliding reliable window and will not keep
     * streaming a window it does not see acked. The <b>download</b> group is the one the media manifest
     * rides, so it echoes the peer's own download cursor; the control group stays at our base
     * deliberately (copying our runaway send seq there broke pagination outright). Sent with seq 0,
     * like every other type-0x04.
     */
    public byte[] ackPayload() {
        synchronized (lock) {
            return ackPayload(peerCursor, peerDownloadCursor, baseSeq);
        }
    }

    /** {@link #ackPayload()} for explicit cursor values — pure, so the layout is assertable. */
    public static byte[] ackPayload(int videoCursor, int downloadCursor, int controlBase) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(34);
        writeGroup(out, videoCursor);
        writeGroup(out, downloadCursor);
        writeGroup(out, controlBase);
        out.write(0);
        out.write(0);
        return out.toByteArray();
    }

    private static void writeGroup(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);
    }

    /**
     * Build a {@code pktType-0x05} command datagram from its parts: transport header, routing header,
     * DUML frame. Pure — no socket, no instance state — which is what makes the wire format testable.
     *
     * @param session  session id
     * @param seq      our sequence at send time (the routing ack is derived from it)
     * @param counter  command counter for routing byte 8
     * @param target   DUML target, e.g. {@code OsmoCommands.TARGET_APP_TO_CAMERA}
     * @param id       DUML message id
     * @param type     24-bit DUML type: {@code (cmdType << 5) | (cmdSet << 8) | (cmdId << 16)}
     */
    public static byte[] commandPacket(int session, int seq, int counter, int target, int id,
                                       int type, byte[] payload) {
        byte[] duml = new DumlFrame(target, id, type, payload).encode();
        byte[] routing = UdpPacket.encodeRoutingHeader(seq, counter, false);
        return packet(session, seq, UdpPacket.TYPE_COMMAND, routing, duml);
    }

    /** {@code pktType-0x04} ack datagram around {@link #ackPayload(int, int, int)}. */
    public static byte[] ackPacket(int session) {
        byte[] payload = ackPayload(0, 0, 0xB887);
        byte[] hdr = UdpPacket.encodeUdpHeader(UdpPacket.TYPE_ACK, payload.length, session, 0);
        return concat(hdr, payload);
    }

    /** {@code pktType-0x00} handshake datagram around {@link #handshakeFrame(int)}. */
    public static byte[] handshakePacket(int session, int seq, int base) {
        byte[] payload = handshakeFrame(base);
        byte[] hdr = UdpPacket.encodeUdpHeader(UdpPacket.TYPE_HANDSHAKE, payload.length, session, seq);
        return concat(hdr, payload);
    }

    /** A raw datagram from header pieces plus the payload that follows the routing header. */
    public static byte[] packet(int session, int seq, int pktType, byte[] routing, byte[] afterRouting) {
        int payloadLen = (routing == null ? 0 : routing.length)
                + (afterRouting == null ? 0 : afterRouting.length);
        byte[] hdr = UdpPacket.encodeUdpHeader(pktType, payloadLen, session, seq);
        return concat(hdr, routing, afterRouting);
    }

    /**
     * Every CRC-valid DUML frame in {@code raw}, in the order they start.
     *
     * <p>Two things this must get right that a naive scan does not. Length is 10 bits plus a 6-bit
     * version packed little-endian into bytes 1-2, so a frame over 255 bytes has byte2
     * {@code 0x05}/{@code 0x06}/{@code 0x07} rather than {@code 0x04} — matching only {@code 0x04} hides
     * every long frame, which is exactly where a manifest lives. And it advances one byte at a time, not
     * by the frame length, because a peer can wrap a reply inside another frame. Verifying both CRCs is
     * what makes byte-at-a-time scanning safe from false positives.
     */
    public static List<DumlFrame> scanFrames(byte[] raw) {
        List<DumlFrame> out = new ArrayList<DumlFrame>();
        if (raw == null) return out;
        int i = 0;
        while (i + 13 <= raw.length) {
            if ((raw[i] & 0xFF) != DumlFrame.SOF) {
                i++;
                continue;
            }
            int len = ((raw[i + 1] & 0xFF) | ((raw[i + 2] & 0xFF) << 8)) & 0x3FF;
            int ver = (raw[i + 2] & 0xFF) >>> 2;
            if (ver != 1 || len < 13 || i + len > raw.length) {
                i++;
                continue;
            }
            if (Crc.crc8(slice(raw, i, i + 3)) != (raw[i + 3] & 0xFF)) {
                i++;
                continue;
            }
            if (Crc.crc16(slice(raw, i, i + len - 2)) != UdpPacket.u16le(raw, i + len - 2)) {
                i++;
                continue;
            }
            try {
                out.add(frameOf(raw, i, len));
            } catch (RuntimeException ignored) {
                // CRCs already agreed; a construction failure here would mean a bug, not bad input.
            }
            i++;
        }
        return out;
    }

    /**
     * Build the {@link DumlFrame} that occupies {@code [i, i+len)} of a CRC-verified buffer.
     *
     * <p>The fields are read straight out of the buffer rather than through
     * {@link DumlFrame#decode(byte[])}. That is not a shortcut, it is required: {@code encode()}
     * writes the length as a single byte, so {@code decode()} demands {@code data.length == len & 0xFF}
     * and rejects every frame longer than 242 bytes as "Bad length". Long frames are exactly where a
     * media manifest lives — the real chunks in the captures are 1012 bytes on the wire, and their
     * third header byte is {@code 0x07} (10-bit length + 6-bit version) rather than {@code 0x04}. The
     * reference never hits this because it scans and hands back tuples; this front end promises a
     * {@link DumlFrame}, so it has to construct one.
     */
    private static DumlFrame frameOf(byte[] raw, int i, int len) {
        int target = UdpPacket.u16le(raw, i + 4);
        int id = UdpPacket.u16le(raw, i + 6);
        int type = (raw[i + 8] & 0xFF) | ((raw[i + 9] & 0xFF) << 8) | ((raw[i + 10] & 0xFF) << 16);
        return new DumlFrame(target, id, type, slice(raw, i + 11, i + len - 2));
    }

    /**
     * First CRC-valid reply frame with the given cmdset/cmd across {@code datagrams}, else null.
     *
     * <p>Skips the empty-payload transport ACK the camera sends before the real reply — that ACK is what
     * a naive "first frame with this cmdset" match grabs, making a command that landed look like a
     * failure.
     */
    public static DumlFrame findReply(List<byte[]> datagrams, int set, int cmd) {
        if (datagrams == null) return null;
        for (byte[] d : datagrams) {
            if (d == null) continue;
            int i = 0;
            while (i + 13 <= d.length) {
                if ((d[i] & 0xFF) != DumlFrame.SOF) {
                    i++;
                    continue;
                }
                int len = ((d[i + 1] & 0xFF) | ((d[i + 2] & 0xFF) << 8)) & 0x3FF;
                if (len < 13 || i + len > d.length) {
                    i++;
                    continue;
                }
                if (Crc.crc8(slice(d, i, i + 3)) != (d[i + 3] & 0xFF)) {
                    i++;
                    continue;
                }
                if ((d[i + 9] & 0xFF) == set && (d[i + 10] & 0xFF) == cmd) {
                    byte[] payload = slice(d, i + 11, i + len - 2);
                    if (payload.length > 0) return new DumlFrame(0, 0, 0, payload);
                }
                i += len;
            }
        }
        return null;
    }

    /**
     * The leading status word of the first reply frame with {@code set}/{@code id}, else null.
     * Port of {@code DumlTransport.findRespStatus}.
     */
    public static Integer findRespStatus(byte[] d, int set, int id) {
        if (d == null) return null;
        int i = 0;
        while (i + 13 <= d.length) {
            if ((d[i] & 0xFF) != DumlFrame.SOF) {
                i++;
                continue;
            }
            int len = ((d[i + 1] & 0xFF) | ((d[i + 2] & 0xFF) << 8)) & 0x3FF;
            if (len < 13 || i + len > d.length) {
                i++;
                continue;
            }
            if ((d[i + 9] & 0xFF) == set && (d[i + 10] & 0xFF) == id) {
                int ps = i + 11;
                int pe = i + len - 2;
                if (pe - ps >= 2) return Integer.valueOf(UdpPacket.u16le(d, ps));
                if (pe - ps >= 1) return Integer.valueOf(d[ps] & 0xFF);
                return Integer.valueOf(0);
            }
            i += len;
        }
        return null;
    }

    /** The 39-byte {@code 0x00/0x26} list-request payload with counter, cursor and page size applied. */
    public static byte[] listPayload(int ctr, long cursor, int pageSize) {
        byte[] p = UdpPacket.hex(
                "4a002a10010000000000010000002d000d0100ffffffffffffffff000100000000000000000000000000");
        p[4] = (byte) ctr;
        p[14] = (byte) pageSize;
        p[10] = (byte) (cursor & 0xFF);
        p[11] = (byte) ((cursor >>> 8) & 0xFF);
        p[12] = (byte) ((cursor >>> 16) & 0xFF);
        p[13] = (byte) ((cursor >>> 24) & 0xFF);
        return p;
    }

    /** {@link #listPayload(int, long, int)} at the page size Mimo uses (45). */
    public static byte[] listPayload(int ctr, long cursor) {
        return listPayload(ctr, cursor, OsmoCommands.PAGE_SIZE);
    }

    /**
     * A {@code 0x00/0x99} subscription payload, byte for byte as Mimo sends it:
     * {@code [02 02 00 00][subId u32-LE][00 00 00][innerLen u16-LE][nameLen u16-LE][name][00 00 00 00]}
     * with {@code innerLen = nameLen + 6}; the name is NOT padded. Port of
     * {@code CameraSession.subscription}.
     */
    public static byte[] subscriptionPayload(String name, int subId) {
        byte[] nameBytes = ascii(name);
        int innerLen = nameBytes.length + 6;
        Bytes w = new Bytes(4 + 4 + 3 + 2 + 2 + nameBytes.length + 4);
        w.writeBytes(new byte[] { 0x02, 0x02, 0x00, 0x00 });
        w.writeU32Le(subId);
        w.writeBytes(new byte[] { 0, 0, 0 });
        w.writeU16Le(innerLen);
        w.writeU16Le(nameBytes.length);
        w.writeBytes(nameBytes);
        w.writeBytes(new byte[] { 0, 0, 0, 0 });
        return w.toByteArray();
    }

    /** The status keys this client subscribes to — the reference's deliberately small set of eight. */
    public static String[] defaultSubscriptions() {
        return DEFAULT_SUBS.clone();
    }

    /** All default subscribe frames, ids running from {@link #FIRST_SUB_ID}. */
    public static List<DumlFrame> subscriptionFrames(int firstId) {
        String[] keys = defaultSubscriptions();
        List<DumlFrame> out = new ArrayList<DumlFrame>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            out.add(new DumlFrame(OsmoCommands.TARGET_APP_TO_SYSTEM, firstId + i,
                    type(2, 0x00, 0x99), subscriptionPayload(keys[i], FIRST_SUB_ID + i)));
        }
        return out;
    }

    /** The 24-bit DUML type word: {@code (cmdType << 5) | (cmdSet << 8) | (cmdId << 16)}. */
    public static int type(int cmdType, int cmdSet, int cmdId) {
        return ((cmdType & 0x07) << 5) | ((cmdSet & 0xFF) << 8) | ((cmdId & 0xFF) << 16);
    }

    /** The target word: {@code appId(0x02) | (((id << 5) | type) << 8)}. */
    public static int target(int receiverType, int receiverId) {
        return 0x02 | ((((receiverId & 0x07) << 5) | (receiverType & 0x1F)) << 8);
    }

    // ---- state -------------------------------------------------------------------------------------

    /** The port the handshake succeeded on, or -1 before {@link #handshake}. */
    public int port() {
        return port;
    }

    /** The session id drawn for this link. */
    public int sessionId() {
        return sessionId;
    }

    /** The base sequence proposed in the handshake. */
    public int baseSeq() {
        return baseSeq;
    }

    /** Our current send sequence — useful for diagnosing window drift. */
    public int seq() {
        synchronized (lock) {
            return udpSeq;
        }
    }

    /** The peer's own sequence channel, learned from bytes 8-9 of everything it sends. */
    public int cameraChannel() {
        synchronized (lock) {
            return camChannel;
        }
    }

    /** True once a datagram carrying a non-zero channel has been seen. */
    public boolean channelLearned() {
        synchronized (lock) {
            return channelReported;
        }
    }

    /** The peer's reliable video window end, from {@code [10:12]} of its 34-byte status frames. */
    public int peerCursor() {
        synchronized (lock) {
            return peerCursor;
        }
    }

    /** The peer's reliable download window end — the window the media manifest rides. */
    public int peerDownloadCursor() {
        synchronized (lock) {
            return peerDownloadCursor;
        }
    }

    /** The last datagram handed to the socket, kept so a query can be diffed verbatim. */
    public byte[] lastSentPacket() {
        synchronized (lock) {
            return lastSentPacket == null ? null : lastSentPacket.clone();
        }
    }

    /** True while the socket is open. */
    public boolean isOpen() {
        synchronized (socketLock) {
            return sock != null && !sock.isClosed();
        }
    }

    /** True once the peer answered a handshake. */
    public boolean isHandshaken() {
        return handshaken;
    }

    /** The local UDP port, or -1 before the socket exists. */
    public int localPort() {
        synchronized (socketLock) {
            return sock == null ? -1 : sock.getLocalPort();
        }
    }

    /** The peer host we were asked to reach. */
    public String host() {
        return host;
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    /**
     * Bring the link up: try the handshake on every candidate port ({@link #PORTS}, 9004 then 10004)
     * and keep whichever answers.
     *
     * <p>The handshake payload is identical on both, so the port is the only variable; the reference
     * picks it from the model, this client just asks. Once a port answers, the peer's heartbeats are
     * drained to learn the camera channel and our send sequence is synced to {@code channel + 8}.
     *
     * @param hostName      camera IP (its AP gateway, usually {@code 192.168.2.1})
     * @param timeoutMsPerPort how long to keep re-sending the handshake on one port before moving on
     * @return true when a session is up
     */
    public boolean handshake(String hostName, int timeoutMsPerPort) {
        return handshake(hostName, timeoutMsPerPort, PORTS);
    }

    /**
     * {@link #handshake(String, int)} against an explicit candidate list, in order.
     *
     * <p>{@link #PORTS} is what production passes. This overload exists for a caller that already knows
     * the model's port (the reference picks it that way) and for tests, which cannot bind the two fixed
     * ports on a machine where something else holds them.
     */
    public boolean handshake(String hostName, int timeoutMsPerPort, int[] candidatePorts) {
        if (closed) {
            return false;
        }
        if (hostName == null || hostName.isEmpty()) {
            fail("handshake: no host given");
            return false;
        }
        if (candidatePorts == null || candidatePorts.length == 0) {
            fail("handshake: no candidate ports");
            return false;
        }
        synchronized (lock) {
            this.host = hostName;
            this.sessionId = randomSessionId(random);
            this.baseSeq = randomBaseSeq(random);
            this.camChannel = baseSeq;
            this.udpSeq = 0;
            this.dumlSeq = 0xA000;
            this.cmdCounter = 0;
            this.channelLogState = 0;
            this.channelReported = false;
            this.peerCursor = 0;
            this.peerDownloadCursor = 0;
            this.sequenceMismatchReported = false;
        }

        InetAddress addr;
        try {
            addr = InetAddress.getByName(hostName);
        } catch (IOException e) {
            fail("handshake: cannot resolve " + hostName + " (" + e.getMessage() + ")");
            return false;
        }

        for (int i = 0; i < candidatePorts.length; i++) {
            int candidate = candidatePorts[i];
            if (candidate <= 0 || candidate > 65535) continue;
            if (!openSocket(addr, candidate)) continue;
            log("datalink: trying udp/" + candidate + " (session=" + UdpPacket.hex4(sessionId)
                    + " base=" + UdpPacket.hex4(baseSeq) + ")");
            byte[] reply = handshakeOn(candidate, timeoutMsPerPort);
            if (reply == null) {
                log("datalink: handshake FAILED on udp/" + candidate);
                closeSocket();
                continue;
            }
            port = candidate;
            handshaken = true;
            log("datalink: handshake OK on udp/" + candidate);
            learnFrom(reply);
            drainHeartbeats();
            syncSeqToPeerChannel();
            log(String.format(Locale.ROOT, "datalink: session=%s base=%s channel=%s seq=%s",
                    UdpPacket.hex4(sessionId), UdpPacket.hex4(baseSeq),
                    UdpPacket.hex4(camChannel), UdpPacket.hex4(udpSeq)));
            if (UdpPacket.sequenceSpaceMismatched(camChannel, baseSeq) && !sequenceMismatchReported) {
                sequenceMismatchReported = true;
                log("datalink: peer answered on its own sequence channel, not the base we proposed — "
                        + "it may be holding a session from a previous connection");
            }
            return true;
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < candidatePorts.length; i++) {
            if (i > 0) names.append(" or ");
            names.append("udp/").append(candidatePorts[i]);
        }
        fail("handshake: no answer on " + names);
        return false;
    }

    /**
     * Send the handshake frame until the peer answers with one, up to {@link #HANDSHAKE_ATTEMPTS}
     * times or until {@code timeoutMs} has elapsed. Instance-stateful but socket-backed, so it is not
     * part of the pure surface.
     */
    private byte[] handshakeOn(int candidate, int timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(500, timeoutMs);
        for (int attempt = 0; attempt < HANDSHAKE_ATTEMPTS; attempt++) {
            sendPacket(handshakePacket(sessionId, udpSeq, baseSeq), candidate);
            List<byte[]> got = recvAll(HANDSHAKE_RECV_MS);
            for (int i = 0; i < got.size(); i++) {
                if (isHandshakeReply(got.get(i))) return got.get(i);
            }
            if (System.currentTimeMillis() >= deadline) break;
        }
        return null;
    }

    /**
     * Drain the peer's first packets so it tells us its channel, then ack each round — the reference's
     * five {@code recvAll(400) + sendAck()} rounds. Returns everything received.
     */
    public List<byte[]> drainHeartbeats() {
        List<byte[]> all = new ArrayList<byte[]>();
        for (int round = 0; round < CHANNEL_DRAIN_ROUNDS; round++) {
            all.addAll(recvAll(DRAIN_MS));
            sendAck();
        }
        return all;
    }

    /**
     * Start our send sequence at the peer's channel + 8, now that heartbeats have taught us the channel.
     * Port of {@code DumlTransport.syncSeqToPeerChannel}.
     */
    public void syncSeqToPeerChannel() {
        synchronized (lock) {
            udpSeq = UdpPacket.startSeqFor(camChannel);
        }
    }

    // ---- playback mode ------------------------------------------------------------------------------

    /**
     * Has the camera told us whether it is in playback? Until it has, {@link #playbackReported()} says
     * nothing at all — the difference matters, because "not reported" and "reported no" lead to
     * different decisions (ask before telling vs. tell it).
     */
    public boolean playbackKnown() {
        return playbackKnown;
    }

    /** The camera's own playback bit. Only meaningful once {@link #playbackKnown()} is true. */
    public boolean playbackReported() {
        return playbackIn;
    }

    /**
     * Enter playback: {@code 0x02/0x0C = 01 01 00 01} to the camera.
     *
     * <p>This is a <b>camera-wide mode</b>, not a per-command flag, and it is what makes the media list
     * complete. A Pocket that is still in capture declares the right file count and then serves only
     * part of the records — the watch saw exactly that: one file, then the session dropped. Mimo enters
     * playback before it asks for the list, and so do we.
     */
    public void enterPlayback() {
        sendCommand(0x02, 0x0C, UdpPacket.hex("01010001"), 0x01, 0, 2);
    }

    /** Leave playback ({@code 01 01 00 00}) — what Mimo sends on teardown. */
    public void leavePlayback() {
        sendCommand(0x02, 0x0C, UdpPacket.hex("01010000"), 0x01, 0, 2);
    }

    /**
     * The Pocket 3's playback entry: {@code 0x01/0x01} control frames, six of {@code prelude} then the
     * second payload for the rest. {@code cmdType 0} and no reply, so the camera's status bit is the
     * only feedback — safe to attempt on any body, an ignoring camera simply leaves the bit alone.
     */
    public void enterPlaybackViaControl(boolean prelude) {
        byte[] payload = UdpPacket.hex(prelude ? "0300000000040000000701" : "0000000000040000000401");
        sendCommand(0x01, 0x01, payload, 0x01, 0, 0);
    }

    /**
     * Receive for {@code durationMs} while acking every {@code ackEveryMs}, and return how many
     * datagrams arrived.
     *
     * <p>The reference acks once per ~800 ms collect batch, which is long enough for the camera's
     * download window to stall mid-page. Acking on a short cadence instead is strictly more permissive:
     * echoing the peer's own cursor can only ever ask it to send more. Frames are still handed to the
     * listener from inside {@link #recvAll}, so a manifest assembles whether or not this returns.
     */
    public int pump(long durationMs, long ackEveryMs) {
        if (closed) {
            // The session was torn down while the worker was still collecting; nothing may be sent
            // or waited for on a closed socket.
            return 0;
        }
        long end = System.currentTimeMillis() + durationMs;
        long nextAck = System.currentTimeMillis() + ackEveryMs;
        int count = 0;
        while (System.currentTimeMillis() < end) {
            long left = end - System.currentTimeMillis();
            count += recvAll(Math.max(20L, Math.min(ackEveryMs, left))).size();
            if (System.currentTimeMillis() >= nextAck) {
                sendAck();
                nextAck = System.currentTimeMillis() + ackEveryMs;
            }
        }
        sendAck();
        return count;
    }

    /** Close the socket and stop the keep-alive. Idempotent. */
    public void close() {
        closed = true;
        stopKeepalive();
        closeSocket();
    }

    // ---- send --------------------------------------------------------------------------------------

    /**
     * Send a DUML frame: {@code [udp hdr type 0x05][routing hdr][frame]}. Advances our sequence by 8
     * on success.
     */
    public void send(DumlFrame frame) {
        if (frame == null) return;
        synchronized (lock) {
            cmdCounter = (cmdCounter + 1) & 0xFF;
            int seq = udpSeq;
            int counter = cmdCounter;
            byte[] duml = frame.encode();
            byte[] routing = UdpPacket.encodeRoutingHeader(seq, counter, false);
            byte[] pkt = packet(sessionId, seq, UdpPacket.TYPE_COMMAND, routing, duml);
            if (sendPacket(pkt, port)) advanceLocked();
        }
    }

    /**
     * Send a command addressed to the camera: builds the frame with the reference's default
     * {@code cmdType = 2} and {@code target = 0x0102} (App -> Camera, type 1 id 0).
     */
    public void sendCommand(int cmdSet, int cmdId, byte[] payload) {
        sendCommand(cmdSet, cmdId, payload, 0x01, 0, 2);
    }

    /** Send a command to an explicit receiver, e.g. {@code (0x08, 1)} for the system subsystem. */
    public void sendCommand(int cmdSet, int cmdId, byte[] payload, int receiverType, int receiverId,
                            int cmdType) {
        DumlFrame frame = new DumlFrame(target(receiverType, receiverId), dumlSeq,
                type(cmdType, cmdSet, cmdId), payload);
        synchronized (lock) {
            dumlSeq = (dumlSeq + 1) & 0xFFFF;
        }
        send(frame);
    }

    /** Send a {@code pktType-0x00} handshake frame — the documented keep-alive ping. */
    public void sendRaw(int pktType, byte[] payload) {
        synchronized (lock) {
            byte[] hdr = UdpPacket.encodeUdpHeader(pktType, payload.length, sessionId, udpSeq);
            byte[] pkt = concat(hdr, payload);
            if (sendPacket(pkt, port)) advanceLocked();
        }
    }

    /**
     * Send our window acknowledgement ({@code pktType 0x04}), with seq 0 like every other one. The
     * camera stops streaming a reliable window it does not see acked, so this must go out at least
     * once per exchange while a manifest is arriving.
     */
    public void sendAck() {
        synchronized (lock) {
            byte[] payload = ackPayload(peerCursor, peerDownloadCursor, baseSeq);
            byte[] hdr = UdpPacket.encodeUdpHeader(UdpPacket.TYPE_ACK, payload.length, sessionId, 0);
            sendPacket(concat(hdr, payload), port);
        }
    }

    /** Send one presence beat ({@code 0x00/0x88}) — the ~1 Hz "I am still here". */
    public void sendPresence() {
        sendCommand(0x00, 0x88, APP_PRESENCE, 0x08, 1, 2);
    }

    // ---- camera registration sequence ---------------------------------------------------------------

    /** {@code 0x00/0x81} device info to the system subsystem (receiver 0x28, cmdType 4). */
    public void requestDevInfo() {
        // cmdType 4, so the low byte of the type word is 0x80, not the 0x40 of a plain request —
        // exactly what CameraSession sends. 62-byte "APP" blob from OsmoCommands.
        sendCommand(0x00, 0x81, OsmoCommands.appDeviceInfo(), 0x08, 2, 4);
        recvAndAck(DRAIN_MS);
    }

    /** {@code 0x00/0x88} registration/presence frame to the system subsystem (receiver 0x28). */
    public void register() {
        sendCommand(0x00, 0x88, APP_PRESENCE, 0x08, 1, 2);
        recvAndAck(DRAIN_MS);
    }

    /** {@code 0x03/0xDA} gimbal init ({@code 05 ff ff ff ff}) to the gimbal receiver 0x03. */
    public void initGimbal() {
        sendCommand(0x03, 0xDA, UdpPacket.hex("05ffffffff"), 0x03, 0, 2);
        recvAndAck(DRAIN_MS);
    }

    /** The eight {@code 0x00/0x99} status subscriptions, back to back like Mimo, then one drain. */
    public void subscribe() {
        sendSubscriptions(subscriptionFrames(OsmoCommands.SUBSCRIBE_MSG_ID));
    }

    /** Send a caller-supplied batch of subscription frames. */
    public void sendSubscriptions(List<DumlFrame> frames) {
        if (frames == null || frames.isEmpty()) return;
        for (int i = 0; i < frames.size(); i++) {
            send(frames.get(i));
            if (i % 8 == 7) {
                recvAndAck(20);
            }
        }
        recvAndAck(DRAIN_MS);
    }

    // ---- media list ---------------------------------------------------------------------------------

    /** Request counter selecting the SD store. */
    public static final int SD_QUERY_CTR = 1;
    /** Request counter selecting the built-in store. */
    public static final int INTERNAL_QUERY_CTR = 2;
    /** Newest-page cursor for the SD store. */
    public static final long NEWEST_SD = 0x00000001L;
    /** Newest-page cursor for the built-in store (the 0x40000000 bit is the store selector). */
    public static final long NEWEST_INTERNAL = 0x40000001L;

    /**
     * Ask for a media-list page: page 0 is the newest SD page, page 1 the newest built-in page.
     *
     * <p>Older pages are addressed by <b>cursor</b>, not by index — the camera returns the 45 records
     * below the handle you pass, so {@link #requestMediaListPage(int, long)} is what a pager uses.
     * Nothing here waits for the answer: the reply stream is collected by the caller and fed to
     * {@link ManifestAssembler}.
     */
    public void requestMediaList(int page) {
        if (page == 1) {
            requestMediaListPage(INTERNAL_QUERY_CTR, NEWEST_INTERNAL);
        } else {
            requestMediaListPage(SD_QUERY_CTR, NEWEST_SD);
        }
    }

    /** One {@code 0x00/0x26} list query with an explicit counter and 4-byte little-endian cursor. */
    public void requestMediaListPage(int ctr, long cursor) {
        sendCommand(0x00, 0x26, listPayload(ctr, cursor), 0x01, 0, 2);
    }

    /** Mimo's interleaved {@code 4a040e1001000000000001000000} trigger frame. */
    public void sendMediaListTrigger() {
        sendCommand(0x00, 0x26, UdpPacket.hex("4a040e1001000000000001000000"), 0x01, 0, 2);
    }

    // ---- receive ------------------------------------------------------------------------------------

    /**
     * Collect every datagram that arrives within {@code durationMs}. Learns the camera channel from any
     * datagram long enough to carry one, latches the peer's window cursors out of its 34-byte status
     * frames, and hands every CRC-valid DUML frame to the listener.
     */
    public List<byte[]> recvAll(long durationMs) {
        List<byte[]> out = new ArrayList<byte[]>();
        long deadline = System.currentTimeMillis() + durationMs;
        byte[] buf = new byte[65536];
        DatagramSocket s;
        synchronized (socketLock) {
            s = sock;
        }
        if (s == null || s.isClosed()) return out;
        while (System.currentTimeMillis() < deadline) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                byte[] data = new byte[p.getLength()];
                System.arraycopy(p.getData(), p.getOffset(), data, 0, p.getLength());
                out.add(data);
                trace("<-", data);
                learnFrom(data);
            } catch (SocketTimeoutException e) {
                // keep polling until the deadline
            } catch (IOException e) {
                if (!closed) {
                    listener.onLost();
                }
                break;
            }
        }
        return out;
    }

    /** One receive window, then an ack. Returns what arrived. */
    public List<byte[]> recvAndAck(long durationMs) {
        List<byte[]> got = recvAll(durationMs);
        sendAck();
        return got;
    }

    /**
     * Learn from one inbound datagram: the camera channel (bytes 8-9 of its routing header) and, for a
     * 34-byte {@code pktType-0x01} status frame, the peer's reliable-window cursors — video window end
     * at {@code [10:12]}, download window end at {@code [18:20]}. The download one is what feeds
     * {@link #sendAck()}; the camera stops streaming a window it does not see acknowledged.
     */
    public void learnFrom(byte[] data) {
        if (data == null || data.length < 8) return;
        if (data.length >= 10) {
            int ch = UdpPacket.cameraChannelOf(data);
            if (ch != 0) {
                synchronized (lock) {
                    camChannel = ch;
                }
                if (!channelReported) {
                    channelReported = true;
                    listener.onChannelLearned(ch);
                }
                maybeLogChannel();
            }
        }
        if (data.length == 34 && (data[6] & 0xFF) == UdpPacket.TYPE_TELEMETRY) {
            synchronized (lock) {
                peerCursor = UdpPacket.u16le(data, 10);
                peerDownloadCursor = UdpPacket.u16le(data, 18);
            }
        }
        List<DumlFrame> frames = scanFrames(data);
        for (int i = 0; i < frames.size(); i++) {
            DumlFrame f = frames.get(i);
            if (f.cmdSet() == 0x02 && f.cmdId() == 0x80 && f.payload != null
                    && f.payload.length >= 13) {
                boolean in = (u32le(f.payload, 0) & 0x40000000) != 0;
                if (!playbackKnown || in != playbackIn) {
                    log("datalink: 相机报告播放模式 = " + (in ? "是" : "否"));
                }
                playbackIn = in;
                playbackKnown = true;
            }
            listener.onFrame(f);
        }
    }

    /** Little-endian u32 — only read from CRC-checked frames, so no bounds games. */
    private static int u32le(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16)
                | ((b[o + 3] & 0xFF) << 24);
    }

    /** Log the first bytes of a datagram while the trace budget lasts. Both directions use this. */
    private void trace(String arrow, byte[] data) {
        if (data == null || TRACE_LEFT.get() <= 0) {
            return;
        }
        TRACE_LEFT.decrementAndGet();
        int n = Math.min(data.length, 96);
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            int v = data[i] & 0xFF;
            if (v < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        log("udp" + arrow + " " + data.length + "B " + sb + (data.length > n ? " ..." : ""));
    }

    // ---- keep-alive ---------------------------------------------------------------------------------

    /**
     * Keep the link alive on a daemon thread: a window ack plus a handshake ping every
     * {@link #KEEPALIVE_INTERVAL_MS} (the camera AP idles out after roughly 10 s), and a
     * {@code 0x00/0x88} presence beat at about 1 Hz. Idempotent — a second call does nothing while the
     * first loop is running.
     */
    public void startKeepalive() {
        if (keepaliveOn) return;
        keepaliveOn = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int tick = 0;
                while (keepaliveOn && !closed) {
                    try {
                        recvAll(SO_TIMEOUT_MS);
                        sendAck();
                        // A bare handshake frame doubles as the documented ~2 s UDP ping, and it is also
                        // what keeps the peer's routing state pointed at us.
                        sendPacket(handshakePacket(sessionId, 0, baseSeq), port);
                        if (tick % KEEPALIVE_TICKS_PER_BEAT == 0) {
                            sendPresence();
                        }
                    } catch (RuntimeException e) {
                        // A send on a dying link must never kill the beat thread, or the session looks
                        // healthy while nothing goes out (the reference crashed here once).
                        log("datalink: keepalive error: " + e);
                    }
                    if (!sleep(KEEPALIVE_INTERVAL_MS)) break;
                    tick++;
                }
            }
        }, "datalink-keepalive");
        t.setDaemon(true);
        keepaliveThread = t;
        t.start();
    }

    /** Stop the keep-alive beat. The socket stays open. */
    public void stopKeepalive() {
        keepaliveOn = false;
        Thread t = keepaliveThread;
        keepaliveThread = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** True while the keep-alive thread is running. */
    public boolean isKeepaliveRunning() {
        return keepaliveOn;
    }

    // ---- internals -----------------------------------------------------------------------------------

    private boolean openSocket(InetAddress addr, int candidate) {
        closeSocket();
        DatagramSocket s;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(SO_TIMEOUT_MS);
        } catch (IOException e) {
            log("datalink: cannot open udp socket (" + e.getMessage() + ")");
            return false;
        }
        synchronized (socketLock) {
            sock = s;
            peer = addr;
        }
        return true;
    }

    private void closeSocket() {
        DatagramSocket s;
        synchronized (socketLock) {
            s = sock;
            sock = null;
        }
        if (s != null) {
            try {
                s.close();
            } catch (RuntimeException ignored) {
                // closing twice is fine
            }
        }
    }

    /** Send, swallowing a failure: an unbound network throws ENETUNREACH and must never propagate. */
    private boolean sendPacket(byte[] pkt, int destPort) {
        if (pkt == null || destPort <= 0) return false;
        synchronized (socketLock) {
            if (sock == null || sock.isClosed() || peer == null) return false;
            try {
                sock.send(new DatagramPacket(pkt, pkt.length, peer, destPort));
                lastSentPacket = pkt;
                // Only commands are worth tracing outbound: the ~2 s handshake ping and the acks are
                // pure noise, and every traced line costs a file write on the datalink thread.
                if (pkt.length > 6 && (pkt[6] & 0xFF) == UdpPacket.TYPE_COMMAND) {
                    trace("->", pkt);
                }
                return true;
            } catch (IOException e) {
                return false;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    /** Advance our send sequence. Caller must hold {@link #lock}. */
    private void advanceLocked() {
        udpSeq = (udpSeq + UdpPacket.SEQ_STEP) & 0xFFFF;
    }

    private void maybeLogChannel() {
        int ch;
        synchronized (lock) {
            ch = camChannel;
            if (channelLogState == 1 && ch != baseSeq) {
                channelLogState = 2;
            } else if (channelLogState == 0) {
                channelLogState = 1;
            } else {
                return;
            }
        }
        if (UdpPacket.sequenceSpaceMismatched(ch, baseSeq)) {
            log("datalink: peer channel " + UdpPacket.hex4(ch) + " differs from our base "
                    + UdpPacket.hex4(baseSeq) + " — the peer is counting in its own sequence space");
        }
    }

    private void fail(String reason) {
        if (closed) {
            return;
        }
        log(reason);
        listener.onFailure(reason);
    }

    private void log(String s) {
        listener.onLog(s);
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static byte[] slice(byte[] src, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] != null) n += parts[i].length;
        }
        byte[] out = new byte[n];
        int p = 0;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] == null) continue;
            System.arraycopy(parts[i], 0, out, p, parts[i].length);
            p += parts[i].length;
        }
        return out;
    }

    /** Flatten a list of datagrams into one contiguous buffer, as the reference's collect loops do. */
    public static byte[] concatDatagrams(byte[][] datagrams) {
        return concat(datagrams);
    }

    private static byte[] ascii(String s) {
        try {
            return s.getBytes("US-ASCII");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("US-ASCII unavailable", e);
        }
    }
}
