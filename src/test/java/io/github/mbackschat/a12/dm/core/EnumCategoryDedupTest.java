package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/** Locks enum-category deduplication to encounter order and one hash lookup per authored value. */
class EnumCategoryDedupTest {

    private static final int UNIQUE_VALUES = 512;

    @Test
    void genericDedupPreservesFirstEncountersWithLinearEqualityWork() {
        var equalityChecks = new AtomicInteger();
        List<EqualityCountingValue> values = new ArrayList<>(UNIQUE_VALUES * 2);
        List<Integer> expectedOrder = IntStream.range(0, UNIQUE_VALUES)
                .map(i -> (i * 37) % UNIQUE_VALUES)
                .boxed()
                .toList();
        expectedOrder.forEach(id -> values.add(new EqualityCountingValue(id, equalityChecks)));
        expectedOrder.reversed().forEach(id -> values.add(new EqualityCountingValue(id, equalityChecks)));

        List<EqualityCountingValue> distinct = ModelIndex.distinctInEncounterOrder(values, value -> value);
        int comparisons = equalityChecks.get();

        assertEquals(expectedOrder, distinct.stream().map(EqualityCountingValue::id).toList());
        assertTrue(comparisons <= values.size(),
                () -> "dedup used " + comparisons + " equality checks for " + values.size()
                        + " values; a contains-scan is quadratic");
    }

    @Test
    void modelIndexAndBoundModelExposeTheSameUniqueCategoryOrder() {
        ModelIndex index = ModelIndex.ofSelfContained(triageJson());
        List<String> expected = List.of("ROUTINE", "URGENT");

        assertEquals(expected, index.enumCategories("/Ticket/Severity").get("Escalation"));
        assertEquals(expected, BoundModel.of(index).enumOf("/Ticket/Severity")
                .category("Escalation").values());
    }

    @Test
    void describeReadsTheEnumDomainWithoutMaterializingCategories() {
        var categoryMaterializations = new AtomicInteger();
        var reader = ModelReader.expand(triageJson(), ReferenceResolvers.selfContained());
        var index = new ModelIndex(reader, ignored -> categoryMaterializations.incrementAndGet());

        var card = new ModelContext(BoundModel.of(index)).describe();

        var severity = card.fields().stream()
                .filter(field -> field.path().equals("/Ticket/Severity"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("LOW", "MEDIUM", "HIGH", "CRITICAL"), severity.enumValues());
        assertEquals(0, categoryMaterializations.get(),
                "describe needs the enum domain only; it must not build or scan category projections");
        assertThrows(IllegalArgumentException.class,
                () -> BoundModel.of(index).fieldAt("/Ticket/Subject").orElseThrow().allowedValues(),
                "the values-only accessor remains strict on non-enum fields");
    }

    private static String triageJson() {
        return DocumentModelDraft.named("triage").locale("en_US")
                .rootGroup("Ticket", ticket -> ticket
                        .stringField("Subject", field -> {})
                        .enumField("Severity", severity -> severity
                                .value("LOW").value("MEDIUM").value("HIGH").value("CRITICAL")
                                .category("Escalation", "ROUTINE", "ROUTINE", "URGENT", "ROUTINE")))
                .build()
                .toJson();
    }

    private static final class EqualityCountingValue {
        private final int id;
        private final AtomicInteger equalityChecks;

        private EqualityCountingValue(int id, AtomicInteger equalityChecks) {
            this.id = id;
            this.equalityChecks = equalityChecks;
        }

        private int id() {
            return id;
        }

        @Override
        public boolean equals(Object candidate) {
            equalityChecks.incrementAndGet();
            return candidate instanceof EqualityCountingValue other && id == other.id;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(id);
        }
    }
}
