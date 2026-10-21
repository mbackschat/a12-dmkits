package io.github.mbackschat.a12.dm.interpreter.property

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Property 6 over unavailable cells** (INTERPRETER-FINDINGS IF423): the keyed and ordered tallies take the rows
 * holding an unavailable empty cell out of their index and tally them as the general scan does, so the two still
 * agree when such a cell is the starred column, a join field, or the iterated row's own. Every empty cell of the
 * marked fields is unavailable here, as a required field's is, on the sampled documents of [HashJoinPropertyTest].
 */
class UnavailableCorrelationTallyPropertyTest {

    private val keyed = "AtLeastOneFieldFilled(Lines*/Amount Having [Lines/ItemId] == [\$Lines/ItemId] " +
        "And CurrentRepetition(Lines) != CurrentRepetition(\$Lines))"

    private val ordered = listOf("<", "<=", ">", ">=", "!=").map { operator ->
        "AtLeastOneFieldFilled(Lines*/ItemId Having [Lines/Amount] $operator [\$Lines/Amount] " +
            "And CurrentRepetition(Lines) != CurrentRepetition(\$Lines))"
    }

    private val markings = mapOf(
        "the starred column" to setOf(AMOUNT),
        "the join field" to setOf(ITEM_ID),
        "both" to setOf(AMOUNT, ITEM_ID),
    )

    @Test
    fun theKeyedTallyEqualsTheGeneralScanWhereverACellIsUnavailable() {
        markings.forEach { (label, marked) -> assertIndexedEqualsGeneral("keyed, $label", keyed, marked) }
    }

    @Test
    fun everyOrderedTallyEqualsTheGeneralScanWhereverACellIsUnavailable() {
        markings.forEach { (label, marked) ->
            ordered.forEach { condition -> assertIndexedEqualsGeneral("ordered, $label: $condition", condition, marked) }
        }
    }

    private fun assertIndexedEqualsGeneral(label: String, condition: String, marked: Set<String>) {
        val model = PropertySupport.singleRep.model
        val star = correlatedStar(parseCondition(condition, "/Cart")) ?: error("no correlated star in: $condition")
        var checks = 0
        var unavailableOuterRows = 0
        val failures = mutableListOf<String>()
        for (seed in PropertySupport.SEEDS) {
            for (ctx in PropertySupport.singleRep.document(seed).contextsFor("/Cart/Lines")) {
                val interpreter = Interpreter(ctx, EvalConfig(indexFields = model.indexFields),
                    invalid = { row, path -> path in marked && !row.filled(path) })
                val both = interpreter.correlationTallyBothWays(star) ?: continue
                checks++
                if (marked.any { !ctx.filled(it) }) unavailableOuterRows++
                if (both.first != both.second) failures += "seed=$seed indexed=${both.first} general=${both.second}"
            }
        }
        assertTrue(checks > 0 && unavailableOuterRows > 0,
            "$label: $checks outer rows, $unavailableOuterRows of them unavailable — the property is vacuous")
        assertTrue(failures.isEmpty(),
            "$label: indexed plan ≠ general scan on ${failures.size} of $checks:\n  " + failures.take(10).joinToString("\n  "))
    }

    private fun correlatedStar(cond: Cond): Expr.Star? {
        val func = ((cond as? Cond.Pred)?.args?.firstOrNull() as? Expr.Star)
        return func?.takeIf { it.having != null }
    }

    private companion object {
        const val AMOUNT = "/Cart/Lines/Amount"
        const val ITEM_ID = "/Cart/Lines/ItemId"
    }
}
