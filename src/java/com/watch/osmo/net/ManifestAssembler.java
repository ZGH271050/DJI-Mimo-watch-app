package com.watch.osmo.net;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.watch.osmo.duml.DumlFrame;

/**
 * Reassembles the media-list blob the camera streams back as {@code 0x00/0x27} DUML frames.
 *
 * <p>Port of {@code CameraSession.manifestBytes} + the {@code 4A} sub-type bookkeeping around it
 * ({@code chunkTally} / {@code hasEndMarker} / {@code streamsEnded}) from osmosis, MIT.
 *
 * <p>Why this exists at all: the file list is far too big for one DUML frame, so it arrives chunked
 * across ~17 x {@code 0x00/0x27} replies. Each reply's payload is
 * {@code [10-byte sub-header][chunk]}, where the sub-header is
 * {@code 4A 01 <len:u16le> <?> <seq:u16le @6> 00 00}. {@code byte1 == 0x01} marks a data chunk;
 * {@code 4A 04 ...} opens the transfer (start, no chunk) and {@code 4A 03 ...} closes it (end, no
 * chunk). <b>Strip the sub-header before concatenating</b> — concatenating raw payloads injects those
 * ten bytes mid-string wherever a path straddles a frame boundary, and that one file then silently
 * disappears from the grid.
 *
 * <p>Chunks go out in <b>arrival order</b>, never sequence-sorted: on a two-store page the sub-header
 * sequence counter restarts per page, so sorting across pages would scramble the list.
 *
 * <p>Two input shapes are accepted, because captures come in both:
 * <ul>
 *   <li>raw UDP datagrams — {@code [8B udp hdr][12B routing hdr][DUML frame]} (nano_45.bin,
 *       xtra_13.bin). DUML frames are located and pulled out wherever they sit in the byte stream, so
 *       a frame split across two feeds is still reassembled.</li>
 *   <li>already-unwrapped {@code 0x27} payloads — {@code [10B sub-header][chunk]} with no datagram
 *       framing at all (op4_45.bin, oa4_6.bin, op3_15.bin).</li>
 * </ul>
 * Normal use is the incremental one: {@link #feed(byte[])} per received datagram, then
 * {@link #bytes()} / {@link #bytes(int)}. The static {@link #assemble(byte[])} is the one-shot form.
 *
 * <p>Pure Java 8, {@code java.io}/{@code java.util} only.
 */
public final class ManifestAssembler {

    /** CmdSet of the media-list reply. */
    public static final int CMD_SET = 0x00;
    /** CmdId of the media-list reply. */
    public static final int CMD_ID = 0x27;

    /** First byte of every media-list sub-header. */
    public static final int SUB_MAGIC = 0x4A;
    /** Sub-type 0x01: a data chunk follows the 10-byte sub-header. */
    public static final int SUB_DATA = 0x01;
    /** Sub-type 0x03: the camera closed this answer. */
    public static final int SUB_END = 0x03;
    /** Sub-type 0x04: the camera opened a transfer. */
    public static final int SUB_START = 0x04;

    /** Bytes of sub-header in front of every chunk. */
    public static final int SUB_HEADER_BYTES = 10;
    /** Offset of the request counter the camera echoes back, inside the sub-header. */
    public static final int SUB_CTR_OFFSET = 4;
    /** Offset of the per-page chunk sequence, inside the sub-header. */
    public static final int SUB_SEQ_OFFSET = 6;

    /** Smallest DUML frame (header 11 + crc16 2). */
    private static final int DUML_MIN = 13;
    /** Byte offsets inside a DUML frame of the command, from its SOF. */
    private static final int DUML_CMD_SET = 9;
    private static final int DUML_CMD_ID = 10;
    private static final int DUML_PAYLOAD = 11;

    /** Per-request-counter stream state (one store's answer, usually). */
    private static final class Slice {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        int startFrames;
        int dataFrames;
        int endFrames;
        int droppedShortChunks;
        int lastChunkBytes = -1;
        int subSeqFirst = -1;
        int subSeqLast = -1;

