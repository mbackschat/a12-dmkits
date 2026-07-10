package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T1 — the {@code meta} verb and the {@code rule}/{@code group} {@code modify} verbs edit the same metadata
 * (annotations + internal/external descriptions) but had drifted vocabularies: {@code meta} used
 * {@code --annotate}/{@code --internal}/{@code --external} + a full removal set, while {@code modify} used
 * {@code --annotation}/{@code --internal-description}/{@code --external-description} and could not remove a
 * description. Reconciled: {@code meta} accepts the modify vocabulary as discoverable aliases, and
 * {@code modify} gained the description-removal peers.
 */
class MetaModifyVocabularyTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode metaData(Path model, String ref) throws Exception {
        return M.readTree(CliTestSupport.run("-m", model.toString(), "meta", ref).out()).path("data");
    }

    @Test
    void metaAcceptsTheModifyVocabularyAsAliases(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var set = CliTestSupport.run("-m", model.toString(), "meta", "/Order",
                "--annotation", "owner=team", "--internal-description", "en_US=note");
        assertEquals(0, set.code(), set::err);
        JsonNode data = metaData(model, "/Order");
        assertEquals("team", data.path("annotations").path("owner").asText());
        assertEquals("note", data.path("internalDescription").path("en_US").asText());

        var rm = CliTestSupport.run("-m", model.toString(), "meta", "/Order",
                "--remove-internal-description", "en_US", "--remove-annotation", "owner");
        assertEquals(0, rm.code(), rm::err);
        JsonNode after = metaData(model, "/Order");
        assertTrue(after.path("annotations").isEmpty(), "annotation removed via the shared vocabulary");
        assertTrue(after.path("internalDescription").isEmpty(), "internal description removed via the alias");
    }

    @Test
    void ruleModifyCanRemoveADescription(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "rule", "modify", "/Order/EligibilityCheck",
                "--internal-description", "en_US=note").code());
        var rm = CliTestSupport.run("-m", model.toString(), "rule", "modify", "/Order/EligibilityCheck",
                "--remove-internal-description", "en_US");
        assertEquals(0, rm.code(), rm::err);
        assertTrue(metaData(model, "/Order/EligibilityCheck").path("internalDescription").isEmpty(),
                "rule modify removed the internal description");
    }

    @Test
    void groupModifyCanRemoveADescription(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "group", "modify", "/Order/ShippingAddress",
                "--external-description", "en_US=addr").code());
        var rm = CliTestSupport.run("-m", model.toString(), "group", "modify", "/Order/ShippingAddress",
                "--remove-external-description", "en_US");
        assertEquals(0, rm.code(), rm::err);
        assertTrue(metaData(model, "/Order/ShippingAddress").path("externalDescription").isEmpty(),
                "group modify removed the external description");
    }
}
