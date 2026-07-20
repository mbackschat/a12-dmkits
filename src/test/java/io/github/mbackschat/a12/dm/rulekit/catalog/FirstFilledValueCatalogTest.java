package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Served authoring guidance for positional filter encounter in {@code FirstFilledValue}. */
class FirstFilledValueCatalogTest {

    @Test
    void distinguishesReachedAndUnvisitedFilters() {
        OperatorEntry entry = OperatorCatalog.load().byId("FirstFilledValue").orElseThrow();
        String gotchas = entry.gotchas().stream().map(OperatorEntry.Gotcha::text)
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(gotchas.contains("immediately before that operand is scanned"));
        assertTrue(gotchas.contains("encountered filter") && gotchas.contains("OMISSION"));
        assertTrue(gotchas.contains("later operand is invisible"));
    }
}
