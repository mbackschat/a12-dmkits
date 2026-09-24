package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;

/**
 * KERNEL-FINDINGS — <b>which ITERATION SOURCES reach {@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP}, measured on
 * both constructs at once.</b> The containment gate itself is [KF212] and [KF372]; what those sets cannot
 * answer is the pair of cells the portable checker's predicate turns on, because each was measured on one
 * construct only and the two results disagree.
 *
 * <p><b>The disagreement, stated before it is resolved.</b> A COMPUTATION with a bare-constant operation
 * into a per-row target outside its declaring group is REFUSED — the target's own repeatable scope derives
 * the iteration with no operand at all ({@code NonIteratingComputationFreedomLawsTest}, from a fixed
 * declaring group). A RULE in the same shape is ADMITTED ({@code RootOnlyErrorTargetIterationLawsTest}'s
 * fixed-root control, from a repeatable declaring group). Two variables moved between those rows — the
 * construct and the declaring group's own repeatability — so neither set can say which one carries the
 * difference, and a checker that guessed would either refuse rules A12 admits or stay silent on a
 * measured refusal. The 2×2 below moves one at a time on ONE model.
 *
 * <p><b>Own domain</b> (e-commerce returns handling), and every rule cell reaches its error field through
 * the NON-repeatable root, which is what keeps the reaching reference from being an iteration source of its
 * own — the confound that made {@code RootOnlyErrorTargetIterationLawsTest}'s first control unreadable.
 */
class ErrorTargetContainmentLawsTest {

    private static final String NOT_IN_RULEGROUP = "MVK_ERROR_FIELD_NOT_IN_RULEGROUP";

