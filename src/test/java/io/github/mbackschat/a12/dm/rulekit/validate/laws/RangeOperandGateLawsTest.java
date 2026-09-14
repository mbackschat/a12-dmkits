package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §7 — what the two substring operators demand of the field they cut, and in what order.
 *
 * <p>{@code RangeAsString} and {@code RangeAsNumber} are one kernel method ({@code CheckFeldOpMitParamsImpl}),
 * so the six classes it can draw form a single chain. {@link StringOpLawsTest} measures the interval's own
 * arithmetic and {@link ScalarFieldSlotGroupOperandLawsTest} one group row; what no grid had put to the
 * kernel is which DECLARATIONS the field gate takes, and which class answers when an operand trips two gates
 * at once — the shape an author writes, since a wrong field and a wrong interval are independent mistakes.
 *
 * <p>The kernel's own javadoc lists "strings, predefined types or non-closed enumerations", and the code
 * behind it is {@code FieldUtils.isLikeString}, which tests two interfaces and knows nothing about
 * enumerations. An extensible enum is a STRING declaration carrying suggestions, so it passes as a string
 * rather than as an enumeration — and a closed {@code EnumerationType} does not pass at all. Both rows are
 * measured here, because reading the list as three kinds is exactly the mistake the name invites.
 *
 * <p>Own domain (an e-commerce catalogue).
 */
class RangeOperandGateLawsTest {

    private static final String CATALOG = DocumentModelDraft.named("catalog-range").locale("en_US")
            .rootGroup("Catalog", c -> c
                    .stringField("Reference", f -> { })
                    .stringField("Sku", f -> { })
                    .stringField("RawNote", f -> f.lineBreaks().noValueValidation())
                    .stringField("Region", f -> f.suggestions("en_US", "EU", "US"))
                    .customField("Supplier", f -> f.type("SupplierCode"))
                    .enumField("Tier", e -> e.value("BRONZE").value("GOLD").category("Band", "LOW", "HIGH"))
                    .numberField("UnitCount", f -> f.fractionDigits(0))
                    .booleanField("Perishable", f -> { })
                    .confirmField("Acknowledged", f -> { })
                    .dateField("ShippedOn", f -> f.format("dd.MM.yyyy"))
                    .dateRangeField("Window", f -> f.format("dd.MM.yyyy").separator("-"))
                    .group("Warehouse", w -> w.stringField("WarehouseCode", f -> { }))
                    .group("Lines", g -> g.repeatable(10).stringField("LineCode", f -> { })))
            .build().toJson();

    private static String verdict(String probe) {
        return KernelLaws.verdictModel(CATALOG, "/Catalog", "../Reference", "FieldFilled(Reference) And " + probe);
    }

    /** The operand cut as text, with the interval held VALID so the field gate is the one that answers. */
    private static String cutAsText(String operand) {
        return verdict("RangeAsString(" + operand + ", 1, 3) == \"ABC\"");
    }

    /** The same cut read back as a number — the second carrier of the identical method. */
    private static String cutAsNumber(String operand) {
        return verdict("RangeAsNumber(" + operand + ", 1, 3) > 0");
    }

