package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Self-describe completeness (CLI-CONFORMANCE-SPEC, AP5 guard) — derived from the live tree, so a new verb
 * can't ship under-described: every verb has a non-blank description, every param has a non-blank
 * description, and every verb that advertises a {@code schema} pointer actually resolves it. Complements
 * {@code ManifestVerbTest} (which pins the verb list) and {@code ParamKindManifestTest} (the param kinds).
 */
class ManifestCompletenessTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void everyVerbAndParamIsDescribed() throws Exception {
        JsonNode manifest = M.readTree(CliTestSupport.run("manifest").out());
        for (JsonNode v : manifest.path("verbs")) {
            String verb = v.path("verb").asText();
            assertFalse(v.path("description").asText().isBlank(), () -> verb + " needs a description");
            for (JsonNode p : v.path("params")) {
                assertFalse(p.path("description").asText().isBlank(),
                        () -> verb + " param '" + p.path("key").asText() + "' needs a description");
            }
        }
    }

    @Test
    void everyAdvertisedSchemaPointerResolves() throws Exception {
        JsonNode manifest = M.readTree(CliTestSupport.run("manifest").out());
        for (JsonNode v : manifest.path("verbs")) {
            if (!v.hasNonNull("schema")) {
                continue;
            }
            String[] schemaArgs = ("schema " + v.path("schema").asText()).split(" ");
            CliTestSupport.Result res = CliTestSupport.run(schemaArgs);
            assertEquals(0, res.code(),
                    () -> v.path("verb").asText() + " advertises schema '" + v.path("schema").asText()
                            + "' but `" + String.join(" ", schemaArgs) + "` failed: " + res.err());
            // and it is real JSON (a usable contract, not an error blob)
            M.readTree(res.out());
        }
    }
}
