package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/** Own-domain guard profiles for SPEC-2026-10-10-01, including independent writers to one target. */
class ComputeAuthoredGuardScopeDiffTest {
    private enum class Guard { IMPLICIT, COMMON_NARROW, ALTERNATIVE_NARROW, COMMON_ROW, ALTERNATIVE_ROW }

    private val draft = DocumentModelDraft.named("parcel-guard-scope").locale("en_US")
        .rootGroup("Parcel") { p ->
            p.numberField("Base") { it.fractionDigits(0) }
                .group("Returns") { r ->
                    r.repeatable(3).numberField("Qty") { it.fractionDigits(0) }
                        .group("Detail") { d ->
                            d.numberField("Size") { it.fractionDigits(0) }
                                .numberField("Fit") { it.fractionDigits(0) }
                        }
                }
        }.build().toJson()

    private fun model(guards: List<Guard>): String {
        val session = EditSession.read(draft)
        val parcel = session.group("/Parcel")
        val returns = session.group("/Parcel/Returns")
        val detail = session.group("/Parcel/Returns/Detail")
        val fit = detail.num("Fit")
        var model = session.toJson()
        guards.forEachIndexed { index, guard ->
            val computation = detail.computation(fit) { builder ->
                builder.name("FitComp$index").message(Dsl.en("Check the fit."))
                val operation = parcel.num("Base").plus(1)
                when (guard) {
                    Guard.IMPLICIT -> builder.calc(operation)
                    Guard.COMMON_NARROW -> builder.commonPrecondition(detail.ref().filled()).calc(operation)
                    Guard.ALTERNATIVE_NARROW -> builder.`when`(detail.ref().filled()).calc(operation)
                    Guard.COMMON_ROW -> builder.commonPrecondition(returns.ref().filled()).calc(operation)
                    Guard.ALTERNATIVE_ROW -> builder.`when`(returns.ref().filled()).calc(operation)
                }.build()
            }
            model = TypedRules.inject(model, computation)
        }
        return model
    }

    @Test
    fun authoredGuardsDetermineFormalInputsBeforeWriterProfilesAreCombined() {
        val profiles = Guard.entries.map(::listOf) + listOf(
            listOf(Guard.COMMON_NARROW, Guard.IMPLICIT),
            listOf(Guard.IMPLICIT, Guard.COMMON_NARROW),
            listOf(Guard.COMMON_NARROW, Guard.ALTERNATIVE_NARROW),
        )
        data class Case(val name: String, val row: String, val malformed: String?)
        val cases = listOf(
            Case("valid", """{"Qty":2,"Detail":{"Size":3}}""", null),
            Case("outer invalid", """{"Qty":2.5,"Detail":{"Size":3}}""", "Qty"),
            Case("inner invalid", """{"Qty":2,"Detail":{"Size":3.5}}""", "Detail/Size"),
            Case("narrow group absent", """{"Qty":2.5}""", "Qty"),
        )
        for (guards in profiles) {
            val model = model(guards)
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model), guards.toString())
            val dynamic = RuntimeLaws.kernelEngine(model)
            val static = RuntimeLaws.kernelJavaEngine(model)
            val prepared = PreparedModel.loadExpandedJson(model)
            val includesOuter = guards.any { it != Guard.COMMON_NARROW && it != Guard.ALTERNATIVE_NARROW }
            for (case in cases) {
                val label = "$guards / ${case.name}"
                val json = """{"Parcel":{"Base":4,"Returns":[${case.row}]}}"""
                val expected = dynamic.computation(dynamic.instance(json), null, Locale.US)
                    .formalErrorsInOperands().map {
                        it.errorCode to (it.errorFieldPointer.fullName() to it.errorFieldPointer.repetitionIndexes())
                    }.sortedBy { it.toString() }
                val malformed = case.malformed?.takeIf { it != "Qty" || includesOuter }
                assertEquals(malformed?.let { listOf("/Parcel/Returns/$it") }.orEmpty(),
                    expected.map { it.second.first }, "$label: operand scope")
                assertEquals(expected, static.computation(static.instance(json), null, Locale.US)
                    .formalErrorsInOperands().map {
                        it.errorCode to (it.errorFieldPointer.fullName() to it.errorFieldPointer.repetitionIndexes())
                    }.sortedBy { it.toString() }, "$label: static formal inputs")
                assertEquals(expected, prepared.createInterpreter().compute(prepared.documents.readDocument(json))
                    .formalErrorsInOperands.map { it.code to (it.errorPath.fullName to it.errorPath.repetitionIndexes) }
                    .sortedBy { it.toString() }, "$label: interpreter formal inputs")
                DocumentDifferential.assertAgree(model, mapOf(label to json))
            }
        }
    }
}
