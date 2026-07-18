package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `validatePart` on the **pure** engine — runs on JVM AND JS, locking the partial-validation contract the
 * `:adapter` differential pins against the real kernel ([io.github.mbackschat...]`PartialValidationDiffTest`):
 * a rule runs only if its error-field instance is relevant, and a non-relevant referenced field is UNKNOWN
 * (3VL) — so `And` suppresses but `Or` with a relevant-true operand still fires, and the whole document being
 * relevant equals full validation.
 */
class PartialValidationTest {

    private fun model(cond: String) = ModelDef(
        listOf(FieldDef(NAME, Kind.STRING), FieldDef(CODE, Kind.STRING)),
        listOf(RuleDef("/Order/R", "/Order", cond, "PV", "../CustomerName")),
    )

    private fun docOf(def: ModelDef) = def.newDocument()
        .group("/Order", 1).field(NAME, "Alice", 1, 1).field(CODE, "SKU-9", 1, 1)

    private fun fires(cond: String, relevant: Set<RelevantEntity>): Boolean {
        val def = model(cond)
        return DmInterpreter(def).validatePart(docOf(def), relevant).messages.any { it.code == "PV" }
    }

    private val name = RelevantEntity.anyRep(NAME)
    private val code = RelevantEntity.anyRep(CODE)
    private val group = RelevantEntity.anyRep("/Order")
    private val and = "FieldFilled(CustomerName) And FieldFilled(ProductCode)"
    private val or = "FieldFilled(CustomerName) Or FieldFilled(ProductCode)"

    @Test fun andBothRelevantFires() = assertEquals(true, fires(and, setOf(name, code)), "both relevant → fires")
    @Test fun andOnlyErrorFieldSuppressed() = assertEquals(false, fires(and, setOf(name)), "true And Unknown = Unknown → suppressed")
    @Test fun andOnlyOtherNotRun() = assertEquals(false, fires(and, setOf(code)), "error field not relevant → rule not run")
    @Test fun orOnlyErrorFieldFires() = assertEquals(true, fires(or, setOf(name)), "true Or Unknown = true → fires")
    @Test fun emptyRelevantRunsNothing() = assertEquals(false, fires(and, emptySet()), "nothing relevant → no rule runs")

    @Test fun wholeGroupRelevantEqualsFull() {
        val def = model(and)
        val full = DmInterpreter(def).validateFull(docOf(def)).map { it.code }.toSet()
        val part = DmInterpreter(def).validatePart(docOf(def), setOf(group)).messages.map { it.code }.toSet()
        assertEquals(full, part, "whole document relevant → validatePart == validateFull")
    }

    private companion object {
        const val NAME = "/Order/CustomerName"
        const val CODE = "/Order/ProductCode"
    }
}
