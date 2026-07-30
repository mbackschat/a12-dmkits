package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §6 — a {@code Date(...)} / {@code Time(...)} component sits in the kernel's
 * {@code datumParam} position, whose grammar is {@code entitySpec | datumExtractOp | StringLiteral}. There is
 * <b>no bare-number alternative</b>: a numeric constant component must be QUOTED, and the bare spelling draws
 * {@code MVK_INVALID_ENTITY} because the kernel reads the digits as an entity name.
 *
 * <p>This pair is the kernel side of a defect in our own authoring path: {@code DslRenderer} rendered a
 * {@link io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral} component bare, so {@code Dsl.date(number(30),
 * number(9), number(1916))} produced a condition the kernel refuses. Nothing exercised the shape end-to-end,
 * which is why it survived. The rendering itself is locked in
 * {@code dsl.RenderConditionTest.rendersConstantDateAndTimeComponentsQuoted}.
 *
 * <p>Also locks the combined shape a12-kernel-lean's SPEC-2026-07-29-01 asks for — a left-hand Date shift
 * compared against a constructed Date — whose joint authorability had not previously been measured on either
 * side.
 */
class DateComponentConstantLawsTest {

    private static final String WRONG_FORMAT = "MVK_WRONG_DATE_FORMAT_FOR_OP";

    @Test
    void aQuotedNumericComponentIsAdmitted() {
        accepts("[OrderDate] == Date(\"1\", \"10\", \"1916\")");
        accepts("[OrderDate] == Date(\"1\", \"1\", \"19\", \"63\")");
    }

    @Test
    void aBareNumericComponentIsRejectedAsAnEntityName() {
        rejects("[OrderDate] == Date(1, 10, 1916)", "MVK_INVALID_ENTITY");
    }

    /** A Date SHIFT on the left and a CONSTRUCTED Date on the right compose — measured, not assumed. */
    @Test
    void aDateShiftComparedAgainstAConstructedDateIsAdmitted() {
        accepts("AddDays(Date(\"30\", \"9\", \"1916\"), 1) == Date(\"1\", \"10\", \"1916\")");
        accepts("AddMonths(Date(\"1\", \"9\", \"1916\"), 1) == Date(\"1\", \"10\", \"1916\")");
        accepts("AddYears(Date(\"1\", \"10\", \"1915\"), 1) == Date(\"1\", \"10\", \"1916\")");
        // the inverse controls, so the comparison operator itself is not the admitted half
        accepts("AddDays(Date(\"30\", \"9\", \"1916\"), 1) != Date(\"1\", \"10\", \"1916\")");
        accepts("AddYears(Date(\"1\", \"10\", \"1915\"), 1) != Date(\"1\", \"10\", \"1916\")");
    }

    /** Each half is separately admitted, so a future rejection is attributable to the composition. */
    @Test
    void bothHalvesAreIndependentlyAdmitted() {
        accepts("AddDays(Date(\"30\", \"9\", \"1916\"), 1) == [OrderDate]");
        accepts("AddDays(OrderDate, 1) == Date(\"1\", \"10\", \"1916\")");
    }

    /**
     * Constants MIX with field components — the kernel checks each `datumParam` position independently, so a
     * construction may draw one component from a field and another from a literal.
     */
    @Test
    void aConstantMixesWithFieldComponents() {
        accepts("[OrderDate] == Date(BirthDay, \"01\", \"1963\")");
        accepts("[OrderDate] == Date(\"01\", BirthMonth, BirthYear)");
        accepts("[OrderDate] == Date(BirthDay, BirthMonth, \"1963\")");
    }

    /**
     * Each position's range, pinned on BOTH sides so the bound itself is located rather than merely exceeded.
     * `CheckDatumKonstruktImpl` supplies `[1,32)` for Day, `[1,13)` for Month, `[1800,2200)` for the complete
     * Year, `[18,22)` for Century and `[0,100)` for Short-Year; every refusal is one code.
     */
    @Test
    void theDayRangeIsOneToThirtyOne() {
        accepts("[OrderDate] == Date(\"31\", \"01\", \"1963\")");
        rejects("[OrderDate] == Date(\"32\", \"01\", \"1963\")", WRONG_FORMAT);
        rejects("[OrderDate] == Date(\"0\", \"01\", \"1963\")", WRONG_FORMAT);
    }

