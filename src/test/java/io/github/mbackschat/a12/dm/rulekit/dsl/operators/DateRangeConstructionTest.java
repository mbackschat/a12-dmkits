package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The {@code DateRange(start, end)} constructor (the lone former catalog PLANNED stub) — a date-range
 * value built from two dates. Computation-only (no condition form): rendered + reference-collected here;
 * its computation round-trip is {@code adapter.DateRangeComputationIngestTest}.
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
}
