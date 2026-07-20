package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The authoring catalog must expose RNU's structural law separately from its ordinary leaf semantics. */
class RepetitionNotUniqueCatalogTest {

    @Test
    void exposesLegalityParticipationEqualityAndComposition() {
        OperatorEntry entry = OperatorCatalog.load().byId("RepetitionNotUnique").orElseThrow();
        String constraints = String.join("\n", entry.constraints());
        String gotchas = entry.gotchas().stream().map(OperatorEntry.Gotcha::text)
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(constraints.contains("at most one RepetitionNotUnique"));
        assertTrue(constraints.contains("MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE"));
        assertTrue(gotchas.contains("ordinary three-valued leaf"));
        assertTrue(gotchas.contains("full scope") && gotchas.contains("sibling guard"));
        assertTrue(gotchas.contains("required-empty") && gotchas.contains("optional-empty"));
        assertTrue(gotchas.contains("scale-insensitive") && gotchas.contains("every duplicate row"));
        assertTrue(gotchas.contains("MVK_NEG_CONDITION_IN_ITERATION"));
    }
}
