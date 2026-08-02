package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 read tail — date expressions beyond fields/Today: {@code AddDays/AddMonths/AddYears} as date
 * operands (already authorable, now read), the {@code YearFromDate}/{@code MonthFromDate}/… numeric
 * extractions, and {@code Date(day, month, year)} construction. Shift + extraction ride the order
 * fixture's date fields; construction uses a small event model with day/month/year number fields.
 */
class DateTimeExprReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static BoundModel order() {
        return BoundModel.ofJson(read("models/order.dm.json"));
    }

    @Test
    void roundTripsDateShiftAndExtraction() {
        BoundModel m = order();
        Condition shift = m.date("/Order/DeliveryDate").after(Dsl.addDays(m.date("/Order/OrderDate"), 7));
        Condition extract = Dsl.yearFromDate(m.date("/Order/OrderDate")).ne(Dsl.yearFromDate(Dsl.today()));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(orderRule(m, "ShiftRule", "/Order/DeliveryDate", shift))
                .add(orderRule(m, "ExtractRule", "/Order/OrderDate", extract)));
        assertEquals(RENDER.render(shift), RENDER.render(converted(adapter.read("/Order/ShiftRule"))));
        assertEquals(RENDER.render(extract), RENDER.render(converted(adapter.read("/Order/ExtractRule"))));
    }

    @Test
    void roundTripsADateShiftOnTheLeftOfAComparison() {
        // the corpus's dominant comparison-tail form: AddDays(date, n) <op> [field] — the shift is the
        // left operand, not the right. EQ is the most common; LE also occurs (vs Today, below).
        BoundModel m = order();
        Condition eq = Dsl.addDays(m.date("/Order/OrderDate"), 7).eq(m.date("/Order/DeliveryDate"));
        Condition leToday = Dsl.addDays(m.date("/Order/OrderDate"), 30).onOrBefore(Dsl.today());

        KernelAdapter adapter = KernelAdapter.of(m
                .add(orderRule(m, "ShiftEqRule", "/Order/DeliveryDate", eq))
                .add(orderRule(m, "ShiftLeRule", "/Order/OrderDate", leToday)));
        assertEquals(RENDER.render(eq), RENDER.render(converted(adapter.read("/Order/ShiftEqRule"))));
        assertEquals(RENDER.render(leToday), RENDER.render(converted(adapter.read("/Order/ShiftLeRule"))));
    }

    @Test
    void roundTripsADateShiftByAFieldAmount() {
        // the corpus shape: the shift amount is a field/expression, not a constant —
        // [DeliveryDate] > AddDays(OrderDate, [BackorderQuantity]).
        BoundModel m = order();
        Condition c = m.date("/Order/DeliveryDate")
                .after(Dsl.addDays(m.date("/Order/OrderDate"), m.num("/Order/BackorderQuantity").asNumber()));
        KernelAdapter adapter = KernelAdapter.of(m.add(orderRule(m, "FieldShiftRule", "/Order/DeliveryDate", c)));
        assertTrue(RENDER.render(c).contains("AddDays(/Order/OrderDate, [/Order/BackorderQuantity])"), RENDER.render(c));
        assertEquals(RENDER.render(c), RENDER.render(converted(adapter.read("/Order/FieldShiftRule"))));
    }

    @Test
    void roundTripsDateAndTimeConstantLiterals() {
        // family 1: [date] > "01.01.2022", [time] > "17:00:00", and a date constant as a diff operand.
        // The kernel stores the constant German-format (type DATE) regardless of the field's display format.
        BoundModel m = order();
        Condition dateCmp = m.date("/Order/OrderDate").after(Dsl.dateValue("01.01.2022"));
        Condition timeCmp = m.time("/Order/PickupTime").after(Dsl.timeValue("17:00:00"));
        Condition diff = Dsl.differenceInDays(m.date("/Order/OrderDate"), Dsl.dateValue("01.01.2022")).gt(7);

        KernelAdapter adapter = KernelAdapter.of(m
                .add(orderRule(m, "DateLitRule", "/Order/OrderDate", dateCmp))
                .add(orderRule(m, "TimeLitRule", "/Order/PickupTime", timeCmp))
                .add(orderRule(m, "DiffLitRule", "/Order/OrderDate", diff)));
        assertEquals(RENDER.render(dateCmp), RENDER.render(converted(adapter.read("/Order/DateLitRule"))));
        assertEquals(RENDER.render(timeCmp), RENDER.render(converted(adapter.read("/Order/TimeLitRule"))));
        assertEquals(RENDER.render(diff), RENDER.render(converted(adapter.read("/Order/DiffLitRule"))));
    }

    @Test
    void roundTripsSubDayTimeArithmetic() {
        // family 2: DifferenceInHours over two date-times, and AddMinutes inside a MinutesFromTime extraction.
        BoundModel m = order();
        Condition diff = Dsl.differenceInHours(m.dateTime("/Order/ScheduledAt"), m.dateTime("/Order/CompletedAt")).gt(24);
        Condition add = Dsl.minutesFromTime(Dsl.addMinutes(m.dateTime("/Order/ScheduledAt"), 30)).lt(60);

        KernelAdapter adapter = KernelAdapter.of(m
                .add(orderRule(m, "DiffHoursRule", "/Order/ScheduledAt", diff))
                .add(orderRule(m, "AddMinutesRule", "/Order/ScheduledAt", add)));
        assertEquals(RENDER.render(diff), RENDER.render(converted(adapter.read("/Order/DiffHoursRule"))));
        assertEquals(RENDER.render(add), RENDER.render(converted(adapter.read("/Order/AddMinutesRule"))));
    }

    @Test
    void roundTripsTimeAndDateTimeConstruction() {
        // family 3: Time accepts a 0..3 component prefix, and DateTime combines a date + time.
        BoundModel m = order();
        List<Condition> times = List.of(
                m.time("/Order/PickupTime").eq(Dsl.time()),
                m.time("/Order/PickupTime").eq(Dsl.time("10")),
                m.time("/Order/PickupTime").eq(Dsl.time("10", "30")),
                m.time("/Order/PickupTime").eq(Dsl.time("10", "30", "50")));
        Condition dt = m.dateTime("/Order/ScheduledAt").eq(Dsl.dateTime(m.date("/Order/OrderDate"), m.time("/Order/PickupTime")));

        BoundModel authored = m;
        for (int i = 0; i < times.size(); i++) {
            authored = authored.add(orderRule(authored, "TimeCtor" + i, "/Order/PickupTime", times.get(i)));
        }
        KernelAdapter adapter = KernelAdapter.of(authored
                .add(orderRule(authored, "DateTimeCtorRule", "/Order/ScheduledAt", dt)));
        for (int i = 0; i < times.size(); i++) {
            assertEquals(RENDER.render(times.get(i)),
                    RENDER.render(converted(adapter.read("/Order/TimeCtor" + i))));
        }
        assertEquals(RENDER.render(dt), RENDER.render(converted(adapter.read("/Order/DateTimeCtorRule"))));
    }

    @Test
    void roundTripsValueAsDate() {
        // family 4 tail: ValueAsDate(partiallyKnownDate, FirstDay|LastDay) resolves to a concrete date.
        BoundModel m = order();
        Condition first = Dsl.valueAsDateFirstDay(m.field("/Order/ApproxDate")).after(Dsl.dateValue("01.01.2020"));
        Condition last = Dsl.valueAsDateLastDay(m.field("/Order/ApproxDate")).before(Dsl.dateValue("31.12.2030"));
        assertEquals("ValueAsDate(/Order/ApproxDate, FirstDay) > \"01.01.2020\"", RENDER.render(first));
        assertEquals("ValueAsDate(/Order/ApproxDate, LastDay) < \"31.12.2030\"", RENDER.render(last));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(orderRule(m, "VadFirstRule", "/Order/ApproxDate", first))
                .add(orderRule(m, "VadLastRule", "/Order/ApproxDate", last)));
        assertEquals(RENDER.render(first), RENDER.render(converted(adapter.read("/Order/VadFirstRule"))));
        assertEquals(RENDER.render(last), RENDER.render(converted(adapter.read("/Order/VadLastRule"))));
    }

    @Test
    void roundTripsNowAndBaseYear() {
        // Now (date-time field) + BaseYear in its three contexts (date / date-time / number). The order
        // fixture declares a base year, so BaseYear is valid.
        BoundModel m = order();
        Condition now = m.dateTime("/Order/ScheduledAt").after(Dsl.now());
        Condition byDate = m.date("/Order/OrderDate").after(Dsl.baseYear());
        Condition byNum = m.num("/Order/Quantity").gt(Dsl.baseYearNumber());
        assertEquals("[/Order/ScheduledAt] > Now", RENDER.render(now));
        assertEquals("[/Order/OrderDate] > BaseYear", RENDER.render(byDate));
        assertEquals("[/Order/Quantity] > BaseYear", RENDER.render(byNum));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(orderRule(m, "NowRule", "/Order/ScheduledAt", now))
                .add(orderRule(m, "ByDateRule", "/Order/OrderDate", byDate))
                .add(orderRule(m, "ByNumRule", "/Order/Quantity", byNum)));
        assertEquals(RENDER.render(now), RENDER.render(converted(adapter.read("/Order/NowRule"))));
        assertEquals(RENDER.render(byDate), RENDER.render(converted(adapter.read("/Order/ByDateRule"))));
        assertEquals(RENDER.render(byNum), RENDER.render(converted(adapter.read("/Order/ByNumRule"))));
    }

    @Test
    void roundTripsCrossDateAndDateTimeOrderingInBothDirections() {
        BoundModel m = order();
        Condition dateTimeFirst = m.dateTime("/Order/ScheduledAt").after(m.date("/Order/OrderDate"));
        Condition dateFirst = m.date("/Order/OrderDate").onOrBefore(m.dateTime("/Order/CompletedAt"));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(orderRule(m, "DateTimeAfterDate", "/Order/ScheduledAt", dateTimeFirst))
                .add(orderRule(m, "DateBeforeDateTime", "/Order/OrderDate", dateFirst)));

        assertEquals(RENDER.render(dateTimeFirst),
                RENDER.render(converted(adapter.read("/Order/DateTimeAfterDate"))));
        assertEquals(RENDER.render(dateFirst),
                RENDER.render(converted(adapter.read("/Order/DateBeforeDateTime"))));
    }

    @Test
    void dateExpressionsAreKernelValid() {
        ValidationResult r = new RuleValidator().check(read("models/order.dm.json"),
                new DraftRule("/Order", "ExtractRule", "../OrderDate",
                        "FieldFilled(OrderDate) And YearFromDate(OrderDate) != YearFromDate(Today)", "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule orderRule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Order")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }

    private static String read(String path) {
        try (InputStream in = DateTimeExprReadTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
