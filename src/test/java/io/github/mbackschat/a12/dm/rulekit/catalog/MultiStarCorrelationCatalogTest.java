package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Served authoring guidance for the two coordinated multi-star correlation constructs. */
class MultiStarCorrelationCatalogTest {

    @Test
    void havingAndCurrentRepetitionCrossLinkBothMultiStarShapes() {
        OperatorCatalog catalog = OperatorCatalog.load();
        OperatorEntry having = catalog.byId("Having").orElseThrow();
        OperatorEntry current = catalog.byId("CurrentRepetition").orElseThrow();

        assertTrue(having.seeAlso().contains("CurrentRepetition"));
        assertTrue(current.seeAlso().contains("Having"));
        assertTrue(current.validExample().dsl().contains("Aisles*/Bins*"));
        assertTrue(current.validExample().dsl().contains("CurrentRepetition($"));
        assertGuidance(having);
        assertGuidance(current);
    }

    private static void assertGuidance(OperatorEntry entry) {
        String gotchas = entry.gotchas().stream().map(OperatorEntry.Gotcha::text)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(gotchas.contains("first starred"), entry.id());
        assertTrue(gotchas.contains("leave an ancestor unstarred"), entry.id());
        assertTrue(gotchas.contains("leaf index"), entry.id());
    }
}
