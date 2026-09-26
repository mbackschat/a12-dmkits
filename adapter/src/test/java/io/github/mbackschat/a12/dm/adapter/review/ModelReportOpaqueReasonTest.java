package io.github.mbackschat.a12.dm.adapter.review;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand;

/**
 * MODEL-REVIEW — an Opaque catalog entry explains why it could not be modeled; an explained entry cannot
 * accidentally carry an opaque reason. Both outcome shapes preserve the information needed by their branch.
 */
class ModelReportOpaqueReasonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EXPLAINED_RULE = "/Review/ExplainedRule";
    private static final String OPAQUE_RULE = "/Review/OpaqueRule";
    private static final String EXPLAINED_COMPUTATION = "/Review/ExplainedComputation";
    private static final String OPAQUE_COMPUTATION = "/Review/OpaqueComputation";
    private static final String SUPPRESSION = "MVK_INVALID_COMPARE_DEC_PLACES";

    @Test
    void opaqueRuleAndComputationCarryTheirReadFailureReasons() throws Exception {
        ModelReport report = ModelReport.of(modelWithOpaqueBodies());

        ModelReport.RuleEntry opaqueRule = rule(report, OPAQUE_RULE);
        assertFalse(opaqueRule.explained());
        assertInstanceOf(ModelReport.OpaqueExplanation.class, opaqueRule.explanation());
        assertTrue(opaqueRule.reason() != null && !opaqueRule.reason().isBlank(),
                () -> "the opaque rule must retain its read reason: " + opaqueRule);
        assertTrue(opaqueRule.canonicalText().contains("Reviewed"), opaqueRule::canonicalText);
        assertNull(opaqueRule.gloss());

        ModelReport.ComputationEntry opaqueComputation = computation(report, OPAQUE_COMPUTATION);
        assertFalse(opaqueComputation.explained());
        assertInstanceOf(ModelReport.OpaqueExplanation.class, opaqueComputation.explanation());
        assertTrue(opaqueComputation.reason() != null && !opaqueComputation.reason().isBlank(),
                () -> "the opaque computation must retain its read reason: " + opaqueComputation);
        assertFalse(opaqueComputation.canonicalText().isBlank(), opaqueComputation::canonicalText);
        assertNull(opaqueComputation.computedField());
        assertNull(opaqueComputation.commonPrecondition());
        assertEquals(List.of(), opaqueComputation.alternatives());
        assertEquals(List.of(SUPPRESSION), opaqueComputation.errorCodesToSuppress());
    }

    @Test
    void explainedEntriesCarryNoOpaqueReasonAndKeepComputationDetail() throws Exception {
        ModelReport report = ModelReport.of(modelWithOpaqueBodies());

        ModelReport.RuleEntry explainedRule = rule(report, EXPLAINED_RULE);
        assertTrue(explainedRule.explained());
        assertInstanceOf(ModelReport.ExplainedRule.class, explainedRule.explanation());
        assertNull(explainedRule.reason());
        assertFalse(explainedRule.gloss().isBlank());

        ModelReport.ComputationEntry explainedComputation = computation(report, EXPLAINED_COMPUTATION);
        assertTrue(explainedComputation.explained());
        assertInstanceOf(ModelReport.ExplainedComputation.class, explainedComputation.explanation());
        assertNull(explainedComputation.reason());
        assertEquals("/Review/ComputedExplained", explainedComputation.computedField());
        assertTrue(explainedComputation.commonPrecondition() != null
                && !explainedComputation.commonPrecondition().isBlank());
        assertEquals(2, explainedComputation.alternatives().size());
        assertEquals(ToleranceBand.TR5,
                explainedComputation.alternatives().getFirst().toleranceRangeOp());
    }

    private static String modelWithOpaqueBodies() throws Exception {
        String base = DocumentModelDraft.named("review-report").locale("en_US")
                .rootGroup("Review", group -> group
                        .field("Approved", FieldKind.BOOLEAN)
                        .field("Reviewed", FieldKind.BOOLEAN)
                        .field("BaseAmount", FieldKind.NUMBER)
                        .field("ExpectedAmount", FieldKind.NUMBER)
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
                .calc(model.num("/Review/BaseAmount").value(), ToleranceBand.TR5)
                .when(model.num("/Review/BaseAmount").le(10))
                .calc(model.num("/Review/ExpectedAmount").value())
                .message(en("The explained total follows the configured amount.")).build());
        session.addComputation(model -> model.computation(model.dateTime("/Review/ComputedOpaque").field())
                .name("OpaqueComputation")
                .calc(Dsl.addHours(model.dateTime("/Review/SourceDateTime"), 1).expression())
                .errorCodesToSuppress(SUPPRESSION)
                .message(en("The opaque total follows a project calculation.")).build());

        ObjectNode root = (ObjectNode) MAPPER.readTree(session.toJson());
        ObjectNode opaqueRule = payload(root, "OpaqueRule", "Rule");
        opaqueRule.put("errorCondition", "[Approved] == [Reviewed]");
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

    private static ModelReport.RuleEntry rule(ModelReport report, String name) {
        return report.rules().stream().filter(entry -> name.equals(entry.rule())).findFirst()
                .orElseThrow(() -> new AssertionError("missing rule " + name + ": " + report.rules()));
    }

    private static ModelReport.ComputationEntry computation(ModelReport report, String name) {
        return report.computations().stream().filter(entry -> name.equals(entry.computation())).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing computation " + name + ": " + report.computations()));
    }
}
