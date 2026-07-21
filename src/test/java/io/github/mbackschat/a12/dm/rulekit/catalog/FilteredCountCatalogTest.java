package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The simple filtered-repeatable count recipe is discoverable from either participating operator. */
class FilteredCountCatalogTest {

    @Test
    void havingTeachesTheValidFieldListAndRejectedGroupListForms() {
        var catalog = OperatorCatalog.load();
        var having = catalog.byId("Having").orElseThrow();
        var filledFields = catalog.byId("NumberOfFilledFields").orElseThrow();

        assertTrue(having.validExample().dsl().contains("NumberOfFilledFields(Items*/Sku Having"));
        assertTrue(having.invalidExample().dsl().contains("NumberOfFilledGroups(Items* Having"));
        assertEquals("MVK_NO_GROUPS_ALLOWED", having.invalidExample().error());
        assertTrue(having.seeAlso().contains("NumberOfFilledFields"));
        assertTrue(filledFields.seeAlso().contains("Having"));
    }
}
