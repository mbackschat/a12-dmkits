package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@code manifest} verb — the machine-readable map of every verb + its parameters, the CLI's
 * agent-exploration surface (CLI-SPEC §1a). Doubles as the <b>discoverability guardian</b> (F11): the
 * known verb set must stay in the manifest with descriptions, so a rename/removal can't silently drop
 * a verb off the self-describing surface.
 */
class ManifestVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> EXPECTED_VERBS = List.of(
            "export", "describe", "validate", "check", "eval-doc", "compute-doc", "add", "read", "explain",
            "where-used", "modify", "batch", "schema", "operators", "manifest");

    @Test
    void listsEveryVerbWithADescription() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("manifest");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());

        Set<String> verbs = new HashSet<>();
        for (JsonNode v : j.path("verbs")) {
            verbs.add(v.path("verb").asText());
            assertTrue(!v.path("description").asText().isBlank(),
                    () -> "verb " + v.path("verb").asText() + " has a description");
        }
        for (String expected : EXPECTED_VERBS) {
            assertTrue(verbs.contains(expected), () -> "manifest is missing verb '" + expected + "': " + verbs);
        }
    }

    @Test
    void describesAVerbsParametersAndRequiredness() throws Exception {
        JsonNode check = verb("check");
        boolean fieldRequired = false;
        boolean groupOptional = false;
        for (JsonNode p : check.path("params")) {
            if ("--field".equals(p.path("name").asText())) {
                fieldRequired = p.path("required").asBoolean();
            }
            if ("--group".equals(p.path("name").asText())) {
                groupOptional = !p.path("required").asBoolean();
            }
        }
        assertTrue(fieldRequired, "check's --field is required");
        assertTrue(groupOptional, "check's --group is optional");
    }

    private static JsonNode verb(String name) throws Exception {
        JsonNode j = MAPPER.readTree(CliTestSupport.run("manifest").out());
        for (JsonNode v : j.path("verbs")) {
            if (name.equals(v.path("verb").asText())) {
                return v;
            }
        }
        throw new AssertionError("no verb '" + name + "' in manifest");
    }
}
