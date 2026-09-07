package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §6/§9 — <b>the COMPLETE denominator of equal-set, different-spelling pairs</b>, and what each
 * of the five entity-list carriers does with every one of them.
 *
 * <p><b>Why this file exists: {@link TemporalAggregateComponentSetLawsTest} said "Two exist".</b> That is the
 * only kind of row that separates the component-SET gate from the format-STRING one, and the count was assembled
 * from the pairs that work happened to use rather than derived from the vocabulary that owns them — the same
 * defect {@code KF211}'s exhaustiveness claim was corrected for twice in one day, one gate over. Derived from
 * the twelve admitted formats ({@code docs/TEMPORAL-FORMAT-VOCABULARY.md}), the real answer is
 * <b>{@value #PAIR_COUNT} pairs across three component-set families</b>: three spellings of
 * <code>{MONTH, DAY}</code>, three of <code>{YEAR, MONTH, DAY}</code>, and two of <code>{YEAR, MONTH}</code>.
 * {@link TemporalFormatVocabularyGuardTest} re-derives {@link #EQUAL_SET_PAIRS} from the live admitted
 * vocabulary on every run, so this list cannot drift back into an undercount.
 *
 * <p><b>The sharper statement the new families make available.</b> {@code MM-dd} and {@code ddMM} decode their
 * two component runs in OPPOSITE orders ({@code KF211}'s separator-free grid measures that), and the admitting
 * carriers take the pair anyway. So the gate is the <em>unordered</em> component set and nothing else — a
 * stronger claim than "reads the component set", and the one an importer needs, because it decides whether two
 * order-opposed spellings may share one operand list. {@code FirstFilledValue} refuses that very pair.
 *
 * <p><b>Reported by a12-kernel-lean as {@code SPEC-2026-09-07-04}</b>, whose carrier split
 * ({@code FirstFilledValue} and {@code FieldValuesNotUnique} read the string; the extrema and the distinct count
 * read the set) our catalog already carried correctly and in both directions. What they supply is the
 * denominator and the month-day family; the pair count below is derived here rather than taken from their entry.
 *
 * <p>Own domain (a motor-claims file).
 */
class TemporalEqualSetPairDenominatorLawsTest {

    static final int PAIR_COUNT = 7;

    private static final String GROUP = "/Claim";
    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String SET_GATE = "MVK_DATEFORMATS_NOT_COMPATIBLE";
    private static final String UNIQUENESS = "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED";
    private static final String FIRST_FILLED = "MVK_VARYING_TYPES_NOT_ALLOWED";

    /** One field per admitted spelling, plus a same-set anchor per family for the comparison carriers. */
    private record Spelling(String field, String format) { }

    /** A component-set family: its spellings, and the anchor an extremum or first-filled is compared against. */
    private record Family(String label, String anchor, List<Spelling> spellings) { }

    private static final List<Family> FAMILIES = List.of(
            new Family("{MONTH, DAY}", "MdAnchor", List.of(
                    new Spelling("Anniversary", "MM-dd"),
                    new Spelling("RenewalCompact", "MMdd"),
                    new Spelling("RenewalReversed", "ddMM"))),
            new Family("{YEAR, MONTH, DAY}", "FullAnchor", List.of(
                    new Spelling("FiledOn", "yyyy-MM-dd"),
                    new Spelling("ClosedOn", "dd.MM.yyyy"),
                    new Spelling("SealedOn", "yyyyMMdd"))),
            new Family("{YEAR, MONTH}", "MonthAnchor", List.of(
                    new Spelling("CoverFrom", "yyyy-MM"),
                    new Spelling("CoverTo", "yyyyMM"))));

    /**
     * Every unordered pair of two DIFFERENT spellings of one component set, derived from {@link #FAMILIES}
     * rather than listed. Exposed for {@link TemporalFormatVocabularyGuardTest}, which checks this derivation
     * against the vocabulary the kernel actually admits.
     */
    static final List<List<String>> EQUAL_SET_PAIRS = FAMILIES.stream()
            .flatMap(family -> {
                List<Spelling> s = family.spellings();
                return java.util.stream.IntStream.range(0, s.size()).boxed()
                        .flatMap(i -> java.util.stream.IntStream.range(i + 1, s.size())
                                .mapToObj(j -> List.of(s.get(i).format(), s.get(j).format())));
            })
            .toList();

    private static final String CLAIMS = model();

    private static String model() {
        DocumentModelDraft.Builder b = DocumentModelDraft.named("motor-claims-spellings").locale("en_US");
        return b.rootGroup("Claim", c -> {
            c.stringField("Reference", s -> { });
            for (Family family : FAMILIES) {
                // The anchor declares the family's FIRST spelling, so it doubles as the same-format control
                // partner: an admitted anchor-plus-first row is what keeps a refusal below readable as the
                // pair's and not the carrier's.
                c.dateField(family.anchor(), d -> d.format(family.spellings().getFirst().format()));
                for (Spelling spelling : family.spellings()) {
                    c.dateField(spelling.field(), d -> d.format(spelling.format()));
                }
            }
        }).build().toJson();
    }

    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(CLAIMS, GROUP, "../Reference",
                "FieldFilled(Reference) And " + condition);
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /** The five carriers, each as the condition text that puts one operand pair to it. */
    private enum Carrier {
        MIN_VALUE("MinValue", "MinValue(%1$s, %2$s) == [%3$s]"),
        MAX_VALUE("MaxValue", "MaxValue(%1$s, %2$s) == [%3$s]"),
        DIFFERENT_VALUES("NumberOfDifferentValues", "NumberOfDifferentValues(%1$s, %2$s) > 1"),
        NOT_UNIQUE("FieldValuesNotUnique", "FieldValuesNotUnique(%1$s, %2$s)"),
        FIRST_FILLED_VALUE("FirstFilledValue", "FirstFilledValue(%1$s, %2$s) == [%3$s]");

        private final String label;
        private final String template;

        Carrier(String label, String template) {
            this.label = label;
            this.template = template;
        }

        String condition(String left, String right, String anchor) {
            return String.format(template, left, right, anchor);
        }
    }

    /** What each carrier answers on an equal-set pair whose spellings differ — the split, stated per carrier. */
    private static String expected(Carrier carrier) {
        return switch (carrier) {
            case MIN_VALUE, MAX_VALUE, DIFFERENT_VALUES -> ADMITTED;
            case NOT_UNIQUE -> UNIQUENESS;
            case FIRST_FILLED_VALUE -> FIRST_FILLED;
        };
    }

    /**
     * The whole grid: every equal-set pair against every carrier, each family carrying its same-format control
     * at the same carrier. Without the controls a refusal cannot be attributed to the pair rather than to the
     * carrier refusing the family outright.
     */
    @Test
    void everyEqualSetPairSplitsTheFiveCarriersTheSameWay() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();

        for (Family family : FAMILIES) {
            List<Spelling> spellings = family.spellings();
            for (Carrier carrier : Carrier.values()) {
                String control = carrier.condition(spellings.getFirst().field(), family.anchor(), family.anchor());
                String controlKey = family.label() + " · " + carrier.label + " · same-format control";
                actual.put(controlKey, verdict(control));
                expected.put(controlKey, ADMITTED);

                for (int i = 0; i < spellings.size(); i++) {
                    for (int j = i + 1; j < spellings.size(); j++) {
                        Spelling left = spellings.get(i);
                        Spelling right = spellings.get(j);
                        String key = family.label() + " · " + carrier.label + " · "
                                + left.format() + " + " + right.format();
                        actual.put(key, verdict(
                                carrier.condition(left.field(), right.field(), family.anchor())));
                        expected.put(key, expected(carrier));
                    }
                }
            }
        }

        assertEquals(expected, actual, "the carrier split holds across the WHOLE equal-set denominator");
    }

    /**
     * The differing-SET rows, without which every admission above is equally consistent with "these carriers
     * have no temporal gate". One pair per adjacent family boundary, at the two carriers that read the set.
     */
    @Test
    void aDifferingComponentSetIsStillRefusedAtTheAdmittingCarriers() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("MinValue over {Y,M,D} beside {Y,M}",
                verdict("MinValue(FiledOn, CoverFrom) == [FullAnchor]"));
        actual.put("MinValue over {M,D} beside {Y,M,D}",
                verdict("MinValue(Anniversary, FiledOn) == [MdAnchor]"));
        actual.put("NumberOfDifferentValues over {Y,M,D} beside {Y,M}",
                verdict("NumberOfDifferentValues(FiledOn, CoverFrom) > 1"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("MinValue over {Y,M,D} beside {Y,M}", SET_GATE);
        expected.put("MinValue over {M,D} beside {Y,M,D}", SET_GATE);
        expected.put("NumberOfDifferentValues over {Y,M,D} beside {Y,M}", SET_GATE);
        assertEquals(expected, actual, "the admitting carriers read the set; they do not ignore the declaration");
    }

    /**
     * The order-opposed pair, called out on its own because it is the strongest single row in this file.
     * {@code MM-dd} reads month-first and {@code ddMM} day-first, so the two spellings decode the same four
     * digits to different dates — and the extrema take the pair regardless, which is what makes the admitting
     * gate the UNORDERED component set rather than "the same components in the same order".
     */
    @Test
    void theExtremaAdmitTwoSpellingsThatDecodeInOppositeOrders() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("MinValue(MM-dd, ddMM)", verdict("MinValue(Anniversary, RenewalReversed) == [MdAnchor]"));
        actual.put("MaxValue(MM-dd, ddMM)", verdict("MaxValue(Anniversary, RenewalReversed) == [MdAnchor]"));
        actual.put("FirstFilledValue(MM-dd, ddMM)",
                verdict("FirstFilledValue(Anniversary, RenewalReversed) == [MdAnchor]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("MinValue(MM-dd, ddMM)", ADMITTED);
        expected.put("MaxValue(MM-dd, ddMM)", ADMITTED);
        expected.put("FirstFilledValue(MM-dd, ddMM)", FIRST_FILLED);
        assertEquals(expected, actual, "component ORDER is not read by the admitting carriers either");
    }

    /** The derivation itself, so an edit that drops a spelling fails here rather than silently narrowing. */
    @Test
    void theDenominatorIsSevenPairsAcrossThreeFamilies() {
        assertEquals(PAIR_COUNT, EQUAL_SET_PAIRS.size(), () -> "derived pairs: " + EQUAL_SET_PAIRS);
        assertEquals(3, FAMILIES.size(), "three component-set families yield pairs at all");
        assertEquals(PAIR_COUNT, EQUAL_SET_PAIRS.stream().distinct().count(), "every pair is distinct");
    }
}
