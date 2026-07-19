package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * legitimate provenance</b> — the hard constraint the gap sets (the codebase carries ~250 bare {@code IGn}
 * origin-labels: "the IG44 drill", "the IG56 E3 hunt", "IG13 residual" — all legitimate history, not to be
 * touched). The mechanical signal is <b>present-tense-OPEN framing</b>: a deleted gap cited as {@code pending IGn}
 * / {@code awaits IGn} / {@code future work … IGn} / {@code not yet … IGn} / {@code TODO … IGn} is a CURRENT-STATE
 * claim that misleads (it reads as open work), whereas a bare descriptive cite is history. So the guard scans the
 * non-Markdown source ({@code .kt}/{@code .java}/{@code .gradle}) for a <b>case-sensitive</b> {@code IGn} token
 * (a lowercase {@code ig44.vcv} drill namespace is not a reference), and fails only when: the {@code n} is not a
 * LIVE ledger row ({@code docs/INTERPRETER-GAPS.md}), the line frames it as OPEN, AND the line is not explicitly
 * historical ({@code no longer}, {@code once …}, {@code was IGn}, {@code graduated}, {@code IGn -> IFm}). Repointing
 * a graduated gap's <i>bare</i> cite to its {@code IFn} is a doc-discipline practice, deliberately not mechanized
 * here — that would ban the provenance the gap protects.
 */
public class StaleGapReferenceGuardTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path LEDGER = ROOT.resolve("docs/INTERPRETER-GAPS.md");

    /** Source trees whose comments cite interpreter gaps — the interpreter engine, its `:adapter` differential/perf
     *  estate, and the CLI. (The kit modules — rulekit/modelkit/edit/core — carry dmkit gaps, not interpreter IGs.)
     *  These are declared as the `staleGapGuardTest` task's inputs (cli/build.gradle), the docGuardTest pattern. */
    private static final List<Path> SRC_ROOTS = List.of(
            ROOT.resolve("interpreter/src"), ROOT.resolve("adapter/src"), ROOT.resolve("cli/src"));

    private static final List<Path> GRADLE_FILES = List.of(
            ROOT.resolve("interpreter/build.gradle"), ROOT.resolve("adapter/build.gradle"),
            ROOT.resolve("cli/build.gradle"));

    /** This file names deleted gaps as examples in its own doc — exempt it from its own scan. */
    private static final String SELF = "StaleGapReferenceGuardTest.java";

    /** A gap reference: uppercase {@code IG} + digits, word-bounded. Case-sensitive by construction. */
    private static final Pattern IG = Pattern.compile("\\bIG(\\d+)\\b");

    /** Present-tense-OPEN framing — a deleted gap here is a misleading current-state claim, not history. */
    private static final Pattern OPEN = Pattern.compile(
            "\\b(pending|awaits?|await|not yet|not-yet|future work|blocked on|TODO)\\b");

    /** An explicit historical form that turns a present-tense-looking line back into provenance (so it is OK). */
    private static final Pattern HISTORICAL = Pattern.compile(
            "no longer|\\bonce\\b|was IG\\d+|graduated|formerly|retired|closes? IG\\d+|IG\\d+ ?-?[>→] ?IF");

    @Test
    public void noPresentTenseReferenceToADeletedGap() throws IOException {
        Set<Integer> live = liveGapRows();
        assertTrue(live.size() >= 15, () -> "sanity: expected many live gap rows, found " + live.size()
                + " — did the ledger move or its row format change?");

        List<String> violations = new ArrayList<>();
        for (Path root : SRC_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(StaleGapReferenceGuardTest::isScannable).forEach(p -> scan(p, live, violations));
            }
        }
        for (Path g : GRADLE_FILES) {
            if (Files.isRegularFile(g)) {
                scan(g, live, violations);
            }
        }

        assertTrue(violations.isEmpty(), () -> "present-tense references to DELETED interpreter gaps — repoint to the "
                + "graduated IFn (or the current IG), or use an explicit historical form (`IGn -> IFm`, `was IGn`, "
                + "`graduated from IGn`, or a `[IGn]` tool label):\n  " + String.join("\n  ", violations));
    }

    /** The live gaps are the ledger's own row headers (`- **IGn — …`). A graduated gap's row is deleted, so it
     *  drops out of this set and every bare cite of it becomes a violation. */
    private Set<Integer> liveGapRows() throws IOException {
        Pattern row = Pattern.compile("^- \\*\\*IG(\\d+) ");
        Set<Integer> live = new HashSet<>();
        for (String line : Files.readAllLines(LEDGER)) {
            Matcher m = row.matcher(line);
            if (m.find()) {
                live.add(Integer.parseInt(m.group(1)));
            }
        }
        return live;
    }

    private static boolean isScannable(Path p) {
        String s = p.toString();
        if (s.endsWith(SELF)) {
            return false;
        }
        return s.endsWith(".kt") || s.endsWith(".java") || s.endsWith(".gradle");
    }

    private void scan(Path p, Set<Integer> live, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            // A defect needs present-tense-OPEN framing that is NOT explicitly re-cast as history.
            if (!OPEN.matcher(line).find() || HISTORICAL.matcher(line).find()) {
                continue;
            }
            Matcher m = IG.matcher(line);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (live.contains(n)) {
                    continue;
                }
                violations.add(ROOT.relativize(p) + ":" + (i + 1) + "  IG" + n + "  |  " + line.trim());
            }
        }
    }
}
