package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The typed peer of {@code validate.laws.TemporalExtractorComponentLawsTest}: which extractor may sit in
 * which {@code Date(...)} and {@code Time(...)} component.
 *
 * <p><b>Intrinsic</b>, so it lives in {@link DateConstruction}'s own constructor rather than on
 * {@link BoundModel} — which extractor occupies which slot is visible from the operands the node holds, with
 * no model involved (API-FINDINGS AF153). That is the opposite placement from the precision and component-set
 * gates, and the distinction is the point.
 *
 * <p>The two refusals partition on <b>family admissibility</b> versus <b>position</b>, which is measured and
 * was not what a single "the unit must match its slot" rule predicts.
 */
class TemporalConstructionExtractorGateTest {

    private static final BoundModel M = BoundModel.ofJson(
            DocumentModelDraft.named("temporal-construction-gate").locale("en_US")
                    .rootGroup("Doc", d -> d
                            .field("Error", FieldKind.STRING)
                            .field("Count", FieldKind.NUMBER)
                            .dateField("OrderDate", f -> f.format("dd.MM.yyyy"))
                            .timeField("PickupTime", t -> { }))
                    .build().toJson());

    private static Calculation extraction(DateExtraction.Unit unit) {
        String source = switch (unit) {
            case HOURS, MINUTES, SECONDS -> "/Doc/PickupTime";
            default -> "/Doc/OrderDate";
        };
        return new DateExtraction(unit, M.field(source).value());
    }

    private static final Calculation NUMBER = M.field("/Doc/Count").value();

    @Test
    void eachPositionAdmitsItsMatchingExtractor() {
        assertDoesNotThrow(() -> new DateConstruction(
                extraction(DateExtraction.Unit.DAY),
                extraction(DateExtraction.Unit.MONTH),
                extraction(DateExtraction.Unit.YEAR)));
    }

    /** The liveness control: non-extractor components are untouched by this gate. */
    @Test
    void plainComponentsAreAdmitted() {
        assertDoesNotThrow(() -> new DateConstruction(NUMBER, NUMBER, NUMBER));
        assertDoesNotThrow(() -> new DateConstruction(NUMBER, NUMBER, NUMBER, Optional.of(NUMBER)));
    }

    /** Right family, wrong position. */
    @Test
    void aMisplacedDateExtractorIsTheMismatchCode() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> new DateConstruction(
                extraction(DateExtraction.Unit.MONTH), NUMBER, NUMBER));
        assertTrue(thrown.getMessage().contains("MVK_INVALID_DATE_OR_TIME_EXTRACT_OP"), thrown::getMessage);
    }

    /** Admitted by no position at all — the other code, and the one A8 named. */
    @Test
    void quarterAndTheTimeFamilyAreNotAdmissibleAtAll() {
        for (DateExtraction.Unit unit : new DateExtraction.Unit[] {
                DateExtraction.Unit.QUARTER, DateExtraction.Unit.HOURS,
                DateExtraction.Unit.MINUTES, DateExtraction.Unit.SECONDS}) {
            var thrown = assertThrows(IllegalArgumentException.class,
                    () -> new DateConstruction(extraction(unit), NUMBER, NUMBER), unit.name());
            assertTrue(thrown.getMessage().contains("MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED"),
                    thrown::getMessage);
        }
    }

    /**
     * The four-component form's own code covers its two YEAR positions — century and decade — and only
     * those. The kernel row this mirrors measures the century slot; the other three are measured in
     * {@code TemporalExtractorComponentLawsTest}, and day/month turned out to be admitted.
     */
    @Test
    void theFourComponentFormRefusesAnExtractorInEitherYearPosition() {
        var century = assertThrows(IllegalArgumentException.class, () -> new DateConstruction(
                NUMBER, NUMBER, extraction(DateExtraction.Unit.YEAR), Optional.of(NUMBER)));
        assertTrue(century.getMessage().contains("MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS"),
                century::getMessage);
        var decade = assertThrows(IllegalArgumentException.class, () -> new DateConstruction(
                NUMBER, NUMBER, NUMBER, Optional.of(extraction(DateExtraction.Unit.YEAR))));
        assertTrue(decade.getMessage().contains("MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS"),
                decade::getMessage);
    }

    /**
     * THE OVER-REJECTION CONTROL. The gate once threw the four-part code at every position, which made a
     * legal model unauthorable — and, since the read path converts inbound through these same records,
     * unreadable. The day and month slots keep the ordinary rules even in the four-part form.
     */
    @Test
    void theFourComponentFormKeepsTheOrdinaryRulesAtDayAndMonth() {
        assertDoesNotThrow(() -> new DateConstruction(
                extraction(DateExtraction.Unit.DAY), NUMBER, NUMBER, Optional.of(NUMBER)));
        assertDoesNotThrow(() -> new DateConstruction(
                NUMBER, extraction(DateExtraction.Unit.MONTH), NUMBER, Optional.of(NUMBER)));
        var misplaced = assertThrows(IllegalArgumentException.class, () -> new DateConstruction(
                extraction(DateExtraction.Unit.MONTH), NUMBER, NUMBER, Optional.of(NUMBER)));
        assertTrue(misplaced.getMessage().contains("MVK_INVALID_DATE_OR_TIME_EXTRACT_OP"), misplaced::getMessage);
        var crossFamily = assertThrows(IllegalArgumentException.class, () -> new DateConstruction(
                extraction(DateExtraction.Unit.HOURS), NUMBER, NUMBER, Optional.of(NUMBER)));
        assertTrue(crossFamily.getMessage().contains("MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED"),
                crossFamily::getMessage);
    }

    // --- Time(...) — the same two-code partition, on its own carrier ----------------------------

    private static TimeConstruction time(Calculation... components) {
        return new TimeConstruction(java.util.List.of(components));
    }

    @Test
    void eachTimeSlotAdmitsItsOwnUnit() {
        assertDoesNotThrow(() -> time(extraction(DateExtraction.Unit.HOURS),
                extraction(DateExtraction.Unit.MINUTES), extraction(DateExtraction.Unit.SECONDS)));
        assertDoesNotThrow(() -> time(NUMBER, NUMBER, NUMBER));
        assertDoesNotThrow(() -> time(extraction(DateExtraction.Unit.HOURS)));   // a shorter prefix
    }

    @Test
    void aMisplacedTimeExtractorIsTheMismatchCode() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> time(extraction(DateExtraction.Unit.MINUTES), NUMBER, NUMBER));
        assertTrue(thrown.getMessage().contains("MVK_INVALID_DATE_OR_TIME_EXTRACT_OP"), thrown::getMessage);
    }

    @Test
    void theDateFamilyIsAdmittedAtNoTimePosition() {
        for (DateExtraction.Unit unit : new DateExtraction.Unit[] {
                DateExtraction.Unit.DAY, DateExtraction.Unit.MONTH,
                DateExtraction.Unit.YEAR, DateExtraction.Unit.QUARTER}) {
            var thrown = assertThrows(IllegalArgumentException.class,
                    () -> time(extraction(unit), NUMBER, NUMBER), unit.name());
            assertTrue(thrown.getMessage().contains("MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED"),
                    thrown::getMessage);
        }
    }
}
