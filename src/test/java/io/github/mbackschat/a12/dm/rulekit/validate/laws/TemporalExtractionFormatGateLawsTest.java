package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * §6 — which extraction a temporal operand answers is decided by ONE rule: the operand's declared format
 * must carry the component the unit asks for. Every cell below is the real kernel's verdict, and the
 * expectation is computed from that rule rather than transcribed, so the kernel adjudicates the mechanism
 * and not just the values.
 *
 * <p>Three cells discriminate it from the readings it would otherwise be confused with:
 *
 * <ul>
 * <li><b>Not the kind.</b> Every field here is DATE-kind, and {@code HoursFromTime} is ADMITTED on the one
 *     declared {@code HH:mm:ss}. No kind-keyed gate produces that.
 * <li><b>Not the shape.</b> {@code DayFromDate} is refused on {@code yyyy-MM} and admitted on
 *     {@code yyyy-MM-dd} — {@link io.github.mbackschat.a12.dm.rulekit.dsl.TemporalFormats}' three-valued
 *     {@code Shape} calls both DATE, so a shape-keyed gate cannot state this law and a component-keyed one
 *     must. That is the finding this test exists to pin.
 * <li><b>Case-sensitive components.</b> {@code MinutesFromTime} is refused on {@code MM} and admitted on
 *     {@code HH:mm:ss}: {@code M} is the month, {@code m} the minute.
 * </ul>
 *
 * <p><b>Denominator.</b> The format axis is the twelve strings the kernel admits over the published candidate
 * universe; that set is derived and owned by {@code TemporalFormatVocabularyGuardTest}, and this law asserts
 * every one of them is still declarable so a vocabulary change cannot silently shrink the grid. The kind is
 * held fixed at DATE across the grid so the format is the only variable; kind-independence of the DECLARATION
 * is [KF206](KERNEL-FINDINGS.md), and the DATE_FRAGMENT rows below show the same component rule deciding the
 * OPERAND gate on a fourth kind — one this kit's temporal helper does not currently count as temporal.
 */
class TemporalExtractionFormatGateLawsTest {

    /** The kernel-admitted temporal format vocabulary. Owner: {@code TemporalFormatVocabularyGuardTest}. */
    // VOCABULARY-GRID-START
    private static final List<String> FORMATS = List.of(
            "HH:mm:ss", "MM", "MM-dd", "MMdd", "dd.MM.yyyy", "ddMM", "yyyy", "yyyy-MM",
            "yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm:ss", "yyyyMM", "yyyyMMdd");
    // VOCABULARY-GRID-END

    /** Each extraction unit and the format component letter it requires. */
    private static final Map<String, Character> COMPONENT_UNITS = Map.of(
            "DayFromDate", 'd',
            "MonthFromDate", 'M',
            "YearFromDate", 'y',
            "QuarterFromDate", 'M',
            "HoursFromTime", 'H',
            "MinutesFromTime", 'm',
            "SecondsFromTime", 's');

    /** Ordered for a readable failure report; {@link #COMPONENT_UNITS} carries the rule. */
    private static final List<String> UNIT_ORDER = List.of("DayFromDate", "MonthFromDate", "YearFromDate",
            "QuarterFromDate", "HoursFromTime", "MinutesFromTime", "SecondsFromTime");

    private static final Set<Character> DATE_COMPONENTS = Set.of('y', 'M', 'd');
    private static final Set<Character> TIME_COMPONENTS = Set.of('H', 'm', 's');

    private static final String REFUSED = "MVK_WRONG_DATE_FORMAT_FOR_OP";

    private static final String MODEL = DocumentModelDraft.named("temporal-format-gate").locale("en_US")
            .rootGroup("Probe", p -> {
                p.field("Error", FieldKind.STRING);
                for (String format : FORMATS) {
                    p.dateField(field(format), d -> d.format(format));
                }
                p.dateFragmentField("Fragment", f -> f.fragmentFormat("yyyy"));
            })
            .build().toJson();

    @Test
    void everyUnitIsAdmittedExactlyWhenTheDeclaredFormatCarriesItsComponent() {
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (String unit : UNIT_ORDER) {
            for (String format : FORMATS) {
                String row = unit + " / " + format;
                expected.put(row, componentsOf(format).contains(COMPONENT_UNITS.get(unit))
                        ? KernelLaws.ADMITTED : REFUSED);
                actual.put(row, verdict(unit + "(" + field(format) + ") > 0"));
            }
        }
        assertEquals(expected, actual);
    }

