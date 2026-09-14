package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §11 — <b>the four single-field value operators share one head and then demand four
 * different things, and two of the four surprise a reader of their names</b>.
 * {@code CheckFeldOpImpl.check} gates the operand as a field without a wildcard and then switches on the
 * operator token.
 *
 * <p><b>{@code FieldValueAsString} demands a NUMBER</b>, not a string: it stringifies a numeric value, so a
 * string operand is {@code MVK_NO_NUMBER}. Reading the name as "the value of any field, as a string" is wrong
 * on every non-numeric declaration.
 *
 * <p><b>{@code FieldValueAsNumber} is the one arm that ADMITS a category reference</b>, and the kernel source
 * carries a standing note at the switch asking each new arm to decide that deliberately. The other three
 * refuse it, {@code MVK_CATEGORY_NOT_ALLOWED}.
 *
 * <p><b>Its string arm is far narrower than "a string that looks like a number"</b>: the declaration itself
 * must carry a bounded {@code maxLength} of at most 15 and exactly the pattern {@code [0-9]+}. A five-character
 * string with no pattern is refused, and so is a {@code [0-9]+} string with no length bound — the value a
 * document happens to hold is never consulted.
 *
 * <p><b>Its enumeration arm reads the declared VALUES</b>, every one of which must parse as a number within
 * the same 15-digit bound; under a category reference it reads that category's values instead, which is the
 * row that makes the two readings distinguishable.
 *
 * <p><b>{@code Length} takes a string-LIKE declaration</b> — {@code IStringType} or a predefined/custom type
 * — and an enumeration is neither, so {@code MVK_NO_STRING}.
 *
 * <p>Own domain (an e-commerce order).
 */
class FieldValueOperatorOperandLawsTest {

    private static final String GROUP = "/Order";
    private static final String ERROR_REL = "../Sku";

    private static final String NO_NUMBER = "MVK_NO_NUMBER";
    private static final String NO_STRING = "MVK_NO_STRING";
    private static final String NO_CONVERT = "MVK_NO_CONVERT_IN_NUMBER";
    private static final String NO_GROUPS = "MVK_NO_GROUPS_ALLOWED";
    private static final String NO_WILDCARDS = "MVK_NO_WILDCARDS_ALLOWED";
    private static final String NO_CATEGORY = "MVK_CATEGORY_NOT_ALLOWED";

