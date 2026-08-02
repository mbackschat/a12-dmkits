package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeConstruction;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValue;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * The {@code DateRange(start, end)} constructor — a date-range value built from two dates. Its condition
 * and computation renderings are exercised here; adapter read round-trips cover both use sites.
 */
class DateRangeConstructionTest {

    private static final BoundModel m = Fixtures.order();

    @Test
    void rendersTheConstructorBilingually() {
        Calculation range = Dsl.dateRange(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate"));
        assertEquals("DateRange(/Order/OrderDate, /Order/DeliveryDate)", new DslRenderer().render(range));
        assertEquals("DatumBereich(/Order/OrderDate, /Order/DeliveryDate)",
                new DslRenderer(ConditionLanguage.DE).render(range));
        assertEquals(ValueKind.DATE_RANGE, range.kind());
    }

    @Test
    void collectsBothDateOperandsAsReferences() {
        Calculation range = Dsl.dateRange(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate"));
        var refs = ConditionReferences.of(range).fieldPaths();
        assertTrue(refs.contains("/Order/OrderDate") && refs.contains("/Order/DeliveryDate"),
                () -> "both date operands are referenced: " + refs);
    }

    @Test
    void authorsTheKernelLegalEqualityAndInequalityUseSites() {
        var constructed = Dsl.dateRange(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate"));
        var field = m.dateRange("/Order/CoverageWindow");

        assertEquals("DateRange(/Order/OrderDate, /Order/DeliveryDate) == [/Order/CoverageWindow]",
                new DslRenderer().render(constructed.eq(field)));
        assertEquals("[/Order/CoverageWindow] != DateRange(/Order/OrderDate, /Order/DeliveryDate)",
                new DslRenderer().render(field.ne(constructed)));
    }

    @Test
    void constructorSurfaceAdmitsOnlyOwnerEntityReferences() throws Exception {
        assertArrayEquals(new Class<?>[] {DateOperand.class, DateOperand.class},
                Dsl.class.getMethod("dateRange", DateOperand.class, DateOperand.class).getParameterTypes());
        assertArrayEquals(new Class<?>[] {Calculation.class, Calculation.class},
                DateRangeConstruction.class.getConstructors()[0].getParameterTypes());
        assertTrue(DateRangeConstruction.class.isRecord());
        assertThrows(IllegalArgumentException.class, () -> new DateRangeConstruction(
                new FieldValue(new FieldRef("/Order/Quantity", ValueKind.NUMBER)),
                m.date("/Order/DeliveryDate").expression()));
        assertThrows(IllegalArgumentException.class, () -> new DateRangeConstruction(
                Dsl.today().expression(), m.date("/Order/DeliveryDate").expression()));
        assertTrue(java.util.Arrays.stream(DateRangeExpr.class.getConstructors()).findAny().isEmpty(),
                "a typed computed date-range cannot wrap an arbitrary Calculation");
    }

    @Test
    void admitsMatchingOwnerDateFragments() {
        BoundModel fragments = BoundModel.ofJson(DocumentModelDraft.named("fragment-range").locale("en_US")
                .rootGroup("Period", period -> period
                        .dateFragmentField("FromMonth", field -> field.fragmentFormat("MM"))
                        .dateFragmentField("ToMonth", field -> field.fragmentFormat("MM"))
                        .dateRangeField("Months", field -> field.format("MM").separator("/")))
                .build().toJson());

        var range = Dsl.dateRange(fragments.dateFragment("/Period/FromMonth"),
                fragments.dateFragment("/Period/ToMonth"));

        assertEquals("DateRange(/Period/FromMonth, /Period/ToMonth)", new DslRenderer().render(range));
    }

    @Test
    void admitsOwnerEntityReferencesReachedThroughSemanticIndexes() {
        BoundModel indexed = BoundModel.ofJson(DocumentModelDraft.named("indexed-range").locale("en_US")
                .rootGroup("Order", order -> order
                        .stringField("SelectedSku", field -> { })
                        .dateRangeField("Coverage", field -> field.format("yyyy-MM-dd").separator("/"))
                        .group("Items", items -> items.repeatable(20).indexField("Sku")
                                .stringField("Sku", field -> { })
                                .dateField("PromiseDate", field -> field.format("yyyy-MM-dd"))))
                .build().toJson());

        var range = Dsl.dateRange(indexed.date("/Order/Items/PromiseDate").forIndex("sku1"),
                indexed.date("/Order/Items/PromiseDate").forIndex(indexed.field("/Order/SelectedSku")));

        assertEquals("DateRange(/Order/Items/PromiseDate For \"sku1\", "
                + "/Order/Items/PromiseDate For /Order/SelectedSku)", new DslRenderer().render(range));
        assertEquals("DifferenceInDays(/Order/Items/PromiseDate For \"sku1\", "
                + "/Order/Items/PromiseDate For \"sku2\")",
                new DslRenderer().render(Dsl.differenceInDays(
                        indexed.date("/Order/Items/PromiseDate").forIndex("sku1"),
                        indexed.date("/Order/Items/PromiseDate").forIndex("sku2")).expression()));
    }

    @Test
    void admitsMixedDateAndFragmentEndpointsWithMatchingComponents() {
        BoundModel mixed = BoundModel.ofJson(DocumentModelDraft.named("mixed-range").locale("en_US")
                .rootGroup("Period", period -> period
                        .dateField("DateMonth", field -> field.format("MM"))
                        .dateFragmentField("FragmentMonth", field -> field.fragmentFormat("MM"))
                        .dateRangeField("Months", field -> field.format("MM").separator("/")))
                .build().toJson());

        assertEquals("DateRange(/Period/DateMonth, /Period/FragmentMonth)", new DslRenderer().render(
                Dsl.dateRange(mixed.date("/Period/DateMonth"), mixed.dateFragment("/Period/FragmentMonth"))));
    }

    @Test
    void rejectsEveryNonOwnerOperandThroughThePublicFactory() {
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateRange(Dsl.today(), m.date("/Order/DeliveryDate")));
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateRange(new DateRef(new FieldRef("/Order/Quantity", ValueKind.NUMBER)),
                        m.date("/Order/DeliveryDate")));
    }
}
