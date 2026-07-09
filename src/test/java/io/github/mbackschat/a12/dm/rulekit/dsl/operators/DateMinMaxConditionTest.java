package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * Operand-list {@code Min}/{@code Max} over <b>DATE</b> operands (DEV-API-GAPS shape (1); the kernel's
 * {@code DateCombiner} — {@code minDatum}/{@code maxDatum}, KERNEL-FINDINGS §6). The number
 * {@code Dsl.min}/{@code Dsl.max} are typed over {@link NumberExpr}; the date overloads take
 * {@link DateOperand}s and yield a {@link DateExpr}, so a date {@code Min([A], [B]) < "20.03.2026"} is
 * typed-authorable. The stored form is pinned by the interpreter differential
 * {@code DateAggregateDiffTest} (the kernel already accepts + evaluates it); this proves the typed DSL
 * renders to that exact string and the real kernel accepts it.
 */
class DateMinMaxConditionTest {

    private static final BoundModel m = Fixtures.order();
    private static final String OD = "/Order/OrderDate";
    private static final String DD = "/Order/DeliveryDate";

    @Test
    void datesMinRendersLikeTheDiffTestStoredForm() {
        // pinned by DateAggregateDiffTest: `Min([$OD], [$DD]) < "20.03.2026"`
        Condition c = Dsl.min(m.date(OD), m.date(DD)).before(Dsl.dateValue("20.03.2026"));
        assertEquals("Min([/Order/OrderDate], [/Order/DeliveryDate]) < \"20.03.2026\"",
                new DslRenderer().render(c));
        // Min/Max keyword is language-independent; only the operands/literal carry, so DE is identical
        assertEquals("Min([/Order/OrderDate], [/Order/DeliveryDate]) < \"20.03.2026\"",
                new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void datesMaxRendersLikeTheDiffTestStoredForm() {
        // pinned by DateAggregateDiffTest: `Max([$OD], [$DD]) > "01.02.2026"`
        Condition c = Dsl.max(m.date(OD), m.date(DD)).after(Dsl.dateValue("01.02.2026"));
        assertEquals("Max([/Order/OrderDate], [/Order/DeliveryDate]) > \"01.02.2026\"",
                new DslRenderer().render(c));
    }

    @Test
    void dateMinMaxAreDateOperandsThatCompareToDates() {
        // the result is a DATE expression (a DateOperand) — its Calculation kind is DATE, not NUMBER,
        // so no number/date mixing: the compiler only lets a date min/max be compared to a date operand.
        DateExpr earliest = Dsl.min(m.date(OD), m.date(DD));
        assertEquals(ValueKind.DATE, earliest.expression().kind());
        // composes with any date operand (Today, another field) — not only a literal
        assertEquals("Max([/Order/OrderDate], [/Order/DeliveryDate]) >= Today",
                new DslRenderer().render(Dsl.max(m.date(OD), m.date(DD)).onOrAfter(Dsl.today())));
    }

    @Test
    void theRealKernelAcceptsTheTypedDateMin() {
        // round-trip: author typed → render → the REAL kernel accepts the rendered errorCondition
        Condition c = Dsl.min(m.date(OD), m.date(DD)).before(Dsl.dateValue("20.03.2026"));
        String rendered = new DslRenderer().render(c);
        ValidationResult vr = new RuleValidator().check(Fixtures.orderJson(),
                new DraftRule("/Order", "DateMinRule", "../DeliveryDate", rendered, "DATE_MIN", "ERROR"));
        assertTrue(vr.ok(), () -> "kernel rejected the typed date-min: " + vr.diagnostics());
    }
}
