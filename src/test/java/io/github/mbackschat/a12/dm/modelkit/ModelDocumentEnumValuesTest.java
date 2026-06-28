package io.github.mbackschat.a12.dm.modelkit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ModelDocument#enumValues} — the typed read-back peer of the enum builder, so a consumer reads an
 * enumeration's values without navigating the raw {@code fieldConfig} {@code enum.values[].value} block
 * (the dogfood gap surfaced by the JSON-Schema exporter, 2026-06-28).
 */
class ModelDocumentEnumValuesTest {

    @Test
    void enumValuesReadsTheValuesInOrder() {
        String json = DocumentModelDraft.named("Order")
                .locale("en_US")
                .rootGroup("Order", g -> g.enumField("Status", e -> e.value("DRAFT").value("APPROVED").value("SHIPPED")))
                .build()
                .toJson();

        assertEquals(List.of("DRAFT", "APPROVED", "SHIPPED"),
                ModelDocument.read(json).enumValues("/Order/Status"));
    }

    @Test
    void enumValuesIsEmptyForANonEnumField() {
        String json = DocumentModelDraft.named("Order")
                .locale("en_US")
                .rootGroup("Order", g -> g.field("Name", FieldKind.STRING))
                .build()
                .toJson();

        assertEquals(List.of(), ModelDocument.read(json).enumValues("/Order/Name"));
    }
}
