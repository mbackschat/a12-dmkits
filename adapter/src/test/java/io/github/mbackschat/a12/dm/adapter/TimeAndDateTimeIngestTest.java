package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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
 * F10 ingest tail — the TIME and DATE_TIME value kinds. Two clusters mined from Corpus-1:
 * <ul>
 *   <li><b>TIME ordering</b> — {@code [ServiceWindowStart] >= [ServiceWindowEnd]} (time-vs-time, orderable);</li>
 *   <li><b>DATE_TIME decomposition</b> — a date-time {@code ScheduledAt} read for its numeric components
 *       ({@code HoursFromTime}/{@code YearFromDate}, …), its date part ({@code DateFromDateTime}), and its
 *       time part ({@code TimeFromDateTime}).</li>
 * </ul>
 * Own domain: a field-service appointment model with its own TIME/DATE_TIME/DATE/number fields.
 */
class TimeAndDateTimeIngestTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static BoundModel appointment() {
        return BoundModel.ofJson(read("models/appointment.dm.json"));
    }

    @Test
    void timeOrderingRoundTrips() {
        BoundModel m = appointment();
        // [ServiceWindowStart] >= [ServiceWindowEnd] — a malformed window (start not before end)
        Condition full = m.time("/Appointment/ServiceWindowStart").filled()
                .and(m.time("/Appointment/ServiceWindowEnd").filled())
                .and(m.time("/Appointment/ServiceWindowStart")
                        .onOrAfter(m.time("/Appointment/ServiceWindowEnd")));
        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "WindowRule", "/Appointment/ServiceWindowStart", full)));
        assertTrue(RENDER.render(full)
                .contains("[/Appointment/ServiceWindowStart] >= [/Appointment/ServiceWindowEnd]"), RENDER.render(full));
        assertEquals(RENDER.render(full), RENDER.render(converted(adapter.ingest("/Appointment/WindowRule"))));
    }

    @Test
    void timeOfDayExtractionFromADateTimeRoundTrips() {
        BoundModel m = appointment();
        // [ScheduledHour] != HoursFromTime(ScheduledAt) — the stored hour disagrees with the timestamp
        Condition full = m.num("/Appointment/ScheduledHour").filled()
                .and(m.num("/Appointment/ScheduledHour")
                        .ne(Dsl.hoursFromTime(m.dateTime("/Appointment/ScheduledAt"))));
        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "HourRule", "/Appointment/ScheduledHour", full)));
        assertTrue(RENDER.render(full).contains("HoursFromTime(/Appointment/ScheduledAt)"), RENDER.render(full));
        assertEquals(RENDER.render(full), RENDER.render(converted(adapter.ingest("/Appointment/HourRule"))));
    }

    @Test
    void numericComponentFromADateTimeSourceRoundTrips() {
        BoundModel m = appointment();
        // YearFromDate accepts a DATE_TIME source too — [ScheduledYear] != YearFromDate(ScheduledAt)
        Condition full = m.num("/Appointment/ScheduledYear").filled()
                .and(m.num("/Appointment/ScheduledYear")
                        .ne(Dsl.yearFromDate(m.dateTime("/Appointment/ScheduledAt"))));
        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "YearRule", "/Appointment/ScheduledYear", full)));
        assertEquals(RENDER.render(full), RENDER.render(converted(adapter.ingest("/Appointment/YearRule"))));
    }

    @Test
    void dateAndTimePartsOfADateTimeRoundTrip() {
        BoundModel m = appointment();
        // [OnSiteDate] != DateFromDateTime(ScheduledAt) ; [OnSiteTime] != TimeFromDateTime(ScheduledAt)
        Condition datePart = m.date("/Appointment/OnSiteDate").filled()
                .and(m.date("/Appointment/OnSiteDate")
                        .ne(Dsl.dateFromDateTime(m.dateTime("/Appointment/ScheduledAt"))));
        Condition timePart = m.time("/Appointment/OnSiteTime").filled()
                .and(m.time("/Appointment/OnSiteTime")
                        .ne(Dsl.timeFromDateTime(m.dateTime("/Appointment/ScheduledAt"))));
        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "DatePartRule", "/Appointment/OnSiteDate", datePart))
                .add(rule(m, "TimePartRule", "/Appointment/OnSiteTime", timePart)));
        assertEquals(RENDER.render(datePart),
                RENDER.render(converted(adapter.ingest("/Appointment/DatePartRule"))));
        assertEquals(RENDER.render(timePart),
                RENDER.render(converted(adapter.ingest("/Appointment/TimePartRule"))));
    }

    @Test
    void theTimeAndDateTimeConstructsAreKernelValid() {
        BoundModel m = appointment();
        record Case(String name, String errorField, Condition cond) { }
        var cases = new Case[] {
            new Case("Window", "../ServiceWindowStart",
                    m.time("/Appointment/ServiceWindowStart").filled()
                            .and(m.time("/Appointment/ServiceWindowEnd").filled())
                            .and(m.time("/Appointment/ServiceWindowStart")
                                    .onOrAfter(m.time("/Appointment/ServiceWindowEnd")))),
            new Case("Hour", "../ScheduledHour",
                    m.num("/Appointment/ScheduledHour").filled()
                            .and(m.num("/Appointment/ScheduledHour")
                                    .ne(Dsl.hoursFromTime(m.dateTime("/Appointment/ScheduledAt"))))),
            new Case("DatePart", "../OnSiteDate",
                    m.date("/Appointment/OnSiteDate").filled()
                            .and(m.date("/Appointment/OnSiteDate")
                                    .ne(Dsl.dateFromDateTime(m.dateTime("/Appointment/ScheduledAt"))))),
            new Case("TimePart", "../OnSiteTime",
                    m.time("/Appointment/OnSiteTime").filled()
                            .and(m.time("/Appointment/OnSiteTime")
                                    .ne(Dsl.timeFromDateTime(m.dateTime("/Appointment/ScheduledAt"))))),
        };
        for (Case c : cases) {
            ValidationResult r = new RuleValidator().check(read("models/appointment.dm.json"),
                    new DraftRule("/Appointment", c.name() + "V", c.errorField(), RENDER.render(c.cond()), "C", "ERROR"));
            assertTrue(r.ok(), () -> "expected kernel acceptance for " + c.name() + ", got: " + r.diagnostics());
        }
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Appointment")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(Ingest result) {
        return assertInstanceOf(Ingest.Converted.class, result,
                () -> "expected a converted ingest, got: " + result).condition();
    }

    private static String read(String path) {
        try (InputStream in = TimeAndDateTimeIngestTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
