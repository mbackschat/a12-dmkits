package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.validate.laws.PolicyPathFixture.RecoveriesIndex;

/**
 * KERNEL-FINDINGS §11 — <b>a keyed GROUP operand beside a keyed error field</b>: the parallel-iteration gates
 * of [KF371]'s table on the twenty-two group carriers, where only the missing-index row had been measured.
 *
 * <p>The keyed {@code Claims} is read unstarred from the root, as in
 * {@link GroupOperandRepetitionScopeLawsTest}, with the error field moved into its repeatable sibling
 * {@code Recoveries}. The one builder call that varies is how {@code Recoveries} declares its index field, so a
 * moved verdict belongs to the index pair and not to the model.
 *
 * <p>Own domain: {@link PolicyPathFixture}.
 */
class KeyedParallelGroupOperandLawsTest {

    private static final String ERROR_FIELD = "Recoveries/Note";

    @Test
    void aFixedErrorFieldGroupDrawsTheMissingIndexOnThisModelToo() {
        assertEquals(uniform("MVK_ERROR_FIELD_NO_INDEX"),
                verdicts(RecoveriesIndex.CLAIM_REF, "Insured/Remark"), "the fixed sibling, re-measured here");
    }

    @Test
    void aRepeatableErrorFieldGroupWithoutAnIndexDrawsTheMissingIndex() {
        assertEquals(uniform("MVK_ERROR_FIELD_NO_INDEX"), verdicts(RecoveriesIndex.NONE, ERROR_FIELD),
                "no index on the error field's chain");
    }

    @Test
    void anIndexOfAnotherNameIsTheInvalidIndex() {
        assertEquals(uniform("MVK_ERROR_FIELD_INVALID_INDEX"), verdicts(RecoveriesIndex.OTHER_NAME, ERROR_FIELD),
                "two index fields of different names");
    }

    @Test
    void theSameNameOfAnotherTypeIsTheInvalidIndexType() {
        assertEquals(uniform("MVK_ERROR_FIELD_INVALID_INDEX_TYPE"),
                verdicts(RecoveriesIndex.CLAIM_REF_AS_NUMBER, ERROR_FIELD), "one index name, two types");
    }

    /**
     * With one index name and type the walk admits the parallel level, and each carrier's own gate answers as it
     * does on a group that does not repeat ({@link GroupOperandRepetitionScopeLawsTest}'s twins and scalar
     * slots): a scalar slot refuses a group, a sole unstarred group is the count's arity class, and the numeric
     * carriers read a text field. {@code CurrentRepetition} of the parallel group is admitted.
     */
    @Test
    void theSameNameAndTypeIsALegalParallelIterationAndTheCarriersOwnGatesAnswer() {
        Map<String, String> expected = uniform("admitted");
        expected.put("FieldFilled(Claims)", "MVK_NO_GROUPS_ALLOWED");
        expected.put("FieldNotFilled(Claims)", "MVK_NO_GROUPS_ALLOWED");
        expected.put("NumberOfFilledGroups(Claims) > 0", "MVK_PARAMSIZE_INVALIDGN");
        expected.put("Sum(Claims, Insured) > 0", "MVK_NO_NUMBER");
        expected.put("FirstFilledValue(Claims, Insured) > 0", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("NumberOfValueInFields(0 In Claims) > 0", "MVK_NO_NUMBER");
        assertEquals(expected, verdicts(RecoveriesIndex.CLAIM_REF, ERROR_FIELD), "one index name and type");
    }

    /**
     * An operand on the same jointly iterated level meets its SCALAR slot's own gate too, as it would on a level
     * the error field sits in: a text field in a numeric rounding slot or a date slot is refused as it is from
     * the fixed {@code Insured}, and a group in a value-list subject is refused as a group. Each refusal sits
     * beside the admitted read of the same slot, the control that the joint read itself passes.
     */
    @Test
    void anOperandOnTheJointLevelMeetsItsScalarSlotsOwnGate() {
        String model = PolicyPathFixture.keyedClaimsBesideRecoveries(RecoveriesIndex.CLAIM_REF);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("0 < RoundUpValue(Claims/Amount, 2)", "admitted");
        expected.put("0 < RoundUpValue(Claims/Remark, 2)", "MVK_NO_NUMBER");
        expected.put("0 < RoundUpValue(Insured/Remark, 2)", "MVK_NO_NUMBER");
        expected.put("DifferenceInDays(Claims/Remark, Insured/Remark) > 0", "MVK_NO_DATE");
        expected.put("FieldValueIncludedInValueList(Claims/Remark, \"x\")", "admitted");
        expected.put("FieldValueIncludedInValueList(Claims, \"x\")", "MVK_NO_GROUPS_ALLOWED");
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(carrier -> actual.put(carrier,
                GroupOperandRepetitionScopeLawsTest.verdict(model, "/Policy", ERROR_FIELD, carrier)));
        assertEquals(expected, actual, "an operand on the jointly iterated level");
    }

    /**
     * The same index pairs on the keyed level ABOVE a starred group, the other position a group operand's walk
     * reaches: {@code Claims} is crossed unstarred on the way to its starred {@code Parts}, and draws the class
     * its own level draws above. A model that adds {@code Parts} is a different model, so the admitted pair is
     * the control that keeps the added group from being what answers.
     */
    @Test
    void theKeyedLevelAboveAStarredGroupDrawsTheSameClasses() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        Map<RecoveriesIndex, String> classes = new LinkedHashMap<>();
        classes.put(RecoveriesIndex.NONE, "MVK_ERROR_FIELD_NO_INDEX");
        classes.put(RecoveriesIndex.OTHER_NAME, "MVK_ERROR_FIELD_INVALID_INDEX");
        classes.put(RecoveriesIndex.CLAIM_REF_AS_NUMBER, "MVK_ERROR_FIELD_INVALID_INDEX_TYPE");
        classes.put(RecoveriesIndex.CLAIM_REF, "admitted");
        classes.forEach((index, verdict) -> {
            String model = PolicyPathFixture.keyedClaimsBesideRecoveries(index, true);
            for (String carrier : BELOW_A_STAR) {
                actual.put(index + " | " + carrier,
                        GroupOperandRepetitionScopeLawsTest.verdict(model, "/Policy", ERROR_FIELD, carrier));
                expected.put(index + " | " + carrier, verdict);
            }
        });
        assertEquals(expected, actual, "the keyed level above a starred group");
    }

    /** Carriers whose group operand stars {@code Parts} and so crosses {@code Claims} unstarred. */
    private static final List<String> BELOW_A_STAR = List.of(
            "AtLeastOneGroupFilled(Claims/Parts*)",
            "NoGroupFilled(Claims/Parts*)",
            "NumberOfFilledGroups(Claims/Parts*) > 0",
            "Sum(Claims/Parts*) > 0");

    private static Map<String, String> uniform(String verdict) {
        Map<String, String> rows = new LinkedHashMap<>();
        GroupOperandRepetitionScopeLawsTest.CARRIERS.forEach(carrier -> rows.put(carrier, verdict));
        return rows;
    }

    private static Map<String, String> verdicts(RecoveriesIndex index, String errorLeaf) {
        String model = PolicyPathFixture.keyedClaimsBesideRecoveries(index);
        Map<String, String> actual = new LinkedHashMap<>();
        GroupOperandRepetitionScopeLawsTest.CARRIERS.forEach(carrier -> actual.put(carrier,
                GroupOperandRepetitionScopeLawsTest.verdict(model, "/Policy", errorLeaf, carrier)));
        return actual;
    }
}
