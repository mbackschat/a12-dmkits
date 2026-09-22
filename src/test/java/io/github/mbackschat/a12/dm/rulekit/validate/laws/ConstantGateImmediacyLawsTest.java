package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;

/**
 * What the comparison's constant gate actually reads: IMMEDIACY, not cell access.
 *
 * <p>Discharges a12-kernel-lean's {@code SPEC-2026-09-22-01}. {@code MVK_INVALID_COMPARE_TO_CONST} fires
 * exactly when both sides are immediate written-out literals — a sign belongs to the literal, and a literal
 * under any operation stops being immediate, so one non-immediate side suffices. Three operands that read no
 * cell clear the gate: {@code BaseYear}, a Base-Year date part, and {@code CurrentRepetition}.
 *
 * <p><b>The last of those clears it only where it BINDS, which the entry does not say.</b> From outside its
 * repeating group {@code CurrentRepetition(/Order/Lines)} draws {@code MVK_NO_WILDCARD} — a different gate,
 * asked earlier — so "clears the constant gate" is a statement about the rule's scope as much as about the
 * operand. Both scopes are measured here, each with its own field-against-literal control, because a reading
 * that took the outside row for the gate's answer would conclude the opposite of the truth.
 *
 * <p>Sibling: {@link ComparisonOperatorOperandLawsTest}, which owns the gate's ORDER relative to the
 * tolerance and ordering questions.
 */
class ConstantGateImmediacyLawsTest {

    private static final String GROUP = "/Order";

    private static final String ORDER = DocumentModelDraft.named("order-constant-gate").locale("en_US")
            .baseYear(2024)
            .rootGroup("Order", order -> order
                    .stringField("Reference", f -> { })
                    .numberField("Quantity", f -> f.fractionDigits(0))
                    .dateField("OrderedOn", f -> f.format("dd.MM.yyyy"))
                    .group("Lines", g -> g.repeatable(20).numberField("LineQty", f -> f.fractionDigits(0))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(ORDER);

    // Every path this law addresses, resolved ONCE into a typed handle and reused — so a path is written in
    // exactly one place and checked against the model there.
    private static final String LINES_GROUP = "/Order/Lines";
    private static final FieldRef REFERENCE = M.field("/Order/Reference");
    private static final NumberRef QUANTITY = M.num("/Order/Quantity");
    private static final FieldRef LINE_QTY_FIELD = M.field(LINES_GROUP + "/LineQty");
    private static final NumberRef LINE_QTY = M.num(LINES_GROUP + "/LineQty");

    /** The repeating group's coordinate, written once and reused by both scopes. */
    private static final NumberExpr LINE_REPETITION = Dsl.currentRepetition(M.group(LINES_GROUP));

    private static String verdict(Condition condition) {
        return KernelLaws.verdictModel(ORDER, GROUP, "../Reference",
                Dsl.fieldFilled(REFERENCE).and(condition));
    }

    /** The same question from INSIDE the repeating group, where CurrentRepetition has a row to answer from. */
    private static String inLines(Condition condition) {
        return KernelLaws.verdictModel(ORDER, LINES_GROUP, "../LineQty",
                Dsl.fieldFilled(LINE_QTY_FIELD).and(condition));
    }

    @Test
    void theConstantGateFiresOnImmediacyAndThreeCellFreeOperandsClearIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a field against a literal — the control", verdict(QUANTITY.gt(0)));
        actual.put("two immediate literals", verdict(Dsl.number(1).gt(0)));
        actual.put("a signed immediate literal", verdict(Dsl.number(-1).gt(0)));
        actual.put("a literal under an operation, left", verdict(Dsl.number(1).plus(2).gt(0)));
        actual.put("a literal under an operation, right", verdict(Dsl.number(1).gt(Dsl.number(1).plus(2))));
        actual.put("BaseYear against a literal", verdict(Dsl.baseYearNumber().gt(1)));
        actual.put("a BaseYear date part against a literal", verdict(Dsl.yearFromDate(Dsl.baseYear()).gt(1)));
        actual.put("CurrentRepetition from OUTSIDE its group", verdict(LINE_REPETITION.gt(1)));
        actual.put("CurrentRepetition from inside its group", inLines(LINE_REPETITION.gt(1)));
        actual.put("a BaseYear part against CurrentRepetition, inside",
                inLines(Dsl.yearFromDate(Dsl.baseYear()).gt(LINE_REPETITION)));
        actual.put("a field against a literal, inside — the control", inLines(LINE_QTY.gt(0)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a field against a literal — the control", KernelLaws.ADMITTED);
        expected.put("two immediate literals", "MVK_INVALID_COMPARE_TO_CONST");
        // The sign belongs to the literal rather than making it an operation.
        expected.put("a signed immediate literal", "MVK_INVALID_COMPARE_TO_CONST");
        // One non-immediate side suffices, on either side.
        expected.put("a literal under an operation, left", KernelLaws.ADMITTED);
        expected.put("a literal under an operation, right", KernelLaws.ADMITTED);
        expected.put("BaseYear against a literal", KernelLaws.ADMITTED);
        expected.put("a BaseYear date part against a literal", KernelLaws.ADMITTED);
        // Not the constant gate's answer at all: the coordinate has no row to bind to out here.
        expected.put("CurrentRepetition from OUTSIDE its group", "MVK_NO_WILDCARD");
        expected.put("CurrentRepetition from inside its group", KernelLaws.ADMITTED);
        expected.put("a BaseYear part against CurrentRepetition, inside", KernelLaws.ADMITTED);
        expected.put("a field against a literal, inside — the control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the gate reads immediacy, and binding is asked first");
    }
}
