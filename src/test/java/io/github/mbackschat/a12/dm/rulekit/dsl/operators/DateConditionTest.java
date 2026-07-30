package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalTime;

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
    void rendersDateAndTimeConstantLiterals() {
        // family 1: a date/time constant renders as a quoted literal (German dd.MM.yyyy / HH:mm:ss),
        // the same in EN and DE (the quote syntax is intrinsic, not a catalog keyword).
        Condition date = m.date(ORDER).after(Dsl.dateValue("01.01.2022"));
        assertEquals("[/Order/OrderDate] > \"01.01.2022\"", new DslRenderer().render(date));
        assertEquals("[/Order/OrderDate] > \"01.01.2022\"", new DslRenderer(ConditionLanguage.DE).render(date));
        Condition time = m.time("/Order/PickupTime").after(Dsl.timeValue("17:00:00"));
        assertEquals("[/Order/PickupTime] > \"17:00:00\"", new DslRenderer().render(time));
        // a date constant also renders bare-quoted in argument position (a DifferenceInDays operand)
        Condition diff = Dsl.differenceInDays(m.date(ORDER), Dsl.dateValue("01.01.2022")).gt(7);
        assertEquals("DifferenceInDays(/Order/OrderDate, \"01.01.2022\") > 7", new DslRenderer().render(diff));
    }

    @Test
    void javaTimeLiteralsRenderLikeTheirStringForms() {
        // DG4 — a typed java.time literal can't be a malformed date/time; it renders to the kernel's
        // German dd.MM.yyyy / HH:mm:ss form, identical to the (kernel-valid) String overload.
        Condition byDate = m.date(ORDER).after(Dsl.dateValue(LocalDate.of(2022, 1, 1)));
        assertEquals("[/Order/OrderDate] > \"01.01.2022\"", new DslRenderer().render(byDate));
        Condition byTime = m.time("/Order/PickupTime").after(Dsl.timeValue(LocalTime.of(17, 0, 0)));
        assertEquals("[/Order/PickupTime] > \"17:00:00\"", new DslRenderer().render(byTime));
    }

    @Test
    void rendersTimeAndDateTimeConstruction() {
        // family 3: Time accepts a 0..3 component prefix; omitted trailing components default to zero.
        assertEquals("Time()", new DslRenderer().render(Dsl.time().expression()));
        assertEquals("Time(\"10\")", new DslRenderer().render(Dsl.time("10").expression()));
        assertEquals("Time(\"10\", \"30\")", new DslRenderer().render(Dsl.time("10", "30").expression()));
        Condition time = m.time("/Order/PickupTime").eq(Dsl.time("10", "30", "50"));
        assertEquals("[/Order/PickupTime] == Time(\"10\", \"30\", \"50\")", new DslRenderer().render(time));
        assertEquals("[/Order/PickupTime] == Zeit(\"10\", \"30\", \"50\")", new DslRenderer(ConditionLanguage.DE).render(time));
        assertEquals("Zeit()", new DslRenderer(ConditionLanguage.DE).render(Dsl.time().expression()));
        Condition dt = m.dateTime("/Order/ScheduledAt").eq(Dsl.dateTime(m.date(ORDER), m.time("/Order/PickupTime")));
        assertEquals("[/Order/ScheduledAt] == DateTime(/Order/OrderDate, /Order/PickupTime)",
                new DslRenderer().render(dt));
    }

    @Test
    void rendersMixedTemporalComponentsWithoutDroppingBareFieldSyntax() {
        DateExpr date = Dsl.date(
                m.field("/Order/BirthDay"),
                Dsl.temporalComponent("01"),
                Dsl.yearFromDate(m.date(ORDER)));
        assertEquals("Date(/Order/BirthDay, \"01\", YearFromDate(/Order/OrderDate))",
                new DslRenderer().render(date.expression()));

        TimeExpr time = Dsl.time(
                m.field("/Order/BirthMonth"),
                Dsl.temporalComponent("30"),
                Dsl.secondsFromTime(m.time("/Order/PickupTime")));
        assertEquals("Time(/Order/BirthMonth, \"30\", SecondsFromTime(/Order/PickupTime))",
                new DslRenderer().render(time.expression()));
    }

    @Test
    void rendersSubDayTimeArithmeticBilingually() {
        // family 2: DifferenceInHours/Minutes/Seconds and AddHours/Minutes/Seconds over date-times.
        Condition diff = Dsl.differenceInHours(m.dateTime("/Order/ScheduledAt"), m.dateTime("/Order/CompletedAt")).gt(24);
        assertEquals("DifferenceInHours(/Order/ScheduledAt, /Order/CompletedAt) > 24", new DslRenderer().render(diff));
        assertEquals("DifferenzInStunden(/Order/ScheduledAt, /Order/CompletedAt) > 24",
                new DslRenderer(ConditionLanguage.DE).render(diff));
        Condition add = Dsl.minutesFromTime(Dsl.addMinutes(m.dateTime("/Order/ScheduledAt"), 30)).lt(60);
        assertEquals("MinutesFromTime(AddMinutes(/Order/ScheduledAt, 30)) < 60", new DslRenderer().render(add));
        assertEquals("MinutenAusZeit(AddiereMinuten(/Order/ScheduledAt, 30)) < 60",
                new DslRenderer(ConditionLanguage.DE).render(add));
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
