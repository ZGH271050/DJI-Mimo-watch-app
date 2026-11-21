package com.watch.osmo.proto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decoder for DJI's <b>CompositePack</b> media manifest — the file list an Osmo camera
 * (Nano / Xtra / Action 4-6 / Pocket 3-4) streams back over the DUML UDP datalink in answer to a
 * {@code 0x00/0x26} list request.
 *
 * <p>Pure Java 8. No Android API, no third-party library, no reflection — just byte arrays.
 *
 * <p>Ported field-for-field from the MIT-licensed reference implementation {@code osmosis}
 * ({@code camera/CameraSession.kt} {@code manifestBytes} + {@code decodeComposite} +
 * {@code resolveRecord} + {@code withCmdHandles} + {@code flagHandleCollisions}). It reproduces every
 * record of every captured fixture byte-exactly, which is what {@code test/ManifestSelfTest.java}
 * checks against the committed golden files.
 *
 * <h3>Wire shape</h3>
 * Two raw forms reach {@link #parse(byte[])} and both are accepted:
 * <ol>
 *   <li><b>Raw datagram blob</b> — a concatenation of DUML frames. Each frame is
 *       {@code [55][len:u16le][crc8][target:u16][id:u16][type][set][cmd]} (11 bytes) followed by
 *       {@code len-13} payload bytes and a 2-byte CRC, with {@code len} taken from bits 0..9.
 *       Manifest chunks arrive as {@code cmdset 0x00 / cmd 0x27} whose payload opens with a
 *       <b>10-byte sub-header</b> {@code 4A 01 .. .. <seq:u16le> 00 00} — only {@code payload[10:]}
 *       is data. The sub-header is stripped and the chunks are concatenated <b>in arrival order</b>
 *       (not seq-sorted: on a multi-page list the seq counter restarts per page).</li>
 *   <li><b>Already-reassembled manifest</b> — what the chunks concatenate to. It opens with a
 *       {@code u32-LE} record count, then the records. A blob whose frames carry no chunks is passed
 *       through unchanged.</li>
 * </ol>
 *
 * <h3>Record shape</h3>
 * Every field is length-delimited (tag → length → value), never grepped, so a custom
 * Folder/File naming prefix decodes exactly like stock:
 * <pre>
 *   1a [total:u8] 00 00 00 01  &lt;ascii "DCIM/…"&gt;   media path   (total-6 bytes of value)
 *   1a [total:u8] 00 00 00 02  &lt;ascii "MISC/…"&gt;   thumbnail path
 *   0d [len:u8]                &lt;ascii "&lt;name&gt;.&lt;ext&gt;"&gt;   filename (read only for its extension)
 * </pre>
 * A video record also carries a marker {@code [03|00] [ff|fe] 19 06} at {@code head+8}, with the
 * delete handle {@code u32-LE} at {@code head}, the byte size {@code u32-LE} at {@code head-4}, the
 * duration {@code u16-LE} at {@code head+4}, the frame-rate/resolution codes at {@code head+6/+7}.
 *
 * <h3>Fault tolerance</h3>
 * Nothing here throws on malformed input. Every index is bounds-checked, an unrecognised record is
 * skipped, and a blob with no media-path field at all decodes to an empty list.
 */
public final class ManifestParser {

    // ---- wire constants --------------------------------------------------------------------------

    /** DUML frame start byte; every frame header begins with it. */
    public static final int FRAME_MARKER = 0x55;
    /** DUML command set that carries the media manifest replies. */
    public static final int MEDIA_CMD_SET = 0x00;
    /** DUML command id of a manifest data frame. */
    public static final int MEDIA_CMD_ID = 0x27;
    /** Bytes of per-frame sub-header in front of each manifest chunk. */
    public static final int SUBHEADER_LEN = 10;
    /** Request counter the camera echoes for the SD-card query. */
    public static final int SD_QUERY_COUNTER = 1;
    /** Request counter the camera echoes for the internal-storage query. */
    public static final int INTERNAL_QUERY_COUNTER = 2;
    /** Bit set on an <em>internal</em>-store handle; clear on an SD-store handle. */
    public static final long INTERNAL_STORE_BIT = 0x40000000L;

    /** {@code ISO-8859-1}: byte-preserving 1:1 mapping, so no path byte is ever mangled. */
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    /**
     * The favourite (⭐) signature, constant and once per record on the Xtra / Action 5 / Pocket 3-4
     * layouts; the byte right after it is strictly {@code 00}/{@code 01}.
     */
    private static final byte[] STAR_SIG = {
            0x1b, 0x0a, 0x00, 0x00, 0x00, 0x02, 0x02, 0x01, 0x14, 0x02, 0x15, 0x03,
    };

    private static final Set<String> VIDEO_EXTS = unmodifiableSet("MP4", "MOV", "OSV", "INSV");
    private static final Set<String> STILL_EXTS = unmodifiableSet("JPG", "JPEG", "DNG", "HEIC");
    private static final Set<String> PROXY_EXTS = unmodifiableSet("LRF", "LRV", "XRF");

    private ManifestParser() {
        // static API only
    }

    // ---- public API ------------------------------------------------------------------------------

    /**
     * Parse a raw capture into media files.
     *
     * @param raw a raw DUML datagram blob <em>or</em> an already-reassembled manifest; may be null
     * @return the decoded records, never null, possibly empty. {@code storage} is a best-effort
     *         handle-bit guess ({@link MediaFile#isStorageKnown()} is false) because the merged
     *         decode cannot tell which store answered — use {@link #parseStores(byte[])} when the
     *         blob echoes the per-store request counters.
     */
    public static List<MediaFile> parse(byte[] raw) {
        return parse(raw, false);
    }

    /**
     * Parse a raw capture, with the storage rules of a single-store (microSD-only) body.
     *
     * @param singleSdStorage true for a body like the Osmo Pocket 3, which has only its microSD and
     *                        always serves it at {@code storage=0} whatever the handle bits say
     */
    public static List<MediaFile> parse(byte[] raw, boolean singleSdStorage) {
        if (raw == null || raw.length == 0) {
            return Collections.emptyList();
        }
        List<MediaFile> files = decodeManifest(reassemble(raw), "");
        return guessStorage(files, singleSdStorage);
    }

    /**
     * Parse a blob that is <em>already</em> an assembled manifest — no frame reassembly is attempted.
     *
     * <p>Use this when the caller has already stripped the 10-byte sub-headers (for example the
     * {@code op4_45.bin} capture, which begins straight at the {@code u32-LE} record count).
     */
    public static List<MediaFile> parseAssembled(byte[] manifest) {
        if (manifest == null || manifest.length == 0) {
            return Collections.emptyList();
        }
        return guessStorage(decodeManifest(manifest, ""), false);
    }

    /**
     * Parse a raw capture and split it into its per-store answers, so each file's store is known
     * from the query that returned it instead of guessed from its handle.
     *
     * <p>Counter {@value #SD_QUERY_COUNTER} is the SD query and counter {@value #INTERNAL_QUERY_COUNTER}
     * the internal one; both namespaces are decoded separately and then tagged. Falls back to the
     * merged decode (with per-file guessing) when the split cannot be trusted: a camera that does not
     * echo the counter, or one that answers both queries with the same list.
     */
    public static List<MediaFile> parseStores(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return Collections.emptyList();
        }
        List<MediaFile> sd = decodeStoreSlice(raw, SD_QUERY_COUNTER, MediaFile.STORAGE_SD);
        List<MediaFile> internal = decodeStoreSlice(raw, INTERNAL_QUERY_COUNTER, MediaFile.STORAGE_INTERNAL);

        boolean ambiguous = !sd.isEmpty() && !internal.isEmpty()
                && pathsOf(sd).equals(pathsOf(internal));
        if ((sd.isEmpty() && internal.isEmpty()) || ambiguous) {
            return parse(raw);
        }
        // Internal first: it is the larger store on every camera we have, so on the rare overlap the
        // kept copy is the one more likely to be right.
        LinkedHashMap<String, MediaFile> byPath = new LinkedHashMap<String, MediaFile>();
        for (MediaFile f : internal) {
            if (!byPath.containsKey(f.getPath())) {
                byPath.put(f.getPath(), f);
            }
        }
        for (MediaFile f : sd) {
            if (!byPath.containsKey(f.getPath())) {
                byPath.put(f.getPath(), f);
            }
        }
        return new ArrayList<MediaFile>(byPath.values());
    }

    /**
     * Strip the per-frame 10-byte sub-headers and concatenate the manifest chunks in arrival order.
     *
     * <p>Returns {@code raw} unchanged when the blob carries no such frames, or when the reassembled
     * stream carries fewer intact media paths than the raw blob does (i.e. a model that streams its
     * list some other way must keep the whole-blob parse).
     */
    public static byte[] reassemble(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return new byte[0];
        }
        byte[] bytes = extractChunks(raw, -1);
        if (bytes.length == 0) {
            return raw;
        }
        return countRecords(bytes) >= countRecords(raw) ? bytes : raw;
    }

    /** {@link #reassemble(byte[])} restricted to the frames echoing {@code requestCounter}. */
    public static byte[] reassemble(byte[] raw, int requestCounter) {
        if (raw == null) {
            return new byte[0];
        }
        return extractChunks(raw, requestCounter);
    }

    /** True when the assembled manifest carries the {@code 0c 01 0d} end-of-list TLV. */
    public static boolean hasEndMarker(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        for (int i = 0; i + 3 <= bytes.length; i++) {
            if (bytes[i] == 0x0C && bytes[i + 1] == 0x01 && bytes[i + 2] == 0x0D) {
                return true;
            }
        }
        return false;
    }

    /**
     * The manifest's own declared record count ({@code u32-LE} at offset 0), or -1 when the bytes are
     * too short. Not every model writes a plausible count there — treat it as a hint, not truth.
     */
    public static long declaredCount(byte[] bytes) {
        return bytes != null && bytes.length >= 4 ? u32le(bytes, 0) : -1L;
    }

    /** Distinct media-path fields ({@code DCIM/…}) in a byte range, counted by structure. */
    public static int countRecords(byte[] bytes) {
        if (bytes == null) {
            return 0;
        }
        HashSet<String> seen = new HashSet<String>();
        int i = 0;
        while (i < bytes.length) {
            Tlv f = readPathField(bytes, i, 1, "DCIM/");
            if (f != null) {
                seen.add(f.value);
                i = f.end;
            } else {
                i++;
            }
        }
        return seen.size();
    }

    /** The DJI-wide video-format index → pixel size map; null for an unmapped code. */
    public static String resolutionForIndex(int code) {
        switch (code) {
            case 10: return "1920x1080";  // 0x0A  1080p 16:9
            case 12: return "1920x1440";  // 0x0C  1080p 4:3
            case 16: return "3840x2160";  // 0x10  4K 16:9
            case 45: return "2688x1512";  // 0x2D  2.7K 16:9
            case 66: return "1080x1920";  // 0x42  1080p 9:16 vertical
            case 67: return "1512x2688";  // 0x43  2.7K 9:16 vertical
            case 95: return "2688x2016";  // 0x5F  2.7K 4:3
            case 103: return "3840x2880"; // 0x67  4K 4:3
            case 105: return "1080x1080"; // 0x69  1080p 1:1
            case 106: return "2160x2160"; // 0x6A  2160p 1:1
            case 107: return "3072x3072"; // 0x6B  3K 1:1
            case 108: return "1728x3072"; // 0x6C  3K 9:16 vertical
            case 125: return "3840x3840"; // 0x7D  4K 1:1 aka OpenGate
            default: return null;
        }
    }

    // ---- frame reassembly ------------------------------------------------------------------------

    private static byte[] extractChunks(byte[] raw, int requestCounter) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
        int i = 0;
        while (i + 13 <= raw.length) {
            if ((raw[i] & 0xFF) != FRAME_MARKER) {
                i++;
                continue;
            }
            int len = ((raw[i + 1] & 0xFF) | ((raw[i + 2] & 0xFF) << 8)) & 0x3FF;
            if (len < 13 || i + len > raw.length) {
                // A false 0x55 inside a payload: step one byte and keep scanning.
                i++;
                continue;
            }
            // The 11-byte header is [55][len:2][crc8][target:2][id:2][type][set][cmd].
            int cmdSet = raw[i + 9] & 0xFF;
            int cmdId = raw[i + 10] & 0xFF;
            int plStart = i + 11;
            int plLen = len - 13;
            if (cmdSet == MEDIA_CMD_SET && cmdId == MEDIA_CMD_ID && plLen > SUBHEADER_LEN
                    && (raw[plStart] & 0xFF) == 0x4A && (raw[plStart + 1] & 0xFF) == 0x01) {
                // Sub-header byte 4 echoes the counter of the request that asked for this chunk; that
                // is what lets one blob be split back into the per-store answers it contains.
                boolean counterOk = requestCounter < 0 || (raw[plStart + 4] & 0xFF) == requestCounter;
                if (counterOk) {
                    out.write(raw, plStart + SUBHEADER_LEN, plLen - SUBHEADER_LEN);
                }
            }
            i += len;
        }
        return out.toByteArray();
    }

    private static List<MediaFile> decodeStoreSlice(byte[] raw, int counter, int store) {
        byte[] bytes = extractChunks(raw, counter);
        if (bytes.length == 0) {
            return Collections.emptyList();
        }
        List<MediaFile> files = decodeManifest(bytes, counter == SD_QUERY_COUNTER ? "SD" : "internal");
        List<MediaFile> out = new ArrayList<MediaFile>(files.size());
        for (MediaFile f : files) {
            out.add(copy(f, f.getHandle(), f.getCmdHandle(), store, true, f.isHandleShared()));
        }
        return out;
    }

    // ---- the decoder -----------------------------------------------------------------------------

    private static List<MediaFile> decodeManifest(byte[] bytes, String store) {
        // A blob with no CompositePack media-path field at all decodes to empty rather than throwing;
        // the reference falls back to a loose regex scrape there, which this port deliberately omits
        // (the watch app only ever talks to the CompositePack family).
        return flagHandleCollisions(decodeComposite(bytes, store));
    }

    /** A length-delimited CompositePack field: its ASCII value and the offset just past it. */
    private static final class Tlv {
        final String value;
        final int end;

        Tlv(String value, int end) {
            this.value = value;
            this.end = end;
        }
    }

    /** One record's anchor: where its media-path field starts, where it ends, and the path. */
    private static final class Media {
        final int pos;
        final int end;
        final String path;

        Media(int pos, int end, String path) {
            this.pos = pos;
            this.end = end;
            this.path = path;
        }
    }

    /**
     * Read a <b>path</b> field at {@code i} if one starts there:
     * {@code 1a [total:u8] 00 00 00 [sub:u8] <ascii>}, the ASCII value being {@code total-6} bytes.
     * Pure tag → length → value; the value must be printable and carry {@code prefix}.
     */
    private static Tlv readPathField(byte[] bytes, int i, int sub, String prefix) {
        if (i < 0 || i + 6 > bytes.length || bytes[i] != 0x1A) {
            return null;
        }
        if (bytes[i + 2] != 0 || bytes[i + 3] != 0 || bytes[i + 4] != 0) {
            return null;
        }
        if ((bytes[i + 5] & 0xFF) != sub) {
            return null;
        }
        int slen = (bytes[i + 1] & 0xFF) - 6;
        if (slen < prefix.length() || i + 6 + slen > bytes.length) {
            return null;
        }
        String s = new String(bytes, i + 6, slen, LATIN1);
        if (!s.startsWith(prefix)) {
            return null;
        }
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c < 0x20 || c > 0x7E) {
                return null;
            }
        }
        return new Tlv(s, i + 6 + slen);
    }

    private static List<MediaFile> decodeComposite(byte[] bytes, String store) {
        // Locate every media-path field (one per record). The 6-byte `1a … 00 00 00 01` signature plus
        // the `DCIM/` prefix makes a false hit in binary essentially impossible.
        List<Media> medias = new ArrayList<Media>();
        int i = 0;
        while (i < bytes.length) {
            Tlv f = readPathField(bytes, i, 1, "DCIM/");
            if (f != null) {
                medias.add(new Media(i, f.end, f.value));
                i = f.end;
            } else {
                i++;
            }
        }
        if (medias.isEmpty()) {
            return Collections.emptyList();
        }

        int boundary = listBoundary(bytes, medias.size());
        LinkedHashMap<String, MediaFile> byPath = new LinkedHashMap<String, MediaFile>();
        for (int k = 0; k < medias.size(); k++) {
            Media m = medias.get(k);
            int lo = k > 0 ? medias.get(k - 1).end : 0;
            int hi = k + 1 < medias.size() ? medias.get(k + 1).pos : bytes.length;
            int group = boundary > 0 && k >= boundary ? 1 : 0;
            if (!byPath.containsKey(m.path)) { // dedup by media path (a paged list can repeat)
                byPath.put(m.path, resolveRecord(bytes, m.path, lo, hi, m.pos, group));
            }
        }
        return withCmdHandles(new ArrayList<MediaFile>(byPath.values()), store);
    }

    /**
     * Index of the first record of the <em>second</em> per-storage list, or -1 when there is one list.
     *
     * <p>With a card in, the camera concatenates two lists — SD first, then internal — each opening
     * with its own {@code [u32-LE count][u32-LE size][u32-LE ts]…} header, so the leading count covers
     * only the first list.
     */
    private static int listBoundary(byte[] bytes, int records) {
        if (bytes.length < 4) {
            return -1;
        }
        long declared = u32le(bytes, 0);
        return declared >= 1 && declared < records ? (int) declared : -1;
    }

    /**
     * Build one {@link MediaFile} from its media-path field. The base name is the last path
     * component; the thumbnail ({@code 1a…02}) and the extension ({@code 0d}) are found by walking
     * the record window {@code [lo,hi)} and matching <em>this</em> base — field order varies across
     * the line (name before path on the Nano, after it on the Xtra), so association is by trailing
     * base, not by position.
     */
    private static MediaFile resolveRecord(byte[] bytes, String mediaDir, int lo, int hi,
                                           int selfPos, int group) {
        int slash = mediaDir.lastIndexOf('/');
        String base = slash >= 0 ? mediaDir.substring(slash + 1) : mediaDir;

        // Thumbnail path: the 1a…02 field whose value ends with this base.
        String thumb = null;
        for (int t = lo; t < hi && t < bytes.length; t++) {
            Tlv f = readPathField(bytes, t, 2, "MISC/");
            if (f != null && f.value.endsWith(base)) {
                thumb = f.value;
                break;
            }
        }

        // Extension: the 0d filename field `<base>.<ext>` — read by its length, matched by base.
        String ext = "";
        String proxyExt = null;
        for (int n = lo; n < hi - 2 && n + 1 < bytes.length; n++) {
            if (bytes[n] != 0x0D) {
                continue;
            }
            int len = bytes[n + 1] & 0xFF;
            if (len > base.length() && n + 2 + len <= bytes.length) {
                String v = new String(bytes, n + 2, len, LATIN1);
                if (v.length() > base.length() + 1 && v.startsWith(base)
                        && v.charAt(base.length()) == '.') {
                    String e = v.substring(base.length() + 1).toUpperCase(Locale.US);
                    if (VIDEO_EXTS.contains(e) || STILL_EXTS.contains(e)) {
                        ext = e;
                    } else if (PROXY_EXTS.contains(e)) {
                        proxyExt = e;
                    }
                }
            }
        }

        // Every record — photo as well as video — hangs its handle off a marker at head+8. The
        // marker's first byte is the media type, its second the star flag, so the four bytes run
        // `[03|00] [ff|fe] 19 06`. The scan stops at this record's OWN media path, never at the
        // next record's: scanning too far hands a photo a neighbouring video's handle, and
        // `0x00/0x28` addresses by handle, so that deletes the wrong file.
        int head = -1;
        int markerEnd = selfPos >= lo + 1 && selfPos <= hi ? selfPos : hi;
        if (markerEnd > bytes.length) {
            markerEnd = bytes.length;
        }
        for (int m = Math.max(0, lo); m < markerEnd - 4; m++) {
            int kind = bytes[m] & 0xFF;
            int star = bytes[m + 1] & 0xFF;
            if ((kind == 0x03 || kind == 0x00) && (star == 0xFF || star == 0xFE)
                    && bytes[m + 2] == 0x19 && bytes[m + 3] == 0x06 && m >= 8) {
                head = m - 8;
                break;
            }
        }
        boolean hasMarker = head >= 0;
        boolean isVideo = VIDEO_EXTS.contains(ext);

        // Photos: their size and pixel dimensions hang off their own `19 06` tag, which sits at a
        // FIXED position — seven bytes before the record's own path field — so read it there rather
        // than scanning: the guard byte before the tag is `ff`/`fe` on some bodies but `f6`/`c7` on a
        // Pocket 3, and a failed scan overruns into the next record.
        long photoSize = 0L;
        String photoRes = null;
        if (!isVideo) {
            int fixed = selfPos - 7;
            if (selfPos >= 21 && fixed + 1 < bytes.length
                    && bytes[fixed] == 0x19 && bytes[fixed + 1] == 0x06) {
                photoSize = u32le(bytes, fixed - 14);
                if (base.startsWith("DJI_") && fixed + 66 <= bytes.length) {
                    int w = (int) u32le(bytes, fixed + 58);
                    int h = (int) u32le(bytes, fixed + 62);
                    if (w >= 1 && w <= 60000 && h >= 1 && h <= 60000) {
                        photoRes = w + "x" + h;
                    }
                }
            }
            for (int q = Math.max(0, lo); photoSize == 0L && q < markerEnd - 3; q++) {
                // NB the guard byte must be masked: a Java byte 0xFF promotes to -1, never to 255.
                if (((bytes[q] & 0xFF) == 0xFF || (bytes[q] & 0xFF) == 0xFE)
                        && bytes[q + 1] == 0x19 && bytes[q + 2] == 0x06) {
                    int mk = q + 1;                       // index of the 19 06 pair
                    if (mk >= 14) {
                        photoSize = u32le(bytes, mk - 14); // size is pre-marker in both layouts
                    }
                    // Pixel W×H sit AFTER the marker, only in the DJI-proper (Nano) layout. The CAM_
                    // family (Xtra/Action) put the path there, so leave it null.
                    if (base.startsWith("DJI_") && mk + 66 <= bytes.length) {
                        int w = (int) u32le(bytes, mk + 58);
                        int h = (int) u32le(bytes, mk + 62);
                        if (w >= 1 && w <= 60000 && h >= 1 && h <= 60000) {
                            photoRes = w + "x" + h;
                        }
                    }
                    break;
                }
            }
        }

        String path = ext.length() > 0 ? mediaDir + "." + ext : mediaDir;
        String thumbPath = (thumb != null ? thumb : defaultThumb(mediaDir)) + ".scr";
        long handle = hasMarker ? u32le(bytes, head) : 0L;
        // Media byte size = u32-LE at head-4 (marker-12) for video; photos read it off their own tag.
        long size = isVideo && hasMarker && head >= 4 ? u32le(bytes, head - 4) : photoSize;
        // Bounded at the NEXT record's head: a clip whose neighbour was shot at a different frame
        // rate would otherwise report the neighbour's rate.
        Integer fps = isVideo && hasMarker
                ? fpsInRange(bytes, head, nextHead(bytes, head, hi)) : null;
        // Video duration in whole seconds = u16-LE at head+4.
        int durationSec = isVideo && hasMarker && head + 6 <= bytes.length
                ? ((bytes[head + 4] & 0xFF) | ((bytes[head + 5] & 0xFF) << 8)) : 0;
        // Prefer the signature read where the record has one: it is the only thing that works on a
        // body whose stills carry no marker, and it agreed with the marker read everywhere both fired.
        Boolean bySignature = starFlagBySignatureOrNull(bytes, selfPos >= 0 ? selfPos : lo, hi);
        boolean starred = bySignature != null ? bySignature.booleanValue() : starFlag(bytes, lo, hi);
        int resIndex = isVideo && hasMarker && head + 7 < bytes.length ? bytes[head + 7] & 0xFF : -1;
        String resolution = resIndex >= 0 ? resolutionForIndex(resIndex) : photoRes;
        // Media type: the byte before the constant `19 06` tag, which sits at mediaPath-13. Read only
        // when that tag is actually there, so a differently shaped record yields TYPE_NONE.
        boolean tagAtPath = selfPos >= 9 && selfPos <= bytes.length
                && bytes[selfPos - 7] == 0x19 && bytes[selfPos - 6] == 0x06;
        int mediaType = tagAtPath ? bytes[selfPos - 9] & 0xFF : MediaFile.TYPE_NONE;
        // The handle at the tag's FIXED position — u32-LE at (19 06) − 10. Untrusted here:
        // withCmdHandles promotes it only where the independent base+step fit agrees.
        long handleCandidate = tagAtPath && selfPos >= 17 ? u32le(bytes, selfPos - 17) : 0L;

        return new MediaFile.Builder()
                .path(path)
                .thumbPath(thumbPath)
                .sizeBytes(size)
                .resLabel(fps != null ? fps + "fps" : null)
                .resolution(resolution)
                .handle(handle)
                .cmdHandle(0L)
                .handleCandidate(handleCandidate)
                .starred(starred)
                .durationSec(durationSec)
                .mediaType(mediaType)
                .group(group)
                .storage(MediaFile.STORAGE_UNKNOWN, false)
                .build();
    }

    private static String defaultThumb(String mediaDir) {
        return mediaDir.startsWith("DCIM/") ? "MISC/THM/" + mediaDir.substring("DCIM/".length())
                : mediaDir;
    }

    /**
     * Fill {@code cmdHandle} by fitting {@code handle = base + seq*step} <b>per storage list</b> to
     * the records that expose a handle, then applying it to every record in that list.
     *
     * <p>Data-driven on purpose: the namespace differs per camera <em>and</em> per store — Nano
     * {@code 0x40100000}/step {@code 0x40}, Xtra SD {@code 0x00040000}/step {@code 0x10}, Xtra
     * internal {@code 0x40040000} — so a hardcoded formula silently produces garbage handles. The
     * step is the most common positive delta over seq-adjacent pairs and the base the most common
     * {@code handle - seq*step}, so a stray record cannot skew the fit; an unfittable list stays 0.
     */
    private static List<MediaFile> withCmdHandles(List<MediaFile> files, String store) {
        Map<Integer, long[]> fits = new LinkedHashMap<Integer, long[]>(); // group -> {base, step}
        Map<Integer, List<MediaFile>> byGroup = new LinkedHashMap<Integer, List<MediaFile>>();
        for (MediaFile f : files) {
            List<MediaFile> list = byGroup.get(f.getGroup());
            if (list == null) {
                list = new ArrayList<MediaFile>();
                byGroup.put(f.getGroup(), list);
            }
            list.add(f);
        }
        for (Map.Entry<Integer, List<MediaFile>> e : byGroup.entrySet()) {
            // seq -> handle, first occurrence wins, ascending by seq.
            LinkedHashMap<Integer, Long> byS = new LinkedHashMap<Integer, Long>();
            for (MediaFile f : e.getValue()) {
                if (f.getHandle() != 0L && f.getSeq() > 0 && !byS.containsKey(f.getSeq())) {
                    byS.put(f.getSeq(), f.getHandle());
                }
            }
            if (byS.size() < 2) {
                continue;
            }
            List<Integer> seqs = new ArrayList<Integer>(byS.keySet());
            Collections.sort(seqs);
            List<Long> steps = new ArrayList<Long>();
            for (int k = 0; k + 1 < seqs.size(); k++) {
                int sa = seqs.get(k);
                int sb = seqs.get(k + 1);
                long delta = (byS.get(sb) - byS.get(sa)) / (sb - sa);
                if (delta > 0) {
                    steps.add(delta);
                }
            }
            if (steps.isEmpty()) {
                continue;
            }
            Long step = modeOf(steps);
            if (step == null) {
                continue;
            }
            List<Long> bases = new ArrayList<Long>(seqs.size());
            for (Integer s : seqs) {
                bases.add(byS.get(s) - s.longValue() * step.longValue());
            }
            Long base = modeOf(bases);
            if (base == null) {
                continue;
            }
            fits.put(e.getKey(), new long[]{base.longValue(), step.longValue()});
        }
        if (fits.isEmpty()) {
            return files;
        }
        List<MediaFile> out = new ArrayList<MediaFile>(files.size());
        for (MediaFile f : files) {
            long[] fit = fits.get(f.getGroup());
            if (fit == null || f.getSeq() <= 0) {
                out.add(f);
                continue;
            }
            long fitted = fit[0] + f.getSeq() * fit[1];
            if (f.getHandle() == 0L && f.getHandleCandidate() != 0L && f.getHandleCandidate() == fitted) {
                // Two independent sources agree: the bytes at the fixed tag position, and a formula
                // fitted to the handles OTHER records exposed. That is the bar for a destructive
                // command, and it is how a Pocket 3's stills become deletable without inventing a thing.
                out.add(copy(f, f.getHandleCandidate(), fitted, f.getStorage(), f.isStorageKnown(),
                        f.isHandleShared()));
            } else if (f.getHandle() != 0L && f.getHandle() != fitted) {
                // The scan produced a handle the fit contradicts — something is being read out of the
                // wrong place. Drop it: the file loses delete (recoverable) rather than deleting
                // whatever else lives at that handle (not recoverable).
                out.add(copy(f, 0L, fitted, f.getStorage(), f.isStorageKnown(), f.isHandleShared()));
            } else {
                out.add(copy(f, f.getHandle(), fitted, f.getStorage(), f.isStorageKnown(),
                        f.isHandleShared()));
            }
        }
        return out;
    }

    /** Shout-free handle-collision guard: a handle shared by two records disables delete for both. */
    private static List<MediaFile> flagHandleCollisions(List<MediaFile> files) {
        Map<Long, Integer> counts = new LinkedHashMap<Long, Integer>();
        for (MediaFile f : files) {
            if (f.getHandle() != 0L) {
                Integer c = counts.get(f.getHandle());
                counts.put(f.getHandle(), c == null ? 1 : c + 1);
            }
        }
        Set<Long> shared = new HashSet<Long>();
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                shared.add(e.getKey());
            }
        }
        if (shared.isEmpty()) {
            return files;
        }
        List<MediaFile> out = new ArrayList<MediaFile>(files.size());
        for (MediaFile f : files) {
            boolean isShared = shared.contains(f.getHandle());
            out.add(copy(f, f.getHandle(), f.getCmdHandle(), f.getStorage(), f.isStorageKnown(),
                    isShared));
        }
        return out;
    }

    /**
     * ⭐ favourite flag as the Xtra / Pocket 3-4 layout writes it: a {@code 00}/{@code 01} byte after
     * a fixed 12-byte signature that sits once per record.
     */
    private static Boolean starFlagBySignatureOrNull(byte[] bytes, int lo, int hi) {
        int end = Math.min(hi, bytes.length - STAR_SIG.length - 1);
        for (int q = Math.max(0, lo); q <= end; q++) {
            int k = 0;
            while (k < STAR_SIG.length && bytes[q + k] == STAR_SIG[k]) {
                k++;
            }
            if (k == STAR_SIG.length) {
                return Boolean.valueOf((bytes[q + STAR_SIG.length] & 0xFF) == 1);
            }
        }
        return null;
    }

    /**
     * ⭐ flag at the byte 9 past the record's {@code [ff|fe] 19 06} marker, 1 when starred.
     *
     * <p>Only trusted when it actually reads as a boolean: on an Xtra the same offset lands on a
     * <em>length</em> byte (44/48, the path length that follows), and reading a length as a flag is
     * how a "starred" badge appears on every file at once.
     */
    private static boolean starFlag(byte[] bytes, int lo, int hi) {
        for (int q = Math.max(0, lo); q < hi - 9 && q + 9 < bytes.length; q++) {
            // NB the guard byte must be masked: a Java byte 0xFF promotes to -1, never to 255.
            if (((bytes[q] & 0xFF) == 0xFF || (bytes[q] & 0xFF) == 0xFE)
                    && bytes[q + 1] == 0x19 && bytes[q + 2] == 0x06) {
                return (bytes[q + 9] & 0xFF) == 1;
            }
        }
        return false;
    }

    // ---- star flag -------------------------------------------------------------------------------

    /**
     * Where the record after the one at {@code head} begins, or {@code hi} if there is no other in
     * range. The marker {@code [00|03][ff|fe] 19 06} sits at {@code head+8}, so the next occurrence
     * marks the next record — the only boundary that holds whichever order a body writes its fields.
     */
    private static int nextHead(byte[] bytes, int head, int hi) {
        int m = head + 9;
        int stop = Math.min(hi, bytes.length) - 4;
        while (m < stop) {
            int kind = bytes[m] & 0xFF;
            int star = bytes[m + 1] & 0xFF;
            if ((kind == 0x03 || kind == 0x00) && (star == 0xFF || star == 0xFE)
                    && bytes[m + 2] == 0x19 && bytes[m + 3] == 0x06) {
                return m - 8;
            }
            m++;
        }
        return hi;
    }

    /**
     * The record encodes fps as a rational num/den ({@code den ∈ {1000,1001}}) shortly before the
     * filename field — 25000/1000 = 25, 30000/1001 = 29.97. Takes the <b>last</b> match in range,
     * which is the one nearest the filename, i.e. this record's own enum block.
     */
    private static Integer fpsInRange(byte[] bytes, int start, int end) {
        Integer fps = null;
        int i = Math.max(0, start);
        int stop = Math.min(end, bytes.length) - 8;
        while (i <= stop) {
            long den = u32le(bytes, i + 4);
            if (den == 1000L || den == 1001L) {
                long num = u32le(bytes, i);
                if (num >= 20000L && num <= 250000L) {
                    fps = Integer.valueOf((int) Math.round((double) num / (double) den));
                }
            }
            i++;
        }
        return fps;
    }

    // ---- storage ---------------------------------------------------------------------------------

    private static List<MediaFile> guessStorage(List<MediaFile> files, boolean singleSdStorage) {
        List<MediaFile> out = new ArrayList<MediaFile>(files.size());
        for (MediaFile f : files) {
            long h = f.getHandle() != 0L ? f.getHandle() : f.getCmdHandle();
            int store;
            if (singleSdStorage) {
                store = MediaFile.STORAGE_SD;
            } else if (h == 0L) {
                store = MediaFile.STORAGE_UNKNOWN;
            } else {
                store = (h & INTERNAL_STORE_BIT) != 0L
                        ? MediaFile.STORAGE_INTERNAL : MediaFile.STORAGE_SD;
            }
            out.add(copy(f, f.getHandle(), f.getCmdHandle(), store, false, f.isHandleShared()));
        }
        return out;
    }

    private static Set<String> pathsOf(List<MediaFile> files) {
        Set<String> s = new HashSet<String>();
        for (MediaFile f : files) {
            s.add(f.getPath());
        }
        return s;
    }

    // ---- small helpers ---------------------------------------------------------------------------

    /** Rebuild a record with selected fields replaced; the rest is copied verbatim. */
    private static MediaFile copy(MediaFile f, long handle, long cmdHandle, int storage,
                                  boolean storageKnown, boolean handleShared) {
        return new MediaFile.Builder()
                .path(f.getPath())
                .thumbPath(f.getThumbPath())
                .sizeBytes(f.getSizeBytes())
                .storage(storage, storageKnown)
                .durationSec(f.getDurationSec())
                .resLabel(f.getResLabel())
                .resolution(f.getResolution())
                .handle(handle)
                .cmdHandle(cmdHandle)
                .handleCandidate(f.getHandleCandidate())
                .starred(f.isStarred())
                .mediaType(f.getMediaType())
                .group(f.getGroup())
                .handleShared(handleShared)
                .build();
    }

    /** The most frequent value, first-encountered winning a tie (the reference's {@code maxByOrNull}). */
    private static <K> K modeOf(List<K> values) {
        LinkedHashMap<K, int[]> counts = new LinkedHashMap<K, int[]>();
        for (K v : values) {
            int[] c = counts.get(v);
            if (c == null) {
                counts.put(v, new int[]{1});
            } else {
                c[0]++;
            }
        }
        K best = null;
        int bestN = -1;
        for (Map.Entry<K, int[]> e : counts.entrySet()) {
            if (e.getValue()[0] > bestN) {
                bestN = e.getValue()[0];
                best = e.getKey();
            }
        }
        return best;
    }

    /** Little-endian u32 at {@code o}; 0 when out of range (callers never rely on that, it is a guard). */
    private static long u32le(byte[] b, int o) {
        if (o < 0 || o + 4 > b.length) {
            return 0L;
        }
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16)
                | ((b[o + 3] & 0xFFL) << 24);
    }

    private static Set<String> unmodifiableSet(String... values) {
        Set<String> s = new HashSet<String>();
        Collections.addAll(s, values);
        return Collections.unmodifiableSet(s);
    }
}
