import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.watch.osmo.net.Downloader;
import com.watch.osmo.net.HttpFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Desktop self-test for the camera file-fetch + store layer — no JUnit, no device.
 *
 * <p>Stands up a mock camera on {@code com.sun.net.httpserver.HttpServer} that behaves like the real
 * lighttpd {@code /v2} endpoint: HEAD with Content-Length, Range with 206 + Content-Range, 404 for a
 * file that is not on the requested storage id, a {@code .scr} thumbnail that is already JPEG bytes,
 * and one file that deliberately ignores Range (answers 200) so the "do not corrupt the file" path
 * can be exercised.
 *
 * <p>Run: {@code java -cp <classes> HttpSelfTest} — exit code 1 if any check fails.
 */
public class HttpSelfTest {

    // ------------------------------------------------------------- fixtures ---

    /** On the SD card (storage=1). 5000 bytes — the resume test's whole file. */
    static final String MAIN = "DCIM/DJI_001/DJI_TEST_0001_D.MP4";
    /** Internal storage only (storage=0). */
    static final String INTERNAL = "DCIM/INTERNAL/INT_0001.JPG";
    /** Thumbnail, served by the same endpoint; its bytes are a JPEG. */
    static final String THUMB = "MISC/THM/DCIM/DJI_001/DJI_TEST_0001_D.scr";
    /** Non-ASCII, a space and a '#' — the URL-encoding end-to-end case. */
    static final String AWKWARD = "DCIM/测试 目录/照片 #1.JPG";
    /** Answers 200 to a ranged request, i.e. Range unsupported. */
    static final String NORANGE = "DCIM/NORANGE.BIN";
    static final String BIG1 = "DCIM/BIG/BIG_0001.MP4";
    static final String BIG2 = "DCIM/BIG/BIG_0002.MP4";
    static final String BIG3 = "DCIM/BIG/BIG_0003.MP4";

    static final Map<String, Integer> SD_FILES = new LinkedHashMap<String, Integer>();
    static final Map<String, Integer> INTERNAL_FILES = new LinkedHashMap<String, Integer>();

    static {
        SD_FILES.put(MAIN, 5000);
        SD_FILES.put(THUMB, 137);
        SD_FILES.put(AWKWARD, 333);
        SD_FILES.put(NORANGE, 4096);
        SD_FILES.put(BIG1, 400000);
        SD_FILES.put(BIG2, 400000);
        SD_FILES.put(BIG3, 400000);
        INTERNAL_FILES.put(INTERNAL, 2048);
    }

    /** Deterministic file content, shared by the mock server and the assertions. */
    static byte[] content(String path, int len) {
        byte[] out = new byte[len];
        long h = (long) path.hashCode() * 0x9E3779B97F4A7C15L;
        for (int i = 0; i < len; i++) {
            long x = h + (long) i * 0x2545F4914F6CDD1DL;
            x ^= (x >>> 29) ^ (x >>> 47);
            out[i] = (byte) x;
        }
        return out;
    }

    /** A JPEG-shaped blob, so the thumbnail bytes are recognisable as an image. */
    static byte[] thumbnail() {
        int len = SD_FILES.get(THUMB);
        byte[] b = content(THUMB, len);
        b[0] = (byte) 0xFF;   // SOI
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        b[3] = (byte) 0xE0;   // APP0
        b[len - 2] = (byte) 0xFF;
        b[len - 1] = (byte) 0xD9;   // EOI
        return b;
    }

    static int sizeOf(String path, int storage) {
        if (storage == 0) {
            Integer n = INTERNAL_FILES.get(path);
            return n == null ? -1 : n;
        }
        if (storage == 1) {
            Integer n = SD_FILES.get(path);
            return n == null ? -1 : n;
        }
        return -1;
    }

    static byte[] bytesOf(String path) {
        Integer n = SD_FILES.get(path);
        if (n == null) {
            n = INTERNAL_FILES.get(path);
        }
        if (n == null) {
            return new byte[0];
        }
        return THUMB.equals(path) ? thumbnail() : content(path, n);
    }

    // --------------------------------------------------------- mock camera ---

    static final class MockCamera implements HttpHandler {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final ConcurrentHashMap<String, String> firstRange = new ConcurrentHashMap<String, String>();
        volatile int requests;

        @Override
        public void handle(HttpExchange ex) throws IOException {
            int now = inFlight.incrementAndGet();
            requests++;
            int best = maxInFlight.get();
            while (now > best && !maxInFlight.compareAndSet(best, now)) {
                best = maxInFlight.get();
            }
            try {
                serve(ex);
            } catch (RuntimeException e) {
                e.printStackTrace();
            } finally {
                inFlight.decrementAndGet();
                ex.close();
            }
        }

