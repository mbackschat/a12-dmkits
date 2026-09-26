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
 * Only {@code NumberOfFilledGroups(G*) < N} bounds G's rows. A star on an ancestor instead counts G across that
 * ancestor's rows (KERNEL-FINDINGS KF462), so {@code NumberOfFilledGroups(Items*}{@code /Packing) < 2} bounds no
 * array and must not project a {@code minItems}. Both shapes are one group scope in the count's operand list,
 * which is why the projection reads the scope's stars rather than its type.
 */
class MinItemsAncestorStarTest {

    @Test
    void aCountOfAGroupBelowAStarredLevelProjectsNoMinItems() {
        String structure = DocumentModelDraft.named("Order").locale("en_US")
                .rootGroup("Root", g -> g.group("Items", i -> {
                    i.repeatable(10);
                    i.field("Sku", FieldKind.STRING);
                    i.group("Packing", p -> p.field("Material", FieldKind.STRING));
                }))
                .build().toJson();
        BoundModel m = BoundModel.ofJson(structure);
        Rule rule = m.rule(m.group("/Root"))
                .name("PackedLines").errorField(m.str("/Root/Items/Packing/Material"))
                .when(Dsl.numberOfFilledGroups(m.flattenGroup("/Root/Items*/Packing")).lt(2))
                .code("FEW_PACKED").severity(Severity.ERROR).message(Dsl.en("Too few packed lines.")).build();
        String dm = m.add(rule).toJson();

        String schema = new JsonSchemaExporter().export(dm).jsonSchema();
        assertFalse(schema.contains("minItems"), () -> "no array is bounded here: " + schema);
    }
}
