package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.ValueListQuantOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The operator name→enum lookups are O(1) maps, and this is what keeps that safe.
 *
 * They used to be `entries.firstOrNull { it.kernelName == name }` — a linear scan with a string comparison per
 * entry — called from the expression evaluator on every evaluation of a node rather than once at parse time.
 * With 53 `FunctionOp` entries and `CurrentRepetition` near the end, JFR put that one method at **7.7 % of all
 * samples** on the compute-overlay correlation scenario, the hottest frame in the whole profile.
 *
 * **The swap is only behaviour-preserving while `kernelName` is unique**, and the two lookups differ exactly
 * there: `firstOrNull` returns the FIRST match, `associateBy` keeps the LAST. A duplicate would therefore not
 * fail — it would silently resolve to a different operator, which is the worst way for this to go wrong. So
 * uniqueness is asserted rather than assumed.
 */
class OperatorLookupTest {

    @Test
    fun everyOperatorNameIsUniqueSoTheMapAndTheOldScanAgree() {
        assertUniqueNames(FunctionOp.entries.map { it.kernelName }, "FunctionOp")
        assertUniqueNames(PredicateOp.entries.map { it.kernelName }, "PredicateOp")
        assertUniqueNames(ConstantOp.entries.map { it.kernelName }, "ConstantOp")
        assertUniqueNames(ValueListQuantOp.entries.map { it.kernelName }, "ValueListQuantOp")
    }

    @Test
    fun everyEntryResolvesToItself() {
        FunctionOp.entries.forEach { assertEquals(it, FunctionOp.byName(it.kernelName)) }
        PredicateOp.entries.forEach { assertEquals(it, PredicateOp.byName(it.kernelName)) }
        ConstantOp.entries.forEach { assertEquals(it, ConstantOp.byName(it.kernelName)) }
        ValueListQuantOp.entries.forEach { assertEquals(it, ValueListQuantOp.byName(it.kernelName)) }
    }

    /** An unknown name must stay `null`: several callers turn exactly that into an unsupported-construct error. */
    @Test
    fun anUnknownNameResolvesToNull() {
        assertNull(FunctionOp.byName("NoSuchFunction"))
        assertNull(PredicateOp.byName("NoSuchPredicate"))
        assertNull(ConstantOp.byName("NoSuchConstant"))
        assertNull(ValueListQuantOp.byName("NoSuchQuantifier"))
        assertNull(FunctionOp.byName(""))
        // The enum CONSTANT name is not the kernel name, and resolving it would be a silent second spelling.
        assertNull(FunctionOp.byName("CURRENT_REPETITION"))
    }

    private fun assertUniqueNames(names: List<String>, enum: String) {
        val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(duplicates.isEmpty(),
            "$enum has duplicate kernelName(s) $duplicates — the by-name map keeps the LAST of them while the " +
                "linear scan it replaced kept the FIRST, so a duplicate silently changes which operator a " +
                "condition resolves to instead of failing")
        assertTrue(names.isNotEmpty(), "$enum must have entries, or every assertion here is vacuous")
    }
}
