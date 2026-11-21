package com.watch.osmo.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.watch.osmo.ui.MainActivity;
import com.watch.osmo.util.Diag;

/**
 * Keeps a long transfer alive once the watch screen goes away.
 *
 * <p>The panel on this watch powers itself down on its own schedule, so a transfer must not depend
 * on the activity staying resumed. This service holds a partial wake lock, shows progress in a
 * notification, and is started/stopped by the UI when a transfer begins and ends. The transfer
 * logic itself lives in {@link com.watch.osmo.core.OsmoClient}; this only keeps the process
 * eligible to run.
 */
public class TransferService extends Service {

    private static final String CHANNEL_ID = "osmo_transfer";
    private static final int NOTIFICATION_ID = 0x05A0;

    private PowerManager.WakeLock wakeLock;
    private NotificationManager notificationManager;

    // ---------------------------------------------------------------- static control surface

    public static void start(Context context) {
        Intent intent = new Intent(context, TransferService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException e) {
            Diag.w("TransferService start failed: " + e);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, TransferService.class));
        } catch (RuntimeException e) {
            Diag.w("TransferService stop failed: " + e);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "传输",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "osmo:transfer");
            wakeLock.setReferenceCounted(false);
            try {
                wakeLock.acquire(60 * 60 * 1000L);
            } catch (RuntimeException e) {
                Diag.w("wake lock refused: " + e);
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification("准备传输…", 0, false));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException ignored) {
                // Nothing useful to do.
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- notification

    /** Updates the ongoing notification. Called on the main thread by the UI. */
    public void update(String text, int percent, boolean done) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(text, percent, done));
        }
    }

    private Notification buildNotification(String text, int percent, boolean done) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("大疆传片");
        builder.setContentText(text);
        builder.setSmallIcon(android.R.drawable.stat_sys_download);
        builder.setOngoing(!done);
        builder.setOnlyAlertOnce(true);
        if (!done && percent > 0) {
            builder.setProgress(100, Math.min(100, percent), false);
        } else if (!done) {
            builder.setProgress(100, 0, true);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        builder.setContentIntent(pi);
        return builder.build();
    }
}
