package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;

/**
 * modelkit — enum <b>categories</b>: a named classification that maps each stored value to a category
 * value (positionally — {@code categories[c].values[i]} pairs with enum {@code values[i]}), so rules
 * can compare a field's category instead of its raw value. A kernel slot used by the order/subscription
 * fixtures. Kernel is the oracle: the categories survive load + expansion and index by distinct set.
 * Own domain (a support ticket's severity, classified by escalation).
 */
class EnumCategoryTest {

    private static DocumentModelDraft tickets() {
        return DocumentModelDraft.named("tickets").locale("en_US")
                .rootGroup("Ticket", t -> t
                        .enumField("Severity", e -> e
                                .value("LOW").value("MEDIUM").value("HIGH")
                                .category("Escalation", "ROUTINE", "ROUTINE", "URGENT")))
                .build();
    }

    private static ModelIndex index(DocumentModelDraft draft) {
        try {
            return ModelIndex.ofSelfContained(draft.toJson());
        } catch (Exception e) {
            throw new RuntimeException("emitted DM-JSON failed checkConsistency:\n" + draft.toJson(), e);
        }
    }

    @Test
    void categoriesAreKernelValidAndIndexByDistinctSet() {
        ModelIndex index = index(tickets());
        assertEquals(Map.of("Escalation", List.of("ROUTINE", "URGENT")),
                index.enumCategories("/Ticket/Severity"));
    }

    @Test
    void emitsTheCategoryBlock() {
        String json = tickets().toJson();
        assertTrue(json.contains("\"categories\""), json);
        assertTrue(json.contains("\"name\" : \"Escalation\""), json);
        assertTrue(json.contains("\"URGENT\""), json);
    }

    @Test
    void aCategoryMustClassifyEveryValue() {
        // the category's values are positional to the enum's values — lengths must match
        assertThrows(IllegalArgumentException.class, () -> DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.enumField("F", e -> e
                        .value("A").value("B").value("C")
                        .category("Half", "x", "y")))
                .build());
    }
}
