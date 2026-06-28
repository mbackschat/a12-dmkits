package io.github.mbackschat.a12.dm.schemakit.spike;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static io.github.mbackschat.a12.dm.schemakit.spike.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code oneOf}/{@code anyOf} union strategy (SCHEMAKIT-SPEC §4b; task #4). a12 has no sum type, so a
 * property that is one of several same-kind <b>object</b> variants becomes — under the default
 * {@code SUPERSET_RULE} strategy — a single group holding the <b>union</b> of every variant's fields (all
 * optional, since an instance fills only one variant). When the variants share a <b>discriminator</b> (a
 * property each pins to one {@code const}/single-{@code enum} value), it becomes an ENUMERATION of those
 * values and drives per-variant conditional-requiredness rules ("field X is required when method = CARD").
 * Without a discriminator, exclusivity has nothing to key on and is reported, not enforced. Omit, never block.
 */
class UnionShapeTest {

    private static final String DISCRIMINATED = """
            { "type": "object", "properties": {
                "payment": { "oneOf": [
                    { "type": "object", "required": ["method", "cardNumber"],
                      "properties": { "method": { "const": "CARD" }, "cardNumber": { "type": "string" } } },
                    { "type": "object", "required": ["method", "iban"],
                      "properties": { "method": { "const": "BANK" }, "iban": { "type": "string" } } } ] } } }
            """;
    private static final String NO_DISCRIMINATOR = """
            { "type": "object", "properties": {
                "contact": { "anyOf": [
                    { "type": "object", "properties": { "email": { "type": "string" } } },
                    { "type": "object", "properties": { "phone": { "type": "string" } } } ] } } }
            """;

    @Test
    void supersetMergesVariantFieldsAndTheDiscriminatorBecomesAnEnum() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(DISCRIMINATED);

        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertTrue(doc.groupPaths().contains("/Root/payment"));
        assertEquals(Optional.of("ENUMERATION"), doc.fieldKind("/Root/payment/method"), "discriminator → enum");
        assertEquals(List.of("CARD", "BANK"), doc.enumValues("/Root/payment/method"));
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/payment/cardNumber"));
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/payment/iban"), "every variant's fields are unioned");
    }

    @Test
    void aDiscriminatorDrivesConditionalRequirednessRules() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(DISCRIMINATED);

        long discriminatorRules = r.report().dispositions().stream()
                .filter(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS)
                .count();
        assertEquals(2, discriminatorRules, "one conditional-requiredness rule per (variant → its required field)");
        assertKernelAccepts(r.dmJson());   // the synthesized rules parse + pass checkConsistency
    }

    @Test
    void withoutADiscriminatorTheSupersetIsOptionalAndExclusivityIsReportedNotEnforced() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(NO_DISCRIMINATOR);

        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/contact/email"));
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/contact/phone"));
        assertEquals(0, r.report().dispositions().stream()
                .filter(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS).count(), "no discriminator → no rules");
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("exclusivity")),
                "the unenforced exclusivity is reported");
    }

    @Test
    void firstVariantStrategyImportsOnlyTheFirstVariant() {
        MappingProfile p = MappingProfile.defaults().withUnionStrategy(MappingProfile.UnionStrategy.FIRST_VARIANT);
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(NO_DISCRIMINATOR, p);

        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/contact/email"));
        assertTrue(doc.fieldKind("/Root/contact/phone").isEmpty(), "only the first variant is imported");
    }

    @Test
    void theOmitStrategySkipsTheUnion() {
        MappingProfile p = MappingProfile.defaults().withUnionStrategy(MappingProfile.UnionStrategy.OMIT);
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(NO_DISCRIMINATOR, p);

        assertFalse(ModelDocument.read(r.dmJson()).groupPaths().contains("/Root/contact"));
        assertTrue(r.report().dispositions().stream()
                .anyMatch(d -> d.source().equals("/Root/contact") && d.tier() == TranscodeReport.Tier.OMITTED));
        assertKernelAccepts(r.dmJson());
    }
}
