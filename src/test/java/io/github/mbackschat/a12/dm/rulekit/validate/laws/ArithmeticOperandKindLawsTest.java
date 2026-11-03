package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §5 — <b>what each arithmetic operator admits as an operand, and the one operator that is
 * not about numbers at all</b>.
 *
 * <p>{@code CheckOperationImpl.checkAndSetTypes} switches on the operator's grammar context. Four of the five
 * call {@code CheckOpUtils.checkOpIsANumberInCalc} on both operands; {@code +} calls it on neither and asks
 * its own question instead, so the two classes never contend.
 *
 * <p><b>{@code +} concatenates</b> when both sides are string-LIKE and adds when both are number-like, and a
 * MIXED pair is {@code MVK_NO_STRING_OR_NUMBER_FOR_ADD} — not {@code MVK_NO_NUMBER_FOR_CALC}, which is the
 * class every other operator draws on the same operand.
 *
 * <p><b>An enumeration's display LABELS play no part</b> on kernel 31.1.1, which deleted the gate that refused a
 * remapping one under that same class (KERNEL-FINDINGS KF396, KF655). The grid varies exactly that one property;
 * labels that only repeat their values, and a braced operand, are
 * {@code adapter.laws.CheckerEnumerationAdditionAgreementTest}'s rows.
 *
 * <p><b>A custom-typed field is string-LIKE here</b>, which the operation-type map makes explicit — it sorts
 * {@code IStringType} and {@code IPredefinedType} into one STRING base type, and {@code IPredefinedType} is what the
 * model mapper builds for every {@code CustomFieldType}, not a third kind.
 *
 * <p>Own domain (an e-commerce order).
 */
class ArithmeticOperandKindLawsTest {

    private static final String GROUP = "/Order";
    private static final String ERROR_REL = "../Reference";

    private static final String NOT_A_NUMBER = "MVK_NO_NUMBER_FOR_CALC";
    private static final String MIXED_ADD = "MVK_NO_STRING_OR_NUMBER_FOR_ADD";

    private static final String ORDER = DocumentModelDraft.named("order-arithmetic").locale("en_US")
            .rootGroup("Order", order -> order
                    .stringField("Reference", f -> { })
                    .stringField("Notes", f -> { })
                    .numberField("Quantity", f -> f.fractionDigits(0))
                    .numberField("UnitPrice", f -> f.fractionDigits(2))
                    .field("PlacedOn", FieldKind.DATE)
                    .field("Expedited", FieldKind.BOOLEAN)
                    .field("TermsAccepted", FieldKind.CONFIRM)
                    .customField("Serial", f -> f.type("OrderSerial"))
                    .enumField("PlainChannel", e -> e.value("WEB").value("STORE"))
                    .enumField("LabelledChannel", e -> e
                            .value("WEB", l -> l.label("en_US", "Web shop"))
                            .value("STORE", l -> l.label("en_US", "In store"))))
            .build().toJson();

    private static String verdict(String condition) {
        return KernelLaws.verdictModel(ORDER, GROUP, ERROR_REL, "FieldFilled(Reference) And " + condition);
    }

