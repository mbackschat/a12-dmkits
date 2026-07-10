package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T3 — the {@code apply}-embedded {@code group}/{@code config}/{@code typedef} reads emit the SAME
 * {@code data} shape as their standalone read verbs (they used to return subsets — group 2 keys vs 8,
 * config 4 vs 10, typedef dropped {@code importedFrom}). Extracted to one shared builder
 * ({@code ReadPayloads}); this locks the parity end-to-end.
 */
class ApplyReadPayloadParityTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode standaloneData(Path model, String... verb) throws Exception {
        String[] args = new String[verb.length + 2];
        args[0] = "-m";
        args[1] = model.toString();
        System.arraycopy(verb, 0, args, 2, verb.length);
        return M.readTree(CliTestSupport.run(args).out()).path("data");
    }

    @Test
    void applyEmbeddedReadsMatchTheStandaloneShape(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        JsonNode stdGroup = standaloneData(model, "group", "read", "/Order/ShippingAddress");
        JsonNode stdConfig = standaloneData(model, "config", "read");
        JsonNode stdTypedefs = standaloneData(model, "typedef", "read");

        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
            [ {"target":"group","op":"read","groupPathInModel":"/Order/ShippingAddress"},
              {"target":"config","op":"read"},
              {"target":"typedef","op":"read"} ]""");
        JsonNode results = M.readTree(CliTestSupport.run("-m", model.toString(), "apply", ops.toString()).out())
                .path("results");

        assertEquals(stdGroup, results.get(0).path("data"), "apply group read == standalone group read");
        assertEquals(stdConfig, results.get(1).path("data"), "apply config read == standalone config read");
        assertEquals(stdTypedefs, results.get(2).path("data"), "apply typedef read == standalone typedef read");
        // sanity: the shared shape is the RICH one, not the old subset
        assertTrue(stdGroup.has("fields"), () -> "the group payload carries the full shape: " + stdGroup);
        assertTrue(stdConfig.has("locales"), () -> "the config payload carries the full shape: " + stdConfig);
    }
}
