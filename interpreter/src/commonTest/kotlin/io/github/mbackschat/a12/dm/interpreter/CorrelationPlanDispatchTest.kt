package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.CorrelationJoin
import io.github.mbackschat.a12.dm.interpreter.eval.CorrelationStrategy
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.OrderedCorrelationPlan
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Multiplatform lock for the closed keyed/ordered/general `$` correlation dispatch boundary. */
class CorrelationPlanDispatchTest {

    private val numericModel = ModelDef(
        fields = listOf(FieldDef(COUNT, Kind.NUMBER, scale = 0)),
        rules = emptyList(),
        repeatableGroups = setOf(ITEMS),
        declaredReps = mapOf(ITEMS to 10),
    )

    @Test
    fun everySingleValueComparatorHasADeterministicIndexedPlan() {
        val expected = mapOf(
            "<" to CorrelationStrategy.ORDERED,
            "<=" to CorrelationStrategy.ORDERED,
            ">" to CorrelationStrategy.ORDERED,
            ">=" to CorrelationStrategy.ORDERED,
            "==" to CorrelationStrategy.KEYED,
            "!=" to CorrelationStrategy.ORDERED,
        )
        expected.forEach { (operator, strategy) ->
            assertEquals(strategy, strategy(numericModel, having("[Items/Count] $operator [\$Items/Count]")),
                "operator $operator")
        }
    }

    @Test
    fun reversedOperandsNormalizeTheComparatorDirection() {
        val star = star(having("[\$Items/Count] < [Items/Count]"))
        val plan = assertIs<OrderedCorrelationPlan>(CorrelationJoin.parse(star.having!!, ITEMS))
        assertEquals(CmpOp.GT, plan.op)
        assertEquals(COUNT, plan.innerField)
        assertEquals(COUNT, plan.outerField)
        assertEquals(CorrelationStrategy.KEYED,
            strategy(numericModel, having("[\$Items/Count] == [Items/Count]")))
    }

    @Test
    fun compoundPredicatesAndRangesStayOnTheGeneralFallback() {
        assertEquals(CorrelationStrategy.NESTED,
            strategy(numericModel, having("[Items/Count] > [\$Items/Count] And FieldFilled(Items/Count)")))
        assertEquals(CorrelationStrategy.NESTED,
            strategy(numericModel, having("[Items/Count] > [\$Items/Count] And [Items/Count] < 100")))
    }

    @Test
    fun kindsWithoutTheSameTotalOrderStayOnTheGeneralFallback() {
        listOf(
            Kind.BOOLEAN to "true",
            Kind.CONFIRM to "true",
            Kind.DATE_RANGE to "2025-01-01 - 2025-01-02",
        ).forEach { (kind, value) ->
            val field = "/Order/Items/Other"
            val model = ModelDef(
                fields = listOf(FieldDef(field, kind)),
                rules = emptyList(),
                repeatableGroups = setOf(ITEMS),
                declaredReps = mapOf(ITEMS to 10),
            )
            assertEquals(CorrelationStrategy.NESTED,
                strategy(model, having("[Items/Other] != [\$Items/Other]", field = "Other"), value),
                "kind $kind")
        }
    }

    @Test
    fun nonUtcDateTimeInstantOrderingStaysOnTheGeneralFallback() {
        val model = ModelDef(
            fields = listOf(FieldDef(TIMESTAMP, Kind.DATE_TIME)),
            rules = emptyList(),
            repeatableGroups = setOf(ITEMS),
            declaredReps = mapOf(ITEMS to 10),
            timeZone = ModelTimeZone.EUROPE_BERLIN,
        )
        assertEquals(CorrelationStrategy.NESTED,
            strategy(model, having("[Items/At] > [\$Items/At]", field = "At"), value = "2025-01-01T12:00:00"))
    }

    @Test
    fun multiStarSelfExclusionStaysOnTheGeneralFallback() {
        val field = "/Order/Groups/Items/Count"
        val group = "/Order/Groups/Items"
        val model = ModelDef(
            fields = listOf(FieldDef(field, Kind.NUMBER, scale = 0)),
            rules = emptyList(),
            repeatableGroups = setOf("/Order/Groups", group),
            declaredReps = mapOf("/Order/Groups" to 10, group to 10),
        )
        val doc = model.newDocument().field(field, "1", 1, 1, 1, 1)
        val context = doc.contextsFor(group).single()
        val text = "AtLeastOneFieldFilled(Groups*/Items*/Count Having " +
            "CurrentRepetition(Groups/Items) != CurrentRepetition(\$Groups/Items) And " +
            "[Groups/Items/Count] > [\$Groups/Items/Count])"
        assertEquals(CorrelationStrategy.NESTED,
            Interpreter(context, EvalConfig()).correlationStrategy(star(text)))
    }

    private fun strategy(model: ModelDef, text: String, value: String = "1"): CorrelationStrategy {
        val doc = model.newDocument().field(model.fields.single().path, value, 1, 1, 1)
        val context = doc.contextsFor(ITEMS).single()
        return Interpreter(context, EvalConfig(timeZone = model.timeZone)).correlationStrategy(star(text))
    }

    private fun having(comparison: String, field: String = "Count"): String =
        "AtLeastOneFieldFilled(Items*/$field Having " +
            "CurrentRepetition(Items) != CurrentRepetition(\$Items) And $comparison)"

    private fun star(text: String): Expr.Star {
        fun inExpression(expression: Expr): Expr.Star? = when (expression) {
            is Expr.Star -> expression.takeIf { it.having != null }
            is Expr.Func -> expression.args.firstNotNullOfOrNull(::inExpression)
            is Expr.Arith -> inExpression(expression.left) ?: inExpression(expression.right)
            else -> null
        }
        fun inCondition(condition: Cond): Expr.Star? = when (condition) {
            is Cond.Pred -> condition.args.firstNotNullOfOrNull(::inExpression)
            is Cond.And -> inCondition(condition.left) ?: inCondition(condition.right)
            is Cond.Or -> inCondition(condition.left) ?: inCondition(condition.right)
            is Cond.Not -> inCondition(condition.c)
            is Cond.Cmp, is Cond.Pattern, is Cond.Tolerance, is Cond.ValueListQuant, is Cond.Custom -> null
        }
        return inCondition(parseCondition(text, ORDER)) ?: error("no filtered star in $text")
    }

    private companion object {
        const val ORDER = "/Order"
        const val ITEMS = "/Order/Items"
        const val COUNT = "/Order/Items/Count"
        const val TIMESTAMP = "/Order/Items/At"
    }
}
