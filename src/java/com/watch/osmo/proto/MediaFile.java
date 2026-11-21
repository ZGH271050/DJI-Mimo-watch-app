package com.watch.osmo.proto;

/**
 * One media item inside a DJI Osmo camera, decoded from a CompositePack manifest record.
 *
 * <p>Pure Java 8 data holder: no Android API, no third-party dependency. Everything the watch app
 * needs to list, label and (optionally) delete a file is a getter here.
 *
 * <p>Ported from the MIT-licensed reference implementation {@code osmosis}
 * ({@code core/CameraFile.kt}); field meanings and derived-value rules match it so that a byte-level
 * regression shows up as a diff against the captured golden files rather than as a silent UI bug.
 *
 * <p>Instances are immutable. Build one through {@link Builder}; the parser is the only intended
 * producer.
 */
public final class MediaFile {

    /** The camera did not tell us which store serves this path; a probe is required. */
    public static final int STORAGE_UNKNOWN = -1;
    /** Removable card, DJI {@code FileLocation.SD_CARD} — the {@code storage=0} HTTP mount. */
    public static final int STORAGE_SD = 0;
    /** Built-in memory, DJI {@code FileLocation.INTERNAL_STORAGE} — the {@code storage=1} mount. */
    public static final int STORAGE_INTERNAL = 1;

    /** DJI {@code MediaFileType} values the manifest has actually produced. */
    public static final int TYPE_JPEG = 0;
    public static final int TYPE_DNG = 1;
    public static final int TYPE_MOV = 2;
    public static final int TYPE_MP4 = 3;
    public static final int TYPE_PANORAMA = 4;
    /** The record carried no type tag at all (e.g. the Nano / Action 6 layouts). */
    public static final int TYPE_NONE = -1;

    private final String path;
    private final String thumbPath;
    private final String name;
    private final String ext;
    private final long sizeBytes;
    private final int storage;
    private final boolean storageKnown;
    private final boolean video;
    private final boolean image;
    private final String timestamp;
    private final String dateTaken;
    private final String ymd;
    private final int durationSec;
    private final String resLabel;
    private final String resolution;
    private final long handle;
    private final long cmdHandle;
    private final long handleCandidate;
    private final boolean starred;
    private final int mediaType;
    private final int group;
    private final boolean handleShared;
    private final int seq;

    private MediaFile(Builder b) {
        this.path = b.path;
        this.thumbPath = b.thumbPath;
        this.sizeBytes = b.sizeBytes;
        this.storage = b.storage;
        this.storageKnown = b.storageKnown;
        this.durationSec = b.durationSec;
        this.resLabel = b.resLabel;
        this.resolution = b.resolution;
        this.handle = b.handle;
        this.cmdHandle = b.cmdHandle;
        this.handleCandidate = b.handleCandidate;
        this.starred = b.starred;
        this.mediaType = b.mediaType;
        this.group = b.group;
        this.handleShared = b.handleShared;

        int slash = this.path.lastIndexOf('/');
        this.name = slash >= 0 ? this.path.substring(slash + 1) : this.path;
        int dot = this.name.lastIndexOf('.');
        this.ext = dot >= 0 && dot + 1 < this.name.length()
                ? this.name.substring(dot + 1).toUpperCase(java.util.Locale.US)
                : "";
        this.video = isVideoExt(this.ext);
        this.image = isImageExt(this.ext);
        this.timestamp = extractTimestamp(this.name);
        this.dateTaken = formatDateTaken(this.timestamp);
        this.ymd = this.timestamp.length() >= 8 ? this.timestamp.substring(0, 8) : "";
        this.seq = extractSeq(this.name);
    }

    private static boolean isVideoExt(String e) {
        return "MP4".equals(e) || "MOV".equals(e) || "OSV".equals(e) || "INSV".equals(e);
    }

