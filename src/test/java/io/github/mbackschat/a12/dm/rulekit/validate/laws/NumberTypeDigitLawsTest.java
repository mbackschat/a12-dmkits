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
}