    /**
     * One root, non-repeatable, carrying both a fixed and a repeatable home for a declaration and a target.
     *
     * <p>{@code Claims} declares an index field and {@code Inspections} does not, because that declaration
     * decides WHICH gate answers an unstarred per-row read from a fixed declaring group
     * ({@code MVK_ERROR_FIELD_NO_INDEX} against {@code MVK_NO_WILDCARD}) and a grid built on one of them
     * would generalize the wrong code.
     */
    private static final String RETURNS = DocumentModelDraft.named("returns-handling").locale("en_US")
            .rootGroup("Returns", root -> root
                    .numberField("TotalRefund", n -> n.fractionDigits(0))
                    .group("Policy", p -> p.numberField("Excess", n -> n.fractionDigits(0)))
                    .group("Claims", c -> c.repeatable(20).indexField("ClaimNo")
                            .field("ClaimNo", FieldKind.STRING)
                            .numberField("Amount", n -> n.fractionDigits(0))
                            .field("Memo", FieldKind.STRING))
                    .group("Inspections", i -> i.repeatable(20)
                            .numberField("Amount", n -> n.fractionDigits(0))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(RETURNS);

    /** A rule declared in [group] reporting on [errorField], under [condition]. */
    private static String rule(String group, String errorField, String condition) {
        return KernelLaws.verdictModel(RETURNS, group, errorField, condition);
    }

    /** A computation declared in [group] writing [computedField] from [operation]. */
    private static String computation(String group, String computedField, Calculation operation) {
        return KernelLaws.computationVerdict(RETURNS, M.computation(M.num(computedField))
                .name("ReturnsProbe")
                .group(M.group(group))
                .calc(operation)
                .message(Dsl.en("Returns placement probe."))
                .build());
    }

    /**
     * Does the WRITTEN FIELD's own repeatable scope derive iteration for a rule as it does for a
     * computation, and does the declaring group's own repeatability change the answer?
     *
     * <p>Every cell holds the written field per-row and outside the declaring group, and supplies an
     * operand that provably derives no iteration — a bare constant for the computation, and for the rule a
     * reach through the non-repeatable root, which is the nearest a rule can come to one given that its
     * error field must be referenced at all ({@code MVK_ERROR_FIELD_NOT_REFERENCED}).
     */
    @Test
    void whetherTheWrittenFieldsOwnScopeIteratesIsAskedOfBothConstructsAtBothDeclaringGroups() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("rule | FIXED declaring group",
                rule("/Returns/Policy", "../../Claims/Memo", "GroupFilled(/Returns)"));
        actual.put("rule | REPEATABLE declaring group",
                rule("/Returns/Inspections", "../../Claims/Memo", "GroupFilled(/Returns)"));
        actual.put("computation | FIXED declaring group",
                computation("/Returns/Policy", "/Returns/Claims/Amount", NumberLiteral.of(-1)));
        actual.put("computation | REPEATABLE declaring group",
                computation("/Returns/Inspections", "/Returns/Claims/Amount", NumberLiteral.of(-1)));
        // The control that keeps any refusal above about CONTAINMENT rather than about the placement: the
        // same four declarations with the written field INSIDE the declaring group.
        actual.put("rule | contained control",
                rule("/Returns/Claims", "../Memo", "GroupFilled(/Returns)"));
        actual.put("computation | contained control",
                computation("/Returns/Claims", "/Returns/Claims/Amount", NumberLiteral.of(-1)));

        Map<String, String> expected = new LinkedHashMap<>();
        // The CONSTRUCT carries the difference and the declaring group's repeatability carries none: a
        // rule's per-row error field derives no iteration by itself, a computation's per-row computed
        // field does. That is the cell the portable checker's predicate turns on, and the reason it may
        // not treat the two written fields alike however symmetric the gate is.
        expected.put("rule | FIXED declaring group", KernelLaws.ADMITTED);
        expected.put("rule | REPEATABLE declaring group", KernelLaws.ADMITTED);
        expected.put("computation | FIXED declaring group", NOT_IN_RULEGROUP);
        expected.put("computation | REPEATABLE declaring group", NOT_IN_RULEGROUP);
        expected.put("rule | contained control", KernelLaws.ADMITTED);
        expected.put("computation | contained control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the written field's own scope as the only iteration source");
    }

    /**
     * With the written field FIXED, does an unstarred per-row read of the declaring group's OWN extent reach
     * the containment gate on the rule side as it does on the computation side?
     *
     * <p>The computation side is measured ({@code NonIteratingComputationFreedomLawsTest}: from the
     * repeatable group it reads, either extent reaches the placement gate). Every rule-side containment row
     * in this estate holds the error field per-row instead, so the rule half of that column is a constant
     * nobody moved. The third cell is the pre-emption boundary the checker has to respect: the same read
     * from a FIXED declaring group answers with a wildcard class instead.
     */
    @Test
    void whetherAnOwnExtentPerRowReadReachesTheGateWithAFixedWrittenField() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("rule | read of the declaring group's own extent",
                rule("/Returns/Claims", "../../TotalRefund", "FieldFilled(../TotalRefund) And [Amount] > 0"));
        actual.put("rule | reach only, no per-row read — control",
                rule("/Returns/Claims", "../../TotalRefund", "FieldFilled(../TotalRefund)"));
        actual.put("rule | per-row read of an INDEXED extent from a fixed declaring group",
                rule("/Returns/Policy", "../../TotalRefund",
                        "FieldFilled(../TotalRefund) And [../Claims/Amount] > 0"));
        actual.put("rule | per-row read of a NON-indexed extent from a fixed declaring group",
                rule("/Returns/Policy", "../../TotalRefund",
                        "FieldFilled(../TotalRefund) And [../Inspections/Amount] > 0"));
        // A star aggregate over its one repeatable level reads no row, so it derives no iteration — measured on
        // the computation side and asked here because the rule side had no such cell, and the predicate would
        // otherwise refuse every aggregate rule whose error field sits outside its group. A star that leaves a
        // second level unstarred does read per row (PartialStarContainmentLawsTest).
        actual.put("rule | STAR aggregate over an extent, no per-row read",
                rule("/Returns/Policy", "../../TotalRefund",
                        "FieldFilled(../TotalRefund) And Sum(/Returns/Claims*/Amount) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("rule | read of the declaring group's own extent", NOT_IN_RULEGROUP);
        expected.put("rule | reach only, no per-row read — control", KernelLaws.ADMITTED);
        // Neither wildcard class pre-empts here, and the contrast with the computation side is NOT a
        // construct difference: the rows that report `MVK_NO_WILDCARD` / `MVK_ERROR_FIELD_NO_INDEX` for the
        // same read hold the written field CONTAINED, so the containment gate had nothing to answer. With
        // containment failing it answers first whichever extent the read crosses — which is why this family
        // may read the extent's index declaration as irrelevant rather than assuming it from that set.
        expected.put("rule | per-row read of an INDEXED extent from a fixed declaring group", NOT_IN_RULEGROUP);
        expected.put("rule | per-row read of a NON-indexed extent from a fixed declaring group",
                NOT_IN_RULEGROUP);
        expected.put("rule | STAR aggregate over an extent, no per-row read", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a fixed written field with the read as the only iteration source");
    }

    /**
     * When the condition does not REACH the error field at all, which class answers — containment or
     * referenced-ness?
     *
     * <p>Both apply, and the checker must not name the one A12 does not: every containment row in this
     * estate carries a reaching reference, so the precedence has never been put to the kernel. The second
     * cell holds the reach and moves nothing else.
     */
    @Test
    void whichClassAnswersWhenTheConditionNeverReachesAnUncontainedErrorField() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("no reach, per-row read, uncontained target",
                rule("/Returns/Claims", "../../TotalRefund", "[Amount] > 0"));
        actual.put("reach added, nothing else moved",
                rule("/Returns/Claims", "../../TotalRefund", "[Amount] > 0 And FieldFilled(../TotalRefund)"));

        Map<String, String> expected = new LinkedHashMap<>();
        // Containment answers first, so this family owes no reaching-reference precondition — the opposite
        // of `MVK_ERROR_FIELDGROUP_ITERATION{4,5}_INVALID`, which are silent without one.
        expected.put("no reach, per-row read, uncontained target", NOT_IN_RULEGROUP);
        expected.put("reach added, nothing else moved", NOT_IN_RULEGROUP);
        assertEquals(expected, actual, "containment against referenced-ness");
    }
}
