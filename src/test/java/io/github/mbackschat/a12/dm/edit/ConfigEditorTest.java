package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * L2c — the {@link ConfigEditor}: a single chaining handle over the model's doc-config, gathering the
 * otherwise-scattered setters under {@code s.config()…}. Forwards to the {@link EditSession} config verbs.
 */
class ConfigEditorTest {

    @Test
    void editsDocConfigFluently() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());

        s.config()
                .decimalSeparator(",")
                .timeZone("UTC")
                .conditionLanguage("de_DE")
                .fieldRefByShortNameAllowed(false)
                .baseYear(2030);
        s.validate();

        ModelDocument doc = ModelDocument.read(s.toJson());
        assertEquals(",", doc.config().decimalSeparator());
        assertEquals("UTC", doc.config().timeZone());
        assertFalse(doc.config().fieldRefByShortNameAllowed());
        assertTrue(s.toJson().contains("2030"));   // baseYear written (string-valued; no getter on DocConfig)
    }
}
