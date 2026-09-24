package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListPredicate;

/**
 * An index-selected FIELD as a group-list quantifier operand, `AtLeastOneGroupFilled(Items/Count For "SKU-1", …)`.
 * A12 admits it and reads it as the selected cell's presence (INTERPRETER-FINDINGS IF335); the kernel admission
 * and the read-back identity are locked in {@code adapter.SemanticIndexFieldOperandPositionReadTest}.
 */
class IndexedFieldGroupListOperandTest {

    private static final BoundModel ORDER = Fixtures.order();

    private final FieldRef count = ORDER.field("/Order/Items/Count");
    private final FieldRef customer = ORDER.field("/Order/CustomerName");

    @Test
    void anIndexSelectedFieldFillsAGroupListSlot() {
        assertEquals("AtLeastOneGroupFilled(/Order/Items/Count For \"SKU-1\", /Order/CustomerName)",
                new DslRenderer().render(Dsl.atLeastOneGroupFilled(count.semanticIndex("SKU-1").index(), customer)));
    }

    /** The field-keyed form's key picks the repetition, so the slot must report it as a reference. */
    @Test
    void theKeyFieldOfAFieldKeyedCellIsReferencedThroughTheSlot() {
        ConditionReferences refs = ConditionReferences.of(Dsl.noGroupFilled(
                count.semanticIndex(ORDER.field("/Order/ProductCode")).index(), customer));
        assertTrue(refs.fieldPaths().contains("/Order/Items/Count"), () -> "got " + refs.fieldPaths());
        assertTrue(refs.fieldPaths().contains("/Order/ProductCode"), () -> "got " + refs.fieldPaths());
    }

    @Test
    void theGroupsViewFailsFastOnAnIndexSelectedField() {
        GroupListPredicate list = (GroupListPredicate) Dsl.allGroupsFilled(
                count.semanticIndex("SKU-1").index(), ORDER.flattenGroup("/Order/ShippingAddress"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, list::groups);
        assertTrue(failure.getMessage().contains("/Order/Items/Count"), failure::getMessage);
    }
}
