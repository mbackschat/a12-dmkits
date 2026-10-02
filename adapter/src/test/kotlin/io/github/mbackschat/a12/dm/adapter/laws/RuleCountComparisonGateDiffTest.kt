package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.fired
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.CompareOp
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance

/** KF507 / SPEC-2026-10-02-06: distinguish a rule's content gate from its comparison's truth value. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuleCountComparisonGateDiffTest {
    private val comparisons = listOf(CompareOp.EQ, CompareOp.NE, CompareOp.LT, CompareOp.LE, CompareOp.GT, CompareOp.GE)

    private val model = run {
        val base = DocumentModelDraft.named("policy-count-comparison-gate").locale("en_US")
            .rootGroup("Policy") { it.numberField("Withheld") { f -> f.fractionDigits(0) }
                .group("Home") { g -> g.stringField("Street") { } }
                .group("Car") { g -> g.stringField("Plate") { } } }
            .build().toJson()
        val policy = EditSession.read(base).group("/Policy")
        val withheld = policy.num("Withheld").asNumber()
        val error = policy.field("Withheld")
        val fields = listOf(policy.field("Home/Street"), policy.field("Car/Plate"))
        val counts = listOf(
            "GROUPS" to Dsl.numberOfFilledGroups(policy.group("Home"), policy.group("Car")),
            "FIELDS" to Dsl.numberOfFilledFields(fields),
            "DISTINCT" to Dsl.numberOfDifferentValues(fields[0], fields[1]),
        )
        fun rule(code: String, condition: Condition) = policy.rule {
            it.name("${code}Rule").errorField(error).`when`(condition)
                .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()
        }
        val rules = counts.flatMap { (name, count) ->
            comparisons.flatMap { op ->
                // KF507's gate must be observable without a negative presence leaf; the self-read also
                // satisfies A12's error-field-reference requirement without changing zero-count truth.
                val value = count.plus(withheld)
                listOf(
                    rule("${name}_L_$op", compare(value, op, Dsl.number(threshold(op)))),
                    rule("${name}_R_$op", compare(Dsl.number(-threshold(op)), op, value)),
                )
            }
        } + counts.flatMap { (name, count) ->
            val value = count.plus(withheld)
            val small = value.lt(1L)
            val large = value.ge(0L)
            listOf(
                rule("${name}_AND_SL", small.and(large)),
                rule("${name}_AND_LS", large.and(small)),
                rule("${name}_AND_SS", small.and(small)),
                rule("${name}_AND_LL", large.and(large)),
                rule("${name}_OR_SF", small.or(value.ge(5L))),
            )
        } + rule("PLAIN", withheld.lt(1L))
        admitted(rules.fold(base) { json, rule -> TypedRules.inject(json, rule) })
    }

    @TestFactory
    fun countSideControlsTheGateWhileEveryComparisonIsTrue(): List<DynamicTest> {
        fun codes(vararg suffixes: String) = listOf("GROUPS", "FIELDS", "DISTINCT").flatMap { count ->
            suffixes.map { "${count}_$it" }
        }.sorted()
        val emptyRoot = codes("L_EQ", "L_NE", "L_LT", "L_LE", "R_EQ", "R_NE", "R_GT", "R_GE",
            "AND_SL", "AND_LS", "AND_SS", "OR_SF")
        val withContent = (codes("L_EQ", "L_NE", "L_LT", "L_LE", "L_GT", "L_GE",
            "R_EQ", "R_NE", "R_LT", "R_LE", "R_GT", "R_GE",
            "AND_SL", "AND_LS", "AND_SS", "AND_LL", "OR_SF") + "PLAIN").sorted()
        return listOf(
            "{}" to emptyRoot,
            """{"Policy":{}}""" to emptyRoot,
            """{"Policy":{"Withheld":null}}""" to emptyRoot,
            """{"Policy":{"Withheld":0}}""" to withContent,
        ).map { (document, expected) ->
            DynamicTest.dynamicTest(document) {
                assertEquals(expected, fired(model, document), document)
            }
        }
    }

    private fun threshold(op: CompareOp): Long = when (op) {
        CompareOp.EQ, CompareOp.LE, CompareOp.GE -> 0L
        CompareOp.NE, CompareOp.LT -> 1L
        CompareOp.GT -> -1L
        else -> error("Tolerance comparisons are outside this grid: $op")
    }

    private fun compare(left: NumberExpr, op: CompareOp, right: NumberExpr): Condition = when (op) {
        CompareOp.EQ -> left.eq(right)
        CompareOp.NE -> left.ne(right)
        CompareOp.LT -> left.lt(right)
        CompareOp.LE -> left.le(right)
        CompareOp.GT -> left.gt(right)
        CompareOp.GE -> left.ge(right)
        else -> error("Tolerance comparisons are outside this grid: $op")
    }
}
