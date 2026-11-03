package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ModelInputFailureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Input no reader can bind: a refusal before any verdict. */
    enum Malformation { TRUNCATED, ARRAY_INDEX_FIELD }

    /**
     * A model missing a required member. Kernel 31.1.1 reads it and then fails to build it (KERNEL-FINDINGS KF654),
     * so it is the kernel's verdict on an invalid model, not unreadable input; each carries the stored member its
     * corrective must name.
     */
    enum Unbuildable {
        MISSING_RULE_REFERENCE("errorEntityRelPath"), MISSING_COMPUTATION_REFERENCE("computedFieldRelPath"),
        MISSING_SORT_REFERENCE("sortFields"), MISSING_ERROR_CODE("errorCode"), MISSING_HEADER_ID("id");

        final String member;

        Unbuildable(String member) {
            this.member = member;
        }
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
            var arguments = new java.util.ArrayList<>(List.of("-m", file.toString()));
            arguments.addAll(List.of(command));
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
                case ARRAY_INDEX_FIELD -> "indexFieldName";
            };
            assertTrue(detail.contains(expectedDetail), result::out);
            assertEquals(file.toString(), diagnostic.path("where").path("arg").asText(), result::out);
            assertFalse(envelope.path("summary").asText().contains("internally"), result::out);
            assertTrue(diagnostic.path("fix").asText().length() > 0, result::out);
            assertTrue(result.err().isBlank(), result::err);
            assertEquals(original, Files.readString(file));
        }
    }

    @ParameterizedTest
    @EnumSource(Unbuildable.class)
    void aMissingReferenceIsTheKernelsInvalidVerdict(Unbuildable unbuildable, @TempDir Path dir) throws Exception {
        Path file = dir.resolve("unbuildable.json");
        Files.writeString(file, unbuildable(unbuildable));
        String original = Files.readString(file);

        var check = CliTestSupport.run("-m", file.toString(), "model", "check");
        assertEquals(1, check.code(), check::out);
        var envelope = MAPPER.readTree(check.out());
        assertEquals("read", envelope.path("outcome").asText(), check::out);
        assertFalse(envelope.path("valid").asBoolean(), check::out);
        assertEquals("KERNEL", envelope.at("/diagnostics/0/source").asText(), check::out);
        assertTrue(envelope.at("/diagnostics/0/summary").asText().startsWith("the kernel could not build this model:"),
                check::out);
        // A12's words name at most a null Java variable, so the corrective must name the stored member.
        assertTrue(envelope.at("/diagnostics/0/fix").asText().contains(unbuildable.member), check::out);

        for (String[] command : new String[][] {{"model", "read"}, {"group", "modify", "/Subscription", "--repeatable", "2"}}) {
            var arguments = new java.util.ArrayList<>(List.of("-m", file.toString()));
            arguments.addAll(List.of(command));
            var result = CliTestSupport.run(arguments.toArray(String[]::new));
            assertEquals(1, result.code(), result::out);
            assertEquals("rejected", MAPPER.readTree(result.out()).path("outcome").asText(), result::out);
            assertTrue(result.err().isBlank(), result::err);
            assertEquals(original, Files.readString(file));
        }
    }

    /** Kernel 31.1.1 reads past a member it does not know (KERNEL-FINDINGS KF654), and so do we. */
    @Test
    void anUnknownRuleMemberIsReadPast(@TempDir Path dir) throws Exception {
        ObjectNode model = (ObjectNode) MAPPER.readTree(CliTestSupport.modelWithRuleAndComputationJson());
        ((ObjectNode) model.findValues("Rule").getFirst()).putArray("customRefErrorEntitiesIfFilled");
        Path file = dir.resolve("unknown-member.json");
        Files.writeString(file, MAPPER.writeValueAsString(model));
        for (String op : List.of("check", "read")) {
            var result = CliTestSupport.run("-m", file.toString(), "model", op);
            assertEquals(0, result.code(), result::out);
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
            case ARRAY_INDEX_FIELD -> ((ObjectNode) model.findValues("Group").getFirst())
                    .putArray("indexFieldName").add("Tier");
        }
        return MAPPER.writeValueAsString(model);
    }

    static String unbuildable(Unbuildable unbuildable) throws Exception {
        ObjectNode model = (ObjectNode) MAPPER.readTree(CliTestSupport.modelWithRuleAndComputationJson());
        switch (unbuildable) {
            case MISSING_RULE_REFERENCE -> ((ObjectNode) model.findValues("Rule").getFirst())
                    .remove("errorEntityRelPath");
            case MISSING_COMPUTATION_REFERENCE -> ((ObjectNode) model.findValues("Computation").getFirst())
                    .remove("computedFieldRelPath");
            case MISSING_SORT_REFERENCE -> ((ObjectNode) model.findValues("Group").getFirst())
                    .putArray("sortFields").addObject().put("order", "ASC");
            case MISSING_ERROR_CODE -> ((ObjectNode) model.findValues("Rule").getFirst()).remove("errorCode");
            case MISSING_HEADER_ID -> ((ObjectNode) model.path("header")).remove("id");
        }
        return MAPPER.writeValueAsString(model);
    }
}