        private void serve(HttpExchange ex) throws IOException {
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            String path = q.get("path");
            int storage = parseInt(q.get("storage"), -1);
            if (path == null || storage < 0) {
                sendStatus(ex, 400, "missing path/storage");
                return;
            }
            int len = sizeOf(path, storage);
            if (len < 0) {
                // Exactly what the camera does for the wrong storage id — this is what detectStorage reads.
                sendStatus(ex, 404, "not on storage " + storage);
                return;
            }
            String method = ex.getRequestMethod();
            if ("HEAD".equals(method)) {
                ex.getResponseHeaders().set("Content-Length", String.valueOf(len));
                ex.getResponseHeaders().set("Accept-Ranges", "bytes");
                ex.getResponseHeaders().set("Content-Type", contentTypeOf(path));
                ex.sendResponseHeaders(200, -1);
                return;
            }
            if (!"GET".equals(method)) {
                sendStatus(ex, 405, "method not allowed");
                return;
            }
            byte[] data = bytesOf(path);
            String range = ex.getRequestHeaders().getFirst("Range");
            firstRange.putIfAbsent(path, range == null ? "" : range);

            // NORANGE.BIN stands in for a server that does not implement Range at all.
            if (range == null || NORANGE.equals(path)) {
                ex.getResponseHeaders().set("Content-Type", contentTypeOf(path));
                ex.sendResponseHeaders(200, data.length);
                writePaced(ex.getResponseBody(), data, 0, data.length, path);
                return;
            }
            long[] r = parseRange(range, data.length);
            if (r == null) {
                ex.getResponseHeaders().set("Content-Range", "bytes */" + data.length);
                sendStatus(ex, 416, "range not satisfiable");
                return;
            }
            int from = (int) r[0];
            int to = (int) r[1];
            int slice = to - from + 1;
            ex.getResponseHeaders().set("Content-Type", contentTypeOf(path));
            ex.getResponseHeaders().set("Accept-Ranges", "bytes");
            ex.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + data.length);
            ex.sendResponseHeaders(206, slice);
            writePaced(ex.getResponseBody(), data, from, slice, path);
        }

        /**
         * Pace big bodies so two transfers genuinely overlap, which is what makes the
         * max-concurrency assertion meaningful rather than a race the test usually wins.
         */
        private void writePaced(OutputStream out, byte[] data, int off, int len, String path)
                throws IOException {
            boolean slow = len > 100000;
            int step = 16384;
            for (int i = 0; i < len; i += step) {
                int n = Math.min(step, len - i);
                out.write(data, off + i, n);
                if (slow) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            out.flush();
        }

        private static String contentTypeOf(String path) {
            String p = path.toLowerCase();
            if (p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".scr")) {
                return "image/jpeg";
            }
            if (p.endsWith(".mp4")) {
                return "video/mp4";
            }
            return "application/octet-stream";
        }

        /** {@code bytes=a-b} / {@code bytes=a-} → {from, to}, or null when unsatisfiable. */
        private static long[] parseRange(String header, int total) {
            String v = header.trim();
            if (!v.startsWith("bytes=")) {
                return null;
            }
            String spec = v.substring(6).trim();
            int dash = spec.indexOf('-');
            if (dash < 0) {
                return null;
            }
            String a = spec.substring(0, dash).trim();
            String b = spec.substring(dash + 1).trim();
            long from;
            try {
                from = a.isEmpty() ? Math.max(0, total - Long.parseLong(b)) : Long.parseLong(a);
            } catch (NumberFormatException e) {
                return null;
            }
            if (from < 0 || from >= total) {
                return null;
            }
            long to = total - 1;
            if (!b.isEmpty()) {
                try {
                    to = Math.min(Long.parseLong(b), total - 1);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            if (to < from) {
                return null;
            }
            return new long[]{from, to};
        }

        private static void sendStatus(HttpExchange ex, int code, String message) throws IOException {
            byte[] body = message.getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(code, body.length);
            ex.getResponseBody().write(body);
            ex.getResponseBody().flush();
        }
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<String, String>();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            try {
                out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------- test harness ----

    static int checks;
    static int failures;
    static final List<String> failureLog = new ArrayList<String>();

    static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    static void check(String name, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            String line = "FAIL  " + name + (detail == null ? "" : "  -- " + detail);
            failureLog.add(line);
            System.out.println(line);
        }
    }

    static void checkEq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(name, ok, "expected <" + expected + "> got <" + actual + ">");
    }

    static void checkEq(String name, long expected, long actual) {
        check(name, expected == actual, "expected <" + expected + "> got <" + actual + ">");
    }

    static void checkBytes(String name, byte[] expected, byte[] actual) {
        if (Arrays.equals(expected, actual)) {
            check(name, true, null);
            return;
        }
        String detail = "lengths " + expected.length + " vs " + actual.length;
        for (int i = 0; i < Math.min(expected.length, actual.length); i++) {
            if (expected[i] != actual[i]) {
                detail += ", first difference at byte " + i + ": " + expected[i] + " vs " + actual[i];
                break;
            }
        }
        check(name, false, detail);
    }

    static void info(String message) {
        System.out.println("INFO  " + message);
    }

    static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    static boolean awaitState(Downloader d, int state, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (d.count(state) >= 1) {
                return true;
            }
            Thread.sleep(10);
        }
        return d.count(state) >= 1;
    }

