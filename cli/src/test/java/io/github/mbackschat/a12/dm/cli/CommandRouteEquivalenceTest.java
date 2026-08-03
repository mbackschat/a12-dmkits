package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Equivalent public routes must produce the same observation or persisted model, not merely all exit zero. */
class CommandRouteEquivalenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aDirectStaticReadAndItsBatchChildReturnTheSameEnvelope(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path model = CliTestSupport.modelWithRule(dir);
        JsonNode direct = MAPPER.readTree(dmtool.run("-m", model.toString(), "field", "read",
                "/Subscription/Addons/MonthlyFee").out());
        Path ops = dir.resolve("read-batch.json");
        Files.writeString(ops, "[{\"id\":\"read\",\"verb\":\"field\","
                + "\"args\":[\"read\",\"/Subscription/Addons/MonthlyFee\"]}]");

        JsonNode batch = MAPPER.readTree(dmtool.run("-m", model.toString(), "batch", ops.toString()).out());

        assertEquals(direct, batch.path("data").path("results").get(0).path("result"));
    }

    @Test
    void aDirectDryRunAndItsBatchChildReturnTheSameEnvelopeAndWriteNothing(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path model = CliTestSupport.modelWithRule(dir);
        Path spec = dir.resolve("field.json");
        Files.writeString(spec, schemaExample("field-add").toString());
        String before = Files.readString(model);
        JsonNode direct = MAPPER.readTree(dmtool.run("-m", model.toString(), "field", "add",
                spec.toString(), "--dry-run").out());
        Path ops = dir.resolve("preview-batch.json");
        Files.writeString(ops, "[{\"id\":\"preview\",\"verb\":\"field\",\"args\":[\"add\","
                + MAPPER.writeValueAsString(spec.toString()) + ",\"--dry-run\"]}]");

        JsonNode batch = MAPPER.readTree(dmtool.run("-m", model.toString(), "batch", ops.toString()).out());

        assertEquals(direct, batch.path("data").path("results").get(0).path("result"));
        assertEquals(before, Files.readString(model));
    }

    @Test
    void aRichDirectFieldEditAndEquivalentApplyPersistTheSameModel(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path directModel = dir.resolve("direct.dm.json");
        Path applyModel = dir.resolve("apply.dm.json");
        Files.writeString(directModel, CliTestSupport.subscriptionJson());
        Files.writeString(applyModel, CliTestSupport.subscriptionJson());
        JsonNode richSpec = schemaExample("field-add");
        Path spec = dir.resolve("rich-field.json");
        Files.writeString(spec, richSpec.toPrettyString());
        ObjectNode applyOp = (ObjectNode) richSpec.deepCopy();
        applyOp.put("target", "field");
        applyOp.put("op", "add");
        Path ops = dir.resolve("rich-apply.json");
        Files.writeString(ops, MAPPER.writeValueAsString(java.util.List.of(applyOp)));

        PackagedDmtool.Result direct = dmtool.run("-m", directModel.toString(), "field", "add",
                spec.toString());
        PackagedDmtool.Result apply = dmtool.run("-m", applyModel.toString(), "apply", ops.toString());

        assertEquals(0, direct.code(), direct::out);
        assertEquals(0, apply.code(), apply::out);
        String directJson = Files.readString(directModel);
        String applyJson = Files.readString(applyModel);
        assertEquals(MAPPER.readTree(directJson), MAPPER.readTree(applyJson),
                () -> "direct and transactional routes must persist the same A12 model tree\ndirect="
                        + directJson + "\napply=" + applyJson);
        assertEquals(0, dmtool.run("-m", directModel.toString(), "model", "check").code());
        assertEquals(0, dmtool.run("-m", applyModel.toString(), "model", "check").code());
    }

    private static JsonNode schemaExample(String name) throws Exception {
        try (InputStream input = CommandRouteEquivalenceTest.class.getResourceAsStream(
                "/schema/" + name + ".schema.json")) {
            if (input == null) throw new IllegalStateException("missing schema " + name);
            return MAPPER.readTree(input).path("examples").get(0);
        }
    }
}
