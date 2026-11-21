package com.watch.osmo.ui;

import com.watch.osmo.core.CameraInfo;
import com.watch.osmo.proto.MediaFile;

import java.util.List;

/**
 * Everything the screens are allowed to ask of the business layer.
 *
 * <p>The three screens are deliberately ignorant of Bluetooth, Wi-Fi and HTTP: they render state and
 * report taps through this interface only. That keeps every screen compilable and testable without a
 * camera, and it means the transport can be replaced without touching a single layout.
 *
 * <p>All methods are called from the main thread. Implementations must not block: the round-screen
 * layouts repaint on every state change and a stalled call would freeze the only UI thread there is.
 */
public interface Controller {

    /** Starts or stops the BLE scan for nearby cameras. */
    void scan(boolean start);

    /** Pairs with and connects to one camera, then brings up its Wi-Fi link. */
    void connect(CameraInfo camera);

    /** Drops the current camera, its link and any in-flight work. */
    void disconnect();

    /** Re-reads the media manifest of the connected camera. */
    void refreshMedia();

    /** Queues the given files for download to the watch. */
    void download(List<MediaFile> files);

    /** Aborts the running transfer. Already-written files are left in place. */
    void cancelTransfers();

    /** Snapshot of the cameras found by the current or last scan. Never null. */
    List<CameraInfo> cameras();

    /** Snapshot of the media list of the connected camera. Never null. */
    List<MediaFile> media();

    /** Last {@code lines} diagnostic lines, oldest first. */
    String logTail(int lines);
}
