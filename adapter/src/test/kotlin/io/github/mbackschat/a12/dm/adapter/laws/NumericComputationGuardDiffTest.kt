package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** SPEC-2026-10-03-01: an empty Number is zero; domain failure and reached poison remain distinct. */
class NumericComputationGuardDiffTest {
    private data class Row(val id: String, val n: String?, val divisor: String, val bad: String)
    @Test
    fun numericGuardsAndTheirDownstreamOutcomesAgree() {
        val names = listOf("Eq", "Ne", "Lt", "Le", "Gt", "Ge", "Arithmetic", "LiteralFirst", "Domain",
            "DomainThenBad", "BadThenDomain", "FalseAnd", "TrueOr", "AfterDomain", "AfterFalse", "AfterTrue")
        val base = DocumentModelDraft.named("invoice-numeric-guards").locale("en_US")
            .rootGroup("Invoice") { root -> for (name in listOf("N", "D", "Bad") + names)
                root.numberField(name) { it.fractionDigits(0) }
            }.build().toJson()
        val group = EditSession.read(base).group("/Invoice")
        val n = group.num("N").asNumber(); val d = group.num("D").asNumber(); val bad = group.num("Bad").asNumber()
        val zero = NumberExpr(NumberLiteral.of(0))
        val conditions = listOf(n.eq(0), n.ne(0), n.lt(1), n.le(0), n.gt(-1), n.ge(0), n.plus(1).gt(0),
            zero.lt(n.plus(1)), n.dividedBy(d).gt(0), n.dividedBy(d).gt(bad), bad.gt(n.dividedBy(d)),
            n.lt(0).and(bad.gt(0)), n.ge(0).or(bad.gt(0)))
        var json = base
        for ((index, condition) in conditions.withIndex()) {
            val comp = group.computation(group.num(names[index])) {
                it.name(names[index] + "Comp").commonPrecondition(condition).calc(NumberLiteral.of(3)).message(Dsl.en("Check guard.")).build()
            }
            json = TypedRules.inject(json, comp)
        }
        for ((target, source) in listOf("AfterDomain" to "DomainThenBad", "AfterFalse" to "FalseAnd", "AfterTrue" to "TrueOr")) {
            json = TypedRules.inject(json, group.computation(group.num(target)) {
                it.name(target + "Comp").calc(group.num(source).asNumber().plus(1)).message(Dsl.en("Check dependent.")).build()
            })
        }
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(json))
        val rows = listOf(Row("empty", null, "1", "1"), Row("zero", "0", "1", "1"), Row("positive", "2", "1", "1"),
            Row("negative", "-2", "1", "1"), Row("invalid", "bad", "1", "1"), Row("domain", "2", "0", "1"),
            Row("domain-poison", "2", "0", "bad"), Row("short-circuit", "0", "1", "bad"))
        val def = ModelLoader.load(json)
        val engine = RuntimeLaws.kernelJavaEngine(json)
        val outcomes = mutableListOf<Pair<String, Map<String, String?>>>()
        for (row in rows) {
            val kd = RuntimeLaws.doc().group("/Invoice", 1)
            val id = def.newDocument().group("/Invoice", 1)
            for ((name, value) in names.map { it to "9" } + listOf("N" to row.n, "D" to row.divisor, "Bad" to row.bad)) {
                if (value != null) { val path = group.num(name).field().path().value()
                    kd.field(path, value, 1, 1); id.field(path, value, 1, 1) }
            }
            fun project(values: List<RuntimeLaws.Computed>): Map<String, String?> {
                assertEquals(names.size, values.size, row.id)
                return values.associate { it.path().substringAfterLast('/') to it.value().orElse(null) }
            }
            val dynamic = project(RuntimeLaws.compute(json, kd))
            assertEquals(dynamic, project(engine.compute(engine.instance(kd))), row.id + ": strategies")
            val interpreted = DmInterpreter(def).compute(id)
            assertEquals(names.size, interpreted.size)
            assertEquals(dynamic, interpreted.associate { it.path.substringAfterLast('/') to it.value }, row.id)
            outcomes += row.id to dynamic
        }
        assertEquals(outcomes[0].second, outcomes[1].second, "empty and explicit zero")
        assertEquals(names.take(8).associateWith { if (it == "Ne") null else "3" }, outcomes[0].second.filterKeys { it in names.take(8) })
        assertEquals("1", outcomes.single { it.first == "domain" }.second["AfterDomain"])
        assertEquals(null, outcomes.single { it.first == "domain-poison" }.second["AfterDomain"])
        assertEquals("1", outcomes.last().second["AfterFalse"])
        assertEquals("4", outcomes.last().second["AfterTrue"])
    }
}
