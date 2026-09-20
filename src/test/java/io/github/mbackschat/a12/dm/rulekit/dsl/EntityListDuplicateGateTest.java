package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * The two carriers over {@code List<AggregateOperand>} that had no duplicate gate: the field-list quantifier
 * family ({@code EntityListPredicate}, nine quantifiers plus {@code FieldValuesNotUnique} and
 * {@code DateRangesOverlap}) and {@code NumberOfValueInFields}.
 *
 * <p>Our own CLI evidence had the first one kernel-caught rather than client-caught
 * ({@code cli.PatternsScaffoldVerdictTest}), which is what an ungated carrier looks like from outside. The
 * second is the carrier a test javadoc had claimed does NOT share the entity-list wildcard skip, with no row
 * behind the claim; it does share it, so the starred rows below must stay admitted.
 * Kernel rows: {@code validate.laws.DuplicateOverlapCarrierSweepLawsTest}.
 */
class EntityListDuplicateGateTest {

    private static final BoundModel ORDER = Fixtures.order();

    private static String refusal(Runnable build) {
        return assertThrows(IllegalArgumentException.class, build::run).getMessage();
    }

    @Test
    void theSameFieldTwiceIsRefusedByTheQuantifierFamily() {
        FieldRef quantity = ORDER.field("/Order/Quantity");
        String message = refusal(() -> Dsl.allFieldsFilled(quantity, quantity));
        assertTrue(message.contains("MVK_DUPLICATE_PARAM1"), () -> "expected the kernel's code: " + message);
    }

    @Test
    void aGroupScopeBesideAFieldInsideItIsRefusedAsAnOverlap() {
        // A MIXED group-scope-plus-field list has no Dsl factory, so the record's own constructor — which is
        // where the gate lives — is the locus under test.
        String message = refusal(() -> new EntityListPredicate(Quantifier.ALL_FIELDS_FILLED,
                java.util.List.of(ORDER.flattenGroup("/Order/ShippingAddress"),
                        ORDER.field("/Order/ShippingAddress/City"))));
        assertTrue(message.contains("MVK_DUPLICATE_PARAM2"), () -> "overlap is the other code: " + message);
    }

    /** A repeated STARRED list is the kernel's own exemption — refusing it would over-reject. */
    @Test
    void aRepeatedStarredListStaysAdmitted() {
        FieldListRef count = ORDER.flatten("/Order/Items*/Count");
        assertDoesNotThrow(() -> Dsl.allFieldsFilled(count, count));
    }

    @Test
    void twoDistinctFieldsAreAdmitted() {
        assertDoesNotThrow(() -> Dsl.allFieldsFilled(
                ORDER.field("/Order/Quantity"), ORDER.field("/Order/UnitPrice")));
    }

    // --- NumberOfValueInFields, which shares both arms and the skip ------------------------------

    @Test
    void numberOfValueInFieldsRefusesTheSameFieldTwice() {
        FieldRef quantity = ORDER.field("/Order/Quantity");
        String message = refusal(() -> Dsl.numberOfValueInFields(0L, quantity, quantity));
        assertTrue(message.contains("MVK_DUPLICATE_PARAM1"), () -> "expected the kernel's code: " + message);
    }

    /** The row the corrected javadoc turns on: this carrier DOES carry the wildcard skip. */
    @Test
    void numberOfValueInFieldsAdmitsARepeatedStarredList() {
        FieldListRef count = ORDER.flatten("/Order/Items*/Count");
        assertDoesNotThrow(() -> Dsl.numberOfValueInFields(0L, count, count));
    }

    // --- the entity-list AGGREGATES, whose containment arm was the last one still delegated -------

    /**
     * A group scope beside a field inside it, on {@code Sum}. The kernel's verdict is held by
     * {@code validate.laws.AggregateGroupOperandLawsTest}, which now authors that row as a string precisely
     * because this refusal exists.
     */
    @Test
    void anAggregateRefusesAGroupScopeBesideAFieldInsideIt() {
        String message = refusal(() -> Dsl.sum(
                ORDER.flattenGroup("/Order/ShippingAddress"), ORDER.field("/Order/ShippingAddress/City")));
        assertTrue(message.contains("MVK_DUPLICATE_PARAM2"), () -> "overlap is the other code: " + message);
    }

