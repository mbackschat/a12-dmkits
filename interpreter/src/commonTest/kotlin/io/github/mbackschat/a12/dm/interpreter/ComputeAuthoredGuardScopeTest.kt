package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** Portable counterpart of the dual-kernel ComputeAuthoredGuardScopeDiffTest. */
class ComputeAuthoredGuardScopeTest {
    private val root = "/Parcel"
    private val returns = "$root/Returns"
    private val detail = "$returns/Detail"
    private val fit = "$detail/Fit"

    @Test
    fun narrowGuardExcludesOuterSiblingsUntilAnotherWriterNeedsTheWholeRow() {
        val narrow = ComputationDef(fit, detail,
            listOf(ComputationAlternative(null, "[/Parcel/Base] + 1")),
            commonPrecondition = "GroupFilled(/Parcel/Returns/Detail)", name = "Narrow")
        val implicit = ComputationDef(fit, detail,
            listOf(ComputationAlternative(null, "[/Parcel/Base] + 1")), name = "Implicit")
        val alternative = ComputationDef(fit, detail,
            listOf(ComputationAlternative("GroupFilled(/Parcel/Returns/Detail)", "[/Parcel/Base] + 1")),
            name = "Alternative")
        for (computations in listOf(listOf(narrow), listOf(alternative), listOf(implicit),
            listOf(narrow, implicit), listOf(implicit, narrow), listOf(narrow, alternative))) {
            val model = ModelDef(
                fields = listOf("$root/Base", "$returns/Qty", "$detail/Size", fit)
                    .map { FieldDef(it, Kind.NUMBER, scale = 0) },
                rules = emptyList(), computations = computations,
                groupPaths = setOf(root, returns, detail),
                repeatableGroups = setOf(returns), declaredReps = mapOf(returns to 3),
            )
            val json = """{"Parcel":{"Base":4,"Returns":[{"Qty":2.5,"Detail":{"Size":3}}]}}"""
            val run = DmInterpreter(model).retainedComputations(model.readDocumentJson(json))
            val expected = if (implicit in computations)
                listOf("zahlHatDezimalTrenner@/Parcel[1]/Returns[1]/Qty[1]") else emptyList()
            assertEquals(expected, run.formalErrorsInOperands.map { "${it.code}@${it.errorKey}" },
                computations.map { it.name }.toString())
            assertEquals(listOf("5"), run.results.map { it.value })
        }
    }
}
