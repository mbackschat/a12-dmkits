package io.github.mbackschat.a12.dm.schemakit;

import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAPI dialect support on import (SCHEMAKIT-SPEC §4g): an OpenAPI document is a <em>bundle</em> of schemas
 * under {@code components/schemas}, in either the 2020-12 dialect (OpenAPI 3.1/3.2) or the Draft-05 variant
 * (OpenAPI 3.0). The importer detects the dialect, resolves {@code components/schemas} {@code $ref}s, picks a
 * component as the model root, and honors the 3.0-only encodings ({@code nullable:true}, boolean exclusive bounds).
 */
class DialectImportShapeTest {

    private static long synthesized(JsonSchemaImporter.ImportResult r) {
        return r.report().dispositions().stream().filter(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS).count();
    }

    private static boolean reports(JsonSchemaImporter.ImportResult r, String fragment) {
        return r.report().diagnostics().stream().anyMatch(d -> d.contains(fragment));
    }

    @Test
    void autoDetectsOpenApi31AndResolvesComponentsSchemasRef() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "openapi": "3.1.0", "info": { "title": "Shop API", "version": "1.0.0" },
                  "components": { "schemas": {
                    "Address": { "type": "object", "properties": { "city": { "type": "string" } } },
                    "Customer": { "type": "object",
                      "properties": { "name": { "type": "string" }, "home": { "$ref": "#/components/schemas/Address" } },
                      "required": ["name"] } } } }
                """, MappingProfile.defaults(), Dialect.AUTO, "Customer");

        assertKernelAccepts(r.dmJson());
        assertTrue(reports(r, "OPENAPI_31") && reports(r, "auto-detected"), () -> "dialect auto-detected: " + r.report().diagnostics());
        assertTrue(r.dmJson().contains("city"), "the #/components/schemas/Address ref resolved (city imported)");
    }

    @Test
    void openApi30NullableRequiredFieldBecomesOptional() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "openapi": "3.0.3", "info": { "title": "X", "version": "1" },
                  "components": { "schemas": {
                    "Order": { "type": "object",
                      "properties": { "code": { "type": "string" }, "note": { "type": "string", "nullable": true } },
                      "required": ["code", "note"] } } } }
                """, MappingProfile.defaults(), Dialect.AUTO, "Order");

        assertKernelAccepts(r.dmJson());
        assertTrue(reports(r, "OPENAPI_30"), () -> "3.0 auto-detected: " + r.report().diagnostics());
        assertTrue(reports(r, "nullable"), () -> "nullable→optional is reported: " + r.report().diagnostics());
    }

    @Test
    void openApi30BooleanExclusiveMinimumBecomesAStrictBoundRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "openapi": "3.0.0", "info": { "title": "X", "version": "1" },
                  "components": { "schemas": {
                    "Item": { "type": "object",
                      "properties": { "qty": { "type": "integer", "minimum": 1, "exclusiveMinimum": true } } } } } }
                """, MappingProfile.defaults(), Dialect.AUTO, "Item");

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "boolean exclusiveMinimum → one strict-bound rule: " + r.report().dispositions());
        assertFalse(r.dmJson().contains("\"minValue\""), "the inclusive minimum is suppressed (the bound is exclusive)");
    }

    @Test
    void jsonSchemaDialectKeepsNumericExclusiveAndDoesNotTreatBooleanAsABound() {
        // Same field shape, but read as JSON Schema (forced): a boolean exclusiveMinimum is NOT a numeric bound,
        // so no strict-bound rule, and the inclusive minimum:1 stands.
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "properties": { "qty": { "type": "integer", "minimum": 1, "exclusiveMinimum": true } } }
                """, MappingProfile.defaults(), Dialect.JSON_SCHEMA, null);

        assertKernelAccepts(r.dmJson());
        assertEquals(0, synthesized(r), () -> "a boolean exclusiveMinimum is not a JSON-Schema numeric bound: " + r.report().dispositions());
    }

    @Test
    void aMultiSchemaOpenApiDocumentWithoutAComponentIsRefusedWithTheList() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new JsonSchemaImporter().importSchema("""
                        { "openapi": "3.1.0", "info": { "title": "X", "version": "1" },
                          "components": { "schemas": {
                            "Pet": { "type": "object", "properties": { "n": { "type": "string" } } },
                            "Order": { "type": "object", "properties": { "id": { "type": "string" } } } } } }
                        """, MappingProfile.defaults(), Dialect.AUTO, null));

        assertTrue(ex.getMessage().contains("Pet") && ex.getMessage().contains("Order"),
                () -> "the refusal lists the available components: " + ex.getMessage());
    }

    @Test
    void yamlOpenApiDocumentImports() {
        // OpenAPI specs are commonly YAML — the importer tries JSON, then YAML (a JSON superset).
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                openapi: "3.0.3"
                info:
                  title: Shop
                  version: "1"
                components:
                  schemas:
                    Order:
                      type: object
                      properties:
                        code: { type: string }
                        qty: { type: integer, minimum: 1, exclusiveMinimum: true }
                      required: [code]
                """, MappingProfile.defaults(), Dialect.AUTO, "Order");

        assertKernelAccepts(r.dmJson());
        assertTrue(reports(r, "OPENAPI_30"), () -> "YAML 3.0 doc detected: " + r.report().diagnostics());
        assertTrue(r.dmJson().contains("code") && r.dmJson().contains("qty"), "the YAML component imported its fields");
        assertEquals(1, synthesized(r), () -> "the YAML boolean exclusiveMinimum still maps: " + r.report().dispositions());
    }

    @Test
    void explicitDiscriminatorDrivesTheUnionEnum() {
        // A discriminated union as a PROPERTY of an object component (a top-level union component would need a
        // type:object root, which the importer requires — SCHEMAKIT-GAPS).
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "openapi": "3.1.0", "info": { "title": "X", "version": "1" },
                  "components": { "schemas": {
                    "Order": { "type": "object", "properties": {
                      "payment": { "discriminator": { "propertyName": "method" },
                        "oneOf": [
                          { "type": "object", "properties": { "method": { "const": "CARD" }, "pan": { "type": "string" } } },
                          { "type": "object", "properties": { "method": { "const": "CASH" }, "received": { "type": "string" } } } ] } } } } } }
                """, MappingProfile.defaults(), Dialect.AUTO, "Order");

        assertKernelAccepts(r.dmJson());
        assertTrue(r.dmJson().contains("CARD") && r.dmJson().contains("CASH"),
                "the discriminator (method) became an enum of the variant values");
    }

    @Test
    void topLevelUnionComponentBecomesARootSuperset() {
        // A components/schemas entry that is itself a oneOf (a top-level discriminated union — common in OpenAPI):
        // the model root becomes the superset, instead of the importer refusing a non-object root.
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "openapi": "3.1.0", "info": { "title": "X", "version": "1" },
                  "components": { "schemas": {
                    "Payment": { "discriminator": { "propertyName": "method" },
                      "oneOf": [
                        { "type": "object", "properties": { "method": { "const": "CARD" }, "pan": { "type": "string" } } },
                        { "type": "object", "properties": { "method": { "const": "CASH" }, "received": { "type": "string" } } } ] } } } }
                """, MappingProfile.defaults(), Dialect.AUTO, "Payment");

        assertKernelAccepts(r.dmJson());
        assertTrue(r.dmJson().contains("CARD") && r.dmJson().contains("CASH"), "the root discriminator enum carries the variant values");
        assertTrue(r.dmJson().contains("pan") && r.dmJson().contains("received"), "both variants' fields are unioned at the root");
    }

    @Test
    void bareTopLevelUnionSchemaImports() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "Shape",
                  "oneOf": [
                    { "type": "object", "properties": { "kind": { "const": "CIRCLE" }, "radius": { "type": "integer" } } },
                    { "type": "object", "properties": { "kind": { "const": "SQUARE" }, "side": { "type": "integer" } } } ] }
                """);

        assertKernelAccepts(r.dmJson());
        assertTrue(r.dmJson().contains("radius") && r.dmJson().contains("side"), "a bare top-level union imports as a root superset");
    }
}
