package io.github.mbackschat.a12.dm.schemakit;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.normalize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.UniquenessCriterion;

/**
 * A document uniqueness criterion constrains values across stored documents, which one document's JSON Schema
 * cannot state, so export carries A12's stored member verbatim in {@code x-a12-document-uniqueness-criteria} and
 * import restores it, each criterion reported CARRIED (SCHEMAKIT-GAPS DG178, KERNEL-FINDINGS KF671). The kernel's
 * consistency check is the admission oracle after the round trip. Own domain (insurance policies).
 */
class UniquenessCriterionCarriageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CARRIER = "x-a12-document-uniqueness-criteria";

    private static final UniquenessCriterion POLICY_KEY = new UniquenessCriterion("PolicyKey",
            List.of("/Policy/PolicyNo", "/Policy/Product"), Map.of("en_US", "Policy $/Policy/PolicyNo$ exists"));
    private static final UniquenessCriterion HOLDER_KEY = new UniquenessCriterion("HolderKey",
            List.of("/Policy/Holder"), Map.of("en_US", "Holder already insured"));

    private static DocumentModelDraft.Builder policy() {
        return DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p
                        .stringField("PolicyNo", f -> f.required())
                        .stringField("Product", f -> f.required())
                        .stringField("Holder", f -> f.required()));
    }

    @Test
    void exportCarriesEachCriterionAndImportRestoresThemInBothWireForms() throws Exception {
        String source = policy().uniquenessCriterion(POLICY_KEY).uniquenessCriterion(HOLDER_KEY).build().toJson();
        assertKernelAccepts(source);
        JsonNode stored = MAPPER.readTree(source).path("content").path("documentUniquenessCriteria");

        for (boolean wrapped : new boolean[] {false, true}) {
            String label = wrapped ? "wrapped OpenAPI 3.1" : "bare JSON Schema";
            var exported = new JsonSchemaExporter().export(source, Dialect.OPENAPI_31, wrapped);
            JsonNode schema = MAPPER.readTree(exported.jsonSchema());
            JsonNode carrier = wrapped ? schema.path("components").path("schemas").path("policy") : schema;
            assertEquals(stored, carrier.path(CARRIER), label);
            assertEquals(List.of("documentUniquenessCriteria/PolicyKey", "documentUniquenessCriteria/HolderKey"),
                    carried(exported.report()), label);

            var imported = new JsonSchemaImporter().importSchema(exported.jsonSchema(), MappingProfile.defaults(),
                    Dialect.AUTO, wrapped ? "policy" : null);
            assertKernelAccepts(imported.dmJson());
            assertEquals(List.of(POLICY_KEY, HOLDER_KEY), ModelDocument.read(imported.dmJson()).uniquenessCriteria(), label);
            assertEquals(List.of("documentUniquenessCriteria/PolicyKey", "documentUniquenessCriteria/HolderKey"),
                    carried(imported.report()), label);
            assertFalse(imported.report().dispositions().stream().anyMatch(d -> d.tier() == TranscodeReport.Tier.OMITTED),
                    label);
            assertTrue(imported.consistencyGate().isEmpty(), label + ": carriage rewrote the gated model");
            assertEquals(normalize(exported.jsonSchema()), normalize(new JsonSchemaExporter()
                    .export(imported.dmJson(), Dialect.OPENAPI_31, wrapped).jsonSchema()), label);
        }
    }

    /** The control: a model without criteria carries no member, so the carrier is the criteria and nothing else. */
    @Test
    void aModelWithoutCriteriaCarriesNone() throws Exception {
        var exported = new JsonSchemaExporter().export(policy().build().toJson());

        assertFalse(MAPPER.readTree(exported.jsonSchema()).has(CARRIER));
        assertEquals(List.of(), carried(exported.report()));
    }

    /** Import never blocks: a carrier that is no array is omitted with a corrective, and the structure survives. */
    @Test
    void aCarrierThatIsNoArrayIsOmittedAndTheStructureStillImports() {
        var imported = new JsonSchemaImporter().importSchema("""
                {"type":"object","x-a12-root-group-name":"Policy",
                 "properties":{"PolicyNo":{"type":"string"}},
                 "x-a12-document-uniqueness-criteria":{"name":"PolicyKey"}}
                """);

        assertKernelAccepts(imported.dmJson());
        assertEquals(List.of(), ModelDocument.read(imported.dmJson()).uniquenessCriteria());
        assertTrue(imported.report().dispositions().stream().anyMatch(d -> d.tier() == TranscodeReport.Tier.OMITTED
                && d.source().equals("/" + CARRIER)), imported.report().toString());
        assertTrue(imported.report().diagnostics().stream().anyMatch(d -> d.startsWith(CARRIER + " must be an array")),
                imported.report().toString());
    }

    private static List<String> carried(TranscodeReport report) {
        return report.dispositions().stream()
                .filter(d -> d.tier() == TranscodeReport.Tier.CARRIED && d.source().startsWith("documentUniquenessCriteria/"))
                .map(TranscodeReport.Disposition::source).toList();
    }
}
