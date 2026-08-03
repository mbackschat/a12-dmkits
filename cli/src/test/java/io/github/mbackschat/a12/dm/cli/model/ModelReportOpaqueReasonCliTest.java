package io.github.mbackschat.a12.dm.cli.model;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** End-to-end lock for the ordinary report's Opaque reason contract. */
class ModelReportOpaqueReasonCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OPAQUE_COMPUTATION = "/Review/OpaqueComputation";
    private static final String SUPPRESSION = "MVK_INVALID_COMPARE_DEC_PLACES";

    @Test
    void emitsReasonOnlyForOpaqueRulesAndComputations(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("review-report.dm.json");
        Files.writeString(model, modelWithOpaqueBodies());

        JsonNode read = MAPPER.readTree(CliTestSupport.run(
                "-m", model.toString(), "computation", "read", OPAQUE_COMPUTATION).out()).path("data");
        assertEquals(SUPPRESSION, read.path("errorCodesToSuppress").get(0).asText());
        JsonNode explain = MAPPER.readTree(CliTestSupport.run(
                "-m", model.toString(), "computation", "explain", OPAQUE_COMPUTATION).out()).path("data");
        assertEquals(SUPPRESSION, explain.path("errorCodesToSuppress").get(0).asText());

        var result = CliTestSupport.run("-m", model.toString(), "model", "report");
        assertEquals(0, result.code(), result::err);
        JsonNode data = MAPPER.readTree(result.out()).path("data");

        JsonNode opaqueRule = named(data.path("rules"), "rule", "/Review/OpaqueRule");
        assertFalse(opaqueRule.path("explained").asBoolean());
        assertTrue(opaqueRule.hasNonNull("reason") && !opaqueRule.path("reason").asText().isBlank(),
                () -> "opaque rule reason missing: " + opaqueRule);
        JsonNode explainedRule = named(data.path("rules"), "rule", "/Review/ExplainedRule");
        assertTrue(explainedRule.path("explained").asBoolean());
        assertFalse(explainedRule.has("reason"), () -> "explained rule acquired opaque state: " + explainedRule);

        JsonNode opaqueComputation = named(
                data.path("computations"), "computation", OPAQUE_COMPUTATION);
        assertFalse(opaqueComputation.path("explained").asBoolean());
        assertTrue(opaqueComputation.hasNonNull("reason") && !opaqueComputation.path("reason").asText().isBlank(),
                () -> "opaque computation reason missing: " + opaqueComputation);
        assertEquals(SUPPRESSION, opaqueComputation.path("errorCodesToSuppress").get(0).asText());
        JsonNode explainedComputation = named(
                data.path("computations"), "computation", "/Review/ExplainedComputation");
        assertTrue(explainedComputation.path("explained").asBoolean());
        assertFalse(explainedComputation.has("reason"),
                () -> "explained computation acquired opaque state: " + explainedComputation);
        assertTrue(explainedComputation.has("commonPrecondition")
                && explainedComputation.path("alternatives").size() == 2,
                () -> "explained computation detail was lost: " + explainedComputation);
    }

    private static String modelWithOpaqueBodies() throws Exception {
        String base = DocumentModelDraft.named("review-report").locale("en_US")
                .rootGroup("Review", group -> group
                        .field("Approved", FieldKind.BOOLEAN)
                        .field("Reviewed", FieldKind.BOOLEAN)
                        .field("BaseAmount", FieldKind.NUMBER)
                        .field("ComputedExplained", FieldKind.NUMBER)
                        .dateTimeField("SourceDateTime", date -> date.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .dateTimeField("ComputedOpaque", date -> date.format("yyyy-MM-dd'T'HH:mm:ss")))
                .build().toJson();
        EditSession session = EditSession.read(base);
        session.addRule(model -> model.rule(model.group("/Review"))
                .name("ExplainedRule").errorField(model.bool("/Review/Approved").field())
                .when(model.bool("/Review/Approved").isFalse())
                .code("APPROVAL_REQUIRED").severity(Severity.ERROR)
                .message(en("The review must be approved.")).build());
        session.addRule(model -> model.rule(model.group("/Review"))
                .name("OpaqueRule").errorField(model.bool("/Review/Approved").field())
                .when(model.bool("/Review/Approved").isFalse())
                .code("REVIEW_MATCH").severity(Severity.ERROR)
                .message(en("The approval and review decisions must match.")).build());
        session.addComputation(model -> model.computation(model.num("/Review/ComputedExplained"))
                .name("ExplainedComputation")
                .commonPrecondition(model.num("/Review/BaseAmount").filled())
                .when(model.num("/Review/BaseAmount").gt(10))
                .calc(model.num("/Review/BaseAmount").value())
                .when(model.num("/Review/BaseAmount").le(10))
                .calc(model.num("/Review/BaseAmount").value())
                .message(en("The explained total follows the base amount.")).build());
        session.addComputation(model -> model.computation(model.dateTime("/Review/ComputedOpaque").field())
                .name("OpaqueComputation")
                .calc(Dsl.addHours(model.dateTime("/Review/SourceDateTime"), 1).expression())
                .errorCodesToSuppress(SUPPRESSION)
                .message(en("The opaque total follows a project calculation.")).build());

        ObjectNode root = (ObjectNode) MAPPER.readTree(session.toJson());
        payload(root, "OpaqueRule", "Rule").put("errorCondition", "[Approved] == [Reviewed]");
        return MAPPER.writeValueAsString(root);
    }

    private static ObjectNode payload(JsonNode node, String elementName, String payloadName) {
        if (node.isObject() && elementName.equals(node.path("name").asText()) && node.path(payloadName).isObject()) {
            return (ObjectNode) node.path(payloadName);
        }
        for (JsonNode child : node) {
            try {
                return payload(child, elementName, payloadName);
            } catch (IllegalArgumentException ignored) {
                // Keep searching the document tree.
            }
        }
        throw new IllegalArgumentException("no " + payloadName + " payload for " + elementName);
    }

    private static JsonNode named(JsonNode entries, String key, String name) {
        for (JsonNode entry : entries) {
            if (name.equals(entry.path(key).asText())) {
                return entry;
            }
        }
        throw new AssertionError("no " + key + " " + name + " in " + entries);
    }
}
