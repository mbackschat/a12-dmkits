package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * The scalar value-list pair — the eighth and last of the kernel's A12 fan-in constructs to reach the gate.
 *
 * <p>It looked unauthorable and was not. The record's own value list takes {@code List<Calculation>}, which
 * reads as literals-only, but {@code StringRef.in(StringRef, …)} and {@code NumberRef.in(NumberRef, …)} fill
 * it with field READS (DG10) — so a reference can repeat, and the kernel refuses it. The kernel checks the
 * value list and the subject field as ONE union, values first and the subject appended last, so a subject
 * repeated inside its own value list is reported as the subject.
 *
 * <p>Only the exact arm applies here, and by refusal rather than by choice: a group operand in this construct
 * draws {@code MVK_NO_GROUPS_ALLOWED}, so no operand can contain another.
 * Kernel rows: {@code validate.laws.DuplicateOverlapCarrierSweepLawsTest}.
 */
class ScalarValueListDuplicateGateTest {

    private static final BoundModel ORDER = Fixtures.order();

    private static String refusal(Runnable build) {
        return assertThrows(IllegalArgumentException.class, build::run).getMessage();
    }

    @Test
    void theSubjectRepeatedInsideItsOwnValueListIsRefused() {
        StringRef name = ORDER.str("/Order/CustomerName");
        String message = refusal(() -> name.in(name));
        assertTrue(message.contains("MVK_DUPLICATE_PARAM1"), () -> "expected the kernel's code: " + message);
        assertTrue(message.contains("CustomerName"), () -> "the subject is the witness: " + message);
    }

    @Test
    void theNegatedFormRefusesItToo() {
        StringRef name = ORDER.str("/Order/CustomerName");
        assertTrue(refusal(() -> name.notIn(name)).contains("MVK_DUPLICATE_PARAM1"));
    }

    @Test
    void aNumberSubjectRepeatedInItsOwnValueListIsRefused() {
        NumberRef qty = ORDER.num("/Order/Quantity");
        assertTrue(refusal(() -> qty.in(qty)).contains("MVK_DUPLICATE_PARAM1"));
    }

    /** A reference repeated WITHIN the value list, with the subject distinct — the other half of the union. */
    @Test
    void aReferenceRepeatedWithinTheValueListIsRefused() {
        StringRef code = ORDER.str("/Order/ProductCode");
        String message = refusal(() -> ORDER.str("/Order/CustomerName").in(code, code));
        assertTrue(message.contains("MVK_DUPLICATE_PARAM1"), () -> "expected the kernel's code: " + message);
    }

    /**
     * The union's ORDER, pinned client-side: when the subject's collision and a value-list collision
     * compete, the value list is reached first and is the one named. This is what makes values-first-
     * subject-last load-bearing rather than cosmetic — reversing it would name the subject where the kernel
     * names the value ({@code validate.laws.ValueListQuantifierDuplicateSiblingLawsTest} measures the kernel
     * side in both authored orders).
     */
    @Test
    void whenTwoCollisionsCompeteTheValueListIsNamed() {
        StringRef subject = ORDER.str("/Order/ProductCode");
        StringRef city = ORDER.str("/Order/ShippingAddress/City");
        String message = refusal(() -> subject.in(city, city, subject));
        assertTrue(message.contains("City"), () -> "the value list resolves first: " + message);
        assertFalse(message.contains("ProductCode"),
                () -> "the subject's own collision must not be the witness while a list collision stands: "
                        + message);
    }

    /** The control: a distinct subject and value reference is the operator's ordinary shape. */
    @Test
    void aDistinctSubjectAndValueReferenceAreAdmitted() {
        assertDoesNotThrow(() -> ORDER.str("/Order/CustomerName").in(ORDER.str("/Order/ProductCode")));
    }

    /** Literals cannot duplicate a reference, and the gate must not fire on a plain literal list. */
    @Test
    void aLiteralValueListIsUnaffected() {
        assertDoesNotThrow(() -> ORDER.str("/Order/CustomerName").in("x", "y"));
    }
}
