package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.Verification;

/**
 * A validator rejection reached <b>before</b> the consistency oracle was consulted carries <b>no gate receipt</b>.
 *
 * <p>This is the hole that let a forged {@code verification} reach published output. {@code ValidationResult} used to
 * stamp {@code Backends.verification()} on every result, including the two early returns that never call the kernel —
 * an add-time precheck ({@code RK_NO_SUCH_GROUP}, {@code RK_NAME_EXISTS}) and the conversion catch-all. That value
 * describes the <b>environment</b>: it is {@code KERNEL_CONFIRMED} whenever a backend is on the classpath, which in
 * the CLI is always. The CLI then read it as a gate receipt, so {@code rule add} against a group that does not exist
 * came back {@code valid:false} with {@code verification: KERNEL_CONFIRMED} — an envelope contradicting its own
 * {@code PRECHECK}-sourced diagnostic, and a claim about the kernel for an invocation the kernel never saw.
 *
 * <p>The lesson generalizes past this type: a receipt has to be minted <em>where the work happens</em>. Wrapping an
 * ambient value in a receipt-shaped API moves the hole one layer down rather than closing it, and that is precisely
 * how a design built to make the false claim unrepresentable still published one.
 */
class PreGateRejectionCarriesNoReceiptTest {

    /** The gated path still attests — otherwise the fix would just be silence everywhere. */
    @Test
    void aCheckTheOracleArbitratedCarriesItsReceipt() {
        ValidationResult result = new RuleValidator().check(Fixtures.orderJson(),
                new DraftRule("/Order", "GoodRule", "../CustomerName",
                        "FieldFilled(CustomerName) And [Quantity] > 0", "C", "ERROR"));

        assertEquals(Optional.of(Verification.KERNEL_CONFIRMED), result.consistencyGate(),
                () -> "the oracle ran, so it left a receipt: " + result.diagnostics());
    }

    /** A group that does not exist is caught by OUR precheck — the kernel is never asked. */
    @Test
    void anAddTimePrecheckRejectionCarriesNoReceipt() {
        ValidationResult result = new RuleValidator().check(Fixtures.orderJson(),
                new DraftRule("/NoSuchGroup", "R", "../CustomerName", "FieldFilled(CustomerName)", "C", "ERROR"));

        assertFalse(result.ok(), "precondition: the precheck rejects");
        assertTrue(result.diagnostics().stream()
                        .anyMatch(d -> d.code().filter("RK_NO_SUCH_GROUP"::equals).isPresent()),
                () -> "precondition: it is the precheck that rejected, not the kernel: " + result.diagnostics());
        assertEquals(Optional.empty(), result.consistencyGate(),
                () -> "the kernel was never consulted, so there is nothing to attest: " + result.diagnostics());
    }

    /** The same for a name collision, the other precheck — so the fix covers the class, not one code. */
    @Test
    void aNameCollisionRejectionCarriesNoReceipt() {
        // A field name already taken in /Order: the precheck rejects a rule whose name collides with any element.
        ValidationResult result = new RuleValidator().check(Fixtures.orderJson(),
                new DraftRule("/Order", "CustomerName", "../CustomerName", "FieldFilled(CustomerName)", "C", "ERROR"));

        assertFalse(result.ok(), () -> "precondition: a duplicate name is rejected: " + result.diagnostics());
        assertEquals(Optional.empty(), result.consistencyGate(),
                () -> "a pre-gate collision attests nothing either: " + result.diagnostics());
    }
}
