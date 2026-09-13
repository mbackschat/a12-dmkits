package io.github.mbackschat.a12.dm.rulekit.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * The computation author-time lints (FEATURE-GAPS G5) — proactive, text-based, no kernel round-trip: a
 * forbidden construct in a precondition ({@code CustomCondition}/{@code RepetitionNotUnique}) and two
 * alternatives that share an identical (overlapping) precondition.
 */
class ComputationLinterTest {

    private static final ComputationLinter LINTER = new ComputationLinter();

    private static final String FORBIDDEN = "RK_COMPUTATION_FORBIDDEN_CONSTRUCT";

    private static DraftComputation computation(String commonPrecondition,
            DraftComputation.Alternative... alternatives) {
        return new DraftComputation("/Order", "FeeCalc", "../Fee", commonPrecondition, List.of(alternatives));
    }

    private static DraftComputation.Alternative alt(String precondition, String operation) {
        return new DraftComputation.Alternative(precondition, operation);
    }

    private static List<String> codes(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(d -> d.code().orElse("")).toList();
    }

    /**
     * {@code CustomCondition} is a BARE-NAME operator, which is the only form worth flagging.
     *
     * <p>{@code DslRenderer} emits {@code CustomCondition <name>} with no parentheses, and the operator
     * catalog's own valid example is {@code … And CustomCondition NotReverse} — so a lint keyed on a call
     * shape fires on nothing anyone can author. It was, until 2026-09-13, and this test said otherwise
     * because it asserted against the implementation's pattern instead of the DSL.
     */
    @Test
    void flagsCustomConditionInAPrecondition() {
        List<Diagnostic> out = LINTER.lint(computation(null,
                alt("FieldFilled(Tier) And CustomCondition SpecialCase", "[Base] * 2"),
                alt("[Tier] == \"BASIC\"", "[Base]")));
        assertTrue(codes(out).contains(FORBIDDEN),
                () -> "CustomCondition must be flagged: " + out);
    }

    /**
     * Both forbidden operators, fed to the linter as the CATALOG spells them.
     *
     * <p>The fixture source is the point. This lint's {@code CustomCondition} arm shipped dead because its
     * positive test fed it the matcher's own pattern — a call shape the operator does not take — so the test
     * could not fail (API-FINDINGS AF148). Taking the DSL from {@code validExample} instead means the fixture
     * is the surface an author reads, and a catalog spelling that ever stops matching fails here rather than
     * passing silently.
     */
    @Test
    void flagsTheCatalogsOwnValidUseOfEveryForbiddenOperator() {
        OperatorCatalog catalog = OperatorCatalog.load();
        Map<String, Boolean> actual = new LinkedHashMap<>();
        for (String id : List.of("CustomCondition", "RepetitionNotUnique")) {
            String dsl = catalog.byId(id).orElseThrow().validExample().dsl();
            actual.put(id + ": " + dsl,
                    codes(LINTER.lint(computation(null, alt(dsl, "[Base]")))).contains(FORBIDDEN));
        }
        assertEquals(Map.of("CustomCondition: FieldFilled(CustomerName) And CustomCondition NotReverse", true,
                "RepetitionNotUnique: RepetitionNotUnique(Items/Sku) Or FieldFilled(Items/Count)", true),
                actual, "every forbidden operator's cataloged spelling must be flagged in a precondition");
    }

    /** A quoted name is how an entity spelled like the keyword is referenced, so it is not a use of it. */
    @Test
    void doesNotFalsePositiveOnAQuotedEntityNamedAfterTheKeyword() {
        List<Diagnostic> out = LINTER.lint(computation(null,
                alt("FieldFilled('CustomCondition')", "[Base]")));
        assertEquals(List.of(), out, () -> "a field quoted as 'CustomCondition' is not a use of it: " + out);
    }

    @Test
    void flagsRepetitionNotUniqueInTheCommonPrecondition() {
        List<Diagnostic> out = LINTER.lint(computation("RepetitionNotUnique(Id)",
                alt("[Tier] == \"PRO\"", "[Base] * 2")));
        assertTrue(codes(out).contains(FORBIDDEN),
                () -> "RepetitionNotUnique must be flagged: " + out);
    }

    @Test
    void flagsTwoAlternativesWithAnIdenticalPrecondition() {
        List<Diagnostic> out = LINTER.lint(computation(null,
                alt("[Tier] == \"PRO\"", "[Base] * 2"),
                alt("[Tier]  ==  \"PRO\"", "[Base] * 3")));   // same predicate, different spacing → still overlap
        assertTrue(codes(out).contains("RK_COMPUTATION_OVERLAPPING_PRECONDITIONS"),
                () -> "identical preconditions must be flagged as overlapping: " + out);
    }

    @Test
    void doesNotFlagACleanComputation() {
        List<Diagnostic> out = LINTER.lint(computation(null,
                alt("[Tier] == \"PRO\"", "[Base] * 2"),
                alt("[Tier] == \"BASIC\"", "[Base]")));
        assertEquals(List.of(), out, () -> "distinct, allowed preconditions must produce no lint: " + out);
    }

    @Test
    void doesNotFalsePositiveOnAFieldNameContainingTheKeyword() {
        // the keyword is a prefix of this field's name, and a boundary class is what keeps the two apart.
        List<Diagnostic> out = LINTER.lint(computation(null,
                alt("FieldFilled(CustomConditionFlag)", "[Base]")));
        assertEquals(List.of(), out, () -> "a field named CustomConditionFlag is not the operator: " + out);
    }
}
