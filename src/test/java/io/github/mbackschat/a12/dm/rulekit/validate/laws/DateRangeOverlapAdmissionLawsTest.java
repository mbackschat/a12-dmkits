package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.RangeYearInterpretation;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;

/**
 * KERNEL-FINDINGS §6 — the two admission gates the DateRange <b>overlap</b> operators apply, which are
 * distinct from each other and from the equality gate `DateRangeComparabilityLawsTest` owns ([KF200]).
 *
 * <ul>
 *   <li><b>Year class, over the whole authored list.</b> Every operand must carry the year or none may; a mix
 *       is refused {@code MVK_DATE_WITH_AND_WITHOUT_YEAR}. A declared <b>Base Year lifts it</b> by completing
 *       each yearless operand. Within one year class, differing component sets are admitted outright — so
 *       overlap does <em>not</em> inherit equality's component-set gate.</li>
 *   <li><b>An {@code interpretationOfYear} declaration is refused outright</b> as an overlap operand
 *       ({@code MVK_INVALID_DATE_RANGE_FORMAT}), on either side, with or without a Base Year — and the two
 *       gates must not be conflated: this one is a declaration-format refusal that no Base Year lifts.</li>
 * </ul>
 *
 * <p><b>The confinement row is what stops the second gate reading as a blanket ban:</b> the same
 * interpretation-bearing declaration is admitted by {@code StartOfDateRange}, {@code EndOfDateRange} and
 * {@code ==}, so the refusal belongs to the two overlap operators alone.
 *
 * <p>Each test asserts its whole verdict table at once, so a kernel upgrade shows the entire matrix rather
 * than aborting at its first divergence.
 */
class DateRangeOverlapAdmissionLawsTest {

    private static final String GROUP = "/Cover";
    private static final String ERROR_FIELD = "../Ref";
    private static final String MIXED_YEAR = "MVK_DATE_WITH_AND_WITHOUT_YEAR";
    private static final String BAD_RANGE_FORMAT = "MVK_INVALID_DATE_RANGE_FORMAT";
    private static final String ADMITTED = "ADMITTED";

    /** No Base Year — the year-class gate is live here. */
    private static final String PLAIN = cover(null);

    /** Byte-identical except for the Base Year, which is the whole point of the pair. */
    private static final String BASED = cover(2024);

    private static final BoundModel M = BoundModel.ofJson(PLAIN);

    // ---- the year-class gate --------------------------------------------------------------------

    /**
     * The gate is on the YEAR, not on the component set: two year-bearing operands with different components
     * are admitted, two yearless ones are admitted, and only the mix is refused.
     */
    @Test
    void bothOverlapOperatorsGateTheWholeListByYearClass() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("year + year, different components", verdict(PLAIN, overlap("ExactWindow", "YearWindow")));
        verdicts.put("yearless + yearless, different components",
                verdict(PLAIN, overlap("MonthWindow", "DayWindow")));
        verdicts.put("year + yearless", verdict(PLAIN, overlap("ExactWindow", "MonthWindow")));
        verdicts.put("yearless + year (the other order)", verdict(PLAIN, overlap("MonthWindow", "ExactWindow")));
        verdicts.put("three operands, one of them mixed",
                verdict(PLAIN, Dsl.dateRangesOverlap(range("ExactWindow"), range("YearWindow"), range("MonthWindow"))));

