package com.watch.osmo.duml;

/**
 * Growable byte sink for building DJI DUML payloads and frames.
 *
 * <p>Port of {@code dev.konraditurbe.osmosis.duml.ByteWriter} (osmosis, MIT). Vendored there from
 * https://github.com/dimadesu/dji-remote (com.dimadesu.djiremote.dji.ByteWriter), MIT.
 *
 * <p>The Kotlin original keeps an {@code ArrayList<Byte>}; this uses a byte[] with doubling so it
 * behaves identically without boxing.
 *
 * <p>Pure Java 8, no Android APIs, no third-party libraries.
 */
public final class Bytes {

    private byte[] buf;
    private int len;

    /** A writer with the default initial capacity (16, as {@code ArrayList}). */
    public Bytes() {
        this(16);
    }

    public Bytes(int initialCapacity) {
        if (initialCapacity < 0) throw new IllegalArgumentException("negative capacity");
        this.buf = new byte[Math.max(initialCapacity, 1)];
        this.len = 0;
    }

    /** Wraps a copy of {@code data} as the writer's current content. */
    public static Bytes of(byte[] data) {
        Bytes w = new Bytes(data.length);
        w.writeBytes(data);
        return w;
    }

    /** A DJI string field: {@code [len:u8][utf8 bytes]}. Port of {@code djiPackString}. */
    public static byte[] packString(String value) {
        byte[] data = Utf8.encode(value);
        Bytes writer = new Bytes(data.length + 1);
        writer.writeU8(data.length);
        writer.writeBytes(data);
        return writer.toByteArray();
    }

    private void ensure(int extra) {
        int need = len + extra;
        if (need <= buf.length) return;
        int cap = buf.length;
        while (cap < need) cap = cap * 2;
        byte[] grown = new byte[cap];
        System.arraycopy(buf, 0, grown, 0, len);
        buf = grown;
    }

    /** Snapshot of everything written so far. Kotlin's {@code ByteWriter.data}. */
    public byte[] toByteArray() {
        byte[] out = new byte[len];
        System.arraycopy(buf, 0, out, 0, len);
        return out;
    }

    /** Alias for {@link #toByteArray()} under the Kotlin property name. */
    public byte[] data() {
        return toByteArray();
    }

    /** Number of bytes written. */
    public int size() {
        return len;
    }

    public void writeU8(int value) {
        ensure(1);
        buf[len++] = (byte) (value & 0xFF);
    }

    /** Alias for {@link #writeU8(int)} under the Kotlin name. */
    public void writeUInt8(int value) {
        writeU8(value);
    }

    public void writeU16Le(int value) {
        ensure(2);
        buf[len++] = (byte) (value & 0xFF);
        buf[len++] = (byte) ((value >>> 8) & 0xFF);
    }

    /** Alias for {@link #writeU16Le(int)} under the Kotlin name. */
    public void writeUInt16Le(int value) {
        writeU16Le(value);
    }

    public void writeU16Be(int value) {
        ensure(2);
        buf[len++] = (byte) ((value >>> 8) & 0xFF);
        buf[len++] = (byte) (value & 0xFF);
    }

    /** Alias for {@link #writeU16Be(int)} under the Kotlin name. */
    public void writeUInt16Be(int value) {
        writeU16Be(value);
    }

    public void writeU24Le(int value) {
        ensure(3);
        buf[len++] = (byte) (value & 0xFF);
        buf[len++] = (byte) ((value >>> 8) & 0xFF);
        buf[len++] = (byte) ((value >>> 16) & 0xFF);
    }

    /** Alias for {@link #writeU24Le(int)} under the Kotlin name. */
    public void writeUInt24Le(int value) {
        writeU24Le(value);
    }

    public void writeU32Le(int value) {
        writeU16Le(value & 0xFFFF);
        writeU16Le((value >>> 16) & 0xFFFF);
    }

    public void writeU32Le(long value) {
        writeU32Le((int) value);
    }

    public void writeBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, len, bytes.length);
        len += bytes.length;
    }

    /** Sequential little-endian reader. Port of {@code ByteReader} (osmosis, MIT). */
    public static final class Reader {
        private final byte[] data;
        private int pos;

        public Reader(byte[] data) {
            if (data == null) throw new IllegalArgumentException("data is null");
            this.data = data;
            this.pos = 0;
        }

        /** Kotlin's {@code bytesAvailable}. */
        public int available() {
            return data.length - pos;
        }

        /** Kotlin's {@code bytesAvailable}. */
        public int bytesAvailable() {
            return available();
        }

        public int position() {
            return pos;
        }

        public int remaining() {
            return available();
        }

        private void checkRemaining(int n) {
            if (pos + n > data.length) throw new IllegalArgumentException("not enough bytes");
        }

        public int readU8() {
            checkRemaining(1);
            return data[pos++] & 0xFF;
        }

        /** Alias for {@link #readU8()} under the Kotlin name. */
        public int readUInt8() {
            return readU8();
        }

        public int readU16Le() {
            checkRemaining(2);
            int lo = data[pos++] & 0xFF;
            int hi = data[pos++] & 0xFF;
            return lo | (hi << 8);
        }

        /** Alias for {@link #readU16Le()} under the Kotlin name. */
        public int readUInt16Le() {
            return readU16Le();
        }

        public int readU16Be() {
            checkRemaining(2);
            int hi = data[pos++] & 0xFF;
            int lo = data[pos++] & 0xFF;
            return (hi << 8) | lo;
        }

        /** Alias for {@link #readU16Be()} under the Kotlin name. */
        public int readUInt16Be() {
            return readU16Be();
        }

        public int readU24Le() {
            checkRemaining(3);
            int b0 = data[pos++] & 0xFF;
            int b1 = data[pos++] & 0xFF;
            int b2 = data[pos++] & 0xFF;
            return b0 | (b1 << 8) | (b2 << 16);
        }

        /** Alias for {@link #readU24Le()} under the Kotlin name. */
        public int readUInt24Le() {
            return readU24Le();
        }

        public int readU32Le() {
            checkRemaining(4);
            int b0 = data[pos++] & 0xFF;
            int b1 = data[pos++] & 0xFF;
            int b2 = data[pos++] & 0xFF;
            int b3 = data[pos++] & 0xFF;
            return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
        }

        public byte[] readBytes(int count) {
            checkRemaining(count);
            byte[] out = new byte[count];
            System.arraycopy(data, pos, out, 0, count);
            pos += count;
            return out;
        }

        /** Whatever is left, without advancing. */
        public byte[] rest() {
            return readBytes(available());
        }
    }

    /**
     * UTF-8 encoding without {@code String.getBytes} surprises: Java 8 already has a UTF-8 charset,
     * this only centralises it and documents the one place it is used.
     */
    static final class Utf8 {
        private Utf8() {
        }

        static byte[] encode(String s) {
            try {
                return s.getBytes("UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                // UTF-8 is required of every JVM; unreachable.
                throw new IllegalStateException("UTF-8 unavailable", e);
            }
        }
    }

    /** A reader over {@code data}, positioned at 0. Convenience for {@code new Bytes.Reader(data)}. */
    public static Reader reader(byte[] data) {
        return new Reader(data);
    }

    /** Lower-case hex of a byte array, matching Kotlin's {@code "%02x".format(it)} join. */
    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            sb.append(HEX[(bytes[i] >>> 4) & 0x0F]);
            sb.append(HEX[bytes[i] & 0x0F]);
        }
        return sb.toString();
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Parse a hex string (whitespace ignored), matching {@code DumlTransport.hex}. */
    public static byte[] fromHex(String s) {
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
}
