package com.watch.osmo.duml;

/**
 * Builders for ready-to-write DUML command frames (BLE fff5).
 *
 * <p>Port of {@code dev.konraditurbe.osmosis.duml.OsmoCommands} + {@code Payloads} (osmosis, MIT),
 * extended with the camera-side payload builders that live in {@code CameraSession} (registration,
 * media list, subscriptions, gimbal init) because those same transactions are the ones this watch
 * client needs. Each method names its Kotlin origin in its javadoc.
 *
 * <p>A note on addressing, because two different conventions are in play and the comments in the
 * Kotlin source mix them: a frame's 16-bit {@code target} is {@code (appId) | (receiverByte << 8)},
 * and the receiver byte is itself {@code (id << 5) | type}. So the shorthand "0x07" target used in
 * prose means receiver byte {@code 0x07} (type 7, id 0), whose full target field is {@code 0x0702}
 * with sender App = {@code 0x02}. "0x07/0x0e" means cmdSet {@code 0x07}, cmdId {@code 0x0e}.
 *
 * <p>Pure Java 8, no Android APIs, no third-party libraries.
 */
public final class OsmoCommands {

    private OsmoCommands() {
    }

    // ---- targets ---------------------------------------------------------------------------------
    /** Sender App(0x02) -> receiver WiFi(0x07). Receiver byte 0x07 = (id 0 &lt;&lt; 5) | type 7. */
    public static final int TARGET_APP_TO_WIFI = 0x0702;
    /** App(0x02) -> Camera(0x01). Receiver byte 0x01 = (id 0 &lt;&lt; 5) | type 1. */
    public static final int TARGET_APP_TO_CAMERA = 0x0102;
    /** App(0x02) -> DM368(0x08). */
    public static final int TARGET_APP_TO_DM368 = 0x0802;
    /** App(0x02) -> system/RTC subsystem. Receiver byte 0x28 = (id 1 &lt;&lt; 5) | type 8. */
    public static final int TARGET_APP_TO_SYSTEM = 0x2802;
    /**
     * App(0x02) -> type 0x10 / id 7 (receiver byte 0xF0). NOT the camera: 0x00/0x2b sent to the
     * camera is answered {@code e0} (reject) and the wake never happens.
     */
    public static final int TARGET_APP_TO_SESSION = 0xF002;
    /** App(0x02) -> type 0x1C / id 0 (receiver byte 0x1C). NOT the camera, same reason. */
    public static final int TARGET_APP_TO_1C = 0x1C02;
    /** App(0x02) -> type 3 / id 0 (receiver byte 0x03). Where gimbal-init is addressed. */
    public static final int TARGET_APP_TO_GIMBAL = 0x0302;

    // ---- type constants --------------------------------------------------------------------------
    // 24-bit LE `type` = flags | (cmdSet << 8) | (cmdId << 16).
    /** flags 0x40, set 0x07, cmd 0x45. */
    public static final int TYPE_SET_PAIRING_PIN = 0x450740;
    /** flags 0x40, set 0x07, cmd 0x47. */
    public static final int TYPE_CONNECT_WIFI = 0x470740;

    /** Request flag: 0x40 in the low byte of `type`. */
    public static final int FLAG_REQUEST = 0x40;

    // ---- message ids -----------------------------------------------------------------------------
    /** Id used by SetPairingPIN. Kotlin {@code PAIR_MSG_ID}. */
    public static final int PAIR_MSG_ID = 0x8092;
    /** Id used by ConnectToWiFi. Kotlin {@code WIFI_MSG_ID}. */
    public static final int WIFI_MSG_ID = 0x8C19;
    /** Default id for {@code wifiQuery}, and the base for the derived ids below. Kotlin default. */
    public static final int DEFAULT_QUERY_ID = 0x8000;

    // Wifi credential reads: Mimo's post-pair sequence sends the cmdId as the low byte of the id
    // (MainActivity: `wifiQuery(0x07, id = 0x8007)`, `wifiQuery(0x0E, id = 0x800E)`). No Kotlin
    // constants exist for these, so they are named here; the values are Mimo's, not invented.
    /** 0x07/0x07 SSID request id. */
    public static final int SSID_MSG_ID = 0x8007;
    /** 0x07/0x0e password request id. */
    public static final int PASSWORD_MSG_ID = 0x800E;
    /** 0x07/0x0c MAC request id — derived by the same rule; no Kotlin call site pins this one. */
    public static final int MAC_MSG_ID = 0x800C;

