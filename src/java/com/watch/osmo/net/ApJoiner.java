package com.watch.osmo.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Handler;
import android.os.Looper;

/**
 * Joins the camera's Wi-Fi access point and binds the whole process to it.
 *
 * <p>Two things make this harder than a normal Wi-Fi connect:
 * <ul>
 *   <li>the camera AP has <b>no internet</b>, so the request must explicitly drop
 *       NET_CAPABILITY_INTERNET or Android will refuse to consider it a match;</li>
 *   <li>unless the process is bound with {@link ConnectivityManager#bindProcessToNetwork}, the
 *       watch keeps routing sockets over its own Wi-Fi / Bluetooth proxy and every request to
 *       192.168.2.1 fails even though the join "succeeded".</li>
 * </ul>
 *
 * <p>A specifier request also does not reconnect by itself: when the camera's AP drops, the process
 * stays bound to a dead network and every socket fails. {@link #rejoin()} re-issues the identical
 * request.
 *
 * <p><b>One request at a time, and always with a deadline.</b> Two live specifier requests for the
 * same SSID cancel each other — the 2026-11 logs show four concurrent joins and the framework
 * answering {@code onUnavailable} within 50 ms. And a request that never gets an answer used to hang
 * the whole session, because {@code onUnavailable} is not guaranteed when the AP briefly advertises
 * and then disappears; every request now owns a {@link #JOIN_TIMEOUT_MS} deadline.
 */
public class ApJoiner {

    public interface Listener {
        void onLog(String message);
        void onNetwork(Network network, LinkProperties link);
        /** The join itself failed; no network was ever handed to us. */
        void onFailure(String reason);
        /** The AP went away after a successful join; the caller decides whether to retry. */
        void onApLost();
    }

    private final ConnectivityManager cm;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** How many times a join that never got a network is re-asked before it is called a failure. */
    private static final int JOIN_ATTEMPTS = 2;
    private static final long JOIN_RETRY_DELAY_MS = 1500L;
    /** The camera's AP takes up to ~15 s to appear after the BLE wake; past this it is not coming. */
    private static final long JOIN_TIMEOUT_MS = 20000L;

    private ConnectivityManager.NetworkCallback callback;
    private String lastSsid;
    private String lastPassphrase = "";
    private boolean lastWpa3;
    private int attempts;
    private boolean released;
    private Runnable timeout;

    public ApJoiner(Context context, Listener listener) {
        this.cm = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
    }

    public void join(String ssid, String passphrase, boolean wpa3) {
        lastSsid = ssid;
        lastPassphrase = passphrase == null ? "" : passphrase;
        lastWpa3 = wpa3;
        attempts = 0;
        released = false;
        request();
    }

    private void request() {
        if (released) {
            return;
        }
        // Never leave a previous request registered: two specifier requests for one SSID make the
        // framework fail both.
        releaseCallback();
        attempts++;
        WifiNetworkSpecifier.Builder spec = new WifiNetworkSpecifier.Builder().setSsid(lastSsid);
        if (lastPassphrase.length() > 0) {
            // The Osmo 360 AP is WPA3-SAE; every other Osmo is WPA2-PSK.
            if (lastWpa3) {
                spec.setWpa3Passphrase(lastPassphrase);
            } else {
                spec.setWpa2Passphrase(lastPassphrase);
            }
        }
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec.build())
                .build();

        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (released) {
                    return;
                }
                cancelTimeout();
                boolean bound = cm.bindProcessToNetwork(network);
                LinkProperties link = null;
                try {
                    link = cm.getLinkProperties(network);
                } catch (RuntimeException ignored) {
                    // Link properties are a nicety, not a requirement.
                }
                listener.onLog("WiFi 已连接，bindProcessToNetwork=" + bound
                        + (link == null ? "" : "  " + link));
                listener.onNetwork(network, link);
            }

            @Override
            public void onUnavailable() {
                if (released) {
                    return;
                }
                cancelTimeout();
                unavailable(null);
            }

            @Override
            public void onLost(Network network) {
                if (released) {
                    return;
                }
                listener.onLog("WiFi 掉线了");
                listener.onApLost();
            }
        };
        callback = cb;
        listener.onLog("正在加入热点 \"" + lastSsid + "\"（" + (lastWpa3 ? "WPA3" : "WPA2")
                + "，无互联网，第 " + attempts + "/" + JOIN_ATTEMPTS + " 次）…");
        try {
            cm.requestNetwork(request, cb);
        } catch (SecurityException e) {
            callback = null;
            listener.onFailure("没有 Wi-Fi 权限：" + e.getMessage());
            return;
        } catch (RuntimeException e) {
            callback = null;
            listener.onFailure("发起 Wi-Fi 请求失败：" + e);
            return;
        }
        armTimeout();
    }

    /**
     * No network yet. Either ask again (once) or give up. {@code reason} is null when the framework
     * itself answered {@link ConnectivityManager.NetworkCallback#onUnavailable()}.
     */
    private void unavailable(String reason) {
        releaseCallback();
        if (attempts < JOIN_ATTEMPTS) {
            listener.onLog("WiFi 第 " + attempts + " 次没连上"
                    + (reason == null ? "" : "（" + reason + "）")
                    + "，" + (JOIN_RETRY_DELAY_MS / 1000) + " 秒后重试");
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!released && lastSsid != null) {
                        request();
                    }
                }
            }, JOIN_RETRY_DELAY_MS);
            return;
        }
        listener.onFailure("WiFi 连不上：密码不对、相机热点没起来，或你在系统弹窗里点了取消"
                + (reason == null ? "" : "（" + reason + "）"));
    }

    private void armTimeout() {
        cancelTimeout();
        timeout = new Runnable() {
            @Override
            public void run() {
                if (!released) {
                    unavailable(JOIN_TIMEOUT_MS / 1000 + " 秒没有连上");
                }
            }
        };
        main.postDelayed(timeout, JOIN_TIMEOUT_MS);
    }

    private void cancelTimeout() {
        if (timeout != null) {
            main.removeCallbacks(timeout);
            timeout = null;
        }
    }

    private void releaseCallback() {
        cancelTimeout();
        ConnectivityManager.NetworkCallback cb = callback;
        callback = null;
        if (cb != null) {
            try {
                cm.unregisterNetworkCallback(cb);
            } catch (RuntimeException ignored) {
                // Already unregistered.
            }
        }
    }

    /** Re-issue the last {@link #join}. Returns false when nothing was ever requested. */
    public boolean rejoin() {
        String ssid = lastSsid;
        if (ssid == null || released) {
            return false;
        }
        releaseCallback();
        request();
        return true;
    }

    /** True when a join is currently outstanding. */
    public boolean isJoining() {
        return callback != null;
    }

    /** Hand the network back and make every later callback inert. Idempotent. */
    public void release() {
        released = true;
        releaseCallback();
        try {
            cm.bindProcessToNetwork(null);
        } catch (RuntimeException ignored) {
            // Nothing bound.
        }
    }
}
