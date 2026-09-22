package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — STRING CONCATENATION `[First] + " " + [Last]` (`Concatenation`, the kernel's overloaded `+`
 * on text) through [DmInterpreter.compute]. The computed value must match the kernel's, including how an
 * EMPTY string operand concatenates. (IF48: `+` was numeric-only, so two FILLED string operands crashed
 * `num(Val.Txt)` — an uncaught error aborting the whole compute run, not one rule.)
 */
class ConcatComputeDiffTest {

    private val person = person()
    private val m = BoundModel.ofJson(person)
    private fun renderOp(c: Calculation): String = RuntimeLaws.render("/Person", c)
    private val operation = renderOp(m.str(FIRST).asString().plus(" ").plus(m.str(LAST).asString()).expression())
    private val fields = listOf(FieldDef(FIRST, Kind.STRING), FieldDef(LAST, Kind.STRING), FieldDef(FULL, Kind.STRING))
    private val def = ModelDef(fields, emptyList(), computations = listOf(ComputationDef(FULL, "/Person", operation)))
    private val model = RuntimeLaws.withComputation(person, "/Person", "FullComp", "../Full", operation)

    private fun kValue(vararg vals: Pair<String, String>): String? {
        val doc = RuntimeLaws.doc().group("/Person", 1)
        for ((p, v) in vals) doc.field(p, v, 1, 1)
        return RuntimeLaws.computedValue(RuntimeLaws.compute(model, doc), FULL).orElse(null)
    }

    private fun iValue(vararg vals: Pair<String, String>): String? {
        val doc = Document(def.kinds).group("/Person", 1)
        for ((p, v) in vals) doc.field(p, v, 1, 1)
        return DmInterpreter(def).compute(doc).firstOrNull { it.path == FULL }?.value
    }

    @Test fun bothFilled() = assertEquals(
        kValue(FIRST to "John", LAST to "Smith"), iValue(FIRST to "John", LAST to "Smith"), "both filled")

    @Test fun firstEmpty() = assertEquals(kValue(LAST to "Smith"), iValue(LAST to "Smith"), "first empty")

    @Test fun lastEmpty() = assertEquals(kValue(FIRST to "John"), iValue(FIRST to "John"), "last empty")

    private companion object {
        const val FIRST = "/Person/First"
        const val LAST = "/Person/Last"
        const val FULL = "/Person/Full"

        fun person(): String = DocumentModelDraft.named("person").locale("en_US")
            .rootGroup("Person") { p -> p.stringField("First") {}.stringField("Last") {}.stringField("Full") {} }
            .build().toJson()
    }
}
