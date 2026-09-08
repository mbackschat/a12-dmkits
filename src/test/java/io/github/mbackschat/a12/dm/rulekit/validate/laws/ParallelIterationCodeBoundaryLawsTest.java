package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — what separates {@code MVK_PARALLEL_ITERATION_INVALID} from
 * {@code MVK_NEG_CONDITION_IN_ITERATION}, since an unguarded negative under iteration draws the latter.
 *
 * <p>The parallel code has exactly ONE measured row in the estate — a bare {@code NoFieldFilled} over two
 * parallel-iterated groups — and the ledger explains it semantically ("the condition could fire with no
 * filled instance"). That row holds BOTH the pure-negative operator and the two-group parallelism constant,
 * so "unguarded negative" and "unguarded negative in a PARALLEL iteration" fit it equally.
 *
 * <p>These rows vary the parallelism alone: the same operator over one repeatable group, then over two.
 */
class ParallelIterationCodeBoundaryLawsTest {

    /**
     * Two repeatable groups sharing ONE index field name, which is what makes them parallel-iterable at all:
     * without it the kernel cannot pair rows and a cross-group operand list is `MVK_NO_WILDCARD` instead.
     * (Measured while building this class — the first fixture declared no index and never reached either
     * code under test.) `Items` carries a spare field so the one-group row can span two operands too.
     */
    private static final String MODEL = DocumentModelDraft.named("parallel-iteration-boundary").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .group("Items", i -> i.repeatable(20).indexField("RowKey")
                            .field("RowKey", FieldKind.STRING)
                            .field("Sku", FieldKind.STRING)
                            .field("Spare", FieldKind.STRING))
                    .group("Notes", n -> n.repeatable(20).indexField("RowKey")
                            .field("RowKey", FieldKind.STRING)
                            .field("Text", FieldKind.STRING)))
            .build().toJson();

    /**
     * The same two groups with NO index field on either, so the kernel cannot pair their rows. This is the
     * fixture the class's own doc says was measured while it was being built and then discarded — KF1003 went
     * on citing the shared-index prerequisite as measured with nothing locking it.
     */
    private static final String UNINDEXED = DocumentModelDraft.named("parallel-iteration-unindexed").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .group("Items", i -> i.repeatable(20)
                            .field("Sku", FieldKind.STRING)
                            .field("Spare", FieldKind.STRING))
                    .group("Notes", n -> n.repeatable(20)
                            .field("Text", FieldKind.STRING)))
            .build().toJson();

    /**
     * THE PREREQUISITE. Without a shared index-field name the two groups are not parallel-iterable at all, so
     * a cross-group operand list never reaches either iteration code — it is refused one gate earlier, as an
     * unresolvable wildcard. The liveness control is the identical list on the INDEXED fixture, which is the
     * admitted row above: same condition, same rule site, one model difference.
     */
    @Test
    void withoutASharedIndexFieldTheListNeverReachesTheIterationCodes() {
        java.util.Map<String, String> actual = new LinkedHashMap<>();
        actual.put("unindexed, positive over both groups", verdictOn(UNINDEXED,
                "AtLeastOneFieldFilled(/Order/Items/Sku, /Order/Notes/Text)"));
        actual.put("unindexed, pure negative over both groups", verdictOn(UNINDEXED,
                "NoFieldFilled(/Order/Items/Sku, /Order/Notes/Text)"));
        actual.put("indexed control, positive over both groups", verdictOn(MODEL,
                "AtLeastOneFieldFilled(/Order/Items/Sku, /Order/Notes/Text)"));

        java.util.Map<String, String> expected = new LinkedHashMap<>();
        expected.put("unindexed, positive over both groups", "MVK_NO_WILDCARD");
        expected.put("unindexed, pure negative over both groups", "MVK_NO_WILDCARD");
        expected.put("indexed control, positive over both groups", "admitted");
        Assertions.assertEquals(expected, actual,
                "whether a shared index field is what makes a cross-group operand list parallel-iterable");
    }

    private static String verdictOn(String json, String condition) {
        ValidationResult r = KernelLaws.checkModel(json, "/Order", "../Items/Sku", condition);
        return r.ok() ? "admitted"
                : r.diagnostics().stream().map(d -> d.code().orElse("?")).findFirst().orElse("?");
    }

    private static String verdictAt(String group, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(MODEL, group, errorField, condition);
        return r.ok() ? "admitted"
                : r.diagnostics().stream().map(d -> d.code().orElse("?")).findFirst().orElse("?");
    }

    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(MODEL, "/Order/Items", "../Sku", condition);
        return r.ok() ? "admitted"
                : r.diagnostics().stream().map(d -> d.code().orElse("?")).findFirst().orElse("?");
    }

    @Test
    void theTwoCodesPartitionOnParallelismRatherThanOnPolarity() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("pure negative, ONE repeatable group",
                verdict("NoFieldFilled(/Order/Items/Sku, /Order/Items/Spare)"));
        actual.put("pure negative, TWO parallel repeatable groups",
                verdict("NoFieldFilled(/Order/Items/Sku, /Order/Notes/Text)"));
        actual.put("positive over the same two groups",
                verdict("AtLeastOneFieldFilled(/Order/Items/Sku, /Order/Notes/Text)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("pure negative, ONE repeatable group", "MVK_NEG_CONDITION_IN_ITERATION");
        expected.put("pure negative, TWO parallel repeatable groups", "MVK_PARALLEL_ITERATION_INVALID");
        expected.put("positive over the same two groups", "admitted");
        Assertions.assertEquals(expected, actual,
                "whether the parallel code is about parallelism or merely about an unguarded negative");
    }

    /**
     * The sibling code, which had NO test anywhere in the estate: the ledger says
     * {@code RepetitionNotUnique} "must not mix with parallel iteration over the same groups". Measured
     * against the liveness control of RNU alone, so a refusal below is about the mixing and not about RNU
     * being unusable in this fixture at all.
     *
     * <p>The ledger's sentence holds, and "mixing" is narrower than it sounds: <b>conjoining RNU with a
     * parallel operand list</b> is the shape that reports it. RNU keys spanning the two groups is a
     * different rule with its own code ({@code MVK_NOT_IN_SAME_PATH} — keys must share a path), so the two
     * ways of "using RNU across parallel groups" are not one law.
     *
     * <p><b>The liveness control is what makes this readable, and it earned its place immediately.</b> The
     * first draft placed the rule inside the repeatable group, where RNU alone is already
     * {@code MVK_REPEATABLE_GROUP_MISSING} — every row then measured "RNU is unusable here" and the table
     * was about to be recorded as "the mixing does not reach this code at all".
     */
    @Test
    void repetitionNotUniqueMixedWithParallelIteration() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("RNU alone, one group", verdictAt("/Order", "../Items/Sku", "RepetitionNotUnique(/Order/Items/Sku)"));
        actual.put("RNU keys spanning both parallel groups",
                verdictAt("/Order", "../Items/Sku", "RepetitionNotUnique(/Order/Items/Sku, /Order/Notes/Text)"));
        actual.put("RNU conjoined with a parallel operand list",
                verdictAt("/Order", "../Items/Sku", "RepetitionNotUnique(/Order/Items/Sku) And "
                        + "AtLeastOneFieldFilled(/Order/Items/Sku, /Order/Notes/Text)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("RNU alone, one group", "admitted");
        expected.put("RNU keys spanning both parallel groups", "MVK_NOT_IN_SAME_PATH");
        expected.put("RNU conjoined with a parallel operand list", "MVK_PARALLEL_ITERATION_NOT_ALLOWED");
        Assertions.assertEquals(expected, actual,
                "which shape of RNU-with-parallel-iteration carries the code");
    }
}
