package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * The typed peer of {@code validate.laws.PartialDateAggregateLawsTest}: the entity-list aggregates refuse a
 * partly-known DATE operand ({@code MVK_PARTIAL_DATE_NOT_ALLOWED}, [KF427]).
 *
 * <p>This is the SECOND refusal on the same kernel check the component-set gate already sits on — one method,
 * two codes — so it joins the same model-taking overloads rather than adding a surface.
 *
 * <p><b>It must not be folded into the component-set comparison.</b> That one supplements every operand set
 * with YEAR when the model declares a base year; this one has nothing to do with base year and would be wrong
 * under it. They share a call site and nothing else.
 */
class AggregatePartialDateGateTest {

    private static final BoundModel MODEL = BoundModel.ofJson(
            DocumentModelDraft.named("aggregate-partial-date").locale("en_US")
                    .rootGroup("Doc", d -> d
                            .stringField("Error", s -> { })
                            // One declared format throughout, so the component-set gate cannot fire and
                            // precision is the only variable these rows move.
                            .dateField("FiledOn", f -> f.format("dd.MM.yyyy"))
                            .dateField("ClosedOn", f -> f.format("dd.MM.yyyy"))
                            .dateField("ApproxDay",
                                    f -> f.format("dd.MM.yyyy").datePrecision(DatePrecision.DAY_OPTIONAL))
                            .dateField("ApproxMonth",
                                    f -> f.format("dd.MM.yyyy").datePrecision(DatePrecision.MONTH_OPTIONAL))
                            .dateField("ApproxYear",
                                    f -> f.format("dd.MM.yyyy").datePrecision(DatePrecision.YEAR_OPTIONAL))
                            .dateFragmentField("ReportingYear", f -> f.fragmentFormat("yyyy")))
                    .build().toJson());

    /** Both DATE carriers of the measured law, as (model, first, rest) factories. */
    private static final List<BiFunction<AggregateOperand, AggregateOperand, Object>> DATE_CARRIERS = List.of(
            (a, b) -> MODEL.latestOf(a, b),
            (a, b) -> MODEL.earliestOf(a, b));

    private static AggregateOperand field(String name) {
        return MODEL.field("/Doc/" + name);
    }

    @Test
    void everyDateAggregateRefusesAPartlyKnownOperand() {
        for (var carrier : DATE_CARRIERS) {
            var thrown = assertThrows(IllegalArgumentException.class,
                    () -> carrier.apply(field("FiledOn"), field("ApproxDay")));
            assertTrue(thrown.getMessage().contains("MVK_PARTIAL_DATE_NOT_ALLOWED"), thrown::getMessage);
            assertTrue(thrown.getMessage().contains("/Doc/ApproxDay"), thrown::getMessage);
        }
    }

    @Test
    void numberOfDifferentValuesRefusesAPartlyKnownOperand() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> MODEL.numberOfDifferentValues(field("FiledOn"), field("ApproxDay")));
        assertTrue(thrown.getMessage().contains("MVK_PARTIAL_DATE_NOT_ALLOWED"), thrown::getMessage);
    }

    /** The liveness control: full-precision operands of the same format are admitted. */
    @Test
    void fullPrecisionOperandsAreAdmitted() {
        for (var carrier : DATE_CARRIERS) {
            assertDoesNotThrow(() -> carrier.apply(field("FiledOn"), field("ClosedOn")));
        }
        assertDoesNotThrow(() -> MODEL.numberOfDifferentValues(field("FiledOn"), field("ClosedOn")));
    }

    /** Every partial precision, and either position — the measured denominator, not a sample. */
    @Test
    void allThreePrecisionsInEitherPositionAreRefused() {
        for (String partial : List.of("ApproxDay", "ApproxMonth", "ApproxYear")) {
            assertThrows(IllegalArgumentException.class,
                    () -> MODEL.latestOf(field("FiledOn"), field(partial)), partial + " second");
            assertThrows(IllegalArgumentException.class,
                    () -> MODEL.latestOf(field(partial), field("FiledOn")), partial + " first");
        }
    }

    /**
     * The kind discriminator, and the row that keeps this gate at its measured basis: a DATE_FRAGMENT is
     * incomplete by its FORMAT, not by a precision declaration, and the kernel refuses it under the other
     * code. Refusing it here would be broader than anything measured.
     */
    @Test
    void aDateFragmentIsNotRefusedByThisGate() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> MODEL.latestOf(field("FiledOn"), field("ReportingYear")));
        assertTrue(thrown.getMessage().contains("MVK_DATEFORMATS_NOT_COMPATIBLE"), thrown::getMessage);
        assertTrue(!thrown.getMessage().contains("MVK_PARTIAL_DATE_NOT_ALLOWED"), thrown::getMessage);
    }
}
