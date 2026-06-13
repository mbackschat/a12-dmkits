package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code meta <ref>} — read/edit an element's descriptions + annotations (field/group/rule by path, type-def
 * by id). Dual-mode: no write flags reads; flags set/remove. An unknown reference is a {@code refused}
 * envelope ({@code RK_NO_SUCH_ELEMENT}).
 */
class MetaVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void writesReadsAndRemovesElementMetadata(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        var set = CliTestSupport.run("-m", model.toString(), "meta", "/Order",
                "--internal", "en_US=an order", "--annotate", "owner=orders-team");
        assertEquals(0, set.code(), set.err());
        assertEquals("applied", M.readTree(set.out()).get("outcome").asText());

        var read = CliTestSupport.run("-m", model.toString(), "meta", "/Order");
        assertEquals(0, read.code(), read.err());
        JsonNode data = M.readTree(read.out()).path("data");
        assertEquals("an order", data.path("internalDescription").path("en_US").asText());
        assertEquals("orders-team", data.path("annotations").path("owner").asText());

        var remove = CliTestSupport.run("-m", model.toString(), "meta", "/Order",
                "--remove-annotation", "owner", "--remove-internal", "en_US");
        assertEquals(0, remove.code(), remove.err());
        JsonNode after = M.readTree(CliTestSupport.run("-m", model.toString(), "meta", "/Order").out()).path("data");
        assertTrue(after.path("annotations").isEmpty(), "annotation removed");
        assertTrue(after.path("internalDescription").isEmpty(), "internal description removed");
    }

    @Test
    void unknownReferenceIsRefused(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "meta", "/Nope/Missing", "--annotate", "k=v");
        assertEquals(2, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertEquals("RK_NO_SUCH_ELEMENT", j.path("diagnostics").path(0).path("code").asText());
        assertTrue(!j.path("written").asBoolean(true), "a refused op writes nothing");
    }
}