    /** The split family needs BOTH halves present, which only the ISO datetime carries. */
    @Test
    void theSplitFamilyNeedsADateComponentAndATimeComponent() {
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (String format : FORMATS) {
            Set<Character> components = componentsOf(format);
            boolean complete = !intersection(components, DATE_COMPONENTS).isEmpty()
                    && !intersection(components, TIME_COMPONENTS).isEmpty();
            String verdict = complete ? KernelLaws.ADMITTED : REFUSED;

            expected.put("DateFromDateTime / " + format, verdict);
            actual.put("DateFromDateTime / " + format,
                    verdict("DateFromDateTime(" + field(format) + ") == Today"));

            expected.put("TimeFromDateTime / " + format, verdict);
            actual.put("TimeFromDateTime / " + format, verdict("TimeFromDateTime(" + field(format)
                    + ") == TimeFromDateTime(" + field(format) + ")"));
        }
        assertEquals(expected, actual);
    }

    /**
     * A DATE_FRAGMENT declared {@code yyyy} answers exactly what that format's components allow — the same
     * rule as the grid above, on a kind the grid does not contain. It is measured because
     * {@code TemporalFormats.isTemporal} counts only DATE/TIME/DATE_TIME, so any later gate reading the
     * format has a fourth kind to account for.
     */
    @Test
    void aDateFragmentObeysTheSameComponentRule() {
        assertEquals(
                Map.of("YearFromDate", KernelLaws.ADMITTED,
                        "MonthFromDate", REFUSED,
                        "SecondsFromTime", REFUSED,
                        "DateFromDateTime", REFUSED),
                Map.of("YearFromDate", verdict("YearFromDate(Fragment) > 0"),
                        "MonthFromDate", verdict("MonthFromDate(Fragment) > 0"),
                        "SecondsFromTime", verdict("SecondsFromTime(Fragment) > 0"),
                        "DateFromDateTime", verdict("DateFromDateTime(Fragment) == Today")));
    }

    /** The grid is only as complete as its format axis, so a vocabulary change must not shrink it silently. */
    @Test
    void everyFormatOnTheAxisIsStillDeclarable() {
        Map<String, String> declarable = new LinkedHashMap<>();
        for (String format : FORMATS) {
            declarable.put(format, verdict("FieldFilled(" + field(format) + ")"));
        }
        assertEquals(FORMATS.stream().collect(Collectors.toMap(f -> f, f -> KernelLaws.ADMITTED,
                (a, b) -> a, LinkedHashMap::new)), declarable);
    }

    /** A field name for a format string, which carries separators a name may not. */
    private static String field(String format) {
        return "F" + Integer.toString(Math.abs(format.hashCode()), 36);
    }