    @Test
    void theMonthRangeIsOneToTwelve() {
        accepts("[OrderDate] == Date(\"01\", \"12\", \"1963\")");
        rejects("[OrderDate] == Date(\"01\", \"13\", \"1963\")", WRONG_FORMAT);
        rejects("[OrderDate] == Date(\"01\", \"0\", \"1963\")", WRONG_FORMAT);
    }

    @Test
    void theCompleteYearRangeIsEighteenHundredToTwentyOneNinetyNine() {
        accepts("[OrderDate] == Date(\"01\", \"01\", \"1800\")");
        accepts("[OrderDate] == Date(\"01\", \"01\", \"2199\")");
        rejects("[OrderDate] == Date(\"01\", \"01\", \"2200\")", WRONG_FORMAT);
        rejects("[OrderDate] == Date(\"01\", \"01\", \"1799\")", WRONG_FORMAT);
    }

    /** The complete Year is width-gated at exactly four code units — unlike Day and Month, which have none. */
    @Test
    void theCompleteYearWidthIsExactlyFour() {
        rejects("[OrderDate] == Date(\"01\", \"01\", \"196\")", WRONG_FORMAT);
        rejects("[OrderDate] == Date(\"01\", \"01\", \"19634\")", WRONG_FORMAT);
    }

    @Test
    void theCenturyRangeIsEighteenToTwentyOneAtWidthTwo() {
        accepts("[OrderDate] == Date(\"01\", \"01\", \"18\", \"00\")");
        accepts("[OrderDate] == Date(\"01\", \"01\", \"21\", \"99\")");
        rejects("[OrderDate] == Date(\"01\", \"01\", \"22\", \"00\")", WRONG_FORMAT);
        rejects("[OrderDate] == Date(\"01\", \"01\", \"17\", \"00\")", WRONG_FORMAT);
        rejects("[OrderDate] == Date(\"01\", \"01\", \"9\", \"00\")", WRONG_FORMAT);
    }

    /** An empty component is not a zero — the digit sequence must be nonempty. */
    @Test
    void anEmptyComponentIsRefused() {
        rejects("[OrderDate] == Date(\"\", \"01\", \"1963\")", WRONG_FORMAT);
    }

    /**
     * The digit repertoire is per UTF-16 CODE UNIT: {@code StringUtils.isNumeric} loops
     * {@code Character.isDigit(char)}, so a BMP non-ASCII digit qualifies while a supplementary-plane digit's
     * surrogate halves do not. Measured 2026-07-29 — this is the boundary SPEC-2026-07-27-11 names, and it is
     * the reason a code-POINT digit predicate would wrongly admit the supplementary form.
     */
    @Test
    void theDigitRepertoireIsPerCodeUnit() {
        accepts("[OrderDate] == Date(\"\u0661\u0660\", \"1\", \"1963\")");          // Arabic-Indic 1,0 — BMP
        rejects("[OrderDate] == Date(\"\uD835\uDFCE\uD835\uDFCF\", \"1\", \"1963\")",
                "MVK_WRONG_DATE_FORMAT_FOR_OP");                                       // bold digits — supplementary
    }

    /** A TIME literal uses a different gate entirely — exact ASCII {@code HH:mm:ss} — so the same BMP digits
     *  are REFUSED there. The two gates are not one rule, and this pair is what keeps them apart. */
    @Test
    void theTimeLiteralGateIsAsciiOnlyUnlikeTheComponentGate() {
        rejects("[PickupTime] == \"\u0661\u0660:\u0663\u0660:\u0664\u0665\"", "MVK_INVALID_COMPARE_TO_DATE");
    }

    @Test
    void timeAcceptsZeroToThreePrefixComponents() {
        accepts("[PickupTime] == Time()");
        accepts("[PickupTime] == Time(\"1\")");
        accepts("[PickupTime] == Time(\"1\", \"2\")");
        accepts("[PickupTime] == Time(\"1\", \"2\", \"3\")");
    }

    @Test
    void aTimeConstantUsesTheClockRangeWithoutAWidthGate() {
        accepts("[PickupTime] == Time(\"1\")");
        rejects("[PickupTime] == Time(\"24\")", WRONG_FORMAT);
    }

    private static void accepts(String condition) {
        KernelLaws.acceptsOrder("/Order", "../CustomerName", "FieldFilled(CustomerName) And " + condition);
    }

    private static void rejects(String condition, String code) {
        KernelLaws.rejectsOrder("/Order", "../CustomerName", "FieldFilled(CustomerName) And " + condition, code);
    }
}
