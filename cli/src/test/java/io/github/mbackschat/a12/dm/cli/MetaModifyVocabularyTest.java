package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * G45/T1 — {@code meta} is the sole post-creation metadata writer. Its explicit long names are canonical in
 * the manifest; the shorter names remain ergonomic synonyms on that same route.
 */
class MetaModifyVocabularyTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode metaData(Path model, String ref) throws Exception {
        return M.readTree(CliTestSupport.run("-m", model.toString(), "meta", ref).out()).path("data");
    }

    @Test
    void metaAcceptsLongCanonicalNames(@TempDir Path dir) throws Exception {
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
    void shortAliasesWorkForRuleMetadata(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "meta", "/Order/EligibilityCheck",
                "--internal", "en_US=note").code());
        var rm = CliTestSupport.run("-m", model.toString(), "meta", "/Order/EligibilityCheck",
                "--remove-internal", "en_US");
        assertEquals(0, rm.code(), rm::err);
        assertTrue(metaData(model, "/Order/EligibilityCheck").path("internalDescription").isEmpty(),
                "the short meta aliases removed the rule's internal description");
    }

    @Test
    void shortAliasesWorkForGroupMetadata(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "meta", "/Order/ShippingAddress",
                "--external", "en_US=addr").code());
        var rm = CliTestSupport.run("-m", model.toString(), "meta", "/Order/ShippingAddress",
                "--remove-external", "en_US");
        assertEquals(0, rm.code(), rm::err);
        assertTrue(metaData(model, "/Order/ShippingAddress").path("externalDescription").isEmpty(),
                "the short meta aliases removed the group's external description");
    }
}
