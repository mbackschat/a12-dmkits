package io.github.mbackschat.a12.dm.cli.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelReader;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/** Correctness and deterministic linear-work locks for rich tree-report metadata. */
class ModelTreeMetadataIndexTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void retainsGroupAndFieldMetadataWithoutPerFacetTreeSearches() throws Exception {
        String model = DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", group -> group
                        .internalDescription("en_US", "Internal policy")
                        .externalDescription("en_US", "Policy")
                        .annotation("owner", "underwriting")
                        .stringField("Reference", field -> field
                                .internalDescription("en_US", "Internal reference")
                                .externalDescription("en_US", "Reference")
                                .helperText("en_US", "Enter the reference.")
                                .annotation("classification", "business")))
                .build()
                .toJson();

        ModelTreeMetadataIndex index = index(model);

        assertEquals(2, index.indexedElementCount());
        assertEquals("Internal policy", index.metadata("/Policy").internal().get("en_US"));
        assertEquals("Policy", index.metadata("/Policy").external().get("en_US"));
        assertEquals("underwriting", index.metadata("/Policy").annotations().get("owner"));
        assertEquals("Internal reference", index.metadata("/Policy/Reference").internal().get("en_US"));
        assertEquals("Reference", index.metadata("/Policy/Reference").external().get("en_US"));
        assertEquals("Enter the reference.", index.metadata("/Policy/Reference").helper().get("en_US"));
        assertEquals("business", index.metadata("/Policy/Reference").annotations().get("classification"));
    }

    @Test
    void constructionVisitsExactlyOneElementPerGroupAndFieldAtEveryScale() throws Exception {
        assertLinearIndexWork(100);
        assertLinearIndexWork(400);
    }

    private static void assertLinearIndexWork(int fields) throws Exception {
        ModelTreeMetadataIndex index = index(wideModel(fields));

        assertEquals(fields + 1, index.traversalVisitCount(),
                "one root group plus every field must be indexed exactly once");
        for (int field = 0; field < fields; field++) {
            index.metadata("/Portfolio/F" + field);
        }
        assertEquals(fields + 1, index.traversalVisitCount(),
                "repeated metadata lookups are map reads and perform no further tree traversal");
    }

    private static String wideModel(int fields) {
        return DocumentModelDraft.named("portfolio-" + fields).locale("en_US")
                .rootGroup("Portfolio", group -> {
                    for (int field = 0; field < fields; field++) {
                        int ordinal = field;
                        group.stringField("F" + ordinal, value -> value
                                .internalDescription("en_US", "Field " + ordinal)
                                .annotation("ordinal", Integer.toString(ordinal)));
                    }
                })
                .build()
                .toJson();
    }

    private static ModelTreeMetadataIndex index(String modelJson) throws Exception {
        JsonNode expanded = MAPPER.readTree(ModelReader.expand(
                modelJson, ReferenceResolvers.selfContained()).toJson());
        return ModelTreeMetadataIndex.from(expanded);
    }
}
