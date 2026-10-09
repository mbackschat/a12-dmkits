package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §6 — <b>a declared Base Year reaches the temporal EXTRACTOR gate</b>, so
 * {@code YearFromDate} is admitted on a pure {@code HH:mm:ss} field once the model declares one. Our record
 * had the Base Year supplementing the year at the comparison gate ([KF193]-adjacent) and the aggregate gate
 * ([KF193]) and never at this one; [KF250] measured the extractor refusal on a base-year-free model, which is
 * the other side of the same axis.
 *
 * <p><b>The control is within-model: the same draft built twice, differing in one builder call.</b> Comparing
 * two models would move every variable nobody thought about; here only {@code baseYear} moves, and the
 * ordinary-DATE extractor row is admitted on both so the gate is visibly armed either way.
 *
 * <p><b>The completed-period difference is the second row</b>, and it is a different unit family from the one
 * [KF211] carries: {@code DifferenceInYears} takes a TIME-declared {@code yyyy-MM-dd} field beside an ordinary
 * DATE, while the same operator on a pure clock field is refused. So the difference family keys on the
 * declared format's component set, exactly as the direct extractors do.
 *
 * <p><b>What this does NOT say.</b> Admission is not a value: the runtime half — the extractor is admitted
 * under a Base Year and then decides nothing — is the differential's business and is locked in
 * {@code adapter.laws.BaseYearExtractorAdmissionDiffTest}, not here.
 *
 * <p>Reported by a12-kernel-lean as {@code SPEC-2026-09-05-14} (narrowed to these two rows after they found
 * the wider claim already held in [KF211]); measured here on our own fixture.
 *
 * <p>Own domain (an insurance cover term).
 */
class TemporalExtractorBaseYearGateLawsTest {

    private static final String GROUP = "/Term";
    private static final String ERROR_REL = "../Holder";
    private static final String WRONG_COMPONENTS = "MVK_WRONG_DATE_FORMAT_FOR_OP";

    private static String model(Integer baseYear) {
        DocumentModelDraft.Builder builder = DocumentModelDraft.named("cover-term").locale("en_US");
        if (baseYear != null) {
            builder.baseYear(baseYear);
        }
        return builder
                .rootGroup("Term", t -> t
                        .stringField("Holder", s -> { })
                        .timeField("RenewalClock", c -> c.format("HH:mm:ss"))
                        .numberField("RenewalHour", n -> n.fractionDigits(0).min("0").max("23"))
                        .timeField("TimeAsDate", c -> c.format("yyyy-MM-dd"))
                        .dateField("StartOn", d -> d.format("yyyy-MM-dd"))
                        .dateField("EndOn", d -> d.format("yyyy-MM-dd")))
                .build().toJson();
    }

    private static final String NO_BASE_YEAR = model(null);
    private static final String WITH_BASE_YEAR = model(2024);

    private static String verdict(String json, String condition) {
        ValidationResult r = KernelLaws.checkModel(json, GROUP, ERROR_REL, "FieldFilled(Holder) And " + condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /** One variable moves: the model's declared base year. */
    @Test
    void aDeclaredBaseYearAdmitsTheYearExtractorOnAPureClockField() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("YearFromDate on a pure clock, NO base year", verdict(NO_BASE_YEAR,
                "YearFromDate(RenewalClock) == 2024"));
        actual.put("YearFromDate on a pure clock, base year 2024", verdict(WITH_BASE_YEAR,
                "YearFromDate(RenewalClock) == 2024"));
        actual.put("YearFromDate on an ordinary DATE, NO base year — armed control", verdict(NO_BASE_YEAR,
                "YearFromDate(StartOn) == 2024"));
        actual.put("YearFromDate on an ordinary DATE, base year 2024 — armed control", verdict(WITH_BASE_YEAR,
                "YearFromDate(StartOn) == 2024"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("YearFromDate on a pure clock, NO base year", WRONG_COMPONENTS);
        expected.put("YearFromDate on a pure clock, base year 2024", KernelLaws.ADMITTED);
        expected.put("YearFromDate on an ordinary DATE, NO base year — armed control", KernelLaws.ADMITTED);
        expected.put("YearFromDate on an ordinary DATE, base year 2024 — armed control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the year gate is 'component present OR a Base Year supplies one'");
    }

    /**
     * A CONSTRUCTED time meets the same gate as a clock field (a12-kernel-lean SPEC-2026-10-08-01): its year is
     * admitted exactly when the model declares a Base Year, built directly or recovered from a date-time.
     */
    @Test
    void aConstructedTimeMeetsTheSameYearGate() {
        String built = "YearFromDate(Time(RenewalHour)) == 1970";
        String recovered = "YearFromDate(TimeFromDateTime(DateTime(StartOn, Time(RenewalHour)))) == 1970";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a built time, NO base year", verdict(NO_BASE_YEAR, built));
        actual.put("a built time, base year 2024", verdict(WITH_BASE_YEAR, built));
        actual.put("a time recovered from a date-time, NO base year", verdict(NO_BASE_YEAR, recovered));
        actual.put("a time recovered from a date-time, base year 2024", verdict(WITH_BASE_YEAR, recovered));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a built time, NO base year", WRONG_COMPONENTS);
        expected.put("a built time, base year 2024", KernelLaws.ADMITTED);
        expected.put("a time recovered from a date-time, NO base year", WRONG_COMPONENTS);
        expected.put("a time recovered from a date-time, base year 2024", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a constructed time's year is admitted only under a Base Year");
    }

    /** The declared FORMAT decides the operand family; the declared KIND is not read. */
    @Test
    void theExtractorAndDifferenceFamiliesKeyOnTheDeclaredFormatAndNotTheKind() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DifferenceInYears over a TIME-declared date beside a DATE", verdict(NO_BASE_YEAR,
                "DifferenceInYears(TimeAsDate, StartOn) == 0"));
        actual.put("DifferenceInYears over two ordinary DATEs — control", verdict(NO_BASE_YEAR,
                "DifferenceInYears(EndOn, StartOn) == 0"));
        actual.put("DifferenceInYears over a pure clock beside a DATE", verdict(NO_BASE_YEAR,
                "DifferenceInYears(RenewalClock, StartOn) == 0"));
        actual.put("YearFromDate on a TIME-declared date", verdict(NO_BASE_YEAR,
                "YearFromDate(TimeAsDate) == 2024"));
        actual.put("HoursFromTime on a TIME-declared date", verdict(NO_BASE_YEAR,
                "HoursFromTime(TimeAsDate) == 0"));
        actual.put("HoursFromTime on a pure clock — control", verdict(NO_BASE_YEAR,
                "HoursFromTime(RenewalClock) == 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DifferenceInYears over a TIME-declared date beside a DATE", KernelLaws.ADMITTED);
        expected.put("DifferenceInYears over two ordinary DATEs — control", KernelLaws.ADMITTED);
        expected.put("DifferenceInYears over a pure clock beside a DATE", WRONG_COMPONENTS);
        expected.put("YearFromDate on a TIME-declared date", KernelLaws.ADMITTED);
        expected.put("HoursFromTime on a TIME-declared date", WRONG_COMPONENTS);
        expected.put("HoursFromTime on a pure clock — control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "each kind sits on both sides of both outcomes");
    }
}
