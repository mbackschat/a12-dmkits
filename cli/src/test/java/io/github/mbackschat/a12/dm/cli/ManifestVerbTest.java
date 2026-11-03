package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@code manifest} verb — the machine-readable map of every verb + its parameters, the CLI's
 * agent-exploration surface (CLI-SPEC §2). Doubles as the <b>discoverability guardian</b> (F11): the
 * known verb set must stay in the manifest with descriptions, so a rename/removal can't silently drop
 * a verb off the self-describing surface.
 */
class ManifestVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // The canonical target-first surface (the legacy flat verbs are hidden aliases, absent from the manifest).
    // THE single source of the live-leaf denominator: `DmtoolPublicContractTest` derives its count from this
    // list's size rather than pinning its own number. It used to carry a literal, which then sat at 81 while
    // this list said 82 — adding `mcp` updated one and not the other. It survived because the lane that pins
    // it hangs off `check`, not `test`, and the local checkpoint ran `:cli:test`; CI does name the lane, but
    // nothing had been pushed for it to run on.
    static final List<String> EXPECTED_VERBS = List.of(
            "model new", "model info", "model check", "model describe", "model diff", "model read", "model usage", "model report", "model rename", "model normalize", "model expand", "model eval", "model compute", "model seed",
            "model import-jsonschema", "model export-jsonschema",
            "profile extract", "profile synthesize", "profile compare",
            "rule check", "rule read", "rule explain", "rule deps", "rule format", "rule eval", "rule add",
            "rule modify", "rule remove", "rule rename",
            "computation add", "computation read", "computation explain", "computation format",
            "computation modify", "computation remove",
            "field add", "field modify", "field read", "field remove", "field rename", "field move",
            "group add", "group multiselect", "group attachment", "group read", "group modify", "group remove",
            "group rename", "group move", "group extract",
            "typedef add", "typedef modify", "typedef read", "typedef remove", "typedef rename", "typedef extract", "typedef inline",
            "typedef import", "typedef unimport",
            "include add", "include read", "include set-reference", "include remove", "include inline",
            "config read", "config modify",
            "workspace list", "workspace graph", "workspace check", "workspace roles",
            "where-used", "meta", "export", "batch", "apply", "schema", "operators", "patterns", "diagnostics", "manifest",
            "mcp");

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
        Set<String> expected = new HashSet<>(EXPECTED_VERBS);
        assertEquals(EXPECTED_VERBS.size(), expected.size(), "EXPECTED_VERBS must not contain duplicates");
        assertEquals(expected, verbs,
                () -> "manifest verb set differs from the independent pin; missing from manifest: "
                        + difference(expected, verbs) + "; live verbs missing from EXPECTED_VERBS: "
                        + difference(verbs, expected));
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

    private static Set<String> difference(Set<String> minuend, Set<String> subtrahend) {
        Set<String> difference = new TreeSet<>(minuend);
        difference.removeAll(subtrahend);
        return difference;
    }
}
