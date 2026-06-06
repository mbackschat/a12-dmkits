package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Behavioral law — <b>formal errors and the "unknown" state</b> (KERNEL-SEMANTICS §3). A value that
 * violates its field's data-type configuration (wrong type, too many decimal places, leading/trailing
 * spaces, …) produces a <b>formal error</b>, and while that error stands the field is <b>"unknown"</b>
 * and <b>cannot be evaluated in any validation rule</b>.
 *
 * <p>The trap: a malformed value <em>silently suppresses</em> an author's rule that references it — the
 * rule simply does not fire (its condition is never evaluated), even when the would-be value clearly
 * violates it. The kernel reports its own formal-error code instead; the author's rule stays quiet.
 * These tests pin both halves: the formal-error code appears, and the author rule does <em>not</em>.
 */
class FormalErrorLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ORDER_ROW = Set.of("/Order");

    @Test
    void tooManyDecimalsMakesTheFieldUnknownAndSuppressesTheRule() {
        // UnitPrice is a scale-2 number; rule "[UnitPrice] > 5"
        String model = RuntimeLaws.withRule(ORDER, "/Order", "PriceHigh", "../UnitPrice", "PRICE_HIGH",
                "[UnitPrice] > 5");

        // a valid value is evaluated normally
        assertTrue(RuntimeLaws.fires(model, "PRICE_HIGH", ORDER_ROW, filled("/Order/UnitPrice", "10.00")),
                "a valid 10.00 > 5 fires");

        // 10.999 has three decimals → formal error → field unknown → the rule is NOT evaluated…
        Set<String> codes = RuntimeLaws.firedCodes(model, ORDER_ROW, filled("/Order/UnitPrice", "10.999"));
        assertFalse(codes.contains("PRICE_HIGH"),
                () -> "a too-many-decimals value makes the field unknown, so [UnitPrice] > 5 is not evaluated: " + codes);
        // …and the kernel reports its own formal-error code instead (the field is flagged, not silently ok)
        assertTrue(codes.contains("zahlFalscheAnzahlNachkommastellen"),
                () -> "the kernel reports the formal decimal-places error: " + codes);
    }

    @Test
    void nonNumericValueMakesTheFieldUnknownAndSuppressesTheRule() {
        String model = RuntimeLaws.withRule(ORDER, "/Order", "PriceHigh", "../UnitPrice", "PRICE_HIGH",
                "[UnitPrice] > 5");

        Set<String> codes = RuntimeLaws.firedCodes(model, ORDER_ROW, filled("/Order/UnitPrice", "abc"));
        assertFalse(codes.contains("PRICE_HIGH"),
                () -> "a non-numeric value makes the field unknown, so the rule is not evaluated: " + codes);
        assertTrue(codes.contains("zahlHatUngueltigeZeichen"),
                () -> "the kernel reports the formal invalid-characters error: " + codes);
    }

    @Test
    void aLeadingOrTrailingSpaceIsAFormalError() {
        // KERNEL-SEMANTICS §3: leading/trailing spaces are a formal error (string value " ABC ")
        String model = RuntimeLaws.withRule(ORDER, "/Order", "CodeAbc", "../ProductCode", "CODE_ABC",
                "[ProductCode] == \"ABC\"");

        Set<String> codes = RuntimeLaws.firedCodes(model, ORDER_ROW, filled("/Order/ProductCode", " ABC "));
        assertTrue(codes.contains("fuehrendesBlank"),
                () -> "a leading space is a formal error: " + codes);
        assertFalse(codes.contains("CODE_ABC"),
                () -> "the malformed string is unknown, so the author rule is not evaluated: " + codes);
    }

    /** A filled /Order row (CustomerName) plus the field under test. */
    private static Map<String, String> filled(String fieldPath, String value) {
        return Map.of("/Order/CustomerName", "Acme", fieldPath, value);
    }
}
