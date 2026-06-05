package io.github.mbackschat.a12.dm.modelkit;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * Dogfood — modelkit authors the test fixtures instead of hand-written DM-JSON. The payoff check: a
 * model built with {@link ModelDrafts} loads + validates through the real kernel exactly like the
 * committed {@code .dm.json}, and indexes to the same structure. {@code order} keeps its committed
 * fixture (30+ consumers) and is proven <em>equivalent</em>; {@code logistics} dropped its hand-written
 * JSON entirely and is now defined only in {@code ModelDrafts} (this test + {@code IterationLawsTest}
 * are the proof it still works).
 */
class DogfoodTest {

    private static ModelIndex index(String json) {
        try {
            return ModelIndex.of(ModelLoader.loadSelfContained(new StringReader(json)));
        } catch (Exception e) {
            throw new RuntimeException("DM-JSON failed checkConsistency:\n" + json, e);
        }
    }

    @Test
    void modelkitOrderIsStructurallyEquivalentToTheCommittedFixture() {
        ModelIndex committed = index(Fixtures.orderJson());
        ModelIndex authored = index(ModelDrafts.order().toJson());

        // same skeleton, in the same model order
        assertEquals(committed.groupPaths().collect(toList()), authored.groupPaths().collect(toList()));
        assertEquals(committed.fieldPaths().collect(toList()), authored.fieldPaths().collect(toList()));

        // every field: same kind, and same kind-specific config (scale / enum domain + categories)
        committed.fieldPaths().forEach(p -> {
            ValueKind kind = committed.kindOf(p);
            assertEquals(kind, authored.kindOf(p), p);
            switch (kind) {
                case NUMBER -> assertEquals(committed.numberScale(p), authored.numberScale(p), p);
                case ENUMERATION -> {
                    assertEquals(committed.enumValues(p), authored.enumValues(p), p);
                    assertEquals(committed.enumCategories(p), authored.enumCategories(p), p);
                }
                default -> { /* no extra config to compare */ }
            }
        });

        // every group repeats (or not) the same way
        committed.groupPaths().forEach(p ->
                assertEquals(committed.isRepeating(p), authored.isRepeating(p), p));
    }

    @Test
    void modelkitLogisticsIsKernelValidWithTwoIndexKeyedLists() {
        ModelIndex i = index(ModelDrafts.logistics().toJson());
        assertEquals(Set.of("/Shipment", "/Shipment/Lines", "/Shipment/Picks"),
                i.groupPaths().collect(toSet()));
        assertTrue(i.isRepeating("/Shipment/Lines"), "Lines should repeat");
        assertTrue(i.isRepeating("/Shipment/Picks"), "Picks should repeat");
        assertEquals(List.of("/Shipment/ShipmentRef", "/Shipment/Lines/LineRef", "/Shipment/Lines/OrderedQty",
                "/Shipment/Picks/LineRef", "/Shipment/Picks/PickedQty"), i.fieldPaths().collect(toList()));
    }
}
