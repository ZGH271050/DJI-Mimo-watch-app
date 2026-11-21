package com.watch.osmo.util;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnostics channel.
 *
 * <p>This app is developed without the watch on the bench, so every run has to be reconstructable
 * from what the device can hand back. Everything goes to logcat <em>and</em> to a rotating file in
 * the app directory, and the last lines are kept in memory so the UI can show them without
 * reading the file. The user can then hit "导出日志" and send the file back.
 */
public final class Diag {

    private static final String TAG = "OsmoWatch";
    private static final int RING = 400;
    /**
     * One whole camera session with wire traces is ~200-400 KB, so 2 MB holds several of them. It used
     * to be 4 MB and there are two sinks plus a {@code .prev} for each, i.e. up to 16 MB of log on a
     * storage-constrained watch; 2 MB keeps the diagnostic value and halves that.
     */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    /** A {@code .prev} older than this is not going to be read again. */
    private static final long STALE_ROTATION_MS = 14L * 24 * 3600 * 1000;

    private static final ArrayDeque<String> RING_BUFFER = new ArrayDeque<String>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static File logFile;
    /**
     * A second copy in {@code getExternalFilesDir()}, i.e. under {@code /sdcard/Android/data/<pkg>/}.
     * That path is readable with plain adb, so the log survives the session even when the watch never
     * comes back to the same Wi-Fi network: pull it later, no root and no in-app export needed.
     */
    private static File mirrorFile;
    private static final Object LOCK = new Object();

    private Diag() {
    }

    /** Points the file sink at the app's own directory. Safe to call more than once. */
    public static void init(File dir) {
        init(dir, null);
    }

    /** {@link #init(File)} plus a mirror sink that plain adb can read. */
    public static void init(File dir, File mirror) {
        synchronized (LOCK) {
            logFile = openSink(dir, "osmo.log");
            mirrorFile = openSink(mirror, "osmo-live.log");
        }
    }

    private static File openSink(File dir, String name) {
        if (dir == null) {
            return null;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File f = new File(dir, name);
        File old = new File(dir, name + ".prev");
        if (old.exists()
                && (old.length() > MAX_FILE_BYTES
                || System.currentTimeMillis() - old.lastModified() > STALE_ROTATION_MS)) {
            old.delete();
        }
        if (f.length() > MAX_FILE_BYTES) {
            if (old.exists()) {
                old.delete();
            }
            f.renameTo(old);
        }
        return f;
    }

    public static File file() {
        return logFile;
    }

    /**
     * Delete both log files and their {@code .prev} rotations, and empty the in-memory ring so the log
     * page shows the wipe instead of replaying lines it just deleted. The next write recreates both
     * files, so this is safe to call at any time.
     *
     * @return bytes freed
     */
    public static long clearAll() {
        long freed = 0L;
        synchronized (LOCK) {
            freed += deletePair(logFile);
            freed += deletePair(mirrorFile);
            RING_BUFFER.clear();
        }
        i("日志已清空（手动清理）");
        return freed;
    }

    private static long deletePair(File f) {
        if (f == null) {
            return 0L;
        }
        long freed = 0L;
        File parent = f.getParentFile();
        if (parent != null) {
            File prev = new File(parent, f.getName() + ".prev");
            if (prev.exists()) {
                freed += prev.length();
                prev.delete();
            }
        }
        if (f.exists()) {
            freed += f.length();
            f.delete();
        }
        return freed;
    }

    /**
     * One-line summary of what the log sinks occupy, so "why is the app bigger than its APK" is
     * answerable from logcat instead of guessed at: {@code 内部 1234+567 KB / 外部 890 KB}.
     */
    public static String usage() {
        return "日志占用：内部 " + describe(logFile) + " / 外部 " + describe(mirrorFile);
    }

    private static String describe(File f) {
        if (f == null) {
            return "未启用";
        }
        long live = f.length();
        File prev = new File(f.getParentFile(), f.getName() + ".prev");
        long old = prev.exists() ? prev.length() : 0;
        return (live / 1024) + "+" + (old / 1024) + " KB";
    }

    public static void i(String message) {
        write("I", message, null);
    }

    public static void w(String message) {
        write("W", message, null);
    }

    public static void e(String message) {
        write("E", message, null);
    }

    public static void e(String message, Throwable t) {
        write("E", message, t);
    }

    private static void write(String level, String message, Throwable t) {
        String line = TIME.format(new Date()) + " " + level + " " + message;
        if ("E".equals(level)) {
            Log.e(TAG, message, t);
        } else if ("W".equals(level)) {
            Log.w(TAG, message);
        } else {
            Log.i(TAG, message);
        }
        synchronized (LOCK) {
            RING_BUFFER.addLast(line);
            while (RING_BUFFER.size() > RING) {
                RING_BUFFER.removeFirst();
            }
            if (logFile != null) {
                append(logFile, line, t);
            }
            if (mirrorFile != null) {
                append(mirrorFile, line, t);
            }
        }
    }

    private static void append(File to, String line, Throwable t) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(to, true);
            out.write((line + "\n").getBytes("UTF-8"));
            if (t != null) {
                out.write(("    " + t + "\n").getBytes("UTF-8"));
            }
        } catch (IOException ignored) {
            // Never let logging break the transfer it is describing.
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Nothing useful to do.
                }
            }
        }
    }

    /** Last {@code max} log lines, oldest first. */
    public static String tail(int max) {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            int skip = Math.max(0, RING_BUFFER.size() - max);
            int i = 0;
            for (String line : RING_BUFFER) {
                if (i++ < skip) {
                    continue;
                }
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
