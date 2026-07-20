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

    // The canonical target-first surface (the legacy flat verbs are hidden aliases, absent from the manifest).
    private static final List<String> EXPECTED_VERBS = List.of(
            "model new", "model info", "model check", "model describe", "model diff", "model read", "model usage", "model report", "model rename", "model normalize", "model eval", "model compute", "model seed",
            "model import-jsonschema", "model export-jsonschema",
            "profile extract", "profile compare",
            "rule check", "rule read", "rule explain", "rule deps", "rule format", "rule eval", "rule add",
            "rule modify", "rule remove", "rule rename",
            "computation add", "computation read", "computation explain", "computation format",
            "computation modify", "computation remove",
            "field add", "field modify", "field read", "field remove", "field rename", "field move",
            "group add", "group multiselect", "group attachment", "group read", "group modify", "group remove",
            "group rename", "group move", "group extract",
            "typedef add", "typedef read", "typedef remove", "typedef rename", "typedef extract", "typedef inline",
            "typedef import", "typedef unimport",
            "include add", "include read", "include set-reference", "include rename", "include remove", "include inline",
            "config read", "config modify",
            "workspace list", "workspace graph", "workspace check", "workspace roles",
            "where-used", "meta", "export", "batch", "apply", "schema", "operators", "patterns", "diagnostics", "manifest");

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
        JsonNode check = verb("rule check");
        assertEquals("rule", check.path("target").asText(), "the verb carries its op-record target");
        assertEquals("check", check.path("op").asText(), "the verb carries its op-record op");
        boolean fieldRequired = false;
        boolean groupOptional = false;
        for (JsonNode p : check.path("params")) {
            if ("--field".equals(p.path("flag").asText())) {
                fieldRequired = p.path("required").asBoolean();
                assertEquals("field", p.path("key").asText(), "the op-record key strips the dashes");
            }
            if ("--group".equals(p.path("flag").asText())) {
                groupOptional = !p.path("required").asBoolean();
            }
        }
        assertTrue(fieldRequired, "rule check's --field is required");
        assertTrue(groupOptional, "rule check's --group is optional");
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