    @Test
    void whichDeclarationsTheSubstringOperatorsCut() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a string field — the control", cutAsText("Sku"));
        actual.put("a custom field", cutAsText("Supplier"));
        actual.put("an extensible-enum field, which is a string carrying suggestions", cutAsText("Region"));
        actual.put("an enumeration field", cutAsText("Tier"));
        actual.put("a number field", cutAsText("UnitCount"));
        actual.put("a boolean field", cutAsText("Perishable"));
        actual.put("a confirm field", cutAsText("Acknowledged"));
        actual.put("a date field", cutAsText("ShippedOn"));
        actual.put("a date-range field", cutAsText("Window"));
        actual.put("a string field waiving value validation", cutAsText("RawNote"));
        actual.put("the string control read as a NUMBER", cutAsNumber("Sku"));
        actual.put("the enumeration read as a NUMBER", cutAsNumber("Tier"));
        actual.put("a number field read as a NUMBER", cutAsNumber("UnitCount"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a string field — the control", KernelLaws.ADMITTED);
        expected.put("a custom field", KernelLaws.ADMITTED);
        expected.put("an extensible-enum field, which is a string carrying suggestions", KernelLaws.ADMITTED);
        expected.put("an enumeration field", "MVK_INVALID_TYPE");
        expected.put("a number field", "MVK_INVALID_TYPE");
        expected.put("a boolean field", "MVK_INVALID_TYPE");
        expected.put("a confirm field", "MVK_INVALID_TYPE");
        expected.put("a date field", "MVK_INVALID_TYPE");
        expected.put("a date-range field", "MVK_INVALID_TYPE");
        expected.put("a string field waiving value validation", "MVK_INVALID_RAW_TYPE");
        expected.put("the string control read as a NUMBER", KernelLaws.ADMITTED);
        // The two operators share one checker, so the last three rows are what makes that a measurement
        // rather than an assumption: the gate reads the FIELD's declaration and not the result's kind, so
        // a number field is refused by the operator that produces a number.
        expected.put("the enumeration read as a NUMBER", "MVK_INVALID_TYPE");
        expected.put("a number field read as a NUMBER", "MVK_INVALID_TYPE");
        assertEquals(expected, actual, "which declarations the substring operators cut");
    }

    /**
     * Which gate answers when the operand and the interval are BOTH wrong.
     *
     * <p>Every row pairs a second defect with the interval {@code 0, 3}, whose own class
     * {@link StringOpLawsTest} already locks, so a green row states an order rather than a refusal.
     */
    @Test
    void theOrderAmongTheOperandGates() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an invalid interval alone — the control",
                verdict("RangeAsString(Sku, 0, 3) == \"ABC\""));
        actual.put("a GROUP operand, interval also invalid",
                verdict("RangeAsString(Warehouse, 0, 3) == \"ABC\""));
        actual.put("a STARRED operand, interval also invalid",
                verdict("RangeAsString(Lines*/LineCode, 0, 3) == \"ABC\""));
        actual.put("a CATEGORY access, interval also invalid",
                verdict("RangeAsString(Tier -> Band, 0, 3) == \"ABC\""));
        actual.put("an invalid interval on a wrong-TYPE field",
                verdict("RangeAsString(UnitCount, 0, 3) == \"ABC\""));
        actual.put("an invalid interval on a field waiving value validation",
                verdict("RangeAsString(RawNote, 0, 3) == \"ABC\""));
        actual.put("a CATEGORY access with a valid interval", cutAsText("Tier -> Band"));
        actual.put("a STARRED GROUP operand, interval also invalid",
                verdict("RangeAsString(Lines*, 0, 3) == \"ABC\""));
        actual.put("an operand that ITERATES, nothing else wrong", cutAsText("Lines/LineCode"));
        actual.put("an operand that ITERATES, interval also invalid",
                verdict("RangeAsString(Lines/LineCode, 0, 3) == \"ABC\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an invalid interval alone — the control", "MVK_INVALID_INTERVAL");
        expected.put("a GROUP operand, interval also invalid", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a STARRED operand, interval also invalid", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("a CATEGORY access, interval also invalid", "MVK_CATEGORY_NOT_ALLOWED");
        // The interval is arithmetic on the written text and needs nothing from the model, so it answers
        // ahead of both gates that read the declaration — which is the half a reader is likeliest to invert,
        // since the operand is written first.
        expected.put("an invalid interval on a wrong-TYPE field", "MVK_INVALID_INTERVAL");
        expected.put("an invalid interval on a field waiving value validation", "MVK_INVALID_INTERVAL");
        expected.put("a CATEGORY access with a valid interval", "MVK_CATEGORY_NOT_ALLOWED");
        expected.put("a STARRED GROUP operand, interval also invalid", "MVK_NO_GROUPS_ALLOWED");
        // The iteration gate sits OUTSIDE this method and pre-empts all six of its classes, which is why a
        // reader of stored models may say nothing at all about an unstarred read into a repetition — not
        // even about the interval, which needs no model.
        expected.put("an operand that ITERATES, nothing else wrong", "MVK_NO_WILDCARD");
        expected.put("an operand that ITERATES, interval also invalid", "MVK_NO_WILDCARD");
        assertEquals(expected, actual, "the order among the operand gates");
    }
}
