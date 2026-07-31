package io.github.mbackschat.a12.dm.cli.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.core.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource;

/**
 * A comparison nested in a starred operand's {@code Having} <b>filter</b> must be visible to the text-path corrective
 * (FEATURE-GAPS G30).
 *
 * <p>The walk backing that stage descended only through {@code Cmp}/{@code And}/{@code Or}/{@code Not} and never into
 * an {@code Expr} — but a {@code Cond} also hangs off one, as {@code Expr.Star.having}. So every comparison inside a
 * filter was invisible, with two consequences: a rejection originating there got no corrective at all, and the
 * "this operator occurs exactly once, so it is the culprit" test counted occurrences it could not see.
 *
 * <p><b>Only the first consequence is observable, and why is worth writing down</b> — it is the reason this class
 * asserts what it does rather than the more obvious thing. A pin additionally requires the pinned comparison's
 * operands to classify, and an aggregate operand does not; so a filter can never sit inside the comparison that gets
 * pinned, only beside it. To make an over-count observable you would need a code documented by one ordering operator
 * and not its siblings, and the catalog documents {@code MVK_INVALID_TYPE_FOR_COMPARISON} for all four equally. The
 * under-count has no such obstacle, and it is the direction that was losing information.
 *
 * <p>Recorded because the first version of this test asserted the over-count and <b>passed with the fix disabled</b> —
 * twice, for two different wrong reasons (a second documenter operator in the fixture, then a suspected parser failure
 * that {@link FilterConditionParseTest} disproved). A green whose cause you cannot name is not evidence; this file has
 * its present shape because that green was checked instead of banked.
 */
class FilterNestedComparisonTest {

    /**
     * The discriminating case. Both conditions compare with {@code ==} at every top level, and {@code Equals}
     * documents nothing for this code — so the <em>only</em> documenter occurrence anywhere is the {@code <} inside
     * B's filter. Without the descent it is unreachable and nothing is pinned; with it, the filter's comparison is
     * identified.
     */
    @Test
    void aComparisonInsideAFilterIsFoundAndCorrected() {
        Diagnostic withoutFilter = enrichAgainst("MaxValue(/Order/Items*/Count) == 1");
        Diagnostic withFilter = enrichAgainst(
                "MaxValue(/Order/Items*/Count Having [/Order/Items/Sku] < [/Order/Items/Sku]) == 1");

        assertEquals(Optional.empty(), withoutFilter.where().operator(),
                () -> "control: no ordering comparison anywhere, so nothing to pin: " + withoutFilter);
        assertEquals(Optional.of("LessThan"), withFilter.where().operator(),
                () -> "the filter's comparison is the only candidate and must be reachable: " + withFilter);
    }

    /** Having found it, the stage owes the operand-matched corrective and that operator's meaning — not the union. */
    @Test
    void theFilterComparisonGetsItsOperandMatchedCorrective() {
        Diagnostic enriched = enrichAgainst(
                "MaxValue(/Order/Items*/Count Having [/Order/Items/Sku] < [/Order/Items/Sku]) == 1");

        assertTrue(enriched.fix().orElse("").contains("== / !="),
                () -> "string-vs-string ordering gets the ==/!= corrective, not the hedged union: " + enriched);
        assertTrue(enriched.explain().isPresent(),
                () -> "an established owner obliges recomputing the meaning too: " + enriched);
    }

    private static final String UNION_FIX =
            "this code is raised by more than one construct; the corrective depends on which you used — pick one";

    /**
     * A kernel-shaped rejection carrying the enricher's hedged union fix — the input this stage refines. Built
     * directly so the case turns on the WALK and nothing else: routing through a real kernel run would make the
     * condition's acceptability a second variable, and what is under test is which comparisons are reachable.
     */
    private static Diagnostic enrichAgainst(String condition) {
        Diagnostic raw = new Diagnostic(Severity.ERROR, DiagnosticSource.KERNEL,
                Optional.of("MVK_INVALID_TYPE_FOR_COMPARISON"),
                "Rule with path '/Order/ProbeRule': the compared types do not match.",
                new Diagnostic.Where("/Order/ProbeRule", Optional.empty()),
                Optional.of(UNION_FIX), Optional.empty());
        return TextConditionCorrective.enrich(List.of(raw), condition, "/Order",
                CliTestSupport.orderRuledJson(), ReferenceResolvers.selfContained()).get(0);
    }
}