    // The remaining ids are NOT present in the Kotlin source (the camera path calls sendDuml with a
    // sequence number instead). Chosen by the same low-byte-of-cmdId rule as the wifi queries above.
    /** 0x00/0x26 media list request id. Derived. */
    public static final int MEDIA_LIST_MSG_ID = 0x8026;
    /** 0x00/0x81 device-info id. Derived. */
    public static final int DEVICE_INFO_MSG_ID = 0x8081;
    /** 0x00/0x88 register/presence id. Derived. */
    public static final int REGISTER_MSG_ID = 0x8088;
    /** Base id for the 0x00/0x99 subscriptions; key i gets {@code SUBSCRIBE_MSG_ID + i}. Derived. */
    public static final int SUBSCRIBE_MSG_ID = 0x8099;
    /** 0x03/0xDA gimbal-init id. Derived. */
    public static final int GIMBAL_INIT_MSG_ID = 0x80DA;
    /** Id used by the sessionPing/5310 session frames. Kotlin {@code 0x802B}/{@code 0x8053}. */
    public static final int SESSION_PING_MSG_ID = 0x802B;
    /** Kotlin {@code session5310}'s default id. */
    public static final int SESSION_5310_MSG_ID = 0x8053;

    // ---- wifi ------------------------------------------------------------------------------------

    /**
     * SetPairingPIN (CmdSet 0x07 / CmdId 0x45). {@code pin} is the second PackString field;
     * {@code identifier} is the app identity, and the field the device keys its remembered approval on.
     *
     * <p>Reply on 0x07/0x45 is {@code [00][status]}: {@code 0x01} already paired, {@code 0x02}
     * approval required. The approval itself arrives as 0x07/0x46, a request (flags 0x40) rather than
     * a response, so it has to be answered like any other request or the device drops the link.
     */
    public static byte[] setPairingPin(String pin, int id, String identifier) {
        byte[] payload = pairMessagePayload(pin, identifier);
        return DumlFrame.encode(TARGET_APP_TO_WIFI, id, TYPE_SET_PAIRING_PIN, payload);
    }

    /** {@link #setPairingPin(String, int, String)} with the Kotlin defaults (id 0x8092, default identity). */
    public static byte[] setPairingPin(String pin) {
        return setPairingPin(pin, PAIR_MSG_ID, PairMessagePayload.DEFAULT_IDENTIFIER);
    }

    /** {@link #setPairingPin(String, int, String)} with the Kotlin default identity. */
    public static byte[] setPairingPin(String pin, int id) {
        return setPairingPin(pin, id, PairMessagePayload.DEFAULT_IDENTIFIER);
    }

    /**
     * ConnectToWiFi (CmdSet 0x07 / CmdId 0x47). Payload = PackString(ssid) + PackString(password).
     * Camera replies 0x07/0x47 (0x0000 = ok). Mimo never sends this; it is the fallback for models
     * that never surface credentials over BLE.
     */
    public static byte[] connectWifi(String ssid, String password, int id) {
        byte[] payload = concat(Bytes.packString(ssid), Bytes.packString(password));
        return DumlFrame.encode(TARGET_APP_TO_WIFI, id, TYPE_CONNECT_WIFI, payload);
    }

    /** {@link #connectWifi(String, String, int)} with the Kotlin default id (0x8C19). */
    public static byte[] connectWifi(String ssid, String password) {
        return connectWifi(ssid, password, WIFI_MSG_ID);
    }

    /** Generic request to the WiFi subsystem (CmdSet 0x07) with an arbitrary cmdId + payload. */
    public static byte[] wifiQuery(int cmdId, byte[] payload, int id) {
        int type = FLAG_REQUEST | (0x07 << 8) | (cmdId << 16);
        return DumlFrame.encode(TARGET_APP_TO_WIFI, id, type, payload);
    }

    /** {@link #wifiQuery(int, byte[], int)} with the Kotlin payload/id defaults. */
    public static byte[] wifiQuery(int cmdId) {
        return wifiQuery(cmdId, new byte[0], DEFAULT_QUERY_ID);
    }

    /** {@link #wifiQuery(int, byte[], int)} with the Kotlin payload default. */
    public static byte[] wifiQuery(int cmdId, int id) {
        return wifiQuery(cmdId, new byte[0], id);
    }

    /** 0x07/0x07 — WiFi SSID. Reply is {@code [status:u8][PackString ssid]}. */
    public static byte[] wifiSsidQuery() {
        return wifiQuery(0x07, SSID_MSG_ID);
    }

