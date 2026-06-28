package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Multi-model bundle import (SCHEMAKIT-SPEC §4g/§4e): a whole OpenAPI document → one model per
 * {@code components/schemas} entry, with an object cross-reference wired as an a12 <b>mount</b> (include) so a
 * shared definition is single-sourced rather than inlined into every referrer. The bundle is kernel-checked by
 * loading each model against a resolver over the others (the kernel-free {@link ReferenceResolvers}).
 */
class BundleShapeTest {

    @Test
    void bundleImportsEachComponentAndWiresObjectRefsAsMounts() {
        JsonSchemaImporter.BundleResult bundle = new JsonSchemaImporter().importBundle("""
                { "openapi": "3.1.0", "info": { "title": "X", "version": "1" },
                  "components": { "schemas": {
                    "Address": { "type": "object", "properties": { "city": { "type": "string" } } },
                    "Customer": { "type": "object",
                      "properties": { "name": { "type": "string" }, "home": { "$ref": "#/components/schemas/Address" } } } } } }
                """, MappingProfile.defaults(), Dialect.AUTO);

        assertEquals(2, bundle.models().size(), "one model per component");
        String customer = bundle.models().get("Customer").dmJson();
        assertTrue(customer.contains("modelAlias"), "the object ref became a mount");
        assertTrue(customer.contains("Address"), "the mount references the Address model");
        assertFalse(customer.contains("city"), "Address is mounted, not inlined — city is not duplicated into Customer");
        assertKernelAcceptsBundle(bundle);
    }

    @Test
    void recursiveComponentExpandsRatherThanSelfMounting() {
        // The kernel forbids a model including itself, so a self-recursive component is expanded (to the profile
        // recursion depth) then omitted with a to-do — NOT self-mounted — and still loads self-contained.
        JsonSchemaImporter.BundleResult bundle = new JsonSchemaImporter().importBundle("""
                { "openapi": "3.1.0", "info": { "title": "X", "version": "1" },
                  "components": { "schemas": {
                    "TreeNode": { "type": "object",
                      "properties": { "label": { "type": "string" }, "parent": { "$ref": "#/components/schemas/TreeNode" } } } } } }
                """, MappingProfile.defaults(), Dialect.AUTO);

        JsonSchemaImporter.ImportResult node = bundle.models().get("TreeNode");
        assertTrue(node.report().diagnostics().stream().anyMatch(d -> d.contains("recursive")),
                () -> "the self-recursion is reported, not self-mounted: " + node.report().diagnostics());
        assertKernelAcceptsBundle(bundle);
    }

    @Test
    void mutualRecursionBreaksTheCycleAndStaysLoadable() {
        // A↔B object refs would be a cyclic include (kernel-rejected); the importer breaks one edge (inline)
        // so the bundle still loads.
        JsonSchemaImporter.BundleResult bundle = new JsonSchemaImporter().importBundle("""
                { "openapi": "3.1.0", "info": { "title": "X", "version": "1" },
                  "components": { "schemas": {
                    "Node": { "type": "object", "properties": { "name": { "type": "string" },
                              "edge": { "$ref": "#/components/schemas/Edge" } } },
                    "Edge": { "type": "object", "properties": { "weight": { "type": "string" },
                              "to": { "$ref": "#/components/schemas/Node" } } } } } }
                """, MappingProfile.defaults(), Dialect.AUTO);

        assertEquals(2, bundle.models().size(), "both components import");
        assertKernelAcceptsBundle(bundle);   // no cyclic include survives
        boolean broke = bundle.models().values().stream()
                .anyMatch(m -> m.report().diagnostics().stream().anyMatch(d -> d.contains("cycle")));
        assertTrue(broke, "one edge of the cycle was inlined to break it");
    }

    @Test
    void arrayOfRefBecomesARepeatableMountInABundle() {
        JsonSchemaImporter.BundleResult bundle = new JsonSchemaImporter().importBundle("""
                { "openapi": "3.1.0", "info": { "title": "X", "version": "1" },
                  "components": { "schemas": {
                    "Line": { "type": "object", "properties": { "sku": { "type": "string" } } },
                    "Order": { "type": "object", "properties": {
                      "lines": { "type": "array", "maxItems": 50, "items": { "$ref": "#/components/schemas/Line" } } } } } } }
                """, MappingProfile.defaults(), Dialect.AUTO);

        String order = bundle.models().get("Order").dmJson();
        assertTrue(order.contains("modelAlias") && order.contains("Line"), "the array-of-$ref became a repeatable mount → Line");
        assertFalse(order.contains("sku"), "Line is mounted, not inlined into Order");
        assertKernelAcceptsBundle(bundle);
    }

    private static void assertKernelAcceptsBundle(JsonSchemaImporter.BundleResult bundle) {
        var resolver = ReferenceResolvers.of(
                id -> bundle.models().containsKey(id) ? bundle.models().get(id).dmJson() : null);
        bundle.models().forEach((id, result) -> {
            try {
                ModelLoader.load(new StringReader(result.dmJson()), resolver);
            } catch (Exception e) {
                fail("bundle model '" + id + "' did not load against the bundle resolver: " + e.getMessage());
            }
        });
    }
}
