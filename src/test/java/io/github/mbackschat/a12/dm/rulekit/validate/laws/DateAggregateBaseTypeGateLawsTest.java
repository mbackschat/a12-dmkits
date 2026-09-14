package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * The FIRST of the three gates a date-typed operand list meets, and the one nothing reported.
 *
 * <p>{@code CheckEntityListenUtils.checkOpTypesAreComparableDatetimeType} walks the operand list once and asks
 * three questions of each operand in turn: is its base type DATETIME ({@code MVK_DATE_AND_NONDATE}), is its
 * date fully known ({@code MVK_PARTIAL_DATE_NOT_ALLOWED}), and do its declared components match the FIRST
 * operand's ({@code MVK_DATEFORMATS_NOT_COMPATIBLE}). The second and third are locked by
 * {@link PartialDatePrecisionOperandLawsTest} and {@link TemporalAggregateComponentSetLawsTest}; this file
 * owns the first, which those two record as pre-checked by the typed builders and therefore never measured
 * on a STORED model.
 *
 * <p><b>The interleaving is what makes the three one walk rather than three passes</b>, so two rows carry
 * this file: the same two defects in either order, which must answer by OPERAND POSITION and not by a fixed
 * precedence among the classes.
 */
class DateAggregateBaseTypeGateLawsTest {

    private static final String GROUP = "/Claim";

    /**
     * The three carriers that type their list from its leading field and route it through that method, each
     * with the comparison its own RESULT type takes — the extrema yield a date and the count a number, so a
     * single tail would answer with the comparison gate's class instead of the operand gate's.
     */
    private static final Map<String, String> CARRIERS = new LinkedHashMap<>(Map.of(
            "MaxValue", " > Today", "MinValue", " > Today", "NumberOfDifferentValues", " > 0"));

    private static final String CLAIMS = DocumentModelDraft.named("motor-claims").locale("en_US")
            .rootGroup("Claim", c -> c
                    .stringField("Reference", s -> { })
                    .dateField("FiledOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("ClosedOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("CoverFrom", d -> d.format("yyyy-MM"))
                    .stringField("Adjuster", s -> { })
                    .numberField("Excess", n -> n.fractionDigits(2)))
            .build().toJson();

    private static String verdict(String condition) {
        return KernelLaws.verdictModel(CLAIMS, GROUP, "../Reference",
                "FieldFilled(Reference) And " + condition);
    }

    private static Map<String, String> overCarriers(String list) {
        Map<String, String> actual = new LinkedHashMap<>();
        CARRIERS.forEach((op, tail) -> actual.put(op, verdict(op + "(" + list + ")" + tail)));
        return actual;
    }

    private static Map<String, String> everyCarrier(String verdict) {
        Map<String, String> expected = new LinkedHashMap<>();
        CARRIERS.keySet().forEach(op -> expected.put(op, verdict));
        return expected;
    }

    /**
     * A later operand of each non-temporal kind — the gate this file owns.
     *
     * <p>Two kinds, not three: a Confirm operand would have to be hand-written DM-JSON, the draft having no
     * builder for either boolean kind (DEV-API-GAPS DG55). Text and number already separate "not temporal"
     * from "not sortable", which is the distinction the class turns on.
     */
    @Test
    void aLaterOperandThatIsNotTemporalIsRefusedWhateverKindItIs() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.putAll(prefix("a text field", overCarriers("FiledOn, Adjuster")));
        actual.putAll(prefix("a number field", overCarriers("FiledOn, Excess")));

        Map<String, String> expected = new LinkedHashMap<>();
        actual.keySet().forEach(key -> expected.put(key, "MVK_DATE_AND_NONDATE"));
        assertEquals(expected, actual, "a later operand that is not temporal");
    }

    /** The LEADING operand decides which arm types the list at all, so it is a different question. */
    @Test
    void aLeadingOperandThatIsNotTemporalIsAnotherArmsAnswer() {
        Map<String, String> actual = overCarriers("Adjuster, FiledOn");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("MaxValue", "MVK_NOT_SORTABLE");
        expected.put("MinValue", "MVK_NOT_SORTABLE");
        // The count asks its own kind question instead, which is why the row is measured per carrier.
        expected.put("NumberOfDifferentValues", "MVK_STRING_ENUM_AND_NON_STRING_ENUM");
        assertEquals(expected, actual, "a leading operand that is not temporal");
    }

    /** Two temporal operands agreeing on components — the liveness control for the rows above. */
    @Test
    void twoTemporalOperandsOfOneComponentSetAreAdmitted() {
        assertEquals(everyCarrier("ADMITTED"), overCarriers("FiledOn, ClosedOn"),
                "two temporal operands of one component set");
    }

    /**
     * The same two defects in either order — the rows that make this ONE walk rather than three passes.
     *
     * <p>A fixed precedence among the classes would answer both rows with the same code; A12 answers by
     * operand position, so each row draws the class of whichever operand comes first.
     */
    @Test
    void twoDefectsAnswerByOperandPositionAndNotByClassPrecedence() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.putAll(prefix("the non-temporal one first", overCarriers("FiledOn, Adjuster, CoverFrom")));
        actual.putAll(prefix("the format mismatch first", overCarriers("FiledOn, CoverFrom, Adjuster")));

        Map<String, String> expected = new LinkedHashMap<>();
        CARRIERS.keySet().forEach(op -> expected.put("the non-temporal one first: " + op, "MVK_DATE_AND_NONDATE"));
        CARRIERS.keySet().forEach(op -> expected.put("the format mismatch first: " + op, "MVK_DATEFORMATS_NOT_COMPATIBLE"));
        assertEquals(expected, actual, "two defects, by operand position");
    }

    /** The carriers that route through a DIFFERENT arm, so the class above is not simply "any date list". */
    @Test
    void theCarriersThatDoNotRouteThroughThisGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("FieldValuesNotUnique", verdict("FieldValuesNotUnique(FiledOn, Adjuster)"));
        actual.put("FirstFilledValue", verdict("FirstFilledValue(FiledOn, Adjuster) > \"\""));

        Map<String, String> expected = new LinkedHashMap<>();
        // Both walk `matchBaseType`, which compares each operand against its PREDECESSOR under one class
        // rather than asking the three datetime questions — the boundary that keeps this file's class from
        // reading as "any date list".
        expected.put("FieldValuesNotUnique", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("FirstFilledValue", "MVK_VARYING_TYPES_NOT_ALLOWED");
        assertEquals(expected, actual, "the carriers that do not route through this gate");
    }

    private static Map<String, String> prefix(String label, Map<String, String> rows) {
        Map<String, String> keyed = new LinkedHashMap<>();
        rows.forEach((carrier, verdict) -> keyed.put(label + ": " + carrier, verdict));
        return keyed;
    }
}
