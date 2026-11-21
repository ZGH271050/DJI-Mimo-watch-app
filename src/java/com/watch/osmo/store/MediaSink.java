package com.watch.osmo.store;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.MediaStore;

import com.watch.osmo.net.Downloader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;

/**
 * Where a downloaded camera file lands on the watch.
 *
 * <p>Two destinations, tried in that order, and <b>the second one is not optional</b> — a MediaStore
 * failure must never lose a file the watch already pulled off the camera over a slow Wi-Fi link:
 *
 * <ol>
 *   <li><b>MediaStore</b> ({@code MediaStore.Video} / {@code MediaStore.Images}) under
 *       {@code RELATIVE_PATH = DCIM/OsmoWatch}, inserted with {@code IS_PENDING=1} and published with
 *       {@code IS_PENDING=0} once the transfer finishes. Needs API 29+ ({@code Q}), which is this
 *       app's minSdk.</li>
 *   <li><b>App-private directory</b>, {@code getExternalFilesDir(null)/OsmoWatch} (falling back to
 *       {@code getFilesDir()} when there is no external volume). No permission needed, always
 *       writable, and it still shows up in a file browser under {@code Android/data/com.watch.osmo}.
 *       </li>
 * </ol>
 *
 * <p>{@link Target} implements {@link Downloader.Sink}, so the queue can reopen it at an arbitrary
 * offset and resume. Everything here is Android API, except the naming/space rules — those are
 * static, side-effect free, and covered by the desktop self-test (see {@code test/HttpSelfTest.java}).
 */
public final class MediaSink {

    /** Gallery folder, API 29+ form (relative to the primary shared storage). */
    public static final String RELATIVE_PATH = "DCIM/OsmoWatch";

    /** Folder name used inside the app-private external directory. */
    public static final String APP_DIR_NAME = "OsmoWatch";

    /** Headroom kept free so a transfer never fills the volume it is writing to. */
    public static final long DEFAULT_RESERVE_BYTES = 32L * 1024 * 1024;

    /** Display names longer than this are truncated (extension preserved). */
    public static final int MAX_NAME_CHARS = 120;

    /** Characters MediaStore / FAT / exFAT will not tolerate in a file name. */
    private static final String ILLEGAL = "\\/:*?\"<>|";

    private MediaSink() {
    }

    // ------------------------------------------------------- pure naming rules ---

    /**
     * Turn a camera path into a safe {@code DISPLAY_NAME} for the gallery.
     *
     * <p>Strips directories, replaces characters the storage layer rejects, drops trailing dots and
     * spaces (illegal on FAT, and MediaStore quietly mangles them), guarantees a non-empty stem, adds
     * {@code fallbackExt} when the camera path carries no extension, and truncates over-long names
     * while keeping the extension.
     *
     * @param remotePath  e.g. {@code DCIM/DJI_001/DJI_20260329115359_0211_D.MP4}
     * @param fallbackExt extension to add when the name has none, e.g. {@code .jpg}
     * @param video       used only to pick the fallback stem when the name is unusable
     */
    public static String buildDisplayName(String remotePath, String fallbackExt, boolean video) {
        String name = remotePath == null ? "" : remotePath.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = sanitizeName(name);
        if (name.isEmpty()) {
            name = video ? "video" : "photo";
        }
        if (extensionOf(name).isEmpty()) {
            String ext = fallbackExt == null ? "" : fallbackExt.trim();
            if (!ext.isEmpty() && ext.charAt(0) != '.') {
                ext = "." + ext;
            }
            if (!ext.isEmpty() && !".".equals(ext)) {
                name = name + sanitizeName(ext);
            }
        }
        return truncateKeepingExtension(name, MAX_NAME_CHARS);
    }

