package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §6 supplement — <b>which of the two gates a quoted literal beneath a temporal extractor
 * trips is decided by the literal's FORMAT, not by the extractor's family.</b>
 *
 * <p>Recorded because the opposite was asserted, and shipped. {@link org.junit.jupiter.api.Test kf167} and the
 * {@code MVK-LEDGER} row both claimed the <em>date</em> extractors ({@code YearFromDate}, {@code MonthFromDate},
 * {@code DayFromDate}) refuse a quoted literal as {@code MVK_INVALID_DATE_STRING} <em>rather than</em>
 * {@code MVK_NO_CONST}, and the operator catalog served that to users through
 * {@code dmtool operators HoursFromTime}. It is false: a date extractor over a <b>model-format</b> literal raises
 * {@code MVK_NO_CONST} exactly as a time extractor does. The claim was generalized from a single ISO-formatted
 * example — an observation dressed as a mechanism, which is precisely the class the project's oracle rule exists
 * to catch. Our own {@code TemporalExtractorComponentLawsTest} had been asserting the true behaviour for a date
 * extractor all along; nothing compared the two statements.
 *
 * <p>The gates are ordered by <b>convertibility alone</b>:
 *
 * <ul>
 *   <li>the literal <b>parses as some temporal value in the model format</b> ({@code "01.01.2019"},
 *       {@code "10:30:45"}) → {@code MVK_NO_CONST}. The value converts, so the constant-operand rule is what
 *       refuses it;</li>
 *   <li>the literal <b>parses as nothing</b> ({@code "2019-01-01"} — ISO, which is not the model format) → the
 *       string gate {@code MVK_INVALID_DATE_STRING} fires first and the constant rule is never reached.</li>
 * </ul>
 *
 * <p>Two things are therefore <em>not</em> discriminators, and each has its own case below because assuming either
 * one is how the shipped claim went wrong. The extractor's <b>family</b> is not: a date extractor and a time
 * extractor over convertible literals give the same code. And the literal's <b>kind-match</b> with the extractor is
 * not either: {@code HoursFromTime("01.01.2020")} — a date literal handed to a time extractor — still converts, so
 * it is still {@code MVK_NO_CONST}. (That last cell was measured because the first draft of this class predicted
 * {@code MVK_INVALID_DATE_STRING} for it and was wrong; the prediction came from the same over-generalizing habit
 * as the claim being corrected.)
 *
 * <p>What survives from kf167 is the conclusion, not its premise: the code alone still cannot tell you which
 * construct rejected, which remains the real reason operator coverage is keyed on
 * <em>operator + operand shape + code + consumer context</em>. There is no "third, date-extractor gate".
 */
class TemporalLiteralFormatGateLawsTest {

    private static final String NO_CONST = "MVK_NO_CONST";
    private static final String INVALID_DATE_STRING = "MVK_INVALID_DATE_STRING";

    /**
     * The pair, on ONE extractor so the extractor cannot be the variable. This is the case that falsifies the
     * shipped claim: the same {@code YearFromDate} yields both codes, chosen by the literal alone.
     */
    @Test
    void aDateExtractorsLiteralGateIsChosenByTheLiteralsFormat() {
        rejects("Date(\"01\", \"01\", YearFromDate(\"01.01.2019\"))", NO_CONST);
        rejects("Date(\"01\", \"01\", YearFromDate(\"2019-01-01\"))", INVALID_DATE_STRING);
    }

    /** Every date extractor takes the model-format literal to the SAME constant gate — not a family of its own. */
    @Test
    void everyDatePositionRefusesAModelFormatLiteralAsAConstant() {
        rejects("Date(DayFromDate(\"01.01.2020\"), \"01\", \"1963\")", NO_CONST);
        rejects("Date(\"01\", MonthFromDate(\"01.01.2020\"), \"1963\")", NO_CONST);
        rejects("Date(\"01\", \"01\", YearFromDate(\"01.01.2020\"))", NO_CONST);
    }

    /** The time extractors sit on the same two gates — so the extractor's family is not the discriminator. */
    @Test
    void theTimeExtractorsShowTheSameTwoGates() {
        rejects("Time(HoursFromTime(\"10:30:45\"), \"30\", \"45\")", NO_CONST);
        rejects("Time(HoursFromTime(\"2019-01-01\"), \"30\", \"45\")", INVALID_DATE_STRING);
    }

    /**
     * Nor is the literal's <b>kind-match</b> with the extractor a discriminator: a DATE literal handed to a TIME
     * extractor still converts, so it is still refused as a constant — never as a bad string. Measured, after the
     * opposite was predicted.
     */
    @Test
    void aConvertibleLiteralOfTheWrongKindIsStillARefusedConstant() {
        rejects("Time(HoursFromTime(\"01.01.2020\"), \"30\", \"45\")", NO_CONST);
        rejects("Date(DayFromDate(\"10:30:45\"), \"01\", \"1963\")", NO_CONST);
    }

    private static void rejects(String comparand, String code) {
        KernelLaws.rejectsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And [DeliveryDate] > " + comparand, code);
    }
}