    /** The component letters a format declares, skipping single-quoted literal sections. */
    private static Set<Character> componentsOf(String format) {
        Set<Character> components = new LinkedHashSet<>();
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '\'') {
                i++;
                while (i < format.length() && format.charAt(i) != '\'') {
                    i++;
                }
            } else if (DATE_COMPONENTS.contains(c) || TIME_COMPONENTS.contains(c)) {
                components.add(c);
            }
        }
        return components;
    }

    private static Set<Character> intersection(Set<Character> a, Set<Character> b) {
        Set<Character> both = new LinkedHashSet<>(a);
        both.retainAll(b);
        return both;
    }

    private static String verdict(String expression) {
        return verdict(MODEL, expression);
    }

    /**
     * The grid holds the kind at DATE so the format is its only variable, which leaves the OPERAND gate's
     * kind-independence resting on [KF206](KERNEL-FINDINGS.md)'s measurement of the DECLARATION tier. These
     * rows close that gap for the cells other tests rely on: the same format decides the same way on a TIME
     * and a DATE_TIME field, so a consumer asserting a refusal over one of those is not relying on transitivity.
     */
    @Test
    void theSameFormatDecidesTheSameWayOnTheOtherTemporalKinds() {
        String model = DocumentModelDraft.named("kind-axis").locale("en_US")
                .rootGroup("Probe", p -> p
                        .field("Error", FieldKind.STRING)
                        .timeField("Clock", f -> f.format("HH:mm:ss"))
                        .dateTimeField("Stamp", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .dateField("ClockAsDate", f -> f.format("HH:mm:ss")))
                .build().toJson();

        assertEquals(
                Map.of("TimeFromDateTime/TIME", REFUSED,
                        "DateFromDateTime/TIME", REFUSED,
                        "SecondsFromTime/TIME", KernelLaws.ADMITTED,
                        "TimeFromDateTime/DATE_TIME", KernelLaws.ADMITTED,
                        "DateFromDateTime/DATE_TIME", KernelLaws.ADMITTED,
                        "HoursFromTime/DATE_TIME", KernelLaws.ADMITTED,
                        "SecondsFromTime/DATE-declared-clock", KernelLaws.ADMITTED),
                Map.of("TimeFromDateTime/TIME",
                        verdict(model, "TimeFromDateTime(Clock) == TimeFromDateTime(Clock)"),
                        "DateFromDateTime/TIME", verdict(model, "DateFromDateTime(Clock) == Today"),
                        "SecondsFromTime/TIME", verdict(model, "SecondsFromTime(Clock) > 0"),
                        "TimeFromDateTime/DATE_TIME",
                        verdict(model, "TimeFromDateTime(Stamp) == TimeFromDateTime(Stamp)"),
                        "DateFromDateTime/DATE_TIME", verdict(model, "DateFromDateTime(Stamp) == Today"),
                        "HoursFromTime/DATE_TIME", verdict(model, "HoursFromTime(Stamp) > 0"),
                        "SecondsFromTime/DATE-declared-clock",
                        verdict(model, "SecondsFromTime(ClockAsDate) > 0")));
    }

    /**
     * A field whose declaration is genuinely ABSENT answers exactly what its kind's default format's
     * components allow, so the one rule covers that case too — which a gate resolving an absent declaration
     * through {@code TemporalFormats.defaultFormat} needs before it may refuse anything.
     *
     * <p>The fixture strips the key after emission, and asserts the strip applied. That is not ceremony:
     * modelkit MATERIALIZES the kind default into the JSON, so a field built with an empty configurer is
     * fully declared and a fixture built that way measures the declared path while reading as though it
     * measured the absent one ({@code core.ModelIndexTemporalFormatTest} pins that behaviour).
     */
    @Test
    void anAbsentDeclarationBehavesAsItsKindsDefault() {
        String model = DocumentModelDraft.named("absent-declaration").locale("en_US")
                .rootGroup("Probe", p -> p
                        .field("Error", FieldKind.STRING)
                        .dateField("D", d -> { })
                        .timeField("T", d -> { })
                        .dateTimeField("DT", d -> { }))
                .build().toJson()
                .replaceAll("\\s*\"format\"\\s*:\\s*\"[^\"]*\"\\s*,?", "");
        assertTrue(!model.contains("\"format\""), "the corruption under test did not apply");
        Map<String, String> defaults = Map.of(
                "D", "yyyy-MM-dd", "T", "HH:mm:ss", "DT", "yyyy-MM-dd'T'HH:mm:ss");

        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (String field : List.of("D", "T", "DT")) {
            Set<Character> components = componentsOf(defaults.get(field));
            for (String unit : UNIT_ORDER) {
                String row = field + " / " + unit;
                expected.put(row, components.contains(COMPONENT_UNITS.get(unit))
                        ? KernelLaws.ADMITTED : REFUSED);
                actual.put(row, verdict(model, unit + "(" + field + ") > 0"));
            }
            String row = field + " / DateFromDateTime";
            expected.put(row, !intersection(components, DATE_COMPONENTS).isEmpty()
                    && !intersection(components, TIME_COMPONENTS).isEmpty() ? KernelLaws.ADMITTED : REFUSED);
            actual.put(row, verdict(model, "DateFromDateTime(" + field + ") == Today"));
        }
        assertEquals(expected, actual);
    }

    private static String verdict(String model, String expression) {
        ValidationResult result = KernelLaws.checkModel(model, "/Probe", "../Error",
                "FieldFilled(Error) And " + expression);
        return result.ok() ? KernelLaws.ADMITTED : result.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .distinct().sorted().collect(Collectors.joining(","));
    }
}
