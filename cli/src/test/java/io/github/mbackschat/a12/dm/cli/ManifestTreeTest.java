package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The manifest must expose the full {@code target → op} tree (CLI-SPEC §2) so a cold agent discovers a
 * nested op like {@code model check} in one call — the #1 self-explaining requirement. The runnable
 * leaf ops carry qualified verb names; the bare group ({@code doc}) is not itself an op. The {@code
 * -m/--model} context option is inherited by every verb, so it is listed once, globally.
 */
class ManifestTreeTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void recursesIntoTargetGroupsSoNestedOpsAreDiscoverable() throws Exception {
        JsonNode j = M.readTree(CliTestSupport.run("manifest").out());
        Set<String> verbs = new HashSet<>();
        JsonNode docValidate = null;
        for (JsonNode v : j.path("verbs")) {
            String name = v.path("verb").asText();
            verbs.add(name);
            if ("model check".equals(name)) {
                docValidate = v;
            }
        }
        assertTrue(verbs.contains("model check"), () -> "nested op 'model check' must be discoverable: " + verbs);
        assertFalse(verbs.contains("model"), "the bare group 'model' is not itself a runnable op");
        assertTrue(docValidate != null && !docValidate.path("description").asText().isBlank(),
                "the nested op carries a description");
        assertTrue(docValidate.path("params").isArray(), "the nested op lists its params");
    }

    @Test
    void pointsAtTheUniversalResultSchema() throws Exception {
        JsonNode j = M.readTree(CliTestSupport.run("manifest").out());
        assertEquals("result", j.path("resultSchema").asText(),
                "manifest must name the universal output schema so an agent fetches it via `schema result`");
    }

    @Test
    void exposesTheModelContextOptionGloballyNotPerVerb() throws Exception {
        JsonNode j = M.readTree(CliTestSupport.run("manifest").out());
        Set<String> globals = new HashSet<>();
        for (JsonNode o : j.path("globalOptions")) {
            globals.add(o.path("flag").asText());
        }
        assertTrue(globals.contains("--model"), () -> "manifest must expose -m/--model globally: " + globals);
    }
}
