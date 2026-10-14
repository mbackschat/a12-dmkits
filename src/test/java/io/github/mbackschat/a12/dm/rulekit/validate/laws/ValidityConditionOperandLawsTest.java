package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §8 — <b>one keyword, two validity conditions, and each has its own single question</b>.
 *
 * <p>{@code Valid} and {@code Invalid} spell two different grammar rules that the operand shape tells apart:
 * {@code Valid(Field, "Type")} is {@code predefinedTypeValidityCondition} and {@code Valid(Date(…))} is
 * {@code dateValidityCondition}. They are checked by two classes that share nothing —
 * {@code CheckPredefinedTypeValidityConditionImpl} asks four things of a field, and
 * {@code CheckDateValidityConditionImpl} asks exactly one thing of a construction.
 *
 * <p>The kind gate is the row worth measuring member by member: {@code isStringEnumOrExtensibleEnum} is
 * {@code isString || isEnum || isExtensibleEnum}, and its third disjunct is a FLAG on a string type rather
 * than a kind of its own — so the admitted set is narrower than the code's name suggests.
 *
 * <p>Own domain (an e-commerce order).
 */
class ValidityConditionOperandLawsTest {

    private static final String GROUP = "/Order";
    private static final String ERROR_REL = "../Reference";

    private static final String WRONG_KIND = "MVK_NO_STRING_OR_ENUM_OR_EXT_ENUM";
    private static final String EMPTY_NAME = "MVK_NO_EMPTY";
    private static final String ONLY_CONSTANTS = "MVK_ONLY_CONSTANTS_USED";

    private static final String ORDER = DocumentModelDraft.named("order-validity-conditions").locale("en_US")
            .rootGroup("Order", order -> order
                    .stringField("Reference", f -> { })
                    .stringField("Code", f -> { })
                    .stringField("Blob", f -> f.lineBreaks().noValueValidation())
                    .enumField("Channel", e -> e.value("WEB").value("STORE"))
                    .numberField("Premium", f -> f.fractionDigits(2))
                    .numberField("Day", f -> f.min("0").maxLength(2))
                    .numberField("Year", f -> f.min("0").maxLength(4))
                    .booleanField("Expedited", f -> { })
                    .confirmField("Acknowledged", f -> { })
                    .customField("Courier", f -> f.type("CourierCode"))
                    .dateField("OrderedOn", f -> f.format("dd.MM.yyyy"))
                    .group("Broker", g -> g.stringField("BrokerName", f -> { }))
                    .group("Items", g -> g.repeatable(20).stringField("ItemCode", f -> { })))
            .build().toJson();

    private static String verdict(String condition) {
        return KernelLaws.verdictModel(ORDER, GROUP, ERROR_REL, "FieldFilled(Reference) And " + condition);
    }

