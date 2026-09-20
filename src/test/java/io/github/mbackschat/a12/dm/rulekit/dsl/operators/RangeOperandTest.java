package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.RangeAsNumber;
import io.github.mbackschat.a12.dm.rulekit.dsl.RangeAsString;

/**
 * Substring/interval operands {@code RangeAsString}/{@code RangeAsNumber} — the bounds pre-check
 * mirrors the kernel's interval law (0 &lt; start ≤ end, {@code MVK_INVALID_INTERVAL};
 * MVK-LEDGER ParserErrorTypesEnum row).
 */
class RangeOperandTest {

    private static final BoundModel m = Fixtures.subscription();
    private static final FieldRef EMAIL = m.str("/Subscription/Billing/InvoiceEmail").field();

    @Test
    void buildsWithinTheKernelIntervalLaw() {
        assertEquals(ValueKind.STRING, new RangeAsString(EMAIL, 1, 3).kind());
        assertEquals(ValueKind.NUMBER, new RangeAsNumber(EMAIL, 2, 2).kind());
    }

    @Test
    void rejectsAZeroStart() {
        // kernel positions are 1-based: start 0 draws MVK_INVALID_INTERVAL — pre-checked
        for (Exception ex : new Exception[]{
                assertThrows(IllegalArgumentException.class, () -> new RangeAsString(EMAIL, 0, 2)),
                assertThrows(IllegalArgumentException.class, () -> new RangeAsNumber(EMAIL, 0, 2))}) {
            assertTrue(ex.getMessage().contains("MVK_INVALID_INTERVAL"), ex::getMessage);
            // The message keeps the code for a human; the TYPE carries it for a caller, which is the whole
            // point of routing this through Refusals.of — a catch site no longer has to parse prose to
            // learn which law it broke (API-FINDINGS AF154).
            assertEquals(KernelCode.MVK_INVALID_INTERVAL,
                    assertInstanceOf(KernelCodeRefusal.class, ex).code());
        }
    }

    @Test
    void rejectsAStartBeyondTheEnd() {
        for (Exception ex : new Exception[]{
                assertThrows(IllegalArgumentException.class, () -> new RangeAsString(EMAIL, 3, 2)),
                assertThrows(IllegalArgumentException.class, () -> new RangeAsNumber(EMAIL, 3, 2))}) {
            assertTrue(ex.getMessage().contains("MVK_INVALID_INTERVAL"), ex::getMessage);
        }
    }
}
