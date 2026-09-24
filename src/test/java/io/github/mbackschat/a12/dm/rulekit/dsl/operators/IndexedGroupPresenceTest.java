package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionPolarity;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.OperatorUsage;

/**
 * {@code GroupFilled(Group For key)} and {@code GroupNotFilled(Group For key)}, the presence of the row a semantic
 * index selects. A12 admits both spellings (KERNEL-FINDINGS KF351, {@code validate.laws.SemanticIndexGroupOperandCarrierLawsTest});
 * the interpreter reads them on the selected row (INTERPRETER-FINDINGS IF331).
 */
class IndexedGroupPresenceTest {

    private static final BoundModel ORDER = Fixtures.order();

    private final GroupListRef items = ORDER.flattenGroup("/Order/Items");

    @Test
    void aSelectedRowHasAPresenceOfItsOwn() {
        DslRenderer renderer = new DslRenderer();
        assertEquals("GroupFilled(/Order/Items For \"SKU-1\")", renderer.render(items.forIndex("SKU-1").filled()));
        assertEquals("GroupNotFilled(/Order/Items For \"SKU-1\")",
                renderer.render(items.forIndex("SKU-1").notFilled()));
    }

    /** The field-keyed form's key picks the row, so the presence reports it beside the group. */
    @Test
    void theKeyFieldOfAFieldKeyedRowIsReferenced() {
        ConditionReferences refs = ConditionReferences.of(items.forIndex(ORDER.field("/Order/ProductCode")).filled());
        assertTrue(refs.groupPaths().contains("/Order/Items"), () -> "got " + refs.groupPaths());
        assertTrue(refs.fieldPaths().contains("/Order/ProductCode"), () -> "got " + refs.fieldPaths());
    }

    @Test
    void theComplementFlipsThePresence() {
        assertEquals(Optional.of(items.forIndex("SKU-1").notFilled()),
                ConditionPolarity.complement(items.forIndex("SKU-1").filled()));
    }

    /** The row's presence reports the semantic index it reads through, as a keyed cell's presence does. */
    @Test
    void theOperatorUsageNamesThePresenceAndTheIndex() {
        assertEquals(Set.of("GroupNotFilled", "SemanticIndex"), OperatorUsage.of(items.forIndex("SKU-1").notFilled()));
    }
}