        int chunkBytes() {
            return data.size();
        }

        boolean opened() {
            return startFrames > 0;
        }

        boolean closed() {
            return endFrames > 0 || (startFrames > 0 && dataFrames == 0);
        }
    }

    /**
     * A data chunk smaller than this cannot be a full chunk of a media list — the captures send 999-byte
     * and 643-byte chunks with the camera closing each stream with a short one — so it is treated as the
     * last chunk of its answer. Page boundaries in the captures never produce a chunk this small except
     * as a tail.
     */
    public static final int SHORT_CHUNK_BYTES = 900;

    /**
     * A stream is taken as finished this long after its last data chunk when the camera never sent its
     * {@code 4A 03} end frame. The reference uses a 5-tick (1 s) quiet window for the same purpose, but
     * only after the path count has settled; this is a single wall-clock deadline.
     */
    public static final long DEFAULT_QUIET_MS = 1500;

    private final ByteArrayOutputStream raw = new ByteArrayOutputStream();
    private final Map<Integer, Slice> slices = new LinkedHashMap<Integer, Slice>();
    private final List<Integer> sliceOrder = new ArrayList<Integer>();
    private final List<DumlFrame> framesSeen = new ArrayList<DumlFrame>();

    /** Sub-header counters are 8-bit on the wire; a camera that never echoes one lands here. */
    public static final int COUNTER_UNKNOWN = -1;

    private boolean sawDuml;
    private boolean sawSubHeader;
    private int chunks;
    private int frames;
    private int payloadBytes;
    private long lastFrameAtMs;
    private boolean anyFrame;

    // ---- incremental API --------------------------------------------------------------------------

    /** Append one received datagram (or one already-unwrapped payload) to the collected blob. */
    public void feed(byte[] datagram) {
        if (datagram == null || datagram.length == 0) return;
        raw.write(datagram, 0, datagram.length);
        scanFrame(datagram, 0, datagram.length);
    }

    /**
     * Take one DUML frame that has <b>already</b> been unwrapped — the {@code 0x00/0x27} reply frames a
     * {@code Datalink.Listener.onFrame} callback hands up. The udp and routing headers are already gone,
     * so this is the entry point an integration layer wants; it runs the identical sub-header stripping
     * and arrival-order concatenation that {@link #feed(byte[])} runs, on the frame's payload alone.
     *
     * <p>Frames of any other cmdset/cmd are counted in {@link #frameCount()} but contribute no bytes, so
     * feeding every frame the listener sees is safe.
     */
    public void feedFrame(DumlFrame frame) {
        if (frame == null) return;
        framesSeen.add(frame);
        frames++;
        markFrame();
        if (frame.cmdSet() != CMD_SET || frame.cmdId() != CMD_ID) return;
        sawDuml = true;
        byte[] payload = frame.payload;
        if (payload == null || payload.length == 0) return;
        consumePayload(payload, 0, payload.length);
    }

    /**
     * Take one already-unwrapped {@code 0x00/0x27} payload — {@code [10B sub-header][chunk]} with no
     * DUML framing around it. {@link #feedFrame(DumlFrame)} is the usual way in; this is for a caller
     * that has the payload bytes but no frame object.
     */
    public void feedPayload(byte[] payload) {
        if (payload == null || payload.length == 0) return;
        markFrame();
        frames++;   // a payload fed directly is one frame as far as the counters are concerned
        consumePayload(payload, 0, payload.length);
    }

    /** Every frame handed to {@link #feedFrame}, in arrival order. */
    public List<DumlFrame> frames() {
        return new ArrayList<DumlFrame>(framesSeen);
    }

    /** Bytes contributed by data chunks, sub-headers excluded. */
    public int payloadBytes() {
        return payloadBytes;
    }

    /** Wall-clock time of the most recent frame accepted, in {@code System.currentTimeMillis()} terms. */
    public long lastFrameAtMs() {
        return lastFrameAtMs;
    }

    /** Every byte fed so far, verbatim. */
    public byte[] rawBytes() {
        return raw.toByteArray();
    }

