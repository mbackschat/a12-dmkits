package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** SPEC-2026-10-03-02: group operands and producer scheduling edges have different denominators. */
class ContainingGroupDependencyAgreementTest {
    private enum class Shape { PAIR, AND, OR, UNCONDITIONAL, FIELD_CYCLE, ALTERNATIVES, COMMON, BYPASS,
        SPLIT, DEAD_READ, DEPTH_AND, DEPTH_OR, REPEATED_BOX, REPEATED_ROW, REPEATED_COMMON, FIXED_BOX }
    private data class Row(val id: String, val json: String, val cycle: Boolean, val runtime: Boolean)

    private fun row(shape: Shape, reverse: Boolean = false): Row {
        val nested = shape in setOf(Shape.DEPTH_AND, Shape.DEPTH_OR, Shape.REPEATED_BOX, Shape.REPEATED_ROW, Shape.REPEATED_COMMON, Shape.FIXED_BOX)
        val repeated = shape in setOf(Shape.REPEATED_BOX, Shape.REPEATED_ROW, Shape.REPEATED_COMMON)
        val base = DocumentModelDraft.named("invoice-group-dependencies").locale("en_US")
            .rootGroup("Invoice") { root ->
                root.numberField("Gate") { it.fractionDigits(0) }
                fun fields(g: DocumentModelDraft.GroupBuilder) {
                    for (name in listOf("X", "Y", "Local")) g.numberField(name) { it.fractionDigits(0) }
                }
                if (nested) root.group("Rows") { rows ->
                    if (repeated) rows.repeatable(2)
                    rows.group("Box") { fields(it) }
                } else fields(root)
            }.build().toJson()
        val session = EditSession.read(base)
        val root = session.group("/Invoice")
        val target = if (nested) session.group("/Invoice/Rows/Box") else root
        val x = target.num("X"); val y = target.num("Y")
        val scope = Dsl.groupFilled(if (nested) root.group("Rows/Box") else root.ref())
        val rootScope = Dsl.groupFilled(root.ref())
        val rowScope = if (nested) Dsl.groupFilled(root.group("Rows")) else rootScope
        val gate = root.num("Gate").filled()
        fun literal(n: Long) = NumberExpr(NumberLiteral.of(n))
        fun comp(name: String, field: NumberRef, condition: Condition?, value: NumberExpr): Computation =
            target.computation(field) { builder ->
                builder.name(name)
                if (condition != null) builder.commonPrecondition(condition)
                builder.calc(value).message(Dsl.en("Check computation.")).build()
            }
        val xGuard = when (shape) {
            Shape.AND -> scope.and(root.num("Gate").notFilled())
            Shape.OR -> scope.or(gate)
            Shape.REPEATED_ROW -> rowScope
            Shape.DEAD_READ -> null
            else -> scope
        }
        val xComp = comp("ComputeX", x, xGuard, if (shape in setOf(Shape.FIELD_CYCLE, Shape.DEAD_READ)) y.asNumber() else literal(1))
        val ys = when (shape) {
            Shape.PAIR, Shape.AND, Shape.OR -> listOf(comp("ComputeY", y, xGuard, literal(2)))
            Shape.UNCONDITIONAL -> listOf(comp("ComputeY", y, null, literal(2)))
            Shape.FIELD_CYCLE -> listOf(comp("ComputeY", y, scope, x.asNumber()))
            Shape.DEPTH_AND -> listOf(comp("ComputeY", y, rootScope.and(scope), x.asNumber()))
            Shape.DEPTH_OR -> listOf(comp("ComputeY", y, rootScope.or(scope), x.asNumber()))
            Shape.ALTERNATIVES, Shape.COMMON, Shape.BYPASS -> listOf(target.computation(y) { builder ->
                builder.name("ComputeY")
                if (shape == Shape.COMMON) builder.commonPrecondition(scope)
                builder.`when`(if (shape == Shape.COMMON) gate else scope.and(gate)).calc(x.asNumber())
                    .`when`(if (shape in setOf(Shape.COMMON, Shape.BYPASS)) root.num("Gate").notFilled() else scope.and(root.num("Gate").notFilled()))
                    .calc(literal(2)).message(Dsl.en("Check alternatives.")).build()
            })
            Shape.DEAD_READ -> listOf(comp("PlainY", y, null, literal(2)), comp("GuardedY", y, gate, x.asNumber()))
            else -> listOf(comp("GuardedY", y, scope, x.asNumber()),
                comp("PlainY", y, if (shape == Shape.REPEATED_COMMON) scope else null, literal(2))).let { if (reverse) it.reversed() else it }
        }
        val json = (listOf(xComp) + ys).fold(base) { model, computation -> TypedRules.inject(model, computation) }
        val cycle = shape in setOf(Shape.OR, Shape.FIELD_CYCLE, Shape.BYPASS, Shape.DEAD_READ, Shape.DEPTH_OR, Shape.REPEATED_BOX)
        return Row("$shape/$reverse", json, cycle, !nested && !cycle)
    }

