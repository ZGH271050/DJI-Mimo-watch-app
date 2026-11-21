package com.watch.osmo.core;

/** Live camera state shown in the status pill: model, battery and how much room is left. */
public final class CameraStatus {

    public final String modelName;
    /** Battery percentage, or -1 when the camera did not report one. */
    public final int batteryPercent;
    /** Free bytes on whichever store the manifest was read from, or -1 when unknown. */
    public final long freeBytes;
    /** Total bytes of that store, or -1 when unknown. */
    public final long totalBytes;
    /** True when the manifest came from the SD card (storage id 1). */
    public final boolean external;

    public CameraStatus(String modelName, int batteryPercent,
                        long freeBytes, long totalBytes, boolean external) {
        this.modelName = modelName;
        this.batteryPercent = batteryPercent;
        this.freeBytes = freeBytes;
        this.totalBytes = totalBytes;
        this.external = external;
    }

    public static CameraStatus unknown() {
        return new CameraStatus("相机", -1, -1, -1, false);
    }

    public boolean hasBattery() {
        return batteryPercent >= 0;
    }

    public boolean hasStorage() {
        return totalBytes > 0;
    }

    /** Rough "there is no point starting a transfer" test, with a safety reserve. */
    public boolean tooFullFor(long needed, long reserveBytes) {
        return freeBytes >= 0 && needed + reserveBytes > freeBytes;
    }
}
