package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Served authoring guidance for {@code Having}'s retention and correlation laws. */
class HavingRetentionCatalogTest {

    @Test
    void havingExplainsKnownTrueRetentionAndExplicitSelfExclusion() {
        OperatorEntry having = OperatorCatalog.load().byId("Having").orElseThrow();
        String guidance = (having.meaning() + "\n" + having.gotchas().stream()
                .map(OperatorEntry.Gotcha::text)
                .reduce("", (left, right) -> left + "\n" + right))
                .toLowerCase(Locale.ROOT);

        assertTrue(guidance.contains("known true"));
        assertTrue(guidance.contains("false or unknown"));
        assertTrue(guidance.contains("before") && guidance.contains("selected cell"));
        assertTrue(guidance.contains("includes the current outer coordinate"));
        assertTrue(guidance.contains("currentrepetition"));
    }
}
