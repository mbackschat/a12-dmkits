package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — **plain-date locale canonicalization** (KERNEL-FINDINGS §3 / IF12). The kernel stores a DATE
 * field's value in its **declared format** (`OrderDate` ISO `yyyy-MM-dd`, `ApproxDate` de `dd.MM.yyyy`), NOT
 * always ISO. The interpreter must canonicalize each date to ISO via the field's format so the extractors and
 * the formal check agree with the kernel — a value valid for its format is well-formed (and extractable), a
 * value in the *wrong* format is `datumFormatFalsch` (and the referencing rule is suppressed). Surfaced by
 * `InterpreterKernelFuzzTest` (the interpreter previously assumed ISO → flagged every valid de date malformed).
 * Compared on the date rule's code + the `datumFormatFalsch` formal code.
 */
class PlainDateDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(OD, Kind.DATE, format = "yyyy-MM-dd"),   // ISO
        FieldDef(AD, Kind.DATE, format = "dd.MM.yyyy"),   // de, non-ISO
    )

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. The renderer emits absolute paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** `YearFromDate(field) > 2020` over [field] = [value] — kernel and interpreter must agree on the relevant codes. */
    private fun agree(field: String, value: String, code: String, errorRel: String) {
        val cond = render(m.date(field).filled().and(Dsl.yearFromDate(m.date(field)).gt(2020)))
        val kModel = RuntimeLaws.withRule(order, "/Order", code, errorRel, code, cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/$code", "/Order", cond, code, errorRel)))
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(field, value, 1, 1)
        val iDoc = def.newDocument()
            .field(field, value, 1, 1)
        val relevant = setOf(code, "datumFormatFalsch")
        val kernel = RuntimeLaws.messages(kModel, kDoc).map { it.code }.filter { it in relevant }.sorted()
        val interp = DmInterpreter(def).validateFull(iDoc).map { it.code }.filter { it in relevant }.sorted()
        assertEquals(kernel, interp, "$field = '$value'  (kernel=$kernel interp=$interp)")
    }

    @Test fun nonIsoFieldExtractsItsOwnFormat() = agree(AD, "15.06.2024", "AY", "../ApproxDate")
    @Test fun nonIsoFieldRejectsAnIsoValue() = agree(AD, "2024-06-15", "AY", "../ApproxDate")
    @Test fun nonIsoFieldRejectsGarbage() = agree(AD, "not-a-date", "AY", "../ApproxDate")
    @Test fun isoFieldExtractsIsoValue() = agree(OD, "2024-06-15", "OY", "../OrderDate")
    @Test fun isoFieldRejectsADeValue() = agree(OD, "15.06.2024", "OY", "../OrderDate")

    private companion object {
        const val OD = "/Order/OrderDate"
        const val AD = "/Order/ApproxDate"
    }
}
