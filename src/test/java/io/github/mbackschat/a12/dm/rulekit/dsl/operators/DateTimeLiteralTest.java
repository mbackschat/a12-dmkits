package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * A date-time constant completes the temporal-literal family beside {@code dateValue}/{@code timeValue}. The
 * kernel stores one in {@code dd.MM.yyyy'T'HH:mm:ss} independent of the compared field's display format, so
 * the value is carried verbatim and renders quoted — the quote syntax is intrinsic, not a catalog keyword.
 *
 * <p>The acceptance row is the point: without a literal of its own kind, a DATE_TIME field could only be
 * compared against another field, and a quoted constant beside it had no typed operand to become.
 */
class DateTimeLiteralTest {

    private static final BoundModel ORDER = Fixtures.order();

    @Test
    void aDateTimeConstantRendersQuotedInTheKernelsOwnForm() {
        assertEquals("[/Order/ScheduledAt] > \"01.01.2022T17:00:00\"",
                new DslRenderer().render(
                        ORDER.dateTime("/Order/ScheduledAt").after(Dsl.dateTimeValue("01.01.2022T17:00:00"))));
    }

    @Test
    void theKernelAcceptsThatComparison() {
        String condition = new DslRenderer().render(Dsl.fieldFilled(ORDER.field("/Order/CustomerName"))
                .and(ORDER.dateTime("/Order/ScheduledAt").after(Dsl.dateTimeValue("01.01.2022T17:00:00"))));
        ValidationResult r = new RuleValidator().check(Fixtures.orderJson(),
                new DraftRule("/Order", "ScheduledAfter", "../CustomerName", condition, "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance of '" + condition + "', got: " + r.diagnostics());
    }
}
