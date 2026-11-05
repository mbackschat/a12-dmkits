package io.github.mbackschat.a12.dm.schemakit;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * A text field compared with a labelled enumeration, admitted since kernel 31.0 (A12K-3969, KERNEL-FINDINGS KF655),
 * is a cross-field comparison no JSON Schema keyword states, so both operand orders ride `x-a12-rule` and an a12 →
 * JSON Schema → a12 round trip restores both rules exactly, in both wire forms. The kernel's consistency check is the
 * admission oracle after the round trip. The comparison is the subject here, so only the rules are compared: the
 * enumeration's labels do not survive the round trip, a defect of the bridge's label carriage that SCHEMAKIT-GAPS SG1
 * owns. Authored through the typed surface; own domain (a subscription request).
 */
class TextEnumComparisonCarriageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String model() {
        String structure = DocumentModelDraft.named("subscription").locale("en_US")
                .rootGroup("Subscription", s -> s
                        .stringField("RequestedPlan", f -> { })
                        .enumField("Plan", e -> e
                                .value("BASIC", l -> l.label("en_US", "Basic plan"))
                                .value("PRO", l -> l.label("en_US", "Pro plan"))))
                .build().toJson();
        var session = EditSession.read(structure);
        var subscription = session.group("/Subscription");
        var requested = subscription.str("RequestedPlan");
        var plan = subscription.enumOf("Plan");
        subscription.addRule(r -> r.name("PlanMismatch").errorField(requested)
                .when(requested.filled().and(plan.ne(requested))).code("PLAN_MISMATCH").severity(Severity.ERROR)
                .message(Dsl.en("The requested plan is not the plan.")).build());
        subscription.addRule(r -> r.name("PlanMatch").errorField(requested)
                .when(requested.eq(plan)).code("PLAN_MATCH").severity(Severity.WARNING)
                .message(Dsl.en("The requested plan is already the plan.")).build());
        return session.toJson();
    }

    @Test
    void bothOperandOrdersSurviveTheRoundTripInBothWireForms() throws Exception {
        String source = model();
        assertKernelAccepts(source);
        List<JsonNode> sourceRules = rules(source);
        assertEquals(2, sourceRules.size(), "the fixture holds one rule per operand order");

        for (boolean wrapped : new boolean[] {false, true}) {
            String label = wrapped ? "wrapped OpenAPI 3.1" : "bare JSON Schema";
            var exported = new JsonSchemaExporter().export(source, Dialect.OPENAPI_31, wrapped);
            assertEquals(List.of("/Subscription/PlanMatch", "/Subscription/PlanMismatch"),
                    exported.report().dispositions().stream()
                            .filter(d -> d.tier() == TranscodeReport.Tier.CARRIED && d.source().startsWith("/Subscription/"))
                            .map(TranscodeReport.Disposition::source).sorted().toList(), label);

            var imported = new JsonSchemaImporter().importSchema(exported.jsonSchema(), MappingProfile.defaults(),
                    Dialect.AUTO, wrapped ? "subscription" : null);
            assertKernelAccepts(imported.dmJson());
            assertFalse(imported.report().dispositions().stream().anyMatch(d -> d.tier() == TranscodeReport.Tier.OMITTED),
                    label);
            assertEquals(sourceRules, rules(imported.dmJson()), label);
        }
    }

    /** The root group's rule elements, as stored. */
    private static List<JsonNode> rules(String dmJson) throws Exception {
        List<JsonNode> rules = new ArrayList<>();
        MAPPER.readTree(dmJson).path("content").path("modelRoot").path("rootGroups").path(0).path("Group")
                .path("elements").forEach(e -> { if ("Rule".equals(e.path("type").asText())) rules.add(e); });
        return rules;
    }
}
