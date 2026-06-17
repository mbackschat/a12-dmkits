package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code model new} — create a new, empty, kernel-valid document model from scratch (the CLI's one
 * model-originating verb; every other verb edits an existing {@code -m} model). It prints the model JSON to
 * stdout, or {@code -o} writes it to a file. What it emits is kernel-checked, so the very next `-m` edit binds.
 */
class ModelNewVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void createsAKernelValidModelToStdout(@TempDir Path dir) throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("model", "new", "--id", "orders", "--locale", "en_US", "--root", "Order");
        assertEquals(0, res.code(), res::err);
        // the output IS the model JSON — round-trip it through `model validate`
        Path model = dir.resolve("orders.dm.json");
        Files.writeString(model, res.out());
        CliTestSupport.Result validated = CliTestSupport.run("-m", model.toString(), "model", "validate");
        assertEquals(0, validated.code(), validated::err);
        assertTrue(M.readTree(validated.out()).path("valid").asBoolean(),
                () -> "a freshly-created model is kernel-valid: " + validated.out());
    }

    @Test
    void writesToFileWithDashO(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subs.dm.json");
        CliTestSupport.Result res = CliTestSupport.run(
                "model", "new", "--id", "subs", "--locale", "en_US", "-o", model.toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(Files.exists(model), "the model file is written");
        // and the agent can immediately edit it — `model describe` binds it
        CliTestSupport.Result describe = CliTestSupport.run("-m", model.toString(), "model", "describe");
        assertEquals(0, describe.code(), describe::err);
        assertEquals("read", M.readTree(describe.out()).path("outcome").asText());
    }

    @Test
    void anInvalidLocaleIsReportedNotSilentlyEmitted() {
        CliTestSupport.Result res = CliTestSupport.run("model", "new", "--id", "x", "--locale", "not_a_locale");
        assertTrue(res.code() != 0, () -> "an unbuildable model is reported, not emitted: " + res.out() + res.err());
    }
}
