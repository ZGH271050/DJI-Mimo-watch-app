package com.watch.osmo.net;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Download queue for camera files: at most {@value #MAX_CONCURRENT} transfers at a time, pausable,
 * cancellable, and resumable — a file that already holds N bytes is re-requested with
 * {@code Range: bytes=N-} and the remainder is appended.
 *
 * <p><b>Pure Java.</b> Threads are {@link java.util.concurrent} only; nothing here touches an
 * Android API (no {@code Handler}, no {@code Context}), which is what lets the queueing, resume and
 * retry rules run under a desktop JVM against a mock camera. The destination is abstracted behind
 * {@link Sink}; on the device that is {@code com.watch.osmo.store.MediaSink.Target} (MediaStore with
 * a private-directory fallback).
 *
 * <p>The decisions callers and tests care about — <i>where to resume from</i>, <i>how much is
 * left</i>, <i>is it done</i>, <i>was the range ignored</i>, <i>how long to wait</i>, <i>when to give
 * up</i> — are static and side-effect free ({@link #resumeOffset}, {@link #remainingBytes},
 * {@link #isComplete}, {@link #rangeIgnored}, {@link #backoffMillis}, {@link #stalled},
 * {@link #attemptsExhausted}).
 *
 * <p>The retry policy mirrors what the hardware actually needed (see the reference implementation's
 * notes): the camera cuts long transfers and answers 404/500 while busy, so <b>those statuses are
 * transient, not fatal</b>. Nothing ends a job except a run of {@link #MAX_BARREN} consecutive
 * attempts that moved no bytes, or the {@link #MAX_ATTEMPTS} runaway guard.
 */
public class Downloader {

    /** Hard ceiling on simultaneous transfers. Two is what the camera tolerates. */
    public static final int MAX_CONCURRENT = 2;

    /** Attempts allowed per file, productive or not — a runaway guard, not a budget. */
    public static final int MAX_ATTEMPTS = 20;

    /** Consecutive zero-byte attempts before a transfer is called stalled. */
    public static final int MAX_BARREN = 5;

    /** Base pause between attempts; multiplied by the barren count. */
    public static final long RESUME_BACKOFF_MS = 750L;

    /** Ceiling for the resume backoff. */
    public static final long MAX_BACKOFF_MS = 8000L;

    private static final int HEAD_TIMEOUT_MS = 8000;

    // ---------------------------------------------------------------- job states ---

    public static final int PENDING = 0;
    public static final int RUNNING = 1;
    public static final int SAVED = 2;
    public static final int PAUSED = 3;
    public static final int FAILED = 4;
    public static final int CANCELLED = 5;

    public static String stateName(int state) {
        switch (state) {
            case PENDING: return "PENDING";
            case RUNNING: return "RUNNING";
            case SAVED: return "SAVED";
            case PAUSED: return "PAUSED";
            case FAILED: return "FAILED";
            case CANCELLED: return "CANCELLED";
            default: return "?" + state;
        }
    }

    /**
     * Where a download lands. Implemented on the device by {@code MediaSink.Target} (MediaStore row
     * plus private-directory fallback); a desktop test can back it with a plain file.
     */
    public interface Sink {
        /** Bytes already stored — the resume offset. */
        long storedBytes() throws IOException;

        /**
         * A stream positioned at {@code offset}. Must not discard what is already stored for a
         * resume; the caller writes sequentially from there.
         */
        OutputStream openAt(long offset) throws IOException;

        /** Close the stream handed out by the last {@link #openAt}. Safe to call when none is open. */
        void closePass() throws IOException;

        /** Drop everything past {@code size} (0 = start over). Used when the range was ignored. */
        void truncate(long size) throws IOException;

        /** The file is complete: publish it (MediaStore {@code IS_PENDING=0}). Called once. */
        void commit() throws IOException;

        /** Give up and remove the partial file. Called once. */
        void abort();

        /** Human-readable destination, for logs. */
        String describe();
    }

    /** Progress + lifecycle callbacks. All fire on worker threads. */
    public interface Listener {
        void onQueueStart(int totalFiles, long totalBytes);

        void onFileStart(int index, Job job);

        void onFileProgress(int index, Job job, long fileDone, long overallDone);

        /** One job settled. {@code state} is one of {@link #SAVED}, {@link #PAUSED}, {@link #FAILED}, {@link #CANCELLED}. */
        void onFileDone(int index, Job job, int state, String message);

        void onQueueDone(int saved, int failed, int cancelled);
    }

    /** Thrown from inside a progress callback to stop the current pass cooperatively. */
    public static final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Cancelled(String message) {
            super(message);
        }
    }

    /** One queued file. */
    public static final class Job {
        public final String id;
        public final String url;
        public final String displayName;

        /** Videos are stored in MediaStore.Video, everything else in Images. */
        public final boolean video;

        /** Size from the DUML manifest, or {@code <= 0} when unknown (a HEAD fills it in). */
        public final long declaredBytes;

        public final Sink sink;

        final AtomicLong done = new AtomicLong();
        volatile int index;
        volatile int state = PENDING;
        volatile String message = "";
        /** Remote size, from the manifest or the last HEAD/Content-Range. */
        volatile long remoteBytes;

        public Job(String id, String url, String displayName, boolean video, long declaredBytes,
                   Sink sink) {
            if (url == null || url.isEmpty()) {
                throw new IllegalArgumentException("job url is empty");
            }
            if (sink == null) {
                throw new IllegalArgumentException("job sink is null");
            }
            this.id = id;
            this.url = url;
            this.displayName = displayName;
            this.video = video;
            this.declaredBytes = declaredBytes;
            this.remoteBytes = declaredBytes;
            this.sink = sink;
        }

        /** Bytes of this file already stored (or written this pass). */
        public long doneBytes() {
            return done.get();
        }

        public int state() {
            return state;
        }

        public String message() {
            return message;
        }

        @Override
        public String toString() {
            return displayName + "[" + stateName(state) + " " + done.get() + "/" + remoteBytes + "]";
        }
    }

    // ------------------------------------------------------- pure decision logic ---

    /** Clamp a requested worker count into {@code 1..MAX_CONCURRENT}. */
    public static int effectiveConcurrency(int requested) {
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, MAX_CONCURRENT);
    }

    /** True when the local file already holds everything the camera has. */
    public static boolean isComplete(long storedBytes, long remoteBytes) {
        return remoteBytes > 0 && storedBytes >= remoteBytes;
    }

    /**
     * Offset the next pass must request: {@code 0} for a fresh file, {@code storedBytes} for a
     * partial one, and <b>-1</b> when there is nothing left to fetch (already complete).
     */
    public static long resumeOffset(long storedBytes, long remoteBytes) {
        long have = storedBytes < 0 ? 0 : storedBytes;
        if (remoteBytes > 0 && have >= remoteBytes) {
            return -1L;
        }
        return have;
    }

    /** Bytes still missing, or <b>-1</b> when the remote size is unknown. */
    public static long remainingBytes(long storedBytes, long remoteBytes) {
        if (remoteBytes <= 0) {
            return -1L;
        }
        long have = storedBytes < 0 ? 0 : storedBytes;
        return Math.max(0L, remoteBytes - have);
    }

    /**
     * True when a request that started at {@code requestedFrom > 0} was answered with the whole file
     * instead of a {@code 206}. Appending that body would corrupt the file, so the caller must
     * truncate and restart at 0.
     */
    public static boolean rangeIgnored(long requestedFrom, int statusCode) {
        return requestedFrom > 0 && statusCode != 206;
    }

    /** Pause before the next attempt: base × max(streak, 1), capped. */
    public static long backoffMillis(int barrenStreak) {
        int n = barrenStreak < 1 ? 1 : barrenStreak;
        return Math.min(RESUME_BACKOFF_MS * (long) n, MAX_BACKOFF_MS);
    }

    /** True when {@code barrenStreak} consecutive attempts moved no bytes at all. */
    public static boolean stalled(int barrenStreak) {
        return barrenStreak >= MAX_BARREN;
    }

    /** True when the per-file attempt guard is used up. */
    public static boolean attemptsExhausted(int attempts) {
        return attempts >= MAX_ATTEMPTS;
    }

    // ------------------------------------------------------------------ engine ---

    private final Listener listener;
    private final List<Job> jobs = Collections.synchronizedList(new ArrayList<Job>());
    private final ExecutorService pool;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean cancelDeletesPartials;

    public Downloader(Listener listener) {
        this.listener = listener;
        this.pool = Executors.newFixedThreadPool(MAX_CONCURRENT, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "osmo-dl-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** Add a file. Runs immediately when the queue is already started and not paused. */
    public void enqueue(Job job) {
        try {
            job.done.set(job.sink.storedBytes());   // so overall progress is right from the start
        } catch (IOException ignored) {
            // nothing stored yet
        }
        synchronized (jobs) {
            job.index = jobs.size();
            jobs.add(job);
        }
        if (started.get() && !paused.get() && !cancelled.get()) {
            submit(job);
        }
    }

    /** (Re)start everything that is not settled. Also resumes after {@link #pause()}. */
    public void start() {
        paused.set(false);
        cancelled.set(false);
        List<Job> runnable = new ArrayList<Job>();
        synchronized (jobs) {
            for (Job j : jobs) {
                if (j.state == PENDING || j.state == PAUSED) {
                    j.state = PENDING;
                    runnable.add(j);
                }
            }
        }
        if (runnable.isEmpty()) {
            return;
        }
        if (started.compareAndSet(false, true) && listener != null) {
            listener.onQueueStart(jobs.size(), totalDeclaredBytes());
        }
        for (Job j : runnable) {
            submit(j);
        }
    }

    /**
     * Ask running transfers to stop. Whatever has arrived stays on disk, so {@link #start()} picks up
     * from the file's current length. Jobs that had not started stay queued.
     */
    public void pause() {
        paused.set(true);
    }

    /**
     * Abandon the queue. Running transfers stop at their next progress tick.
     *
     * @param deletePartials also delete whatever was written for jobs that are not saved
     */
    public void cancel(boolean deletePartials) {
        cancelDeletesPartials = deletePartials;
        cancelled.set(true);
        synchronized (jobs) {
            for (Job j : jobs) {
                if (j.state == PENDING) {
                    j.state = CANCELLED;
                    j.message = "cancelled";
                    if (deletePartials) {
                        j.sink.abort();
                    }
                    if (listener != null) {
                        listener.onFileDone(j.index, j, CANCELLED, j.message);
                    }
                }
            }
        }
    }

    public void shutdown() {
        paused.set(true);
        pool.shutdownNow();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public List<Job> jobs() {
        synchronized (jobs) {
            return new ArrayList<Job>(jobs);
        }
    }

    public Job job(int index) {
        synchronized (jobs) {
            return index >= 0 && index < jobs.size() ? jobs.get(index) : null;
        }
    }

    public int count(int state) {
        int n = 0;
        synchronized (jobs) {
            for (Job j : jobs) {
                if (j.state == state) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Sum of per-file bytes known so far (declared sizes plus anything HEAD learned). */
    public long totalKnownBytes() {
        long total = 0;
        synchronized (jobs) {
            for (Job j : jobs) {
                total += j.remoteBytes > 0 ? j.remoteBytes : Math.max(0L, j.declaredBytes);
            }
        }
        return total;
    }

    /** Sum of the bytes every job currently holds — the overall progress figure. */
    public long overallDone() {
        long total = 0;
        synchronized (jobs) {
            for (Job j : jobs) {
                total += j.done.get();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ worker ---

    private void submit(final Job job) {
        active.incrementAndGet();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runJob(job);
                } catch (Throwable t) {
                    finish(job, FAILED, "unexpected " + t.getClass().getSimpleName() + ": " + t.getMessage());
                } finally {
                    if (active.decrementAndGet() == 0) {
                        queueSettled();
                    }
                }
            }
        });
    }

    private void runJob(Job job) {
        if (cancelled.get()) {
            finish(job, CANCELLED, "cancelled");
            return;
        }
        if (paused.get()) {
            finish(job, PAUSED, "paused");
            return;
        }
        job.state = RUNNING;
        try {
            job.done.set(job.sink.storedBytes());
        } catch (IOException e) {
            job.done.set(0);
        }
        if (listener != null) {
            listener.onFileStart(job.index, job);
        }

        // Size: prefer what the manifest said, fall back to a HEAD (photos and unknown layouts).
        long remote = job.remoteBytes > 0 ? job.remoteBytes : HttpFile.headContentLength(job.url, HEAD_TIMEOUT_MS);
        if (remote > 0) {
            job.remoteBytes = remote;
        }
        if (isComplete(job.done.get(), remote)) {
            try {
                job.sink.commit();
                finish(job, SAVED, "already complete");
            } catch (IOException e) {
                finish(job, FAILED, "commit failed: " + e.getMessage());
            }
            return;
        }

        int attempt = 0;
        int barren = 0;
        while (true) {
            if (cancelled.get()) {
                finish(job, CANCELLED, "cancelled");
                return;
            }
            if (paused.get()) {
                finish(job, PAUSED, "paused at " + job.done.get() + " bytes");
                return;
            }
            if (attempt > 0 && !sleepFor(backoffMillis(barren))) {
                finish(job, CANCELLED, "interrupted while backing off");
                return;
            }

            final long offset = resumeOffset(safeStored(job), remote);
            if (offset < 0) {
                try {
                    job.sink.commit();
                    finish(job, SAVED, "complete");
                } catch (IOException e) {
                    finish(job, FAILED, "commit failed: " + e.getMessage());
                }
                return;
            }

            boolean honoured = false;
            String error = null;
            try {
                OutputStream out = job.sink.openAt(offset);
                honoured = HttpFile.downloadRange(job.url, offset, -1, out, progress(job));
            } catch (Cancelled c) {
                closeQuietly(job);
                finish(job, cancelled.get() ? CANCELLED : PAUSED, "stopped at " + job.done.get() + " bytes");
                return;
            } catch (IOException e) {
                error = e.getMessage();
            } finally {
                closeQuietly(job);
            }

            long before = offset;
            long after = safeStored(job);
            job.done.set(after);

            if (!honoured && error == null) {
                // A ranged request answered 200: HttpFile wrote nothing, so the partial copy is still
                // valid. Throw it away anyway and restart from 0 — splicing byte 0 into the middle is
                // exactly the corruption this branch exists to avoid.
                try {
                    job.sink.truncate(0);
                } catch (IOException e) {
                    finish(job, FAILED, "truncate failed: " + e.getMessage());
                    return;
                }
                job.done.set(0);
                attempt++;
                if (attempt >= MAX_ATTEMPTS) {
                    finish(job, FAILED, "camera ignored the resume range and the attempt budget is spent");
                    return;
                }
                continue;
            }

            boolean complete = error == null && honoured && (remote <= 0 || after >= remote);
            if (complete) {
                try {
                    job.sink.commit();
                    finish(job, SAVED, after + " bytes");
                } catch (IOException e) {
                    finish(job, FAILED, "commit failed: " + e.getMessage());
                }
                return;
            }

            barren = after > before ? 0 : barren + 1;
            attempt++;
            if (stalled(barren)) {
                finish(job, FAILED, "stalled: " + barren + " attempts moved no bytes"
                        + (error == null ? "" : " (" + error + ")"));
                return;
            }
            if (attemptsExhausted(attempt)) {
                finish(job, FAILED, "gave up after " + attempt + " attempts at " + after + " bytes"
                        + (error == null ? "" : " (" + error + ")"));
                return;
            }
            // loop: the next pass resumes from `after`
        }
    }

    private HttpFile.ProgressListener progress(final Job job) {
        return new HttpFile.ProgressListener() {
            @Override
            public void onProgress(long done, long total) {
                job.done.set(done);
                if (total > 0) {
                    job.remoteBytes = total;
                }
                if (listener != null) {
                    listener.onFileProgress(job.index, job, done, overallDone());
                }
                if (paused.get() || cancelled.get()) {
                    throw new Cancelled("stopped by request");
                }
            }
        };
    }

    private long safeStored(Job job) {
        try {
            return job.sink.storedBytes();
        } catch (IOException e) {
            return job.done.get();
        }
    }

    private void closeQuietly(Job job) {
        try {
            job.sink.closePass();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    private void finish(Job job, int state, String message) {
        job.state = state;
        job.message = message == null ? "" : message;
        if (state == CANCELLED && cancelDeletesPartials) {
            job.sink.abort();
        }
        if (listener != null) {
            listener.onFileDone(job.index, job, state, job.message);
        }
    }

    private void queueSettled() {
        int open = 0;
        synchronized (jobs) {
            for (Job j : jobs) {
                if (j.state == PENDING || j.state == RUNNING || j.state == PAUSED) {
                    open++;
                }
            }
        }
        if (open > 0) {
            return;   // still work queued, running, or held for a later resume
        }
        if (listener != null) {
            listener.onQueueDone(count(SAVED), count(FAILED), count(CANCELLED));
        }
    }

    private long totalDeclaredBytes() {
        long total = 0;
        synchronized (jobs) {
            for (Job j : jobs) {
                total += Math.max(0L, j.declaredBytes);
            }
        }
        return total;
    }

    private boolean sleepFor(long ms) {
        if (ms <= 0) {
            return true;
        }
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