    /** 0x07/0x0e — WiFi password. Reply is {@code [status:u8][PackString password]}. */
    public static byte[] wifiPasswordQuery() {
        return wifiQuery(0x0E, PASSWORD_MSG_ID);
    }

    /** 0x07/0x0c — WiFi MAC. */
    public static byte[] wifiMacQuery() {
        return wifiQuery(0x0C, MAC_MSG_ID);
    }

    /**
     * Parse a {@code [status:1][PackString]} reply (0x07/0x07 SSID, 0x07/0x0e password): status byte,
     * then a length-prefixed string. Returns null if malformed. Port of
     * {@code MainActivity.parseStatusPackString} (US-ASCII there; kept).
     */
    public static String parseStatusPackString(byte[] p) {
        if (p == null || p.length < 2) return null;
        int len = p[1] & 0xFF;
        if (2 + len > p.length) return null;
        try {
            return new String(p, 2, len, "US-ASCII");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("US-ASCII unavailable", e);
        }
    }

    // ---- generic ---------------------------------------------------------------------------------

    /** Generic command to an arbitrary cmdset/cmdId + target, mirroring {@link #wifiQuery}'s framing. */
    public static byte[] command(int cmdSet, int cmdId, byte[] payload, int target, int id) {
        int type = FLAG_REQUEST | (cmdSet << 8) | (cmdId << 16);
        return DumlFrame.encode(target, id, type, payload);
    }

    /** {@link #command(int, int, byte[], int, int)} with the Kotlin target/id defaults. */
    public static byte[] command(int cmdSet, int cmdId) {
        return command(cmdSet, cmdId, new byte[0], TARGET_APP_TO_CAMERA, DEFAULT_QUERY_ID);
    }

    /** {@link #command(int, int, byte[], int, int)} with the Kotlin id default. */
    public static byte[] command(int cmdSet, int cmdId, byte[] payload) {
        return command(cmdSet, cmdId, payload, TARGET_APP_TO_CAMERA, DEFAULT_QUERY_ID);
    }

    /**
     * {@code 0x00/0x2b} — the session/heartbeat frame Mimo opens with and then repeats. Payload
     * {@code 04 00} is what it sends first (before pairing) on a sleeping camera; {@code 01 01} is
     * the repeating form.
     */
    public static byte[] sessionPing(byte[] payload, int id) {
        return command(0x00, 0x2B, payload, TARGET_APP_TO_SESSION, id);
    }

    /** {@link #sessionPing(byte[], int)} with the Kotlin default id (0x802B). */
    public static byte[] sessionPing(byte[] payload) {
        return sessionPing(payload, SESSION_PING_MSG_ID);
    }

    /** Payload of the first 0x00/0x2b Mimo sends to a sleeping camera. */
    public static final byte[] SESSION_WAKE = new byte[] { 0x04, 0x00 };
    /** Payload of the repeating 0x00/0x2b keepalive. */
    public static final byte[] SESSION_KEEPALIVE = new byte[] { 0x01, 0x01 };

    /**
     * {@code 0x53/0x10} {@code 00 00 00 00} -> type 0x1C. The camera answers {@code 01 00 00 00} for
     * Mimo; addressed to the camera instead it answers {@code e0} (reject). This is the command that
     * actually correlates with the camera waking.
     */
    public static byte[] session5310(int id) {
        return command(0x53, 0x10, new byte[] { 0, 0, 0, 0 }, TARGET_APP_TO_1C, id);
    }

    /** {@link #session5310(int)} with the Kotlin default id (0x8053). */
    public static byte[] session5310() {
        return session5310(SESSION_5310_MSG_ID);
    }

    // ---- camera registration ----------------------------------------------------------------------

    /**
     * App device-info blob ("APP" identity), 62 bytes, as {@code CameraSession.appDeviceInfo()} builds
     * it: zeroed, {@code 'A''P''P'} at bytes 1..3, {@code 0x02} at 41, {@code 0x02 0x08} at 50..51.
     *
     * <p>Sent as 0x00/0x81 to receiver 0x28. Note that this is the <em>camera</em> blob; the
     * {@code OsmoCommands.APP_DEVICE_INFO} constant in the Kotlin source is a different, older 62-byte
     * shape (it puts a leading 0x00 before "APP", which shifts the later zero runs by one) and is not
     * what the camera path actually transmits. Both are provided: this one and
     * {@link #APP_DEVICE_INFO_LEGACY}.
     */
    public static byte[] appDeviceInfo() {
        byte[] b = new byte[62];
        b[1] = (byte) 'A';
        b[2] = (byte) 'P';
        b[3] = (byte) 'P';
        b[41] = 0x02;
        b[50] = 0x02;
        b[51] = 0x08;
        return b;
    }

