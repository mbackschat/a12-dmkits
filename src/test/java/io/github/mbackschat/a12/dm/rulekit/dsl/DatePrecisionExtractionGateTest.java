package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.LinkedHashMap;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * The typed peer of {@code validate.laws.DateExtractionLawsTest}'s precision grid: a component is extractable
 * iff the operand's declared {@code datePrecision} GUARANTEES it, and a refusal reports
 * {@code MVK_INVALID_DATE_TYPE}.
 *
 * <p>This is the second, independent gate on the same factory. The format gate (KF426) asks whether the
 * declared format carries the component at all; this asks whether a format that carries it is allowed to
 * omit its value. A field may pass one and fail the other, so both denominators are whole grids rather than
 * samples.
 *
 * <p><b>Deliberately silent on the time components.</b> The measurement covers the date triple only, which is
 * all a {@code DateType} can carry; an hour/minute/second extraction over one of these fields is already
 * refused by the format gate, and stating a precision rule for it would state a rule nothing measured.
 */
class DatePrecisionExtractionGateTest {

    /** The measured grid: what each precision GUARANTEES (DateExtractionLawsTest). */
    private static final Map<DatePrecision, Set<TemporalFormats.Component>> GUARANTEED = Map.of(
            DatePrecision.FULL,
            EnumSet.of(TemporalFormats.Component.YEAR, TemporalFormats.Component.MONTH,
                    TemporalFormats.Component.DAY),
            DatePrecision.DAY_OPTIONAL,
            EnumSet.of(TemporalFormats.Component.YEAR, TemporalFormats.Component.MONTH),
            DatePrecision.MONTH_OPTIONAL, EnumSet.of(TemporalFormats.Component.YEAR),
            DatePrecision.YEAR_OPTIONAL, EnumSet.noneOf(TemporalFormats.Component.class));

    /** Quarter maps to the MONTH component, as it does on the format gate. */
    private static final Map<DateExtraction.Unit, TemporalFormats.Component> DATE_UNITS = Map.of(
            DateExtraction.Unit.DAY, TemporalFormats.Component.DAY,
            DateExtraction.Unit.MONTH, TemporalFormats.Component.MONTH,
            DateExtraction.Unit.QUARTER, TemporalFormats.Component.MONTH,
            DateExtraction.Unit.YEAR, TemporalFormats.Component.YEAR);

    private static final BoundModel MODEL = BoundModel.ofJson(
            DocumentModelDraft.named("date-precision-extraction-gate").locale("en_US")
                    .rootGroup("Probe", p -> {
                        p.field("Error", io.github.mbackschat.a12.dm.modelkit.FieldKind.STRING);
                        // Every precision on the SAME complete format, so the format gate is satisfied on all
                        // four and precision is the only variable this grid moves.
                        for (DatePrecision precision : DatePrecision.values()) {
                            p.dateField(precision.name(),
                                    d -> d.format("dd.MM.yyyy").datePrecision(precision));
                        }
                        // A domain-named peer of the DAY_OPTIONAL grid cell, for the guide snippet: a
                        // manufacturing date is often known only to the month.
                        p.dateField("ManufacturedOn",
                                d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.DAY_OPTIONAL));
                    })
                    .build().toJson());

    private static Calculation source(DatePrecision precision) {
        return MODEL.field("/Probe/" + precision.name()).value();
    }

    @Test
    void eachUnitIsRefusedExactlyWhenThePrecisionDoesNotGuaranteeItsComponent() {
        Map<String, String> refused = new LinkedHashMap<>();
        for (DatePrecision precision : DatePrecision.values()) {
            for (var entry : DATE_UNITS.entrySet()) {
                DateExtraction.Unit unit = entry.getKey();
                String cell = precision.name() + "/" + unit.name();
                boolean shouldRefuse = !GUARANTEED.get(precision).contains(entry.getValue());
                if (shouldRefuse) {
                    var thrown = assertThrows(IllegalArgumentException.class,
                            () -> Dsl.dateExtraction(MODEL, unit, source(precision)), cell);
                    refused.put(cell, thrown.getMessage());
                } else {
                    assertDoesNotThrow(() -> Dsl.dateExtraction(MODEL, unit, source(precision)), cell);
                }
            }
        }
        // The grid is 4 precisions x 4 units; FULL admits all 4, DAY_OPTIONAL 3, MONTH_OPTIONAL 1,
        // YEAR_OPTIONAL 0 -> 8 admitted, 8 refused.
        assertEquals(8, refused.size(), "refused cells: " + refused.keySet());
        refused.forEach((cell, message) ->
                assertTrue(message.contains("MVK_INVALID_DATE_TYPE"),
                        cell + " must report the measured code, was: " + message));
    }

    @Test
    void aFullPrecisionFieldIsUnaffectedByThisGate() {
        for (DateExtraction.Unit unit : DATE_UNITS.keySet()) {
            assertDoesNotThrow(() -> Dsl.dateExtraction(MODEL, unit, source(DatePrecision.FULL)),
                    unit.name());
        }
    }

    @Test
    void theRefusalNamesTheFieldAndTheComponentItCannotGuarantee() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateExtraction(MODEL, DateExtraction.Unit.DAY, source(DatePrecision.DAY_OPTIONAL)));
        String message = thrown.getMessage();
        assertTrue(message.contains("/Probe/DAY_OPTIONAL"), message);
        assertTrue(message.contains("DAY_OPTIONAL"), message);
        assertTrue(message.toLowerCase(java.util.Locale.ROOT).contains("day"), message);
    }

    /** The guide's worked example: the same operand answers the month and refuses the day. */
    @Test
    void aPartiallyKnownDateAnswersOnlyWhatItsPrecisionGuarantees() {
        BoundModel model = MODEL;
        // tag::date-precision-gate[]
        // The SECOND gate on the same factory, and independent of the first: the declared FORMAT says which
        // components a value is written with, the declared PRECISION says which of them may be unknown. A
        // DAY_OPTIONAL date is written `dd.MM.yyyy` and still admits `00.02.2024`.
        Calculation manufactured = model.field("/Probe/ManufacturedOn").value();
        Calculation month = Dsl.dateExtraction(model, DateExtraction.Unit.MONTH, manufactured);

        // DayFromDate over the same operand is MVK_INVALID_DATE_TYPE: the precision admits an unknown day.
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateExtraction(model, DateExtraction.Unit.DAY, manufactured));
        // end::date-precision-gate[]
        assertEquals(DateExtraction.Unit.MONTH, ((DateExtraction) month).unit());
    }

    /**
     * An operand whose precision cannot be resolved is DELEGATED, never refused — the same stance the format
     * gate takes, and for the same reason: there is nothing to read.
     */
    @Test
    void anOperandWithNoResolvableFieldIsDelegated() {
        assertDoesNotThrow(() -> Dsl.dateExtraction(MODEL, DateExtraction.Unit.DAY,
                Dsl.today().expression()));
        assertDoesNotThrow(() -> Dsl.dateExtraction(MODEL, DateExtraction.Unit.DAY,
                Dsl.addDays(MODEL.date("/Probe/" + DatePrecision.DAY_OPTIONAL.name()), 1).expression()));
    }
}
