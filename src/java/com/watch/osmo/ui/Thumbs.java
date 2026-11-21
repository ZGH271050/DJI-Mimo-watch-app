package com.watch.osmo.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import com.watch.osmo.net.HttpFile;
import com.watch.osmo.proto.MediaFile;
import com.watch.osmo.util.Diag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded thumbnail loader for the browse list.
 *
 * <p>The watch has 1.8 GB of RAM and a 32-bit ABI, so every ceiling here is deliberate rather than
 * incidental:
 * <ul>
 *   <li><b>at most 32 decoded bitmaps</b> in an access-ordered LRU, plus a byte budget on top of the
 *       count, because 32 small renders and 32 large ones are not the same amount of memory;</li>
 *   <li><b>at most 2 download threads</b>, so a 200-file list cannot open 200 sockets;</li>
 *   <li><b>every decode goes through {@code inSampleSize}</b> aimed at {@value #TARGET_PX}px, so a
 *       camera that hands back a 1600px frame still only costs a ~110px bitmap. The full-size bitmap
 *       is never allocated: the sample size comes from a bounds-only first pass;</li>
 *   <li><b>requests are cancellable by generation.</b> {@link #clear()} drops everything queued and
 *       makes results already in flight unattachable, so a fast flick through a long list does not
 *       leave a hundred decodes running behind it.</li>
 * </ul>
 *
 * <p>Nothing in here throws at the UI. A missing thumbnail, a dead socket, an undecodable payload and
 * an {@code OutOfMemoryError} all end the same way: the row keeps its placeholder.
 *
 * <p>The only transport API used is the one {@code HttpFile} publishes:
 * {@link HttpFile#getBytes(String, int)} and {@link HttpFile#buildV2Url(String, int, String)}.
 * {@code HttpFile} is written by someone else; this class is the sole caller of both.
 */
public final class Thumbs {

    /** Camera hotspot gateway. An Osmo in AP mode is always the gateway of its own network. */
    public static final String CAMERA_HOST = "192.168.2.1";

    /** Thumbnails render at ~48dp; decoding to 110px covers that at this screen's 340dpi. */
    public static final int TARGET_PX = 110;

    /** Hard cap on cached bitmaps. A memory decision, not a tuning knob. */
    public static final int MAX_CACHE = 32;

    /** Second cap in bytes, so 32 unlucky large renders cannot slip past the count cap. */
    private static final int MAX_CACHE_BYTES = 6 * 1024 * 1024;
    private static final int MAX_DOWNLOADS = 2;
    private static final int FETCH_TIMEOUT_MS = 6000;
    /** Wait this long before starting the first download, so a flick does not start any at all. */
    private static final int DEBOUNCE_IDLE_MS = 120;
    /** Shorten the wait once several rows have asked, otherwise a slow scroll never catches up. */
    private static final int DEBOUNCE_BUSY_MS = 40;
    private static final int BUSY_THRESHOLD = 4;
    /** A thumbnail bigger than this is not a thumbnail; skip it rather than allocate for it. */
    private static final int MAX_PAYLOAD_BYTES = 3 * 1024 * 1024;

    /** Where a decoded thumbnail goes. Always invoked on the main thread. */
    public interface Target {
        void onThumb(Bitmap bitmap);

        /** The row has been rebound or recycled, so a result arriving late is not wanted. */
        boolean isStale();
    }

    /** Notified when some thumbnail finished, so the list can rebind that one row. */
    public interface Observer {
        void onThumbReady(MediaFile file);
    }

    private final Object lock = new Object();
    /** Queue of requests, oldest first. Doubles as the record of which files may still be fetched. */
    private final List<MediaFile> queue = new ArrayList<MediaFile>();
    private final Map<MediaFile, Target> waiting = new HashMap<MediaFile, Target>();
    private final Map<MediaFile, Target> inFlight = new HashMap<MediaFile, Target>();
    private final LinkedHashMap<MediaFile, Bitmap> cache =
            new LinkedHashMap<MediaFile, Bitmap>(MAX_CACHE, 0.75f, true);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool;
    private final Thread dispatcher;
    private final AtomicInteger generation = new AtomicInteger();

    private volatile boolean running = true;
    private volatile Observer observer;
    private int cacheBytes;
    private int hits;
    private int misses;
    private int failures;

    public Thumbs() {
        this.pool = Executors.newFixedThreadPool(MAX_DOWNLOADS, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "osmo-thumb-" + n.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }
        });
        this.dispatcher = new Thread(new Runnable() {
            @Override
            public void run() {
                dispatchLoop();
            }
        }, "osmo-thumb-dispatch");
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();
    }

    public void setObserver(Observer observer) {
        this.observer = observer;
    }

    // ---- visibility gate ---------------------------------------------------------------------------

    /**
     * True when the row has pixels on the round panel.
     *
     * <p>Uses {@link Round#visibleFraction}, the same circle arithmetic the layout uses, so "visible"
     * here means "inside the 480px circle" rather than "inside the ScrollView". A row that has been
     * scrolled behind the bezel does not start a download. Called from a scroll listener, so it does
     * no allocation beyond one {@code int[2]}.
     */
    public static boolean visible(View row, int displayWidth, int displayHeight, float minFraction) {
        if (row == null || row.getWidth() <= 0 || row.getHeight() <= 0
                || displayWidth <= 0 || displayHeight <= 0) {
            return false;
        }
        int[] loc = new int[2];
        row.getLocationInWindow(loc);
        float cx = displayWidth / 2f;
        float cy = displayHeight / 2f;
        float r = Math.min(displayWidth, displayHeight) / 2f;
        float f = Round.visibleFraction(loc[0], loc[1], loc[0] + row.getWidth(),
                loc[1] + row.getHeight(), cx, cy, r);
        return f >= minFraction;
    }

    // ---- request path ------------------------------------------------------------------------------

    /**
     * Asks for the thumbnail of {@code file} on behalf of {@code target}.
     *
     * <p>Returns immediately. A cache hit is delivered through a posted callback rather than
     * synchronously, so callers never need to handle two different timing paths.
     */
    public void load(final MediaFile file, final Target target) {
        if (file == null || target == null) {
            return;
        }
        String thumbPath = file.getThumbPath();
        if (thumbPath == null || thumbPath.isEmpty()) {
            postMiss(target);
            return;
        }

        Bitmap cached;
        synchronized (lock) {
            cached = cache.get(file);
            if (cached != null) {
                hits++;
                cache.put(file, cached); // refresh LRU order
            } else {
                misses++;
            }
        }
        if (cached != null) {
            final Bitmap hit = cached;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (!target.isStale()) {
                        target.onThumb(hit);
                    }
                }
            });
            return;
        }
        synchronized (lock) {
            if (waiting.containsKey(file) || inFlight.containsKey(file)) {
                // Some other row wants this exact thumbnail. Its result lands in the cache and this
                // row picks it up on the next bind, so there is nothing to queue.
                return;
            }
            waiting.put(file, target);
            queue.add(file);
            lock.notifyAll();
        }
    }

    private void postMiss(final Target target) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!target.isStale()) {
                    target.onThumb(null);
                }
            }
        });
    }

    /**
     * Cancels everything queued and detaches everything in flight.
     *
     * <p>Call on every scroll and whenever the media list is replaced. Downloads already running are
     * not interrupted: a socket mid-read would leave the connection half-drained, and the result is
     * discarded by generation anyway.
     */
    public void clear() {
        generation.incrementAndGet();
        synchronized (lock) {
            queue.clear();
            waiting.clear();
            inFlight.clear();
            lock.notifyAll();
        }
    }

    /** Frees the cache without stopping the loader, for a low-memory callback. */
    public void clearCache() {
        synchronized (lock) {
            recycleAll();
        }
    }

    /** Stops the worker threads and frees the cache. The instance is unusable afterwards. */
    public void shutdown() {
        running = false;
        synchronized (lock) {
            queue.clear();
            waiting.clear();
            inFlight.clear();
            lock.notifyAll();
        }
        pool.shutdownNow();
        dispatcher.interrupt();
        synchronized (lock) {
            recycleAll();
        }
    }

    private void recycleAll() {
        for (Bitmap b : cache.values()) {
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
        }
        cache.clear();
        cacheBytes = 0;
    }

    /** Cache state for the log screen. */
    public String stats() {
        synchronized (lock) {
            return "缩略图 命中" + hits + " 未命中" + misses + " 失败" + failures
                    + " 缓存" + cache.size() + "/" + MAX_CACHE + " " + Ui.bytes(cacheBytes)
                    + " 排队" + queue.size() + " 在途" + inFlight.size();
        }
    }

    // ---- dispatcher --------------------------------------------------------------------------------

    /**
     * Single dispatcher thread.
     *
     * <p>It exists so the debounce lives in one place instead of in a flag on every row: rows ask,
     * the dispatcher waits, and only what is still wanted when the finger stops is sent to the pool.
     */
    private void dispatchLoop() {
        while (running) {
            List<MediaFile> batch;
            synchronized (lock) {
                if (queue.isEmpty()) {
                    if (!sleep(0)) {
                        return;
                    }
                    continue;
                }
                int wait = queue.size() > BUSY_THRESHOLD ? DEBOUNCE_BUSY_MS : DEBOUNCE_IDLE_MS;
                if (!sleep(wait)) {
                    return;
                }
                if (queue.isEmpty()) {
                    continue;
                }
                batch = new ArrayList<MediaFile>(queue);
                queue.clear();
            }
            for (MediaFile file : batch) {
                Target target;
                synchronized (lock) {
                    target = waiting.get(file);
                    if (target == null) {
                        continue;
                    }
                    inFlight.put(file, target);
                }
                pool.execute(new ThumbTask(file, target, generation.get()));
            }
        }
    }

    /** Waits {@code ms} on the queue monitor (0 = indefinitely). False when the thread must exit. */
    private boolean sleep(long ms) {
        try {
            if (ms <= 0) {
                lock.wait();
            } else {
                lock.wait(ms);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---- worker ------------------------------------------------------------------------------------

    private final class ThumbTask implements Runnable {
        private final MediaFile file;
        private final Target target;
        private final int gen;

        ThumbTask(MediaFile file, Target target, int gen) {
            this.file = file;
            this.target = target;
            this.gen = gen;
        }

        @Override
        public void run() {
            Bitmap result = null;
            try {
                if (!superseded()) {
                    result = fetchAndDecode(file);
                }
            } catch (Throwable t) {
                // OutOfMemoryError included: a thumbnail is never worth crashing the list for.
                Diag.w("缩略图处理异常 " + file.getName() + ": " + t);
            }
            publish(file, target, result, superseded() || target.isStale());
        }

        private boolean superseded() {
            return gen < generation.get();
        }
    }

    private void publish(MediaFile file, Target target, Bitmap result, boolean drop) {
        synchronized (lock) {
            if (inFlight.get(file) == target) {
                inFlight.remove(file);
            }
            waiting.remove(file);
            if (result != null) {
                Bitmap old = cache.put(file, result);
                if (old != null && old != result) {
                    cacheBytes -= bytesOf(old);
                }
                cacheBytes += bytesOf(result);
                trim();
            } else {
                failures++;
                Diag.w("缩略图失败 " + file.getName() + "（网络/解码），这一格会保留占位符");
            }
        }
        if (drop) {
            return;
        }
        final Bitmap bitmap = result;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!target.isStale()) {
                    target.onThumb(bitmap);
                }
                Observer o = observer;
                if (o != null) {
                    o.onThumbReady(file);
                }
            }
        });
    }

    private static int bytesOf(Bitmap bitmap) {
        return bitmap == null ? 0 : bitmap.getByteCount();
    }

    /** Enforces the byte cap; the count cap is applied as entries are inserted. */
    private void trim() {
        while (cacheBytes > MAX_CACHE_BYTES && !cache.isEmpty()) {
            java.util.Iterator<Map.Entry<MediaFile, Bitmap>> it = cache.entrySet().iterator();
            Map.Entry<MediaFile, Bitmap> eldest = it.next();
            it.remove();
            cacheBytes -= bytesOf(eldest.getValue());
        }
    }

    // ---- fetch + decode ----------------------------------------------------------------------------

    private Bitmap fetchAndDecode(MediaFile file) {
        String thumbPath = file.getThumbPath();
        byte[] raw = getBytes(file, thumbPath);
        if ((raw == null || raw.length == 0) && thumbPath.endsWith(".scr")) {
            // Videos carry a .scr screennail, photos a .thm; the camera lists one or the other.
            getBytes(file, thumbPath.substring(0, thumbPath.length() - 4) + ".thm");
        }
        if (raw == null || raw.length == 0) {
            return null;
        }
        if (raw.length > MAX_PAYLOAD_BYTES) {
            Diag.w("缩略图过大，跳过 " + file.getName() + " " + raw.length + "B");
            return null;
        }
        return decode(raw, file);
    }

    private byte[] getBytes(MediaFile file, String path) {
        int storage = file.getStorage();
        String url = HttpFile.buildV2Url(CAMERA_HOST,
                storage < 0 ? MediaFile.STORAGE_SD : storage, path);
        try {
            return HttpFile.getBytes(url, FETCH_TIMEOUT_MS);
        } catch (Throwable t) {
            Diag.w("缩略图下载失败 " + path + ": " + t);
            return null;
        }
    }

    /**
     * Decodes with {@code inSampleSize} taken from a bounds-only first pass, so the full-size bitmap
     * is never allocated. Returns null instead of throwing when the payload is not an image.
     */
    private Bitmap decode(byte[] raw, MediaFile file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Diag.w("缩略图不是图片 " + file.getName() + " " + raw.length + "B");
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, TARGET_PX);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
            if (bitmap == null) {
                Diag.w("缩略图解码为空 " + file.getName());
            }
            return bitmap;
        } catch (Throwable t) {
            Diag.w("缩略图解码失败 " + file.getName() + ": " + t);
            return null;
        }
    }

    /** Smallest power-of-two sub-sample that keeps the longer edge at or above {@code target}. */
    static int sampleSize(int width, int height, int target) {
        if (target <= 0) {
            return 1;
        }
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / (sample * 2) >= target) {
            sample *= 2;
        }
        return sample;
    }

    // ---- row helper --------------------------------------------------------------------------------

    /**
     * Binds a thumbnail onto an {@link ImageView} the safe way.
     *
     * <p>The URL is stashed in the view tag, so a result that arrives after the row has been recycled
     * for a different file is discarded instead of painting the wrong picture. The main-thread
     * {@link Handler} is used for the reply rather than {@code View.post}: a detached view drops
     * posted runnables silently, which would leave the row stuck on its placeholder.
     */
    public void bind(final MediaFile file, final ImageView view) {
        if (view == null) {
            return;
        }
        String path = file == null ? null : file.getThumbPath();
        view.setTag(path);
        view.setImageBitmap(null);
        if (file == null || path == null || path.isEmpty()) {
            return;
        }
        load(file, new Target() {
            @Override
            public void onThumb(Bitmap bitmap) {
                if (path.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                }
            }

            @Override
            public boolean isStale() {
                return !path.equals(view.getTag());
            }
        });
    }
}
