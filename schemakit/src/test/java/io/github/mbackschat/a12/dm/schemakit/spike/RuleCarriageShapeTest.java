package io.github.mbackschat.a12.dm.schemakit.spike;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.spike.SchemaTestSupport.assertKernelAccepts;
import static io.github.mbackschat.a12.dm.schemakit.spike.SchemaTestSupport.normalize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike shape 4 (SCHEMAKIT-SPEC §6, proposal §6) — Tier-B / Tier-C carriage: a rule and a computation
 * (which JSON Schema cannot express) ride {@code x-a12-rule} / {@code x-a12-computation} so an
 * a12 → JSON Schema → a12 round-trip preserves them exactly. The fixture is authored through the typed
 * dogfood surface (modelkit {@link DocumentModelDraft} + rulekit {@link BoundModel}); the root group is
 * named {@code Root} to match the importer, so the carried elements' group paths align on re-import.
 */
class RuleCarriageShapeTest {

    /** Author a model with a cross-field rule + a tiered computation, via the typed surface. */
    private static String modelWithRuleAndComputation() {
        String structure = DocumentModelDraft.named("Order")
                .locale("en_US")
                .rootGroup("Root", g -> {
                    g.field("PostalCode", FieldKind.STRING);
                    g.numberField("Subtotal", n -> n.fractionDigits(0));
                    g.numberField("ShippingFee", n -> n.fractionDigits(0));
                })
                .build()
                .toJson();

        BoundModel m = BoundModel.ofJson(structure);
        StringRef postal = m.str("/Root/PostalCode");
        NumberRef subtotal = m.num("/Root/Subtotal");
        NumberRef shippingFee = m.num("/Root/ShippingFee");

        Rule rule = m.rule(m.group("/Root"))
                .name("PostalCodeFormat")
                .errorField(postal)
                .when(postal.filled().and(postal.violates("[0-9]{5}")))
                .code("POSTAL_CODE_MALFORMED")
                .severity(Severity.ERROR)
                .message(Dsl.en("The postal code must be exactly five digits."))
                .build();

        Computation comp = m.computation(shippingFee)
                .name("ShippingTier")
                .commonPrecondition(subtotal.filled())
                .when(subtotal.lt(100)).calc(NumberLiteral.of(10))
                .when(subtotal.ge(100)).calc(NumberLiteral.of(0))
                .message(Dsl.en("Shipping fee by order subtotal."))
                .build();

        return m.add(rule).add(comp).toJson();
    }

    @Test
    void ruleAndComputationCarryAndReconstruct() {
        String dm = modelWithRuleAndComputation();
        assertKernelAccepts(dm);

        // export: the rule + computation become x-a12-* carriage on the schema
        JsonSchemaExporter.ExportResult exported = new JsonSchemaExporter().export(dm);
        assertTrue(exported.jsonSchema().contains(Carriage.X_RULE), "the rule must be carried as x-a12-rule");
        assertTrue(exported.jsonSchema().contains(Carriage.X_COMPUTATION),
                "the computation must be carried as x-a12-computation");
        assertTrue(exported.jsonSchema().contains("POSTAL_CODE_MALFORMED"));

        // re-import: the carried elements are reconstructed into a kernel-accepted model
        JsonSchemaImporter.ImportResult reimported = new JsonSchemaImporter().importSchema(exported.jsonSchema());
        assertKernelAccepts(reimported.dmJson());
        assertTrue(reimported.dmJson().contains("POSTAL_CODE_MALFORMED"), "the rule survives the round-trip");
        assertTrue(reimported.dmJson().contains("ShippingTier"), "the computation survives the round-trip");
    }

    @Test
    void carriageRoundTripIsLossless() {
        String dm = modelWithRuleAndComputation();
        JsonSchemaExporter.ExportResult first = new JsonSchemaExporter().export(dm);
        JsonSchemaImporter.ImportResult back = new JsonSchemaImporter().importSchema(first.jsonSchema());
        JsonSchemaExporter.ExportResult second = new JsonSchemaExporter().export(back.dmJson());

        assertEquals(normalize(first.jsonSchema()), normalize(second.jsonSchema()),
                "a12 → schema → a12 → schema must be lossless (structure + carried rules/computations)");
    }
}
