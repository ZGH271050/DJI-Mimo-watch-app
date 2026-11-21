package com.watch.osmo.core;

/**
 * One camera discovered over BLE advertising.
 *
 * <p>Immutable on purpose: the scan callback fires repeatedly and the UI list is rebuilt from
 * snapshots, so nothing downstream should be able to mutate an entry that is already displayed.
 */
public final class CameraInfo {

    /** Advertised Bluetooth name, e.g. {@code OsmoPocket4P-6E55}. */
    public final String name;
    /** Bluetooth MAC address, used to open the GATT connection. */
    public final String address;
    /** Numeric DJI model id, e.g. 0x22 for the Osmo Pocket 4P. */
    public final int modelId;
    /** Human readable model name resolved from {@link #modelId}. */
    public final String modelName;
    /** Signal strength in dBm; more negative is weaker. */
    public final int rssi;

    public CameraInfo(String name, String address, int modelId, String modelName, int rssi) {
        this.name = name;
        this.address = address;
        this.modelId = modelId;
        this.modelName = modelName;
        this.rssi = rssi;
    }

    /** True when this camera is an Osmo Pocket 4 / 4P, the model this app is built for. */
    public boolean isPocket4() {
        return modelId == 0x21 || modelId == 0x22;
    }

    /** Reachability bucket, so the list can be sorted without leaking raw dBm to the UI. */
    public int signalLevel() {
        if (rssi >= -60) {
            return 3;
        }
        if (rssi >= -75) {
            return 2;
        }
        return 1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CameraInfo)) {
            return false;
        }
        CameraInfo other = (CameraInfo) o;
        return address != null && address.equals(other.address);
    }

    @Override
    public int hashCode() {
        return address == null ? 0 : address.hashCode();
    }

    @Override
    public String toString() {
        return modelName + "[" + name + " " + address + " rssi=" + rssi + "]";
    }
}