    /** Number of {@code 0x00/0x27} frames matched, including start/end control frames. */
    public int frameCount() {
        return frames;
    }

    /** Number of data chunks that carried payload. */
    public int chunkCount() {
        return chunks;
    }

    /** True once a DUML-framed reply has been seen (raw-datagram captures). */
    public boolean sawDumlFraming() {
        return sawDuml;
    }

    /** True once a {@code 4A}-prefixed sub-header has been seen in any shape. */
    public boolean sawSubHeaders() {
        return sawSubHeader;
    }

    /** True when every counter seen has been closed by an end frame (or opened with no data). */
    public boolean streamsEnded() {
        if (slices.isEmpty()) return false;
        boolean anyData = false;
        for (Slice s : slices.values()) {
            if (s.dataFrames > 0) anyData = true;
            if (!s.closed()) return false;
        }
        return anyData;
    }

    /**
     * Is the media list finished arriving?
     *
     * <p>Three conditions, in order of trust:
     * <ol>
     *   <li><b>the camera said so</b> — every counter it opened has been closed, which in practice means
     *       the {@code 4A 03} end frame arrived ({@link #streamsEnded()});</li>
     *   <li><b>the last chunk was a short one</b> — a data chunk under {@link #SHORT_CHUNK_BYTES} is a
     *       tail, never a page boundary, so its arrival means the sender has nothing more to give even
     *       if the end frame was lost;</li>
     *   <li><b>the stream has gone quiet</b> — no frame for {@link #DEFAULT_QUIET_MS}. The packet loss
     *       backstop, and the reason a lost {@code 4A 03} does not cost the caller its whole page.</li>
     * </ol>
     *
     * <p>At least one data chunk must have arrived for any of it to count: an empty answer is not a
     * finished list, it is a question that has not been answered yet.
     */
    public boolean complete() {
        return complete(System.currentTimeMillis());
    }

    /** {@link #complete()} against a caller-supplied clock, so the quiet fallback is testable. */
    public boolean complete(long nowMs) {
        if (chunks == 0) return false;
        if (streamsEnded()) return true;
        if (isShortFinalChunk()) return true;
        return lastFrameAtMs > 0 && (nowMs - lastFrameAtMs) >= DEFAULT_QUIET_MS;
    }

    /**
     * Did the most recent data chunk come in short? A full chunk in every captured manifest is 999 or
     * 643 bytes with the answer's last chunk short of that; a chunk under {@link #SHORT_CHUNK_BYTES} is
     * therefore a tail.
     */
    public boolean isShortFinalChunk() {
        boolean sawShort = false;
        for (Slice s : slices.values()) {
            if (s.dataFrames > 0 && s.lastChunkBytes >= 0 && s.lastChunkBytes < SHORT_CHUNK_BYTES) {
                sawShort = true;
            }
        }
        return sawShort;
    }

    /** Bytes in the most recent data chunk of a counter, or -1 when it never sent one. */
    public int lastChunkBytes(int ctr) {
        Slice s = slices.get(ctr);
        return s == null ? -1 : s.lastChunkBytes;
    }

    /** True when this counter's answer was closed. */
    public boolean streamEnded(int ctr) {
        Slice s = slices.get(ctr);
        return s != null && s.closed();
    }

    /** Request counters seen, in first-arrival order. */
    public List<Integer> counters() {
        return new ArrayList<Integer>(sliceOrder);
    }

    /** Distinct media paths seen so far — lets a collect loop stop once the list stops growing. */
    public int distinctPathCount() {
        return countMediaPaths(bytes());
    }

    /** Chunks dropped because the payload was not longer than the sub-header. */
    public int droppedShortChunks() {
        int n = 0;
        for (Slice s : slices.values()) n += s.droppedShortChunks;
        return n;
    }

