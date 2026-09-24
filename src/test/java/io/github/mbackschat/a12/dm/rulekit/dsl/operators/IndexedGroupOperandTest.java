package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.AggregateOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences;
import io.github.mbackschat.a12.dm.rulekit.dsl.EntityListPredicate;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedGroupValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.Quantifier;

/**
 * The group peer of {@code IndexedFieldOperandTest}: an index-selected group ({@code [Group For key]}) fills
 * an entity-list slot, which the kernel admits and the typed surface could not express — the admission and its
 * discriminating unindexed control are in {@code validate.laws.SemanticIndexSlotLawsTest}.
 *
 * <p>{@link IndexedGroupValue} is never a {@code Calculation} — a group has no value of its own — so unlike the
 * field form it cannot reach the exhaustive value-expression switch at all, and the PROJECTED/PLAIN read mode
 * does not apply to it. Only one property carries over: the field-keyed form's KEY is a reference in its own
 * right, because its value is what picks the repetition, and a slot that drops it leaves
 * {@code fieldPaths()}/{@code covers()} blind to a real dependency.
 */
class IndexedGroupOperandTest {

    private static final BoundModel ORDER = Fixtures.order();

    private GroupListRef items() {
        return ORDER.flattenGroup("/Order/Items");
    }

    private static ConditionReferences refsOf(AggregateOperand indexed, FieldRef sibling) {
        return ConditionReferences.of(
                new EntityListPredicate(Quantifier.ALL_FIELDS_FILLED, List.of(indexed, sibling)));
    }

    /** The literal-keyed form references the group; there is no key field to carry. */
    @Test
    void aLiteralKeyedIndexedGroupReferencesTheGroup() {
        ConditionReferences refs =
                refsOf(items().forIndex("SKU-1"), ORDER.field("/Order/CustomerName"));
        assertTrue(refs.groupPaths().contains("/Order/Items"),
                () -> "expected the group among " + refs.groupPaths());
    }

    /** The field-keyed form's KEY must survive the slot — the value that picks the repetition. */
    @Test
    void theKeyFieldOfAFieldKeyedIndexedGroupIsReferenced() {
        ConditionReferences refs = refsOf(
                items().forIndex(ORDER.field("/Order/ProductCode")),
                ORDER.field("/Order/CustomerName"));
        assertTrue(refs.groupPaths().contains("/Order/Items"),
                () -> "expected the group among " + refs.groupPaths());
        assertTrue(refs.fieldPaths().contains("/Order/ProductCode"),
                () -> "the key field must be a reference too — got " + refs.fieldPaths());
    }

    /** The control, so the assertion above is not satisfied by everything being reported. */
    @Test
    void aFieldTheConditionNeverMentionsIsNotReferenced() {
        ConditionReferences refs =
                refsOf(items().forIndex("SKU-1"), ORDER.field("/Order/CustomerName"));
        assertFalse(refs.fieldPaths().contains("/Order/ProductCode"),
                () -> "an unmentioned field must stay unreferenced — got " + refs.fieldPaths());
    }
}