    private static final String ORDER = DocumentModelDraft.named("order-values").locale("en_US")
            .rootGroup("Order", order -> order
                    .stringField("Sku", f -> { })
                    .stringField("Notes", f -> { })
                    .numberField("Quantity", f -> f.fractionDigits(0))
                    .stringField("DigitCode", f -> f.maxLength(5).pattern("[0-9]+", "digits only"))
                    .stringField("LongDigits", f -> f.maxLength(16).pattern("[0-9]+", "digits only"))
                    .stringField("UnboundedDigits", f -> f.pattern("[0-9]+", "digits only"))
                    .stringField("PlainText", f -> f.maxLength(5))
                    .customField("Serial", f -> f.type("OrderSerial"))
                    .enumField("NumericTier", f -> f.value("1").value("2"))
                    .enumField("TextTier", f -> f.value("A").value("B"))
                    .enumField("BandedTier", f -> f.value("1").value("2").category("Band", "3", "4"))
                    .enumField("TextBandedTier", f -> f.value("1").value("2").category("Band", "X", "Y"))
                    .group("Bundle", g -> g.numberField("BundleQty", f -> f.fractionDigits(0)))
                    .group("Lines", g -> g.repeatable(20).numberField("LineQty", f -> f.fractionDigits(0))))
            .build().toJson();

    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(ORDER, GROUP, ERROR_REL, "FieldFilled(Sku) And " + condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /** Each operator wrapped in a comparison of the class it returns, so the comparison gate never answers. */
    private static String call(String op, String operand) {
        return switch (op) {
            case "FieldValueAsString" -> "FieldValueAsString(" + operand + ") == \"0\"";
            default -> op + "(" + operand + ") > 0";
        };
    }

    private static final List<String> ALL = List.of("AbsValue", "Length", "FieldValueAsNumber",
            "FieldValueAsString");

    /** The head both branches share: one field, no repetition star. */
    @Test
    void everyOperatorRefusesAGroupAndAStarredOperandBeforeItsOwnGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String op : ALL) {
            actual.put(op + " over a group", verdict(call(op, "Bundle")));
            actual.put(op + " over a starred field", verdict(call(op, "Lines*/LineQty")));
            expected.put(op + " over a group", NO_GROUPS);
            expected.put(op + " over a starred field", NO_WILDCARDS);
        }
        assertEquals(expected, actual, "the shared head refuses a group and a star at all four operators");
    }

    /** The three arms that gate on the declared KIND, and the one that inverts its name's reading. */
    @Test
    void theKindEachOperatorDemandsIsNotTheOneItsNameSuggests() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> operands = new LinkedHashMap<>();
        operands.put("a number", "Quantity");
        operands.put("a string", "Notes");
        operands.put("a custom type", "Serial");
        operands.put("an enumeration", "TextTier");
        Map<String, List<String>> admits = Map.of(
                "AbsValue", List.of("a number"),
                "FieldValueAsString", List.of("a number"),
                "Length", List.of("a string", "a custom type"));
        Map<String, String> refusalOf = Map.of(
                "AbsValue", NO_NUMBER, "FieldValueAsString", NO_NUMBER, "Length", NO_STRING);
        for (String op : List.of("AbsValue", "Length", "FieldValueAsString")) {
            operands.forEach((label, field) -> {
                String cell = op + " over " + label;
                actual.put(cell, verdict(call(op, field)));
                expected.put(cell, admits.get(op).contains(label) ? KernelLaws.ADMITTED : refusalOf.get(op));
            });
        }
        assertEquals(expected, actual, "the operand kind each operator takes, over the four declared classes");
    }

    /** The category gate: three arms refuse it, and the number conversion deliberately does not. */
    @Test
    void onlyTheNumberConversionAdmitsACategoryReference() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("AbsValue over a category", verdict(call("AbsValue", "BandedTier -> Band")));
        actual.put("Length over a category", verdict(call("Length", "BandedTier -> Band")));
        actual.put("FieldValueAsString over a category", verdict(call("FieldValueAsString", "BandedTier -> Band")));
        actual.put("FieldValueAsNumber over a numeric category",
                verdict(call("FieldValueAsNumber", "BandedTier -> Band")));
        actual.put("FieldValueAsNumber over a non-numeric category",
                verdict(call("FieldValueAsNumber", "TextBandedTier -> Band")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("AbsValue over a category", NO_CATEGORY);
        expected.put("Length over a category", NO_CATEGORY);
        expected.put("FieldValueAsString over a category", NO_CATEGORY);
        expected.put("FieldValueAsNumber over a numeric category", KernelLaws.ADMITTED);
        // The category's own values are read, not the enumeration's — the row that separates the two readings.
        expected.put("FieldValueAsNumber over a non-numeric category", NO_CONVERT);
        assertEquals(expected, actual, "the number conversion is the one arm with no category gate");
    }

    /** The conversion arm's declaration-level demands, which never consult a stored value. */
    @Test
    void theNumberConversionReadsTheDECLARATIONAndNothingElse() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a bounded digit-patterned string", verdict(call("FieldValueAsNumber", "DigitCode")));
        actual.put("the same pattern over sixteen characters", verdict(call("FieldValueAsNumber", "LongDigits")));
        actual.put("the same pattern with no length bound",
                verdict(call("FieldValueAsNumber", "UnboundedDigits")));
        actual.put("a bounded string with no pattern", verdict(call("FieldValueAsNumber", "PlainText")));
        actual.put("an enumeration whose values parse", verdict(call("FieldValueAsNumber", "NumericTier")));
        actual.put("an enumeration whose values do not", verdict(call("FieldValueAsNumber", "TextTier")));
        actual.put("a number, which is neither string nor enumeration",
                verdict(call("FieldValueAsNumber", "Quantity")));
        actual.put("a custom type", verdict(call("FieldValueAsNumber", "Serial")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a bounded digit-patterned string", KernelLaws.ADMITTED);
        expected.put("the same pattern over sixteen characters", NO_CONVERT);
        expected.put("the same pattern with no length bound", NO_CONVERT);
        expected.put("a bounded string with no pattern", NO_CONVERT);
        expected.put("an enumeration whose values parse", KernelLaws.ADMITTED);
        expected.put("an enumeration whose values do not", NO_CONVERT);
        // The arm branches on `IStringType` then `IEnumerationType` and refuses everything else outright.
        expected.put("a number, which is neither string nor enumeration", NO_CONVERT);
        expected.put("a custom type", NO_CONVERT);
        assertEquals(expected, actual, "the declaration is the whole question; no stored value is consulted");
    }

    /**
     * The arm's third gate, {@code MVK_INVALID_RAW_TYPE}, is UNREACHABLE behind its own predecessor — and
     * both links are measured here rather than argued.
     *
     * <p>It fires only on a string declaring {@code noValueValidation}, and it runs after the conversion gate,
     * which demands the pattern {@code [0-9]+}. Those two declarations are mutually exclusive: A12 refuses the
     * model itself with {@code MVK_STRING_TYPE_PARAMS_INCONSISTENT}. Drop the pattern to make the declaration
     * legal and the conversion gate answers first. So no model reaches the third gate through this operator.
     */
    @Test
    void theRawTypeGateCannotBeReachedThroughTheNumberConversion() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a raw string that also carries the conversion pattern",
                rawVerdict(DocumentModelDraft.named("raw-patterned").locale("en_US")
                        .rootGroup("Order", order -> order
                                .stringField("Sku", f -> { })
                                .stringField("Code", f -> f.maxLength(5).pattern("[0-9]+", "digits only")
                                        .lineBreaks().noValueValidation()))
                        .build().toJson()));
        actual.put("a raw string with the pattern dropped, which is the legal declaration",
                rawVerdict(DocumentModelDraft.named("raw-plain").locale("en_US")
                        .rootGroup("Order", order -> order
                                .stringField("Sku", f -> { })
                                .stringField("Code", f -> f.maxLength(5).lineBreaks().noValueValidation()))
                        .build().toJson()));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a raw string that also carries the conversion pattern",
                "MVK_STRING_TYPE_PARAMS_INCONSISTENT,MVK_INVALID_RAW_TYPE");
        expected.put("a raw string with the pattern dropped, which is the legal declaration", NO_CONVERT);
        assertEquals(expected, actual, "the raw-type gate sits behind a predecessor that excludes its trigger");
    }

    private static String rawVerdict(String json) {
        ValidationResult r = KernelLaws.checkModel(json, GROUP, ERROR_REL,
                "FieldFilled(Sku) And FieldValueAsNumber(Code) > 0");
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }
}