    /**
     * {@code OsmoCommands.APP_DEVICE_INFO} verbatim: {@code 00 41 50 50} + 37 zeros + {@code 02} +
     * 8 zeros + {@code 02 08} + 10 zeros.
     */
    public static final byte[] APP_DEVICE_INFO_LEGACY = concat(
            new byte[] { 0x00, 0x41, 0x50, 0x50 }, new byte[37],
            new byte[] { 0x02 }, new byte[8], new byte[] { 0x02, 0x08 }, new byte[10]);

    /**
     * 0x00/0x81 device-info exchange, addressed like the camera path: receiver 0x28, cmdType 4
     * (Command) so the low byte of `type` is {@code 0x04 << 5 = 0x80} rather than 0x40.
     */
    public static byte[] deviceInfo(int id) {
        return deviceInfo(appDeviceInfo(), id);
    }

    /** {@link #deviceInfo(int)} with the derived default id. */
    public static byte[] deviceInfo() {
        return deviceInfo(DEVICE_INFO_MSG_ID);
    }

    /** {@link #deviceInfo(int)} with a caller-supplied payload. */
    public static byte[] deviceInfo(byte[] payload, int id) {
        int type = (4 << 5) | (0x00 << 8) | (0x81 << 16);   // cmdType 4, as sendDuml(cmdType = 4)
        return DumlFrame.encode(TARGET_APP_TO_SYSTEM, id, type, payload);
    }

    /**
     * {@code 0x00/0x88} sub-command {@code 0x17} — the app telling the camera it is still here, ASCII
     * {@code APP} at bytes 5..7. Sent at registration and every ~1 s for the whole session.
     * Port of {@code CameraSession.APP_PRESENCE}.
     */
    public static final byte[] APP_PRESENCE = Bytes.fromHex("170046237c415050000000000002");

    /**
     * 0x00/0x88 presence beat, addressed to receiver 0x28 (receiverType 0x08, receiverId 1).
     * Port of {@code CameraSession.sendDuml(0x00, 0x88, APP_PRESENCE, receiverType = 0x08, receiverId = 1)}.
     */
    public static byte[] register(int id) {
        return register(APP_PRESENCE, id);
    }

    /** {@link #register(int)} with the derived default id. */
    public static byte[] register() {
        return register(REGISTER_MSG_ID);
    }

    /** {@link #register(int)} with a caller-supplied presence payload. */
    public static byte[] register(byte[] payload, int id) {
        return command(0x00, 0x88, payload, TARGET_APP_TO_SYSTEM, id);
    }

    /**
     * A {@code 0x00/0x99} status subscription, byte-for-byte as Mimo sends it (port of
     * {@code CameraSession.subscription}):
     *
     * <pre>
     * [02 02 00 00][subId u32-LE][00 00 00][innerLen u16-LE][nameLen u16-LE][name][00 00 00 00]
     * innerLen = nameLen + 6                                  name is NOT padded
     * </pre>
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

    /** One 0x00/0x99 subscribe frame: payload above, addressed to receiver 0x28. */
    public static byte[] subscribe(String name, int subId, int id) {
        return command(0x00, 0x99, subscriptionPayload(name, subId), TARGET_APP_TO_SYSTEM, id);
    }

    /** {@link #subscribe(String, int, int)} with the derived default id. */
    public static byte[] subscribe(String name, int subId) {
        return subscribe(name, subId, SUBSCRIBE_MSG_ID);
    }

    /**
     * The status keys this porting effort follows. Eight, deliberately — NOT Mimo's 54; raising it
     * swamps the media-manifest stream (see {@code CameraSession.paramSubs}).
     */
    public static final String[] PARAM_SUBS = new String[] {
            "camcap_mode_profile", "camcap_video_format", "camcap_fov", "camcap_iso",
            "camcap_photo_storage_format", "camcap_color_mode", "cam_storage", "cam_status",
    };

    /** The first subscription id Mimo/moblin use ({@code subId = 0x69DF}, incrementing per key). */
    public static final int FIRST_SUB_ID = 0x69DF;

