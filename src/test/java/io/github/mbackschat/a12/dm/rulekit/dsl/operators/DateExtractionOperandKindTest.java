package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExtraction;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimePart;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand;

/**
 * The typed peers of the operand-kind rows in {@code validate.laws.DateExtractionLawsTest}, which measure at
 * the kernel what each of these states — a client-side refusal states a rule the kernel already has, never
 * one invented here. The rule these gates can state is narrow on purpose: only that the operand is a point in
 * time, because everything finer is decided by the DECLARED FORMAT that a bare {@code Calculation} does not
 * carry.
 *
 * <p>These gates live on the records rather than on the {@code Dsl} factories because the factories take an
 * already-typed handle and can only ever be handed a correct operand; a caller holding a bare
 * {@code Calculation} — anything reading a parsed AST rather than writing Java against the DSL — reaches the
 * constructor directly, and until the gate existed it built a node the kernel would refuse.
 */
class DateExtractionOperandKindTest {

    private static final BoundModel ORDER = Fixtures.order();

    /** A TIME-kind field declared with a date format — the shape whose extractions the kernel admits. */
    private static final BoundModel DATE_DECLARED_TIME = BoundModel.ofJson(
            DocumentModelDraft.named("date-declared-time").locale("en_US")
                    .rootGroup("P", p -> p.timeField("TimeAsDate", f -> f.format("yyyy-MM-dd")))
                    .build().toJson());

    @Test
    void aNonTemporalExtractionOperandIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DateExtraction(DateExtraction.Unit.YEAR,
                        ORDER.field("/Order/CustomerName").value()));
        assertTrue(e.getMessage().contains("YearFromDate"), () -> "unexpected: " + e.getMessage());

        assertDoesNotThrow(() -> new DateExtraction(DateExtraction.Unit.YEAR,
                ORDER.field("/Order/OrderDate").value()));
    }

    /** A date RANGE is a span, not a point in time, so it too has no component to extract. */
    @Test
    void aDateRangeExtractionOperandIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DateExtraction(DateExtraction.Unit.YEAR,
                        ORDER.field("/Order/CoverageWindow").value()));
        assertTrue(e.getMessage().contains("MVK_NO_DATE"), () -> "unexpected: " + e.getMessage());
    }

    /**
     * Which temporal kind each unit admits is the DECLARED FORMAT's business, so it is delegated rather than
     * refused here: a TIME-kind field declared {@code yyyy-MM-dd} answers {@code YearFromDate} at the kernel
     * (KF211), and refusing it on kind made that legal condition unbuildable. The mirror under-refusal is
     * accepted for the same reason — {@code HoursFromTime} over a DATE-kind source builds, and the kernel's
     * authoring gate is what reports it (API-FINDINGS 130; {@code dsl.TemporalFormatHandleTest}).
     */
    @Test
    void theTemporalKindIsLeftToTheKernelInBothDirections() {
        assertDoesNotThrow(() -> Dsl.yearFromDate(DATE_DECLARED_TIME.date("/P/TimeAsDate")),
                "the kernel-legal extraction the kind gate had made unrepresentable");
        assertDoesNotThrow(() -> new DateExtraction(DateExtraction.Unit.HOURS,
                ORDER.field("/Order/OrderDate").value()),
                "and its mirror is not refused here either — the format, not the kind, decides");
    }

    /** The split family states the same one rule, and nothing about completeness. */
    @Test
    void aNonTemporalSplitOperandIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DateTimePart(DateTimePart.Unit.DATE, ORDER.field("/Order/CustomerName").value()));
        assertTrue(e.getMessage().contains("MVK_NO_DATE"), () -> "unexpected: " + e.getMessage());

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
