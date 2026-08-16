package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * <b>A corpus count stated in a source comment must say WHEN it was measured.</b> This is the one place a
 * derived figure has no keeper: {@code docs/CLAUDE.recompute.md} registers figures stated in <i>docs</i>, and a
 * count living in a {@code *.java}/{@code *.kt} comment is a source file, so nothing recomputes it, no guard
 * fails on it, and it drifts silently for as long as nobody happens to look.
 *
 * <p><b>Measured, which is why this is a guard rather than a preference.</b> A sweep on 2026-08-16 found
 * <b>13</b> such figures and <b>nine</b> of them were stale — a relative-link count reading 1 513 against a
 * live 2 432 <i>while contradicting its own floor comment twelve lines below</i>; {@code ## §9 supplement}
 * reading 25 against 32; the §-mirror reading ~110 against 144; API-FINDINGS reading ~100 findings and ~113
 * citing sites against 125 and 147; lock citations reading ~640 against 460 distinct classes in 837
 * occurrences; the doc tree reading ~50 and ~55 against 66. Every one had been correct when written.
 *
 * <p><b>What this guard claims, and what it does not.</b> It enforces <i>dating</i>, never <i>correctness</i>:
 * no rule here can know what an arbitrary comment's number was supposed to count. That limit is the point
 * rather than a weakness, because the deeper defect the sweep exposed was not drift but the <b>unnamed
 * quantity</b> — not one stale figure said whether it counted occurrences, distinct ids, or files, or over what
 * scope, so a first attempt to check them produced four confident comparisons that were all worthless (raw
 * greps returning 295/77/110/427 against claims of ~250/~40/~66/~20, each counting a different thing; one of
 * those claims was <i>exactly right</i>). A dated figure is a checkable claim. An undated one is not a fact.
 *
 * <p><b>Scope is deliberately narrow, because a guard that fails honest content gets disabled.</b> Only
 * <i>corpus-inventory</i> nouns count — things enumerated over this repository. Fixture sizes ({@code 10 rows},
 * {@code 50 rows}) are not corpus counts and are excluded by construction: they were 30 of the 41 hits under a
 * looser noun list, and every one of them was honest. The surviving rule matched <b>13</b> figures repo-wide
 * (2026-08-16), which is a population small enough that every exemption can carry a real reason.
 *
 * <p><b>The date must sit in the SAME comment block</b>, not within a fixed line window. A ±3-line window was
 * tried first and mis-reported a correctly dated figure whose date sat four lines up. More importantly, the
 * sweep that motivated this guard <b>missed every single-line {@code /** … *}{@code /} comment</b> because it
 * filtered for lines beginning with {@code *} — which is exactly how three stale figures survived inside a file
 * whose other two were being fixed at the time. Both comment shapes are scanned here for that reason.
 *
 * <p>Rides {@code referentGuardTest}, which already declares the repo-wide source surface as its inputs.
 */
class DerivedFigureDatingGuardTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    /**
     * Nouns denoting something enumerated over this repository. A count of these is derived and rots; a count
     * of fixture rows or test cases does not. Kept as a closed list rather than a heuristic so the guard's
     * denominator is readable, and so widening it is a deliberate edit with its own measurement.
     */
    private static final String CORPUS_NOUNS =
            "entries|docs|sites|findings|citations|occurrences|operators|headings|ledgers|verbs|snippets";

    /**
     * A count over the corpus: an optional tilde, a number of two or more digits (or one grouped with thin
     * spaces), then one of the nouns above — optionally qualified by {@code distinct}/{@code live}/{@code bare}.
     *
     * <p>Deliberately described rather than illustrated. A literal example of the shape here would make this
     * guard report <b>itself</b>, and the first run did exactly that; the repo's discipline is to write around
     * the shape (as {@code ReferentDriftGuardTest} and {@code QuotedTitleCitationRatchetTest} both do) rather
     * than to exclude the guard's own file, which would be a hole of precisely the kind it exists to close.
     */
    private static final Pattern FIGURE = Pattern.compile(
            "(?:~\\s*)?\\b(?:\\d{1,3}(?:[  ]\\d{3})+|\\d{2,})\\s+(?:distinct\\s+|live\\s+|bare\\s+)?(?:"
                    + CORPUS_NOUNS + ")\\b");

    /** An ISO measurement date. Any of the estate's forms — bare, parenthesized, or "measured YYYY-MM-DD". */
    private static final Pattern MEASURED_ON = Pattern.compile("20\\d\\d-\\d\\d-\\d\\d");

    /**
     * Figures that are NOT live-corpus claims, each with the reason it is exempt. Asserted <b>fully used</b>
     * below, so an entry cannot outlive the line it excuses and quietly widen the rule.
     */
    private static final Map<String, String> NOT_A_LIVE_CORPUS_CLAIM = Map.of(
            "NotFoundRefusalTest.java", "historical narrative: \"the bare stderr string 11 of 13 verbs returned "
                    + "BEFORE\" describes the pre-fix behaviour, so there is no live count to re-derive",
            "Operators.kt", "a source-code fact, not a doc-corpus one: `FunctionOp` has 53 entries and the "
                    + "compiler keeps that honest, unlike a count over markdown that nothing recomputes");

    private static final int MIN_FIGURES = 8;

    @Test
    void everyCorpusFigureInASourceCommentCarriesItsMeasurementDate() throws IOException {
        List<String> undated = new ArrayList<>();
        Set<String> exemptionsUsed = new TreeSet<>();
        int figures = 0;

        for (Path file : sources()) {
            List<String> lines = List.of(Files.readString(file).split("\n", -1));
            for (int i = 0; i < lines.size(); i++) {
                if (!isComment(lines.get(i)) || !FIGURE.matcher(lines.get(i)).find()) {
                    continue;
                }
                figures++;
                String name = file.getFileName().toString();
                if (NOT_A_LIVE_CORPUS_CLAIM.containsKey(name)) {
                    exemptionsUsed.add(name);
                    continue;
                }
                if (!MEASURED_ON.matcher(block(lines, i)).find()) {
                    Matcher m = FIGURE.matcher(lines.get(i));
                    m.find();
                    undated.add(name + ":" + (i + 1) + "  \"" + m.group().trim() + "\"");
                }
            }
        }

        int parsed = figures;
        assertTrue(parsed >= MIN_FIGURES,
                () -> "the rule matched only " + parsed + " corpus figures (floor " + MIN_FIGURES + ") — the "
                        + "pattern or the walk stopped working, and this guard is checking nothing");

        assertEquals(NOT_A_LIVE_CORPUS_CLAIM.keySet(), exemptionsUsed,
                "an exemption no longer matches any figure, so its written reason can no longer be checked — "
                        + "remove the row rather than leaving a permission nobody reviews");

        assertTrue(undated.isEmpty(), () -> "corpus figure(s) in a source comment with no measurement date in "
                + "the same comment block. A derived count in source has no keeper — the recompute register "
                + "covers docs, and no guard recomputes a comment — so an undated one rots invisibly (nine of "
                + "thirteen were stale when this landed). State what it counts and when it was measured, e.g. "
                + "\"460 distinct classes across 837 occurrences (2026-08-16)\":\n  "
                + String.join("\n  ", undated));
    }

    /**
     * Does-fire proof for the rule, over fabricated text rather than the live tree, so it keeps proving the
     * rule after the estate is clean. The negative cases matter as much: a guard that fired on a fixture size
     * or on a dated figure would fail honest content, and would be switched off rather than satisfied.
     */
    @Test
    void theRuleFiresOnAnUndatedCorpusFigureAndOnNothingElse() {
        List<String> dated = List.of("    /** the tree carries 66 docs (2026-08-16). */");
        List<String> undated = List.of("    /** the tree carries 66 docs. */");
        List<String> farDate = List.of(
                "    /**", "     * Measured 2026-08-16 through the guard's own rule.", "     *",
                "     * <p>The record holds 299 entries today.", "     */");
        List<String> fixture = List.of("    /** builds 50 rows and 4 cases. */");
        List<String> code = List.of("    int docs = countOf(66);");

        assertTrue(fires(undated).size() == 1, () -> "an undated corpus figure must fire, got " + fires(undated));
        assertTrue(fires(dated).isEmpty(), "a figure dated on its own line must not fire");
        assertTrue(fires(farDate).isEmpty(),
                "the date may sit anywhere in the SAME comment block — a fixed line window mis-reported exactly "
                        + "this shape, which is why the block is the unit");
        assertTrue(fires(fixture).isEmpty(),
                "a fixture size is not a corpus count; firing here is how a guard earns being disabled");
        assertTrue(fires(code).isEmpty(), "a figure in CODE is not a prose claim and is not in scope");
    }

    /** The rule, over one file's lines, returning the 1-based lines that would be reported. */
    private static List<Integer> fires(List<String> lines) {
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (isComment(lines.get(i)) && FIGURE.matcher(lines.get(i)).find()
                    && !MEASURED_ON.matcher(block(lines, i)).find()) {
                hits.add(i + 1);
            }
        }
        return hits;
    }

    /**
     * The contiguous comment block containing {@code index}. Walking outward — rather than taking a fixed
     * window — is what lets a block state its date once in a preamble and carry several figures beneath it.
     */
    private static String block(List<String> lines, int index) {
        int from = index;
        while (from > 0 && isComment(lines.get(from - 1))) {
            from--;
        }
        int to = index;
        while (to + 1 < lines.size() && isComment(lines.get(to + 1))) {
            to++;
        }
        return String.join("\n", lines.subList(from, to + 1));
    }

    /**
     * Both comment shapes, and the second one is the whole reason this guard exists: a sweep that filtered for
     * lines starting with {@code *} saw only multi-line continuations, so every single-line {@code /** … *}{@code /}
     * was structurally invisible to it and three stale figures survived in a file being edited at the time.
     */
    private static boolean isComment(String line) {
        String s = line.stripLeading();
        return s.startsWith("*") || s.startsWith("//") || s.startsWith("/*");
    }

    private static final Set<String> SKIPPED = Set.of("build", ".git", ".gradle", "node_modules");

    private static List<Path> sources() throws IOException {
        Set<Path> found = new LinkedHashSet<>();
        Files.walkFileTree(ROOT, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return SKIPPED.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (name.endsWith(".java") || name.endsWith(".kt")) {
                    try {
                        found.add(file.toRealPath());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return List.copyOf(found);
    }
}
