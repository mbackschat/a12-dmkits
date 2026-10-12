package io.github.mbackschat.a12.dm.schemakit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.normalize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape 4 (SCHEMAKIT-SPEC §6, proposal §6) — Tier-B / Tier-C carriage: a rule and a computation
 * (which JSON Schema cannot express) ride {@code x-a12-rule} / {@code x-a12-computation} so an
 * a12 → JSON Schema → a12 round-trip preserves them exactly. The fixture is authored through the typed
 * dogfood surface (modelkit {@link DocumentModelDraft} + {@link EditSession}). Root names and repetition
 * limits must survive independently of model identity and executable carriage.
 */
class RuleCarriageShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROOT_NAME = "x-a12-root-group-name";

    private static String modelWithRuleAndComputation() {
        return modelWithRuleAndComputation("Root", 0, -1, true);
    }

    private static String modelWithRuleAndComputation(String rootName, int rootMax, int archiveMax,
                                                     boolean executables) {
        var builder = DocumentModelDraft.named("DispatchModel").locale("en_US")
                .rootGroup(rootName, g -> {
                    if (rootMax > 0) g.repeatable(rootMax);
                    g.field("PostalCode", FieldKind.STRING);
                    g.numberField("Subtotal", n -> n.fractionDigits(0));
                    g.numberField("ShippingFee", n -> n.fractionDigits(0));
                });
        if (archiveMax >= 0) builder.rootGroup("Archive", g -> {
            if (archiveMax > 0) g.repeatable(archiveMax);
            g.field("PostalCode", FieldKind.STRING);
        });
        String structure = builder.build().toJson();
        assertKernelAccepts(structure);
        if (!executables) return structure;
        var session = EditSession.read(structure);
        var root = session.group("/" + rootName);
        var postal = root.str("PostalCode");
        var subtotal = root.num("Subtotal");
        var shippingFee = root.num("ShippingFee");
        root.addRule(r -> r.name("PostalCodeFormat").errorField(postal)
                .when(postal.filled().and(postal.violates("[0-9]{5}")))
                .code("POSTAL_CODE_MALFORMED").severity(Severity.ERROR)
                .message(Dsl.en("The postal code must be exactly five digits.")).build());
        root.addComputation(shippingFee, c -> c.name("ShippingTier")
                .commonPrecondition(subtotal.filled())
                .when(subtotal.lt(100)).calc(NumberLiteral.of(10))
                .when(subtotal.ge(100)).calc(NumberLiteral.of(0))
                .message(Dsl.en("Shipping fee by order subtotal.")).build());
        if (archiveMax >= 0) {
            var archive = session.group("/Archive");
            var archivePostal = archive.str("PostalCode");
            archive.addRule(r -> r.name("ArchiveFormat").errorField(archivePostal)
                    .when(archivePostal.filled().and(archivePostal.violates("[A-Z]+")))
                    .code("ARCHIVE_FORMAT").severity(Severity.ERROR)
                    .message(Dsl.en("The archive code must use uppercase letters.")).build());
        }
        return session.toJson();
    }

    @Test
    void rootIdentityAndTopologySurviveBareAndSelectedOpenApiCarriers() throws Exception {
        record RootCase(String name, int max, int archiveMax, boolean executables) {}
        List<Executable> checks = new ArrayList<>();
        for (var row : List.of(new RootCase("Root", 0, -1, true),
                new RootCase("Dispatch", 0, -1, true), new RootCase("Dispatch", 0, -1, false),
                new RootCase("Dispatch", 5, -1, true), new RootCase("Dispatch", 0, 0, true),
                new RootCase("Dispatch", 0, 5, true))) {
            for (boolean wrapped : List.of(false, true)) checks.add(() -> {
                String dm = modelWithRuleAndComputation(row.name(), row.max(), row.archiveMax(), row.executables());
                assertKernelAccepts(dm);
                var source = ModelDocument.read(dm);
                String label = row + ", wrapped=" + wrapped;
                var exported = new JsonSchemaExporter().export(dm, Dialect.OPENAPI_31, wrapped);
                JsonNode schema = MAPPER.readTree(exported.jsonSchema());
                if (wrapped) schema = schema.path("components").path("schemas").path(source.id());
                for (String rootPath : source.groupPaths()) {
                    JsonNode carrier = row.archiveMax() >= 0
                            ? schema.path("properties").path(rootPath.substring(1)) : schema;
                    assertEquals(source.isRepeating(rootPath) ? "array" : "object", carrier.path("type").asText(), label);
                    if (source.isRepeating(rootPath)) {
                        assertEquals(source.maxRepetitions(rootPath), carrier.path("maxItems").asInt(), label);
                        carrier = carrier.path("items");
                    }
                    assertEquals(rootPath.substring(1), carrier.path(ROOT_NAME).asText(), label);
                    if (row.executables()) assertFalse(carrier.path("properties").path("PostalCode").has("pattern"), label);
                }
                var imported = new JsonSchemaImporter().importSchema(exported.jsonSchema(), MappingProfile.defaults(),
                        Dialect.AUTO, wrapped ? source.id() : null);
                assertKernelAccepts(imported.dmJson());
                var back = ModelDocument.read(imported.dmJson());
                assertEquals(source.groupPaths(), back.groupPaths(), label);
                assertEquals(source.fieldPaths(), back.fieldPaths(), label);
                for (String rootPath : source.groupPaths()) {
                    assertEquals(source.isRepeating(rootPath), back.isRepeating(rootPath), label);
                    assertEquals(source.maxRepetitions(rootPath), back.maxRepetitions(rootPath), label);
                }
                assertEquals(executableElements(dm), executableElements(imported.dmJson()), label);
                assertFalse(imported.report().dispositions().stream().anyMatch(d -> d.tier() == TranscodeReport.Tier.OMITTED), label);
                assertEquals(normalize(exported.jsonSchema()), normalize(new JsonSchemaExporter()
                        .export(imported.dmJson(), Dialect.OPENAPI_31, wrapped).jsonSchema()), label);
            });
        }
        assertAll(checks);
    }

    @Test
    void untaggedForeignObjectsStillUseTheRootConvention() {
        var imported = new JsonSchemaImporter().importSchema("""
                {"type":"object","properties":{"PostalCode":{"type":"string"}}}
                """);
        assertKernelAccepts(imported.dmJson());
        assertEquals(List.of("/Root"), ModelDocument.read(imported.dmJson()).groupPaths());
    }

    @Test
    void malformedAndMixedRootAnnotationsReportFallbackWithoutDroppingStructure() {
        for (String source : List.of(
                "{\"type\":\"object\",\"x-a12-root-group-name\":17,\"properties\":{\"Code\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"Dispatch\":{\"type\":\"object\",\"x-a12-root-group-name\":\"Dispatch\",\"properties\":{\"Code\":{\"type\":\"string\"}}},\"Archive\":{\"type\":\"object\",\"properties\":{\"Code\":{\"type\":\"string\"}}}}}")) {
            var imported = new JsonSchemaImporter().importSchema(source);
            assertKernelAccepts(imported.dmJson());
            var model = ModelDocument.read(imported.dmJson());
            assertTrue(model.groupPaths().contains("/Root"));
            assertEquals(source.contains("Archive") ? 2 : 1, model.fieldPaths().size());
            assertTrue(imported.report().dispositions().stream().anyMatch(d -> d.tier() == TranscodeReport.Tier.OMITTED
                    && d.source().contains(ROOT_NAME)), imported.report().toString());
            assertTrue(imported.report().diagnostics().stream().anyMatch(d -> d.contains("root") && d.contains("fallback")));
        }
    }

    @Test
    void independentRootMinimumsReachTheirOwnSameNamedArrays() throws Exception {
        var builder = DocumentModelDraft.named("dispatch-minimums").locale("en_US");
        for (String name : List.of("Dispatch", "Archive")) builder.rootGroup(name,
                root -> root.group("Parcels", parcels -> parcels.repeatable(5).stringField("Code", f -> { })));
        var edit = EditSession.read(builder.build().toJson());
        for (String name : List.of("Dispatch", "Archive")) {
            var root = edit.group("/" + name);
            int minimum = name.equals("Dispatch") ? 1 : 2;
            root.addRule(r -> r.name("MinimumParcels").errorField(root.str("Parcels/Code"))
                    .when(Dsl.numberOfFilledGroups(root.group("Parcels")).lt(minimum)).code("MIN_PARCELS")
                    .message(Dsl.en("Supply enough parcels.")).build());
        }
        assertKernelAccepts(edit.toJson());
        for (boolean wrapped : List.of(false, true)) {
            var exported = new JsonSchemaExporter().export(edit.toJson(), Dialect.OPENAPI_31, wrapped);
            JsonNode schema = MAPPER.readTree(exported.jsonSchema());
            if (wrapped) schema = schema.path("components").path("schemas").path("dispatch-minimums");
            for (String name : List.of("Dispatch", "Archive")) {
                JsonNode root = schema.path("properties").path(name);
                assertEquals(name.equals("Dispatch") ? 1 : 2, root.path("properties").path("Parcels").path("minItems").asInt());
                assertTrue(root.path("required").toString().contains("Parcels"));
            }
            var imported = new JsonSchemaImporter().importSchema(exported.jsonSchema(), MappingProfile.defaults(),
                    Dialect.AUTO, wrapped ? "dispatch-minimums" : null);
            assertKernelAccepts(imported.dmJson());
            assertEquals(normalize(exported.jsonSchema()), normalize(new JsonSchemaExporter()
                    .export(imported.dmJson(), Dialect.OPENAPI_31, wrapped).jsonSchema()));
        }
    }

    private static List<JsonNode> executableElements(String dm) throws Exception {
        List<JsonNode> elements = new ArrayList<>();
        for (JsonNode root : MAPPER.readTree(dm).path("content").path("modelRoot").path("rootGroups")) {
            for (JsonNode element : root.path("Group").path("elements")) {
                if (element.has("Rule") || element.has("Computation")) elements.add(element);
            }
        }
        elements.sort(Comparator.comparing(n -> n.path("id").asText()));
        return elements;
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
