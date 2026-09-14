package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * A declared number type's fractional-digit pair and its value range.
 *
 * <p>{@code FieldTypeSpec.NumberSpec} refuses four shapes here before the kernel is ever asked — a negative
 * digit count, a scale above 14, an inverted digit pair and an inverted value range — and three of those
 * carry a comment rather than a measurement. This grid is the measurement; a refusal broader than its basis
 * is invisible precisely because the kernel never gets to disagree.
 *
 * <p>The scale cap is the one to read twice: {@code NumberSpec.MAX_SCALE} says 14 and calls a 15 refusal
 * "measured", and the refusal is justified there by TRANSITIVITY — refusing a minimum above 14 "cannot
 * over-reject, because the kernel independently requires min ≤ max". A chain with an unmeasured link is a
 * hypothesis, and both links are measured below.
 *
 * <p>Own domain (the shared order fixture), on {@code /Order/CustomerName}, retyped as a number.
 */
class NumberTypeDigitLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FRACT_DIGITS_INVALID = "MVK_FRACT_DIGITS_INVALID";
    private static final String MIN_ABOVE_MAX_VALUE = "MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE";

    /** The order fixture with {@code CustomerName} retyped as the {@code NumberType} body [json] spells. */
    private static String storedModelWith(String json) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("CustomerName".equals(element.path("name").asText()) && element.has("Field")) {
                    ObjectNode fieldType = MAPPER.createObjectNode();
                    fieldType.put("type", "NumberType");
                    fieldType.set("NumberType", (ObjectNode) MAPPER.readTree(json));
                    ((ObjectNode) element.path("Field")).set("fieldType", fieldType);
                    return root.toString();
                }
            }
            throw new IllegalStateException("the fixture no longer declares the root field `CustomerName`");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    @Test
    void theFractionalDigitPair() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("an ordinary scale — the control", "{\"maxFractionalDigits\":2}");
        rows.put("a minimum above the maximum", "{\"minFractionalDigits\":3,\"maxFractionalDigits\":2}");
        rows.put("a minimum EQUAL to the maximum — the boundary",
                "{\"minFractionalDigits\":2,\"maxFractionalDigits\":2}");
        rows.put("a minimum with NO declared maximum", "{\"minFractionalDigits\":2}");
        rows.put("neither member", "{}");
        rows.put("a NEGATIVE maximum", "{\"maxFractionalDigits\":-1}");
        rows.put("a NEGATIVE minimum", "{\"minFractionalDigits\":-1,\"maxFractionalDigits\":2}");
        rows.put("a NEGATIVE minimum with no maximum", "{\"minFractionalDigits\":-1}");
        // The discriminator, and it splits ABSENT from NEGATIVE. A minimum of 2 with no maximum IS refused,
        // so an absent maximum reads as 0; a minimum of 1 beside an explicit -1 is ADMITTED, so a negative
        // maximum takes the pair out of the check instead of being compared. The observable law is therefore
        // "max >= 0 and min > max", with an absent member reading as 0 — never a plain min > max.
        rows.put("a ZERO minimum with a negative maximum",
                "{\"minFractionalDigits\":0,\"maxFractionalDigits\":-1}");
        rows.put("a ONE minimum with a negative maximum",
                "{\"minFractionalDigits\":1,\"maxFractionalDigits\":-1}");

        // The scale cap our own NumberSpec refuses at, and the boundary below it.
        rows.put("a maximum of 14 — the cap this kit claims", "{\"maxFractionalDigits\":14}");
        rows.put("a maximum of 15 — one past it", "{\"maxFractionalDigits\":15}");
        rows.put("a minimum of 15 with a maximum of 15",
                "{\"minFractionalDigits\":15,\"maxFractionalDigits\":15}");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, json) -> actual.put(row, KernelLaws.verdictOf(storedModelWith(json))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an ordinary scale — the control", KernelLaws.ADMITTED);
        expected.put("a minimum above the maximum", FRACT_DIGITS_INVALID);
        expected.put("a minimum EQUAL to the maximum — the boundary", KernelLaws.ADMITTED);
        expected.put("a minimum with NO declared maximum", FRACT_DIGITS_INVALID);
        expected.put("neither member", KernelLaws.ADMITTED);
        expected.put("a NEGATIVE maximum", KernelLaws.ADMITTED);
        expected.put("a NEGATIVE minimum", KernelLaws.ADMITTED);
        expected.put("a NEGATIVE minimum with no maximum", KernelLaws.ADMITTED);
        expected.put("a ZERO minimum with a negative maximum", KernelLaws.ADMITTED);
        expected.put("a ONE minimum with a negative maximum", KernelLaws.ADMITTED);
        expected.put("a maximum of 14 — the cap this kit claims", KernelLaws.ADMITTED);
        expected.put("a maximum of 15 — one past it", KernelLaws.NOT_BUILDABLE);
        expected.put("a minimum of 15 with a maximum of 15", KernelLaws.NOT_BUILDABLE);
        assertEquals(expected, actual, "a number type's fractional-digit pair");
    }

    @Test
    void theValueRange() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("an ordinary range — the control", "{\"minValue\":0,\"maxValue\":100}");
        rows.put("a minimum above the maximum", "{\"minValue\":10,\"maxValue\":5}");
        rows.put("a minimum EQUAL to the maximum — the boundary", "{\"minValue\":5,\"maxValue\":5}");
        rows.put("a minimum with no maximum", "{\"minValue\":10}");
        rows.put("a maximum with no minimum", "{\"maxValue\":10}");
        rows.put("a NEGATIVE range", "{\"minValue\":-10,\"maxValue\":-5}");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, json) -> actual.put(row, KernelLaws.verdictOf(storedModelWith(json))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an ordinary range — the control", KernelLaws.ADMITTED);
        expected.put("a minimum above the maximum", MIN_ABOVE_MAX_VALUE);
        expected.put("a minimum EQUAL to the maximum — the boundary", KernelLaws.ADMITTED);
        expected.put("a minimum with no maximum", KernelLaws.ADMITTED);
        expected.put("a maximum with no minimum", KernelLaws.ADMITTED);
        expected.put("a NEGATIVE range", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a number type's value range");
    }

    /**
     * The two laws that demand a DECLARATION rather than compare two of them.
     *
     * <p>Both are gated on the number's trait: A12's metamodel spells the gate as three of its own type
     * names, of which a stored {@code NumberType} reaches the plain one and — through {@code trait:"amount"}
     * — the two money ones. So the amount arm is measured beside the plain one rather than assumed, and the
     * percent arm is measured to show the gate is the trait rather than the presence of a fractional part.
     *
     * <p><b>Only one of the two is reachable, and the amount law's unreachability has a source basis as well
     * as these rows.</b> {@code TypeConverter.convertNumber} fills the integer-place count with
     * {@code 15 - maxFractionalDigits} for every amount that declares none, and
     * {@code FieldDataCollector.addNumberType} writes the member only when it is present — so on the arm the
     * law gates itself to, the member it demands is never absent. The fractional pair is the opposite case:
     * both members are primitive counts that the collector suppresses at {@code -1} alone, so "declared as
     * zero" and "not declared" are distinguishable and the law is reachable.
     */
    @Test
    void theDeclarationDemands() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("an ordinary scale — the control", "{\"maxFractionalDigits\":2}");
        rows.put("BOTH fractional members negative",
                "{\"minFractionalDigits\":-1,\"maxFractionalDigits\":-1}");
        rows.put("only the MAXIMUM negative", "{\"minFractionalDigits\":0,\"maxFractionalDigits\":-1}");
        rows.put("an AMOUNT with no integer places", "{\"trait\":\"amount\",\"maxFractionalDigits\":2}");
        rows.put("the same amount WITH integer places",
                "{\"trait\":\"amount\",\"maxFractionalDigits\":2,\"maxIntegerDigits\":5}");
        rows.put("an amount with no cents and no integer places", "{\"trait\":\"amount\"}");
        rows.put("a PERCENT with no integer places", "{\"trait\":\"percent\",\"maxFractionalDigits\":2}");
        rows.put("an amount whose fractional members are both negative",
                "{\"trait\":\"amount\",\"maxIntegerDigits\":5,\"minFractionalDigits\":-1,"
                        + "\"maxFractionalDigits\":-1}");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, json) -> actual.put(row, KernelLaws.verdictOf(storedModelWith(json))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an ordinary scale — the control", KernelLaws.ADMITTED);
        expected.put("BOTH fractional members negative", "MVK_FRACT_DIGITS_MISSING");
        expected.put("only the MAXIMUM negative", KernelLaws.ADMITTED);
        // The three amount rows are the unreachability, and they cover both money arms — with cents and
        // without, which is the only axis A12's own type name splits on.
        expected.put("an AMOUNT with no integer places", KernelLaws.ADMITTED);
        expected.put("the same amount WITH integer places", KernelLaws.ADMITTED);
        expected.put("an amount with no cents and no integer places", KernelLaws.ADMITTED);
        expected.put("a PERCENT with no integer places", KernelLaws.ADMITTED);
        expected.put("an amount whose fractional members are both negative", "MVK_FRACT_DIGITS_MISSING");
        assertEquals(expected, actual, "a number type's declaration demands");
    }

    /**
     * The four laws that measure a declaration against the model-wide DIGIT BUDGET, plus the length pair.
     *
     * <p>The budget is a member of A12's metamodel document, not of a stored model, so its value for a
     * stored one is derived here rather than assumed — the length rows walk the threshold instead of naming
     * it. The inverted length pair is measured on THIS carrier because the class it draws was measured on
     * the string type: the metamodel states the same law twice, once per carrier, and a class measured on
     * one carrier is not a class measured on the other.
     */
    @Test
    void theDigitBudget() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("an ordinary length — the control", "{\"maxLength\":10}");
        rows.put("a maximum length of 15", "{\"maxLength\":15}");
        rows.put("a maximum length of 16", "{\"maxLength\":16}");
        rows.put("a maximum length of 17", "{\"maxLength\":17}");
        rows.put("a minimum length of 17", "{\"minLength\":17}");
        rows.put("a maximum length of 16 on a SIGNLESS number", "{\"positivesOnly\":true,\"maxLength\":16}");
        rows.put("a maximum length of 17 beside a fractional part",
                "{\"maxFractionalDigits\":2,\"maxLength\":17}");
        rows.put("a maximum length of 18 beside a fractional part",
                "{\"maxFractionalDigits\":2,\"maxLength\":18}");
        rows.put("an over-long maximum where NEITHER fractional member is declared",
                "{\"minFractionalDigits\":-1,\"maxFractionalDigits\":-1,\"maxLength\":20}");
        rows.put("the same undeclared pair at a maximum length of 17",
                "{\"minFractionalDigits\":-1,\"maxFractionalDigits\":-1,\"maxLength\":17}");
        rows.put("a maximum length below the fractional part",
                "{\"minFractionalDigits\":3,\"maxFractionalDigits\":3,\"maxLength\":4}");
        rows.put("the same fractional part one length up",
                "{\"minFractionalDigits\":3,\"maxFractionalDigits\":3,\"maxLength\":5}");
        rows.put("an INVERTED length pair", "{\"minLength\":8,\"maxLength\":4}");
        rows.put("integer and fractional places past the budget",
                "{\"maxIntegerDigits\":14,\"minFractionalDigits\":2,\"maxFractionalDigits\":2}");
        rows.put("the same places within it",
                "{\"maxIntegerDigits\":5,\"minFractionalDigits\":2,\"maxFractionalDigits\":2}");
        rows.put("integer places alone at the budget", "{\"maxIntegerDigits\":15}");
        rows.put("integer places one past the budget", "{\"maxIntegerDigits\":16}");
        rows.put("integer places well past the budget", "{\"maxIntegerDigits\":20}");
        rows.put("an amount whose maximum scale is suppressed",
                "{\"trait\":\"amount\",\"maxFractionalDigits\":-1}");
        rows.put("an amount whose minimum scale exceeds its maximum",
                "{\"trait\":\"amount\",\"minFractionalDigits\":5,\"maxFractionalDigits\":2}");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, json) -> actual.put(row, KernelLaws.verdictOf(storedModelWith(json))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an ordinary length — the control", KernelLaws.ADMITTED);
        expected.put("a maximum length of 15", KernelLaws.ADMITTED);
        expected.put("a maximum length of 16", KernelLaws.ADMITTED);
        expected.put("a maximum length of 17", "MVK_MAX_LENGTH_TOO_BIG");
        expected.put("a minimum length of 17", "MVK_MIN_LENGTH_TOO_BIG");
        // The three discriminators that DERIVE the budget instead of assuming it: 16 is admitted on a signed
        // number and refused on a signless one, and a declared fractional part buys one more place still.
        // So the threshold is 15 + (a sign) + (a fractional part), and the budget itself is 15.
        expected.put("a maximum length of 16 on a SIGNLESS number", "MVK_MAX_LENGTH_TOO_BIG");
        expected.put("a maximum length of 17 beside a fractional part", KernelLaws.ADMITTED);
        expected.put("a maximum length of 18 beside a fractional part", "MVK_MAX_LENGTH_TOO_BIG");
        // …and a SUPPRESSED fractional member behaves as a ZERO one rather than as a declared fractional
        // part: 17 is refused here where it is admitted two rows up, so the threshold is 16 and the branch
        // taken is the one testing `== 0` against a member the collector never wrote. The pair therefore
        // costs nothing at all — a checker that read `-1` as "some fractional part" would allow 17 and
        // under-report. The second row is what discriminates; the first alone is consistent with either.
        expected.put("an over-long maximum where NEITHER fractional member is declared",
                "MVK_FRACT_DIGITS_MISSING,MVK_MAX_LENGTH_TOO_BIG");
        expected.put("the same undeclared pair at a maximum length of 17",
                "MVK_FRACT_DIGITS_MISSING,MVK_MAX_LENGTH_TOO_BIG");
        expected.put("a maximum length below the fractional part", "MVK_MAX_LENGTH_TOO_SMALL");
        expected.put("the same fractional part one length up", KernelLaws.ADMITTED);
        expected.put("an INVERTED length pair", "MVK_MIN_LENGTH_BIGGER_MAX_LENGTH");
        expected.put("integer and fractional places past the budget", "MVK_ARITY_TOO_BIG");
        expected.put("the same places within it", KernelLaws.ADMITTED);
        expected.put("integer places alone at the budget", KernelLaws.ADMITTED);
        // An integer-place count ABOVE the budget is a build refusal rather than an arity finding, so the
        // arity law is reachable only from a count within the budget that a fractional part then pushes
        // past it. This is the same tier the scale cap sits in, and the family reports it the same way.
        expected.put("integer places one past the budget", KernelLaws.NOT_BUILDABLE);
        expected.put("integer places well past the budget", KernelLaws.NOT_BUILDABLE);
        // The amount arm reaches both through a DERIVED count of `15 - maxFractionalDigits`: suppressing the
        // maximum derives 16 and is refused at the build tier, while a minimum above the maximum leaves the
        // count buildable and puts the SUM over the budget — so the amount arm does reach the arity law,
        // beside the inversion class rather than instead of it.
        expected.put("an amount whose maximum scale is suppressed", KernelLaws.NOT_BUILDABLE);
        expected.put("an amount whose minimum scale exceeds its maximum",
                "MVK_FRACT_DIGITS_INVALID,MVK_ARITY_TOO_BIG");
        assertEquals(expected, actual, "a number type against the model-wide digit budget");
    }
}
