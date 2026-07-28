package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * G43 — {@code model eval --relevant} exposes the interpreter's already kernel-matched partial-validation
 * boundary. The CLI encoding is a JSON array of instance paths: an omitted index or {@code [*]} means the
 * kernel's {@code ALL_SEMANTIC}; {@code [n]} selects one repetition.
 */
class PartialValidationCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void relevantPageRunsOnlyItsRulesPlusGlobalFields(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path doc = document(dir, """
                { "Order": {} }
                """);

        JsonNode full = data(CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc.toString(), "--no-computations"));
        assertEquals(Set.of("NAME_MISSING", "CODE_MISSING", "GLOBAL_MISSING"), fired(full));

        JsonNode partial = data(CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc.toString(), "--no-computations",
                "--relevant", "[\"/Order/Name\"]"));
        assertEquals("partial", partial.path("validationMode").asText());
        assertEquals(Set.of("NAME_MISSING", "GLOBAL_MISSING"), fired(partial),
                "the selected page field runs; an omitted peer does not; a global field is auto-relevant");
    }

    @Test
    void concreteAndWildcardRepetitionIndicesMapToRelevantEntity(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path doc = document(dir, """
                { "Order": { "Lines": [
                  { "Quantity": "one" },
                  { "Quantity": "two" }
                ] } }
                """);

        JsonNode rowTwo = data(CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc.toString(), "--no-computations",
                "--relevant", "[\"/Order/Lines[2]/Quantity\"]"));
        assertEquals(1, messages(rowTwo, "QUANTITY_MISSING"));
        assertTrue(java.util.stream.StreamSupport.stream(rowTwo.path("messages").spliterator(), false)
                .filter(message -> "QUANTITY_MISSING".equals(message.path("code").asText()))
                .allMatch(message -> message.path("field").asText().contains("Lines[2]")));

        JsonNode everyRow = data(CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc.toString(), "--no-computations",
                "--relevant", "[\"/Order/Lines[*]/Quantity\"]"));
        assertEquals(2, messages(everyRow, "QUANTITY_MISSING"));
    }

    @Test
    void malformedOrUnknownRelevantPointerRefusesBeforeEvaluation(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path doc = document(dir, "{ \"Order\": {} }");

        var malformed = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc.toString(), "--relevant", "[\"/Order/Lines[0]/Quantity\"]");
        assertEquals(2, malformed.code());
        assertTrue(malformed.err().contains("positive 1-based index"), malformed::err);

        var unknown = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc.toString(), "--relevant", "[\"/Order/Nmae\"]");
        assertEquals(2, unknown.code());
        assertTrue(unknown.err().contains("/Order/Nmae"), unknown::err);
        assertTrue(unknown.err().contains("/Order/Name"), unknown::err);
    }

    private static Path model(Path dir) throws Exception {
        String base = DocumentModelDraft.named("partial-cli").locale("en_US")
                .rootGroup("Order", order -> order
                        .stringField("Name", field -> {})
                        .stringField("Code", field -> {})
                        .stringField("GlobalFlag", field -> field.global())
                        .group("Lines", lines -> lines.repeatable(5).stringField("Quantity", field -> {})))
                .build().toJson();
        EditSession session = EditSession.read(base);
        session.addRule(m -> m.rule(m.group("/Order"))
                .name("NameMissing").errorField(m.str("/Order/Name"))
                .when(m.str("/Order/Name").notFilled())
                .code("NAME_MISSING").severity(Severity.ERROR).message(Dsl.en("Name missing.")).build());
        session.addRule(m -> m.rule(m.group("/Order"))
                .name("CodeMissing").errorField(m.str("/Order/Code"))
                .when(m.str("/Order/Code").notFilled())
                .code("CODE_MISSING").severity(Severity.ERROR).message(Dsl.en("Code missing.")).build());
        session.addRule(m -> m.rule(m.group("/Order"))
                .name("GlobalMissing").errorField(m.str("/Order/GlobalFlag"))
                .when(m.str("/Order/GlobalFlag").notFilled())
                .code("GLOBAL_MISSING").severity(Severity.ERROR).message(Dsl.en("Global missing.")).build());
        session.addRule(m -> m.rule(m.group("/Order"))
                .name("QuantityMissing").errorField(m.str("/Order/Lines/Quantity"))
                .when(m.str("/Order/Lines/Quantity").filled())
                .code("QUANTITY_MISSING").severity(Severity.ERROR).message(Dsl.en("Quantity supplied.")).build());
        Path model = dir.resolve("partial.dm.json");
        Files.writeString(model, session.toJson());
        return model;
    }

    private static Path document(Path dir, String json) throws Exception {
        Path doc = dir.resolve("document-" + Integer.toHexString(json.hashCode()) + ".json");
        Files.writeString(doc, json);
        return doc;
    }

    private static JsonNode data(CliTestSupport.Result result) throws Exception {
        assertEquals(0, result.code(), result::err);
        return MAPPER.readTree(result.out()).path("data");
    }

    private static Set<String> fired(JsonNode data) {
        return java.util.stream.StreamSupport.stream(data.path("fired").spliterator(), false)
                .map(JsonNode::asText).collect(java.util.stream.Collectors.toSet());
    }

    private static int messages(JsonNode data, String code) {
        int count = 0;
        for (JsonNode message : data.path("messages")) {
            if (code.equals(message.path("code").asText())) {
                count++;
            }
        }
        return count;
    }
}
