package io.github.mbackschat.a12.dm.cli.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

class ModelExpandCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void expandsIncludesAndImportedTypedefsIntoOneBareModelArtifact(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("benefits.dm.json"), benefits());
        Files.writeString(dir.resolve("catalogtypes.dm.json"), catalogTypes());
        Path entry = dir.resolve("policy.dm.json");
        Files.writeString(entry, policy());

        CliTestSupport.Result result = CliTestSupport.run(
                "-m", entry.toString(), "model", "expand");

        assertEquals(0, result.code(), () -> result.out() + result.err());
        JsonNode expanded = JSON.readTree(result.out());
        assertEquals("policy", expanded.path("header").path("id").asText());
        assertTrue(result.out().contains("\"Cover\""), "the included model is mounted");
        assertTrue(result.out().contains("\"Premium\""), "the imported typedef field remains present");
        assertFalse(result.out().contains("TypeDefType"), "every typedef use is concrete");
        assertFalse(result.out().contains("\"modelAlias\""), "include directives are consumed");
    }

    @Test
    void outputIsWrittenOnlyAfterExpansionSucceeds(@TempDir Path dir) throws Exception {
        Path entry = dir.resolve("policy.dm.json");
        Files.writeString(entry, policy());
        Path output = dir.resolve("expanded.dm.json");

        CliTestSupport.Result failed = CliTestSupport.run(
                "-m", entry.toString(), "model", "expand", "-o", output.toString());

        assertFalse(Files.exists(output), "an unresolved closure never leaves a partial artifact");
        assertTrue(failed.code() != 0, () -> failed.out() + failed.err());
        assertEquals("RK_UNRESOLVED_REFERENCE",
                JSON.readTree(failed.out()).path("diagnostics").path(0).path("code").asText());

        Files.writeString(dir.resolve("benefits.dm.json"), benefits());
        Files.writeString(dir.resolve("catalogtypes.dm.json"), catalogTypes());
        CliTestSupport.Result succeeded = CliTestSupport.run(
                "-m", entry.toString(), "model", "expand", "-o", output.toString());

        assertEquals(0, succeeded.code(), () -> succeeded.out() + succeeded.err());
        assertEquals("applied", JSON.readTree(succeeded.out()).path("outcome").asText());
        assertEquals("policy", JSON.readTree(Files.readString(output)).path("header").path("id").asText());
    }

    @Test
    void conversionStopsBeforeEvaluatorOnlyTimeZoneChecks(@TempDir Path dir) throws Exception {
        String base = DocumentModelDraft.named("travel").locale("en_US")
                .rootGroup("Travel", group -> group.field("Destination", FieldKind.STRING))
                .build().toJson();
        EditSession edit = EditSession.read(base);
        edit.config().timeZone("Europe/Paris");
        Path entry = dir.resolve("travel.dm.json");
        Files.writeString(entry, edit.toJson());
        Path instance = dir.resolve("instance.json");
        Files.writeString(instance, "{\"fields\":{}}");

        CliTestSupport.Result expanded = CliTestSupport.run(
                "-m", entry.toString(), "model", "expand");
        CliTestSupport.Result evaluated = CliTestSupport.run(
                "-m", entry.toString(), "model", "eval", "--instance", instance.toString());

        assertEquals(0, expanded.code(), () -> expanded.out() + expanded.err());
        assertEquals("Europe/Paris",
                JSON.readTree(expanded.out()).path("content").path("modelConfig").path("timeZone").asText());
        assertTrue(evaluated.code() != 0, "the runtime evaluator still refuses an unsupported time zone");
    }

    private static String benefits() {
        return DocumentModelDraft.named("benefits").locale("en_US")
                .rootGroup("Benefits", group -> group.numberField("Coverage", field -> field.fractionDigits(2)))
                .build().toJson();
    }

    private static String catalogTypes() {
        return DocumentModelDraft.typeDefModel("catalogtypes").locale("en_US")
                .typeDef("Money", type -> type.number(number -> number.fractionDigits(2)))
                .build().toJson();
    }

    private static String policy() {
        return DocumentModelDraft.named("policy").locale("en_US")
                .include("benefits", "benefits")
                .importTypeDefs("catalogtypes")
                .rootGroup("Policy", group -> group
                        .mount("Cover", "benefits")
                        .typedefField("Premium", "Money"))
                .build().toJson();
    }
}
