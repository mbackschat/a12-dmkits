package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
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
 * F10 read tail — date-range bound extraction: {@code StartOfDateRange(range)} / {@code EndOfDateRange(range)}
 * (the kernel's {@code IDateRangeExtractionOperation}) yield the interval's bound dates, which then compare or
 * feed a date component extraction ({@code DayFromDate(EndOfDateRange(range))}). Own domain: a booking whose
 * coverage interval's bounds are checked against the requested dates.
 */
class DateRangeReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String bookingJson() {
        return DocumentModelDraft.named("booking").locale("en_US")
                .rootGroup("Booking", b -> b
                        .dateRangeField("Coverage", r -> r.format("dd.MM.yyyy").separator("-"))
                        .dateRangeField("Blackout", r -> r.format("dd.MM.yyyy").separator("-"))
                        .dateField("RequestedStart", d -> d.format("dd.MM.yyyy"))
                        .numberField("EndDay", n -> { })
                        .group("Periods", g -> g.repeatable(20)
                                .dateRangeField("Span", r -> r.format("dd.MM.yyyy").separator("-"))))
                .build().toJson();
    }

    @Test
    void roundTripsDateRangeBoundExtraction() {
        BoundModel m = BoundModel.ofJson(bookingJson());
        // [RequestedStart] != StartOfDateRange(Coverage)
        Condition startCmp = m.date("/Booking/RequestedStart").filled()
                .and(m.date("/Booking/RequestedStart").ne(Dsl.startOfDateRange(m.dateRange("/Booking/Coverage"))));
        // [EndDay] != DayFromDate(EndOfDateRange(Coverage)) — a bound feeding a component extraction
        Condition endDay = m.num("/Booking/EndDay").filled()
                .and(m.num("/Booking/EndDay").ne(Dsl.dayFromDate(Dsl.endOfDateRange(m.dateRange("/Booking/Coverage")))));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "StartRule", "/Booking/RequestedStart", startCmp))
                .add(rule(m, "EndDayRule", "/Booking/EndDay", endDay)));
        assertTrue(RENDER.render(startCmp).contains("StartOfDateRange(/Booking/Coverage)"), RENDER.render(startCmp));
        assertTrue(RENDER.render(endDay).contains("DayFromDate(EndOfDateRange(/Booking/Coverage))"), RENDER.render(endDay));
        assertEquals(RENDER.render(startCmp), RENDER.render(converted(adapter.read("/Booking/StartRule"))));
        assertEquals(RENDER.render(endDay), RENDER.render(converted(adapter.read("/Booking/EndDayRule"))));
    }

    @Test
    void roundTripsDateRangeOverlapConditions() {
        BoundModel m = BoundModel.ofJson(bookingJson());
        // DateRangesOverlap(Coverage, Blackout) — the two intervals overlap
        Condition overlap = m.dateRange("/Booking/Coverage").filled()
                .and(Dsl.dateRangesOverlap(m.field("/Booking/Coverage"), m.field("/Booking/Blackout")));
        // AtLeastOneDateRangeOverlaps(Coverage IN Periods*/Span) — Coverage overlaps at least one period
        Condition anyOverlap = m.dateRange("/Booking/Coverage").filled()
                .and(Dsl.atLeastOneDateRangeOverlaps(m.field("/Booking/Coverage"),
                        m.flatten("/Booking/Periods*/Span")));
        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "OverlapRule", "/Booking/Coverage", overlap))
                .add(rule(m, "AnyOverlapRule", "/Booking/Coverage", anyOverlap)));
        assertEquals(RENDER.render(overlap), RENDER.render(converted(adapter.read("/Booking/OverlapRule"))));
        assertEquals(RENDER.render(anyOverlap), RENDER.render(converted(adapter.read("/Booking/AnyOverlapRule"))));
    }

    @Test
    void dateRangeExtractionIsKernelValid() {
        BoundModel m = BoundModel.ofJson(bookingJson());
        Condition c = m.date("/Booking/RequestedStart").filled()
                .and(m.date("/Booking/RequestedStart").ne(Dsl.startOfDateRange(m.dateRange("/Booking/Coverage"))));
        ValidationResult r = new RuleValidator().check(bookingJson(),
                new DraftRule("/Booking", "StartRule", "../RequestedStart", RENDER.render(c), "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Booking")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