    /** The four operators that demand a number, over every declared kind this model carries. */
    @Test
    void everyOperatorButAdditionDemandsANumberOnBothSides() {
        Map<String, String> operands = new LinkedHashMap<>();
        operands.put("a number", "Quantity");
        operands.put("a string", "Notes");
        operands.put("a custom type", "Serial");
        operands.put("an enumeration", "PlainChannel");
        operands.put("a date", "PlacedOn");
        operands.put("a boolean", "Expedited");
        operands.put("a confirm", "TermsAccepted");

        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String operator : List.of("*", "/", "-", "^")) {
            operands.forEach((label, field) -> {
                String cell = "`" + operator + "` over " + label;
                actual.put(cell, verdict("{[Quantity] " + operator + " [" + field + "]} > 0"));
                expected.put(cell, "a number".equals(label) ? KernelLaws.ADMITTED : NOT_A_NUMBER);
            });
        }
        assertEquals(expected, actual, "the four numeric operators, over the seven declared kinds");
    }

    /** Addition's own question: both sides number-like, or both string-like, and nothing mixed. */
    @Test
    void additionTakesTwoNumbersOrTwoStringsAndRefusesAMixedPair() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two numbers", verdict("{[Quantity] + [UnitPrice]} > 0"));
        actual.put("two strings", verdict("{[Notes] + [Reference]} == \"x\""));
        actual.put("a string and a custom type", verdict("{[Notes] + [Serial]} == \"x\""));
        actual.put("a string and a labelless enumeration", verdict("{[Notes] + [PlainChannel]} == \"x\""));
        actual.put("a number and a string", verdict("{[Quantity] + [Notes]} > 0"));
        actual.put("a number and a date", verdict("{[Quantity] + [PlacedOn]} > 0"));
        actual.put("a string and a boolean", verdict("{[Notes] + [Expedited]} == \"x\""));
        actual.put("a number and a written-out number", verdict("{[Quantity] + 1} > 0"));
        actual.put("a string and a written-out text", verdict("{[Notes] + \"x\"} == \"x\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two numbers", KernelLaws.ADMITTED);
        expected.put("two strings", KernelLaws.ADMITTED);
        // The operation-type map sorts a custom type into the STRING base type beside `IStringType`.
        expected.put("a string and a custom type", KernelLaws.ADMITTED);
        expected.put("a string and a labelless enumeration", KernelLaws.ADMITTED);
        expected.put("a number and a string", MIXED_ADD);
        expected.put("a number and a date", MIXED_ADD);
        expected.put("a string and a boolean", MIXED_ADD);
        expected.put("a number and a written-out number", KernelLaws.ADMITTED);
        expected.put("a string and a written-out text", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "addition's two admitted pairings and the mixed ones it refuses");
    }

    /** Whether two enumerations of the same tokens carry display labels no longer separates them. */
    @Test
    void additionJoinsAnEnumerationWhateverItsLabels() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a labelless enumeration on the left", verdict("{[PlainChannel] + [Notes]} == \"x\""));
        actual.put("a labelled enumeration on the left", verdict("{[LabelledChannel] + [Notes]} == \"x\""));
        actual.put("a labelled enumeration on the right", verdict("{[Notes] + [LabelledChannel]} == \"x\""));
        actual.put("two labelless enumerations", verdict("{[PlainChannel] + [PlainChannel]} == \"x\""));
        actual.put("a labelled enumeration under a numeric operator",
                verdict("{[Quantity] * [LabelledChannel]} > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a labelless enumeration on the left", KernelLaws.ADMITTED);
        expected.put("a labelled enumeration on the left", KernelLaws.ADMITTED);
        expected.put("a labelled enumeration on the right", KernelLaws.ADMITTED);
        expected.put("two labelless enumerations", KernelLaws.ADMITTED);
        expected.put("a labelled enumeration under a numeric operator", NOT_A_NUMBER);
        assertEquals(expected, actual, "display labels decide nothing, and the numeric arm still refuses");
    }

    /**
     * The nesting rows: an inner operation's RESULT type is what the outer operator sees, which is the only
     * way the two classes can be made to meet on one expression.
     */
    @Test
    void anInnerOperationsRESULTTypeIsWhatTheOuterOperatorJudges() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a concatenation subtracted from", verdict("{{[Notes] + [Reference]} - [Quantity]} > 0"));
        actual.put("a product added to a string", verdict("{{[Quantity] * [UnitPrice]} + [Notes]} > 0"));
        actual.put("a product added to a number", verdict("{{[Quantity] * [UnitPrice]} + [UnitPrice]} > 0"));
        actual.put("a string multiplied inside an admitted addition",
                verdict("{[Quantity] + {[Notes] * [Quantity]}} > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a concatenation subtracted from", NOT_A_NUMBER);
        expected.put("a product added to a string", MIXED_ADD);
        expected.put("a product added to a number", KernelLaws.ADMITTED);
        // The inner context is checked on its own, so its defect answers before the outer pairing is asked.
        expected.put("a string multiplied inside an admitted addition", NOT_A_NUMBER);
        assertEquals(expected, actual, "the outer operator judges the inner operation's result type");
    }

    /**
     * The KIND walk runs ahead of both arity gates inside one operation context, so an expression violating
     * a kind law and an arity law draws the kind class alone.
     *
     * <p>This is what forces the two questions into ONE portable family: a checker reporting them
     * independently would name a second class on a model A12 answers once, which is an over-report by
     * construction. The controls are the same expressions with a number in the offending position.
     */
    @Test
    void theKindWalkPreEmptsBothArityGates() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a string in a two-division chain", verdict("{[Notes] / [Quantity] / [Quantity]} > 0"));
        actual.put("the same chain, all numbers", verdict("{[UnitPrice] / [Quantity] / [Quantity]} > 0"));
        actual.put("a string in a nested power", verdict("{[Notes] ^ [Quantity] ^ [Quantity]} > 0"));
        actual.put("the same power, all numbers", verdict("{[Quantity] ^ [Quantity] ^ [Quantity]} > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a string in a two-division chain", NOT_A_NUMBER);
        expected.put("the same chain, all numbers", "MVK_TOO_MANY_DIV_FOR_CALC");
        expected.put("a string in a nested power", NOT_A_NUMBER);
        expected.put("the same power, all numbers", "MVK_TOO_MANY_POW_FOR_CALC");
        assertEquals(expected, actual, "the kind class answers alone where an arity law is also broken");
    }
}
