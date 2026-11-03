package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §11 — <b>the operand-scope walk judges a GROUP operand exactly as it judges a field</b>, on
 * every carrier that takes one, and ahead of each carrier's own gates; plus the gate order in the two scalar
 * field slots. Each row is one the checker-vs-kernel path grid ({@code adapter.laws.CheckerPathGridAgreementTest})
 * disagreed on, or a carrier that grid does not reach.
 *
 * <p>An unstarred repeatable group read from outside it is {@code MVK_NO_WILDCARD} on all twenty-two carriers
 * measured here, the group quantifiers, the field lists that expand a group, the aggregates and
 * {@code CurrentRepetition} alike, so no carrier's gate answers first; read from a parallel level it is
 * {@code MVK_ERROR_FIELD_NO_INDEX} on all twenty-two once it declares an index field. The controls that the
 * walk then passes are the non-repeating twins: they are admitted, or refused by the carrier's own type gate.
 *
 * <p>Own domain: {@link PolicyPathFixture}.
 */
class GroupOperandRepetitionScopeLawsTest {

    private static final String MODEL = PolicyPathFixture.policy(true);
    private static final String CLAIMS_KEYED = PolicyPathFixture.policy(true, true);
    private static final String NO_WILDCARD = "MVK_NO_WILDCARD";
    private static final String NO_INDEX = "MVK_ERROR_FIELD_NO_INDEX";

    /** Every carrier that takes a group operand, each naming the repeatable {@code Claims} unstarred. */
    static final List<String> CARRIERS = List.of(
            "FieldFilled(Claims)",
            "FieldNotFilled(Claims)",
            "GroupFilled(Claims)",
            "GroupNotFilled(Claims)",
            "AtLeastOneGroupFilled(Claims)",
            "AtLeastOneGroupFilled(Claims, Insured)",
            "AllGroupsFilled(Claims, Insured)",
            "NoGroupFilled(Claims, Insured)",
            "NotAllGroupsFilled(Claims, Insured)",
            "GroupsNotCollectivelyFilled(Claims, Insured)",
            "NumberOfFilledGroups(Claims) > 0",
            "AtLeastOneFieldFilled(Claims, Insured)",
            "AllFieldsFilled(Claims, Insured)",
            "NoFieldFilled(Claims, Insured)",
            "NotAllFieldsFilled(Claims, Insured)",
            "NotExactlyOneFieldFilled(Claims, Insured)",
            "MoreThanOneFieldFilled(Claims, Insured)",
            "NumberOfFilledFields(Claims) > 0",
            "Sum(Claims, Insured) > 0",
            "FirstFilledValue(Claims, Insured) > 0",
            "NumberOfValueInFields(0 In Claims) > 0",
            "CurrentRepetition(Claims) > 1");

    @Test
    void anUnstarredRepeatableGroupIsRefusedByTheWalkOnEveryCarrier() {
        Map<String, String> rows = new LinkedHashMap<>();
        CARRIERS.forEach(carrier -> rows.put(carrier, NO_WILDCARD));
        assertEquals(rows, verdicts("/Policy", "Holder", rows), "an unstarred repeatable group, from the root");
    }

    /**
     * The same carriers with {@code Claims} declaring an index field, nothing else moved, and the error field
     * still at the root: {@code Claims} lies below the error field's own group, so A12 never asks whether the
     * level could iterate in parallel, and the index moves nothing.
     */
    @Test
    void anIndexOnALevelBelowTheErrorFieldsGroupMovesNothing() {
        Map<String, String> rows = new LinkedHashMap<>();
        CARRIERS.forEach(carrier -> rows.put(carrier, NO_WILDCARD));
        assertEquals(rows, verdicts(CLAIMS_KEYED, "/Policy", "Holder", rows), "an index-keyed group, from the root");
    }

    /**
     * The same carriers with the error field moved into {@code Insured}, so {@code Claims} is a PARALLEL level:
     * each is the missing star, and the missing index once {@code Claims} declares one. The declaring group is the
     * root on every row, so the error field's position is what the previous test's silence rests on.
     */
    @Test
    void aParallelLevelDrawsTheMissingIndexOnEveryCarrierWhereItDeclaresOne() {
        Map<String, String> unkeyed = new LinkedHashMap<>();
        Map<String, String> keyed = new LinkedHashMap<>();
        CARRIERS.forEach(carrier -> {
            unkeyed.put(carrier, NO_WILDCARD);
            keyed.put(carrier, NO_INDEX);
        });
        assertEquals(unkeyed, verdicts(MODEL, "/Policy", "Insured/Remark", unkeyed), "a parallel group, no index");
        assertEquals(keyed, verdicts(CLAIMS_KEYED, "/Policy", "Insured/Remark", keyed), "a parallel group, keyed");
    }

    @Test
    void theNonRepeatingTwinsPassTheWalk() {
        Map<String, String> rows = orderedMap(
                "GroupFilled(Insured)", "admitted",
                "AllFieldsFilled(Insured, Broker)", "admitted",
                "NotAllGroupsFilled(Insured, Broker)", "admitted",
                "Sum(Insured) > 0", "MVK_NO_NUMBER",
                "FirstFilledValue(Insured) > 0", "MVK_VARYING_TYPES_NOT_ALLOWED");
        assertEquals(rows, verdicts("/Policy", "Holder", rows), "a group that does not repeat");
    }