    @Test
    fun profilesRetainOrdinaryEdgesAndNormalizeRepeatableGuardsBeforeMergingTargets() {
        val rows = Shape.entries.flatMap { shape ->
            if (shape in setOf(Shape.SPLIT, Shape.REPEATED_BOX, Shape.REPEATED_ROW, Shape.REPEATED_COMMON, Shape.FIXED_BOX))
                listOf(row(shape), row(shape, true)) else listOf(row(shape))
        }
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        val expected = rows.map { it.id to if (it.cycle) listOf("MVK_ERROR_CALC_CYCLE") else emptyList() }
        assertEquals(expected, rows.map { it.id to MessageParameterProbes.kernel(it.json) }, "kernel admission")
        val portable = rows.map { it.id to MessageParameterProbes.portable(it.json) }
        val retained = mutableMapOf<String, MutableList<Map<String, Any?>>>()
        val outcomes = mutableListOf<Pair<String, Map<String, String?>>>()
        val interpreted = mutableListOf<Pair<String, Map<String, String?>>>()
        for (row in rows.filter { it.runtime }) {
            val def = ModelLoader.load(row.json)
            val engine = RuntimeLaws.kernelJavaEngine(row.json)
            for (gate in listOf(false, true)) {
                val kd = RuntimeLaws.doc().group("/Invoice", 1).field("/Invoice/X", "9", 1, 1).field("/Invoice/Y", "9", 1, 1)
                val id = def.newDocument().group("/Invoice", 1).field("/Invoice/X", "9", 1, 1).field("/Invoice/Y", "9", 1, 1)
                if (gate) { kd.field("/Invoice/Gate", "3", 1, 1); id.field("/Invoice/Gate", "3", 1, 1) }
                fun project(values: List<RuntimeLaws.Computed>): Map<String, String?> {
                    assertEquals(2, values.size, "${row.id}/$gate result cardinality")
                    return values.associate { it.path() to it.value().orElse(null) }.also { assertEquals(2, it.size) }
                }
                val dynamic = project(RuntimeLaws.compute(row.json, kd))
                assertEquals(dynamic, project(engine.compute(engine.instance(kd))), "${row.id}/$gate strategies")
                val key = "${row.id}/$gate"
                retained.getOrPut(row.id) { mutableListOf() }.add(mapOf("id" to key,
                    "document" to RuntimeLaws.canonicalDocumentJson(row.json, kd), "values" to dynamic))
                outcomes += key to dynamic
                interpreted += key to try {
                    DmInterpreter(def).compute(id).also { assertEquals(2, it.size, key) }.associate { it.path to it.value }
                }
                    catch (failure: io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException) { mapOf("refused" to failure.message) }
            }
        }
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val stored = HarvestedModels.expand(mapper.readTree(checkNotNull(javaClass.getResource("/checker-near-miss/containing-group-profiles.json"))))
        val authored = rows.map { mapOf("id" to it.id, "model" to it.json,
            "classes" to if (it.cycle) listOf("MVK_ERROR_CALC_CYCLE") else emptyList(), "runtime" to retained[it.id].orEmpty()) }
        assertEquals(stored, mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(authored).toList(), "retained typed fixtures")
        assertEquals(expected, portable, "portable cycle verdicts")
        assertEquals(outcomes, interpreted, "computed values and clean clears")
    }
}
