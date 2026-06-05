package io.github.mbackschat.a12.rulekit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * F-IDX regression: the index must resolve elements by their <b>name-path</b> (the path a rule
 * condition references, the join of {@code getName()} segments), not by {@code getId()}. rulekit's
 * own fixtures set id == path so the two coincided and the defect was invisible; real models (the
 * kernel-mm metamodels, the A12 Tools apps) use opaque ids while conditions reference name-paths, so
 * the id-keyed index resolved ~0% of foreign references. {@code idmismatch.dm.json} reproduces the
 * divergence (opaque hash ids, readable names).
 */
class ModelIndexNamePathTest {

    private static ModelIndex index(String resource) {
        try (Reader r = new InputStreamReader(open(resource), StandardCharsets.UTF_8)) {
            return ModelIndex.of(ModelLoader.loadSelfContained(r));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static InputStream open(String resource) {
        return ModelIndexNamePathTest.class.getClassLoader().getResourceAsStream(resource);
    }

    @Test
    void resolvesFieldsByNamePathWhenIdsDiffer() {
        ModelIndex index = index("models/idmismatch.dm.json");
        // The condition-facing path is the name-path, even though the ids are opaque hashes.
        assertEquals(ValueKind.STRING, index.kindOf("/Catalog/Sku"));
        assertEquals(ValueKind.NUMBER, index.kindOf("/Catalog/ListPrice"));
        assertTrue(index.isField("/Catalog/Sku"));
    }

    @Test
    void enumeratesNamePathsNotIds() {
        ModelIndex index = index("models/idmismatch.dm.json");
        java.util.List<String> fields = index.fieldPaths().toList();
        assertTrue(fields.contains("/Catalog/Sku"), () -> "field paths should be name-paths, got: " + fields);
        assertTrue(fields.contains("/Catalog/ListPrice"), () -> "field paths should be name-paths, got: " + fields);
        assertEquals(java.util.List.of("/Catalog"), index.groupPaths().toList());
        // and the per-group children are name-paths too
        assertEquals(java.util.List.of("/Catalog/Sku", "/Catalog/ListPrice"),
                index.fieldPaths("/Catalog").toList());
    }

    @Test
    void stillWorksWhenIdEqualsPath() {
        // the existing fixtures (id == path) must keep resolving identically
        ModelIndex index = index("models/catalog.dm.json");
        assertEquals(ValueKind.STRING, index.kindOf("/Catalog/Sku"));
        assertEquals(ValueKind.NUMBER, index.kindOf("/Catalog/ListPrice"));
    }
}
