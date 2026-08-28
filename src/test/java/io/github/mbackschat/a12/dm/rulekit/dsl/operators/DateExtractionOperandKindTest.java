package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExtraction;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimePart;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand;

/**
 * The typed peers of the operand-kind rows in {@code validate.laws.DateExtractionLawsTest}, which measure that
 * the kernel refuses each of these — a client-side refusal states a rule the kernel already has, never one
 * invented here.
 *
 * <p>These gates live on the records rather than on the {@code Dsl} factories because the factories take an
 * already-typed handle and can only ever be handed a correct operand; a caller holding a bare
 * {@code Calculation} — anything reading a parsed AST rather than writing Java against the DSL — reaches the
 * constructor directly, and until the gate existed it built a node the kernel would refuse.
 */
class DateExtractionOperandKindTest {

    private static final BoundModel ORDER = Fixtures.order();

    @Test
    void aNonTemporalExtractionOperandIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DateExtraction(DateExtraction.Unit.YEAR,
                        ORDER.field("/Order/CustomerName").value()));
        assertTrue(e.getMessage().contains("YearFromDate"), () -> "unexpected: " + e.getMessage());

        assertDoesNotThrow(() -> new DateExtraction(DateExtraction.Unit.YEAR,
                ORDER.field("/Order/OrderDate").value()));
    }

    /** A date where the unit needs a TIME — the wrong temporal kind, not a non-temporal one. */
    @Test
    void aWrongTemporalKindExtractionOperandIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new DateExtraction(DateExtraction.Unit.HOURS, ORDER.field("/Order/OrderDate").value()));
        assertDoesNotThrow(() -> new DateExtraction(DateExtraction.Unit.HOURS,
                ORDER.field("/Order/PickupTime").value()));
    }

    /** The date/time split needs a COMPLETE date-time; a plain date has no time half to take. */
    @Test
    void splittingAPlainDateIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new DateTimePart(DateTimePart.Unit.DATE, ORDER.field("/Order/OrderDate").value()));
        assertDoesNotThrow(() -> new DateTimePart(DateTimePart.Unit.DATE,
                ORDER.field("/Order/ScheduledAt").value()));
    }

    /**
     * {@code FieldFilled}'s wider overload exists for the same untyped caller, and states the kernel's
     * MVK_NO_WILDCARDS_ALLOWED rather than leaving the caller with no method to call.
     */
    @Test
    void fieldPresenceOverAStarredOperandIsRefusedWithTheKernelsCode() {
        FieldListRef starred = ORDER.flatten("/Order/Items*/Count");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Dsl.fieldFilled((FieldOperand) starred));
        assertTrue(e.getMessage().contains("MVK_NO_WILDCARDS_ALLOWED"),
                () -> "expected the kernel's own code in: " + e.getMessage());

        assertDoesNotThrow(() -> Dsl.fieldFilled((FieldOperand) ORDER.field("/Order/ProductCode")));
    }
}
