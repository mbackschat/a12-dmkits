package io.github.mbackschat.a12.dm.schemakit;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.normalize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * Kernel 31.0's `SumOfTerms` (A12K-3756, KERNEL-FINDINGS KF670) has no JSON Schema keyword, so a rule and a
 * computation built on it ride `x-a12-rule` / `x-a12-computation` and an a12 → JSON Schema → a12 round trip restores
 * them exactly, in both wire forms. The kernel's consistency check is the admission oracle after the round trip.
 * Authored through the typed surface; own domain (an order's shipping weight).
 */
class SumOfTermsCarriageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String model() {
        String structure = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .stringField("CustomerName", f -> { })
                        .numberField("TotalWeight", n -> n.fractionDigits(2))
                        .group("Items", i -> i.repeatable(10)
                                .numberField("Count", n -> n.fractionDigits(0))
                                .numberField("UnitWeight", n -> n.fractionDigits(2))))
                .build().toJson();
        var session = EditSession.read(structure);
        var order = session.group("/Order");
        var item = session.group("/Order/Items");
        var customer = order.str("CustomerName");
        NumberExpr weight = Dsl.sumOfTerms(order.flattenGroup("Items*"), item.num("Count").times(item.num("UnitWeight")));
        NumberExpr heavyWeight = Dsl.sumOfTerms(order.flattenGroup("Items*"), item.num("UnitWeight").gt(10),
                item.num("Count").times(item.num("UnitWeight")));
        order.addRule(r -> r.name("TooHeavy").errorField(customer)
                .when(customer.filled().and(weight.gt(1000))).code("TOO_HEAVY").severity(Severity.ERROR)
                .message(Dsl.en("The order is too heavy to ship.")).build());
        order.addRule(r -> r.name("TooManyHeavyItems").errorField(customer)
                .when(customer.filled().and(heavyWeight.gt(500))).code("HEAVY_ITEMS").severity(Severity.ERROR)
                .message(Dsl.en("The heavy items weigh too much.")).build());
        order.addComputation(order.num("TotalWeight"), c -> c.name("Weigh").calc(weight.expression())
                .message(Dsl.en("The order's total weight.")).build());
        return session.toJson();
    }

    @Test
    void aTermSumRuleAndComputationSurviveTheRoundTripInBothWireForms() throws Exception {
        String source = model();
        assertKernelAccepts(source);
        assertEquals(3, source.split("SumOfTerms\\(").length - 1, "the fixture holds two term-sum rules and one computation");
        JsonNode sourceRoot = MAPPER.readTree(source).path("content").path("modelRoot");

        for (boolean wrapped : new boolean[] {false, true}) {
            String label = wrapped ? "wrapped OpenAPI 3.1" : "bare JSON Schema";
            var exported = new JsonSchemaExporter().export(source, Dialect.OPENAPI_31, wrapped);
            assertEquals(List.of("/Order/TooHeavy", "/Order/TooManyHeavyItems"),
                    sources(exported.report(), TranscodeReport.Tier.CARRIED), label);
            assertEquals(List.of("/Order/Weigh"), sources(exported.report(), TranscodeReport.Tier.COMPUTATION), label);

            var imported = new JsonSchemaImporter().importSchema(exported.jsonSchema(), MappingProfile.defaults(),
                    Dialect.AUTO, wrapped ? "order" : null);
            assertKernelAccepts(imported.dmJson());
            assertFalse(imported.report().dispositions().stream().anyMatch(d -> d.tier() == TranscodeReport.Tier.OMITTED),
                    label);
            assertEquals(sourceRoot, MAPPER.readTree(imported.dmJson()).path("content").path("modelRoot"), label);
            assertEquals(normalize(exported.jsonSchema()), normalize(new JsonSchemaExporter()
                    .export(imported.dmJson(), Dialect.OPENAPI_31, wrapped).jsonSchema()), label);
        }
    }

    /** The element sources reported at {@code tier}; the root group's own carriage disposition is not an element. */
    private static List<String> sources(TranscodeReport report, TranscodeReport.Tier tier) {
        return report.dispositions().stream().filter(d -> d.tier() == tier && d.source().startsWith("/Order/"))
                .map(TranscodeReport.Disposition::source).sorted().toList();
    }
}