    private static boolean isImageExt(String e) {
        return "JPG".equals(e) || "JPEG".equals(e) || "DNG".equals(e)
                || "HEIC".equals(e) || "RAW".equals(e);
    }

    /** The 14-digit {@code YYYYMMDDhhmmss} run between underscores in a DJI filename, or {@code ""}. */
    private static String extractTimestamp(String name) {
        for (int i = 0; i + 16 <= name.length(); i++) {
            if (name.charAt(i) != '_') {
                continue;
            }
            boolean digits = true;
            for (int k = i + 1; k <= i + 14; k++) {
                if (!Character.isDigit(name.charAt(k))) {
                    digits = false;
                    break;
                }
            }
            if (digits && name.charAt(i + 15) == '_') {
                return name.substring(i + 1, i + 15);
            }
        }
        return "";
    }

    /** The {@code NNNN} in {@code …_NNNN_D.ext} (the grid cell's short number), or 0. */
    private static int extractSeq(String name) {
        for (int i = 0; i + 7 <= name.length(); i++) {
            if (name.charAt(i) != '_') {
                continue;
            }
            boolean digits = true;
            for (int k = i + 1; k <= i + 4; k++) {
                if (!Character.isDigit(name.charAt(k))) {
                    digits = false;
                    break;
                }
            }
            if (digits && name.charAt(i + 5) == '_' && name.charAt(i + 6) == 'D') {
                try {
                    return Integer.parseInt(name.substring(i + 1, i + 5));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String formatDateTaken(String ts) {
        if (ts.length() != 14) {
            return "";
        }
        return ts.substring(0, 4) + "-" + ts.substring(4, 6) + "-" + ts.substring(6, 8)
                + " " + ts.substring(8, 10) + ":" + ts.substring(10, 12);
    }

    // ---- getters ---------------------------------------------------------------------------------

    /** Full in-camera path, e.g. {@code DCIM/DJI_001/DJI_20260714225011_0078_D.MP4}. */
    public String getPath() {
        return path;
    }

    /** Thumbnail path, e.g. {@code MISC/THM/DJI_001/DJI_20260714225011_0078_D.scr}. */
    public String getThumbPath() {
        return thumbPath;
    }

    /** File name including extension — the last path component. */
    public String getName() {
        return name;
    }

    /** Upper-case extension without the dot, or {@code ""}. */
    public String getExt() {
        return ext;
    }

    /** Full media byte size from the manifest record; 0 means unknown (probe over HTTP). */
    public long getSizeBytes() {
        return sizeBytes;
    }

    /** {@link #STORAGE_SD}, {@link #STORAGE_INTERNAL} or {@link #STORAGE_UNKNOWN}. */
    public int getStorage() {
        return storage;
    }

    /**
     * True when {@link #getStorage()} came from the store-specific query that returned this record,
     * so there is nothing left to confirm. False when it is a handle-bit guess.
     */
    public boolean isStorageKnown() {
        return storageKnown;
    }

    /** True for {@code MP4 / MOV / OSV / INSV}. */
    public boolean isVideo() {
        return video;
    }

    /** True for {@code JPG / JPEG / DNG / HEIC / RAW}. */
    public boolean isImage() {
        return image;
    }

    /** The 14-digit filename timestamp ({@code YYYYMMDDhhmmss}), or {@code ""}. */
    public String getTimestamp() {
        return timestamp;
    }

    /** Human date from the filename timestamp, e.g. {@code 2026-07-14 22:50}, or {@code ""}. */
    public String getDateTaken() {
        return dateTaken;
    }

    /** {@code YYYYMMDD} for date grouping, or {@code ""}. */
    public String getYmd() {
        return ymd;
    }

    /** Video length in whole seconds from the record; 0 means unknown. */
    public int getDurationSec() {
        return durationSec;
    }

    /** Frame-rate label from the record, e.g. {@code "60fps"}; null when the record carries none. */
    public String getResLabel() {
        return resLabel;
    }

    /** Pixel size, e.g. {@code "3840x2160"}; null when unknown. */
    public String getResolution() {
        return resolution;
    }

    /** Camera-assigned delete handle (DUML {@code 0x00/0x28}); 0 = not deletable. */
    public long getHandle() {
        return handle;
    }

    /**
     * Handle for the non-destructive per-file commands (favourite / burst group-expand), fitted as
     * {@code base + seq*step} over the handles the manifest does expose. 0 when unfittable.
     */
    public long getCmdHandle() {
        return cmdHandle;
    }

    /** The delete handle read at the record's fixed marker position, before anything vouched for it. */
    public long getHandleCandidate() {
        return handleCandidate;
    }

    /** Favourite (⭐) flag from the manifest. */
    public boolean isStarred() {
        return starred;
    }

    /** DJI {@code MediaFileType} byte for this record, or {@link #TYPE_NONE} when absent. */
    public int getMediaType() {
        return mediaType;
    }

    /** Which per-storage list of the manifest this record came from (0 = first, 1 = second). */
    public int getGroup() {
        return group;
    }

    /** Another record in the same manifest carries this file's {@link #getHandle()}. */
    public boolean isHandleShared() {
        return handleShared;
    }

    /** The short per-file number from the name ({@code …_0078_D.MP4} → 78), or 0. */
    public int getSeq() {
        return seq;
    }

    /** An in-camera-stitched panorama: an ordinary {@code .JPG} whose type byte reads 4. */
    public boolean isPanorama() {
        return mediaType == TYPE_PANORAMA;
    }

    /** The value a delete/favourite command addresses this file by; 0 when it has none. */
    public long getOpHandle() {
        return handle != 0L ? handle : 0L;
    }

    /** True when it has a non-shared handle, so a delete can be issued safely. */
    public boolean isDeletable() {
        return getOpHandle() != 0L && !handleShared;
    }

    @Override
    public String toString() {
        return name + " [" + path + "] size=" + sizeBytes + " storage=" + storage
                + " res=" + resolution + " fps=" + resLabel + " dur=" + durationSec + "s"
                + " starred=" + starred;
    }

    /** Mutable builder; the parser is the only intended caller. */
    public static final class Builder {
        private String path = "";
        private String thumbPath = "";
        private long sizeBytes;
        private int storage = STORAGE_UNKNOWN;
        private boolean storageKnown;
        private int durationSec;
        private String resLabel;
        private String resolution;
        private long handle;
        private long cmdHandle;
        private long handleCandidate;
        private boolean starred;
        private int mediaType = TYPE_NONE;
        private int group;
        private boolean handleShared;

        public Builder path(String v) {
            this.path = v;
            return this;
        }

        public Builder thumbPath(String v) {
            this.thumbPath = v;
            return this;
        }

        public Builder sizeBytes(long v) {
            this.sizeBytes = v;
            return this;
        }

        public Builder storage(int v, boolean known) {
            this.storage = v;
            this.storageKnown = known;
            return this;
        }

        public Builder durationSec(int v) {
            this.durationSec = v;
            return this;
        }

        public Builder resLabel(String v) {
            this.resLabel = v;
            return this;
        }

        public Builder resolution(String v) {
            this.resolution = v;
            return this;
        }

        public Builder handle(long v) {
            this.handle = v;
            return this;
        }

        public Builder cmdHandle(long v) {
            this.cmdHandle = v;
            return this;
        }

        public Builder handleCandidate(long v) {
            this.handleCandidate = v;
            return this;
        }

        public Builder starred(boolean v) {
            this.starred = v;
            return this;
        }

        public Builder mediaType(int v) {
            this.mediaType = v;
            return this;
        }

        public Builder group(int v) {
            this.group = v;
            return this;
        }

        public Builder handleShared(boolean v) {
            this.handleShared = v;
            return this;
        }

        public MediaFile build() {
            return new MediaFile(this);
        }
    }
}
