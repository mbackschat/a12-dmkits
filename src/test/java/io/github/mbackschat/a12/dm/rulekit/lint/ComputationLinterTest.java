package io.github.mbackschat.a12.dm.rulekit.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * The computation author-time lints (FEATURE-GAPS G5) — proactive, text-based, no kernel round-trip: a
 * forbidden construct in a precondition ({@code CustomCondition}/{@code RepetitionNotUnique}) and two
 * alternatives that share an identical (overlapping) precondition.
 */
class ComputationLinterTest {

    private static final ComputationLinter LINTER = new ComputationLinter();

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

    @Test
    void flagsCustomConditionInAPrecondition() {
        List<Diagnostic> out = LINTER.lint(computation(null,
                alt("CustomCondition(SpecialCase)", "[Base] * 2"),
                alt("[Tier] == \"BASIC\"", "[Base]")));
        assertTrue(codes(out).contains("RK_COMPUTATION_FORBIDDEN_CONSTRUCT"),
                () -> "CustomCondition must be flagged: " + out);
    }

    @Test
    void flagsRepetitionNotUniqueInTheCommonPrecondition() {
        List<Diagnostic> out = LINTER.lint(computation("RepetitionNotUnique(Id)",
                alt("[Tier] == \"PRO\"", "[Base] * 2")));
        assertTrue(codes(out).contains("RK_COMPUTATION_FORBIDDEN_CONSTRUCT"),
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
        // a field reference is not a CustomCondition( call — the call-shape match must not fire on a path.
        List<Diagnostic> out = LINTER.lint(computation(null,
                alt("FieldFilled(CustomConditionFlag)", "[Base]")));
        assertEquals(List.of(), out, () -> "a field named CustomConditionFlag is not a CustomCondition call: " + out);
    }
}
