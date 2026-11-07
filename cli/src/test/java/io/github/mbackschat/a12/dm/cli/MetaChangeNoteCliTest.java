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
 * A12's {@code changeNote} through {@code meta <ref>} and {@code apply}'s meta modify: set, read back beside the
 * descriptions, and removed. The kit locks where it is written ({@code adapter.ElementMemberOrderTest}).
 */
class MetaChangeNoteCliTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void metaSetsReadsAndRemovesTheNote(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertTrue(meta(model).path("changeNote").isMissingNode(), "a model without a note reads without the key");

        var set = CliTestSupport.run("-m", model.toString(), "meta", "/Order", "--change-note", "reviewed by billing");
        assertEquals(0, set.code(), set::out);
        assertEquals("reviewed by billing", M.readTree(set.out()).path("changed").path("changeNote").asText());
        assertEquals("reviewed by billing", meta(model).path("changeNote").asText());

        var remove = CliTestSupport.run("-m", model.toString(), "meta", "/Order", "--remove-change-note");
        assertEquals(0, remove.code(), remove::out);
        assertTrue(meta(model).path("changeNote").isMissingNode());
    }

    @Test
    void applySetsTheSameNote(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [{"target": "meta", "op": "modify", "ref": "/Order", "change-note": "reviewed by billing"}]""");

        var apply = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());

        assertEquals(0, apply.code(), apply::out);
        assertEquals("reviewed by billing", meta(model).path("changeNote").asText());
    }

    private static JsonNode meta(Path model) throws Exception {
        var read = CliTestSupport.run("-m", model.toString(), "meta", "/Order");
        assertEquals(0, read.code(), read::err);
        return M.readTree(read.out()).path("data");
    }
}
