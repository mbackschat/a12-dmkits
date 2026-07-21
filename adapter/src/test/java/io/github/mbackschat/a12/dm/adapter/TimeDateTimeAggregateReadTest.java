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
 * F10 read tail — the <b>TIME</b> and <b>DATETIME</b> variants of {@code MaxValue}/{@code MinValue}: a time /
 * date-time field compared against the latest/earliest time-of-day / instant over a repetition list. The kernel
 * folds all three date-like kinds through one {@code DateCombiner} (SPEC-2026-07-21-07), so the converter routes
 * the TIME/DATETIME extremum through the shared {@code DateListAggregate}, wrapped as a {@code TimeExpr} /
 * {@code DateTimeExpr} (the DATE sibling is {@link DateAggregateReadTest}). Own domain: a field-service visit
 * roll-up of arrival times and check-in instants.
 */
class TimeDateTimeAggregateReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String scheduleJson() {
        return DocumentModelDraft.named("visit-schedule").locale("en_US")
                .rootGroup("Schedule", s -> s
                        .timeField("LatestArrival", t -> t.format("HH:mm:ss"))
                        .timeField("EarliestArrival", t -> t.format("HH:mm:ss"))
                        .dateTimeField("LatestCheckIn", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .dateTimeField("EarliestCheckIn", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .group("Visits", g -> g
                                .repeatable(20)
                                .timeField("ArrivalTime", t -> t.format("HH:mm:ss"))
                                .dateTimeField("CheckInAt", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))))
                .build().toJson();
    }

    @Test
    void roundTripsLatestAndEarliestTimeAggregates() {
        BoundModel m = BoundModel.ofJson(scheduleJson());
        Condition latest = m.time("/Schedule/LatestArrival")
                .ne(Dsl.latestTimeOf(m.flatten("/Schedule/Visits*/ArrivalTime")));
        Condition earliest = m.time("/Schedule/EarliestArrival")
                .ne(Dsl.earliestTimeOf(m.flatten("/Schedule/Visits*/ArrivalTime")));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "LatestArrivalRule", "/Schedule/LatestArrival", latest))
                .add(rule(m, "EarliestArrivalRule", "/Schedule/EarliestArrival", earliest)));
        assertEquals(RENDER.render(latest), RENDER.render(converted(adapter.read("/Schedule/LatestArrivalRule"))));
        assertEquals(RENDER.render(earliest), RENDER.render(converted(adapter.read("/Schedule/EarliestArrivalRule"))));
    }

    @Test
    void roundTripsLatestAndEarliestDateTimeAggregates() {
        BoundModel m = BoundModel.ofJson(scheduleJson());
        Condition latest = m.dateTime("/Schedule/LatestCheckIn")
                .ne(Dsl.latestDateTimeOf(m.flatten("/Schedule/Visits*/CheckInAt")));
        Condition earliest = m.dateTime("/Schedule/EarliestCheckIn")
                .ne(Dsl.earliestDateTimeOf(m.flatten("/Schedule/Visits*/CheckInAt")));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "LatestCheckInRule", "/Schedule/LatestCheckIn", latest))
                .add(rule(m, "EarliestCheckInRule", "/Schedule/EarliestCheckIn", earliest)));
        assertEquals(RENDER.render(latest), RENDER.render(converted(adapter.read("/Schedule/LatestCheckInRule"))));
        assertEquals(RENDER.render(earliest), RENDER.render(converted(adapter.read("/Schedule/EarliestCheckInRule"))));
    }

    @Test
    void aTimeFieldComparedAgainstATimeAggregateIsKernelValid() {
        ValidationResult r = new RuleValidator().check(scheduleJson(),
                new DraftRule("/Schedule", "LatestArrivalRule", "../LatestArrival",
                        "FieldFilled(LatestArrival) And [/Schedule/LatestArrival] != MaxValue(Visits*/ArrivalTime)",
                        "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    @Test
    void aDateTimeFieldComparedAgainstADateTimeAggregateIsKernelValid() {
        ValidationResult r = new RuleValidator().check(scheduleJson(),
                new DraftRule("/Schedule", "EarliestCheckInRule", "../EarliestCheckIn",
                        "FieldFilled(EarliestCheckIn) And [/Schedule/EarliestCheckIn] != MinValue(Visits*/CheckInAt)",
                        "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Schedule")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