    /**
     * The reassembled manifest: every data chunk, sub-headers stripped, in arrival order.
     *
     * <p>With a per-store camera this is the concatenation of every store's answer, which is what the
     * merged decode path wants. Use {@link #bytes(int)} to get one store's slice.
     */
    public byte[] bytes() {
        if (!sawSubHeader) {
            // No sub-header anywhere: either the camera streams the list some other way, or the caller
            // fed a blob that is already reassembled. Hand back the raw bytes so a different parser can
            // still have a go, exactly as manifestBytes() falls back to the raw blob.
            return rawBytes();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Integer ctr : sliceOrder) {
            byte[] b = slices.get(ctr).data.toByteArray();
            out.write(b, 0, b.length);
        }
        byte[] merged = out.toByteArray();
        return merged.length > 0 ? merged : rawBytes();
    }

    /**
     * One request counter's slice — the per-store answer, with the sub-headers stripped.
     *
     * <p>The counter is the one echoed back at sub-header byte 4; it is what lets a two-store blob be
     * split into the SD answer and the internal answer without guessing from the file handles.
     *
     * @return the slice, empty (not null) when that counter never sent data
     */
    public byte[] bytes(int ctr) {
        Slice s = slices.get(ctr);
        return s == null ? new byte[0] : s.data.toByteArray();
    }

    /** Alias for {@link #bytes(int)} under the Kotlin name. */
    public byte[] manifestBytes(int ctr) {
        return bytes(ctr);
    }

    /** Alias for {@link #bytes()} under the Kotlin name — the whole reassembled manifest. */
    public byte[] manifestBytes() {
        return bytes();
    }

    /** Start frames seen for one counter. */
    public int startFrames(int ctr) {
        Slice s = slices.get(ctr);
        return s == null ? 0 : s.startFrames;
    }

    /** Data chunks seen for one counter. */
    public int dataFrames(int ctr) {
        Slice s = slices.get(ctr);
        return s == null ? 0 : s.dataFrames;
    }

    /** End frames seen for one counter. */
    public int endFrames(int ctr) {
        Slice s = slices.get(ctr);
        return s == null ? 0 : s.endFrames;
    }

    /** First and only sub-header sequence seen for one counter, or -1. */
    public int subSeqFirst(int ctr) {
        Slice s = slices.get(ctr);
        return s == null ? -1 : s.subSeqFirst;
    }

    /** Last sub-header sequence seen for one counter, or -1. */
    public int subSeqLast(int ctr) {
        Slice s = slices.get(ctr);
        return s == null ? -1 : s.subSeqLast;
    }

    /**
     * Does the reassembled stream carry the camera's {@code 0c 01} end-of-list TLV? It sits immediately
     * before a record's {@code 0d} name field ({@code ... 13 00 | 0c 01 | 0d <len> <name>}), so the
     * three-byte run {@code 0c 01 0d} is the test. Present in every captured final page (xtra_13,
     * oa4_6, oa6_*), absent in every captured full page (nano_45, oa4_45, op4_45).
     */
    public boolean hasEndMarker() {
        return hasEndMarker(bytes());
    }

    /** {@link #hasEndMarker()} for an arbitrary blob. Port of {@code CameraSession.hasEndMarker}. */
    public static boolean hasEndMarker(byte[] bytes) {
        if (bytes == null) return false;
        for (int i = 0; i + 2 < bytes.length; i++) {
            if (bytes[i] == 0x0C && bytes[i + 1] == 0x01 && bytes[i + 2] == 0x0D) return true;
        }
        return false;
    }

    /**
     * The record count declared at manifest offset 0 (a {@code u32-LE}), or -1 when the blob is too
     * short or the field is not a plausible count. Port of the {@code COUNT_MAX} guard in
     * {@code CameraSession}: several bodies write something other than a count there.
     */
    public int declaredCount() {
        return declaredCount(bytes());
    }

    /** Largest value at offset 0 still treated as a page's record count. */
    public static final int COUNT_MAX = 512;

    /** {@link #declaredCount()} for an arbitrary blob. */
    public static int declaredCount(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return -1;
        long v = UdpPacket.u32le(bytes, 0);
        return (v <= COUNT_MAX) ? (int) v : -1;
    }

