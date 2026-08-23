package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §6 — <b>a selected DateRange endpoint carries exactly its declaration's date components</b>,
 * and the two families of consumer are gated separately with different diagnostics ([KF201]).
 *
 * <ul>
 *   <li><b>Extraction itself is unconditional.</b> {@code StartOfDateRange} / {@code EndOfDateRange} are
 *       admitted on every admitted declaration, a yearless profile in an unconfigured model included.</li>
 *   <li><b>A numeric extractor is gated on the COMPONENT SET</b> — admitted where the set exposes the
 *       component, {@code MVK_WRONG_DATE_FORMAT_FOR_OP} where it does not, with quarter counting as the
 *       month. A declared <b>Base Year supplements YEAR</b> into the set, and only the year.</li>
 *   <li><b>A comparison is gated by the ordinary DIRECT temporal rule instead</b>, with its own
 *       {@code MVK_INVALID_COMPARE_TO_DATE}. The two gates disagree on the same declaration, which is the
 *       whole reason they must not be stated as one: an unconfigured {@code MM} range exposes its month to an
 *       extractor and is refused against a complete date.</li>
 * </ul>
 *
 * <p>Each grid is asserted whole, so a kernel upgrade shows the entire matrix rather than its first
 * divergence. The refused cells are as load-bearing as the admitted ones: an implementation that completes
 * the endpoint to a date before gating would expose every component and admit them all.
 */
class DateRangeBoundComponentLawsTest {

    private static final String GROUP = "/Cover";
    private static final String ERROR_FIELD = "../Ref";
    private static final String WRONG_FORMAT = "MVK_WRONG_DATE_FORMAT_FOR_OP";
    private static final String NOT_COMPARABLE = "MVK_INVALID_COMPARE_TO_DATE";
    private static final String ADMITTED = "ADMITTED";

    /** Every admitted DateRange (format, separator) pair that carries a distinct component set. */
    private static final List<String> PROFILES =
            List.of("Full", "Iso", "YearOnly", "YearMonth", "MonthOnly", "MonthDay", "DayMonth");

    private static final String PLAIN = cover(null);
    private static final String BASED = cover(2024);
    private static final BoundModel M = BoundModel.ofJson(PLAIN);

    // ---- extraction is unconditional --------------------------------------------------------------

    /** Both extractors, every profile, no Base Year — the row that says the gate is not on the extraction. */
    @Test
    void bothBoundExtractorsAreAdmittedOnEveryDeclarationWithNoBaseYear() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (String profile : PROFILES) {
            verdicts.put("start " + profile, verdict(PLAIN, start(profile).eq(start(profile))));
            verdicts.put("end " + profile, verdict(PLAIN, end(profile).eq(end(profile))));
        }