    /** The four gates of the predefined-type arm, and which declarations the kind one calls code-bearing. */
    @Test
    void whatThePredefinedTypeArmDemandsOfItsField() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a string field — the control", verdict("Valid(Code, \"EMail\")"));
        actual.put("an enumeration field", verdict("Valid(Channel, \"EMail\")"));
        actual.put("a group", verdict("Valid(Broker, \"EMail\")"));
        actual.put("a starred field", verdict("Valid(Items*/ItemCode, \"EMail\")"));
        actual.put("a string waiving value validation", verdict("Valid(Blob, \"EMail\")"));
        actual.put("a number field", verdict("Valid(Premium, \"EMail\")"));
        actual.put("a boolean field", verdict("Valid(Expedited, \"EMail\")"));
        actual.put("a confirm field", verdict("Valid(Acknowledged, \"EMail\")"));
        actual.put("a date field", verdict("Valid(OrderedOn, \"EMail\")"));
        actual.put("a CUSTOM-typed field", verdict("Valid(Courier, \"EMail\")"));
        actual.put("an empty type name", verdict("Valid(Code, \"\")"));
        actual.put("a type name nothing registers", verdict("Valid(Code, \"NotRegistered\")"));
        actual.put("a wrong KIND and an empty name together", verdict("Valid(Premium, \"\")"));
        actual.put("a raw string and an empty name together", verdict("Valid(Blob, \"\")"));
        actual.put("the negated spelling, same gates", verdict("Invalid(Premium, \"EMail\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a string field — the control", KernelLaws.ADMITTED);
        expected.put("an enumeration field", KernelLaws.ADMITTED);
        expected.put("a group", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a starred field", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("a string waiving value validation", "MVK_INVALID_RAW_TYPE");
        expected.put("a number field", WRONG_KIND);
        expected.put("a boolean field", WRONG_KIND);
        expected.put("a confirm field", WRONG_KIND);
        expected.put("a date field", WRONG_KIND);
        // The extensible-enum disjunct is a FLAG on a string declaration, not a kind — so a custom type is
        // outside the admitted set even though the code's name reads as if it might carry one.
        expected.put("a CUSTOM-typed field", WRONG_KIND);
        expected.put("an empty type name", EMPTY_NAME);
        // The type name is opaque at model-check time: predefined types are a runtime client SPI, so a name
        // nothing registers is admitted here and only fails when the document is evaluated.
        expected.put("a type name nothing registers", KernelLaws.ADMITTED);
        // Both orderings, because the name gate is LAST and a reader checking the cheap string first would
        // name it on a model A12 refuses for its field.
        expected.put("a wrong KIND and an empty name together", WRONG_KIND);
        expected.put("a raw string and an empty name together", "MVK_INVALID_RAW_TYPE");
        expected.put("the negated spelling, same gates", WRONG_KIND);
        assertEquals(expected, actual, "what the predefined-type arm demands of its field");
    }

    /**
     * The grammar's two shapes, and nothing between them: a validity condition is a {@code Date} construction
     * alone or a field and a type name, so any other operand count or construction fails at the parser.
     */
    @Test
    void whatTheGrammarAdmitsAsAValidityCondition() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a field alone", verdict("Valid(Code)"));
        actual.put("a date field alone", verdict("Valid(OrderedOn)"));
        actual.put("the negated spelling of a field alone", verdict("Invalid(Code)"));
        actual.put("a field, a type name and a third operand", verdict("Valid(Code, \"EMail\", \"IBAN\")"));
        actual.put("a date shift alone", verdict("Valid(AddDays(OrderedOn, 1))"));
        actual.put("a date extract alone", verdict("Valid(DayFromDate(OrderedOn))"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a field alone", "MVK_UNEXPECTED_TOKEN");
        expected.put("a date field alone", "MVK_UNEXPECTED_TOKEN");
        expected.put("the negated spelling of a field alone", "MVK_UNEXPECTED_TOKEN");
        expected.put("a field, a type name and a third operand", "MVK_UNEXPECTED_TOKEN");
        expected.put("a date shift alone", "MVK_UNEXPECTED_TOKEN");
        expected.put("a date extract alone", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "what the grammar admits as a validity condition");
    }

    /** The date arm's one question: the construction must reference at least one field. */
    @Test
    void whatTheDateArmDemandsOfItsConstruction() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a construction naming a field — the control",
                verdict("Valid(Date(Day, \"02\", \"2024\"))"));
        actual.put("a construction of constants only", verdict("Valid(Date(\"01\", \"02\", \"2024\"))"));
        actual.put("the field in the LAST slot", verdict("Valid(Date(\"01\", \"02\", Year))"));
        actual.put("the field in the MIDDLE slot", verdict("Valid(Date(\"01\", Day, \"2024\"))"));
        actual.put("an EXTRACT operator in a slot, which names a field inside itself",
                verdict("Valid(Date(DayFromDate(OrderedOn), \"02\", \"2024\"))"));
        actual.put("an unresolvable construction of constants", verdict("Valid(Date(\"32\", \"02\", \"2024\"))"));
        actual.put("the negated spelling, same question", verdict("Invalid(Date(\"01\", \"02\", \"2024\"))"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a construction naming a field — the control", KernelLaws.ADMITTED);
        expected.put("a construction of constants only", ONLY_CONSTANTS);
        expected.put("the field in the LAST slot", KernelLaws.ADMITTED);
        expected.put("the field in the MIDDLE slot", KernelLaws.ADMITTED);
        // A12's test is `entitySpec() == null` PER SLOT, and an extract operator is a different grammar
        // alternative — so a construction that plainly reads a field is still "only constants" to it.
        expected.put("an EXTRACT operator in a slot, which names a field inside itself", ONLY_CONSTANTS);
        // The slot family's own range gate is a different check on a different class, and it answers first:
        // this arm asks only whether ANY slot names an entity, and never gets to ask it here.
        expected.put("an unresolvable construction of constants", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("the negated spelling, same question", ONLY_CONSTANTS);
        assertEquals(expected, actual, "what the date arm demands of its construction");
    }
}
