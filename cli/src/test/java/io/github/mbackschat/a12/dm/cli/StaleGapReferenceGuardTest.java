package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * IF141 (graduated from IG82) — the DELETED-GAP reference guard. Executable comments outlive the gaps they
 * cite: an {@code IGn} that graduated to an {@code IFn} keeps being read as an open gap by every maintainer who
 * meets the comment, and only an expensive manual audit catches it (six {@code IG80} perf comments, {@code IG20},
 * {@code IG21}, {@code Dec}-by-identity, and deleted {@code IG63/IG64} all outlived their graduation this way).
 *
 * <p>This makes current-state vs. historical references <b>mechanically distinguishable without banning
 * legitimate provenance</b> — the hard constraint the gap sets. The codebase carries <b>286</b> bare {@code IGn}
 * origin-label occurrences naming <b>81</b> distinct ids (measured 2026-08-16 over this guard's own scope: every
 * module source root plus every module build file): "the IG44 drill", "the IG56 E3 hunt", "IG13 residual" — all
 * legitimate history, not to be touched.
 *
 * <p>The mechanical signal is <b>present-tense-OPEN framing</b>: a deleted gap cited as {@code pending IGn}
 * / {@code awaits IGn} / {@code future work … IGn} / {@code not yet … IGn} / {@code TODO … IGn} is a CURRENT-STATE
 * claim that misleads (it reads as open work), whereas a bare descriptive cite is history. The guard derives every
 * repository module source root plus every module build file, then delegates to
 * {@link StaleGapReferenceClassifier}: block/consecutive-line comments are read as logical comments, and each
 * reference is classified locally so another reference's historical/open wording cannot leak across it. Repointing
 * a graduated gap's <i>bare</i> cite to its {@code IFn} is a doc-discipline practice, deliberately not mechanized
 * here — that would ban the provenance the gap protects.
 */
public class StaleGapReferenceGuardTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path LEDGER = ROOT.resolve("docs/INTERPRETER-GAPS.md");

    /** The classifier and this guard contain deliberate deleted-gap canaries; they cannot scan themselves. */
    private static final String SELF_PREFIX = "StaleGapReference";
    private static final Pattern GAP_ROW = Pattern.compile("^- \\*\\*IG(\\d+) — ");

    @Test
    public void noPresentTenseReferenceToADeletedGap() throws IOException {
        Set<Integer> live = liveGapRows();

        List<String> violations = new ArrayList<>();
        for (Path root : sourceRoots()) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(StaleGapReferenceGuardTest::isScannable).forEach(p -> scan(p, live, violations));
            }
        }
        for (Path buildFile : buildFiles()) {
            scan(buildFile, live, violations);
        }

        assertTrue(violations.isEmpty(), () -> "present-tense references to DELETED interpreter gaps — repoint to the "
                + "graduated IFn (or the current IG), or use an explicit historical form (`IGn -> IFm`, `was IGn`, "
                + "or `graduated from IGn`):\n  " + String.join("\n  ", violations));
    }

    @Test
    public void sourceDiscoveryIncludesSharedKitModules() throws IOException {
        Set<Path> roots = new HashSet<>(sourceRoots());
        assertTrue(roots.contains(ROOT.resolve("core/src")), "core can carry interpreter contracts and must be scanned");
        assertTrue(roots.contains(ROOT.resolve("rulekit/src")), "rulekit source must stay in the derived scan");
        assertTrue(roots.contains(ROOT.resolve("modelkit/src")), "modelkit source must stay in the derived scan");
        assertTrue(roots.contains(ROOT.resolve("edit/src")), "edit source must stay in the derived scan");
    }

    /** The live gaps are the ledger's own row headers (`- **IGn — …`). A graduated gap's row is deleted, so it
     *  drops out of this set and every bare cite of it becomes a violation. */
    private Set<Integer> liveGapRows() throws IOException {
        assertTrue(Files.isRegularFile(LEDGER), "missing interpreter gap ledger: " + LEDGER);
        List<String> lines = Files.readAllLines(LEDGER);
        assertTrue(!lines.isEmpty() && lines.getFirst().equals("# interpreter — gaps"),
                "unexpected interpreter gap ledger title/shape: " + LEDGER);
        assertTrue(lines.contains("## 1. Open")
                        && lines.stream().anyMatch(line -> line.startsWith("## 2. "))
                        && lines.stream().anyMatch(line -> line.startsWith("## 3. ")),
                "interpreter gap ledger must retain its three lifecycle sections");

        Set<Integer> live = new HashSet<>();
        List<String> malformed = new ArrayList<>();
        List<Integer> duplicates = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = GAP_ROW.matcher(line);
            if (line.startsWith("- **IG")) {
                if (!matcher.find()) {
                    malformed.add(line);
                } else {
                    int gap = Integer.parseInt(matcher.group(1));
                    if (!live.add(gap)) {
                        duplicates.add(gap);
                    }
                }
            }
        }
        assertTrue(malformed.isEmpty(), () -> "malformed interpreter gap row(s):\n  " + String.join("\n  ", malformed));
        assertTrue(duplicates.isEmpty(), () -> "duplicate interpreter gap ids: " + duplicates);
        return live;
    }

    private static boolean isScannable(Path p) {
        String s = p.toString();
        if (p.getFileName().toString().startsWith(SELF_PREFIX)) {
            return false;
        }
        return s.endsWith(".kt") || s.endsWith(".java") || s.endsWith(".gradle") || s.endsWith(".gradle.kts");
    }

    private static List<Path> sourceRoots() throws IOException {
        List<Path> roots = new ArrayList<>();
        if (Files.isDirectory(ROOT.resolve("src"))) {
            roots.add(ROOT.resolve("src"));
        }
        try (Stream<Path> children = Files.list(ROOT)) {
            children.filter(Files::isDirectory)
                    .map(child -> child.resolve("src"))
                    .filter(Files::isDirectory)
                    .forEach(roots::add);
        }
        return roots.stream().distinct().sorted().toList();
    }

    private static List<Path> buildFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String name : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
            Path file = ROOT.resolve(name);
            if (Files.isRegularFile(file)) {
                files.add(file);
            }
        }
        try (Stream<Path> children = Files.list(ROOT)) {
            children.filter(Files::isDirectory).forEach(child -> {
                for (String name : List.of("build.gradle", "build.gradle.kts")) {
                    Path file = child.resolve(name);
                    if (Files.isRegularFile(file)) {
                        files.add(file);
                    }
                }
            });
        }
        return files.stream().distinct().sorted(Comparator.naturalOrder()).toList();
    }

    private void scan(Path p, Set<Integer> live, List<String> violations) {
        String source;
        try {
            source = Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        StaleGapReferenceClassifier.deletedOpenReferenceHits(source, live).forEach(hit ->
                violations.add(ROOT.relativize(p) + ":" + hit.line() + "  IG" + hit.gap() + "  |  " + hit.excerpt()));
    }
}
