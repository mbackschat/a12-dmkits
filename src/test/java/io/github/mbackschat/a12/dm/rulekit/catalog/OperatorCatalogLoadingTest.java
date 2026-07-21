package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** Locks the immutable operator catalog's process-wide loading boundary. */
class OperatorCatalogLoadingTest {

    @Test
    void repeatedConsumersReuseTheParsedCatalog() {
        assertSame(OperatorCatalog.load(), OperatorCatalog.load(),
                "renderers and diagnostics must not reparse the catalog resource per use");
    }
}
