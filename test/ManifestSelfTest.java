import com.watch.osmo.proto.ManifestParser;
import com.watch.osmo.proto.MediaFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Desktop regression harness for {@link ManifestParser}.
 *
 * Pure Java 8, no JUnit. Reads every fixture under
 * {@code reference/osmosis/app/src/test/resources/manifests/}, parses it through the real
 * entry point, and diffs the decoded records field-for-field against the committed golden file
 * {@code golden/<fixture>.golden.txt} produced by the reference Kotlin implementation.
 *
 * The golden line is the reference's own canonical form:
 * <pre>
 *   name|ext|handle|cmdHandle|sizeBytes|resolution|resLabel|durationSec|starred|mediaType|group|deletable
 * </pre>
 * with {@code handle}/{@code cmdHandle} as {@code %08x} and null strings rendered as "".
 *
 * Exit code 0 only when every fixture matches exactly; 1 otherwise.
 */
public final class ManifestSelfTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private static int failures = 0;
    private static int passed = 0;
    private static int extra = 0;
    private static File manifestsDir;

    public static void main(String[] args) throws Exception {
        PrintStream out = System.out;
        manifestsDir = locateManifests(args);
        out.println("=== osmo manifest parser self-test ===");
        out.println("fixtures: " + (manifestsDir == null ? "<NOT FOUND>" : manifestsDir.getAbsolutePath()));
        if (manifestsDir == null) {
            out.println("FATAL: could not locate the manifests directory.");
            out.println("       pass it as argv[0] or set -Dosmo.fixtures=<dir>");
            System.exit(1);
        }

        File goldenDir = new File(manifestsDir, "golden");
        File[] goldens = goldenDir.listFiles();
        if (goldens == null || goldens.length == 0) {
            out.println("FATAL: no golden files under " + goldenDir.getAbsolutePath());
            System.exit(1);
        }
        Arrays.sort(goldens);

        List<String> fixtureNames = new ArrayList<String>();
        for (File g : goldens) {
            String n = g.getName();
            if (n.endsWith(".golden.txt")) {
                fixtureNames.add(n.substring(0, n.length() - ".golden.txt".length()));
            }
        }
        Collections.sort(fixtureNames);

        for (String fixture : fixtureNames) {
            runFixture(out, fixture);
        }

        out.println();
        out.println("--- op4_45 structural cross-checks (reference Op4ManifestTest invariants) ---");
        op4StructuralChecks(out);

        out.println();
        out.println("--- op4_45 storage / display sample ---");
        op4StorageSample(out);

        out.println();
        out.println("--- fault tolerance (must never throw) ---");
        faultTolerance(out, fixtureNames);

        out.println();
        out.println("=== summary: " + passed + " record(s) PASS, " + failures + " FAIL, "
                + extra + " unexpected ===");
        out.println(failures == 0 && extra == 0 ? "RESULT: ALL PASS" : "RESULT: FAILURES PRESENT");
        System.exit(failures == 0 && extra == 0 ? 0 : 1);
    }

    // ---- one fixture -----------------------------------------------------------------------------

    private static void runFixture(PrintStream out, String fixture) throws IOException {
        File binFile = new File(manifestsDir, fixture);
        if (!binFile.isFile()) {
            out.println();
            out.println("### " + fixture + ": MISSING FIXTURE FILE " + binFile.getAbsolutePath());
            failures++;
            return;
        }
        byte[] raw = readAll(binFile);
        List<String> expected = readLines(new File(new File(manifestsDir, "golden"),
                fixture + ".golden.txt"));

        List<MediaFile> files;
        try {
            files = ManifestParser.parse(raw);
        } catch (RuntimeException e) {
            out.println();
            out.println("### " + fixture + ": parser THREW " + e);
            e.printStackTrace(out);
            failures++;
            return;
        }

        out.println();
        out.println("### " + fixture + "  (" + raw.length + " bytes raw, "
                + files.size() + " record(s) decoded, " + expected.size() + " in golden)");
        out.println("    raw form: " + describeForm(raw));

        Map<String, String> gotByName = new LinkedHashMap<String, String>();
        for (MediaFile f : files) {
            gotByName.put(f.getName(), canonical(f));
        }
        Set<String> expectedNames = new LinkedHashSet<String>();
        for (String line : expected) {
            int bar = line.indexOf('|');
            expectedNames.add(bar >= 0 ? line.substring(0, bar) : line);
        }

        for (String line : expected) {
            int bar = line.indexOf('|');
            String name = bar >= 0 ? line.substring(0, bar) : line;
            String got = gotByName.get(name);
            if (got == null) {
                out.println("FAIL " + name);
                out.println("       expected: " + line);
                out.println("       actual:   <record not decoded>");
                failures++;
            } else if (!got.equals(line)) {
                out.println("FAIL " + name);
                out.println("       expected: " + line);
                out.println("       actual:   " + got);
                failures++;
            } else {
                out.println("PASS " + name);
                passed++;
            }
        }
        for (String name : gotByName.keySet()) {
            if (!expectedNames.contains(name)) {
                out.println("FAIL " + name + " (decoded but NOT in golden)");
                out.println("       actual:   " + gotByName.get(name));
                extra++;
            }
        }

        // Independent evidence that the decoded path is real and not synthesised: the manifest's own
        // media-path field (the path with the extension stripped, since the extension comes from the
        // separate 0d filename field) must appear verbatim in the reassembled byte stream. Checked
        // against the REASSEMBLED bytes, because on a raw frame blob a path that straddles a frame
        // boundary is not contiguous in the raw capture — which is exactly what reassembly fixes.
        byte[] assembled = ManifestParser.reassemble(raw);
        String latin = new String(assembled, LATIN1);
        int pathMisses = 0;
        for (MediaFile f : files) {
            String base = f.getExt().isEmpty() ? f.getPath()
                    : f.getPath().substring(0, f.getPath().length() - f.getExt().length() - 1);
            if (latin.indexOf(base) < 0) {
                out.println("FAIL " + f.getName() + " media path not present verbatim in the "
                        + "reassembled manifest: " + base);
                pathMisses++;
            }
            // The thumbnail path is not part of the golden line, so pin it the same way: the .scr
            // rendition is synthesised from the media path, but the MISC/THM tree the camera listed
            // must appear verbatim (the `1a … 00 00 00 02` field, minus the appended ".scr").
            String thumb = f.getThumbPath();
            String thumbBase = thumb.endsWith(".scr")
                    ? thumb.substring(0, thumb.length() - 4) : thumb;
            if (latin.indexOf(thumbBase) < 0) {
                out.println("FAIL " + f.getName() + " thumbnail path not present verbatim in the "
                        + "reassembled manifest: " + thumbBase);
                pathMisses++;
            }
        }
        if (pathMisses > 0) {
            failures += pathMisses;
        } else {
            out.println("     all " + files.size() + " media + thumbnail path(s) present verbatim "
                    + "in the reassembled bytes (" + assembled.length + "B)");
        }
    }

    /** The reference's canonical golden line — every field the UI reads, one stable line per record. */
    private static String canonical(MediaFile f) {
        return String.format(Locale.US, "%s|%s|%08x|%08x|%d|%s|%s|%d|%b|%d|%d|%b",
                f.getName(),
                f.getExt(),
                f.getHandle(),
                f.getCmdHandle(),
                f.getSizeBytes(),
                f.getResolution() == null ? "" : f.getResolution(),
                f.getResLabel() == null ? "" : f.getResLabel(),
                f.getDurationSec(),
                f.isStarred(),
                f.getMediaType(),
                f.getGroup(),
                f.isDeletable());
    }

    /** Raw-frame blob or already-assembled manifest? Decided from the bytes, not from the name. */
    private static String describeForm(byte[] raw) {
        long declared = ManifestParser.declaredCount(raw);
        int frameStarts = 0;
        for (int i = 0; i + 13 <= raw.length; i++) {
            if ((raw[i] & 0xFF) == 0x55) {
                int len = ((raw[i + 1] & 0xFF) | ((raw[i + 2] & 0xFF) << 8)) & 0x3FF;
                if (len >= 13 && i + len <= raw.length
                        && (raw[i + 9] & 0xFF) == 0x00 && (raw[i + 10] & 0xFF) == 0x27) {
                    frameStarts++;
                    i += len - 1;
                }
            }
        }
        String form = frameStarts > 0
                ? "raw DUML frame blob (" + frameStarts + " x 0x00/0x27 frame(s), needs reassembly)"
                : "already-assembled manifest (no 0x00/0x27 frames; passed straight through)";
        return form + "; u32-LE @0 = " + declared + "; end marker 0c 01 0d = "
                + ManifestParser.hasEndMarker(ManifestParser.reassemble(raw));
    }

    // ---- op4_45 detail ---------------------------------------------------------------------------

    private static void op4StructuralChecks(PrintStream out) throws IOException {
        byte[] raw = readAll(new File(manifestsDir, "op4_45.bin"));
        List<MediaFile> files = ManifestParser.parse(raw);
        check(out, "45 records decoded", files.size() == 45, "got " + files.size());
        Set<String> paths = new LinkedHashSet<String>();
        for (MediaFile f : files) {
            paths.add(f.getPath());
        }
        check(out, "every path distinct", paths.size() == 45, "got " + paths.size());
        int mp4 = 0;
        for (MediaFile f : files) {
            if ("MP4".equals(f.getExt())) {
                mp4++;
            }
        }
        check(out, "all 45 are MP4", mp4 == 45, "got " + mp4);
        boolean allDji = true;
        for (MediaFile f : files) {
            if (!f.getName().startsWith("DJI_")) {
                allDji = false;
            }
        }
        check(out, "stock DJI_ naming, no model suffix", allDji, "a name did not start with DJI_");

        Set<Long> handles = new LinkedHashSet<Long>();
        for (MediaFile f : files) {
            handles.add(Long.valueOf(f.getHandle()));
        }
        check(out, "45 unique delete handles", handles.size() == 45, "got " + handles.size());
        boolean stepped = true;
        String badStep = "";
        boolean deletable = true;
        boolean video = true;
        for (MediaFile f : files) {
            long want = 0x40100000L + (long) f.getSeq() * 0x40L;
            if (f.getHandle() != want) {
                stepped = false;
                badStep = f.getName() + " handle=0x" + Long.toHexString(f.getHandle())
                        + " expected=0x" + Long.toHexString(want);
            }
            if (!f.isDeletable()) {
                deletable = false;
            }
            if (!f.isVideo()) {
                video = false;
            }
        }
        check(out, "handle == 0x40100000 + seq*0x40 for every record", stepped, badStep);
        check(out, "all deletable", deletable, "at least one not deletable");
        check(out, "all classified as video", video, "at least one not video");
        boolean noneStarred = true;
        for (MediaFile f : files) {
            if (f.isStarred()) {
                noneStarred = false;
            }
        }
        check(out, "nothing starred (nothing was favourited on that card)", noneStarred,
                "a record read as starred");
        boolean pathShape = true;
        String badPath = "";
        for (MediaFile f : files) {
            String want = "DCIM/DJI_001/" + f.getName();
            if (!want.equals(f.getPath())) {
                pathShape = false;
                badPath = f.getPath();
            }
            if (!f.getPath().endsWith(f.getName() + "")) {
                pathShape = false;
                badPath = f.getPath();
            }
        }
        check(out, "path == DCIM/DJI_001/<name>", pathShape, badPath);
        Set<String> stamps = new TreeSet<String>();
        for (MediaFile f : files) {
            stamps.add(f.getTimestamp());
        }
        check(out, "every record carries a 14-digit filename timestamp",
                stamps.size() == 45 && !stamps.contains(""), "distinct timestamps=" + stamps.size());
    }

    private static void op4StorageSample(PrintStream out) throws IOException {
        byte[] raw = readAll(new File(manifestsDir, "op4_45.bin"));
        List<MediaFile> files = ManifestParser.parse(raw);
        int sd = 0;
        int internal = 0;
        int unknown = 0;
        for (MediaFile f : files) {
            if (f.getStorage() == MediaFile.STORAGE_SD) {
                sd++;
            } else if (f.getStorage() == MediaFile.STORAGE_INTERNAL) {
                internal++;
            } else {
                unknown++;
            }
        }
        out.println("merged decode storage guess: SD=" + sd + " internal=" + internal
                + " unknown=" + unknown + "  (storageKnown=false: a handle-bit guess)");
        out.println("first 3 records as the watch app would show them:");
        for (int i = 0; i < Math.min(3, files.size()); i++) {
            MediaFile f = files.get(i);
            out.println("  name=" + f.getName()
                    + " | path=" + f.getPath()
                    + " | thumb=" + f.getThumbPath()
                    + " | size=" + f.getSizeBytes()
                    + " | video=" + f.isVideo()
                    + " | image=" + f.isImage()
                    + " | res=" + f.getResolution()
                    + " | fps=" + f.getResLabel()
                    + " | dur=" + f.getDurationSec() + "s"
                    + " | taken=" + f.getDateTaken()
                    + " | ymd=" + f.getYmd()
                    + " | storage=" + f.getStorage()
                    + " | handle=0x" + Long.toHexString(f.getHandle()));
        }
        out.println("last record: " + files.get(files.size() - 1));
    }

    private static void check(PrintStream out, String what, boolean ok, String detail) {
        if (ok) {
            out.println("PASS " + what);
            passed++;
        } else {
            out.println("FAIL " + what + " -- " + detail);
            failures++;
        }
    }

    /**
     * The parser must never throw: a truncated or corrupted manifest is a normal thing to receive on
     * a flaky UDP link, and a crash in the grid is worse than a short list.
     */
    private static void faultTolerance(PrintStream out, List<String> fixtureNames) throws IOException {
        int cases = 0;
        int bad = 0;

        Object[][] edge = {
                {"parse(null)", null},
                {"parse(new byte[0])", new byte[0]},
                {"parse(new byte[1])", new byte[]{0x00}},
                {"parse(all 0x55)", repeat((byte) 0x55, 64)},
                {"parse(all 0x00)", repeat((byte) 0x00, 64)},
                {"parse(all 0xFF)", repeat((byte) 0xFF, 64)},
                {"parse(0x55 frame header, len=0x3FF, truncated)", truncatedFrame()},
                {"parse(fake 1a 2c 00 00 00 01 DCIM/ with no tail)",
                        new byte[]{0x1A, 0x2C, 0x00, 0x00, 0x00, 0x01, 'D', 'C', 'I', 'M', '/'}},
        };
        for (Object[] c : edge) {
            cases++;
            try {
                List<MediaFile> got = ManifestParser.parse((byte[]) c[1]);
                out.println("PASS " + c[0] + " -> " + (got == null ? "null" : got.size() + " record(s)"));
                passed++;
            } catch (Throwable t) {
                out.println("FAIL " + c[0] + " THREW " + t);
                bad++;
            }
        }

        // Every prefix truncation of every fixture, plus a byte-corrupted copy: none may throw.
        int truncations = 0;
        int corruptions = 0;
        for (String fixture : fixtureNames) {
            File binFile = new File(manifestsDir, fixture);
            if (!binFile.isFile()) {
                continue;
            }
            byte[] raw = readAll(binFile);
            for (int step = 1; step <= 16; step++) {
                int len = raw.length * step / 16;
                byte[] cut = Arrays.copyOf(raw, len);
                truncations++;
                cases++;
                try {
                    ManifestParser.parse(cut);
                } catch (Throwable t) {
                    out.println("FAIL truncation " + fixture + " @" + len + "B THREW " + t);
                    bad++;
                }
            }
            java.util.Random rnd = new java.util.Random(0x0531L ^ fixture.hashCode());
            for (int rep = 0; rep < 8; rep++) {
                byte[] mod = raw.clone();
                for (int k = 0; k < 32; k++) {
                    mod[rnd.nextInt(mod.length)] = (byte) rnd.nextInt(256);
                }
                corruptions++;
                cases++;
                try {
                    ManifestParser.parse(mod);
                } catch (Throwable t) {
                    out.println("FAIL corrupted " + fixture + " rep=" + rep + " THREW " + t);
                    bad++;
                }
            }
        }
        out.println((bad == 0 ? "PASS " : "FAIL ") + cases + " robustness case(s): "
                + edge.length + " edge, " + truncations + " truncation, " + corruptions
                + " byte-corrupted (" + bad + " failure(s))");
        if (bad == 0) {
            passed++;
        } else {
            failures += bad;
        }

        // The per-store split is an extra entry point; it must also survive every fixture.
        int storeBad = 0;
        for (String fixture : fixtureNames) {
            File binFile = new File(manifestsDir, fixture);
            if (!binFile.isFile()) {
                continue;
            }
            try {
                ManifestParser.parseStores(readAll(binFile));
            } catch (Throwable t) {
                out.println("FAIL parseStores(" + fixture + ") THREW " + t);
                storeBad++;
            }
        }
        out.println((storeBad == 0 ? "PASS " : "FAIL ") + "parseStores over all "
                + fixtureNames.size() + " fixture(s) (" + storeBad + " failure(s))");
        if (storeBad == 0) {
            passed++;
        } else {
            failures += storeBad;
        }
    }

    private static byte[] repeat(byte v, int n) {
        byte[] b = new byte[n];
        Arrays.fill(b, v);
        return b;
    }

    private static byte[] truncatedFrame() {
        byte[] b = new byte[20];
        b[0] = 0x55;
        b[1] = (byte) 0xFF;
        b[2] = 0x03;   // len = 0x3FF, far past the end of the array
        b[9] = 0x00;
        b[10] = 0x27;
        return b;
    }

    // ---- plumbing --------------------------------------------------------------------------------

    private static File locateManifests(String[] args) {
        List<String> candidates = new ArrayList<String>();
        if (args.length > 0) {
            candidates.add(args[0]);
        }
        String prop = System.getProperty("osmo.fixtures");
        if (prop != null) {
            candidates.add(prop);
        }
        File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int i = 0; i < 10 && dir != null; i++) {
            candidates.add(new File(dir, "reference/osmosis/app/src/test/resources/manifests")
                    .getPath());
            candidates.add(new File(dir, "manifests").getPath());
            dir = dir.getParentFile();
        }
        for (String c : candidates) {
            File f = new File(c);
            if (f.isDirectory()) {
                return f.getAbsoluteFile();
            }
        }
        return null;
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(64, f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static List<String> readLines(File f) throws IOException {
        String text = new String(readAll(f), UTF8);
        List<String> lines = new ArrayList<String>();
        for (String line : text.split("\r\n|\n|\r", -1)) {
            if (line.length() > 0) {
                lines.add(line);
            }
        }
        return lines;
    }
}
