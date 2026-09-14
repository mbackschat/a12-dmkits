package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §11 — <b>where a CUSTOM-typed field stands in the operand gates that sort declarations by
 * kind</b>: it is text-LIKE wherever a string is admitted and never a number.
 *
 * <p>A12's {@code FieldUtils} puts {@code IPredefinedType} beside {@code IStringType} under
 * {@code isLikeString} and leaves it out of {@code isNumber}, so a custom declaration travels with strings
 * through the text gates and is refused by every numeric one. The grid measures that on the five carriers
 * that ask the question independently — the value-list constant arm, both rounding constructs, the
 * field-list arms, and a date shift's amount — because each asks it in its own method.
 *
 * <p>The grid exists because a checker matched the token {@code CustomType} while A12's stored discriminator
 * is {@code CustomFieldType}, so every one of these gates silently saw a type it had no row for. The rows
 * below are what the corrected token has to agree with; the text-constant row is the one that was an
 * over-rejection rather than a silence.
 *
 * <p>Own domain (an e-commerce order).
 */
class CustomFieldTypeOperandLawsTest {

    private static final String GROUP = "/Order";
    private static final String ERROR_REL = "../Reference";

    private static final String NO_NUMBER_ROUNDING = "MVK_NO_NUMBER";

    private static final String ORDER = DocumentModelDraft.named("order-custom").locale("en_US")
            .rootGroup("Order", order -> order
                    .stringField("Reference", f -> { })
                    .numberField("Quantity", f -> f.fractionDigits(0))
                    .field("PlacedOn", FieldKind.DATE)
                    .customField("Serial", f -> f.type("OrderSerial")))
            .build().toJson();

    private static String verdict(String condition) {
        return KernelLaws.verdictModel(ORDER, GROUP, ERROR_REL, "FieldFilled(Reference) And " + condition);
    }

    /** Every numeric gate, each asked in its own kernel method, over the same custom declaration. */
    @Test
    void aCustomDeclarationIsRefusedByEveryGateThatDemandsANumber() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the rounding field spelling", verdict("RoundUpValue(Serial, 2) > 0"));
        actual.put("the rounding operation spelling", verdict("RoundUp([Serial], 2) > 0"));
        actual.put("a field list that is added up", verdict("Sum(Serial, Quantity) > 0"));
        actual.put("a field list that is ordered", verdict("MaxValue(Serial, Quantity) > 0"));
        actual.put("the amount a date is shifted by", verdict("[PlacedOn] < AddDays(PlacedOn, [Serial])"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the rounding field spelling", NO_NUMBER_ROUNDING);
        expected.put("the rounding operation spelling", NO_NUMBER_ROUNDING);
        expected.put("a field list that is added up", "MVK_NO_NUMBER");
        expected.put("a field list that is ordered", "MVK_NOT_SORTABLE");
        expected.put("the amount a date is shifted by", "MVK_NON_NUMERIC");
        assertEquals(expected, actual, "a custom declaration is never a number, on any carrier that asks");
    }

    /** The text side of the same question, which is what makes the missing row an over-rejection. */
    @Test
    void aCustomDeclarationTravelsWithStringsThroughTheTextGates() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a text constant over a custom field",
                verdict("NumberOfValueInFields(\"X\" In Serial, Reference) > 0"));
        actual.put("a text constant over a number field, the control",
                verdict("NumberOfValueInFields(\"X\" In Quantity, Reference) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a text constant over a custom field", KernelLaws.ADMITTED);
        expected.put("a text constant over a number field, the control", "MVK_NO_STRING_OR_ENUM");
        assertEquals(expected, actual, "the text arm admits a custom declaration and refuses a number one");
    }
}
