package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The typed peer of {@code validate.laws.ValueAsDateOperandPrecisionLawsTest}: {@code ValueAsDate} REQUIRES a
 * partially known date, the inverse of the gate its sibling families apply to the same declaration.
 *
 * <p>Contextual, so it lives on {@link BoundModel}. The {@code Dsl} factories of the same names stay
 * unchecked because they take no model and so cannot read a precision declaration; a converter that HOLDS a
 * model — the interpreter bridge included — goes through the facade (API-FINDINGS AF148).
 *
 * <p>Scoped to the kinds the kernel measurement covers — DATE and DATE_FRAGMENT. Any other kind delegates:
 * neither the factory nor the record kind-checks today, and refusing there under THIS code would state a rule
 * nothing measured.
 */
class ValueAsDatePrecisionGateTest {

    private static final BoundModel M = BoundModel.ofJson(
            DocumentModelDraft.named("value-as-date-gate").locale("en_US")
                    .rootGroup("Record", r -> r
                            .field("Reference", FieldKind.STRING)
                            .dateField("SettledOn", d -> d.format("dd.MM.yyyy"))
                            .dateField("ApproxDay",
                                    d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.DAY_OPTIONAL))
                            .dateField("ApproxMonth",
                                    d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.MONTH_OPTIONAL))
                            .dateField("ApproxYear",
                                    d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.YEAR_OPTIONAL))
                            .dateFragmentField("ReportingYear", f -> f.fragmentFormat("yyyy")))
                    .build().toJson());

    private static FieldRef field(String name) {
        return M.field("/Record/" + name);
    }

    @Test
    void aFullPrecisionDateIsRefusedOnBothKeywords() {
        for (var call : List.<Runnable>of(
                () -> M.valueAsDateFirstDay(field("SettledOn")),
                () -> M.valueAsDateLastDay(field("SettledOn")))) {
            var thrown = assertThrows(IllegalArgumentException.class, call::run);
            assertTrue(thrown.getMessage().contains("MVK_INVALID_DATE_TYPE_FOR_OP"), thrown::getMessage);
            assertTrue(thrown.getMessage().contains("/Record/SettledOn"), thrown::getMessage);
        }
    }

    /** The liveness control and the denominator: every partial precision is admitted, both keywords. */
    @Test
    void everyPartialPrecisionIsAdmitted() {
        for (String name : List.of("ApproxDay", "ApproxMonth", "ApproxYear")) {
            assertDoesNotThrow(() -> M.valueAsDateFirstDay(field(name)), name);
            assertDoesNotThrow(() -> M.valueAsDateLastDay(field(name)), name);
        }
    }

    /** A DATE_FRAGMENT is incomplete by format, not by precision — measured to draw the same refusal. */
    @Test
    void aDateFragmentIsRefused() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> M.valueAsDateFirstDay(field("ReportingYear")));
        assertTrue(thrown.getMessage().contains("MVK_INVALID_DATE_TYPE_FOR_OP"), thrown::getMessage);
    }

    /** An unmeasured kind delegates rather than borrowing this code. */
    @Test
    void aNonTemporalFieldIsDelegated() {
        assertDoesNotThrow(() -> M.valueAsDateFirstDay(field("Reference")));
    }

    /**
     * THE ASYMMETRY. {@code DatePrecisions.guaranteedComponents} answers the full triple for a token it has no
     * rule for, which makes the REFUSING gates decline — the safe direction. This gate refuses the opposite,
     * so that same default would turn "no measured rule" into a refusal, and a model declaring a precision
     * A12 knows and we do not would become both unauthorable and unreadable.
     *
     * <p>The token is unauthorable through the typed surface and undeserializable by the kernel — its
     * {@code DatePrecision} is a closed enum, so {@code ofJson} throws rather than binding. The carrier is
     * therefore the KERNEL-FREE reader, which indexes the declaration as the text it is: the same
     * "a lenient load can present any string at all" case {@code Dsl.dateExtraction} already anticipates for
     * formats. The model is corrupted after emission, the technique the no-value-validation law uses.
     */
    @Test
    void aPrecisionTokenOutsideTheMeasuredVocabularyDelegates() {
        BoundModel foreign = BoundModel.ofJsonLenient(
                M.toJson().replace("DAY_OPTIONAL", "QUARTER_OPTIONAL"),
                io.github.mbackschat.a12.dm.core.ReferenceResolvers.selfContained());
        assertDoesNotThrow(() -> foreign.valueAsDateFirstDay(foreign.field("/Record/ApproxDay")));
        assertDoesNotThrow(() -> foreign.valueAsDateLastDay(foreign.field("/Record/ApproxDay")));
        // The control: the untouched siblings still refuse and still admit, so the row above is about the
        // token and not about the corruption having broken the model.
        assertDoesNotThrow(() -> foreign.valueAsDateFirstDay(foreign.field("/Record/ApproxMonth")));
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> foreign.valueAsDateFirstDay(foreign.field("/Record/SettledOn")));
        assertTrue(thrown.getMessage().contains("MVK_INVALID_DATE_TYPE_FOR_OP"), thrown::getMessage);
    }
}
