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
 * {@code group multiselect} — expand a Multi-Select group template (FEATURE-GAPS §3.8): a repeatable group
 * with one enum/string selection field. Kernel-validated on write ({@code MultiSelectGroupRule}).
 */
class GroupMultiSelectVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void addsAnEnumMultiSelectGroup(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "multiselect",
                "--parent", "/Order", "--name", "Conditions",
                "--field", "Condition", "--values", "NEW,USED,REFURBISHED", "--max-rows", "3");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("/Order/Conditions", j.path("changed").path("added").asText());
        assertEquals(3, j.path("changed").path("maxRows").asInt());
        String written = Files.readString(model);
        assertTrue(written.contains("\"usageType\" : \"multi-select\""), "the multi-select marker is written");
    }

    @Test
    void addsAStringMultiSelectWhenNoValuesGiven(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "multiselect",
                "--parent", "/Order", "--name", "Tags", "--field", "Tag", "--max-rows", "5");
        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
        assertTrue(Files.readString(model).contains("\"usageType\" : \"multi-select\""));
    }

    @Test
    void aSingleRowMultiSelectIsRefusedWithACode(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "multiselect",
                "--parent", "/Order", "--name", "Bad", "--field", "X", "--values", "A,B", "--max-rows", "1");
        assertEquals(2, res.code(), "maxRows must be > 1 — a single-row multi-select is refused");
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText(), () -> "a coded refusal, not a bare fail: " + res.out());
        assertEquals("RK_MULTISELECT_MIN_ROWS", j.path("diagnostics").path(0).path("code").asText());
    }
}
