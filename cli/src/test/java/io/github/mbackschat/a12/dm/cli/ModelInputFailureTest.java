package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ModelInputFailureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    enum Malformation {
        TRUNCATED, MISSING_RULE_REFERENCE, UNKNOWN_RULE_PROPERTY, ARRAY_INDEX_FIELD,
        MISSING_COMPUTATION_REFERENCE, MISSING_SORT_REFERENCE
    }

    @ParameterizedTest
    @EnumSource(Malformation.class)
    void malformedStoredModelsAreInputRefusals(Malformation malformation, @TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.json");
        Files.writeString(file, malformed(malformation));
        String original = Files.readString(file);
        for (String[] command : new String[][] {
                {"model", "check"}, {"model", "read"},
                {"group", "modify", "/Subscription", "--repeatable", "2"}}) {
            var arguments = new java.util.ArrayList<>(java.util.List.of("-m", file.toString()));
            arguments.addAll(java.util.List.of(command));
            var result = CliTestSupport.run(arguments.toArray(String[]::new));
            var envelope = MAPPER.readTree(result.out());
            assertEquals(2, result.code(), result::out);
            assertEquals("refused", envelope.path("outcome").asText(), result::out);
            assertFalse(envelope.path("ok").asBoolean());
            assertFalse(envelope.has("verification"), result::out);
            assertFalse(envelope.has("valid"), result::out);
            var diagnostic = envelope.path("diagnostics").path(0);
            assertEquals("PRECHECK", diagnostic.path("source").asText(), result::out);
            assertEquals("RK_MODEL_INPUT_INVALID", diagnostic.path("code").asText(), result::out);
            String detail = diagnostic.path("summary").asText();
            String expectedDetail = switch (malformation) {
                case TRUNCATED -> "line:";
                case MISSING_RULE_REFERENCE -> "errorEntityRelPath";
                case UNKNOWN_RULE_PROPERTY -> "customRefErrorEntitiesIfFilled";
                case ARRAY_INDEX_FIELD -> "indexFieldName";
                case MISSING_COMPUTATION_REFERENCE -> "computedFieldRelPath";
                case MISSING_SORT_REFERENCE -> "sortFields[].name";
            };
            assertTrue(detail.contains(expectedDetail), result::out);
            assertEquals(file.toString(), diagnostic.path("where").path("arg").asText(), result::out);
            assertFalse(envelope.path("summary").asText().contains("internally"), result::out);
            assertTrue(diagnostic.path("fix").asText().length() > 0, result::out);
            assertTrue(result.err().isBlank(), result::err);
            assertEquals(original, Files.readString(file));
        }
    }

    @Test
    void aValidStoredModelStillReachesTheConsistencyGate(@TempDir Path dir) throws Exception {
        Path file = CliTestSupport.modelWithRule(dir);
        var result = CliTestSupport.run("-m", file.toString(), "model", "check");
        assertEquals(0, result.code(), result::out);
        var envelope = MAPPER.readTree(result.out());
        assertTrue(envelope.path("valid").asBoolean(), result::out);
        assertEquals("KERNEL_CONFIRMED", envelope.path("verification").asText());
    }

    static String malformed(Malformation malformation) throws Exception {
        ObjectNode model = (ObjectNode) MAPPER.readTree(CliTestSupport.modelWithRuleAndComputationJson());
        switch (malformation) {
            case TRUNCATED -> { return "{\"header\":"; }
            case MISSING_RULE_REFERENCE -> ((ObjectNode) model.findValues("Rule").getFirst())
                    .remove("errorEntityRelPath");
            case UNKNOWN_RULE_PROPERTY -> ((ObjectNode) model.findValues("Rule").getFirst())
                    .putArray("customRefErrorEntitiesIfFilled");
            case ARRAY_INDEX_FIELD -> ((ObjectNode) model.findValues("Group").getFirst())
                    .putArray("indexFieldName").add("Tier");
            case MISSING_COMPUTATION_REFERENCE -> ((ObjectNode) model.findValues("Computation").getFirst())
                    .remove("computedFieldRelPath");
            case MISSING_SORT_REFERENCE -> ((ObjectNode) model.findValues("Group").getFirst())
                    .putArray("sortFields").addObject().put("order", "ASC");
        }
        return MAPPER.writeValueAsString(model);
    }
}
