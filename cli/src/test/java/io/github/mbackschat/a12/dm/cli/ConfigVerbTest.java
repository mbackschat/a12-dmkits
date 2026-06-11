package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The target-first {@code config} group (CLI-SPEC §5) — model-level document configuration: {@code read}
 * (the whole {@code modelConfig} into {@code data}) and {@code modify} (set decimal-separator / time-zone /
 * condition-language; at least one is required). No add/remove — config is a fixed object, not a collection.
 */
class ConfigVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void readReturnsTheConfig(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText());
        JsonNode data = j.path("data");
        assertTrue(data.has("decimalSeparator"));
        assertTrue(data.has("timeZone"));
        assertTrue(data.has("conditionLanguage"));
    }

    @Test
    void modifyChangesTheDecimalSeparator(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--decimal-separator", ",");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals(",", j.path("changed").path("decimalSeparator").asText());
        // re-read confirms the write took
        var read = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertEquals(",", M.readTree(read.out()).path("data").path("decimalSeparator").asText());
    }

    @Test
    void modifyRequiresAtLeastOneSetting(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify");
        assertEquals(2, res.code());
    }
}
