package com.watch.osmo.store;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Moves a finished download into the watch's gallery.
 *
 * <p>On API 29+ the only supported way to publish media is MediaStore with a RELATIVE_PATH; there
 * is no WRITE_EXTERNAL_STORAGE to ask for. If MediaStore refuses for any reason the file is copied
 * into the app's own external directory instead, because losing a download that already crossed
 * the network is the worst possible outcome.
 */
public final class FileSaver {

    private static final String TAG = "OsmoWatch";
    public static final String ALBUM = "OsmoWatch";

    private FileSaver() {
    }

    /** Bytes free on the volume the app writes to. -1 when it cannot be determined. */
    /**
     * Delete everything in the app cache — in practice the {@code .part} files of interrupted
     * downloads. This is the manual counterpart of {@link #sweepCache(Context)}: the user asked for a
     * "clean cache and logs" option, and this is the honest implementation of the cache half.
     *
     * @return bytes freed
     */
    public static long clearCache(Context context) {
        File dir;
        try {
            dir = context.getCacheDir();
        } catch (RuntimeException e) {
            return 0L;
        }
        return dir == null ? 0L : deleteContents(dir);
    }

    private static long deleteContents(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0L;
        }
        long freed = 0L;
        for (File f : files) {
            if (f == null) {
                continue;
            }
            if (f.isDirectory()) {
                freed += deleteContents(f);
                f.delete();
            } else {
                long size = f.length();
                if (f.delete()) {
                    freed += size;
                }
            }
        }
        return freed;
    }

    /**
     * Delete half-written downloads that were abandoned. A {@code .part} exists so an interrupted
     * transfer can resume on the next attempt, but one that is a week old is almost certainly never
     * going to be resumed, and each one is as large as the video it was downloading. Without this the
     * app cache grows by one video per failed download and never shrinks.
     *
     * @return bytes freed
     */
    public static long sweepCache(Context context) {
        File dir;
        try {
            dir = context.getCacheDir();
        } catch (RuntimeException e) {
            return 0L;
        }
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return 0L;
        }
        long cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        long freed = 0L;
        for (File f : files) {
            if (f == null || !f.getName().endsWith(".part")) {
                continue;
            }
            if (f.lastModified() < cutoff) {
                long size = f.length();
                if (f.delete()) {
                    freed += size;
                }
            }
        }
        return freed;
    }

    public static long freeBytes(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) {
                dir = context.getFilesDir();
            }
            StatFs stat = new StatFs(dir.getAbsolutePath());
            return stat.getAvailableBytes();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Copies {@code src} into DCIM/OsmoWatch (or the app directory) and returns where it went. */
    public static String publish(Context context, File src, String displayName, boolean video) {
        if (src == null || !src.exists()) {
            return null;
        }
        String viaMediaStore = publishViaMediaStore(context, src, displayName, video);
        if (viaMediaStore != null) {
            return viaMediaStore;
        }
        return publishToAppDir(context, src, displayName);
    }

    private static String publishViaMediaStore(Context context, File src, String displayName,
                                               boolean video) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        Uri collection = video
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + ALBUM);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        Uri item = null;
        OutputStream out = null;
        InputStream in = null;
        try {
            item = resolver.insert(collection, values);
            if (item == null) {
                return null;
            }
            out = resolver.openOutputStream(item);
            if (out == null) {
                resolver.delete(item, null, null);
                return null;
            }
            in = new FileInputStream(src);
            copy(in, out);
            out.flush();
            out.close();
            out = null;

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(item, done, null, null);
            return "相册/DCIM/" + ALBUM + "/" + displayName;
        } catch (IOException e) {
            Log.w(TAG, "MediaStore publish failed: " + e);
            if (item != null) {
                try {
                    resolver.delete(item, null, null);
                } catch (RuntimeException ignored) {
                    // Nothing else to try.
                }
            }
            return null;
        } catch (RuntimeException e) {
            Log.w(TAG, "MediaStore unavailable: " + e);
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static String publishToAppDir(Context context, File src, String displayName) {
        File dir = new File(context.getExternalFilesDir(null), ALBUM);
        if (!dir.exists() && !dir.mkdirs()) {
            dir = new File(context.getFilesDir(), ALBUM);
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
        }
        File dest = new File(dir, displayName);
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dest);
            copy(in, out);
            return dest.getAbsolutePath();
        } catch (IOException e) {
            Log.w(TAG, "fallback publish failed: " + e);
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // Nothing useful to do.
            }
        }
    }
}