    /** Replace everything the storage layer rejects, and trim the ends. Never returns null. */
    public static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || ILLEGAL.indexOf(c) >= 0) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        // Trailing '.' / ' ' are illegal on FAT and get rewritten by MediaStore.
        int end = out.length();
        while (end > 0 && (out.charAt(end - 1) == '.' || out.charAt(end - 1) == ' ')) {
            end--;
        }
        return out.substring(0, end);
    }

    /** Lower-case extension without the dot, or "" when there is none. */
    public static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot < slash || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase();
    }

    /** MIME type for a gallery row — MediaStore rejects a video in the Images collection and vice versa. */
    public static String mimeTypeFor(String displayName, boolean video) {
        String ext = extensionOf(displayName);
        if (video) {
            if ("mov".equals(ext)) {
                return "video/quicktime";
            }
            if ("m4v".equals(ext)) {
                return "video/x-m4v";
            }
            return "video/mp4";
        }
        if ("png".equals(ext)) {
            return "image/png";
        }
        if ("heic".equals(ext) || "heif".equals(ext)) {
            return "image/heic";
        }
        if ("dng".equals(ext)) {
            return "image/x-adobe-dng";
        }
        if ("webp".equals(ext)) {
            return "image/webp";
        }
        if ("gif".equals(ext)) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    /**
     * Pre-flight space check: is there room for {@code needed} bytes while keeping
     * {@code reserveBytes} free?
     *
     * <p>{@code free < 0} means "could not measure" and is allowed through — blocking every transfer
     * because StatFs failed would be worse than letting the write fail with ENOSPC, which the
     * private-directory fallback already handles. {@code free == 0} is a genuinely full volume and is
     * refused.
     */
    public static boolean hasRoom(long needed, long free, long reserveBytes) {
        long reserve = reserveBytes < 0 ? 0 : reserveBytes;
        if (needed <= 0) {
            return true;
        }
        if (free < 0) {
            return true;
        }
        long usable = free - reserve;
        return usable > 0 && needed <= usable;
    }

    /** Collision-free name: {@code clip.mp4} → {@code clip (1).mp4}. */
    public static String uniqueName(String desired, Set<String> taken) {
        if (taken == null || !taken.contains(desired)) {
            return desired;
        }
        String ext = extensionOf(desired);
        String stem = ext.isEmpty() ? desired : desired.substring(0, desired.length() - ext.length() - 1);
        String suffix = ext.isEmpty() ? "" : "." + ext;
        for (int n = 1; n < 10000; n++) {
            String candidate = stem + " (" + n + ")" + suffix;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        return stem + " (" + System.currentTimeMillis() + ")" + suffix;
    }

    /** Cut a name down to {@code max} characters without losing its extension (case preserved). */
    public static String truncateKeepingExtension(String name, int max) {
        if (name == null || max <= 0 || name.length() <= max) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String suffix = "";
        if (dot > 0 && dot < name.length() - 1 && dot > name.lastIndexOf('/')) {
            suffix = name.substring(dot);
        }
        int keep = max - suffix.length();
        if (keep < 1) {
            return name.substring(0, max);
        }
        String stem = name.substring(0, name.length() - suffix.length());
        return stem.substring(0, Math.min(keep, stem.length())) + suffix;
    }

    // ------------------------------------------------------------ android bits ---

    /** Free bytes on the volume holding {@code dir}, or -1 when it cannot be measured. */
    public static long freeBytes(File dir) {
        if (dir == null) {
            return -1;
        }
        File probe = dir;
        while (probe != null && !probe.exists()) {
            probe = probe.getParentFile();
        }
        if (probe == null) {
            return -1;
        }
        try {
            return new StatFs(probe.getAbsolutePath()).getAvailableBytes();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** The app-private fallback folder. Created if needed. */
    public static File privateDir(Context context) throws IOException {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();   // no external volume at all
        }
        if (base == null) {
            throw new IOException("no writable app directory (external files dir and files dir are both null)");
        }
        File dir = new File(base, APP_DIR_NAME);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create " + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * Pre-flight for a transfer of {@code needed} bytes: shared storage (where both destinations
     * live) must hold it plus {@link #DEFAULT_RESERVE_BYTES}.
     *
     * <p>{@code Environment.getExternalStorageDirectory()} is deprecated on API 29+ but remains the
     * only way to measure the shared volume both destinations actually live on; {@code StatFs} needs
     * a directory path of its own.
     */
    @SuppressWarnings("deprecation")
    public static boolean checkSpace(Context context, long needed) {
        long free = freeBytes(Environment.getExternalStorageDirectory());
        if (free < 0) {
            try {
                free = freeBytes(privateDir(context));
            } catch (IOException e) {
                free = -1;
            }
        }
        return hasRoom(needed, free, DEFAULT_RESERVE_BYTES);
    }

    /**
     * Open a destination for {@code displayName}: a pending MediaStore row when possible, otherwise a
     * file in the app-private folder. Never returns null; throws only when <b>both</b> destinations
     * are unusable.
     */
    public static Target open(Context context, String displayName, boolean video) throws IOException {
        if (context == null) {
            throw new IllegalArgumentException("context is null");
        }
        String name = buildDisplayName(displayName, video ? ".mp4" : ".jpg", video);
        String why = "API " + Build.VERSION.SDK_INT + " < Q";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Uri collection = video
                        ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(name, video));
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                ContentResolver resolver = context.getContentResolver();
                Uri uri = resolver.insert(collection, values);
                if (uri != null && isWritable(resolver, uri)) {
                    return new Target(resolver, uri, null, name);
                }
                if (uri != null) {
                    // A row we cannot write to is worse than no row: drop it and fall back.
                    why = "row created but not writable";
                    try {
                        resolver.delete(uri, null, null);
                    } catch (RuntimeException ignored) {
                        // the row may already be gone
                    }
                } else {
                    why = "insert returned null";
                }
            } catch (RuntimeException e) {
                // RemoteException / IllegalArgumentException / SecurityException — fall through.
                why = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }

        File dir = privateDir(context);
        File file = new File(dir, uniqueName(name, namesIn(dir)));
        Target target = new Target(null, null, file, name);
        target.fallbackReason = why;
        return target;
    }

    /** Confirm the freshly inserted pending row can actually be opened before committing to it. */
    private static boolean isWritable(ContentResolver resolver, Uri uri) {
        ParcelFileDescriptor probe = null;
        try {
            probe = resolver.openFileDescriptor(uri, "rw");
            return probe != null;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (probe != null) {
                try {
                    probe.close();
                } catch (IOException ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    private static Set<String> namesIn(File dir) {
        Set<String> names = new HashSet<String>();
        String[] list = dir.list();
        if (list != null) {
            for (String n : list) {
                names.add(n);
            }
        }
        return names;
    }

    // ------------------------------------------------------------------ target ---

    /**
     * One file being written. Reopenable at any offset (that is the resume), and it keeps the
     * {@code IS_PENDING} flag honest: {@link #commit()} publishes, {@link #abort()} removes.
     */
    public static final class Target implements Downloader.Sink {

        private final ContentResolver resolver;   // null in file mode
        private final Uri uri;                    // null in file mode
        private final File file;                  // null in MediaStore mode
        private final String displayName;

        private OutputStream pass;
        private ParcelFileDescriptor pfd;
        private boolean committed;
        private boolean aborted;
        private String fallbackReason = "";

        Target(ContentResolver resolver, Uri uri, File file, String displayName) {
            this.resolver = resolver;
            this.uri = uri;
            this.file = file;
            this.displayName = displayName;
        }

        public boolean isMediaStore() {
            return uri != null;
        }

        public Uri uri() {
            return uri;
        }

        public File file() {
            return file;
        }

        public String displayName() {
            return displayName;
        }

        /** Why MediaStore was not used, when the private directory is the destination. */
        public String fallbackReason() {
            return fallbackReason;
        }

        /** Where a UI should point the user: a {@code content://} Uri or an absolute path. */
        public String savedLocation() {
            return uri != null ? uri.toString() : (file == null ? "?" : file.getAbsolutePath());
        }

        @Override
        public String describe() {
            return (uri != null ? "MediaStore " + uri : "file " + (file == null ? "?" : file.getAbsolutePath()))
                    + " (" + displayName + ")";
        }

        @Override
        public long storedBytes() throws IOException {
            if (file != null) {
                return file.exists() ? file.length() : 0L;
            }
            ParcelFileDescriptor p = resolver.openFileDescriptor(uri, "r");
            if (p == null) {
                return 0L;
            }
            try {
                long size = p.getStatSize();
                return size < 0 ? 0L : size;
            } finally {
                p.close();
            }
        }

        @Override
        public OutputStream openAt(long offset) throws IOException {
            if (committed || aborted) {
                throw new IOException("sink already closed: " + describe());
            }
            closePass();
            long at = offset < 0 ? 0 : offset;
            if (file != null) {
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IOException("cannot create " + parent.getAbsolutePath());
                }
                RandomAccessFile raf = new RandomAccessFile(file, "rw");
                try {
                    raf.seek(at);
                    if (raf.length() > at) {
                        raf.setLength(at);   // never leave a stale tail behind the write head
                    }
                } catch (IOException e) {
                    closeQuietly(raf);
                    throw e;
                }
                pass = new RafOutputStream(raf);
                return pass;
            }
            pfd = resolver.openFileDescriptor(uri, "rw");
            if (pfd == null) {
                throw new IOException("openFileDescriptor returned null for " + uri);
            }
            FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
            fos.getChannel().position(at);
            pass = fos;
            return pass;
        }

        /**
         * End the current pass.
         *
         * <p>Private-directory mode: the stream owns its {@link RandomAccessFile}, so it is closed.
         *
         * <p>MediaStore mode: the stream merely wraps the very descriptor the
         * {@link ParcelFileDescriptor} owns, so it is flushed and then <b>dropped without being
         * closed</b> — closing both would close the same fd twice, which can silently take out an
         * unrelated, freshly opened fd. Writes are unbuffered, so the flush is the whole job.
         */
        @Override
        public void closePass() throws IOException {
            if (pass != null) {
                OutputStream p = pass;
                pass = null;
                if (file != null) {
                    p.close();
                } else {
                    try {
                        p.flush();
                    } catch (IOException ignored) {
                        // the pass is over either way
                    }
                }
            }
            if (pfd != null) {
                ParcelFileDescriptor p = pfd;
                pfd = null;
                p.close();
            }
        }

        @Override
        public void truncate(long size) throws IOException {
            closePass();
            long at = size < 0 ? 0 : size;
            if (file != null) {
                RandomAccessFile raf = new RandomAccessFile(file, "rw");
                try {
                    raf.setLength(at);
                } finally {
                    closeQuietly(raf);
                }
                return;
            }
            ParcelFileDescriptor p = resolver.openFileDescriptor(uri, "rw");
            if (p == null) {
                throw new IOException("openFileDescriptor returned null for " + uri);
            }
            try {
                FileOutputStream fos = new FileOutputStream(p.getFileDescriptor());
                fos.getChannel().truncate(at);
                fos.flush();
            } finally {
                p.close();
            }
        }

        @Override
        public void commit() throws IOException {
            closePass();
            if (committed || aborted) {
                return;
            }
            committed = true;
            if (uri != null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            }
        }

        @Override
        public void abort() {
            aborted = true;
            try {
                closePass();
            } catch (IOException ignored) {
                // nothing useful to do
            }
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (RuntimeException ignored) {
                    // the row may already be gone
                }
            } else if (file != null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }

        private static void closeQuietly(RandomAccessFile raf) {
            try {
                raf.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    /** {@link OutputStream} over a {@link RandomAccessFile}, so an arbitrary offset needs no fd games. */
    private static final class RafOutputStream extends OutputStream {
        private final RandomAccessFile raf;

        RafOutputStream(RandomAccessFile raf) {
            this.raf = raf;
        }

        @Override
        public void write(int b) throws IOException {
            raf.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            raf.write(b, off, len);
        }

        @Override
        public void flush() {
            // RandomAccessFile is unbuffered
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }
}
