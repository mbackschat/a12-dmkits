package io.github.mbackschat.a12.dm.cli.group;

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
 * The target-first {@code group} group (CLI-SPEC §5) — modelkit group operations: {@code add} (plain or
 * {@code --repeatable}), {@code read} (repeatability), {@code remove} (the same safe-delete gate as
 * {@code field}).
 */
class GroupVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void addAddsAGroup(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "add", "--parent", "/Order", "--name", "Notes");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("/Order/Notes", j.path("changed").path("added").asText());
        assertTrue(Files.readString(model).contains("Notes"));
    }

    @Test
    void addAddsARepeatableGroup(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "add",
                "--parent", "/Order", "--name", "Lines", "--repeatable", "50");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertTrue(j.path("changed").path("repeatable").asBoolean());
    }

    @Test
    void readWithNoPathListsTheGroupPaths(@TempDir Path dir) throws Exception {
        // P2: no path → a lean name-index, uniform with the read family.
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "read");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText());
        JsonNode groups = j.path("data").path("groups");
        assertTrue(groups.isArray() && groups.size() > 0, () -> "lists group paths: " + res.out());
        assertTrue(groups.toString().contains("/Order/BillingAddress"), () -> res.out());
    }

    @Test
    void readReturnsRepeatability(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "read", "/Order/BillingAddress");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText());
        assertEquals("/Order/BillingAddress", j.path("data").path("group").asText());
        assertTrue(j.path("data").has("repeatable"));
    }

    @Test
    void readEchoesTheGroupsDirectChildFields(@TempDir Path dir) throws Exception {
        // probe 10: `group read` echoes a group's DIRECT child fields, so a composite group is confirmable
        // through this read alone — no fallback to `model describe` for "what's in here".
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "read", "/Order/Items");
        assertEquals(0, res.code(), res.err());
        JsonNode fields = M.readTree(res.out()).path("data").path("fields");
        assertTrue(fields.isArray() && fields.toString().contains("/Order/Items/Count"),
                () -> "group read echoes the direct child fields: " + res.out());
    }

    @Test
    void removesAnAddedGroup(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "group", "add",
                "--parent", "/Order", "--name", "Notes").code());
        var res = CliTestSupport.run("-m", model.toString(), "group", "remove", "/Order/Notes");
        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
    }
}
