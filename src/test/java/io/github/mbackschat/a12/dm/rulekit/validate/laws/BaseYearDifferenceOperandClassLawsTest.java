package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * Whether a declared Base Year moves the difference operators' ADMISSION and, separately, their refusal
 * CLASS.
 *
 * <p>Discharges a12-kernel-lean's {@code SPEC-2026-09-21-03}. Two units of one family agree on every
 * admitted operand and still answer different codes on a refused one: a pure clock at
 * {@code DifferenceInYears} draws {@code MVK_INVALID_TIME} with a Base Year declared and
 * {@code MVK_WRONG_DATE_FORMAT_FOR_OP} without, while {@code DifferenceInMonths} draws the latter in both
 * models.
 *
 * <p><b>The PARTNER is a second variable, and holding it still is what makes the yearless rows readable.</b>
 * "{@code DifferenceInMonths} over a yearless operand is admitted either way" is true of a homogeneous
 * yearless pair and false of a yearless-against-dated one, which draws
 * {@code MVK_DATE_WITH_AND_WITHOUT_YEAR} — the year-MIXTURE gate. Both pairs are measured, because a grid
 * carrying only the mixed pair reports the mixture's class as the unit's.
 *
 * <p><b>Corrected 2026-09-22: the mixture gate does NOT answer first, and the rows here say so.</b> The
 * earlier reading had it answering before each unit's own operand gate, which the grid refutes: the same
 * mixed pair {@code (Yearless, Dated)} draws {@code MVK_WRONG_DATE_FORMAT_FOR_OP} under
 * {@code DifferenceInYears} and {@code MVK_DATE_WITH_AND_WITHOUT_YEAR} under {@code DifferenceInMonths}, and
 * {@link KernelLaws#verdictModel} joins every distinct code, so one code means one gate reported. A gate that
 * ran first would report on both. What the rows support instead: {@code DifferenceInMonths} ADMITS a yearless
 * operand at its own gate, which leaves the mixture gate as the one able to refuse the mixed pair, while
 * {@code DifferenceInYears} refuses a yearless operand at its own gate and PRE-EMPTS the mixture gate. The
 * order is the same in both; what differs is whether the unit's gate has anything to say.
 *
 * <p>Sibling: {@link BaseYearRequirementLawsTest}, which owns where the {@code BaseYear} CONSTANT needs the
 * declaration; this file is about a yearless FIELD reaching an operand slot.
 *
 * <p><b>Authoring, and what the split between the two paths is worth.</b> The kit types these slots
 * ({@code differenceInYears(DateOperand, DateOperand)}), so the DATE rows — including the yearless ones,
 * which are DATE-kind fields with a {@code dd.MM.} format — are authored through it, and the CLOCK and STAMP
 * rows have no typed spelling and carry text. That split is itself the result: our static erasure refuses
 * exactly the operands the kernel refuses here, row for row, which is the measured basis a client-side
 * refusal is required to have rather than an analogy about the date family.
 */
class BaseYearDifferenceOperandClassLawsTest {

    private static final String GROUP = "/Order";

    private static String order(Integer baseYear) {
        var draft = DocumentModelDraft.named("order-difference").locale("en_US");
        if (baseYear != null) {
            draft = draft.baseYear(baseYear);
        }
        return draft.rootGroup("Order", order -> order
                        .stringField("Reference", f -> { })
                        .dateField("Yearless", f -> f.format("dd.MM."))
                        .dateField("Dated", f -> f.format("dd.MM.yyyy"))
                        .timeField("Clock", f -> f.format("HH:mm:ss"))
                        // The clock's own partner, so the clock rows can hold the PARTNER still the way the
                        // yearless rows do. Without it every clock row is (Clock, Dated) and the partner is a
                        // second variable in exactly the grid that declares partners must be controlled.
                        .timeField("Clock2", f -> f.format("HH:mm:ss"))
                        .dateTimeField("Stamp", f -> f.format("yyyy-MM-dd'T'HH:mm:ss")))
                .build().toJson();
    }

    private static final String WITHOUT = order(null);
    private static final String WITH = order(2024);

    private static final BoundModel WITH_M = BoundModel.ofJson(WITH);
    private static final BoundModel WITHOUT_M = BoundModel.ofJson(WITHOUT);

    /** The text path, for an operand kind the typed slot erases — the clock and the stamp. */
    private static String verdict(String model, String condition) {
        return KernelLaws.verdictModel(model, GROUP, "../Reference",
                "FieldFilled(Reference) And " + condition);
    }

    /**
     * The typed path. The condition is built per model because a ref is bound to the model it came from, and
     * the two models here differ exactly in the Base Year declaration.
     */
    private static String verdict(String model, BoundModel m, java.util.function.Function<BoundModel,
            Condition> build) {
        return KernelLaws.verdictModel(model, GROUP, "../Reference",
                Dsl.fieldFilled(m.field("/Order/Reference")).and(build.apply(m)));
    }

    private static void both(Map<String, String> into, String label, String declared, String undeclared) {
        into.put(label + " — Base Year declared", declared);
        into.put(label + " — no Base Year", undeclared);
    }

    private static void row(Map<String, String> into, String label, String condition) {
        into.put(label + " — Base Year declared", verdict(WITH, condition));
        into.put(label + " — no Base Year", verdict(WITHOUT, condition));
    }

    private static void row(Map<String, String> into, String label,
            java.util.function.Function<BoundModel, Condition> build) {
        into.put(label + " — Base Year declared", verdict(WITH, WITH_M, build));
        into.put(label + " — no Base Year", verdict(WITHOUT, WITHOUT_M, build));
    }

    @Test
    void aDeclaredBaseYearMovesBothTheAdmissionAndTheRefusalClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        row(actual, "years over a dated operand — the control", m -> Dsl.differenceInYears(m.date("/Order/Dated"), m.date("/Order/Dated")).gt(1));
        row(actual, "years over a yearless operand", m -> Dsl.differenceInYears(m.date("/Order/Yearless"), m.date("/Order/Dated")).gt(1));
        row(actual, "months over a yearless operand", m -> Dsl.differenceInMonths(m.date("/Order/Yearless"), m.date("/Order/Dated")).gt(1));
        row(actual, "years over TWO yearless operands", m -> Dsl.differenceInYears(m.date("/Order/Yearless"), m.date("/Order/Yearless")).gt(1));
        row(actual, "months over TWO yearless operands", m -> Dsl.differenceInMonths(m.date("/Order/Yearless"), m.date("/Order/Yearless")).gt(1));
        row(actual, "years over a clock operand", "DifferenceInYears(Clock, Dated) > 1");
        row(actual, "months over a clock operand", "DifferenceInMonths(Clock, Dated) > 1");
        row(actual, "years over a stamp operand", "DifferenceInYears(Stamp, Dated) > 1");
        row(actual, "months over a stamp operand", "DifferenceInMonths(Stamp, Dated) > 1");
        row(actual, "days over a stamp operand", m -> Dsl.differenceInDays(m.dateTime("/Order/Stamp"), m.dateTime("/Order/Stamp")).gt(1));
        row(actual, "days over a clock operand", "DifferenceInDays(Clock, Dated) > 1");
        // The homogeneous controls this file's own paragraph demands. Every clock row above pairs against a
        // DATE, so the partner moves with the unit; these hold it at the operand's own kind.
        row(actual, "years over TWO clock operands", "DifferenceInYears(Clock, Clock2) > 1");
        row(actual, "months over TWO clock operands", "DifferenceInMonths(Clock, Clock2) > 1");
        row(actual, "days over TWO clock operands", "DifferenceInDays(Clock, Clock2) > 1");
        row(actual, "years over TWO stamp operands", "DifferenceInYears(Stamp, Stamp) > 1");
        row(actual, "months over TWO stamp operands", "DifferenceInMonths(Stamp, Stamp) > 1");

        Map<String, String> expected = new LinkedHashMap<>();
        both(expected, "years over a dated operand — the control", KernelLaws.ADMITTED, KernelLaws.ADMITTED);
        // The Base Year supplies the missing year position, so the same operand crosses the gate with it.
        both(expected, "years over a yearless operand", KernelLaws.ADMITTED, "MVK_WRONG_DATE_FORMAT_FOR_OP");
        // Months ADMITS a yearless operand at its own gate, so the mixture gate is the one left to refuse the
        // mixed pair. Read against the years row above, which draws the UNIT's class on the same pair: that is
        // what shows the unit's gate is not being skipped here, only satisfied.
        both(expected, "months over a yearless operand", KernelLaws.ADMITTED, "MVK_DATE_WITH_AND_WITHOUT_YEAR");
        both(expected, "years over TWO yearless operands",
                KernelLaws.ADMITTED, "MVK_WRONG_DATE_FORMAT_FOR_OP");
        // …and with the partner held yearless, months IS admitted either way: the unit is the only variable.
        both(expected, "months over TWO yearless operands", KernelLaws.ADMITTED, KernelLaws.ADMITTED);
        // The class itself moves with the Base Year on the completed-period unit, and not on its sibling.
        both(expected, "years over a clock operand", "MVK_INVALID_TIME", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        both(expected, "months over a clock operand",
                "MVK_WRONG_DATE_FORMAT_FOR_OP", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        both(expected, "years over a stamp operand",
                "MVK_WRONG_DATE_FORMAT_FOR_OP", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        both(expected, "months over a stamp operand",
                "MVK_WRONG_DATE_FORMAT_FOR_OP", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        // Each family refuses the other's carrier: days takes the stamp and refuses the pure clock.
        both(expected, "days over a stamp operand", KernelLaws.ADMITTED, KernelLaws.ADMITTED);
        both(expected, "days over a clock operand",
                "MVK_WRONG_DATE_FORMAT_FOR_OP", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        // With the partner held at the operand's own kind, every conclusion above survives — the clock rows
        // were reporting the unit's gate, not a mixture with the DATE partner. `DifferenceInYears` keeps its
        // Base-Year-only MVK_INVALID_TIME, which is the row the whole file turns on.
        both(expected, "years over TWO clock operands", "MVK_INVALID_TIME", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        both(expected, "months over TWO clock operands",
                "MVK_WRONG_DATE_FORMAT_FOR_OP", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        both(expected, "days over TWO clock operands",
                "MVK_WRONG_DATE_FORMAT_FOR_OP", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        both(expected, "years over TWO stamp operands",
                "MVK_WRONG_DATE_FORMAT_FOR_OP", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        both(expected, "months over TWO stamp operands",
                "MVK_WRONG_DATE_FORMAT_FOR_OP", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        assertEquals(expected, actual, "a Base Year moves both the admission and the class");
    }
}
