package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * <b>A rename keeps every link valid while quietly reassigning what the surrounding prose asserts.</b> The
 * performance doc was split on 2026-08-15: the stable method stayed put and the dated runs moved to
 * {@code INTERPRETER-PERFORMANCE-RESULTS.md}. Seventeen sentences across a findings doc, a spec, the project
 * instructions, a guard's own javadoc and an interpreter source comment went on pointing dated evidence at the
 * pre-split home; a hand sweep for the same claim in wording this rule cannot match added the method doc's own
 * preamble, which still described itself as holding the evidence it had just handed over. Every one of them still
 * <i>resolved</i>, which is exactly why nothing caught them: {@link DocsIndexGuardTest} and
 * {@link DocAnchorResolveGuardTest} verify that a pointer lands somewhere, never that the sentence around it is
 * still true, and {@link DocsEvidencePlacementGuardTest} checks the structural arrangement rather than the prose
 * describing it. This is referent drift, and it is invisible to the whole link-checking family.
 *
 * <p><b>The signal.</b> A pre-split reference is only wrong when it is making an <i>evidence-home</i> claim, so
 * naming the method doc is not itself a defect — it is the doc's real name and dozens of correct sentences use it.
 * The guard therefore fires on co-occurrence: the pre-split name within {@code WINDOW} characters of one of the
 * three phrases that mark an evidence-home claim ({@code Checkpoint <year>}, {@code results log},
 * {@code dated evidence}). It is a <b>review trigger, not a truth checker</b> — no guard can read a sentence —
 * and the standard it must therefore meet is zero false positives, because a guard that fails honest content gets
 * disabled, which is worse than no guard (the reasoning {@link KernelDocsBasisContractTest} records).
 *
 * <p><b>Three normalizations, each forced by a measured case.</b> Markdown links resolve to their target before
 * matching, because {@code [<old name>](<new name>)} is <i>correct</i> content whose label is the old name, and a
 * label-matching rule flags three verified-correct sites. Matching is case-insensitive and spans a character
 * window rather than a line, because the two references in {@link DocsEvidencePlacementGuardTest}'s own javadoc
 * are capitalized differently and split across lines. And a pair is <b>exonerated when the results doc is named
 * in the same window</b>: the defect is naming <i>only</i> the pre-split home as the evidence home, so a sentence
 * that names both halves has already made the distinction. That case is not hypothetical — it is what the correct
 * repair of two of these sites produces ("Method: X; dated evidence: Y"), and without the exoneration the guard
 * would reject the very wording it exists to demand.
 *
 * <p>The exoneration is <b>local to the window, never file-global</b>, which is the property that keeps it from
 * gutting the guard: every findings doc now names the results doc dozens of times, so a file-wide test would
 * exonerate every site in it and the guard would pass while checking nothing.
 * {@link #theGuardStillFiresWhenTheResultsDocIsNamedFarAway} is the control for exactly that mutant.
 *
 * <p><b>Why {@code WINDOW} is 150.</b> Measured on the pre-fix tree: the hit set is 16 for a window of 60–105,
 * 17 for 110–185, and grows again from 190. The 17-hit plateau is the correct answer — its one extra site over
 * the narrow reading is a genuine defect, and every site gained above it is correct content (a {@code METHOD}
 * path constant, an assertion message deliberately naming the method doc, a self-reference inside the method doc
 * itself). 150 sits in the middle of that plateau rather than on either edge.
 *
 * <p><b>This guard cannot exclude itself from its own scan.</b> An exclusion would be a hole of exactly the kind
 * the guard exists to close, so the pre-split name appears nowhere in this file as a literal: it is assembled at
 * runtime and every fixture is built from that assembly. Do not paste the name in, in source or in javadoc.
 */
public class ReferentDriftGuardTest {

    /** Canonical, because the walk canonicalizes every file it reports and the two must be relativizable. */
    private static final Path ROOT = realPath(Path.of("..").toAbsolutePath().normalize());

    /** Assembled, never written: a literal here would make the scan consume its own fixtures and never go green. */
    private static final String MOVED_HOME = "INTERPRETER-" + "PERFORMANCE";

    /** The pre-split name, excluding the results doc's own name, which legitimately starts with it. */
    private static final Pattern PRE_SPLIT =
            Pattern.compile(Pattern.quote(MOVED_HOME) + "(?!-RESULTS)", Pattern.CASE_INSENSITIVE);

    /** The three phrases that mark a sentence as claiming where dated evidence lives. */
    private static final Pattern EVIDENCE_CLAIM =
            Pattern.compile("Checkpoint\\s+\\d{4}|results log|dated evidence", Pattern.CASE_INSENSITIVE);

    /** The correct home. Naming it in the same window means the sentence has already made the distinction. */
    private static final Pattern RESULTS_DOC_NAME =
            Pattern.compile(Pattern.quote(MOVED_HOME + "-RESULTS"), Pattern.CASE_INSENSITIVE);

    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]\\n]*)]\\(([^)\\s]+)[^)]*\\)");

    /** See the class javadoc: the middle of the measured 110–185 plateau. */
    private static final int WINDOW = 150;

    private static final Path RESULTS_DOC = ROOT.resolve("docs/" + MOVED_HOME + "-RESULTS.md");

    private static final Set<String> SCANNED_SUFFIXES =
            Set.of(".md", ".java", ".kt", ".gradle", ".gradle.kts", ".sh");

    /** One walk shared by both scanning tests; see {@link #walkOnce}. */
    private static List<Path> scanned;

    @Test
    public void noProseNamesThePreSplitHomeOfAMovedCheckpoint() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : scannedFiles()) {
            String text;
            try {
                text = Files.readString(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (Hit hit : evidenceHomeClaims(text)) {
                violations.add(ROOT.relativize(file) + ":" + hit.line() + "  |  " + hit.excerpt());
            }
        }

        assertTrue(violations.isEmpty(), () -> "prose points dated evidence at the PRE-SPLIT home of the "
                + "performance doc. The runs moved to " + RESULTS_DOC.getFileName() + " and the method doc kept the "
                + "stable contract, so a sentence naming the method doc as the home of a checkpoint, a results log "
                + "or dated evidence is now false — even though its link still resolves. Repoint the sentence, or "
                + "reword it if it is describing the method rather than the evidence:\n  "
                + String.join("\n  ", violations));
    }

    /**
     * The scan reaches every surface that cites the moved doc, named rather than counted.
     *
     * <p>A bare count floor cannot tell a healthy scan from one that has silently lost a whole file kind, which is
     * the failure this guard would be blind to: prose lives in markdown outside {@code docs/} (module instructions,
     * the public READMEs), in javadoc, in Kotlin comments and in shell scripts, and dropping any one of those
     * suffixes would leave the guard green while checking less. Both counts and members are asserted.
     */
    @Test
    public void theScanCoversEveryProseSurfaceThatCitesTheMovedDoc() throws IOException {
        List<Path> scanned = scannedFiles();
        Set<Path> members = new LinkedHashSet<>(scanned);

        for (String required : List.of(
                "docs/INTERPRETER-FINDINGS.md",
                "docs/DOC-DISCIPLINE.md",
                "docs/INTERPRETER-SPEC.md",
                "docs/" + MOVED_HOME + ".md",
                "CLAUDE.md",
                "README.md",
                "interpreter/CLAUDE.md",
                "cli/src/test/java/io/github/mbackschat/a12/dm/cli/DocsEvidencePlacementGuardTest.java",
                "interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/eval/CascadeOverlay.kt",
                "scripts/perf-anchor.sh")) {
            assertTrue(members.contains(ROOT.resolve(required)),
                    () -> required + " cites the moved doc and must stay inside the scan");
        }

        assertTrue(!members.contains(ROOT.resolve("AGENTS.md")),
                "root AGENTS.md is a symlink to CLAUDE.md, so the scan must report the real file — a violation "
                        + "reported at the symlink sends a maintainer to edit a path that carries no content");

        long markdown = scanned.stream().filter(p -> p.toString().endsWith(".md")).count();
        long source = scanned.size() - markdown;
        assertTrue(markdown >= 50, () -> "only " + markdown + " markdown files scanned — the prose surface is "
                + "larger than that, so the walk has lost a tree");
        assertTrue(source >= 500, () -> "only " + source + " source files scanned — the module source surface is "
                + "larger than that, so the walk has lost a tree");
    }

    /** A does-fire control: without it, a rule that matches nothing at all passes the guard above forever. */
    @Test
    public void theGuardFiresOnAPreSplitReference() {
        String drifted = "The measurements are in " + MOVED_HOME + "'s results log, not here.";
        List<Hit> hits = evidenceHomeClaims(drifted);
        assertEquals(1, hits.size(), () -> "the rule must fire on a bare pre-split evidence-home claim: " + drifted);
    }

    /**
     * A does-not-fire control over the form the link normalization exists for.
     *
     * <p>Three verified-correct sites write the old name as a link <i>label</i> over a correct target. Without
     * this control the normalization is untested and those three false positives return the moment it regresses.
     */
    @Test
    public void theGuardAcceptsALinkWhoseLabelIsTheOldName() {
        String correct = "See [" + MOVED_HOME + "](" + MOVED_HOME + "-RESULTS.md)'s results log for the numbers.";
        assertTrue(evidenceHomeClaims(correct).isEmpty(),
                () -> "a link whose LABEL is the old name but whose TARGET is the results doc is correct "
                        + "content, and flagging it is how a guard gets disabled: " + correct);
    }

    /** A does-not-fire control for the repair this guard demands: a sentence that names BOTH halves is correct. */
    @Test
    public void theGuardAcceptsASentenceThatNamesBothHalves() {
        String repaired = "Method: " + MOVED_HOME + ".md; dated evidence: " + MOVED_HOME + "-RESULTS.md.";
        assertTrue(evidenceHomeClaims(repaired).isEmpty(),
                () -> "naming the method doc and the results doc in one sentence IS the correct repair, and a "
                        + "guard that rejects its own remedy is unsatisfiable: " + repaired);
    }

    /**
     * The exoneration above is local to the window, and this is the control that holds it there.
     *
     * <p>Every findings doc now names the results doc many times over. If the exoneration were tested against the
     * whole file rather than the neighbourhood, each of those mentions would clear every drifted sentence in the
     * same file, and the guard would report nothing while appearing to work — the exact vacuity a repaired estate
     * makes easy to ship. Here the correct name sits far outside the window and must not rescue the claim.
     */
    @Test
    public void theGuardStillFiresWhenTheResultsDocIsNamedFarAway() {
        String drifted = "The measurements are in " + MOVED_HOME + "'s results log."
                + " ".repeat(4 * WINDOW)
                + "Unrelated later prose that happens to mention " + MOVED_HOME + "-RESULTS.md.";
        assertEquals(1, evidenceHomeClaims(drifted).size(),
                () -> "a mention of the results doc elsewhere in the file must not exonerate a drifted sentence — "
                        + "file-global exoneration would silence the guard on every repaired doc");
    }

    /** A second does-not-fire control: the pre-split name alone, with no evidence-home claim beside it. */
    @Test
    public void theGuardAcceptsTheMethodDocNamedWithoutAnEvidenceClaim() {
        String correct = "The harness contract and the gate mechanics are specified in " + MOVED_HOME + ".md.";
        assertTrue(evidenceHomeClaims(correct).isEmpty(),
                () -> "naming the method doc is not a defect — it is the doc's real name: " + correct);
    }

    /** One flagged co-occurrence: the 1-based line of the pre-split reference, plus surrounding text to act on. */
    private record Hit(int line, String excerpt) {}

    /**
     * Every place a pre-split reference sits within {@link #WINDOW} characters of an evidence-home claim.
     *
     * <p>Markdown links collapse to their target first, so a correct target cannot be flagged for carrying a stale
     * label. Line numbers are then computed on the collapsed text, which shifts them by the labels removed above;
     * that is accepted because the excerpt, not the number, is what locates the sentence.
     */
    private static List<Hit> evidenceHomeClaims(String rawText) {
        String text = resolveLinksToTargets(rawText);
        List<int[]> references = spans(PRE_SPLIT, text);
        List<int[]> claims = spans(EVIDENCE_CLAIM, text);
        List<int[]> correctHome = spans(RESULTS_DOC_NAME, text);

        List<Hit> hits = new ArrayList<>();
        for (int[] reference : references) {
            for (int[] claim : claims) {
                int lo = Math.min(reference[0], claim[0]);
                int hi = Math.max(reference[1], claim[1]);
                if (hi - lo > WINDOW || fitsInWindow(correctHome, lo, hi)) {
                    continue;
                }
                hits.add(new Hit(lineOf(text, reference[0]),
                        text.substring(Math.max(0, lo - 25), Math.min(text.length(), hi + 25))
                                .replaceAll("\\s+", " ").trim()));
                break;
            }
        }
        return hits;
    }

    /** Whether any of {@code spans} joins {@code [lo, hi]} without the whole neighbourhood exceeding the window. */
    private static boolean fitsInWindow(List<int[]> spans, int lo, int hi) {
        return spans.stream()
                .anyMatch(span -> Math.max(hi, span[1]) - Math.min(lo, span[0]) <= WINDOW);
    }

    private static String resolveLinksToTargets(String text) {
        Matcher matcher = MARKDOWN_LINK.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(2)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static List<int[]> spans(Pattern pattern, String text) {
        List<int[]> spans = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            spans.add(new int[] {matcher.start(), matcher.end()});
        }
        return spans;
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Every prose and source file in the repository, canonicalized so one file is one entry.
     *
     * <p>Symlinked instruction files ({@code AGENTS.md} beside {@code CLAUDE.md}, in the root and in
     * {@code interpreter/}) are one identity. Resolving to the real path rather than keeping whichever the walk
     * met first does two jobs at once: a site is reported once, and it is reported at the file a maintainer can
     * actually edit — the first cut of this guard named {@code AGENTS.md}, which is a symlink. The results doc
     * itself is excluded: it is the new home, so it names the old one legitimately throughout.
     */
    private static List<Path> scannedFiles() throws IOException {
        if (scanned == null) {
            scanned = walkOnce();
        }
        return scanned;
    }

    /**
     * Memoized, and pruned at the DIRECTORY level rather than filtered afterwards.
     *
     * <p>Both matter, measured: a {@code Files.walk} that rejects {@code build/} in its filter still descends
     * every generated tree first, and each of the two walking tests paid for it separately — 10.7 s and 12.1 s
     * against a 2.4 s always-on lane. Pruning the subtree and sharing one walk is what lets this guard ride the
     * cheap lane instead of the checkpoint's.
     */
    private static List<Path> walkOnce() throws IOException {
        Set<Path> canonical = new LinkedHashSet<>();
        List<Path> found = new ArrayList<>();
        for (Path file : RepoWalk.trackedFiles(ROOT)) {
            if (isScannable(file)) {
                found.add(file);
            }
        }
        found.sort(Comparator.naturalOrder());
        for (Path file : found) {
            canonical.add(realPath(file));
        }
        return List.copyOf(canonical);
    }

    private static Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isScannable(Path p) {
        if (p.equals(RESULTS_DOC)) {
            return false;
        }
        String name = p.getFileName().toString();
        return SCANNED_SUFFIXES.stream().anyMatch(name::endsWith);
    }
}
