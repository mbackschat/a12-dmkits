package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExtraction;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimePart;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;

/**
 * The typed peers of the operand-kind rows in {@code validate.laws.DateExtractionLawsTest}, which measure at
 * the kernel what each of these states — a client-side refusal states a rule the kernel already has, never
 * one invented here. The refusal is narrow on purpose: exactly the seven owner-backed kinds measured on both
 * carriers, while UNKNOWN and every temporal kind remain delegated because a bare {@code Calculation} does not
 * carry the declared format that decides the finer gate.
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
    void everyMeasuredNonPointKindIsRefusedByBothPublicConstructors() {
        for (ValueKind kind : EnumSet.of(ValueKind.NUMBER, ValueKind.STRING, ValueKind.ENUMERATION,
                ValueKind.BOOLEAN, ValueKind.CONFIRM, ValueKind.DATE_RANGE, ValueKind.CUSTOM)) {
            var source = new FieldRef("/Probe/" + kind, kind).value();
            IllegalArgumentException extraction = assertThrows(IllegalArgumentException.class,
                    () -> new DateExtraction(DateExtraction.Unit.YEAR, source), kind.name());
            assertTrue(extraction.getMessage().contains("YearFromDate"),
                    () -> "unexpected for " + kind + ": " + extraction.getMessage());
            assertTrue(extraction.getMessage().contains("MVK_NO_DATE"),
                    () -> "unexpected for " + kind + ": " + extraction.getMessage());

            IllegalArgumentException split = assertThrows(IllegalArgumentException.class,
                    () -> new DateTimePart(DateTimePart.Unit.DATE, source), kind.name());
            assertTrue(split.getMessage().contains("DateFromDateTime"),
                    () -> "unexpected for " + kind + ": " + split.getMessage());
            assertTrue(split.getMessage().contains("MVK_NO_DATE"),
                    () -> "unexpected for " + kind + ": " + split.getMessage());
        }
    }

    /** An unresolved owner type has no measured kernel counterpart, so under-report rather than refuse it. */
    @Test
    void anUnknownKindRemainsKernelArbitratedOnBothCarriers() {
        var source = new FieldRef("/Probe/Unresolved", ValueKind.UNKNOWN).value();
        assertDoesNotThrow(() -> new DateExtraction(DateExtraction.Unit.YEAR, source));
        assertDoesNotThrow(() -> new DateTimePart(DateTimePart.Unit.DATE, source));

        assertDoesNotThrow(() -> new DateExtraction(DateExtraction.Unit.YEAR,
                ORDER.field("/Order/OrderDate").value()));
        assertDoesNotThrow(() -> new DateTimePart(DateTimePart.Unit.DATE,
                ORDER.field("/Order/ScheduledAt").value()));
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
