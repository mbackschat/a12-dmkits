package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Val
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [Dec] value equality (IF41 — graduated from the deferred IG4). A `Dec` is a value type: two `Dec`s are equal iff they
 * compare equal ([Dec.compareTo] `== 0`), **scale-insensitively** (matching the kernel's `BigDecimal.compareTo` /
 * the interpreter's `compareScaled19` everywhere numbers are compared) — `2.0 == 2.00`. Before this, `Dec` used
 * reference identity, so the `data class`es that hold it ([Val.Num], `Expr.NumLit`) had silently-broken equality
 * (`Val.Num(2.0) != Val.Num(2.0)`), a latent landmine for any AST dedup / `Set<Val>` use. `hashCode` is consistent
 * with `equals` (equal values hash the same). Cross-platform: JVM BigDecimal + JS big.js, verified here on both.
 */
class DecEqualityTest {

    @Test fun equalValuesAcrossScalesAreEqual() {
        assertEquals(dec("2.0"), dec("2.00"), "2.0 == 2.00 (scale-insensitive, like compareTo)")
        assertEquals(dec("600"), dec("600.0"), "600 == 600.0")
        assertEquals(dec("0"), dec("0.00"), "0 == 0.00 (the zero edge)")
        assertEquals(dec("49.90"), dec("49.9"), "49.90 == 49.9")
    }

    @Test fun differentValuesAreNotEqual() {
        assertNotEquals(dec("2.0"), dec("3.0"), "2.0 != 3.0")
        assertNotEquals(dec("2.0"), dec("2.01"), "2.0 != 2.01")
        assertNotEquals(dec("-1"), dec("1"), "-1 != 1")
    }

    @Test fun hashCodeIsConsistentWithEquals() {
        assertEquals(dec("2.0").hashCode(), dec("2.00").hashCode(), "equal values hash the same")
        assertEquals(dec("0").hashCode(), dec("0.000").hashCode(), "zero hashes the same across scales")
        // A Set dedups equal-by-value Decs to ONE element (the property identity-equality broke).
        assertEquals(1, setOf(dec("2.0"), dec("2.00"), dec("2.000")).size, "a Set dedups equal values")
    }

    @Test fun valNumEqualityNowWorks() {
        assertEquals(Val.Num(dec("2.0")), Val.Num(dec("2.00")), "the Val.Num data class equality is fixed")
        assertTrue(Val.Num(dec("2.0")) in setOf(Val.Num(dec("2.00"))), "Val.Num works as a Set element")
    }
}
