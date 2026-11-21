package com.watch.osmo.duml;

/**
 * DJI DUML checksums. Same algorithm as drone-side DUML.
 *
 * <p>Port of {@code dev.konraditurbe.osmosis.duml.DjiCrc} (osmosis, MIT). Vendored there from
 * https://github.com/dimadesu/dji-remote (com.dimadesu.djiremote.dji.DjiCrc), MIT.
 *
 * <p>Pure Java 8, no Android APIs, no third-party libraries.
 */
public final class Crc {

    private Crc() {
    }

    // CRC-8: spec init=0xEE, poly=0x31, refIn=true, refOut=true.
    // Reflected (LSB-first) form uses reflected init/poly: init=reflect8(0xEE)=0x77, poly=reflect8(0x31)=0x8C.
    /** Default reflected CRC-8 initial value ({@code reflect8(0xEE)}). */
    public static final int CRC8_INIT = 0x77;
    /** Default reflected CRC-8 polynomial ({@code reflect8(0x31)}). */
    public static final int CRC8_POLY = 0x8C;

    // CRC-16: spec init=0x496C, poly=0x1021, refIn=true, refOut=true.
    // Reflected form: init=reflect16(0x496C)=0x3692, poly=reflect16(0x1021)=0x8408.
    /** Default reflected CRC-16 initial value ({@code reflect16(0x496C)}). */
    public static final int CRC16_INIT = 0x3692;
    /** Default reflected CRC-16 polynomial ({@code reflect16(0x1021)}). */
    public static final int CRC16_POLY = 0x8408;

    /** CRC-8 over {@code data} with the reflected DUML defaults. Returns an unsigned 0..255 value. */
    public static int crc8(byte[] data) {
        return crc8(data, CRC8_INIT, CRC8_POLY);
    }

    /** CRC-8 over {@code data}. Returns an unsigned 0..255 value. */
    public static int crc8(byte[] data, int initial, int poly) {
        int crc = initial & 0xFF;
        for (int i = 0; i < data.length; i++) {
            crc = crc ^ (data[i] & 0xFF);
            for (int b = 0; b < 8; b++) {
                crc = ((crc & 0x01) != 0) ? (crc >>> 1) ^ poly : crc >>> 1;
            }
        }
        return crc & 0xFF;
    }

    /** CRC-16 over {@code data} with the reflected DUML defaults. Returns an unsigned 0..65535 value. */
    public static int crc16(byte[] data) {
        return crc16(data, CRC16_INIT, CRC16_POLY);
    }

    /** CRC-16 over {@code data}. Returns an unsigned 0..65535 value. */
    public static int crc16(byte[] data, int initial, int poly) {
        int crc = initial & 0xFFFF;
        for (int i = 0; i < data.length; i++) {
            crc = crc ^ (data[i] & 0xFF);
            for (int b = 0; b < 8; b++) {
                crc = ((crc & 0x01) != 0) ? (crc >>> 1) ^ poly : crc >>> 1;
            }
        }
        return crc & 0xFFFF;
    }

    /** Alias for {@link #crc8(byte[])} under the Kotlin name, for side-by-side reading. */
    public static int computeCrc8(byte[] data) {
        return crc8(data);
    }

    /** Alias for {@link #crc8(byte[], int, int)} under the Kotlin name. */
    public static int computeCrc8(byte[] data, int initial, int poly) {
        return crc8(data, initial, poly);
    }

    /** Alias for {@link #crc16(byte[])} under the Kotlin name. */
    public static int computeCrc16(byte[] data) {
        return crc16(data);
    }

    /** Alias for {@link #crc16(byte[], int, int)} under the Kotlin name. */
    public static int computeCrc16(byte[] data, int initial, int poly) {
        return crc16(data, initial, poly);
    }
}
