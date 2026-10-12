package io.github.mbackschat.a12.dm.schemakit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import java.util.List;
import java.util.Optional;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SCHEMAKIT-SPEC §4: native whole-root minimums complement authoritative rule carriage.
 * Rule-pattern applicability remains carried; independently declared field patterns remain native.
 */
class TierAProjectionShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PATTERN = "[0-9]{5}";
    private static final String INDEX_FIELD = "x-a12-index-field-name";

    @Test
    void emptyAuthoredPatternIsCarriedWithoutClaimingAUniversalNativeConstraint() throws Exception {
        var exported = new JsonSchemaExporter().export(modelWithPatternRule(""));
        assertFalse(MAPPER.readTree(exported.jsonSchema()).path("properties").path("PostalCode").has("pattern"));
        assertTrue(exported.jsonSchema().contains(Carriage.X_RULE));
        assertEquals(MAPPER.readTree(modelWithPatternRule("")).path("content").path("modelRoot"),
                MAPPER.readTree(new JsonSchemaImporter().importSchema(exported.jsonSchema()).dmJson()).path("content").path("modelRoot"));
    }

    @Test
    void indexedPatternIsCarriedWithoutProjectingAConstraintOnEveryRow() throws Exception {
        String dm = modelWithIndexedPatternRule();
        SchemaTestSupport.assertKernelAccepts(dm);
        var exported = new JsonSchemaExporter().export(dm);
        JsonNode schema = MAPPER.readTree(exported.jsonSchema());
        JsonNode array = schema.path("properties").path("Items");
        JsonNode item = array.path("items");
        assertEquals("Sku", item.path(INDEX_FIELD).asText());
        assertFalse(array.has(INDEX_FIELD), "index metadata belongs to the group object, not its array");
        assertFalse(item.path("properties").path("Label").has("pattern"),
                "a selected row does not constrain every item label");
        assertEquals(ruleElement(dm), schema.path(Carriage.X_RULE).path(0).path("element"));
        assertTrue(exported.report().dispositions().stream().anyMatch(d ->
                d.tier() == TranscodeReport.Tier.CARRIED
                        && d.source().equals("/Root/Items/indexFieldName") && d.target().equals(INDEX_FIELD)));

        var back = new JsonSchemaImporter().importSchema(exported.jsonSchema());
        ModelDocument doc = ModelDocument.read(back.dmJson());
        assertEquals(Optional.of("Sku"), doc.indexField("/Root/Items"));
        assertEquals(5, doc.maxRepetitions("/Root/Items"));
        assertEquals(ruleElement(dm), ruleElement(back.dmJson()));
        assertTrue(back.report().dispositions().stream().anyMatch(d ->
                d.tier() == TranscodeReport.Tier.CARRIED
                        && d.source().equals("/Root/Items/" + INDEX_FIELD)
                        && d.target().equals("/Root/Items/indexFieldName")));
        assertTrue(back.consistencyGate().isEmpty(), "restored rules do not retain a structural gate receipt");
        SchemaTestSupport.assertKernelAccepts(back.dmJson());
        assertEquals(SchemaTestSupport.normalize(exported.jsonSchema()),
                SchemaTestSupport.normalize(new JsonSchemaExporter().export(back.dmJson()).jsonSchema()));
    }

    @Test
    void restoredIndexedRulesInvalidateAnEarnedSynthesisReceipt() throws Exception {
        ObjectNode schema = (ObjectNode) MAPPER.readTree(
                new JsonSchemaExporter().export(modelWithIndexedPatternRule()).jsonSchema());
        ((ObjectNode) schema.path("properties").path("Title")).put("const", "T");
        ObjectNode structural = schema.deepCopy();
        structural.remove(Carriage.X_RULE);
        assertTrue(new JsonSchemaImporter().importSchema(structural.toString()).consistencyGate().isPresent());

        var restored = new JsonSchemaImporter().importSchema(schema.toString());
        assertTrue(restored.consistencyGate().isEmpty());
        assertEquals(Optional.of("Sku"), ModelDocument.read(restored.dmJson()).indexField("/Root/Items"));
        assertEquals(ruleElement(modelWithIndexedPatternRule()), ruleElement(restored.dmJson()));
        SchemaTestSupport.assertKernelAccepts(restored.dmJson());
    }

    @Test
    void removingOnlyTheIndexAnnotationLeavesTheCarriedRuleKernelInvalid() throws Exception {
        String dm = modelWithIndexedPatternRule();
        SchemaTestSupport.assertKernelAccepts(dm);
        ObjectNode schema = (ObjectNode) MAPPER.readTree(new JsonSchemaExporter().export(dm).jsonSchema());
        ((ObjectNode) schema.path("properties").path("Items").path("items")).remove(INDEX_FIELD);
        var back = new JsonSchemaImporter().importSchema(schema.toString());
        assertTrue(ModelDocument.read(back.dmJson()).indexField("/Root/Items").isEmpty());
        assertEquals(ruleElement(dm), ruleElement(back.dmJson()));
        var rejected = assertThrows(ModelConsistencyException.class,
                () -> ModelLoader.loadSelfContained(back.dmJson()));
        assertTrue(rejected.notifications().stream().anyMatch(n -> n.message().contains("MVK_NO_INDEX_FIELD")),
                () -> "the absent-index kernel control: " + rejected.notifications());
    }

    @Test
    void fixedGroupIndexMetadataAndAnUnindexedSiblingStayDistinct() throws Exception {
        String dm = DocumentModelDraft.named("Order").locale("en_US")
                .rootGroup("Root", g -> g
                        .group("Single", one -> one.indexField("Key").field("Key", FieldKind.STRING))
                        .group("Plain", plain -> plain.field("Key", FieldKind.STRING)))
                .build().toJson();
        SchemaTestSupport.assertKernelAccepts(dm);
        var exported = new JsonSchemaExporter().export(dm);
        JsonNode schema = MAPPER.readTree(exported.jsonSchema());
        assertEquals("Key", schema.path("properties").path("Single").path(INDEX_FIELD).asText());
        assertFalse(schema.path("properties").path("Plain").has(INDEX_FIELD));
        assertFalse(schema.path("properties").path("Single").has("uniqueItems"));
        assertFalse(schema.path("properties").path("Single").has("required"));
        var back = new JsonSchemaImporter().importSchema(exported.jsonSchema());
        var doc = ModelDocument.read(back.dmJson());
        assertEquals(Optional.of("Key"), doc.indexField("/Root/Single"));
        assertTrue(doc.indexField("/Root/Plain").isEmpty());
        assertFalse(doc.isRepeating("/Root/Single"));
        SchemaTestSupport.assertKernelAccepts(back.dmJson());
    }

    @Test
    void anAdmittedRelativeIndexReferenceIsCarriedWithoutChangingItsSpelling() throws Exception {
        String control = DocumentModelDraft.named("Order").locale("en_US")
                .rootGroup("Root", g -> g.field("Sku", FieldKind.STRING)
                        .group("Items", items -> items.repeatable(5).indexField("Sku")
                                .field("Sku", FieldKind.STRING)))
                .build().toJson();
        SchemaTestSupport.assertKernelAccepts(control);
        ObjectNode tree = (ObjectNode) MAPPER.readTree(control);
        for (JsonNode element : tree.path("content").path("modelRoot").path("rootGroups").path(0)
                .path("Group").path("elements")) {
            if ("Items".equals(element.path("name").asText())) {
                ((ObjectNode) element.path("Group")).put("indexFieldName", "../Sku");
            }
        }
        String relative = tree.toString();
        SchemaTestSupport.assertKernelAccepts(relative);
        var exported = new JsonSchemaExporter().export(relative);
        assertEquals("../Sku", MAPPER.readTree(exported.jsonSchema()).path("properties")
                .path("Items").path("items").path(INDEX_FIELD).asText());
        var back = new JsonSchemaImporter().importSchema(exported.jsonSchema());
        assertEquals(Optional.of("../Sku"), ModelDocument.read(back.dmJson()).indexField("/Root/Items"));
        SchemaTestSupport.assertKernelAccepts(back.dmJson());
        assertEquals(SchemaTestSupport.normalize(exported.jsonSchema()),
                SchemaTestSupport.normalize(new JsonSchemaExporter().export(back.dmJson()).jsonSchema()));
    }

    @Test
    void aNontextualIndexAnnotationIsOmittedWithoutDiscardingItsGroup() throws Exception {
        ObjectNode schema = (ObjectNode) MAPPER.readTree(new JsonSchemaExporter()
                .export(modelWithIndexedPatternRule()).jsonSchema());
        schema.remove(Carriage.X_RULE);
        ((ObjectNode) schema.path("properties").path("Items").path("items")).put(INDEX_FIELD, 7);
        var back = new JsonSchemaImporter().importSchema(schema.toString());
        var doc = ModelDocument.read(back.dmJson());
        assertTrue(doc.indexField("/Root/Items").isEmpty());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/Items/Label"));
        assertEquals(5, doc.maxRepetitions("/Root/Items"));
        assertTrue(back.report().dispositions().stream().anyMatch(d ->
                d.tier() == TranscodeReport.Tier.OMITTED && d.source().equals("/Root/Items/" + INDEX_FIELD)));
        assertTrue(back.report().diagnostics().stream().anyMatch(d -> d.contains(INDEX_FIELD)));
        SchemaTestSupport.assertKernelAccepts(back.dmJson());
    }

    @Test
    void wrappedOpenApiCarriesTheSameGroupIndexScalar() throws Exception {
        String dm = modelWithIndexedPatternRule();
        SchemaTestSupport.assertKernelAccepts(dm);
        var exported = new JsonSchemaExporter().export(dm, Dialect.OPENAPI_31, true);
        JsonNode component = MAPPER.readTree(exported.jsonSchema()).path("components").path("schemas").path("Order");
        assertEquals("Sku", component.path("properties").path("Items").path("items").path(INDEX_FIELD).asText());
        var back = new JsonSchemaImporter().importSchema(exported.jsonSchema(), MappingProfile.defaults(),
                Dialect.AUTO, "Order");
        assertEquals(Optional.of("Sku"), ModelDocument.read(back.dmJson()).indexField("/Root/Items"));
        assertEquals(ruleElement(dm), ruleElement(back.dmJson()));
        SchemaTestSupport.assertKernelAccepts(back.dmJson());
    }

    private static JsonNode ruleElement(String dm) throws Exception {
        for (JsonNode element : MAPPER.readTree(dm).path("content").path("modelRoot").path("rootGroups").path(0)
                .path("Group").path("elements")) {
            if ("Rule".equals(element.path("type").asText())
                    && "SelectedLabel".equals(element.path("name").asText())) return element;
        }
        throw new IllegalStateException("the typed selected-label rule must exist");
    }

    private static String modelWithIndexedPatternRule() {
        String structure = DocumentModelDraft.named("Order").locale("en_US")
                .rootGroup("Root", g -> g.field("Title", FieldKind.STRING)
                        .group("Items", i -> i.repeatable(5).indexField("Sku")
                                .field("Sku", FieldKind.STRING).field("Label", FieldKind.STRING)))
                .build().toJson();
        BoundModel m = BoundModel.ofJson(structure);
        StringRef title = m.str("/Root/Title");
        Rule rule = m.rule(m.group("/Root")).name("SelectedLabel")
                .errorField(title)
                .when(title.filled().and(Dsl.violates(
                        m.field("/Root/Items/Label").semanticIndex("S-1").index(), "[A-Z]+")))
                .code("LABEL_FORMAT").severity(Severity.ERROR)
                .message(Dsl.en("The selected item label must be uppercase."))
                .build();
        return m.add(rule).toJson();
    }

    private static String modelWithPatternRule() { return modelWithPatternRule(PATTERN); }

    private static String modelWithPatternRule(String pattern) {
        String structure = DocumentModelDraft.named("Order")
                .locale("en_US")
                .rootGroup("Root", g -> g.field("PostalCode", FieldKind.STRING))
                .build()
                .toJson();
        BoundModel m = BoundModel.ofJson(structure);
        StringRef postal = m.str("/Root/PostalCode");
        Rule rule = m.rule(m.group("/Root"))
                .name("PostalCodeFormat")
                .errorField(postal)
                .when(postal.filled().and(postal.violates(pattern)))
                .code("POSTAL_CODE_MALFORMED")
                .severity(Severity.ERROR)
                .message(Dsl.en("The postal code must be exactly five digits."))
                .build();
        return m.add(rule).toJson();
    }

    @Test
    void aRuleProjectionCannotOverwriteADifferentDeclaredPatternOnTheSameField() throws Exception {
        String structure = DocumentModelDraft.named("Order").locale("en_US")
                .rootGroup("Root", g -> g.stringField("PostalCode", f -> f.pattern("AA", "Check the declared format.")))
                .build().toJson();
        var session = EditSession.read(structure);
        var root = session.group("/Root");
        var postal = root.str("PostalCode");
        root.addRule(r -> r.name("Digits").errorField(postal).when(postal.violates("[0-9]+"))
                .code("DIGITS").message(Dsl.en("Check the digits.")).build());
        String dm = session.toJson();
        SchemaTestSupport.assertKernelAccepts(dm);
        var exported = new JsonSchemaExporter().export(dm);
        assertEquals("AA", MAPPER.readTree(exported.jsonSchema()).path("properties").path("PostalCode").path("pattern").asText());
        var imported = new JsonSchemaImporter().importSchema(exported.jsonSchema());
        SchemaTestSupport.assertKernelAccepts(imported.dmJson());
        assertEquals(Optional.of("AA"), ModelDocument.read(imported.dmJson()).fieldPattern("/Root/PostalCode"));
        assertEquals(MAPPER.readTree(dm).findValue("errorCondition"), MAPPER.readTree(imported.dmJson()).findValue("errorCondition"));
    }

    @Test
    void aRulePatternIsCarriedWithAnExplicitNativeProjectionDiagnostic() throws Exception {
        JsonSchemaExporter.ExportResult exported = new JsonSchemaExporter().export(modelWithPatternRule());
        JsonNode schema = MAPPER.readTree(exported.jsonSchema());

        assertFalse(schema.path("properties").path("PostalCode").has("pattern"));
        assertTrue(exported.jsonSchema().contains(Carriage.X_RULE));
        assertTrue(exported.report().diagnostics().stream().anyMatch(d -> d.contains("pattern") && d.contains("carried")));
        assertFalse(exported.report().dispositions().stream().anyMatch(d -> d.tier() == TranscodeReport.Tier.TIER_A));
    }

    @Test
    void aWholeMinimumAboveIntRangeRemainsExactAndIntersectsIndependentBounds() throws Exception {
        for (boolean reversed : List.of(false, true)) {
            String structure = DocumentModelDraft.named("Order").locale("en_US")
                    .rootGroup("Root", g -> g.group("Items", i -> i.repeatable(5).field("Label", FieldKind.STRING)))
                    .build().toJson();
            var session = EditSession.read(structure);
            var root = session.group("/Root");
            var items = root.group("Items");
            var label = root.str("Items/Label");
            for (String bound : reversed ? List.of("2147483648", "2147483647") : List.of("2147483647", "2147483648")) {
                root.addRule(r -> r.name("MinItems" + bound).errorField(label)
                        .when(Dsl.numberOfFilledGroups(items).lt(bound)).code("MIN_ITEMS_" + bound)
                        .message(Dsl.en("Check the number of items.")).build());
            }
            SchemaTestSupport.assertKernelAccepts(session.toJson());
            var exported = new JsonSchemaExporter().export(session.toJson());
            assertEquals(new java.math.BigInteger("2147483648"), MAPPER.readTree(exported.jsonSchema())
                    .path("properties").path("Items").path("minItems").bigIntegerValue());
            var imported = new JsonSchemaImporter().importSchema(exported.jsonSchema());
            SchemaTestSupport.assertKernelAccepts(imported.dmJson());
            assertEquals(SchemaTestSupport.normalize(exported.jsonSchema()), SchemaTestSupport.normalize(
                    new JsonSchemaExporter().export(imported.dmJson()).jsonSchema()));
        }
    }

    @Test
    void aNonIntegerGroupCountBoundDoesNotCrashTheExport() throws Exception {
        // F8 — TierAProjection called intValueExact on the NumberOfFilledGroups(G*) < N bound of ANY exported
        // rule; a kernel-legal but non-integer bound (2.5) threw ArithmeticException, aborting the whole export.
        String structure = DocumentModelDraft.named("Order").locale("en_US")
                .rootGroup("Root", g -> g.group("Items", i -> {
                    i.repeatable(10);
                    i.field("Sku", FieldKind.STRING);
                }))
                .build().toJson();
        BoundModel m = BoundModel.ofJson(structure);
        Rule rule = m.rule(m.group("/Root"))
                .name("MinLines").errorField(m.str("/Root/Items/Sku"))
                .when(Dsl.numberOfFilledGroups(m.group("/Root/Items")).lt("2.5"))
                .code("MIN_LINES").severity(Severity.ERROR).message(Dsl.en("Too few lines.")).build();
        String dm = m.add(rule).toJson();

        JsonSchemaExporter.ExportResult exported = new JsonSchemaExporter().export(dm);   // must NOT throw
        JsonNode schema = MAPPER.readTree(exported.jsonSchema());
        assertFalse(schema.path("properties").path("Items").has("minItems"),
                () -> "a non-integer group-count bound is not projectable as minItems: " + exported.jsonSchema());
        assertTrue(exported.report().diagnostics().stream().anyMatch(d -> d.contains("minItems")),
                () -> "the skipped minItems projection is reported: " + exported.report().diagnostics());
    }

    @Test
    void projectionIsAdditiveAndRoundTripStillLossless() throws Exception {
        String dm = modelWithPatternRule();
        JsonSchemaExporter.ExportResult first = new JsonSchemaExporter().export(dm);
        JsonSchemaImporter.ImportResult back = new JsonSchemaImporter().importSchema(first.jsonSchema());
        SchemaTestSupport.assertKernelAccepts(back.dmJson());
        JsonSchemaExporter.ExportResult second = new JsonSchemaExporter().export(back.dmJson());

        assertEquals(SchemaTestSupport.normalize(first.jsonSchema()),
                SchemaTestSupport.normalize(second.jsonSchema()),
                "the unprojected rule must round-trip through authoritative carriage");
    }
}
