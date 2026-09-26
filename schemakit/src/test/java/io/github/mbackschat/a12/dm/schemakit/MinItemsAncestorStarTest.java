package io.github.mbackschat.a12.dm.schemakit;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * Only {@code NumberOfFilledGroups(G*) < N} bounds G's rows. A star on an ancestor as well counts G across every
 * row of that ancestor (KERNEL-FINDINGS KF462), so {@code NumberOfFilledGroups(Items*}{@code /Boxes*) < 2} bounds
 * the boxes of all items together and not the boxes of any one item, and must not project a {@code minItems} onto
 * {@code Boxes}. Both counts are one group scope over a repeatable group, so the projection reads the scope's stars.
 */
class MinItemsAncestorStarTest {

    @Test
    void aCountAcrossAStarredAncestorProjectsNoMinItems() {
        String structure = DocumentModelDraft.named("Order").locale("en_US")
                .rootGroup("Root", g -> g.group("Items", i -> {
                    i.repeatable(10);
                    i.field("Sku", FieldKind.STRING);
                    i.group("Boxes", b -> {
                        b.repeatable(5);
                        b.field("Label", FieldKind.STRING);
                    });
                }))
                .build().toJson();
        BoundModel m = BoundModel.ofJson(structure);
        Rule rule = m.rule(m.group("/Root"))
                .name("BoxedLines").errorField(m.str("/Root/Items/Boxes/Label"))
                .when(Dsl.numberOfFilledGroups(m.flattenGroup("/Root/Items*/Boxes*")).lt(2))
                .code("FEW_BOXES").severity(Severity.ERROR).message(Dsl.en("Too few boxes.")).build();
        String dm = m.add(rule).toJson();

        String schema = new JsonSchemaExporter().export(dm).jsonSchema();
        assertFalse(schema.contains("minItems"), () -> "no single array is bounded here: " + schema);
    }
}
