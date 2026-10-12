package io.github.mbackschat.a12.dm.schemakit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape 5 (SCHEMAKIT-SPEC §6; proposal §12.1) — the two-tier validation coupling, field-local tier.
 * The exported schema's native Tier-A keywords validate with a STOCK JSON-Schema validator
 * ({@code com.networknt}); but a cross-field rule that this exporter does not project slips past that validator
 * and is carried in {@code x-a12-rule} for the interpreter (the full-rule tier). This proves the
 * complementarity that makes export more than documentation.
 *
 */
class TwoTierValidationShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final String PATTERN = "[0-9]{5}";

    /** A declaration-owned pattern and a carried cross-field rule exercise the two validation tiers. */
    private static String model() {
        String structure = DocumentModelDraft.named("Order")
                .locale("en_US")
                .rootGroup("Root", g -> {
                    g.stringField("PostalCode", f -> f.pattern(PATTERN, "The postal code must be exactly five digits."));
                    g.numberField("Priority", n -> n.fractionDigits(0));
                })
                .build()
                .toJson();
        BoundModel m = BoundModel.ofJson(structure);
        StringRef postal = m.str("/Root/PostalCode");
        NumberRef priority = m.num("/Root/Priority");

        Rule crossField = m.rule(m.group("/Root"))
                .name("PriorityRequiredWithPostal").errorField(priority)
                .when(postal.filled().and(priority.notFilled()))
                .code("PRIORITY_REQUIRED").severity(Severity.ERROR)
                .message(Dsl.en("A priority is required when a postal code is given.")).build();

        return m.add(crossField).toJson();
    }

    @Test
    void stockValidatorEnforcesTheFieldLocalTier() throws Exception {
        String schemaJson = new JsonSchemaExporter().export(model()).jsonSchema();
        JsonSchema schema = FACTORY.getSchema(schemaJson);

        // Tier 1: the native `pattern` keyword is enforced by an off-the-shelf validator
        Set<ValidationMessage> bad = schema.validate(MAPPER.readTree("""
                { "PostalCode": "12" }"""));
        assertFalse(bad.isEmpty(), "a stock validator must catch the pattern violation (Tier-A keyword)");

        Set<ValidationMessage> good = schema.validate(MAPPER.readTree("""
                { "PostalCode": "12345" }"""));
        assertTrue(good.isEmpty(), "a five-digit postal code passes the field-local tier");
    }

    @Test
    void stockValidatorCannotSeeCrossFieldRulesButTheyAreCarried() throws Exception {
        JsonSchemaExporter.ExportResult exported = new JsonSchemaExporter().export(model());
        JsonSchema schema = FACTORY.getSchema(exported.jsonSchema());

        // This instance violates the a12 cross-field rule (postal given, priority missing) …
        Set<ValidationMessage> stock = schema.validate(MAPPER.readTree("""
                { "PostalCode": "12345" }"""));
        // … yet the stock JSON-Schema validator passes it — this exporter does not project the cross-field rule
        assertTrue(stock.isEmpty(), "the field-local tier alone cannot enforce a cross-field rule");

        // …but the rule is carried, available to the interpreter (the full-rule tier)
        assertTrue(exported.jsonSchema().contains("PRIORITY_REQUIRED"),
                "the cross-field rule must be carried in x-a12-rule for the interpreter tier");
    }
}
