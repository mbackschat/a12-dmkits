package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The predicates a COMPUTATION's precondition may not use, and the rule slot that uses them freely.
 *
 * <p>A12's calculation module refuses three language features in a calculation rule's condition, in a fixed
 * {@code if / else if / else if} order — the legacy count-given op, an application condition
 * ({@code CustomCondition}), then {@code RepetitionNotUnique} — with the message "Using predicate '%s' is
 * prohibited in the calculation rules." ({@code NormalisierteBerechnungsFormHelper}, kernel 30.8.1). The
 * denominator is read from that source rather than from prose, because naming a subset is the failure this
 * estate keeps measuring.
 *
 * <p><b>Only two of the three are authorable here.</b> The count-given op has no English spelling at all —
 * it is a DE-only keyword — so an {@code en_US} model cannot reach the first arm. That arm is stated as a
 * boundary rather than measured, and nothing below claims anything about it.
 *
 * <p>The RULE rows are the control that makes this about computations: the same predicates in a rule
 * condition are ordinary.
 */
class ComputationPreconditionLanguageFeatureLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();

    private static final String PROHIBITED = "MVK_ERROR_INVALID_LANGUAGE_FEATURE";

    @Test
    void whichPredicatesAComputationPreconditionMayNotUse() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an ordinary precondition — the control", computation("FieldFilled(Items/Sku)"));
        actual.put("CustomCondition", computation("FieldFilled(Items/Sku) And CustomCondition NotReverse"));
        actual.put("RepetitionNotUnique",
                computation("FieldFilled(Items/Sku) And RepetitionNotUnique(Items/Sku)"));
        actual.put("CustomCondition as the COMMON precondition",
                commonPrecondition("FieldFilled(Items/Sku) And CustomCondition NotReverse"));
        actual.put("CustomCondition in a RULE — the control",
                rule("FieldFilled(Items/Sku) And CustomCondition NotReverse"));
        actual.put("RepetitionNotUnique in a RULE — the control",
                rule("FieldFilled(Items/Sku) And RepetitionNotUnique(Items/Sku)"));
        actual.put("RepetitionNotUnique in the OPERATION — the control",
                operation("RepetitionNotUnique(Items/Sku)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an ordinary precondition — the control", KernelLaws.ADMITTED);
        expected.put("CustomCondition", PROHIBITED);
        expected.put("RepetitionNotUnique", PROHIBITED);
        expected.put("CustomCondition as the COMMON precondition", PROHIBITED);
        expected.put("CustomCondition in a RULE — the control", KernelLaws.ADMITTED);
        expected.put("RepetitionNotUnique in a RULE — the control", KernelLaws.ADMITTED);
        // The slot, not the text, decides the class: the same predicate in the operation is a GRAMMAR error,
        // because an operation is not a condition. A checker reading both string members of an alternative
        // alike would answer a legal-looking question with A12's other vocabulary.
        expected.put("RepetitionNotUnique in the OPERATION — the control", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "which predicates a computation precondition may not use");
    }

    /** One computation on {@code /Order} whose single alternative is guarded by [precondition]. */
    private static String computation(String precondition) {
        return verdictOf(new DraftComputation("/Order", "CalcProbe", "../Items/UnitWeight", null,
                List.of(new DraftComputation.Alternative(precondition, "1")), List.of()));
    }

    /**
     * …and the same predicate in the OPERATION slot, which is not a condition at all.
     *
     * <p>The row a checker needs before it may read a computation's condition members selectively: both slots
     * are strings on the same alternative, and only one of them is a condition.
     */
    private static String operation(String operation) {
        return verdictOf(new DraftComputation("/Order", "CalcProbe", "../Items/UnitWeight", null,
                List.of(new DraftComputation.Alternative(null, operation)), List.of()));
    }

    /** …and the same guard on the computation's COMMON precondition instead of the alternative's. */
    private static String commonPrecondition(String precondition) {
        return verdictOf(new DraftComputation("/Order", "CalcProbe", "../Items/UnitWeight", precondition,
                List.of(new DraftComputation.Alternative(null, "1")), List.of()));
    }

    private static String verdictOf(DraftComputation computation) {
        ValidationResult result = VALIDATOR.check(Fixtures.orderJson(), computation);
        if (result.ok()) {
            return KernelLaws.ADMITTED;
        }
        return result.diagnostics().stream().flatMap(d -> d.code().stream()).distinct()
                .reduce((a, b) -> a + "," + b).orElse("REJECTED WITHOUT A CODE");
    }

    /** The same predicate in a rule condition, which is where these are ordinary. */
    private static String rule(String condition) {
        return KernelLaws.verdictModel(Fixtures.orderJson(), "/Order", "../Items/Sku", condition);
    }
}