    /**
     * Distinct {@code DCIM/} media paths in a blob — the "is the list still growing" measure the
     * reference collect loop uses. Cheap byte scan, no record decode.
     */
    public static int countMediaPaths(byte[] bytes) {
        if (bytes == null) return 0;
        int n = 0;
        for (int i = 0; i + 5 <= bytes.length; i++) {
            if (bytes[i] == 'D' && bytes[i + 1] == 'C' && bytes[i + 2] == 'I' && bytes[i + 3] == 'M'
                    && bytes[i + 4] == '/') {
                n++;
                i += 4;
            }
        }
        return n;
    }

    /** Human-readable census, e.g. {@code ctr1={start=1} ctr2={data=17,end=12}}. Diagnostics only. */
    public String census() {
        if (slices.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (Integer ctr : sliceOrder) {
            Slice s = slices.get(ctr);
            if (sb.length() > 0) sb.append(' ');
            sb.append("ctr").append(ctr).append('{');
            List<String> parts = new ArrayList<String>();
            if (s.startFrames > 0) parts.add("start=" + s.startFrames);
            if (s.dataFrames > 0) parts.add("data=" + s.dataFrames);
            if (s.endFrames > 0) parts.add("end=" + s.endFrames);
            if (parts.isEmpty()) parts.add("empty");
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(parts.get(i));
            }
            sb.append('}');
        }
        return sb.toString();
    }

    // ---- one-shot API -----------------------------------------------------------------------------

    /**
     * Reassemble a whole captured blob. Accepts both shapes described on the class: raw datagrams or
     * bare {@code 0x27} payloads. Equivalent to {@code feed(blob)} on a fresh assembler.
     */
    public static byte[] assemble(byte[] blob) {
        ManifestAssembler a = new ManifestAssembler();
        a.feed(blob);
        return a.bytes();
    }

    /** {@link #assemble(byte[])} for one request counter's slice. */
    public static byte[] assembleSlice(byte[] blob, int ctr) {
        ManifestAssembler a = new ManifestAssembler();
        a.feed(blob);
        return a.bytes(ctr);
    }

    // ---- scanning ---------------------------------------------------------------------------------

    /**
     * Walk {@code [off, off+len)} of {@code data} for either a DUML {@code 0x00/0x27} frame or a bare
     * {@code 4A}-prefixed payload, and consume every match found.
     *
     * <p>A matched DUML frame is consumed <b>whole</b>: the walk resumes after its CRC, not one byte in.
     * That matters — its payload starts with {@code 4A}, and re-examining those bytes as a bare
     * sub-header is what would consume the same chunk twice and splice it in twice. A frame that does
     * <i>not</i> match (a foreign cmdSet/cmdId, a bad CRC) is skipped one byte at a time instead, so a
     * 0x00/0x27 frame wrapped inside another frame is still found.
     */
    private void scanFrame(byte[] data, int off, int len) {
        int end = off + len;
        int i = off;
        while (i < end) {
            int b = data[i] & 0xFF;
            if (b == 0x55) {
                int consumed = tryDuml(data, i, end);
                i += (consumed > 0) ? consumed : 1;
                continue;
            }
            if (b == SUB_MAGIC) {
                tryBareSubHeader(data, i, end);
            }
            i++;
        }
    }

    /**
     * Consume a well-formed {@code 0x00/0x27} frame at {@code i}.
     *
     * @return the frame's length in bytes when it matched, else 0
     */
    private int tryDuml(byte[] data, int i, int end) {
        if (i + DUML_MIN > end) return 0;
        int len = ((data[i + 1] & 0xFF) | ((data[i + 2] & 0xFF) << 8)) & 0x3FF;
        int ver = (data[i + 2] & 0xFF) >>> 2;
        if (ver != 1 || len < DUML_MIN) return 0;
        int frameEnd = i + len;
        if (frameEnd > end) return 0;
        // Verify both CRCs so a chance 0x55 cannot be mistaken for a frame. Cheap: frames are few and
        // the reference does the same (and needs it, since it scans byte-at-a-time).
        if (com.watch.osmo.duml.Crc.crc8(copyOf(data, i, i + 3)) != (data[i + 3] & 0xFF)) return 0;
        if (com.watch.osmo.duml.Crc.crc16(copyOf(data, i, frameEnd - 2))
                != UdpPacket.u16le(data, frameEnd - 2)) {
            return 0;
        }
        sawDuml = true;
        frames++;
        int cmdSet = data[i + DUML_CMD_SET] & 0xFF;
        int cmdId = data[i + DUML_CMD_ID] & 0xFF;
        if (cmdSet == CMD_SET && cmdId == CMD_ID) {
            consumePayload(data, i + DUML_PAYLOAD, frameEnd - 2);
        }
        return len;
    }

