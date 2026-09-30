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
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand;
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
    void numericMembershipUsesTheSelectedCellWithEitherKeyForm() {
        var renderer = new DslRenderer();
        assertEquals("FieldValueIncludedInValueList(/Order/Items/Count For \"SKU-1\", 0, 5)",
                renderer.render(count.semanticIndex("SKU-1").index().in(0, 5)));
        assertEquals("FieldValueNotIncludedInValueList(/Order/Items/Count For /Order/Items/Sku, 0, 5)",
                renderer.render(count.semanticIndex(sku).index().notIn(0, 5)));
    }

    @Test
    void anIndexSelectedFieldFillsAnEntityListSlot() {
        assertEquals("NumberOfFilledFields(/Order/Items/Count For \"SKU-1\", /Order/Items/UnitWeight) > 0",
                new DslRenderer().render(
                        Dsl.numberOfFilledFields(count.semanticIndex("SKU-1").index(), weight).gt(0)));
        assertDoesNotThrow(() -> Dsl.sum(count.semanticIndex("SKU-1").index(), weight));
    }

    @Test
    void anIndexSelectedFieldFillsPresenceSlotsReachedThroughFieldOperand() {
        FieldOperand indexed = count.semanticIndex("SKU-1").index();

        assertEquals("FieldFilled(/Order/Items/Count For \"SKU-1\")",
                new DslRenderer().render(Dsl.fieldFilled(indexed)));
        assertEquals("FieldNotFilled(/Order/Items/Count For \"SKU-1\")",
                new DslRenderer().render(Dsl.fieldNotFilled(indexed)));
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
    @Test
    void aKeyedFieldListKeepsItsModifiersAndFirstFilledNormalizesTheKey() {
        var model = BoundModel.ofJson(io.github.mbackschat.a12.dm.ModelDrafts.shipping().toJson());
        var plain = model.flatten("/Order/Packages/Parcels*/Label");
        var key = model.field("/Order/Title");
        var indexed = plain.forIndex(key);
        assertEquals("NumberOfFilledFields(/Order/Packages/Parcels*/Label For /Order/Title) > 0",
                new DslRenderer().render(Dsl.numberOfFilledFields(indexed).gt(0)));
        assertEquals(ConditionReferences.ReadMode.PROJECTED,
                ConditionReferences.readMode(Dsl.numberOfFilledFields(indexed).expression(), plain.fieldPath()));
        assertEquals(ConditionReferences.ReadMode.PROJECTED,
                ConditionReferences.readMode(Dsl.numberOfFilledFields(indexed).expression(), key.fieldPath()));
        var decorated = indexed.outer().absolute().having(plain.field().filled());
        assertEquals(indexed.semanticIndex(), decorated.semanticIndex());
        assertTrue(decorated.correlated());
        assertEquals(io.github.mbackschat.a12.dm.rulekit.dsl.PathStyle.ABSOLUTE, decorated.styleHint());
        assertTrue(new DslRenderer(io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage.EN, "/Order")
                .render(Dsl.numberOfFilledFields(decorated).gt(0))
                .contains("$/Order/Packages/Parcels*/Label For Title Having "),
                "outer correlation, absolute style, key and filter must render together");
        assertTrue(io.github.mbackschat.a12.dm.rulekit.dsl.OperatorUsage.of(
                Dsl.numberOfFilledFields(decorated).gt(0)).containsAll(java.util.Set.of("SemanticIndex", "Having")));
        assertEquals("For:/Order/Title", indexed.overlapModifier());
        assertEquals("For:\"P1\"", plain.forIndex("P1").overlapModifier());
        assertEquals("", plain.overlapModifier());
        var first = indexed.firstFilledOperand();
        assertEquals(indexed.semanticIndex(), first.semanticIndex());
        assertTrue(((io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef) first.field().orElseThrow())
                .semanticIndex().isEmpty());
        assertEquals("NumberOfFilledFields(/Order/Packages/Parcels*/Label For \"P1\") > 0",
                new DslRenderer().render(Dsl.numberOfFilledFields(plain.forIndex("P1")).gt(0)));
        assertTrue(plain.semanticIndex().isEmpty());
    }

}