    /**
     * The INDEXED-FIELD arm's containment component, controlled in the module that owns it.
     *
     * <p>An index-selected field inside an index-selected group under the SAME key is contained; under
     * DIFFERENT keys the two operands address different repetitions and are not. Both halves are
     * kernel-measured ({@code validate.laws.DuplicateOverlapCarrierSweepLawsTest}). Without this row the only
     * guard on {@code IndexedFieldValue.overlapModifier()} sits in a bridge-translation test in another
     * module, so a rulekit-side change to the component would go unnoticed here.
     */
    @Test
    void anIndexSelectedFieldInsideAnIndexSelectedGroupIsContainedOnlyUnderTheSameKey() {
        var group = new GroupListRef(ORDER.group("/Order/Items"), java.util.List.of());
        var field = ORDER.field("/Order/Items/Count");

        String refused = refusal(() -> new EntityListPredicate(Quantifier.MORE_THAN_ONE_FIELD_FILLED,
                java.util.List.of(new SemanticIndexValue(field, "a"),
                        new GroupSemanticIndexValue(group, "a"))));
        assertTrue(refused.contains("MVK_DUPLICATE_PARAM2"),
                () -> "the same key makes this a containment: " + refused);

        assertDoesNotThrow(() -> new EntityListPredicate(Quantifier.MORE_THAN_ONE_FIELD_FILLED,
                java.util.List.of(new SemanticIndexValue(field, "a"),
                        new GroupSemanticIndexValue(group, "b"))),
                "different keys address different repetitions, so there is no containment");
    }

    /**
     * The EXACT arm on both indexed operand shapes, controlled in the module that owns the identity.
     *
     * <p>An index selection is part of the identity, so the same entity under one key twice is a duplicate and
     * under two keys is not. Both halves are kernel-measured
     * ({@code validate.laws.DuplicateOverlapCarrierSweepLawsTest}). Without these rows the only guard on
     * {@code IndexedFieldValue.exactIdentity()} and {@code IndexedGroupValue.exactIdentity()} was a
     * bridge-translation test in another module — a mutant emptying either survived the whole rulekit lane.
     */
    @Test
    void anIndexedEntityIsADuplicateOfItselfOnlyUnderTheSameKey() {
        var group = new GroupListRef(ORDER.group("/Order/Items"), java.util.List.of());
        var field = ORDER.field("/Order/Items/Count");

        String repeatedField = refusal(() -> new EntityListPredicate(Quantifier.MORE_THAN_ONE_FIELD_FILLED,
                java.util.List.of(new SemanticIndexValue(field, "a"), new SemanticIndexValue(field, "a"))));
        assertTrue(repeatedField.contains("MVK_DUPLICATE_PARAM1"),
                () -> "one field under one key twice is an exact duplicate: " + repeatedField);

        String repeatedGroup = refusal(() -> new EntityListPredicate(Quantifier.MORE_THAN_ONE_FIELD_FILLED,
                java.util.List.of(new GroupSemanticIndexValue(group, "a"),
                        new GroupSemanticIndexValue(group, "a"))));
        assertTrue(repeatedGroup.contains("MVK_DUPLICATE_PARAM1"),
                () -> "one group under one key twice is an exact duplicate: " + repeatedGroup);

        assertDoesNotThrow(() -> new EntityListPredicate(Quantifier.MORE_THAN_ONE_FIELD_FILLED,
                java.util.List.of(new SemanticIndexValue(field, "a"), new SemanticIndexValue(field, "b"))),
                "two keys address two repetitions, so the pair is not a duplicate");
        assertDoesNotThrow(() -> new EntityListPredicate(Quantifier.MORE_THAN_ONE_FIELD_FILLED,
                java.util.List.of(new GroupSemanticIndexValue(group, "a"),
                        new GroupSemanticIndexValue(group, "b"))),
                "the same, one level up");
    }

    /** And the exemption still holds on that carrier: a repeated starred list is not a duplicate. */
    @Test
    void anAggregateAdmitsARepeatedStarredList() {
        FieldListRef count = ORDER.flatten("/Order/Items*/Count");
        assertDoesNotThrow(() -> Dsl.sum(count, count));
    }
}
