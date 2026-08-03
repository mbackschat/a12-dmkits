package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T3 — the {@code apply}-embedded {@code field}/{@code group}/{@code config}/{@code typedef}/{@code include}
 * reads emit the SAME
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
        JsonNode stdField = standaloneData(model, "field", "read", "/Order/Quantity");
        JsonNode stdGroup = standaloneData(model, "group", "read", "/Order/ShippingAddress");
        JsonNode stdConfig = standaloneData(model, "config", "read");
        JsonNode stdTypedefs = standaloneData(model, "typedef", "read");

        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
            [ {"target":"field","op":"read","fieldPathInModel":"/Order/Quantity"},
              {"target":"group","op":"read","groupPathInModel":"/Order/ShippingAddress"},
              {"target":"config","op":"read"},
              {"target":"typedef","op":"read"} ]""");
        JsonNode results = M.readTree(CliTestSupport.run("-m", model.toString(), "apply", ops.toString()).out())
                .path("data").path("results");

        assertEquals(stdField, results.get(0).path("data"), "apply field read == standalone field read");
        assertEquals(stdGroup, results.get(1).path("data"), "apply group read == standalone group read");
        assertEquals(stdConfig, results.get(2).path("data"), "apply config read == standalone config read");
        assertEquals(stdTypedefs, results.get(3).path("data"), "apply typedef read == standalone typedef read");
        // sanity: the shared shape is the RICH one, not the old subset
        assertTrue(stdGroup.has("fields"), () -> "the group payload carries the full shape: " + stdGroup);
        assertTrue(stdConfig.has("locales"), () -> "the config payload carries the full shape: " + stdConfig);
    }

    @Test
    void applyEmbeddedIncludeListMatchesTheStandaloneShape(@TempDir Path dir) throws Exception {
        Path model = copyModel("/models/multifile/app/storefront.dm.json", dir.resolve("storefront.dm.json"));
        copyModel("/models/multifile/lib/catalog.dm.json", dir.resolve("catalog.dm.json"));
        JsonNode standalone = standaloneData(model, "include", "read");
        Path ops = dir.resolve("include-read.json");
        Files.writeString(ops, "[{\"target\":\"include\",\"op\":\"read\"}]");

        JsonNode embedded = M.readTree(CliTestSupport.run("-m", model.toString(), "-w", dir.toString(),
                "apply", ops.toString()).out()).path("data").path("results").get(0).path("data");

        assertEquals(standalone, embedded, "apply include read == standalone include read");
        assertEquals("catalog", embedded.path("includes").get(0).path("alias").asText());
    }

    private static Path copyModel(String resource, Path target) throws Exception {
        try (InputStream input = ApplyReadPayloadParityTest.class.getResourceAsStream(resource)) {
            assertTrue(input != null, () -> "missing " + resource);
            Files.write(target, input.readAllBytes());
        }
        return target;
    }
}
