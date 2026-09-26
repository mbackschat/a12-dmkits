package io.github.mbackschat.a12.dm.schemakit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * Only {@code NumberOfFilledGroups(G*) < N} bounds G's rows. A star on an ancestor as well counts G across every
 * row of that ancestor (KERNEL-FINDINGS KF462), so {@code NumberOfFilledGroups(Items*}{@code /Boxes*) < 2} bounds
 * the boxes of all items together and not the boxes of any one item, and must not project a {@code minItems} onto
 * {@code Boxes}. Both counts are one group scope over a repeatable group, so the projection reads the scope's stars.
 * The within-model control, the same model counting {@code Items*}, proves the rule reaches the projection at all.
 */
class MinItemsAncestorStarTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("Order").locale("en_US")
            .rootGroup("Root", g -> g.group("Items", i -> {
                i.repeatable(10);
                i.field("Sku", FieldKind.STRING);
                i.group("Boxes", b -> {
                    b.repeatable(5);
                    b.field("Label", FieldKind.STRING);
                });
            }))
            .build().toJson());

    @Test
    void aCountAcrossAStarredAncestorProjectsNoMinItems() throws Exception {
        String schema = export(Dsl.numberOfFilledGroups(M.flattenGroup("/Root/Items*/Boxes*")).lt(2),
                "/Root/Items/Boxes/Label");
        assertFalse(schema.contains("minItems"), () -> "no single array is bounded here: " + schema);
    }

    @Test
    void theSameModelCountingItsOwnStarProjectsMinItems() throws Exception {
        String schema = export(Dsl.numberOfFilledGroups(M.flattenGroup("/Root/Items*")).lt(2), "/Root/Items/Sku");
        JsonNode items = MAPPER.readTree(schema).path("properties").path("Items");
        assertEquals(2, items.path("minItems").asInt(), () -> "the control must project: " + schema);
    }

    private static String export(Condition condition, String errorField) {
        Rule rule = M.rule(M.group("/Root"))
                .name("Counted").errorField(M.str(errorField)).when(condition)
                .code("COUNTED").severity(Severity.ERROR).message(Dsl.en("Too few rows.")).build();
        return new JsonSchemaExporter().export(M.add(rule).toJson()).jsonSchema();
    }
}
