package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * <b>The operator index is generated, and this proves the committed page still matches its sources.</b>
 *
 * <p><b>The retrieval defect it closes.</b> {@code docs/CLAUDE.md} tells a reader to find a fact by grepping
 * headings first. For an operator that fails, badly and measurably: of the <b>106</b> catalog operators named
 * anywhere in KERNEL-FINDINGS or KERNEL-SEMANTICS, only 34 appear in <i>any</i> heading — <b>72 are
 * heading-blind</b>, and they are the most discussed ones ({@code Sum}, {@code FieldFilled},
 * {@code DifferenceInDays}, {@code RoundDown}, {@code NumberOfDifferentValues}). The cause is not sloppiness:
 * these headings state a <i>claim</i> ("an invalid index in any joined SIBLING group"), which is what makes
 * them worth reading, and a claim rarely names its operands. {@code SEMANTICS-MAP.md} is no fallback — it is
 * keyed by the 14-section taxonomy and contains none of those five names.
 *
 * <p><b>Why generated rather than written.</b> A hand-curated index of 106 operators over ~200 entries is the
 * exact drift class the addressing work of 2026-08-15/16 was about: it would be correct on the day it was
 * written and silently wrong from the next entry on, with nothing to detect it. So the page is a pure function
 * of the catalog and the two docs, and this guard recomputes it. The fixer is
 * {@code ./build.sh :cli:dumpOperatorIndex}, mirroring {@code dumpKitApi} over the API goldens.
 *
 * <p><b>What makes the addresses durable.</b> Rows cite {@code KF<n>} anchors and KERNEL-SEMANTICS slugs, both
 * already enforced by {@link DocAnchorResolveGuardTest} — so amending a heading, which happens constantly here,
 * moves nothing. Indexing keys on an operator name appearing <b>inside a code span</b>, this estate's
 * convention for naming one; matching bare words would sweep in every English "sum" and "true".
 */
class OperatorIndexGuardTest {

    private static final Path INDEX = Path.of("../docs/OPERATOR-INDEX.md");

    private static final Path CATALOG = Path.of("../rulekit/src/main/resources/catalog/operators.json");

    /**
     * Sanity floor, asserted on what the ROWS are built from — the catalog carries 110 operators (2026-08-16).
     *
     * <p><i>Corrected 2026-08-16:</i> briefly read 106, which is a DIFFERENT quantity — the operators this index
     * actually lists, i.e. those named anywhere in the kernel docs (34 in a heading + 72 heading-blind). The floor
     * below is asserted on the catalog, so the subset count was the wrong number on this line. A cold review caught
     * it; the correction had introduced it, which is the unnamed-quantity defect committed inside its own fix.
     */
    private static final int MIN_OPERATORS = 90;

    /** Sanity floor: KERNEL-FINDINGS contributes ~188 addressable records. */
    private static final int MIN_FINDINGS_ENTRIES = 150;

    /** Sanity floor: KERNEL-SEMANTICS contributes its 14 canonical sections. */
    private static final int MIN_SEMANTICS_ENTRIES = 12;

    @Test
    void theCommittedIndexMatchesWhatItsSourcesGenerate() throws IOException {
        Path docs = docs();
        List<String> operators = OperatorIndex.catalogOperatorIds(resolve(CATALOG));
        OperatorIndex index = OperatorIndex.from(docs);

        assertTrue(operators.size() >= MIN_OPERATORS,
                () -> "read only " + operators.size() + " operator ids from the catalog (floor " + MIN_OPERATORS
                        + ") — the rows would be built from almost nothing");
        // Per SOURCE, never a combined floor: KERNEL-FINDINGS alone clears any total, so dropping the
        // KERNEL-SEMANTICS half entirely regenerated a thinner page and stayed green (measured 2026-08-16).
        long findings = index.entries().stream().filter(e -> e.doc().startsWith("KERNEL-FINDINGS")).count();
        long semantics = index.entries().stream().filter(e -> e.doc().startsWith("KERNEL-SEMANTICS")).count();
        assertTrue(findings >= MIN_FINDINGS_ENTRIES,
                () -> "parsed only " + findings + " KERNEL-FINDINGS entries (floor " + MIN_FINDINGS_ENTRIES + ")");
        assertTrue(semantics >= MIN_SEMANTICS_ENTRIES,
                () -> "parsed only " + semantics + " KERNEL-SEMANTICS sections (floor " + MIN_SEMANTICS_ENTRIES
                        + ") — small enough to vanish under a combined floor, which is what happened");

        String generated = index.render(operators);
        // Resolved off the docs DIRECTORY, never by file existence: the fixer writes a file that is not there
        // yet, and an existence-keyed resolver would hand it a path relative to the wrong working directory.
        Path committed = docs().resolve(INDEX.getFileName());
        if (Boolean.getBoolean("operatorIndex.fix")) {
            Files.writeString(committed, generated);
            return;
        }

        assertTrue(Files.exists(committed),
                () -> committed + " is missing — regenerate it with `./build.sh :cli:dumpOperatorIndex`");
        assertEquals(generated, Files.readString(committed),
                "docs/OPERATOR-INDEX.md no longer matches what the catalog and the kernel docs generate. It is "
                + "derived, never hand-edited: regenerate it with `./build.sh :cli:dumpOperatorIndex` and commit "
                + "the result alongside the change that moved it");
    }

    /**
     * The index is worth having only if it beats the grep it replaces, so that is asserted rather than claimed:
     * the five worst heading-blind operators must each resolve to at least one entry here.
     */
    @Test
    void theIndexReachesTheOperatorsAHeadingGrepCannot() throws IOException {
        OperatorIndex index = OperatorIndex.from(docs());
        for (String operator : List.of("Sum", "FieldFilled", "DifferenceInDays", "RoundDown",
                "NumberOfDifferentValues")) {
            assertTrue(!index.mentioning(operator).isEmpty(),
                    () -> operator + " resolves to no entry — either the docs stopped discussing it, or the "
                            + "code-span matching broke and every row below is thinner than it looks");
        }
    }

    /** Does-fire proof for the matching rule itself: a code span counts, the same word in prose does not. */
    @Test
    void onlyACodeSpanMentionCounts() {
        OperatorIndex fabricated = new OperatorIndex(List.of(
                new OperatorIndex.Entry("D.md", "a", "A", "The `Sum` operator over a group."),
                new OperatorIndex.Entry("D.md", "b", "B", "The sum of the parts, and Sum as prose."),
                new OperatorIndex.Entry("D.md", "c", "C", "Beware `SumOfSquares`, a different operator."),
                new OperatorIndex.Entry("D.md", "d", "D", "Written inline: `Sum([Qty]) > 0` fires per row.")));

        assertEquals(List.of("a", "d"),
                fabricated.mentioning("Sum").stream().map(OperatorIndex.Entry::address).toList(),
                "only the code-span mention counts: prose must not match, and a longer identifier that merely "
                + "starts with the operator's name must not either. Entry `d` is the DISCRIMINATING case — an "
                + "operator named inside a larger expression span. Without it, narrowing the matcher to "
                + "whole-span equality passes every test while thinning the real page by five rows");
    }

    private static Path docs() {
        return Files.isDirectory(Path.of("../docs")) ? Path.of("../docs") : Path.of("docs");
    }

    private static Path resolve(Path relative) {
        return Files.exists(relative) ? relative : Path.of(relative.toString().replaceFirst("^\\.\\./", ""));
    }
}