    @Test
    void aParallelRepeatableGroupWithoutAnIndexIsRefusedToo() {
        Map<String, String> rows = orderedMap(
                "FieldFilled(../Claims)", NO_WILDCARD,
                "GroupFilled(../Claims)", NO_WILDCARD,
                "AtLeastOneGroupFilled(../Claims)", NO_WILDCARD,
                "NumberOfFilledGroups(../Claims) > 0", NO_WILDCARD);
        assertEquals(rows, verdicts("/Policy/Insured", "Remark", rows), "from a sibling group");
    }

    /**
     * The sibling rows with {@code Claims} declaring an index field, nothing else moved: a group takes the field's
     * missing-index class as it takes its missing-star class ([KF466]).
     */
    @Test
    void aParallelRepeatableGroupWithAnIndexDrawsTheMissingIndexClass() {
        Map<String, String> rows = orderedMap(
                "FieldFilled(../Claims)", NO_INDEX,
                "GroupFilled(../Claims)", NO_INDEX,
                "AtLeastOneGroupFilled(../Claims)", NO_INDEX,
                "NumberOfFilledGroups(../Claims) > 0", NO_INDEX);
        assertEquals(rows, verdicts(CLAIMS_KEYED, "/Policy/Insured", "Remark", rows), "from a sibling group");
    }

    /**
     * The two scalar field slots after the walk: a group is refused before a star is, and a star on a field
     * below a correctly starred level is refused next. Every earlier segment gate answers ahead of both, the
     * suffix, a star landing on a level that does not repeat, and a starred leading up-step.
     */
    @Test
    void theScalarFieldSlotsRefuseAGroupThenAStar() {
        Map<String, String> rows = orderedMap(
                "FieldFilled(Claims*)", "MVK_NO_GROUPS_ALLOWED",
                "FieldNotFilled(Claims*)", "MVK_NO_GROUPS_ALLOWED",
                "FieldFilled(Insured)", "MVK_NO_GROUPS_ALLOWED",
                "FieldFilled(Claims*/Amount)", "MVK_NO_WILDCARDS_ALLOWED",
                "FieldNotFilled(Claims*/Amount)", "MVK_NO_WILDCARDS_ALLOWED",
                "FieldFilled(Claims/Amount)", NO_WILDCARD,
                "FieldFilled(Claims.*)", "MVK_POINT_IN_A12_MODEL",
                "FieldFilled(Claims*/Amount*)", "MVK_INVALID_WILDCARD",
                "FieldFilled(Insured*/Age)", "MVK_INVALID_WILDCARD");
        assertEquals(rows, verdicts("/Policy", "Holder", rows), "a scalar field slot, from the root");
        Map<String, String> nested = orderedMap(
                "FieldFilled(../Claims*/Amount)", "MVK_NO_WILDCARDS_ALLOWED",
                "FieldFilled(..*/Claims)", "MVK_INVALID_WILDCARD_REL");
        assertEquals(nested, verdicts("/Policy/Insured", "Remark", nested), "a scalar field slot, nested");
    }

    /**
     * A group handed to a {@code Having} filter is refused as a group only once the walk passes it: the unstarred
     * group draws the walk's class, the starred one the group's. Kernel 30.8.1 refused the group as the filter
     * was entered, ahead of the walk, starred or not (KERNEL-FINDINGS KF656).
     */
    @Test
    void aFilteredGroupIsRefusedAsAGroupOnceTheWalkPassesIt() {
        Map<String, String> rows = orderedMap(
                "AtLeastOneFieldFilled(Claims Having [Claims/Amount] > 0)", "MVK_NO_WILDCARD",
                "AtLeastOneFieldFilled(Claims* Having [Claims/Amount] > 0)", "MVK_NO_GROUPS_ALLOWED",
                "Sum(Claims Having [Claims/Amount] > 0) > 0", "MVK_NO_WILDCARD");
        assertEquals(rows, verdicts("/Policy", "Holder", rows), "a filtered group");
    }

    /** The scalar slot's star gate holds inside a {@code Having} body too, where the unstarred read is the row. */
    @Test
    void theStarGateHoldsInsideAFilter() {
        Map<String, String> rows = orderedMap(
                "Sum(Claims*/Amount Having FieldFilled(Claims*/Remark)) > 0", "MVK_NO_WILDCARDS_ALLOWED",
                "AtLeastOneFieldFilled(Claims*/Amount Having FieldNotFilled(Claims*/Remark))",
                "MVK_NO_WILDCARDS_ALLOWED",
                "Sum(Claims*/Amount Having FieldFilled(Claims/Remark)) > 0", "admitted");
        assertEquals(rows, verdicts("/Policy", "Holder", rows), "inside a filter");
    }

    /** Each carrier, read beside the error field's own reference. */
    private static Map<String, String> verdicts(String group, String errorLeaf, Map<String, String> rows) {
        return verdicts(MODEL, group, errorLeaf, rows);
    }

    private static Map<String, String> verdicts(String model, String group, String errorLeaf,
            Map<String, String> rows) {
        Map<String, String> actual = new LinkedHashMap<>();
        rows.keySet().forEach(row -> actual.put(row, verdict(model, group, errorLeaf, row)));
        return actual;
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    static String verdict(String model, String group, String errorLeaf, String carrier) {
        ValidationResult r = KernelLaws.checkModel(model, group, "../" + errorLeaf,
                "FieldFilled(" + errorLeaf + ") And " + carrier);
        return r.ok() ? "admitted" : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).distinct().toList());
    }

    private static Map<String, String> orderedMap(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
