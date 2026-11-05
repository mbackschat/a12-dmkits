package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.edit.EditSession;

/**
 * Kernel 31.0 added {@code modelInfo.packet} (A12K-4118), a tool-managed member the kits never author. A model
 * carrying one must survive an edit unchanged, through the typed edit surface and through a dmtool write-back alike,
 * and stay admitted by A12 afterwards. The comment edited beside it is the control that the edit really happened.
 */
class ModelInfoPacketPreservationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String PACKET = "claims-core";

    private static String withPacket() throws Exception {
        ObjectNode model = (ObjectNode) M.readTree(CliTestSupport.orderRuledJson());
        ((ObjectNode) model.path("content")).withObject("modelInfo").put("packet", PACKET);
        return M.writeValueAsString(model);
    }

    private static JsonNode modelInfo(String json) throws Exception {
        return M.readTree(json).path("content").path("modelInfo");
    }

    @Test
    void theEditSurfaceKeepsThePacket() throws Exception {
        var session = EditSession.read(withPacket());
        session.setModelComment("Reviewed by underwriting.");
        JsonNode info = modelInfo(session.toJson());
        assertEquals(PACKET, info.path("packet").asText());
        assertEquals("Reviewed by underwriting.", info.path("comment").asText());
    }

    @Test
    void aDmtoolWriteBackKeepsThePacketAndA12AdmitsTheModel(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("order.json");
        Files.writeString(model, withPacket());
        var edit = CliTestSupport.run("-m", model.toString(), "config", "modify", "--comment", "Reviewed by underwriting.");
        assertEquals(0, edit.code(), edit::out);
        JsonNode info = modelInfo(Files.readString(model));
        assertEquals(PACKET, info.path("packet").asText());
        assertEquals("Reviewed by underwriting.", info.path("comment").asText());
        var check = CliTestSupport.run("-m", model.toString(), "model", "check");
        assertTrue(M.readTree(check.out()).path("valid").asBoolean(), check::out);
    }
}
