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
 * exactly when both sides are immediate CONSTANTS in the grammar's sense — a sign belongs to the literal, and
 * a constant under any operation stops being immediate, so one non-immediate side suffices. Three operands
 * that read no cell clear the gate: {@code BaseYear}, a Base-Year date part, and {@code CurrentRepetition}.
 *
 * <p><b>Corrected 2026-09-22:</b> this first said "written-out literals", which
 * {@link #theGateReadsTheGrammarsConstantAndNotOnlyWrittenOutLiterals} falsifies — the clock constants are
 * not written out and fire anyway. The number rows were measuring one member of a wider production.
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

    /**
     * The erased path. Two of this law's subjects have no typed spelling: a boolean CONSTANT, which the kit
     * offers no literal for, and a written-out operand PATH, whose whole point is the text the kit would
     * otherwise choose for you.
     */
    private static String verdictOf(String rawCondition) {
        return KernelLaws.verdictModel(ORDER, GROUP, "../Reference", "FieldFilled(Reference) And " + rawCondition);
    }

    private static String inLinesVerdictOf(String rawCondition) {
        return KernelLaws.verdictModel(ORDER, LINES_GROUP, "../LineQty", "FieldFilled(LineQty) And " + rawCondition);
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

    /**
     * <b>"Written-out literal" is the wrong denominator: the gate reads the grammar's CONSTANT.</b>
     *
     * <p>A first cut of this law said the gate fires exactly when both sides are immediate written-out
     * literals, which is falsified by the clock constants below — neither is written out and both fire. The
     * kernel's predicate is {@code CheckOpUtils.isConstant}, which asks whether the operand is a grammar
     * {@code konstante}, and {@code commonKonstante} is
     * {@code Falsch | Wahr | Heute | Jetzt | StringLiteral | number} — the boolean and clock constants sit in
     * the same production as the literals. So the rule is IMMEDIACY over that whole set, and the number rows
     * above were measuring one member of it.
     *
     * <p>Basis for the source half: the parser's grammar and {@code CheckOpUtils}; the behaviour is measured
     * here. {@link ClockGroupingComparisonEntryLawsTest} owns what a GROUP around such a constant does.
     */
    @Test
    void theGateReadsTheGrammarsConstantAndNotOnlyWrittenOutLiterals() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two clock constants — Today", verdict(Dsl.today().eq(Dsl.today())));
        actual.put("two clock constants — Now", verdict(Dsl.now().eq(Dsl.now())));
        actual.put("two string literals", verdictOf("\"a\" == \"a\""));
        actual.put("two boolean constants", verdictOf("True == True"));
        actual.put("a clock constant against a FIELD — the control", verdict(Dsl.today().eq(M.date("/Order/OrderedOn"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two clock constants — Today", "MVK_INVALID_COMPARE_TO_CONST");
        expected.put("two clock constants — Now", "MVK_INVALID_COMPARE_TO_CONST");
        expected.put("two string literals", "MVK_INVALID_COMPARE_TO_CONST");
        expected.put("two boolean constants", "MVK_INVALID_COMPARE_TO_CONST");
        // …and one non-constant side clears it, exactly as for the number rows.
        expected.put("a clock constant against a FIELD — the control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the gate reads the grammar's constant");
    }

    /**
     * <b>The coordinate's written-out SPELLING is load-bearing, and it differs by scope.</b>
     *
     * <p>The rows above author the coordinate typed and let the kit choose the text, which is right for them
     * and hides this: the renderer relativizes the operand to the rule's group, so one {@code NumberExpr}
     * reaches the kernel as {@code CurrentRepetition(Lines)} from {@code /Order} and as
     * {@code CurrentRepetition(/Order/Lines)} from inside. Publishing one of those texts for both scopes
     * would be publishing a row that was never run — and the fourth cell shows it is not cosmetic.
     */
    @Test
    void theCoordinatesSpellingIsScopeRelativeAndTheWrongOneIsRefused() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("outside, relative spelling", verdictOf("CurrentRepetition(Lines) > 1"));
        actual.put("outside, absolute spelling", verdictOf("CurrentRepetition(/Order/Lines) > 1"));
        actual.put("inside, absolute spelling", inLinesVerdictOf("CurrentRepetition(/Order/Lines) > 1"));
        actual.put("inside, relative spelling", inLinesVerdictOf("CurrentRepetition(Lines) > 1"));

        Map<String, String> expected = new LinkedHashMap<>();
        // Outside, the binding gate answers whichever way the operand is spelled.
        expected.put("outside, relative spelling", "MVK_NO_WILDCARD");
        expected.put("outside, absolute spelling", "MVK_NO_WILDCARD");
        expected.put("inside, absolute spelling", KernelLaws.ADMITTED);
        // Inside, `Lines` resolves against the rule's own group and names nothing.
        expected.put("inside, relative spelling", "MVK_INVALID_ENTITY");
        assertEquals(expected, actual, "the coordinate's spelling is scope-relative");
    }
}
