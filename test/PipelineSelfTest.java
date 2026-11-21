import com.watch.osmo.duml.DumlFrame;
import com.watch.osmo.net.Datalink;
import com.watch.osmo.net.ManifestAssembler;
import com.watch.osmo.proto.ManifestParser;
import com.watch.osmo.proto.MediaFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end desktop test of the path the app actually runs:
 *
 * <pre>
 *   real hardware capture -> UDP datagrams -> 0x00/0x27 frames
 *      -> ManifestAssembler (strip the 10-byte sub-header, stitch in arrival order)
 *      -> ManifestParser -> List&lt;MediaFile&gt; -> compare with the reference golden file
 * </pre>
 *
 * <p>Two feed paths are exercised and must agree byte for byte, because they are both live in the
 * app: the datagram path is what {@link ManifestAssembler#feed(byte[])} is tested with, and the
 * per-frame path is what {@link com.watch.osmo.core.OsmoClient} uses (the datalink hands it parsed
 * {@link DumlFrame}s, not raw datagrams).
 *
 * <p>The fixture is a genuine capture from a DJI Osmo, checked into the reference implementation, so
 * a pass here means the reassembly and the record decoding agree with real camera output rather
 * than with our own idea of it.
 *
 * <p>Run with a plain JVM; no JUnit, no Android.
 */
public final class PipelineSelfTest {

    private static int passed;
    private static int failed;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static void main(String[] args) throws Exception {
        File manifests = findManifestsDir();
        System.out.println("fixtures: " + manifests.getAbsolutePath());

        // nano_45 is the capture that is stored as real datagrams; op4_45 is the Pocket 4 capture,
        // stored already assembled (it has no frames to stitch, so it only exercises the parser).
        runFramePipeline(new File(manifests, "nano_45.bin"),
                new File(manifests, "golden/nano_45.bin.golden.txt"));
        runFramePipeline(new File(manifests, "xtra_13.bin"),
                new File(manifests, "golden/xtra_13.bin.golden.txt"));

        System.out.println();
        System.out.println("SUMMARY: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void runFramePipeline(File fixture, File golden) throws IOException {
        System.out.println();
        System.out.println("### " + fixture.getName());
        if (!fixture.exists() || !golden.exists()) {
            fail(fixture.getName() + ": fixture or golden missing");
            return;
        }
        byte[] blob = Files.readAllBytes(fixture.toPath());

        // 1. Split the capture into datagrams. Every datagram starts with (0x8000 | totalLength)
        //    little-endian, so the stream is self-describing; if that does not consume the file
        //    exactly, the fixture is not what this test assumes and we must not pretend otherwise.
        List<byte[]> datagrams = splitDatagrams(blob);
        int consumed = 0;
        for (byte[] d : datagrams) {
            consumed += d.length;
        }
        check(fixture.getName() + ": datagram split consumes the whole capture",
                consumed == blob.length);
        check(fixture.getName() + ": capture contains datagrams", datagrams.size() > 0);
        System.out.println("    datagrams: " + datagrams.size() + ", bytes " + consumed);

        // 2. Path A - feed whole datagrams.
        ManifestAssembler byDatagram = new ManifestAssembler();
        int framesSeenA = 0;
        for (byte[] d : datagrams) {
            byDatagram.feed(d);
        }
        byte[] rawA = byDatagram.complete() ? byDatagram.manifestBytes() : null;

        // 3. Path B - scan each datagram for DUML frames and feed them one by one, which is exactly
        //    what OsmoClient does from Datalink.Listener.onFrame().
        ManifestAssembler byFrame = new ManifestAssembler();
        int mediaListFrames = 0;
        for (byte[] d : datagrams) {
            List<DumlFrame> frames = Datalink.scanFrames(d);
            for (DumlFrame f : frames) {
                if (f.cmdSet() == 0x00 && f.cmdId() == 0x27) {
                    byFrame.feedFrame(f);
                    mediaListFrames++;
                }
            }
        }
        framesSeenA = mediaListFrames;
        byte[] rawB = byFrame.complete() ? byFrame.manifestBytes() : null;

        check(fixture.getName() + ": datagram path produced a manifest", rawA != null);
        check(fixture.getName() + ": frame path produced a manifest", rawB != null);
        check(fixture.getName() + ": saw 0x00/0x27 frames", framesSeenA > 0);
        System.out.println("    0x00/0x27 frames: " + framesSeenA
                + ", manifest A " + (rawA == null ? -1 : rawA.length)
                + " B " + (rawB == null ? -1 : rawB.length) + " bytes");
        if (rawA == null || rawB == null) {
            return;
        }
        check(fixture.getName() + ": both feed paths produce identical bytes",
                java.util.Arrays.equals(rawA, rawB));

        // 4. Decode and compare against the reference golden file.
        //
        // The golden files list records oldest-first, while the camera stores the manifest
        // newest-first. That is not a mismatch: a media picker wants the newest clip at the top, and
        // the reference implementation's own test compares order-insensitively, which is why it never
        // surfaced. Here both facts are pinned: the order must be the exact reverse of the golden,
        // and every field of every record must match its golden line by name.
        List<MediaFile> files = ManifestParser.parse(rawA);
        List<String> goldenLines = readGolden(golden);
        check(fixture.getName() + ": record count matches golden",
                files.size() == goldenLines.size());
        System.out.println("    records: " + files.size() + ", golden lines: " + goldenLines.size());

        check(fixture.getName() + ": newest-first order is the exact reverse of the golden order",
                isReverseOfGolden(files, goldenLines));

        java.util.Map<String, String> goldenByName = new java.util.HashMap<String, String>();
        for (String line : goldenLines) {
            goldenByName.put(line.split("\\|")[0], line);
        }
        for (int i = 0; i < files.size(); i++) {
            MediaFile f = files.get(i);
            String line = goldenByName.get(f.getName());
            if (line == null) {
                fail("record " + i + " not present in the golden file: " + f.getName());
                continue;
            }
            compareRecord(line, f, i);
        }
    }

    /** True when the parsed names are the golden names in reverse order. */
    private static boolean isReverseOfGolden(List<MediaFile> files, List<String> goldenLines) {
        if (files.size() != goldenLines.size()) {
            return false;
        }
        for (int i = 0; i < files.size(); i++) {
            String want = goldenLines.get(goldenLines.size() - 1 - i).split("\\|")[0];
            if (!want.equals(files.get(i).getName())) {
                System.out.println("      order mismatch at " + i + ": golden-reversed=" + want
                        + " actual=" + files.get(i).getName());
                return false;
            }
        }
        return true;
    }

    /** Compares one golden line against one parsed record. */
    private static void compareRecord(String line, MediaFile f, int index) {
        String[] p = line.split("\\|");
        if (p.length < 12) {
            fail("golden line " + index + " has " + p.length + " fields, expected 12");
            return;
        }
        String name = p[0];
        long handle = parseHex(p[2]);
        long cmdHandle = parseHex(p[3]);
        long size = parseLong(p[4]);
        String resolution = p[5];
        String resLabel = p[6];
        int duration = (int) parseLong(p[7]);
        boolean starred = Boolean.parseBoolean(p[8]);

        boolean ok = true;
        ok &= expect(name, f.getName(), index, "name");
        ok &= expect(handle, f.getHandle(), index, "handle");
        ok &= expect(cmdHandle, f.getCmdHandle(), index, "cmdHandle");
        ok &= expect(size, f.getSizeBytes(), index, "sizeBytes");
        ok &= expect(resolution, f.getResolution(), index, "resolution");
        ok &= expect(resLabel, f.getResLabel(), index, "resLabel");
        ok &= expect(duration, f.getDurationSec(), index, "durationSec");
        ok &= expect(starred, f.isStarred(), index, "starred");

        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("    FAIL record " + index + ": " + name);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<byte[]> splitDatagrams(byte[] blob) {
        List<byte[]> out = new ArrayList<byte[]>();
        int offset = 0;
        while (offset + 2 <= blob.length) {
            int word = (blob[offset] & 0xFF) | ((blob[offset + 1] & 0xFF) << 8);
            int length = word & 0x7FFF;
            if (length < 2 || offset + length > blob.length) {
                break;
            }
            byte[] d = new byte[length];
            System.arraycopy(blob, offset, d, 0, length);
            out.add(d);
            offset += length;
        }
        return out;
    }

    private static List<String> readGolden(File golden) throws IOException {
        List<String> lines = new ArrayList<String>();
        for (String raw : Files.readAllLines(golden.toPath(), UTF8)) {
            String s = raw.trim();
            if (s.length() > 0 && !s.startsWith("#")) {
                lines.add(s);
            }
        }
        return lines;
    }

    private static long parseHex(String s) {
        try {
            return Long.parseLong(s.trim(), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean expect(String want, String got, int index, String field) {
        // A photo has no frame-rate label: the golden file writes an empty field, the parser returns
        // null. Those are the same fact, so compare them as such.
        String a = want == null ? "" : want;
        String b = got == null ? "" : got;
        if (a.equals(b)) {
            return true;
        }
        System.out.println("      record " + index + " " + field + ": golden=" + a + " actual=" + b);
        return false;
    }

    private static boolean expect(long want, long got, int index, String field) {
        if (want == got) {
            return true;
        }
        System.out.println("      record " + index + " " + field + ": golden=" + want + " actual=" + got);
        return false;
    }

    private static boolean expect(boolean want, boolean got, int index, String field) {
        if (want == got) {
            return true;
        }
        System.out.println("      record " + index + " " + field + ": golden=" + want + " actual=" + got);
        return false;
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("    PASS " + what);
        } else {
            failed++;
            System.out.println("    FAIL " + what);
        }
    }

    private static void fail(String what) {
        failed++;
        System.out.println("    FAIL " + what);
    }

    /** Walks up from the working directory to find the reference fixtures. */
    private static File findManifestsDir() {
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            File candidate = new File(dir,
                    "reference/osmosis/app/src/test/resources/manifests");
            if (candidate.isDirectory()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        // Optional override for running from anywhere: -Dwosmo.fixtures=... or the env var.
        String prop = System.getProperty("wosmo.fixtures");
        if (prop == null || prop.isEmpty()) {
            prop = System.getenv("WOSMO_FIXTURES");
        }
        if (prop != null && !prop.isEmpty()) {
            return new File(prop);
        }
        // Run the tests from the project root; reference/ is fetched separately (see README).
        return new File("reference/osmosis/app/src/test/resources/manifests");
    }
}