    /** A payload with no datagram framing at all: the whole buffer is one {@code [sub-header][chunk]}. */
    private boolean tryBareSubHeader(byte[] data, int i, int end) {
        if (i + SUB_HEADER_BYTES > end) return false;
        int sub = data[i + 1] & 0xFF;
        if (sub != SUB_DATA && sub != SUB_START && sub != SUB_END) return false;
        int plEnd = end;
        if (sub == SUB_DATA) {
            // The sub-header declares its payload length at bytes 2-3 (little-endian). Honour it when it
            // is plausible and the blob really does hold a run of chunks back to back; otherwise treat
            // the rest of the buffer as one chunk, which is the single-reply case.
            int declared = (data[i + 2] & 0xFF) | ((data[i + 3] & 0xFF) << 8);
            if (declared > 0 && i + SUB_HEADER_BYTES + declared <= end) {
                plEnd = i + SUB_HEADER_BYTES + declared;
            }
        }
        consumePayload(data, i, plEnd);
        return true;
    }

    /**
     * Handle one {@code 0x00/0x27} payload (or one bare sub-header + chunk).
     *
     * <p>{@code start}/{@code end} frames are pure control: their payload is exactly the 10-byte
     * sub-header, and nothing is appended for them.
     */
    private void consumePayload(byte[] data, int plStart, int plEnd) {
        if (plEnd - plStart < 2) return;
        if ((data[plStart] & 0xFF) != SUB_MAGIC) return;
        markFrame();
        sawSubHeader = true;
        // NOTE: no frame counter here. The caller (feedFrame / tryDuml) already counted the frame this
        // payload came from; counting again here made frameCount() report exactly double.
        int sub = data[plStart + 1] & 0xFF;
        int ctr = (plEnd - plStart) > SUB_CTR_OFFSET ? (data[plStart + SUB_CTR_OFFSET] & 0xFF)
                : COUNTER_UNKNOWN;
        Slice s = sliceFor(ctr);
        if (sub == SUB_START) {
            s.startFrames++;
            return;
        }
        if (sub == SUB_END) {
            s.endFrames++;
            return;
        }
        if (sub != SUB_DATA) return;   // an unknown sub-type: counted, not concatenated
        int plLen = plEnd - plStart;
        if (plLen <= SUB_HEADER_BYTES) {
            s.droppedShortChunks++;
            return;
        }
        s.dataFrames++;
        chunks++;
        payloadBytes += plLen - SUB_HEADER_BYTES;
        s.lastChunkBytes = plLen - SUB_HEADER_BYTES;
        if (plLen > SUB_SEQ_OFFSET + 1) {
            int seq = UdpPacket.u16le(data, plStart + SUB_SEQ_OFFSET);
            if (s.subSeqFirst < 0) s.subSeqFirst = seq;
            s.subSeqLast = seq;
        }
        s.data.write(data, plStart + SUB_HEADER_BYTES, plLen - SUB_HEADER_BYTES);
    }

    /** Stamp the arrival of one frame, for the quiet-stream fallback in {@link #complete()}. */
    private void markFrame() {
        anyFrame = true;
        lastFrameAtMs = System.currentTimeMillis();
    }

    private Slice sliceFor(int ctr) {
        Slice s = slices.get(ctr);
        if (s == null) {
            s = new Slice();
            slices.put(ctr, s);
            sliceOrder.add(ctr);
        }
        return s;
    }

    private static byte[] copyOf(byte[] src, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }
}
