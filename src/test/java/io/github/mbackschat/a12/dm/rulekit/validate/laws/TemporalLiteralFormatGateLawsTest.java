package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §6 supplement — <b>which of the two gates a quoted literal beneath a temporal extractor
 * trips is decided by the literal's FORMAT, not by the extractor's family.</b>
 *
 * <p>Recorded because the opposite was asserted, and shipped. KERNEL-FINDINGS {@code kf167} and the
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
 *
 * <p><b>Authored as condition strings, deliberately.</b> The project's dogfood rule prefers the typed
 * surface, and the numeric sibling ({@code NumericWrapperConstantLawsTest}) uses it — but here it is
 * <em>impossible</em>: {@code DateOperand} is a sealed interface that does not permit {@code DateLiteral},
 * so {@code Dsl.dayFromDate(new DateLiteral("01.01.2020"))} does not compile. The typed builder already
 * makes this refusal unrepresentable, which is why only a string can reach the kernel's own gate
 * (API-FINDINGS finding 110).
 */
class TemporalLiteralFormatGateLawsTest {

    private static final String NO_CONST = "MVK_NO_CONST";
    private static final String INVALID_DATE_STRING = "MVK_INVALID_DATE_STRING";

    /**
     * The pair, on ONE extractor so the extractor cannot be the variable. This is the case that falsifies the
     * shipped claim: the same {@code YearFromDate} yields both codes, chosen by the literal alone.
     *
     * <p>Asserted <b>exclusively</b> in both directions, because the law is an ordering claim ("the string gate
     * fires first and the constant rule is never reached") and the ordinary assertion is an {@code anyMatch} that
     * cannot see it. As first written this method would have passed had the ISO cell raised {@code MVK_NO_CONST}
     * too — i.e. it did not test the sentence it was named after. Review found it, which is fitting: the finding it
     * locks is itself about a claim that outran what was measured.
     */
    @Test
    void aDateExtractorsLiteralGateIsChosenByTheLiteralsFormat() {
        rejectsInsteadOf("Date(\"01\", \"01\", YearFromDate(\"01.01.2019\"))", NO_CONST, INVALID_DATE_STRING);
        rejectsInsteadOf("Date(\"01\", \"01\", YearFromDate(\"2019-01-01\"))", INVALID_DATE_STRING, NO_CONST);
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
        rejectsInsteadOf("Time(HoursFromTime(\"10:30:45\"), \"30\", \"45\")", NO_CONST, INVALID_DATE_STRING);
        rejectsInsteadOf("Time(HoursFromTime(\"2019-01-01\"), \"30\", \"45\")", INVALID_DATE_STRING, NO_CONST);
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

    /** The exclusive form — {@code code} fires and {@code preempted} does not, which is what an ordering law claims. */
    private static void rejectsInsteadOf(String comparand, String code, String preempted) {
        KernelLaws.rejectsOrderInsteadOf("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And [DeliveryDate] > " + comparand, code, preempted);
    }
}