    /** All eight subscribe frames, ids running from {@link #FIRST_SUB_ID}, like the Kotlin loop. */
    public static byte[][] subscribeAll() {
        byte[][] out = new byte[PARAM_SUBS.length][];
        for (int i = 0; i < PARAM_SUBS.length; i++) {
            out[i] = subscribe(PARAM_SUBS[i], FIRST_SUB_ID + i, SUBSCRIBE_MSG_ID + i);
        }
        return out;
    }

    /**
     * 0x03/0xDA gimbal init ({@code 05 ff ff ff ff}) to receiver 0x03 — the third frame of the
     * camera registration sequence.
     */
    public static byte[] gimbalInit(int id) {
        return command(0x03, 0xDA, Bytes.fromHex("05ffffffff"), TARGET_APP_TO_GIMBAL, id);
    }

    /** {@link #gimbalInit(int)} with the derived default id. */
    public static byte[] gimbalInit() {
        return gimbalInit(GIMBAL_INIT_MSG_ID);
    }

    // ---- media manifest (0x00/0x26) ---------------------------------------------------------------

    /** Records requested per file-list query — Mimo's own {@code BaseFetchData.mCount} is 45. */
    public static final int PAGE_SIZE = 45;
    /** Newest-page selector for the SD store (counter 1). */
    public static final long NEWEST_SD = 0x00000001L;
    /** Newest-page selector for the internal store (counter 2; the 0x40000000 bit selects it). */
    public static final long NEWEST_INTERNAL = 0x40000001L;
    /** Request counter that goes at payload byte 4 for the SD query. */
    public static final int SD_QUERY_CTR = 1;
    /** Request counter that goes at payload byte 4 for the internal query. */
    public static final int INTERNAL_QUERY_CTR = 2;
    /** Byte-identical trigger Mimo interleaves between the two list queries. */
    public static final byte[] LIST_TRIGGER = Bytes.fromHex("4a040e1001000000000001000000");

    /**
     * The full-list request payload (port of {@code CameraSession.listCmd}): the 39-byte template with
     * the request counter at byte 4, the 4-byte LE page cursor at bytes 10..13 and the record count at
     * byte 14.
     */
    public static byte[] listPayload(int ctr, long cursor, int pageSize) {
        byte[] p = Bytes.fromHex(
                "4a002a10010000000000010000002d000d0100ffffffffffffffff000100000000000000000000000000");
        p[4] = (byte) ctr;
        p[14] = (byte) pageSize;
        p[10] = (byte) (cursor & 0xFF);
        p[11] = (byte) ((cursor >>> 8) & 0xFF);
        p[12] = (byte) ((cursor >>> 16) & 0xFF);
        p[13] = (byte) ((cursor >>> 24) & 0xFF);
        return p;
    }

    /** {@link #listPayload(int, long, int)} at the default {@link #PAGE_SIZE}. */
    public static byte[] listPayload(int ctr, long cursor) {
        return listPayload(ctr, cursor, PAGE_SIZE);
    }

    /** Port of {@code CameraSession.buildListCmdForTest}. */
    public static byte[] buildListCmdForTest(int ctr, long cursor) {
        return listPayload(ctr, cursor);
    }

    /**
     * A full 0x00/0x26 media-list query frame. The Kotlin path reaches this through
     * {@code sendDuml(0x00, 0x26, listCmd(...), receiverType = 0x01, receiverId = 0)}; the id is
     * synthesised here because that call site uses a transport sequence number, not an id.
     */
    public static byte[] mediaListQuery(int ctr, long cursor, int id) {
        return command(0x00, 0x26, listPayload(ctr, cursor), TARGET_APP_TO_CAMERA, id);
    }

    /** {@link #mediaListQuery(int, long, int)} with the derived default id. */
    public static byte[] mediaListQuery(int ctr, long cursor) {
        return mediaListQuery(ctr, cursor, MEDIA_LIST_MSG_ID);
    }

    /** The newest SD page: counter 1, selector {@link #NEWEST_SD}. */
    public static byte[] mediaListQueryNewestSd() {
        return mediaListQuery(SD_QUERY_CTR, NEWEST_SD);
    }

    /** The newest internal-memory page: counter 2, selector {@link #NEWEST_INTERNAL}. */
    public static byte[] mediaListQueryNewestInternal() {
        return mediaListQuery(INTERNAL_QUERY_CTR, NEWEST_INTERNAL);
    }

    /** Mimo's interleaved trigger frame (0x00/0x26, payload {@link #LIST_TRIGGER}). */
    public static byte[] mediaListTrigger(int id) {
        return command(0x00, 0x26, LIST_TRIGGER, TARGET_APP_TO_CAMERA, id);
    }

