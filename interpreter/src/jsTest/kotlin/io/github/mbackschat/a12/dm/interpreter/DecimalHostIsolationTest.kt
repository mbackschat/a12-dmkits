package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals

/** The SHARED big.js constructor (module default) — a co-resident consumer would configure this global. */
@JsModule("big.js")
@JsNonModule
private external val hostBig: dynamic

/**
 * IF153 host-isolation lock (JS-only). big.js's `DP`/`RM`/`PE`/`NE` are MUTABLE statics on the shared
 * constructor, so any other big.js consumer sharing the JS runtime could wreck the interpreter's decimal
 * context. `Dec.js.kt` defends against this by cloning its OWN constructor and configuring only that — so a
 * hostile mutation of the host `Big` here must leave every [Dec] contract intact. This is exactly the divergence
 * class the [DecimalHostParityTest] vectors pin, re-checked under an adversarially-corrupted host context.
 */
class DecimalHostIsolationTest {

    @Test fun hostGlobalMutationDoesNotAffectTheInterpreter() {
        // A hostile co-resident consumer corrupts the SHARED big.js context in every way that would matter:
        hostBig.DP = 2       // 2 decimal places (interpreter needs 50 significant digits)
        hostBig.RM = 0       // roundDown (interpreter needs HALF_UP)
        hostBig.PE = 1       // force exponent form early (interpreter needs plain)
        hostBig.NE = -1

        // The interpreter uses its OWN isolated constructor, so every contract still holds bit-for-bit:
        assertEquals("0.33333333333333333333333333333333333333333333333333", dec("1").div(dec("3")).display(),
            "÷ still 50 significant digits HALF_UP (DP isolated)")
        assertEquals("121932631137021795226185032733622923332237463801110000000000",
            (dec("123456789012345678901234567890").times(dec("987654321098765432109876543210"))).display(),
            "× still capped at 50 significant digits")
        assertEquals("10000000000000000000000000", dec("10000000000000000000000000").display(),
            "display still PLAIN (PE/NE isolated)")
        assertEquals("2912324058756262786433368781182555.3552912554484006", dec("0.3").pow(-64).display(),
            "negative power still the kernel reciprocal form at 50 significant digits")
    }
}
