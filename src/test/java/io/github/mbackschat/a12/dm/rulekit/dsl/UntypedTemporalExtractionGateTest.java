package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The typed peer of {@code validate.laws.TemporalExtractionFormatGateLawsTest}: the same component rule the
 * kernel was measured to apply, stated by the two factories a caller holding a bare {@link Calculation}
 * reaches — an AST reader rather than someone writing Java against the typed handles, which already carry
 * their format.
 *
 * <p>The denominator is the whole measured grid rather than a sample: every unit against every admitted
 * format, expected computed from the rule, so a gate that is right on the cells someone happened to write
 * down and wrong elsewhere fails here.
 */
class UntypedTemporalExtractionGateTest {

    // VOCABULARY-GRID-START
    private static final List<String> FORMATS = List.of(
            "HH:mm:ss", "MM", "MM-dd", "MMdd", "dd.MM.yyyy", "ddMM", "yyyy", "yyyy-MM",
            "yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm:ss", "yyyyMM", "yyyyMMdd");
    // VOCABULARY-GRID-END

    private static final Map<DateExtraction.Unit, TemporalFormats.Component> REQUIRED = Map.of(
            DateExtraction.Unit.DAY, TemporalFormats.Component.DAY,
            DateExtraction.Unit.MONTH, TemporalFormats.Component.MONTH,
            DateExtraction.Unit.YEAR, TemporalFormats.Component.YEAR,
            DateExtraction.Unit.QUARTER, TemporalFormats.Component.MONTH,
            DateExtraction.Unit.HOURS, TemporalFormats.Component.HOUR,
            DateExtraction.Unit.MINUTES, TemporalFormats.Component.MINUTE,
            DateExtraction.Unit.SECONDS, TemporalFormats.Component.SECOND);

    private static String name(String format) {
        return "F" + Integer.toString(Math.abs(format.hashCode()), 36);
    }

    private static final BoundModel MODEL = BoundModel.ofJson(
            DocumentModelDraft.named("untyped-extraction-gate").locale("en_US")
                    .rootGroup("Probe", p -> {
                        p.field("Error", FieldKind.STRING);
                        p.field("Text", FieldKind.STRING);
                        for (String format : FORMATS) {
                            p.dateField(name(format), d -> d.format(format));
                        }
                        p.dateFragmentField("Fragment", f -> f.fragmentFormat("yyyy"));
                        p.dateRangeField("Window", r -> r.format("dd.MM.yyyy").separator("-"));
                        p.group("Rows", r -> r.repeatable(9).indexField("Key")
                                .field("Key", FieldKind.STRING)
                                .dateField("Month", f -> f.format("yyyy-MM")));
                        p.dateField("Undeclared", d -> { });
                    })
                    .build().toJson());

    private static Calculation source(String field) {
        return MODEL.field("/Probe/" + field).value();
    }

    @Test
    void eachUnitIsRefusedExactlyWhenTheDeclaredFormatLacksItsComponent() {
        Map<String, Boolean> expected = new LinkedHashMap<>();
        Map<String, Boolean> actual = new LinkedHashMap<>();
        for (DateExtraction.Unit unit : DateExtraction.Unit.values()) {
            for (String format : FORMATS) {
                String row = unit + " / " + format;
                expected.put(row, !TemporalFormats.componentsOf(format).contains(REQUIRED.get(unit)));
                actual.put(row, refuses(() -> Dsl.dateExtraction(MODEL, unit, source(name(format)))));
            }
        }
        assertEquals(expected, actual);
    }

    @Test
    void theSplitFamilyIsRefusedUnlessBothHalvesArePresent() {
        Map<String, Boolean> expected = new LinkedHashMap<>();
        Map<String, Boolean> actual = new LinkedHashMap<>();
        for (DateTimePart.Unit unit : DateTimePart.Unit.values()) {
            for (String format : FORMATS) {
                String row = unit + " / " + format;
                expected.put(row, !format.equals("yyyy-MM-dd'T'HH:mm:ss"));
                actual.put(row, refuses(() -> Dsl.dateTimePart(MODEL, unit, source(name(format)))));
            }
        }
        assertEquals(expected, actual);
    }

