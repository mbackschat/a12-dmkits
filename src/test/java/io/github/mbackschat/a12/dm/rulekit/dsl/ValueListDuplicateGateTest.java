package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * The two value-list carriers, whose duplicate gate lived in the {@code Dsl} factories and so was reachable
 * around: both records are public, and one factory built {@code FieldListEntityListCondition} directly instead
 * of routing through the shared quantifier path, which left the {@code AtLeastOneDateRangeOverlaps} arm with no
 * gate at all while its three siblings had one.
 *
 * <p>The gate now sits in the records, which is where an intrinsic check belongs (API-FINDINGS AF153) and is what
 * makes every construction path — the factories, a direct {@code new}, and the kernel→AST read — reach it. Two
 * things came with the move. The kernel checks each list alone and then their UNION, so a reference repeated
 * ACROSS the two lists is a duplicate; and the CONTAINMENT arm now runs here too, which the factory never did.
 * Kernel rows: {@code validate.laws.DuplicateOverlapCarrierSweepLawsTest},
 * {@code validate.laws.EnumValueListUnionLawsTest}.
 */
class ValueListDuplicateGateTest {

    private static final BoundModel ORDER = Fixtures.order();

    private static String refusal(Runnable build) {
        return assertThrows(IllegalArgumentException.class, build::run).getMessage();
    }

    @Test
    void theSameFieldTwiceInALiteralValueListIsRefused() {
        StringRef name = ORDER.str("/Order/CustomerName");
        String message = refusal(() -> Dsl.noFieldValueIncludedInValueList(List.of("x"), name, name));
        assertTrue(message.contains("MVK_DUPLICATE_PARAM1"), () -> "expected the kernel's code: " + message);
    }

    /** The containment arm, which the factory-sited gate never ran: a group scope beside a field inside it. */
    @Test
    void aGroupScopeBesideAFieldInsideItIsRefusedAsAnOverlap() {
        String message = refusal(() -> new FieldListValueListCondition(
                ValueListQuantifier.NO_FIELD_VALUE_INCLUDED,
                List.<ValueListOperand>of(ORDER.flattenGroup("/Order/ShippingAddress"),
                        ORDER.field("/Order/ShippingAddress/City")),
                List.of(new StringLiteral("x"))));
        assertTrue(message.contains("MVK_DUPLICATE_PARAM2"), () -> "overlap is the other code: " + message);
    }

    /**
     * THE BYPASS this partition existed to close: {@code atLeastOneDateRangeOverlaps} constructs its record
     * directly, so before the gate moved it was the one arm of this carrier with no duplicate check.
     */
    @Test
    void theDateRangeOverlapArmNoLongerBypassesTheGate() {
        FieldRef window = ORDER.field("/Order/CoverageWindow");
        String message = refusal(() -> Dsl.atLeastOneDateRangeOverlaps(window, window));
        assertTrue(message.contains("MVK_DUPLICATE_PARAM1"),
                () -> "the record's own constructor must catch what the factory skipped: " + message);
    }

    /** A repeated STARRED list stays admitted on this carrier too. */
    @Test
    void aRepeatedStarredListStaysAdmitted() {
        assertDoesNotThrow(() -> new FieldListValueListCondition(
                ValueListQuantifier.NO_FIELD_VALUE_INCLUDED,
                List.<ValueListOperand>of(ORDER.flatten("/Order/Items*/Sku"),
                        ORDER.flatten("/Order/Items*/Sku")),
                List.of(new StringLiteral("x"))));
    }

    /**
     * The CATEGORY component of containment, one variable apart. A root group contains a plain read of a
     * field inside it, and does NOT contain a category projection of that same field.
     *
     * <p>Both halves are kernel-measured on this fixture: the plain pair is
     * {@code MVK_DUPLICATE_PARAM2}, and the projected pair reaches {@code MVK_ROOT_GROUP_REFERENCED} instead —
     * which is evidence rather than a pre-empt, because the duplicate arms precede the root-group bar in that
     * routine, so a duplicate would have won. Dropping the category from the modifier would refuse the second
     * pair, which is the over-rejection this row exists to catch.
     */
    @Test
    void aRootGroupDoesNotContainACategoryProjectionOfAFieldInsideIt() {
        var root = new GroupListRef(ORDER.group("/Order"), List.of());
        var projection = new EnumCategoryAccess(ORDER.field("/Order/Priority"), "SpeedClass", false);

        String refused = refusal(() -> new FieldListValueListCondition(
                ValueListQuantifier.NO_FIELD_VALUE_INCLUDED,
                List.<ValueListOperand>of(root, ORDER.field("/Order/Priority")),
                List.of(new StringLiteral("x"))));
        assertTrue(refused.contains("MVK_DUPLICATE_PARAM2"),
                () -> "a plain read inside the root group is contained: " + refused);

        assertDoesNotThrow(() -> new FieldListValueListCondition(
                ValueListQuantifier.NO_FIELD_VALUE_INCLUDED,
                List.<ValueListOperand>of(root, projection),
                List.of(new StringLiteral("x"))),
                "the projection carries a category the group does not, so it is not contained");
    }

    /**
     * The ENTITY-valued record's containment arm. Its sibling above covers the literal-value record; nothing
     * reached this one, because no factory builds a group scope into the entity-valued form — so the record's
     * own constructor is the locus under test.
     *
     * <p>The pair spans the two lists on purpose: the kernel checks each list alone and then their union, and
     * containment across the union is the arm this record runs that its factory never did.
     */
    @Test
    void theEntityValuedRecordRefusesAGroupScopeBesideAFieldInsideIt() {
        String message = refusal(() -> new FieldListEntityListCondition(
                ValueListQuantifier.NO_FIELD_VALUE_INCLUDED,
                List.<ValueListOperand>of(ORDER.flattenGroup("/Order/ShippingAddress")),
                List.<ValueListOperand>of(ORDER.field("/Order/ShippingAddress/City"))));
        assertTrue(message.contains("MVK_DUPLICATE_PARAM2"),
                () -> "containment across the union is this record's own arm: " + message);
    }

    @Test
    void twoDistinctFieldsAreAdmitted() {
        assertDoesNotThrow(() -> Dsl.noFieldValueIncludedInValueList(
                List.of("x"), ORDER.str("/Order/CustomerName"), ORDER.str("/Order/ProductCode")));
    }
}