    /** {@link #mediaListTrigger(int)} with the derived default id. */
    public static byte[] mediaListTrigger() {
        return mediaListTrigger(MEDIA_LIST_MSG_ID);
    }

    // ---- per-file commands -------------------------------------------------------------------------

    /**
     * Delete payload (0x00/0x28), port of {@code CameraSession.deletePayload}:
     * {@code [n:u8] [handle:u32-LE]*n [counter:u32-LE] 00 [n:u32-LE] 01 01 00 00}.
     *
     * <p>The field after the handles is a <b>per-request counter</b>, not a second copy of the
     * count — it advances 1, 2, 3… across a session.
     */
    public static byte[] deletePayload(long[] handles, int counter) {
        Bytes w = new Bytes(1 + handles.length * 4 + 4 + 1 + 4 + 4);
        w.writeU8(handles.length & 0xFF);
        for (int i = 0; i < handles.length; i++) w.writeU32Le((int) handles[i]);
        w.writeU32Le(counter);
        w.writeU8(0x00);
        w.writeU32Le(handles.length);
        w.writeBytes(new byte[] { 0x01, 0x01, 0x00, 0x00 });
        return w.toByteArray();
    }

    /**
     * Favourite payload (0x02/0xbf, port of {@code CameraSession.favoritePayload}):
     * {@code 01 01 [handle:u32-LE][counter:u32-LE] 00 [on:u8] 00 00 00}.
     */
    public static byte[] favoritePayload(long handle, int counter, boolean on) {
        Bytes w = new Bytes(13);
        w.writeBytes(new byte[] { 0x01, 0x01 });
        w.writeU32Le((int) handle);
        w.writeU32Le(counter);
        w.writeU8(0x00);
        w.writeU8(on ? 0x01 : 0x00);
        w.writeBytes(new byte[] { 0x00, 0x00, 0x00 });
        return w.toByteArray();
    }

    // ---- helpers ------------------------------------------------------------------------------------

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

    private static byte[] ascii(String s) {
        try {
            return s.getBytes("US-ASCII");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("US-ASCII unavailable", e);
        }
    }

    /** Port of {@code DjiPairMessagePayload} (osmosis, MIT). */
    public static final class PairMessagePayload {

        /** What moblin/dji-remote use; accepted by every Osmo camera. */
        public static final String DEFAULT_IDENTIFIER = "284ae5b8d76b3375a04a6417ad71bea3";

        /**
         * A DJI Fly install UUID, read off a btsnoop of it pairing a Mavic 3. Its shape (a standard
         * UUID with the last group truncated to 8 hex, 32 characters) is what
         * {@link #newInstallIdentity()} mints. Not used as an identity any more; kept for reference.
         */
        public static final String DJI_FLY_IDENTIFIER = "bbf9994f-a1da-44db-b1e0-9d889c5b";

        /**
         * PackString("284ae5b8d76b3375a04a6417ad71bea3") — the 0x20 length prefix + 32 hex chars, as
         * the verbatim constant in the Kotlin source.
         */
        public static final byte[] identifierBlob = new byte[] {
                0x20, 0x32, 0x38, 0x34, 0x61, 0x65, 0x35, 0x62,
                0x38, 0x64, 0x37, 0x36, 0x62, 0x33, 0x33, 0x37,
                0x35, 0x61, 0x30, 0x34, 0x61, 0x36, 0x34, 0x31,
                0x37, 0x61, 0x64, 0x37, 0x31, 0x62, 0x65, 0x61,
                0x33
        };

        /** A fresh per-install identity in the 32-char shape above (UUID's first 32 chars). */
        public static String newInstallIdentity() {
            String uuid = java.util.UUID.randomUUID().toString();
            return uuid.substring(0, 32);
        }

        private PairMessagePayload() {
        }

        /** {@code PackString(identifier) + PackString(pairPinCode)}. */
        public static byte[] encode(String pairPinCode, String identifier) {
            return concat(Bytes.packString(identifier), Bytes.packString(pairPinCode));
        }
    }

    /** Kotlin's {@code DjiPairMessagePayload(pin, identifier).encode()} under the class name. */
    public static byte[] pairMessagePayload(String pairPinCode, String identifier) {
        return PairMessagePayload.encode(pairPinCode, identifier);
    }

    /** {@link #pairMessagePayload(String, String)} with the default identity. */
    public static byte[] pairMessagePayload(String pairPinCode) {
        return PairMessagePayload.encode(pairPinCode, PairMessagePayload.DEFAULT_IDENTIFIER);
    }
}
