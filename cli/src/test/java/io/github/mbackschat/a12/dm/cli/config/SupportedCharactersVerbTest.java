package io.github.mbackschat.a12.dm.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/** Config authoring serves and enforces the singleton/range/atomic-grapheme contract on every CLI path. */
class SupportedCharactersVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String E_ACUTE = "e\u0301";

    @Test
    void helpManifestAndSchemaDescribeAtomicEntriesAndTheBmpDefault() throws Exception {
        String help = CliTestSupport.run("config", "modify", "--help").out();
        assertTrue(help.contains("atomic combined grapheme"), help);
        assertTrue(help.contains("full BMP"), help);

        JsonNode manifest = MAPPER.readTree(CliTestSupport.run("manifest").out());
        JsonNode option = findParam(manifest, "config modify", "--supported-character");
        assertTrue(option.path("description").asText().contains("atomic combined grapheme"), option::toString);
        assertTrue(option.path("description").asText().contains("full BMP"), option::toString);

        JsonNode schema = MAPPER.readTree(CliTestSupport.run("schema", "apply").out());
        String description = schema.path("items").path("properties")
                .path("supported-character").path("description").asText();
        assertTrue(description.contains("atomic combined grapheme"), schema::toString);
        assertTrue(description.contains("full BMP"), schema::toString);
    }

    @Test
    void modifyAndApplyPreserveOneCombinedGraphemeEntry(@TempDir Path dir) throws Exception {
        Path standalone = newModel(dir.resolve("standalone.dm.json"), "standalone");
        var modified = CliTestSupport.run("-m", standalone.toString(), "config", "modify",
                "--supported-character", E_ACUTE);
        assertEquals(0, modified.code(), modified.err());
        assertEquals(E_ACUTE, readCharacters(standalone).get(0).asText());
        assertEquals(1, readCharacters(standalone).size());

        Path applied = newModel(dir.resolve("applied.dm.json"), "applied");
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [{"target":"config","op":"modify","supported-character":["e\\u0301"]}]
                """);
        var result = CliTestSupport.run("-m", applied.toString(), "apply", ops.toString());
        assertEquals(0, result.code(), result.err());
        assertEquals(E_ACUTE, readCharacters(applied).get(0).asText());
        assertEquals(1, readCharacters(applied).size());
    }

    @Test
    void invalidDefinitionsReceiveTheSameCorrectiveDiagnosticOnBothMutationPaths(@TempDir Path dir)
            throws Exception {
        Path standalone = newModel(dir.resolve("standalone.dm.json"), "standalone");
        var modified = CliTestSupport.run("-m", standalone.toString(), "config", "modify",
                "--supported-character", "ab");
        assertEquals(2, modified.code(), modified.err());
        JsonNode standaloneEnvelope = MAPPER.readTree(modified.out());
        assertCorrective(standaloneEnvelope.path("diagnostics").get(0));

        Path applied = newModel(dir.resolve("applied.dm.json"), "applied");
        Path ops = dir.resolve("invalid-ops.json");
        Files.writeString(ops, """
                [{"target":"config","op":"modify","supported-character":["ab"]}]
                """);
        var result = CliTestSupport.run("-m", applied.toString(), "apply", ops.toString());
        JsonNode envelope = MAPPER.readTree(result.out());
        // The aggregate now reports the KIND of stop, and its exit follows from that rather than from a blanket
        // "not committed -> 1". Asserting the per-op outcome alongside pins WHY this exit is the right one.
        assertEquals("refused", envelope.path("data").path("results").get(0).path("outcome").asText(), result::out);
        assertEquals(2, result.code(), result::err);
        JsonNode diagnostic = envelope.path("data").path("results").get(0).path("diagnostics").get(0);
        assertCorrective(diagnostic);
    }

    private static Path newModel(Path path, String id) {
        var created = CliTestSupport.run("model", "new", "--id", id, "--locale", "en_US",
                "--root", "Root", "-o", path.toString());
        assertEquals(0, created.code(), created.err());
        return path;
    }

    private static JsonNode readCharacters(Path model) throws Exception {
        var read = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertEquals(0, read.code(), read.err());
        return MAPPER.readTree(read.out()).path("data").path("supportedCharacters");
    }

    private static void assertCorrective(JsonNode diagnostic) {
        assertEquals("RK_INVALID_CONFIG_VALUE", diagnostic.path("code").asText());
        assertTrue(diagnostic.path("summary").asText().contains("combined grapheme"), diagnostic::toString);
        assertTrue(diagnostic.path("fix").asText().contains("singleton"), diagnostic::toString);
        assertTrue(diagnostic.path("fix").asText().contains("range"), diagnostic::toString);
        assertTrue(diagnostic.path("fix").asText().contains("atomic combined grapheme"), diagnostic::toString);
    }

    private static JsonNode findParam(JsonNode manifest, String verb, String flag) {
        for (JsonNode entry : manifest.path("verbs")) {
            if (!verb.equals(entry.path("verb").asText())) {
                continue;
            }
            for (JsonNode param : entry.path("params")) {
                if (flag.equals(param.path("flag").asText())) {
                    return param;
                }
            }
        }
        throw new AssertionError("manifest lacks " + verb + " " + flag);
    }
}
