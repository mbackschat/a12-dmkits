package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;

/** Date family (corpus item 9, slice 9a): Today, date ordering, DifferenceIn*, Add* — KERNEL-SEMANTICS §6. */
class DateConditionTest {

    private static final BoundModel m = Fixtures.order();
    private static final String ORDER = "/Order/OrderDate";
    private static final String DELIVERY = "/Order/DeliveryDate";

    @Test
    void rendersDateVsTodayBilingually() {
        Condition c = m.date(ORDER).onOrAfter(Dsl.today());
        assertEquals("[/Order/OrderDate] >= Today", new DslRenderer().render(c));
        assertEquals("[/Order/OrderDate] >= Heute", new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void rendersDateFieldVsFieldOrdering() {
        // dates have no scale, so field-vs-field ordering needs no D13 machinery (verified 2026-06-04)
        assertEquals("[/Order/DeliveryDate] < [/Order/OrderDate]",
                new DslRenderer().render(m.date(DELIVERY).before(m.date(ORDER))));
        assertEquals("[/Order/OrderDate] == [/Order/DeliveryDate]",
                new DslRenderer().render(m.date(ORDER).eq(m.date(DELIVERY))));
    }

    @Test
    void rendersDifferenceFunctionsBilingually() {
        // operands render BARE inside the function (entity refs), not bracketed values
        Condition c = Dsl.differenceInDays(m.date(ORDER), m.date(DELIVERY)).gt(7);
        assertEquals("DifferenceInDays(/Order/OrderDate, /Order/DeliveryDate) > 7", new DslRenderer().render(c));
        assertEquals("DifferenzInTagen(/Order/OrderDate, /Order/DeliveryDate) > 7",
                new DslRenderer(ConditionLanguage.DE).render(c));
        assertEquals("DifferenceInYears(/Order/OrderDate, /Order/DeliveryDate) >= 1",
                new DslRenderer().render(Dsl.differenceInYears(m.date(ORDER), m.date(DELIVERY)).ge(1)));
        // Today is a valid operand
        assertEquals("DifferenceInDays(/Order/OrderDate, Today) > 30",
                new DslRenderer().render(Dsl.differenceInDays(m.date(ORDER), Dsl.today()).gt(30)));
    }

    @Test
    void rendersAddFunctionsAndComposeIntoComparisons() {
        // AddMonths(date, n) is itself a date — comparable; the date operand renders bare
        Condition c = m.date(DELIVERY).after(Dsl.addMonths(m.date(ORDER), 3));
        assertEquals("[/Order/DeliveryDate] > AddMonths(/Order/OrderDate, 3)", new DslRenderer().render(c));
        assertEquals("[/Order/DeliveryDate] > AddiereJahre(/Order/OrderDate, 1)",
                new DslRenderer(ConditionLanguage.DE).render(m.date(DELIVERY).after(Dsl.addYears(m.date(ORDER), 1))));
    }

    @Test
    void typedDateHandleRejectsANonDateField() {
        assertThrows(IllegalArgumentException.class, () -> m.date("/Order/Quantity"));
        assertThrows(IllegalArgumentException.class, () -> m.date("/Order/CustomerName"));
    }
}