    /** Records progress so the tests can assert on the numbers the UI would show. */
    static final class ProgressProbe implements HttpFile.ProgressListener {
        final AtomicLong last = new AtomicLong(-1);
        final AtomicLong total = new AtomicLong(-1);
        final AtomicInteger calls = new AtomicInteger();
        final List<Long> samples = new ArrayList<Long>();

        @Override
        public void onProgress(long done, long t) {
            calls.incrementAndGet();
            last.set(done);
            if (t > 0) {
                total.set(t);
            }
            synchronized (samples) {
                samples.add(done);
            }
        }
    }

    /** A {@link Downloader.Sink} backed by one file — the desktop stand-in for MediaSink.Target. */
    static final class FileSink implements Downloader.Sink {
        final File file;
        private RandomAccessFile raf;
        boolean committed;
        boolean aborted;

        FileSink(File file) {
            this.file = file;
        }

        @Override
        public long storedBytes() {
            return file.exists() ? file.length() : 0L;
        }

        @Override
        public OutputStream openAt(long offset) throws IOException {
            closePass();
            RandomAccessFile r = new RandomAccessFile(file, "rw");
            r.seek(offset);
            if (r.length() > offset) {
                r.setLength(offset);
            }
            raf = r;
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    raf.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    raf.write(b, off, len);
                }
            };
        }

        @Override
        public void closePass() throws IOException {
            if (raf != null) {
                RandomAccessFile r = raf;
                raf = null;
                r.close();
            }
        }

        @Override
        public void truncate(long size) throws IOException {
            closePass();
            RandomAccessFile r = new RandomAccessFile(file, "rw");
            try {
                r.setLength(size);
            } finally {
                r.close();
            }
        }

        @Override
        public void commit() throws IOException {
            closePass();
            committed = true;
        }

        @Override
        public void abort() {
            aborted = true;
            try {
                closePass();
            } catch (IOException ignored) {
                // nothing useful to do
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }

        @Override
        public String describe() {
            return file.getAbsolutePath();
        }
    }

    // ----------------------------------------------------------------- main ---

    public static void main(String[] args) throws Exception {
        System.out.println("=== watch-osmo file-fetch self-test (mock camera) ===");
        System.out.println("java " + System.getProperty("java.version") + " on "
                + System.getProperty("os.name"));
        System.out.println();

        File tmp = new File(System.getProperty("java.io.tmpdir"), "osmo-selftest-" + System.nanoTime());
        if (!tmp.mkdirs() && !tmp.isDirectory()) {
            throw new IOException("cannot create " + tmp.getAbsolutePath());
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        MockCamera mock = new MockCamera();
        mockRef = mock;
        server.createContext("/v2", mock);
        // Kept in a field so it can be shut down: a fixed pool's threads are non-daemon, and
        // HttpServer.stop() does not touch the executor it was handed — the JVM would never exit.
        ExecutorService serverPool = Executors.newFixedThreadPool(8);
        server.setExecutor(serverPool);
        server.start();
        String host = "127.0.0.1:" + server.getAddress().getPort();
        System.out.println("mock camera: http://" + host + "/v2  (storage 0 = internal, 1 = SD)");
        System.out.println();

        try {
            testUrls(host);
            testHead(host);
            testDetectStorage(host);
            testResume(tmp, host);
            testThumbnail(host);
            testRangeIgnored(tmp, host);
            testDownloaderDecisions();
            testDownloaderEngine(tmp, host, mock);
            testDownloaderPauseResume(tmp, host, mock);
            testMediaSinkPureLogic();
        } finally {
            server.stop(0);
            serverPool.shutdownNow();
            deleteTree(tmp);
        }

        System.out.println();
        System.out.println("=== " + (checks - failures) + "/" + checks + " checks passed, "
                + failures + " failed ===");
        if (!failureLog.isEmpty()) {
            System.out.println("--- failures ---");
            for (String line : failureLog) {
                System.out.println(line);
            }
        }
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("RESULT: OK");
    }

    // ------------------------------------------------------------ URL rules ---

    static void testUrls(String host) throws Exception {
        String u = HttpFile.buildV2Url(host, 1, "DCIM/A B.jpg");
        checkEq("url/simple path is encoded, separators kept", "http://" + host
                + "/v2?storage=1&path=DCIM/A%20B.jpg", u);

        String awkward = HttpFile.buildV2Url(host, 1, AWKWARD);
        check("url/no raw space", awkward.indexOf(' ') < 0, awkward);
        check("url/no raw hash", awkward.indexOf('#') < 0, awkward);
        check("url/no second query separator",
                awkward.indexOf('&') == awkward.indexOf("&path="), awkward);
        check("url/is pure ASCII", isAscii(awkward), awkward);
        check("url/keeps the path separator", awkward.contains("/%E6%B5%8B"), awkward);

        // Round trip: whatever the camera-side parser decodes must be the original path, byte for byte.
        String encoded = awkward.substring(awkward.indexOf("path=") + 5);
        String decoded = URLDecoder.decode(encoded, "UTF-8");
        checkEq("url/round trips through URLDecoder", AWKWARD, decoded);

        checkEq("url/leading slash is dropped", "http://" + host + "/v2?storage=0&path=DCIM/X.MP4",
                HttpFile.buildV2Url(host, 0, "/DCIM/X.MP4"));
        checkEq("url/trailing slash on the host", "http://" + host + "/v2?storage=1&path=A.jpg",
                HttpFile.buildV2Url(host + "/", 1, "A.jpg"));
        checkEq("url/full scheme is accepted", "http://" + host + "/v2?storage=1&path=A.jpg",
                HttpFile.buildV2Url("http://" + host, 1, "A.jpg"));
        check("url/segment escapes every query metacharacter",
                "a%23b%3Fc%26d%3De".equals(HttpFile.encodeSegment("a#b?c&d=e")),
                HttpFile.encodeSegment("a#b?c&d=e"));
        checkEq("url/range header open ended", "bytes=100-", HttpFile.rangeHeader(100, -1));
        checkEq("url/range header closed", "bytes=0-99", HttpFile.rangeHeader(0, 99));

        // End to end: a path with non-ASCII, a space and a '#' really fetches the right file.
        byte[] got = HttpFile.getBytes(HttpFile.buildV2Url(host, 1, AWKWARD), 5000);
        checkBytes("url/non-ASCII path fetches the right bytes end to end",
                bytesOf(AWKWARD), got);
    }

    static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    // ----------------------------------------------------------------- HEAD ---

    static void testHead(String host) {
        long len = HttpFile.headContentLength(HttpFile.buildV2Url(host, 1, MAIN), 5000);
        checkEq("head/Content-Length of an existing file", 5000L, len);
        checkEq("head/missing file is -1",
                -1L, HttpFile.headContentLength(HttpFile.buildV2Url(host, 1, "DCIM/NOPE.MP4"), 5000));
        checkEq("head/wrong storage id is -1",
                -1L, HttpFile.headContentLength(HttpFile.buildV2Url(host, 0, MAIN), 5000));
        checkEq("head/internal-only file on storage 0", 2048L,
                HttpFile.headContentLength(HttpFile.buildV2Url(host, 0, INTERNAL), 5000));

        int dead = closedPort();
        long t0 = System.currentTimeMillis();
        checkEq("head/dead port is -1 (no exception escapes)",
                -1L, HttpFile.headContentLength(HttpFile.buildV2Url("127.0.0.1:" + dead, 1, MAIN), 2000));
        info("dead-port HEAD cost " + (System.currentTimeMillis() - t0) + " ms (3 attempts + backoff)");
    }

    static int closedPort() {
        try {
            ServerSocket s = new ServerSocket(0);
            int port = s.getLocalPort();
            s.close();
            return port;
        } catch (IOException e) {
            return 1;
        }
    }

    // -------------------------------------------------------- storage probe ---

    static void testDetectStorage(String host) {
        checkEq("storage/SD file selects 1 (after storage=1 answers 200)",
                1, HttpFile.detectStorage(host, MAIN));
        checkEq("storage/internal file selects 0 (after storage=1 answers 404)",
                0, HttpFile.detectStorage(host, INTERNAL));
        checkEq("storage/missing on both is -1",
                -1, HttpFile.detectStorage(host, "DCIM/NOPE.MP4"));
        int dead = closedPort();
        checkEq("storage/unreachable host is -1",
                -1, HttpFile.detectStorage("127.0.0.1:" + dead, MAIN));
    }

    // ---------------------------------------------------------------- resume ---

    static void testResume(File tmp, String host) throws Exception {
        String url = HttpFile.buildV2Url(host, 1, MAIN);
        byte[] expected = bytesOf(MAIN);
        File f = new File(tmp, "resume.bin");
        // The destination is a FileSink, not a bare FileOutputStream: opening a FileOutputStream
        // truncates, which is exactly what a real resumable sink must not do.
        FileSink sink = new FileSink(f);

        // First pass: exactly the first 100 bytes, into a fresh file.
        ProgressProbe p1 = new ProgressProbe();
        boolean ok1 = HttpFile.downloadRange(url, 0, 99, sink.openAt(0), p1);
        sink.closePass();
        check("resume/first 100 bytes honoured the range", ok1);
        checkEq("resume/first pass wrote exactly 100 bytes", 100L, f.length());
        checkBytes("resume/first pass content", Arrays.copyOf(expected, 100), readAll(f));

        // Second pass: resume at offset 100, positioned the way MediaSink positions a real sink.
        // (Forget the first pass's Range header so the assertion below sees the resume's request.)
        if (mockRef != null) {
            mockRef.firstRange.remove(MAIN);
        }
        ProgressProbe p2 = new ProgressProbe();
        boolean ok2 = HttpFile.downloadRange(url, 100, -1, sink.openAt(100), p2);
        sink.closePass();
        check("resume/remainder honoured the range", ok2);
        checkEq("resume/range header really asked for 100-",
                "bytes=100-", mockFirstRange(host, MAIN));
        checkEq("resume/file is the full length", 5000L, f.length());
        checkBytes("resume/stitched file is byte-for-byte the original", expected, readAll(f));
        checkEq("resume/progress reported the absolute total", 5000L, p2.total.get());
        checkEq("resume/progress ended at the file size", 5000L, p2.last.get());
        check("resume/progress is monotonic", monotonic(p2.samples));
        info("resume second pass made " + p2.calls.get() + " progress callbacks");

        // A whole-file transfer from 0 sends no Range header at all.
        File f2 = new File(tmp, "whole.bin");
        FileSink sink2 = new FileSink(f2);
        check("resume/whole-file download returns true",
                HttpFile.downloadRange(url, 0, -1, sink2.openAt(0), null));
        sink2.closePass();
        checkBytes("resume/whole-file content", expected, readAll(f2));
    }

    static String mockFirstRange(String host, String path) {
        return mockRef == null ? "?" : mockRef.firstRange.get(path);
    }

    static boolean monotonic(List<Long> samples) {
        synchronized (samples) {
            for (int i = 1; i < samples.size(); i++) {
                if (samples.get(i) < samples.get(i - 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------- thumbnail ---

    static void testThumbnail(String host) throws Exception {
        String url = HttpFile.buildV2Url(host, 1, THUMB);
        byte[] got = HttpFile.getBytes(url, 5000);
        byte[] expected = thumbnail();
        checkEq("thumb/length", expected.length, got.length);
        checkBytes("thumb/bytes equal what the camera served", expected, got);
        check("thumb/looks like a JPEG (SOI)",
                got.length > 2 && (got[0] & 0xFF) == 0xFF && (got[1] & 0xFF) == 0xD8);
        check("thumb/ends with EOI",
                (got[got.length - 2] & 0xFF) == 0xFF && (got[got.length - 1] & 0xFF) == 0xD9);

        boolean threw = false;
        try {
            HttpFile.getBytes(HttpFile.buildV2Url(host, 1, "MISC/THM/nope.scr"), 5000);
        } catch (IOException e) {
            threw = true;
        }
        check("thumb/missing thumbnail throws IOException (not an empty array)", threw);
    }

    // ---------------------------------------------------- Range unsupported ---

    static void testRangeIgnored(File tmp, String host) throws Exception {
        String url = HttpFile.buildV2Url(host, 1, NORANGE);
        byte[] expected = bytesOf(NORANGE);
        File f = new File(tmp, "norange.bin");
        FileSink sink = new FileSink(f);

        check("norange/first 100 bytes",
                HttpFile.downloadRange(url, 0, 99, sink.openAt(0), null));
        sink.closePass();
        checkEq("norange/partial length", 100L, f.length());
        byte[] before = readAll(f);

        // The server answers this ranged request with 200 + the whole body. Writing it at byte 100
        // would splice the file's opening into its middle: the call must refuse and write nothing.
        long t0 = System.currentTimeMillis();
        boolean honoured = HttpFile.downloadRange(url, 100, -1, sink.openAt(100), null);
        sink.closePass();
        info("norange/ignored-range response handled in " + (System.currentTimeMillis() - t0) + " ms");

        check("norange/downloadRange reports the range was ignored", !honoured);
        checkEq("norange/partial file was NOT extended", 100L, f.length());
        checkBytes("norange/partial file was NOT corrupted", before, readAll(f));

        // Restart properly, from 0.
        boolean ok = HttpFile.downloadRange(url, 0, -1, sink.openAt(0), null);
        sink.closePass();
        check("norange/restart from 0 succeeds", ok);
        checkBytes("norange/file after the restart equals the original", expected, readAll(f));
    }

    // ------------------------------------------------- Downloader decisions ---

    static void testDownloaderDecisions() {
        checkEq("decide/concurrency 0 -> 1", 1, Downloader.effectiveConcurrency(0));
        checkEq("decide/concurrency 1 -> 1", 1, Downloader.effectiveConcurrency(1));
        checkEq("decide/concurrency 2 -> 2", 2, Downloader.effectiveConcurrency(2));
        checkEq("decide/concurrency 8 is capped at 2", 2, Downloader.effectiveConcurrency(8));

        check("decide/complete when local == remote", Downloader.isComplete(5000, 5000));
        check("decide/complete when local > remote", Downloader.isComplete(5001, 5000));
        check("decide/not complete when short", !Downloader.isComplete(4999, 5000));
        check("decide/unknown remote is never complete", !Downloader.isComplete(100, -1));
        check("decide/zero/zero is not complete", !Downloader.isComplete(0, 0));

        checkEq("decide/fresh file resumes at 0", 0L, Downloader.resumeOffset(0, 5000));
        checkEq("decide/partial file resumes at its length", 100L, Downloader.resumeOffset(100, 5000));
        checkEq("decide/complete file has nothing to resume", -1L, Downloader.resumeOffset(5000, 5000));
        checkEq("decide/oversized local copy has nothing to resume", -1L,
                Downloader.resumeOffset(5001, 5000));
        checkEq("decide/unknown remote resumes at the local length", 100L,
                Downloader.resumeOffset(100, -1));

        checkEq("decide/remaining of a partial file", 4900L, Downloader.remainingBytes(100, 5000));
        checkEq("decide/remaining of a complete file", 0L, Downloader.remainingBytes(5000, 5000));
        checkEq("decide/remaining is unknown without a size", -1L, Downloader.remainingBytes(100, -1));

        check("decide/200 to a ranged request means Range was ignored",
                Downloader.rangeIgnored(100, 200));
        check("decide/206 to a ranged request is fine", !Downloader.rangeIgnored(100, 206));
        check("decide/200 for a from-0 request is fine", !Downloader.rangeIgnored(0, 200));

        checkEq("decide/backoff base", 750L, Downloader.backoffMillis(0));
        checkEq("decide/backoff grows with the barren streak", 2250L, Downloader.backoffMillis(3));
        checkEq("decide/backoff is capped", 8000L, Downloader.backoffMillis(100));
        check("decide/4 barren attempts is not yet a stall", !Downloader.stalled(4));
        check("decide/5 barren attempts is a stall", Downloader.stalled(5));
        check("decide/19 attempts remain", !Downloader.attemptsExhausted(19));
        check("decide/20 attempts are the cap", Downloader.attemptsExhausted(20));
    }

    // ---------------------------------------------------- Downloader engine ---

    static final class RecordingListener implements Downloader.Listener {
        final CountDownLatch done = new CountDownLatch(1);
        final List<String> events = new ArrayList<String>();
        final AtomicInteger saved = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger cancelled = new AtomicInteger();
        final AtomicLong lastOverall = new AtomicLong(-1);
        volatile boolean overallMonotonic = true;
        volatile int totalFiles = -1;

        @Override
        public void onQueueStart(int totalFiles, long totalBytes) {
            this.totalFiles = totalFiles;
            synchronized (events) {
                events.add("queueStart files=" + totalFiles + " bytes=" + totalBytes);
            }
        }

        @Override
        public void onFileStart(int index, Downloader.Job job) {
            synchronized (events) {
                events.add("start " + index + " " + job.displayName);
            }
        }

        @Override
        public void onFileProgress(int index, Downloader.Job job, long fileDone, long overallDone) {
            long prev = lastOverall.getAndSet(overallDone);
            if (prev >= 0 && overallDone < prev) {
                overallMonotonic = false;
            }
        }

        @Override
        public void onFileDone(int index, Downloader.Job job, int state, String message) {
            synchronized (events) {
                events.add("done " + index + " " + Downloader.stateName(state) + " " + message);
            }
        }

        @Override
        public void onQueueDone(int savedCount, int failedCount, int cancelledCount) {
            saved.set(savedCount);
            failed.set(failedCount);
            cancelled.set(cancelledCount);
            done.countDown();
        }
    }

    static void testDownloaderEngine(File tmp, String host, MockCamera mock) throws Exception {
        String[] paths = {BIG1, BIG2, BIG3};
        RecordingListener listener = new RecordingListener();
        Downloader downloader = new Downloader(listener);
        List<FileSink> sinks = new ArrayList<FileSink>();

        // The first file already holds its first 50000 bytes: the engine must resume, not restart.
        byte[] expected1 = bytesOf(BIG1);
        FileOutputStream preOut = new FileOutputStream(new File(tmp, "engine-0.bin"));
        preOut.write(expected1, 0, 50000);
        preOut.close();

        for (int i = 0; i < paths.length; i++) {
            FileSink sink = new FileSink(new File(tmp, "engine-" + i + ".bin"));
            sinks.add(sink);
            downloader.enqueue(new Downloader.Job("j" + i, HttpFile.buildV2Url(host, 1, paths[i]),
                    paths[i].substring(paths[i].lastIndexOf('/') + 1), true, bytesOf(paths[i]).length, sink));
        }

        mock.firstRange.clear();
        downloader.start();
        check("engine/queue finishes", listener.done.await(30, TimeUnit.SECONDS));
        checkEq("engine/queue start reported the file count", 3, listener.totalFiles);
        check("engine/all three files saved", listener.saved.get() == 3,
                "saved=" + listener.saved.get() + " failed=" + listener.failed.get());
        check("engine/overall progress never went backwards", listener.overallMonotonic);

        for (int i = 0; i < paths.length; i++) {
            checkBytes("engine/" + paths[i] + " is byte-for-byte the original",
                    bytesOf(paths[i]), readAll(sinks.get(i).file));
        }
        checkEq("engine/first file resumed at its existing length",
                "bytes=50000-", mock.firstRange.get(BIG1));
        check("engine/max concurrent transfers <= 2", mock.maxInFlight.get() <= 2,
                "observed " + mock.maxInFlight.get());
        check("engine/two transfers really did overlap", mock.maxInFlight.get() >= 2,
                "observed " + mock.maxInFlight.get());
        info("engine/mock camera peak concurrency = " + mock.maxInFlight.get()
                + ", total requests = " + mock.requests);
        downloader.shutdown();
    }

    static void testDownloaderPauseResume(File tmp, String host, MockCamera mock) throws Exception {
        final FileSink sink = new FileSink(new File(tmp, "pause.bin"));
        final Downloader[] box = new Downloader[1];
        final AtomicInteger pauses = new AtomicInteger();
        final CountDownLatch finished = new CountDownLatch(1);

        Downloader.Listener listener = new Downloader.Listener() {
            @Override
            public void onQueueStart(int totalFiles, long totalBytes) {
            }

            @Override
            public void onFileStart(int index, Downloader.Job job) {
            }

            @Override
            public void onFileProgress(int index, Downloader.Job job, long fileDone, long overallDone) {
                // Pause once, after real bytes have landed, so the resume has something to resume from.
                if (fileDone > 0 && pauses.compareAndSet(0, 1) && box[0] != null) {
                    box[0].pause();
                }
            }

            @Override
            public void onFileDone(int index, Downloader.Job job, int state, String message) {
            }

            @Override
            public void onQueueDone(int saved, int failed, int cancelled) {
                finished.countDown();
            }
        };

        Downloader d = new Downloader(listener);
        box[0] = d;
        byte[] expected = bytesOf(BIG1);
        Downloader.Job job = new Downloader.Job("p0", HttpFile.buildV2Url(host, 1, BIG1), "pause.mp4",
                true, expected.length, sink);
        d.enqueue(job);
        d.start();

        check("pause/transfer pauses when asked", awaitState(d, Downloader.PAUSED, 15000));
        long held = sink.storedBytes();
        check("pause/partial file kept on disk", held > 0 && held < expected.length,
                "held " + held + " of " + expected.length);
        check("pause/job state is PAUSED", d.job(0).state() == Downloader.PAUSED,
                Downloader.stateName(d.job(0).state()) + " " + d.job(0).message());
        info("pause/paused after " + held + " of " + expected.length + " bytes");

        mock.firstRange.remove(BIG1);
        d.start();
        check("pause/resume finishes the file", finished.await(30, TimeUnit.SECONDS));
        check("pause/resumed job is SAVED", d.job(0).state() == Downloader.SAVED,
                Downloader.stateName(d.job(0).state()) + " " + d.job(0).message());
        checkEq("pause/resume range starts where the partial file ended",
                "bytes=" + held + "-", mock.firstRange.get(BIG1));
        checkBytes("pause/resumed file is byte-for-byte the original", expected, readAll(sink.file));
        d.shutdown();
    }

    // ------------------------------------------------- MediaSink pure logic ---

    /**
     * {@code MediaSink} is an Android class, so it is only present when the Android build output is on
     * the classpath; without it these checks report SKIPPED rather than failing. Its pure static
     * helpers do not touch an Android API, so with android.jar present they run right here.
     */
    static void testMediaSinkPureLogic() throws Exception {
        Class<?> sink;
        try {
            sink = Class.forName("com.watch.osmo.store.MediaSink");
        } catch (Throwable t) {
            info("MediaSink pure-logic checks SKIPPED (" + t.getClass().getSimpleName()
                    + " — Android build output not on the classpath)");
            return;
        }
        Method name3 = sink.getMethod("buildDisplayName", String.class, String.class, boolean.class);
        Method ext = sink.getMethod("extensionOf", String.class);
        Method mime = sink.getMethod("mimeTypeFor", String.class, boolean.class);
        Method room = sink.getMethod("hasRoom", long.class, long.class, long.class);
        Method unique = sink.getMethod("uniqueName", String.class, Set.class);
        Method trunc = sink.getMethod("truncateKeepingExtension", String.class, int.class);

        checkEq("sink/keeps the camera basename", "DJI_0001_D.MP4",
                name3.invoke(null, "DCIM/DJI_001/DJI_0001_D.MP4", ".mp4", true));
        checkEq("sink/adds the fallback extension when missing", "x.jpg",
                name3.invoke(null, "MISC/THM/x", ".jpg", false));
        checkEq("sink/replaces characters the storage layer rejects", "a_b_c_.jpg",
                name3.invoke(null, "DCIM/a:b*c?.jpg", ".jpg", false));
        checkEq("sink/strips directories, including Windows separators", "evil.jpg",
                name3.invoke(null, "DCIM/..\\..\\evil.jpg", ".jpg", false));
        checkEq("sink/drops the trailing dot that FAT rewrites", "trailing.jpg",
                name3.invoke(null, "DCIM/trailing. ", ".jpg", false));
        checkEq("sink/empties out to a usable stem, never a blank name", "photo.jpg",
                name3.invoke(null, "DCIM/", ".jpg", false));

        checkEq("sink/extension is lower-cased", "mp4", ext.invoke(null, "A.MP4"));
        checkEq("sink/no extension is empty", "", ext.invoke(null, "noext"));
        checkEq("sink/last extension wins", "c", ext.invoke(null, "a.b.c"));
        checkEq("sink/trailing dot is not an extension", "", ext.invoke(null, "a."));

        checkEq("sink/mime mp4", "video/mp4", mime.invoke(null, "a.mp4", true));
        checkEq("sink/mime mov", "video/quicktime", mime.invoke(null, "a.mov", true));
        checkEq("sink/mime jpeg", "image/jpeg", mime.invoke(null, "a.jpg", false));
        checkEq("sink/mime heic", "image/heic", mime.invoke(null, "a.heic", false));

        check("sink/room: fits inside the reserve", (Boolean) room.invoke(null, 100L, 1000L, 100L));
        check("sink/room: exactly at the reserve limit", (Boolean) room.invoke(null, 900L, 1000L, 100L));
        check("sink/room: one byte over the reserve",
                !(Boolean) room.invoke(null, 901L, 1000L, 100L));
        check("sink/room: full volume refuses", !(Boolean) room.invoke(null, 10L, 0L, 0L));
        check("sink/room: nothing to write always fits", (Boolean) room.invoke(null, 0L, 0L, 0L));
        check("sink/room: unmeasurable free space does not block",
                (Boolean) room.invoke(null, 10L, -1L, 0L));
        check("sink/room: negative reserve behaves as zero",
                (Boolean) room.invoke(null, 10L, 1000L, -5L));

        checkEq("sink/collision becomes a (1) suffix", "a (1).jpg",
                unique.invoke(null, "a.jpg", new java.util.HashSet<String>(Arrays.asList("a.jpg"))));
        checkEq("sink/second collision becomes (2)", "a (2).jpg",
                unique.invoke(null, "a.jpg", new java.util.HashSet<String>(
                        Arrays.asList("a.jpg", "a (1).jpg"))));
        checkEq("sink/no collision leaves the name alone", "b.jpg",
                unique.invoke(null, "b.jpg", new java.util.HashSet<String>()));

        String longName = repeat('A', 126) + ".MP4";
        String cut = (String) trunc.invoke(null, longName, 120);
        checkEq("sink/long name truncated to the limit", 120, cut.length());
        check("sink/truncation keeps the extension case", cut.endsWith(".MP4"), cut);
    }

    static String repeat(char c, int n) {
        char[] buf = new char[n];
        Arrays.fill(buf, c);
        return new String(buf);
    }

    // ------------------------------------------------------------- plumbing ---

    static volatile MockCamera mockRef;

    static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteTree(k);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
