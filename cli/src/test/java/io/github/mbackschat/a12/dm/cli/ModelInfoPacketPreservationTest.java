package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * Kernel 31.0 added {@code modelInfo.packet} (A12K-4118), which A12 sets for a model generated from an ExtMP packet
 * specification ({@code IDocumentModelInfo#getPacket}), so the kits read it and never author it. A model carrying one
 * must survive an edit unchanged, through the typed edit surface and through a dmtool write-back alike, and stay
 * admitted by A12 afterwards; the comment edited beside it is the control that the edit really happened. dmtool reads
 * it in {@code config read} and reports a changed one in {@code model diff}.
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
        assertEquals(Optional.of(PACKET), ModelDocument.read(withPacket()).modelPacket());
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

    @Test
    void dmtoolReadsThePacketAndDiffsAChangedOne(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("base.json");
        Files.writeString(base, withPacket());
        JsonNode read = M.readTree(CliTestSupport.run("-m", base.toString(), "config", "read").out());
        assertEquals(PACKET, read.path("data").path("packet").asText(), read::toString);

        ObjectNode moved = (ObjectNode) M.readTree(withPacket());
        ((ObjectNode) moved.path("content").path("modelInfo")).put("packet", "claims-extended");
        Path head = dir.resolve("head.json");
        Files.writeString(head, M.writeValueAsString(moved));
        JsonNode diff = M.readTree(CliTestSupport.run("model", "diff", base.toString(), head.toString()).out());
        JsonNode delta = diff.path("data").path("changes").path(0).path("deltas").path(0);
        assertEquals(List.of("CONFIG", "packet", PACKET, "claims-extended"),
                List.of(diff.path("data").path("changes").path(0).path("element").asText(), delta.path("aspect").asText(),
                        delta.path("before").asText(), delta.path("after").asText()), diff::toString);
    }
}
