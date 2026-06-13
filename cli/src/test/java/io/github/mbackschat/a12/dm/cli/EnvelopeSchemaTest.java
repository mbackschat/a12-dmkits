package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The uniform result envelope (every verb's output) and the {@link io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic}
 * shape must each be published as a discoverable schema (CLI-SPEC §3b/§4) — an agent learns the contract via
 * {@code schema result} / {@code schema diagnostic} instead of inferring it. The {@code result} schema is also
 * drift-locked against a real verb's envelope so a new envelope field can't slip past the published contract.
 */
class EnvelopeSchemaTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void resultSchemaIsServedAndDeclaresEnvelopeKeys(@TempDir Path dir) throws Exception {
        CliTestSupport.Result s = CliTestSupport.run("schema", "result");
        assertEquals(0, s.code(), s::err);
        JsonNode props = M.readTree(s.out()).path("properties");
        for (String k : List.of("target", "op", "outcome", "ok", "valid", "summary", "changed", "data",
                "diagnostics", "written", "output")) {
            assertTrue(props.has(k), () -> "result schema must declare envelope key '" + k + "'");
        }
    }

    @Test
    void resultSchemaCoversAnActualEnvelope(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        JsonNode props = M.readTree(CliTestSupport.run("schema", "result").out()).path("properties");
        JsonNode env = M.readTree(CliTestSupport.run("-m", model.toString(), "model", "validate").out());
        env.fieldNames().forEachRemaining(k ->
                assertTrue(props.has(k), () -> "result schema is missing emitted envelope key '" + k + "'"));
    }

    @Test
    void diagnosticSchemaIsServedWithTheWhereExtension() throws Exception {
        CliTestSupport.Result s = CliTestSupport.run("schema", "diagnostic");
        assertEquals(0, s.code(), s::err);
        JsonNode where = M.readTree(s.out()).path("properties").path("where").path("properties");
        for (String k : List.of("rule", "path", "arg", "operator", "span")) {
            assertTrue(where.has(k), () -> "diagnostic.where must declare '" + k + "'");
        }
    }
}
