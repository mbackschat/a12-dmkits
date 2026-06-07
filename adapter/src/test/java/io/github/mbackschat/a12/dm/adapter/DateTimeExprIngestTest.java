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
 * F10 ingest tail — date expressions beyond fields/Today: {@code AddDays/AddMonths/AddYears} as date
 * operands (already authorable, now ingested), the {@code YearFromDate}/{@code MonthFromDate}/… numeric
 * extractions, and {@code Date(day, month, year)} construction. Shift + extraction ride the order
 * fixture's date fields; construction uses a small event model with day/month/year number fields.
 */
class DateTimeExprIngestTest {

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
        assertEquals(RENDER.render(shift), RENDER.render(converted(adapter.ingest("/Order/ShiftRule"))));
        assertEquals(RENDER.render(extract), RENDER.render(converted(adapter.ingest("/Order/ExtractRule"))));
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
        assertEquals(RENDER.render(eq), RENDER.render(converted(adapter.ingest("/Order/ShiftEqRule"))));
        assertEquals(RENDER.render(leToday), RENDER.render(converted(adapter.ingest("/Order/ShiftLeRule"))));
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

    private static Condition converted(Ingest result) {
        return assertInstanceOf(Ingest.Converted.class, result,
                () -> "expected a converted ingest, got: " + result).condition();
    }

    private static String read(String path) {
        try (InputStream in = DateTimeExprIngestTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