    /** A refusal cites the kernel code the measurement observed, so it cannot pass for some other verdict. */
    @Test
    void aRefusalCitesTheMeasuredKernelCode() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateExtraction(MODEL, DateExtraction.Unit.DAY, source(name("yyyy-MM"))));
        assertTrue(refusal.getMessage().contains("MVK_WRONG_DATE_FORMAT_FOR_OP"), refusal::getMessage);
        assertTrue(refusal.getMessage().contains("yyyy-MM"), refusal::getMessage);
    }

    /** A fragment is in the rule's domain: {@code yyyy} answers the year and nothing below it. */
    @Test
    void aDateFragmentIsGatedByItsFragmentFormat() {
        BoundModel model = MODEL;
        // tag::untyped-temporal-gate[]
        // A caller reading a parsed AST holds a bare Calculation, which carries a kind and no format. These
        // factories resolve the operand's DECLARED FORMAT through the model and refuse exactly what the
        // kernel refuses — a `yyyy` fragment answers YearFromDate and nothing below it.
        Calculation fragment = model.field("/Probe/Fragment").value();
        Calculation year = Dsl.dateExtraction(model, DateExtraction.Unit.YEAR, fragment);

        // MonthFromDate over the same operand is MVK_WRONG_DATE_FORMAT_FOR_OP: `yyyy` carries no month.
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateExtraction(model, DateExtraction.Unit.MONTH, fragment));
        // end::untyped-temporal-gate[]
        assertEquals(DateExtraction.Unit.YEAR, ((DateExtraction) year).unit());
    }

    /**
     * A field built with an empty configurer is DECLARED, not undeclared — modelkit materializes the kind
     * default into the JSON — so this row exercises the ordinary declared path under a misleading name and is
     * kept only to say so. The genuine absent-declaration path is
     * {@link #anAbsentDeclarationFallsBackToTheKindDefault}.
     */
    @Test
    void anEmptyConfigurerStillDeclaresTheKindDefault() {
        assertEquals("yyyy-MM-dd", MODEL.temporalFormat("/Probe/Undeclared"));
        assertDoesNotThrow(() -> Dsl.dateExtraction(MODEL, DateExtraction.Unit.DAY, source("Undeclared")));
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateExtraction(MODEL, DateExtraction.Unit.HOURS, source("Undeclared")));
    }

    /**
     * The fallback branch, reachable only on a model some other author wrote: with the key genuinely absent
     * the gate resolves the kind's default, which the kernel was measured to apply (KF1001).
     */
    @Test
    void anAbsentDeclarationFallsBackToTheKindDefault() {
        String stripped = DocumentModelDraft.named("absent-declaration").locale("en_US")
                .rootGroup("Probe", p -> p.field("Error", FieldKind.STRING).dateField("D", d -> { }))
                .build().toJson()
                .replaceAll("\\s*\"format\"\\s*:\\s*\"[^\"]*\"\\s*,?", "");
        assertTrue(!stripped.contains("\"format\""), "the corruption under test did not apply");
        BoundModel model = BoundModel.ofJson(stripped);
        assertEquals(null, model.temporalFormat("/Probe/D"));

        Calculation date = model.field("/Probe/D").value();
        assertDoesNotThrow(() -> Dsl.dateExtraction(model, DateExtraction.Unit.DAY, date));
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateExtraction(model, DateExtraction.Unit.HOURS, date));
    }

    /**
     * Delegated, not refused — and the shapes that still reach this branch are the ones the kernel's own
     * PARSER rejects in an extraction slot ({@code validate.laws.DerivedTemporalOperandFormatLawsTest}), so
     * delegating them costs nothing: the kernel never gets a chance to disagree.
     *
     * <p>This row used a DATE SHIFT until 2026-09-07, which was wrong in the expensive direction: a shift
     * answers its kind's default and the kernel refuses an hour over it, so the row pinned a miss as correct.
     * Every derived temporal shape the grammar admits here is now resolved, which is why the example had to
     * move to one the grammar does not.
     */
    @Test
    void aShapeTheGrammarRejectsAsASourceIsDelegatedRatherThanRefused() {
        assertDoesNotThrow(() -> Dsl.dateExtraction(MODEL, DateExtraction.Unit.HOURS,
                Dsl.firstFilledDate(MODEL.flatten("/Probe/Rows*/Month")).expression()));
    }

    /**
     * The point-in-time constants take the kind default, so the gate treats them exactly as it treats a field
     * declaring no format. {@code Now} is DATE_TIME and therefore carries the hour; the two DATE-kind
     * constants do not.
     */
    @Test
    void aPointInTimeConstantAnswersItsKindsDefaultFormat() {
        assertDoesNotThrow(() -> Dsl.dateExtraction(MODEL, DateExtraction.Unit.DAY,
                Dsl.today().expression()));
        assertThrows(IllegalArgumentException.class, () -> Dsl.dateExtraction(MODEL,
                DateExtraction.Unit.HOURS, Dsl.today().expression()));
        assertThrows(IllegalArgumentException.class, () -> Dsl.dateExtraction(MODEL,
                DateExtraction.Unit.HOURS, Dsl.baseYear().expression()));
        assertDoesNotThrow(() -> Dsl.dateExtraction(MODEL, DateExtraction.Unit.HOURS,
                Dsl.now().expression()));
    }

    /**
     * The gate falls back to a kind default only for the kinds whose default was measured, and a fragment is
     * not one — which is safe because a fragment without its format cannot be BOUND at all. That is the fact
     * this locks: it is what makes the fallback unreachable for that kind, so if the reader ever becomes
     * lenient the branch becomes live and needs a measurement rather than a default.
     */
    @Test
    void aFragmentCannotBeBoundWithoutItsDeclaration() {
        String stripped = DocumentModelDraft.named("fragment-without-format").locale("en_US")
                .rootGroup("Probe", p -> p
                        .field("Error", FieldKind.STRING)
                        .dateFragmentField("Frag", f -> f.fragmentFormat("yyyy")))
                .build().toJson()
                .replaceAll("\\s*\"formatOfFragment\"\\s*:\\s*\"[^\"]*\"\\s*", "");
        assertTrue(!stripped.contains("formatOfFragment"), "the corruption under test did not apply");

        assertThrows(RuntimeException.class, () -> BoundModel.ofJson(stripped));
    }

    /**
     * An INDEX-SELECTED cell is gated by the declaration of the field it selects, because a selection cannot
     * change one that belongs to the field. Delegated until it was measured rather than assumed.
     */
    @Test
    void anIndexSelectedCellIsGatedByItsFieldsDeclaration() {
        Calculation indexed = new SemanticIndexValue(MODEL.field("/Probe/Rows/Month"), "K1");
        assertDoesNotThrow(() -> Dsl.dateExtraction(MODEL, DateExtraction.Unit.MONTH, indexed));
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateExtraction(MODEL, DateExtraction.Unit.DAY, indexed));
        assertTrue(refusal.getMessage().contains("MVK_WRONG_DATE_FORMAT_FOR_OP"), refusal::getMessage);
        assertTrue(refusal.getMessage().contains("yyyy-MM"), refusal::getMessage);
    }

    /**
     * A DATE_RANGE declares a format like any temporal kind, but a span carries no component to extract, so
     * the kernel reports {@code MVK_NO_DATE} for it and not the format code. Reading the declaration before
     * checking the kind would answer the wrong one — {@code SecondsFromTime} over a {@code dd.MM.yyyy} range
     * lacks the second, which is a format-shaped refusal for a kind the family cannot read at all.
     */
    @Test
    void aDateRangeReportsTheKindRefusalRatherThanAFormatOne() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateExtraction(MODEL, DateExtraction.Unit.SECONDS, source("Window")));
        assertTrue(refusal.getMessage().contains("MVK_NO_DATE"), refusal::getMessage);
        assertTrue(!refusal.getMessage().contains("MVK_WRONG_DATE_FORMAT_FOR_OP"), refusal::getMessage);
    }

    /**
     * A model the operand does not come from is a WIRING bug and must not arrive as the refusal's own
     * exception type, or a caller catching it reads a mistake about the model as a verdict about the rule.
     */
    @Test
    void anOperandFromAnotherModelIsAWiringErrorNotAVerdict() {
        BoundModel other = BoundModel.ofJson(DocumentModelDraft.named("other").locale("en_US")
                .rootGroup("Other", p -> p.field("Error", FieldKind.STRING)).build().toJson());
        assertThrows(IllegalStateException.class,
                () -> Dsl.dateExtraction(other, DateExtraction.Unit.YEAR, source(name("yyyy"))));
    }

    /**
     * The non-temporal refusal stays where it already lives, on the record — this gate adds the format axis
     * and must not restate the kind one, or the two could disagree.
     */
    @Test
    void aNonTemporalOperandStillReportsTheKindRefusal() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateExtraction(MODEL, DateExtraction.Unit.YEAR, source("Text")));
        assertTrue(refusal.getMessage().contains("MVK_NO_DATE"), refusal::getMessage);
    }

    private static boolean refuses(Runnable build) {
        try {
            build.run();
            return false;
        } catch (IllegalArgumentException refusal) {
            return true;
        }
    }
}
