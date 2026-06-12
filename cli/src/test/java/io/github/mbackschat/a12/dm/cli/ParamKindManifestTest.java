package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * {@code @ParamKind} surfaces in the {@code manifest} (CLI-CONFORMANCE-SPEC) — so a cold agent learns what
 * each arg is (a FIELD_PATH that must exist vs a NEW_NAME you choose), and the conformance fuzzer can read
 * it. Tagged params carry a {@code kind}; untagged ones omit it (it's optional, additive metadata).
 */
class ParamKindManifestTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Map<String, JsonNode> paramsOf(String verb) throws Exception {
        JsonNode root = M.readTree(CliTestSupport.run("manifest").out());
        Map<String, JsonNode> out = new HashMap<>();
        for (JsonNode v : root.path("verbs")) {
            if (verb.equals(v.path("verb").asText())) {
                for (JsonNode p : v.path("params")) {
                    out.put(p.path("key").asText(), p);
                }
            }
        }
        return out;
    }

    @Test
    void fieldMoveParamsCarryTheirKind() throws Exception {
        Map<String, JsonNode> p = paramsOf("field move");
        assertEquals("FIELD_PATH", p.get("fieldPathInModel").path("kind").asText());
        assertEquals("GROUP_PATH", p.get("to").path("kind").asText());
    }

    @Test
    void untaggedParamsOmitKind() throws Exception {
        Map<String, JsonNode> p = paramsOf("field move");
        assertFalse(p.get("output").has("kind"), "an untagged param carries no kind (additive metadata)");
    }

    @Test
    void renameToIsANewNameNotAPath() throws Exception {
        // the rename `--to` is a NEW_NAME (you choose it); the move `--to` is a GROUP_PATH (must exist) —
        // the kind disambiguates two same-named flags an agent would otherwise have to guess between.
        assertEquals("NEW_NAME", paramsOf("field rename").get("to").path("kind").asText());
        assertEquals("GROUP_PATH", paramsOf("field move").get("to").path("kind").asText());
    }
}
