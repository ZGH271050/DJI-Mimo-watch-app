package com.watch.osmo.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * The camera's {@code /v2} file API: fetch one file by explicit path.
 *
 * <p>The camera runs lighttpd and exposes <b>no directory listing</b>. Files are addressed as
 * {@code GET /v2?storage={0|1}&path=<path>} where {@code 0} is internal storage and {@code 1} the SD
 * card. {@code HEAD} is supported (Content-Length) and so is {@code Range} (206 partial), which is
 * what makes resumable transfers possible. Thumbnails ({@code MISC/THM/.../<name>.scr}) come out of
 * the same endpoint and their bytes are already a JPEG, decodable as-is.
 *
 * <p><b>Deliberately pure {@code java.net} / {@code java.io}.</b> No Android API is touched
 * anywhere in this class, so the whole transfer path can be compiled and exercised against a mock
 * camera on a desktop JVM (see {@code test/HttpSelfTest.java}).
 *
 * <p>Two failure shapes matter to callers:
 * <ul>
 *   <li>a failed request throws an {@link IOException} whose message names the URL, the byte offset
 *       and the reason — whatever already reached the {@link OutputStream} is left in place, so the
 *       caller resumes from its own length;</li>
 *   <li>{@link #downloadRange} returns {@code false} when a ranged request was answered {@code 200}
 *       (the whole file, starting at byte 0). <b>Nothing is written in that case</b>, because
 *       appending that body at the resume offset would splice the file's opening bytes into its
 *       middle and produce a corrupt file of a plausible size. The caller must restart at 0.</li>
 * </ul>
 */
public final class HttpFile {

    /** Connect timeout for a transfer request, in milliseconds. */
    public static final int CONNECT_TIMEOUT_MS = 8000;

    /** Read timeout for a transfer request, in milliseconds. The camera pauses mid-transfer. */
    public static final int READ_TIMEOUT_MS = 20000;

    /** Short timeout for a HEAD probe ({@link #detectStorage}, {@link #headContentLength}). */
    public static final int PROBE_TIMEOUT_MS = 4000;

    /**
     * Connect attempts before a request gives up. Retries happen <b>only before the first body byte
     * is consumed</b>: the camera routinely refuses a burst of reconnects, and a refused connect
     * moves no bytes, so retrying it is safe. Once the body is streaming, an interruption must not be
     * retried here — the caller resumes from the number of bytes it actually stored.
     */
    public static final int MAX_CONNECT_ATTEMPTS = 3;

    /** Pause before connect attempt N+1, multiplied by the attempt number. */
    private static final long RETRY_BACKOFF_MS = 400L;

    /** Copy buffer. The point is that a 4 GB clip never sits in memory. */
    private static final int COPY_BUFFER = 64 * 1024;

    /** Ceiling for {@link #getBytes} — it exists for thumbnails, not for media. */
    public static final int MAX_SMALL_BYTES = 8 * 1024 * 1024;

    /** Progress of one transfer, in absolute file offsets (not per-request bytes). */
    public interface ProgressListener {
        /**
         * @param done  bytes of the file now written, counting the offset the transfer resumed at
         * @param total total file size, or {@code -1} when the server did not say
         */
        void onProgress(long done, long total);
    }

    private HttpFile() {
    }

    // ---------------------------------------------------------------- URLs ---

    /**
     * Absolute URL for a camera path: {@code http://host/v2?storage=N&path=<encoded>}.
     *
     * <p>Every path segment is percent-encoded with UTF-8, so non-ASCII names, spaces, {@code #},
     * {@code %}, {@code &} and {@code ?} survive the trip. Separators stay literal ({@code /}), and
     * the query parameter itself is not encoded into the path (that is what breaks naive
     * implementations: a bare {@code #} truncates the URL, and a bare {@code &} or {@code ?} splits
     * the query).
     *
     * @param host    {@code 192.168.2.1}, {@code 192.168.2.1:8080} or a full {@code http://…} base
     * @param storage {@code 0} internal, {@code 1} SD card
     * @param path    camera path, e.g. {@code DCIM/DJI_001/DJI_20260329115359_0211_D.MP4}
     */
    public static String buildV2Url(String host, int storage, String path) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("host is empty");
        }
        String base = host.trim();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "http://" + base;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/v2?storage=" + storage + "&path=" + encodePath(path);
    }

    /**
     * Percent-encode a camera path for use as the {@code path} query value, keeping {@code /} as the
     * separator between segments.
     */
    public static String encodePath(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        StringBuilder sb = new StringBuilder(p.length() + 16);
        String[] parts = p.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(encodeSegment(parts[i]));
        }
        return sb.toString();
    }

    /** Percent-encode one path segment (space becomes {@code %20}, not {@code +}). */
    public static String encodeSegment(String segment) {
        if (segment == null) {
            return "";
        }
        try {
            // URLEncoder leaves '*', '-', '_' and '.' alone and turns a space into '+', which is only
            // legal in a form body — inside a URL path/query value it must be %20.
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is unavailable", e);
        }
    }

    /** {@code Range} header value. An open-ended range runs to the end of the file. */
    public static String rangeHeader(long from, long to) {
        long start = from < 0 ? 0 : from;
        if (to >= start) {
            return "bytes=" + start + "-" + to;
        }
        return "bytes=" + start + "-";
    }

    // --------------------------------------------------------------- HEAD ---

    /**
     * Content-Length of {@code url} via HEAD, or <b>-1</b> on any failure (no route, timeout, 404,
     * 5xx, no Content-Length). Callers use this to size a file and to decide whether a local copy is
     * already complete, so "unknown" must not be confused with "zero".
     */
    public static long headContentLength(String url, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = open(url, "HEAD", timeoutMs, -1, -1);
            int code = c.getResponseCode();
            if (code < 200 || code > 299) {
                return -1;
            }
            long len = contentLength(c);
            return len < 0 ? -1 : len;
        } catch (IOException e) {
            return -1;
        } finally {
            disconnect(c);
        }
    }

    /** HTTP status of a HEAD, or -1 when the request could not be completed at all. */
    public static int headStatus(String url, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = open(url, "HEAD", timeoutMs, -1, -1);
            return c.getResponseCode();
        } catch (IOException e) {
            return -1;
        } finally {
            disconnect(c);
        }
    }

    /** True when a HEAD answered 2xx. */
    public static boolean isAvailable(String url, int timeoutMs) {
        int code = headStatus(url, timeoutMs);
        return code >= 200 && code <= 299;
    }

    /**
     * Which storage id holds {@code probePath}: tries SD ({@code storage=1}) first, then internal
     * ({@code storage=0}), and returns <b>1</b>, <b>0</b>, or <b>-1</b> when neither answers. The
     * probe costs two HEADs and is how the (documented, hardware-verified) "storage id is not in the
     * list paths" problem is solved.
     */
    public static int detectStorage(String host, String probePath) {
        if (host == null || host.trim().isEmpty() || probePath == null || probePath.isEmpty()) {
            return -1;
        }
        if (isAvailable(buildV2Url(host, 1, probePath), PROBE_TIMEOUT_MS)) {
            return 1;
        }
        if (isAvailable(buildV2Url(host, 0, probePath), PROBE_TIMEOUT_MS)) {
            return 0;
        }
        return -1;
    }

    // ------------------------------------------------------------- stream ---

    /**
     * Stream bytes {@code from..to} of {@code url} into {@code out}, never holding the body in memory.
     *
     * <p>The request carries {@code Range: bytes=from-to} (or {@code bytes=from-}, open ended, when
     * {@code to} is negative). {@code out} is written sequentially from its current position and is
     * <b>flushed but never closed</b> — the caller owns it and must position it at {@code from}
     * before calling, which is exactly how a resume works.
     *
     * @param from first byte to fetch (0 = the whole file)
     * @param to   inclusive last byte, or negative for "to the end of the file"
     * @param out  destination; not closed here
     * @param l    progress sink, may be null
     * @return {@code true} when the requested range was served; {@code false} when a ranged request
     *         was answered with the whole file ({@code 200} to a {@code from > 0} request) — in which
     *         case <b>nothing was written</b> and the caller must restart from 0
     * @throws IOException on a non-2xx status, a connect failure, a timeout or a mid-stream break.
     *                     Bytes already written stay written, so the caller can resume from the
     *                     file's new length.
     */
    public static boolean downloadRange(String url, long from, long to, OutputStream out,
                                        ProgressListener l) throws IOException {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("url is empty");
        }
        if (out == null) {
            throw new IllegalArgumentException("out is null");
        }
        final long start = from < 0 ? 0 : from;
        HttpURLConnection c = open(url, "GET", CONNECT_TIMEOUT_MS, start, to);
        long done = start;
        long total = -1;
        try {
            int code = c.getResponseCode();
            if (code < 200 || code > 299) {
                throw new IOException("HTTP " + code + " for " + url
                        + (code == 404 ? " (not found on this storage id?)" : ""));
            }
            // A range was asked for and the server answered with the whole file. Writing this body at
            // the resume offset would corrupt the file, so report it before a single byte is written.
            if (start > 0 && code != 206) {
                return false;
            }
            if (code == 206) {
                total = totalFromContentRange(c.getHeaderField("Content-Range"));
            }
            long len = contentLength(c);
            if (total < 0 && len >= 0) {
                total = start + len;
            }
            // `to` is honoured locally as well: a server that ignores the closed range must not be
            // allowed to overrun the window the caller asked for.
            final long limit = (to >= start) ? (to - start + 1) : -1L;
            if (l != null) {
                l.onProgress(done, total);
            }
            byte[] buf = new byte[COPY_BUFFER];
            InputStream in = c.getInputStream();
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (limit >= 0) {
                        long left = limit - (done - start);
                        if (left <= 0) {
                            break;
                        }
                        if (n > left) {
                            n = (int) left;
                        }
                    }
                    out.write(buf, 0, n);
                    done += n;
                    if (l != null) {
                        l.onProgress(done, total);
                    }
                }
            } finally {
                closeQuietly(in);
            }
            out.flush();
            return true;
        } catch (IOException e) {
            flushQuietly(out);   // keep whatever arrived: that is the next resume offset
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("HTTP ")) {
                throw e;         // already a clear status failure; do not bury it in a second wrap
            }
            throw new IOException("GET " + url + " failed at byte " + done
                    + (total > 0 ? " of " + total : "") + ": " + msg, e);
        } finally {
            disconnect(c);
        }
    }

    /**
     * Whole (small) file into memory — a thumbnail, a {@code .scr}, a JSON blob. Throws rather than
     * returning null so a caller cannot mistake a failed fetch for an empty file.
     */
    public static byte[] getBytes(String url, int timeoutMs) throws IOException {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("url is empty");
        }
        HttpURLConnection c = open(url, "GET", timeoutMs, -1, -1);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code > 299) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            long len = contentLength(c);
            int initial = (len > 0 && len < MAX_SMALL_BYTES) ? (int) len : 8192;
            ByteArrayOutputStream bos = new ByteArrayOutputStream(initial);
            byte[] buf = new byte[16 * 1024];
            InputStream in = c.getInputStream();
            try {
                int n;
                long seen = 0;
                while ((n = in.read(buf)) > 0) {
                    seen += n;
                    if (seen > MAX_SMALL_BYTES) {
                        throw new IOException("response larger than " + MAX_SMALL_BYTES
                                + " bytes — getBytes is for thumbnails, not media");
                    }
                    bos.write(buf, 0, n);
                }
            } finally {
                closeQuietly(in);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IOException("GET " + url + " failed: " + e.getMessage(), e);
        } finally {
            disconnect(c);
        }
    }

    // ------------------------------------------------------------ helpers ---

    /**
     * Open a connection and force the request out, retrying only connection-phase failures. Leaving
     * the request unsent until the caller first reads the body would move the failure to a point
     * where retrying is no longer safe (bytes may have been written).
     *
     * <p>{@code new URL(String)} is deprecated on a modern desktop JVM but is the portable
     * constructor on Android and Java 8, which is what this code has to run on.
     */
    @SuppressWarnings("deprecation")
    private static HttpURLConnection open(String url, String method, int timeoutMs,
                                          long rangeFrom, long rangeTo) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_CONNECT_ATTEMPTS; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(timeoutMs > 0 ? timeoutMs : CONNECT_TIMEOUT_MS);
                c.setReadTimeout(Math.max(timeoutMs > 0 ? timeoutMs : 0, READ_TIMEOUT_MS));
                c.setRequestMethod(method);
                c.setRequestProperty("Accept", "*/*");
                // Deliberate: the camera cuts long transfers, and Android's connection pool would
                // otherwise hand the very same dead socket to the next request, which then fails
                // instantly with "unexpected end of stream" having moved no bytes.
                c.setRequestProperty("Connection", "close");
                c.setRequestProperty("User-Agent", "watch-osmo/0.1");
                if (rangeFrom > 0 || (rangeTo >= 0 && rangeTo >= rangeFrom)) {
                    c.setRequestProperty("Range", rangeHeader(rangeFrom, rangeTo));
                }
                c.getResponseCode();   // sends the request now; the body stays readable afterwards
                return c;
            } catch (IOException e) {
                last = e;
                disconnect(c);
                if (attempt < MAX_CONNECT_ATTEMPTS) {
                    sleepQuietly(RETRY_BACKOFF_MS * attempt);
                }
            }
        }
        throw new IOException(method + " " + url + " failed after " + MAX_CONNECT_ATTEMPTS
                + " attempts: " + (last == null ? "unknown" : last.getMessage()), last);
    }

    private static long contentLength(HttpURLConnection c) {
        String v = c.getHeaderField("Content-Length");
        if (v == null) {
            return -1;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Total size out of {@code Content-Range: bytes 100-4999/5000}. -1 when absent. */
    static long totalFromContentRange(String value) {
        if (value == null) {
            return -1;
        }
        int slash = value.lastIndexOf('/');
        if (slash < 0) {
            return -1;
        }
        String total = value.substring(slash + 1).trim();
        if (total.isEmpty() || "*".equals(total)) {
            return -1;
        }
        try {
            return Long.parseLong(total);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void disconnect(HttpURLConnection c) {
        if (c != null) {
            try {
                c.disconnect();
            } catch (RuntimeException ignored) {
                // nothing useful to do
            }
        }
    }

    static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    private static void flushQuietly(OutputStream out) {
        try {
            out.flush();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