        assertEquals(PROFILES.stream()
                .flatMap(p -> java.util.stream.Stream.of("start " + p, "end " + p))
                .collect(Collectors.toMap(Function.identity(), k -> ADMITTED)), verdicts);
    }

    // ---- the component gate -------------------------------------------------------------------------

    /**
     * The extractor grid with NO Base Year: admitted exactly where the declaration retains the component.
     * Quarter rides the month, so it tracks {@code MonthFromDate} cell for cell.
     */
    @Test
    void aNumericExtractorIsAdmittedOnlyWhereTheDeclarationRetainsItsComponent() {
        assertEquals(Map.ofEntries(
                Map.entry("Year of Full", ADMITTED), Map.entry("Month of Full", ADMITTED),
                Map.entry("Day of Full", ADMITTED), Map.entry("Quarter of Full", ADMITTED),
                Map.entry("Year of Iso", ADMITTED), Map.entry("Month of Iso", ADMITTED),
                Map.entry("Day of Iso", ADMITTED), Map.entry("Quarter of Iso", ADMITTED),
                Map.entry("Year of YearOnly", ADMITTED), Map.entry("Month of YearOnly", WRONG_FORMAT),
                Map.entry("Day of YearOnly", WRONG_FORMAT), Map.entry("Quarter of YearOnly", WRONG_FORMAT),
                Map.entry("Year of YearMonth", ADMITTED), Map.entry("Month of YearMonth", ADMITTED),
                Map.entry("Day of YearMonth", WRONG_FORMAT), Map.entry("Quarter of YearMonth", ADMITTED),
                Map.entry("Year of MonthOnly", WRONG_FORMAT), Map.entry("Month of MonthOnly", ADMITTED),
                Map.entry("Day of MonthOnly", WRONG_FORMAT), Map.entry("Quarter of MonthOnly", ADMITTED),
                Map.entry("Year of MonthDay", WRONG_FORMAT), Map.entry("Month of MonthDay", ADMITTED),
                Map.entry("Day of MonthDay", ADMITTED), Map.entry("Quarter of MonthDay", ADMITTED),
                Map.entry("Year of DayMonth", WRONG_FORMAT), Map.entry("Month of DayMonth", ADMITTED),
                Map.entry("Day of DayMonth", ADMITTED), Map.entry("Quarter of DayMonth", ADMITTED)),
                extractorGrid(PLAIN));
    }

    /**
     * …and a declared Base Year supplements <b>YEAR</b> into every set — the year column flips and no other
     * cell moves. {@code Day of MonthOnly} staying refused in the same configured model is the live control:
     * without it the row is satisfied by a Base Year that simply admits everything.
     */
    @Test
    void aDeclaredBaseYearSupplementsTheYearComponentAndNothingElse() {
        Map<String, String> plain = extractorGrid(PLAIN);
        Map<String, String> based = extractorGrid(BASED);

        Map<String, String> moved = new LinkedHashMap<>();
        plain.forEach((cell, was) -> {
            if (!was.equals(based.get(cell))) {
                moved.put(cell, was + " → " + based.get(cell));
            }
        });

        assertEquals(Map.of(
                "Year of MonthOnly", WRONG_FORMAT + " → " + ADMITTED,
                "Year of MonthDay", WRONG_FORMAT + " → " + ADMITTED,
                "Year of DayMonth", WRONG_FORMAT + " → " + ADMITTED), moved,
                "only the YEAR cells of the yearless profiles may move");
        assertEquals(WRONG_FORMAT, based.get("Day of MonthOnly"),
                "the control: a Base Year supplies no DAY, so this stays refused in the configured model");
    }

    // ---- the comparison gate is a different one ------------------------------------------------------

    /**
     * The same unconfigured `MM` declaration whose month an extractor reads is refused against a complete
     * date — the pair that proves the extractor gate and the comparison gate are not one rule.
     *
     * <p><b>And "yearless" is the discriminator, not "incomplete".</b> A YEAR-ONLY endpoint is admitted
     * against a complete date in the same unconfigured model: the direct gate asks only for matching year
     * presence, so a `yyyy` range clears it while `MM` does not. Reading the refusal as "any profile the
     * complete date does not match" groups those two together and rejects a kernel-legal comparison.
     */
    @Test
    void theComparisonConsumersApplyTheOrdinaryDirectTemporalGateInstead() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("month extractor on the same declaration",
                verdict(PLAIN, Dsl.monthFromDate(start("MonthOnly")).ge(1)));
        verdicts.put("that endpoint against a complete date",
                verdict(PLAIN, start("MonthOnly").eq(M.date(GROUP + "/ExactDate"))));
        verdicts.put("a year-only endpoint against a complete date",
                verdict(PLAIN, start("YearOnly").eq(M.date(GROUP + "/ExactDate"))));
        verdicts.put("a year-bearing endpoint against a complete date",
                verdict(PLAIN, start("Iso").eq(M.date(GROUP + "/ExactDate"))));

        assertEquals(Map.of(
                "month extractor on the same declaration", ADMITTED,
                "that endpoint against a complete date", NOT_COMPARABLE,
                "a year-only endpoint against a complete date", ADMITTED,
                "a year-bearing endpoint against a complete date", ADMITTED), verdicts);
    }

    /** Endpoint against endpoint follows that same direct rule — year presence, not component identity. */
    @Test
    void twoSelectedEndpointsCompareByYearPresenceRatherThanByComponentIdentity() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("MM vs MM-dd", verdict(PLAIN, start("MonthOnly").eq(start("MonthDay"))));
        verdicts.put("MM vs yyyy", verdict(PLAIN, start("MonthOnly").eq(start("YearOnly"))));
        verdicts.put("yyyy vs yyyy-MM-dd", verdict(PLAIN, start("YearOnly").eq(start("Iso"))));

        assertEquals(Map.of(
                "MM vs MM-dd", ADMITTED,
                "MM vs yyyy", NOT_COMPARABLE,
                "yyyy vs yyyy-MM-dd", ADMITTED), verdicts);
    }


    // ---- the computation surface, and the locus ------------------------------------------------------

    /**
     * The numeric component is admitted in a Number COMPUTATION on the same terms as in a condition — the
     * component gate travels with the operand rather than belonging to the validation surface — and it is a
     * <b>non-expandable scale-0</b> operation, so a fractional target is refused.
     */
    @Test
    void theComponentGateAndTheScaleZeroRuleBothHoldInAComputation() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("month of an unconfigured MM range → integer target",
                computationVerdict(PLAIN, "WholeCount", Dsl.monthFromDate(start("MonthOnly"))));
        verdicts.put("year of the same range → integer target",
                computationVerdict(PLAIN, "WholeCount", Dsl.yearFromDate(start("MonthOnly"))));
        verdicts.put("month → FRACTIONAL target",
                computationVerdict(PLAIN, "FractionalCount", Dsl.monthFromDate(start("MonthOnly"))));

        assertEquals(Map.of(
                "month of an unconfigured MM range → integer target", ADMITTED,
                "year of the same range → integer target", WRONG_FORMAT,
                "month → FRACTIONAL target", "MVK_INVALID_COMPARE_DEC_PLACES"), verdicts);
    }

    /**
     * A ROW endpoint joins the measured rule-locus list, and at it <b>the locus gate reports before the
     * component gate</b>: the same operand whose profile withholds the requested component draws the locus
     * diagnostic from a non-iterating error field and the component one from an iterating error field, on one
     * model differing only in where the error field sits. A starred occurrence is refused outright, so an
     * iterating rule is the only legal way to read a row endpoint's component.
     */
    @Test
    void theLocusGateReportsBeforeTheComponentGate() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("row operand, non-iterating error field",
                verdictAt(PLAIN, GROUP, ERROR_FIELD, Dsl.yearFromDate(rowStart()).ge(0)));
        verdicts.put("row operand, iterating error field",
                verdictAt(PLAIN, GROUP + "/Periods", "../RowRef", Dsl.yearFromDate(rowStart()).ge(0)));
        verdicts.put("…and its admitted component from the same iterating locus",
                rowVerdict(PLAIN, Dsl.monthFromDate(rowStart()).ge(0)));
        // A STARRED bound is authored as a raw string on purpose: the typed surface has no starred
        // DateRangeRef, which is the builder making an illegal state unrepresentable. The kernel's own
        // refusal is what this row records.
        verdicts.put("a starred occurrence", rawVerdict(PLAIN, GROUP, ERROR_FIELD,
                "FieldFilled(Ref) And MonthFromDate(StartOfDateRange(Periods*/RowMonth)) >= 0"));

        assertEquals(Map.of(
                "row operand, non-iterating error field", "MVK_NO_WILDCARD",
                "row operand, iterating error field", WRONG_FORMAT,
                "…and its admitted component from the same iterating locus", ADMITTED,
                "a starred occurrence", "MVK_NO_WILDCARDS_ALLOWED"), verdicts);
    }

    private static DateExpr rowStart() {
        return Dsl.startOfDateRange(M.dateRange(GROUP + "/Periods/RowMonth"));
    }

    /** The guard references the ROW's own field, so an iterating locus meets the error-field gate. */
    private static String rowVerdict(String json, Condition condition) {
        Condition guarded = Dsl.fieldFilled(M.field(GROUP + "/Periods/RowRef")).and(condition);
        return rawVerdict(json, GROUP + "/Periods", "../RowRef", new DslRenderer().render(guarded));
    }

    private static String rawVerdict(String json, String group, String errorField, String condition) {
        ValidationResult r = new RuleValidator().check(json,
                new DraftRule(group, "BoundProbe", errorField, condition, "BOUND", "ERROR"));
        return r.ok() ? ADMITTED
                : r.diagnostics().stream().map(d -> d.code().orElse("(no code)")).collect(Collectors.joining(","));
    }

    private static String computationVerdict(String json, String target, NumberExpr operation) {
        io.github.mbackschat.a12.dm.rulekit.dsl.Computation computation =
                M.computation(M.num(GROUP + "/" + target)).name("BoundCalc").calc(operation.expression())
                        .message(Dsl.en("The bound component does not match.")).build();
        ValidationResult r = new RuleValidator().check(json, computation.draft());
        return r.ok() ? ADMITTED
                : r.diagnostics().stream().map(d -> d.code().orElse("(no code)")).collect(Collectors.joining(","));
    }

    private static String verdictAt(String json, String group, String errorField, Condition condition) {
        Condition guarded = Dsl.fieldFilled(M.field(GROUP + "/Ref")).and(condition);
        ValidationResult r = new RuleValidator().check(json,
                new DraftRule(group, "BoundProbe", errorField, new DslRenderer().render(guarded),
                        "BOUND", "ERROR"));
        return r.ok() ? ADMITTED
                : r.diagnostics().stream().map(d -> d.code().orElse("(no code)")).collect(Collectors.joining(","));
    }

    // ---- fixture ---------------------------------------------------------------------------------------

    private static Map<String, String> extractorGrid(String json) {
        Map<String, String> grid = new LinkedHashMap<>();
        for (String profile : PROFILES) {
            grid.put("Year of " + profile, verdict(json, Dsl.yearFromDate(start(profile)).ge(0)));
            grid.put("Month of " + profile, verdict(json, Dsl.monthFromDate(start(profile)).ge(0)));
            grid.put("Day of " + profile, verdict(json, Dsl.dayFromDate(start(profile)).ge(0)));
            grid.put("Quarter of " + profile, verdict(json, Dsl.quarterFromDate(start(profile)).ge(0)));
        }
        return grid;
    }

    private static DateExpr start(String profile) {
        return Dsl.startOfDateRange(M.dateRange(GROUP + "/" + profile));
    }

    private static DateExpr end(String profile) {
        return Dsl.endOfDateRange(M.dateRange(GROUP + "/" + profile));
    }

    /** The error field rides a guard conjunct, so the kernel's error-field gate never decides a row here. */
    private static String verdict(String json, Condition condition) {
        Condition guarded = Dsl.fieldFilled(M.field(GROUP + "/Ref")).and(condition);
        ValidationResult r = new RuleValidator().check(json,
                new DraftRule(GROUP, "BoundProbe", ERROR_FIELD, new DslRenderer().render(guarded),
                        "BOUND", "ERROR"));
        return r.ok() ? ADMITTED
                : r.diagnostics().stream().map(d -> d.code().orElse("(no code)")).collect(Collectors.joining(","));
    }

    /** One field per admitted (format, separator) pair, plus a complete DATE for the comparison rows. */
    private static String cover(Integer baseYear) {
        DocumentModelDraft.Builder b = DocumentModelDraft.named("cover").locale("en_US");
        if (baseYear != null) {
            b.baseYear(baseYear);
        }
        return b.rootGroup("Cover", cover -> cover
                        .stringField("Ref", f -> { })
                        .dateField("ExactDate", d -> d.format("yyyy-MM-dd"))
                        .dateRangeField("Full", r -> r.format("dd.MM.yyyy").separator("-"))
                        .dateRangeField("Iso", r -> r.format("yyyy-MM-dd").separator("/"))
                        .dateRangeField("YearOnly", r -> r.format("yyyy").separator("/"))
                        .dateRangeField("YearMonth", r -> r.format("yyyy-MM").separator("/"))
                        .dateRangeField("MonthOnly", r -> r.format("MM").separator("/"))
                        .dateRangeField("MonthDay", r -> r.format("MM-dd").separator("/"))
                        .dateRangeField("DayMonth", r -> r.format("dd.MM").separator("-"))
                        .numberField("WholeCount", n -> { })
                        .numberField("FractionalCount", n -> n.fractionDigits(2))
                        .group("Periods", periods -> periods.repeatable(20)
                                .stringField("RowRef", f -> { })
                                .dateRangeField("RowMonth", r -> r.format("MM").separator("/"))))
                .build().toJson();
    }
}
