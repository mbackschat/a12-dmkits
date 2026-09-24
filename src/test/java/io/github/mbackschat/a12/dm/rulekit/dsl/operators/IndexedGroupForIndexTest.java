package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupSemanticIndexFieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupSemanticIndexValue;

/**
 * A group handle selects one of its rows the way a field handle selects a cell: {@code scope.forIndex(key)} is the
 * index-selected group, and the group-scoped field-fill quantifiers take it beside a plain scope. A12 admits both
 * shapes on the same carriers (KERNEL-FINDINGS KF351), locked in
 * {@code validate.laws.SemanticIndexGroupOperandCarrierLawsTest}.
 */
class IndexedGroupForIndexTest {

    private static final BoundModel ORDER = Fixtures.order();

    private final GroupListRef items = ORDER.flattenGroup("/Order/Items");

    @Test
    void forIndexSelectsTheRowALiteralKeyNames() {
        assertEquals(new GroupSemanticIndexValue(items, "SKU-1"), items.forIndex("SKU-1"));
    }

    @Test
    void forIndexOnAFieldSelectsTheRowThatFieldsValueNames() {
        assertEquals(new GroupSemanticIndexFieldValue(items, ORDER.field("/Order/ProductCode")),
                items.forIndex(ORDER.field("/Order/ProductCode")));
    }

    @Test
    void theGroupScopedFieldFillQuantifiersTakeASelectedRowBesideAScope() {
        DslRenderer renderer = new DslRenderer();
        assertEquals("AllFieldsFilled(/Order/Items For \"SKU-1\", /Order/ShippingAddress)", renderer.render(
                Dsl.allFieldsFilled(items.forIndex("SKU-1"), ORDER.flattenGroup("/Order/ShippingAddress"))));
        assertEquals("NoFieldFilled(/Order/Items For /Order/ProductCode)",
                renderer.render(Dsl.noFieldFilled(items.forIndex(ORDER.field("/Order/ProductCode")))));
    }
}
