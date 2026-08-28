package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedFieldValue;

/**
 * An index-selected field ({@code [Field For key]}) fills an entity-list slot, so
 * {@code NumberOfFilledFields(Items/Count For "SKU-1", …)} — a shape the kernel accepts, locked in
 * {@code validate.laws.SemanticIndexSlotLawsTest} — is authorable through the typed surface rather than only
 * as condition text.
 *
 * <p>{@link IndexedFieldValue} behaves like a single field entity at every one of those slots: it never
 * satisfies arity alone the way a starred {@code FieldListRef} does, which is why it joins {@code FieldOperand}
 * rather than growing {@code AggregateOperand} a third arm.
 *
 * <p>The reference half is the part a slot can silently lose. {@code ConditionReferences} reads a value
 * expression through an exhaustive switch but an OPERAND through an {@code instanceof} chain, so a widened
 * sealed type does not fail that build — an unlisted arm lands in the plain-field branch, recording the read as
 * PLAIN and dropping the key field entirely. Both properties are asserted here through a list slot, beside the
 * direct read that already had them.
 */
class IndexedFieldOperandTest {

    private static final BoundModel ORDER = Fixtures.order();

    private final FieldRef count = ORDER.field("/Order/Items/Count");
    private final FieldRef weight = ORDER.field("/Order/Items/UnitWeight");
    private final FieldRef sku = ORDER.field("/Order/Items/Sku");

    @Test
    void anIndexSelectedFieldFillsAnEntityListSlot() {
        assertEquals("NumberOfFilledFields(/Order/Items/Count For \"SKU-1\", /Order/Items/UnitWeight) > 0",
                new DslRenderer().render(
                        Dsl.numberOfFilledFields(count.semanticIndex("SKU-1").index(), weight).gt(0)));
        assertDoesNotThrow(() -> Dsl.sum(count.semanticIndex("SKU-1").index(), weight));
    }

    /** Reached through a list slot, the read is the same read — PROJECTED, exactly as read directly. */
    @Test
    void theReadStaysProjectedThroughAListSlot() {
        Calculation direct = count.semanticIndex("SKU-1").index();
        assertEquals(ConditionReferences.ReadMode.PROJECTED,
                ConditionReferences.readMode(direct, "/Order/Items/Count"),
                "the direct read is the control");

        Calculation viaList = Dsl.sum(count.semanticIndex("SKU-1").index(), weight).expression();
        assertEquals(ConditionReferences.ReadMode.PROJECTED,
                ConditionReferences.readMode(viaList, "/Order/Items/Count"),
                "an aggregate slot is not a different kind of read");
    }

    /** The field-keyed form's KEY is a reference in its own right, and a list slot must not drop it. */
    @Test
    void theKeyFieldStaysAReferenceThroughAListSlot() {
        Calculation direct = count.semanticIndex(sku).index();
        assertTrue(ConditionReferences.of(direct).fieldPaths().contains("/Order/Items/Sku"),
                () -> "the direct read is the control: " + ConditionReferences.of(direct).fieldPaths());

        Calculation viaList = Dsl.sum(count.semanticIndex(sku).index(), weight).expression();
        assertTrue(ConditionReferences.of(viaList).fieldPaths().contains("/Order/Items/Sku"),
                () -> "the key must survive the slot: " + ConditionReferences.of(viaList).fieldPaths());
    }
}
