package com.watch.osmo.duml;

/**
 * BLE-variant DUML frame: SOF, len, ver, crc8, target(2 LE), id(2 LE), type(3 LE), payload, crc16(2 LE).
 *
 * <p>Byte-for-byte port of {@code dev.konraditurbe.osmosis.duml.DjiMessage} (osmosis, MIT). Vendored
 * there from https://github.com/dimadesu/dji-remote (com.dimadesu.djiremote.dji.DjiMessage), MIT.
 *
 * <p>NOTE on {@code id}: on the BLE channel this 16-bit field is a big-endian message id in the wild,
 * but this class treats it as LE for a lossless encode/decode round-trip — exactly as the Kotlin
 * original does. For the simple request/response matching we do, the camera echoes the same bytes, so
 * LE-in/LE-out is correct on the wire regardless of interpretation.
 *
 * <p>Pure Java 8, no Android APIs, no third-party libraries.
 */
public final class DumlFrame {

    /** Start-of-frame byte. */
    public static final int SOF = 0x55;
    /** Protocol version byte. */
    public static final int VERSION = 0x04;
    /** Header bytes preceding the payload: SOF,len,ver,crc8,target(2),id(2),type(3) = 10, plus crc16(2) plus 1. */
    public static final int OVERHEAD = 13;

    /** Sender/receiver pair: App(0x02) -> Camera(0x01). */
    public static final int TARGET_APP_TO_CAMERA = 0x0102;

    public final int target;
    public final int id;
    public final int type;
    public final byte[] payload;

    public DumlFrame(int target, int id, int type, byte[] payload) {
        this.target = target & 0xFFFF;
        this.id = id & 0xFFFF;
        this.type = type & 0xFFFFFF;
        this.payload = (payload == null) ? new byte[0] : payload;
    }

    /**
     * Parse a frame. Throws {@link IllegalArgumentException} with the same messages the Kotlin
     * original uses: "Bad first byte", "Bad length", "Bad version", "Bad header CRC", "Bad CRC",
     * plus the reader's "not enough bytes".
     */
    public static DumlFrame decode(byte[] data) {
        if (data == null) throw new IllegalArgumentException("Bad length");
        Bytes.Reader reader = new Bytes.Reader(data);
        int first = reader.readU8();
        if (first != SOF) throw new IllegalArgumentException("Bad first byte");
        int length = reader.readU8();
        if (data.length != length) throw new IllegalArgumentException("Bad length");
        int version = reader.readU8();
        if (version != VERSION) throw new IllegalArgumentException("Bad version");
        int headerCrc = reader.readU8();
        byte[] headerBytes = new byte[3];
        System.arraycopy(data, 0, headerBytes, 0, 3);
        int calculatedHeaderCrc = Crc.crc8(headerBytes);
        if (headerCrc != calculatedHeaderCrc) throw new IllegalArgumentException("Bad header CRC");
        int target = reader.readU16Le();
        int id = reader.readU16Le();
        int type = reader.readU24Le();
        byte[] payload = reader.readBytes(reader.available() - 2);
        int crc = reader.readU16Le();
        byte[] withoutCrc = new byte[data.length - 2];
        System.arraycopy(data, 0, withoutCrc, 0, data.length - 2);
        int calcCrc = Crc.crc16(withoutCrc);
        if (crc != calcCrc) throw new IllegalArgumentException("Bad CRC");
        return new DumlFrame(target, id, type, payload);
    }

    /** Alias for {@link #decode(byte[])} under the Kotlin name. */
    public static DumlFrame fromBytes(byte[] data) {
        return decode(data);
    }

    /** Serialise. len is {@code 13 + payload.length}; length is a u8, so payload must be <= 242 bytes. */
    public byte[] encode() {
        Bytes writer = new Bytes(OVERHEAD + payload.length);
        writer.writeU8(SOF);
        writer.writeU8(OVERHEAD + payload.length);
        writer.writeU8(VERSION);
        byte[] header = writer.toByteArray();
        int headerCrc = Crc.crc8(header);
        writer.writeU8(headerCrc);
        writer.writeU16Le(target);
        writer.writeU16Le(id);
        writer.writeU24Le(type);
        writer.writeBytes(payload);
        byte[] withoutCrc = writer.toByteArray();
        int crc = Crc.crc16(withoutCrc);
        writer.writeU16Le(crc);
        return writer.toByteArray();
    }

    /** cmdSet is the middle byte of the 24-bit LE type field. */
    public int cmdSet() {
        return (type >>> 8) & 0xFF;
    }

    /** cmdId is the high byte of the 24-bit LE type field. */
    public int cmdId() {
        return (type >>> 16) & 0xFF;
    }

    /** flags is the low byte of the 24-bit LE type field (0x40 req, 0xC0 resp, 0x00 notify). */
    public int flags() {
        return type & 0xFF;
    }

    /** {@code target=0x%04X id=0x%04X flags=0x%02X set=0x%02X cmd=0x%02X payload=<hex>} */
    public String format() {
        return String.format(java.util.Locale.ROOT,
                "target=0x%04X id=0x%04X flags=0x%02X set=0x%02X cmd=0x%02X payload=%s",
                target, id, flags(), cmdSet(), cmdId(), Bytes.hex(payload));
    }

    @Override
    public String toString() {
        return format();
    }

    /** Content equality on all four fields, for tests. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DumlFrame)) return false;
        DumlFrame other = (DumlFrame) o;
        if (target != other.target || id != other.id || type != other.type) return false;
        return java.util.Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return ((target * 31 + id) * 31 + type) * 31 + java.util.Arrays.hashCode(payload);
    }

    /** One-line wrapper for the common {@code new DumlFrame(t,id,type,pl).encode()}. */
    public static byte[] encode(int target, int id, int type, byte[] payload) {
        return new DumlFrame(target, id, type, payload).encode();
    }
}