        assertEquals(Map.of(
                "year + year, different components", ADMITTED,
                "yearless + yearless, different components", ADMITTED,
                "year + yearless", MIXED_YEAR,
                "yearless + year (the other order)", MIXED_YEAR,
                "three operands, one of them mixed", MIXED_YEAR), verdicts);
    }

    /** A declared Base Year completes every yearless operand, so the same mixes are admitted. */
    @Test
    void aDeclaredBaseYearLiftsTheYearClassGate() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("year + yearless", verdict(BASED, overlap("ExactWindow", "MonthWindow")));
        verdicts.put("yearless + year", verdict(BASED, overlap("MonthWindow", "ExactWindow")));
        verdicts.put("three operands, one of them mixed",
                verdict(BASED, Dsl.dateRangesOverlap(range("ExactWindow"), range("YearWindow"), range("MonthWindow"))));

        assertEquals(Map.of(
                "year + yearless", ADMITTED,
                "yearless + year", ADMITTED,
                "three operands, one of them mixed", ADMITTED), verdicts);
    }

    /** For the plural operator the gate spans the scalar and the list TOGETHER, not each side separately. */
    @Test
    void thePluralOperatorGatesTheScalarAndTheListTogether() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("year scalar, year list", verdict(PLAIN, atLeastOne("ExactWindow", "RowExactWindow")));
        verdicts.put("yearless scalar, yearless list",
                verdict(PLAIN, atLeastOne("MonthWindow", "RowMonthWindow")));
        verdicts.put("year scalar, yearless list", verdict(PLAIN, atLeastOne("ExactWindow", "RowMonthWindow")));
        verdicts.put("yearless scalar, year list", verdict(PLAIN, atLeastOne("MonthWindow", "RowExactWindow")));
        verdicts.put("…and the Base Year lifts that too",
                verdict(BASED, atLeastOne("ExactWindow", "RowMonthWindow")));

        assertEquals(Map.of(
                "year scalar, year list", ADMITTED,
                "yearless scalar, yearless list", ADMITTED,
                "year scalar, yearless list", MIXED_YEAR,
                "yearless scalar, year list", MIXED_YEAR,
                "…and the Base Year lifts that too", ADMITTED), verdicts);
    }

    // ---- the interpretationOfYear gate ------------------------------------------------------------

    /**
     * A declaration carrying {@code interpretationOfYear} is refused as an overlap operand on either side and
     * in either model, while its interpretation-FREE twin — same format, same separator, same year class — is
     * admitted in the same model. The twin is what makes this a property of the declaration rather than of
     * the format pair.
     */
    @Test
    void anInterpretingDeclarationIsRefusedAsAnOverlapOperandAndNoBaseYearLiftsIt() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("left operand", verdict(PLAIN, overlap("Interpreting", "PlainDdMm")));
        verdicts.put("right operand", verdict(PLAIN, overlap("PlainDdMm", "Interpreting")));
        verdicts.put("interpretation-free control", verdict(PLAIN, overlap("PlainDdMm", "MonthWindow")));
        verdicts.put("with a Base Year declared", verdict(BASED, overlap("Interpreting", "PlainDdMm")));
        verdicts.put("plural, on the scalar side",
                verdict(PLAIN, atLeastOne("Interpreting", "RowDdMm")));
        verdicts.put("plural, on the list side",
                verdict(PLAIN, atLeastOne("PlainDdMm", "RowInterpreting")));

        assertEquals(Map.of(
                "left operand", BAD_RANGE_FORMAT,
                "right operand", BAD_RANGE_FORMAT,
                "interpretation-free control", ADMITTED,
                "with a Base Year declared", BAD_RANGE_FORMAT,
                "plural, on the scalar side", BAD_RANGE_FORMAT,
                "plural, on the list side", BAD_RANGE_FORMAT), verdicts);
    }

    /** …and the gate is the two overlap operators' alone: the SAME declaration is fine everywhere else. */
    @Test
    void theSameDeclarationIsAdmittedByBoundExtractionAndByEquality() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("StartOfDateRange", verdict(PLAIN,
                Dsl.startOfDateRange(M.dateRange(GROUP + "/Interpreting"))
                        .eq(Dsl.endOfDateRange(M.dateRange(GROUP + "/Interpreting")))));
        verdicts.put("EndOfDateRange", verdict(PLAIN,
                Dsl.endOfDateRange(M.dateRange(GROUP + "/Interpreting"))
                        .ne(Dsl.startOfDateRange(M.dateRange(GROUP + "/Interpreting")))));
        verdicts.put("range equality", verdict(PLAIN,
                M.dateRange(GROUP + "/Interpreting").eq(M.dateRange(GROUP + "/PlainDdMm"))));

        assertEquals(Map.of(
                "StartOfDateRange", ADMITTED,
                "EndOfDateRange", ADMITTED,
                "range equality", ADMITTED), verdicts);
    }

    // ---- fixture -----------------------------------------------------------------------------------

    /** The overlap operators take a FIELD operand, so a typed {@code DateRangeRef} is unwrapped here. */
    private static io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef range(String name) {
        return M.dateRange(GROUP + "/" + name).field();
    }

    private static Condition overlap(String left, String right) {
        return Dsl.dateRangesOverlap(range(left), range(right));
    }

    private static Condition atLeastOne(String scalar, String rowField) {
        return Dsl.atLeastOneDateRangeOverlaps(range(scalar), M.flatten(GROUP + "/Periods*/" + rowField));
    }

    /**
     * The error field rides a {@code FieldFilled(Ref)} conjunct because the kernel's own error-field gate
     * ({@code MVK_ERROR_FIELD_NOT_REFERENCED}) would otherwise decide every admitted row before the gate under
     * test does — and it did, on the first run of this table.
     */
    private static String verdict(String json, Condition condition) {
        Condition guarded = Dsl.fieldFilled(M.field(GROUP + "/Ref")).and(condition);
        ValidationResult r = new RuleValidator().check(json,
                new DraftRule(GROUP, "OverlapProbe", ERROR_FIELD,
                        new io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer().render(guarded),
                        "OVERLAP", "ERROR"));
        return r.ok() ? ADMITTED
                : r.diagnostics().stream().map(d -> d.code().orElse("(no code)"))
                        .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * A cover model carrying both year classes, two component sets within each, and an
     * {@code interpretationOfYear} declaration beside its interpretation-free twin — the pair the second gate
     * is measured on. The repeatable {@code Periods} supplies the plural operator's list side.
     */
    private static String cover(Integer baseYear) {
        DocumentModelDraft.Builder b = DocumentModelDraft.named("cover").locale("en_US");
        if (baseYear != null) {
            b.baseYear(baseYear);
        }
        return b.rootGroup("Cover", cover -> cover
                        .stringField("Ref", f -> { })
                        .dateRangeField("ExactWindow", r -> r.format("yyyy-MM-dd").separator("/"))
                        .dateRangeField("YearWindow", r -> r.format("yyyy").separator("/"))
                        .dateRangeField("MonthWindow", r -> r.format("MM").separator("/"))
                        .dateRangeField("DayWindow", r -> r.format("MM-dd").separator("/"))
                        .dateRangeField("Interpreting", r -> r.format("dd.MM").separator("-")
                                .interpretationOfYear(RangeYearInterpretation.TO))
                        .dateRangeField("PlainDdMm", r -> r.format("dd.MM").separator("-"))
                        .group("Periods", periods -> periods.repeatable(20)
                                .dateRangeField("RowExactWindow", r -> r.format("yyyy-MM-dd").separator("/"))
                                .dateRangeField("RowMonthWindow", r -> r.format("MM").separator("/"))
                                .dateRangeField("RowDdMm", r -> r.format("dd.MM").separator("-"))
                                .dateRangeField("RowInterpreting", r -> r.format("dd.MM").separator("-")
                                        .interpretationOfYear(RangeYearInterpretation.TO))))
                .build().toJson();
    }
}
