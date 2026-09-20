package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §6 — the constraint {@code DateConstruction} names as the kernel's call: each component's
 * DECLARATION must be position-compatible (a bounded Number, an exact digit-pattern String, or the complete
 * year's exact {@code yyyy} Date exception).
 *
 * <p>{@code DateConstruction}'s constructor gates only what is visible from the operands — which extractor sits
 * in which slot — because a field's bounds are model data. So the declaration rule is measured here before any
 * client-side refusal is written for it, and a refusal may never be wider than this basis.
 *
 * <p><b>Measured rule for a NUMBER component: bounded on BOTH sides, and within the slot's own range.</b> A
 * ceiling alone is not enough — {@code max} with no {@code min} is refused, which is the "sign allowed" shape —
 * and bounds wider than the slot are refused even though every in-range value would fit.
 *
 * <p>Both slots are measured because the rule is per-slot rather than a boundedness check: a 1..31 field is
 * admitted in the DAY slot and refused in the MONTH slot. The YEAR slot is deliberately absent — it carries a
 * separate {@code yyyy} Date exception these rows do not measure, so nothing here licenses a claim about it.
 */
class DateConstructionComponentDeclarationLawsTest {

    private static final String WRONG_FORMAT = "MVK_WRONG_DATE_FORMAT_FOR_OP";

    private static final String MODEL = DocumentModelDraft.named("date-construction-components")
            .locale("en_US")
            .rootGroup("Cover", c -> c
                    .field("N", FieldKind.NUMBER)
                    .dateField("FullDate", d -> d.format("yyyy-MM-dd"))
                    // A month-shaped ceiling but no floor: the kernel's "sign allowed" shape.
                    .numberField("MonthMaxOnly", n -> n.max("12"))
                    // Bounded on both sides to exactly a month.
                    .numberField("MonthBounded", n -> n.min("1").max("12"))
                    // Bounded, but to a range wider than the slot.
                    .numberField("MonthTooWide", n -> n.min("1").max("99"))
                    .numberField("MonthUnbounded", n -> { })
                    // Bounded on both sides to exactly a day.
                    .numberField("DayBounded", n -> n.min("1").max("31"))
                    // Inside the day slot's range but not filling it: the gate refuses this on inference from
                    // the wider/narrower rows, and an unmeasured refusal is exactly what may not ship.
                    .numberField("DayNarrow", n -> n.min("1").max("30"))
                    // UNSIGNED shapes: the kernel's low-side requirement is a non-negative minimum OR no
                    // sign, so these declare no minimum at all and are what real models use.
                    .numberField("MonthUnsignedMax", n -> n.positivesOnly().max("12"))
                    .numberField("DayUnsignedMax", n -> n.positivesOnly().max("31"))
                    // A ceiling declared as a digit LENGTH rather than a value maximum.
                    .numberField("MonthUnsignedLen2", n -> n.positivesOnly().maxLength(2))
                    .numberField("MonthSignedLen2", n -> n.maxLength(2))
                    // A NEGATIVE minimum satisfies "a minimum is declared" but not "a non-negative one".
                    .numberField("MonthNegativeMin", n -> n.min("-5").max("12"))
                    // A declared SCALE: the rule's third conjunct is that the component has no fraction.
                    .numberField("MonthScaled", n -> n.min("1").max("12").fractionDigits(2))
                    .field("MonthText", FieldKind.STRING))
            .build().toJson();

    /**
     * {@code Valid(...)} rather than a comparison: comparing the construction to a date field does not parse
     * here, and the parse error masks the declaration verdict on exactly the rows where the declaration is
     * ACCEPTABLE — so the comparison form hides the admitted cells and reads as a uniform refusal.
     *
     * <p>The condition references {@code N}, the error field, or every row draws
     * {@code MVK_ERROR_FIELD_NOT_REFERENCED} instead.
     */
    private static String verdict(String day, String month) {
        return verdict("Date(" + day + ", " + month + ", \"2009\")");
    }

    /** The FOUR-component form, whose year is split into century and decade. */
    private static String verdictFourPart(String day, String month) {
        return verdict("Date(" + day + ", " + month + ", \"19\", \"63\")");
    }

    private static String verdict(String construction) {
        var result = KernelLaws.checkModel(MODEL, "/Cover", "../N",
                "Valid(" + construction + ") And FieldFilled(N)");
        if (result.ok()) {
            return KernelLaws.ADMITTED;
        }
        return result.diagnostics().stream()
                .map(d -> d.code().orElse("NO_CODE"))
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining("+"));
    }

    /** One table, so a kernel shift shows every cell rather than aborting at the first. */
    @Test
    void whatTheDayAndMonthSlotsAcceptByDECLARATION() {
        var actual = new LinkedHashMap<String, String>();

        // The MONTH slot, across every declaration a NUMBER field can carry.
        actual.put("month: number, max 12 only", verdict("\"01\"", "MonthMaxOnly"));
        actual.put("month: number, min 1 max 12", verdict("\"01\"", "MonthBounded"));
        actual.put("month: number, min 1 max 99", verdict("\"01\"", "MonthTooWide"));
        actual.put("month: number, unbounded", verdict("\"01\"", "MonthUnbounded"));
        actual.put("month: string, no digit pattern", verdict("\"01\"", "MonthText"));

        // THE PER-SLOT DISCRIMINATOR: a 1..31 field in each slot.
        actual.put("day: number, min 1 max 31", verdict("DayBounded", "MonthBounded"));
        actual.put("month: number, min 1 max 31", verdict("\"01\"", "DayBounded"));
        // SUBSET OR EQUALITY? 1..12 sits inside the day slot's range without filling it.
        actual.put("day: number, min 1 max 12", verdict("MonthBounded", "MonthBounded"));

        // A construction of constants only is a separate law, not a declaration verdict.
        actual.put("day: number, min 1 max 30", verdict("DayNarrow", "MonthBounded"));

        // THE UNSIGNED AXIS — reported as a live over-rejection: our gate demands a declared minimum, while
        // the kernel accepts unsignedness instead. Rows (a) and (c) of the differential's brief.
        actual.put("month: unsigned, max 12, NO min", verdict("\"01\"", "MonthUnsignedMax"));
        actual.put("day: unsigned, max 31, NO min", verdict("DayUnsignedMax", "MonthBounded"));
        actual.put("month: unsigned, maxLength 2, no max", verdict("\"01\"", "MonthUnsignedLen2"));
        // The control for the row above: the same length ceiling while signs are permitted.
        actual.put("month: signed, maxLength 2, no max", verdict("\"01\"", "MonthSignedLen2"));
        // The other two conjuncts of the same rule, which our gate does not check at all.
        actual.put("month: NEGATIVE min, max 12", verdict("\"01\"", "MonthNegativeMin"));
        actual.put("month: scale 2, min 1 max 12", verdict("\"01\"", "MonthScaled"));
        actual.put("all components constant", verdict("\"01\"", "\"03\""));

        // THE FOUR-COMPONENT FORM. It has rules of its own for the two YEAR positions it splits
        // (MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS), so whether the day and month slots keep
        // the same declaration rule is a separate carrier and not a scope transfer to assume.
        actual.put("4-part: day 1..31, month 1..12", verdictFourPart("DayBounded", "MonthBounded"));
        actual.put("4-part: month slot given 1..31", verdictFourPart("DayBounded", "DayBounded"));
        actual.put("4-part: day slot given 1..12", verdictFourPart("MonthBounded", "MonthBounded"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("month: number, max 12 only", WRONG_FORMAT);
        expected.put("month: number, min 1 max 12", KernelLaws.ADMITTED);
        expected.put("month: number, min 1 max 99", WRONG_FORMAT);
        expected.put("month: number, unbounded", WRONG_FORMAT);
        expected.put("month: string, no digit pattern", "MVK_NO_DIGIT_PATTERN");
        expected.put("day: number, min 1 max 31", KernelLaws.ADMITTED);
        expected.put("month: number, min 1 max 31", WRONG_FORMAT);
        expected.put("day: number, min 1 max 12", WRONG_FORMAT);
        expected.put("day: number, min 1 max 30", WRONG_FORMAT);
        expected.put("month: unsigned, max 12, NO min", KernelLaws.ADMITTED);
        expected.put("day: unsigned, max 31, NO min", KernelLaws.ADMITTED);
        expected.put("month: unsigned, maxLength 2, no max", KernelLaws.ADMITTED);
        expected.put("month: signed, maxLength 2, no max", WRONG_FORMAT);
        expected.put("month: NEGATIVE min, max 12", WRONG_FORMAT);
        expected.put("month: scale 2, min 1 max 12", WRONG_FORMAT);
        expected.put("all components constant", "MVK_ONLY_CONSTANTS_USED");
        expected.put("4-part: day 1..31, month 1..12", KernelLaws.ADMITTED);
        expected.put("4-part: month slot given 1..31", WRONG_FORMAT);
        expected.put("4-part: day slot given 1..12", WRONG_FORMAT);

        assertEquals(expected, actual);
    }
}
