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
 * F10 read of a <b>point-in-time temporal source</b>. The converter handled {@code Today} but not the other
 * point-in-time values as a source, so two shapes read Opaque:
 * <ul>
 *   <li>a date <b>extraction</b> source ({@code IDateExtractionOperation}) that is {@code Now}/{@code BaseYear}
 *       — e.g. {@code YearFromDate(BaseYear)}, {@code MonthFromDate(Now)} (Today/Now/BaseYear are all valid);</li>
 *   <li>a date-<b>range</b> source ({@code IDateRangeExtractionOperation}) that is {@code BaseYear} — the one
 *       point-in-time the kernel allows there: {@code StartOfDateRange(BaseYear)} = 01.01, {@code
 *       EndOfDateRange(BaseYear)} = 31.12 of the reference year.</li>
 * </ul>
 *
 * <p>Own domain: the order fixture declares a base year, so {@code BaseYear} is valid.
 */
class PointInTimeSourceReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    @Test
    void pointInTimeSourcesAreKernelValid() {
        String model = read("models/order.dm.json");
        var cases = new String[] {
            "FieldFilled(OrderDate) And YearFromDate(OrderDate) != YearFromDate(BaseYear)",   // extraction: BaseYear
            "FieldFilled(OrderDate) And MonthFromDate(OrderDate) != MonthFromDate(Now)",       // extraction: Now
            "FieldFilled(OrderDate) And [OrderDate] < StartOfDateRange(BaseYear)",             // range source: BaseYear
            "FieldFilled(OrderDate) And [OrderDate] > EndOfDateRange(BaseYear)",               // range source: BaseYear
        };
        for (String dsl : cases) {
            ValidationResult r = new RuleValidator().check(model,
                    new DraftRule("/Order", "R", "../OrderDate", dsl, "C", "ERROR"));
            assertTrue(r.ok(), () -> "expected kernel acceptance for: " + dsl + " — got: " + r.diagnostics());
        }
    }

    @Test
    void roundTripsPointInTimeSources() {
        BoundModel m = BoundModel.ofJson(read("models/order.dm.json"));
        Condition extractBaseYear = m.date("/Order/OrderDate").filled()
                .and(Dsl.yearFromDate(m.date("/Order/OrderDate")).ne(Dsl.yearFromDate(Dsl.baseYear())));
        Condition extractNow = m.date("/Order/OrderDate").filled()
                .and(Dsl.monthFromDate(m.date("/Order/OrderDate")).ne(Dsl.monthFromDate(Dsl.now())));
        Condition rangeStart = m.date("/Order/OrderDate").filled()
                .and(m.date("/Order/OrderDate").before(Dsl.startOfBaseYear()));
        Condition rangeEnd = m.date("/Order/OrderDate").filled()
                .and(m.date("/Order/OrderDate").after(Dsl.endOfBaseYear()));

        assertTrue(RENDER.render(extractBaseYear).contains("YearFromDate(BaseYear)"), RENDER.render(extractBaseYear));
        assertTrue(RENDER.render(extractNow).contains("MonthFromDate(Now)"), RENDER.render(extractNow));
        assertTrue(RENDER.render(rangeStart).contains("StartOfDateRange(BaseYear)"), RENDER.render(rangeStart));
        assertTrue(RENDER.render(rangeEnd).contains("EndOfDateRange(BaseYear)"), RENDER.render(rangeEnd));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "ExtractBaseYear", "/Order/OrderDate", extractBaseYear))
                .add(rule(m, "ExtractNow", "/Order/OrderDate", extractNow))
                .add(rule(m, "RangeStart", "/Order/OrderDate", rangeStart))
                .add(rule(m, "RangeEnd", "/Order/OrderDate", rangeEnd)));

        assertEquals(RENDER.render(extractBaseYear), RENDER.render(converted(adapter.read("/Order/ExtractBaseYear"))));
        assertEquals(RENDER.render(extractNow), RENDER.render(converted(adapter.read("/Order/ExtractNow"))));
        assertEquals(RENDER.render(rangeStart), RENDER.render(converted(adapter.read("/Order/RangeStart"))));
        assertEquals(RENDER.render(rangeEnd), RENDER.render(converted(adapter.read("/Order/RangeEnd"))));
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Order")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }

    private static String read(String path) {
        try (InputStream in = PointInTimeSourceReadTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
