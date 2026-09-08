package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * KERNEL-FINDINGS §6 — the SIBLING SWEEP of [KF1007](#kf1007)'s class, over the operands the format gate
 * delegates because they are not field reads.
 *
 * <p>[KF1007](#kf1007) found the three point-in-time CONSTANTS wrongly delegated: each answers its KIND's
 * default format, so a time-family extraction over a DATE-kinded one is refused while our gate stayed silent.
 * {@code Dsl.declaredFormatOf} resolves only a field read, so of {@code Calculation}'s 42 arms it answers for
 * five and delegates the rest — and the constants were the one delegated case anybody had looked at. This
 * sweeps the temporal-valued remainder, which the root-cause rule asks for before the class is called closed.
 *
 * <p>Each shape is asked a DATE unit, which anything carrying a date admits, and an HOUR, which only something
 * carrying a time half admits — the pair that separates "no format is read" from "the kind's default is".
 *
 * <p><b>Measured: the kind-default rule holds for all five derived arms, in BOTH directions.</b> Each behaves
 * exactly as a field declaring no format of its kind — the DATE arms admit date units and refuse the hour, the
 * DATE_TIME arm admits both, and the TIME arm inverts it. So our gate was delegating five more operands of
 * [KF1007](#kf1007)'s class. Three further shapes render plausibly and the kernel's PARSER rejects them in an
 * extraction slot, so they can never reach the gate and delegating them costs nothing.
 *
 * <p>The trailing {@code And FieldFilled(N)} is safe here, unlike in an iteration law: nothing in this table
 * turns on a positive presence guard ([KF1011](#kf1011)).
 */
class DerivedTemporalOperandFormatLawsTest {

    private static final String WRONG_FORMAT = "MVK_WRONG_DATE_FORMAT_FOR_OP";
    /** The kernel parser's refusal, which is a claim about the GRAMMAR rather than about a format. */
    private static final String UNPARSED = "MVK_UNEXPECTED_TOKEN";

    /**
     * NO base year is declared here, and that is load-bearing: a declared base year SUPPLEMENTS the YEAR
     * component, so it admits {@code YearFromDate} over a format carrying no year and silently confounds every
     * date-unit row. Caught by this table contradicting {@code TemporalExtractionFormatGateLawsTest}'s
     * CI-green grid on the identical field construction. The one row that needs a base year takes its own
     * model below.
     */
    private static final String MODEL = DocumentModelDraft.named("derived-temporal").locale("en_US")
            .rootGroup("Cover", c -> c
                    .field("N", FieldKind.NUMBER)
                    .dateField("FiledOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("ClosedOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("Approx", d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL))
                    .dateTimeField("Stamp", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .timeField("PickupTime", d -> d.format("HH:mm:ss"))
                    // The same FORMAT on a DATE kind, which is how A3's grid isolates format from kind.
                    .dateField("ClockAsDate", d -> d.format("HH:mm:ss"))
                    .dateRangeField("Window", r -> r.format("yyyy-MM-dd").separator("/"))
                    .group("Rows", r -> r.repeatable(20)
                            .dateField("RowDate", d -> d.format("yyyy-MM-dd"))))
            .build().toJson();

    /** Identical but for the declaration the BaseYear constant needs. */
    private static final String WITH_BASE_YEAR = DocumentModelDraft.named("derived-temporal-by").locale("en_US")
            .baseYear(2024)
            .rootGroup("Cover", c -> c
                    .field("N", FieldKind.NUMBER)
                    .dateField("FiledOn", d -> d.format("yyyy-MM-dd")))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);
    private static final DslRenderer RENDERER = new DslRenderer();

    /** The temporal-valued derived shapes this DSL can build, by the KIND each carries. */
    private static Map<String, Calculation> shapes() {
        var shapes = new LinkedHashMap<String, Calculation>();
        shapes.put("DateShift DATE (AddDays)", Dsl.addDays(M.date("/Cover/FiledOn"), 1).expression());
        shapes.put("DateShift DATE_TIME (AddHours)", Dsl.addHours(M.dateTime("/Cover/Stamp"), 1).expression());
        shapes.put("DateTimePart DATE (DateFromDateTime)",
                Dsl.dateFromDateTime(M.dateTime("/Cover/Stamp")).expression());
        shapes.put("DateTimePart TIME (TimeFromDateTime)",
                Dsl.timeFromDateTime(M.dateTime("/Cover/Stamp")).expression());
        shapes.put("DateRangeBound DATE (StartOfDateRange)",
                Dsl.startOfDateRange(M.dateRange("/Cover/Window")).expression());
        // These three render plausibly and the kernel's PARSER rejects them in an extraction slot, so they can
        // never reach the format gate from condition text — delegating them costs nothing.
        shapes.put("FirstFilledValue (date)",
                Dsl.firstFilledDate(M.flatten("/Cover/Rows*/RowDate")).expression());
        shapes.put("DateListAggregate (MaxValue)",
                M.latestOf(M.field("/Cover/FiledOn"), M.field("/Cover/ClosedOn")).expression());
        shapes.put("ValueAsDate", M.valueAsDateFirstDay(M.field("/Cover/Approx")).expression());
        return shapes;
    }

    private static String verdictOf(String condition) {
        var result = KernelLaws.checkModel(MODEL, "/Cover", "../N", condition + " > 1 And FieldFilled(N)");
        if (result.ok()) {
            return KernelLaws.ADMITTED;
        }
        return result.diagnostics().stream()
                .map(d -> d.code().orElse("NO_CODE"))
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining("+"));
    }

    private static String verdict(String unit, Calculation source) {
        return verdictOf(unit + "(" + RENDERER.render(source) + ")");
    }

    /** One table, so a kernel shift shows every cell rather than aborting at the first. */
    @Test
    void everyDerivedTemporalOperandAnswersItsKindsDefault() {
        var actual = new LinkedHashMap<String, String>();
        shapes().forEach((name, source) -> {
            actual.put(name + " / YearFromDate", verdict("YearFromDate", source));
            actual.put(name + " / HoursFromTime", verdict("HoursFromTime", source));
        });

        // THE CONTROLS, whose verdicts [KF1007] already fixes: a DATE field and a DATE-kinded constant. The
        // field is named in TEXT because rendering a bare field read emits a bracketed absolute path, which is
        // not what an extraction slot takes — a rendering artefact, not a kernel fact.
        actual.put("a yyyy-MM-dd FIELD / HoursFromTime", verdictOf("HoursFromTime(FiledOn)"));
        var byResult = KernelLaws.checkModel(WITH_BASE_YEAR, "/Cover", "../N",
                "HoursFromTime(BaseYear) > 1 And FieldFilled(N)");
        actual.put("BaseYear constant / HoursFromTime", byResult.ok() ? KernelLaws.ADMITTED
                : byResult.diagnostics().stream().map(d -> d.code().orElse("NO_CODE")).distinct().sorted()
                        .collect(java.util.stream.Collectors.joining("+")));
        actual.put("a DATE_TIME field / HoursFromTime", verdictOf("HoursFromTime(Stamp)"));
        // THE DISCRIMINATOR for the TIME row above: a TIME FIELD, whose HH:mm:ss carries no year. If the
        // field refuses YearFromDate where the derived TIME operand admits it, the kind default does NOT
        // describe that arm and it must stay delegated.
        actual.put("a TIME field / YearFromDate", verdictOf("YearFromDate(PickupTime)"));
        // ONE VARIABLE from the row above: the same HH:mm:ss format carried by a DATE kind instead. If that
        // refuses, the date units are not checked against a TIME-kinded operand at all, and a purely
        // format-keyed gate over-rejects one.
        actual.put("a DATE field declaring HH:mm:ss / YearFromDate", verdictOf("YearFromDate(ClockAsDate)"));

        Map<String, String> expected = new LinkedHashMap<>();
        // A DATE-valued derived operand behaves exactly as a date FIELD does: date units yes, hour no.
        expected.put("DateShift DATE (AddDays) / YearFromDate", KernelLaws.ADMITTED);
        expected.put("DateShift DATE (AddDays) / HoursFromTime", WRONG_FORMAT);
        expected.put("DateShift DATE_TIME (AddHours) / YearFromDate", KernelLaws.ADMITTED);
        expected.put("DateShift DATE_TIME (AddHours) / HoursFromTime", KernelLaws.ADMITTED);
        expected.put("DateTimePart DATE (DateFromDateTime) / YearFromDate", KernelLaws.ADMITTED);
        expected.put("DateTimePart DATE (DateFromDateTime) / HoursFromTime", WRONG_FORMAT);
        expected.put("DateTimePart TIME (TimeFromDateTime) / YearFromDate", WRONG_FORMAT);
        expected.put("DateTimePart TIME (TimeFromDateTime) / HoursFromTime", KernelLaws.ADMITTED);
        expected.put("DateRangeBound DATE (StartOfDateRange) / YearFromDate", KernelLaws.ADMITTED);
        expected.put("DateRangeBound DATE (StartOfDateRange) / HoursFromTime", WRONG_FORMAT);
        for (String unparseable : new String[] {
                "FirstFilledValue (date)", "DateListAggregate (MaxValue)", "ValueAsDate"}) {
            expected.put(unparseable + " / YearFromDate", UNPARSED);
            expected.put(unparseable + " / HoursFromTime", UNPARSED);
        }
        expected.put("a yyyy-MM-dd FIELD / HoursFromTime", WRONG_FORMAT);
        expected.put("BaseYear constant / HoursFromTime", WRONG_FORMAT);
        expected.put("a DATE_TIME field / HoursFromTime", KernelLaws.ADMITTED);
        expected.put("a TIME field / YearFromDate", WRONG_FORMAT);
        expected.put("a DATE field declaring HH:mm:ss / YearFromDate", WRONG_FORMAT);

        assertEquals(expected, actual);
    }
}
