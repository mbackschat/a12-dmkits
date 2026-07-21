package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Keeps the author-facing group-presence descriptions aligned with the validation-stage contract. */
class GroupPresenceCatalogTest {

    private static final OperatorCatalog CATALOG = OperatorCatalog.load();

    @Test
    void distinguishesRepeatableRowsFromNonRepeatableAdmittedContent() {
        String filled = textOf("GroupFilled");
        String notFilled = textOf("GroupNotFilled");

        assertTrue(filled.contains("repeatable group") && filled.contains("row itself is content"));
        assertTrue(filled.contains("non-repeatable group") && filled.contains("formally admitted"));
        assertTrue((filled + notFilled).contains("invalid-only") || (filled + notFilled).contains("only formally invalid"));
        assertTrue((filled + notFilled).contains("neither filled nor definitely empty"));
    }

    @Test
    void everyGroupListPredicateExplainsTheUnavailableBucket() {
        for (String id : List.of(
                "AllGroupsFilled",
                "NoGroupFilled",
                "AtLeastOneGroupFilled",
                "NotAllGroupsFilled",
                "GroupsNotCollectivelyFilled")) {
            String text = textOf(id);
            assertTrue(text.contains("definitely filled") || text.contains("definitely-filled"), id);
            assertTrue(text.contains("definitely empty") || text.contains("definitely-empty"), id);
            assertTrue(text.contains("erroneous") && text.contains("neither"), id);
        }
    }

    @Test
    void distinguishesPlainUnavailableCountFromStructuralStarredCount() {
        String count = textOf("NumberOfFilledGroups");

        assertTrue(count.contains("plain multi-group") && count.contains("entire count unavailable"));
        assertTrue(count.contains("starred single-group") && count.contains("structural"));
        assertTrue(count.contains("instantiated repeat") && count.contains("formally invalid"));
    }

    private static String textOf(String id) {
        OperatorEntry entry = CATALOG.byId(id).orElseThrow();
        return entry.meaning() + "\n" + entry.gotchas().stream()
                .map(OperatorEntry.Gotcha::text)
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
