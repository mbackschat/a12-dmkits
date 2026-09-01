package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.ComputationOutcome
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.modelkit.RangeYearInterpretation
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-08-24-02's missing declaration-order control. The ledger model declares interpreted `dd.MM`
 * fields before every `MM` field. Reversing only those two declaration blocks changes both validity and target
 * presentation on both kernel strategies, so the observed dotted carrier is not a field-local DateRange rule.
 *
 * The source discriminator is the shared-format key: `IDateRangeType.getFormatOfDateRange` canonicalizes every
 * nonstandard declaration of one direction to a `dd.MM-dd.MM (...)` key, while `CodeGenDateRangeTypeImpl`
 * retains the first declaration's actual date format and separator. `MetaGemeinsameFormate` keeps that first
 * equal definition. The interpreter deliberately keeps declaration-local formats until that separate kernel
 * defect has a sound, fully measured contract.
 *
 * The two models use distinct header ids only to isolate generated-engine caches. That id does not enter the
 * discriminator: `MetaGemeinsameFormate` constructs its map exclusively from the enumerated field definitions.
 */
class DateRangeSharedFormatOrderDiffTest {

    private data class Observation(val validation: List<String>, val computation: String)

    private val dottedFirst by lazy { modelJson(dottedFirst = true) }
    private val monthFirst by lazy { modelJson(dottedFirst = false) }

    @Test
    fun declarationOrderSeparatesTheLedgerObservationFromFieldLocalSemantics() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(dottedFirst))
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(monthFirst))

        val wrapping = "{\"Season\":{\"Entries\":[{\"MonthTo\":\"11/02\"}]}}"
        assertEquals(
            Observation(emptyList(), "VALUE|01.11-01.02"),
            kernelObservation(dottedFirst, wrapping, WRAPPED_TARGET),
            "the ledger's dotted-first declaration order",
        )
        assertEquals(
            listOf("datumBereichNichtGueltig|VALUE_ERROR|/Season[1]/Entries[1]/MonthTo"),
            kernelObservation(monthFirst, wrapping, WRAPPED_TARGET).validation,
            "the same declarations with the MM block first reject the wrapping month pair",
        )

        val ordered = "{\"Season\":{\"Entries\":[{\"StandardMonth\":\"03/10\"}]}}"
        assertEquals(
            Observation(emptyList(), "VALUE|01.03-31.10"),
            kernelObservation(dottedFirst, ordered, ORDERED_TARGET),
            "the ledger order projects the standard source through the shared dotted format",
        )
        assertEquals(
            Observation(emptyList(), "VALUE|03/10"),
            kernelObservation(monthFirst, ordered, ORDERED_TARGET),
            "the MM-first control keeps the target's declared spelling",
        )

        assertEquals(listOf("datumBereichNichtGueltig"),
            interpreterObservation(dottedFirst, wrapping, WRAPPED_TARGET).validation)
        assertEquals(listOf("datumBereichNichtGueltig"),
            interpreterObservation(monthFirst, wrapping, WRAPPED_TARGET).validation)
        assertEquals("VALUE|03/10", interpreterObservation(dottedFirst, ordered, ORDERED_TARGET).computation)
        assertEquals("VALUE|03/10", interpreterObservation(monthFirst, ordered, ORDERED_TARGET).computation)
    }

    private fun modelJson(dottedFirst: Boolean): String {
        val authored = DocumentModelDraft.named("seasonal-order-${if (dottedFirst) "dotted" else "month"}")
            .locale("en_US")
            .rootGroup("Season") { season ->
                season.group("Entries") { entries ->
                    entries.repeatable(20)
                    fun dottedBlock() {
                        entries.dateRangeField("DottedFrom") {
                            it.format("dd.MM").separator("-")
                                .interpretationOfYear(RangeYearInterpretation.FROM)
                        }
                        entries.dateRangeField("DottedTo") {
                            it.format("dd.MM").separator("-")
                                .interpretationOfYear(RangeYearInterpretation.TO)
                        }
                    }
                    fun monthBlock() {
                        entries.dateRangeField("MonthFrom") {
                            it.format("MM").separator("/")
                                .interpretationOfYear(RangeYearInterpretation.FROM)
                        }
                        entries.dateRangeField("MonthTo") {
                            it.format("MM").separator("/")
                                .interpretationOfYear(RangeYearInterpretation.TO)
                        }
                        entries.dateRangeField("StandardMonth") { it.format("MM").separator("/") }
                    }
                    if (dottedFirst) {
                        dottedBlock()
                        monthBlock()
                    } else {
                        monthBlock()
                        dottedBlock()
                    }
                }
                season.dateRangeField("WrappedTarget") {
                    it.format("MM").separator("/").interpretationOfYear(RangeYearInterpretation.FROM)
                }
                season.dateRangeField("OrderedTarget") {
                    it.format("MM").separator("/").interpretationOfYear(RangeYearInterpretation.FROM)
                }
            }.build().toJson()
        val base = ModelDocument.read(authored).setTimeZone("UTC").toJson()
        val model = BoundModel.ofJson(base)

        fun first(name: String, target: String, source: String): Computation =
            model.computation(model.dateRange("$ROOT/$target")).name(name)
                .calc(Dsl.firstFilledDateRange(model.flatten("$ENTRIES*/$source")).expression())
                .message(Dsl.en("Project the first seasonal range.")).build()

        return listOf(
            first("WrappedTargetComp", "WrappedTarget", "MonthTo"),
            first("OrderedTargetComp", "OrderedTarget", "StandardMonth"),
        ).fold(base) { json, computation -> TypedRules.inject(json, computation) }
    }

    private fun kernelObservation(model: String, document: String, target: String): Observation {
        val dynamic = RuntimeLaws.kernelEngine(model)
        val dynamicInstance = dynamic.instance(document)
        val generated = RuntimeLaws.kernelJavaEngine(model)
        val generatedInstance = generated.instance(document)
        val validation = dynamic.validationSignaturesSorted(dynamicInstance)
        val computation = kernelComputation(dynamic.computationSignaturesSorted(dynamicInstance), target)
        assertEquals(validation, generated.validationSignaturesSorted(generatedInstance), "dynamic vs generated validity")
        assertEquals(computation, kernelComputation(generated.computationSignaturesSorted(generatedInstance), target),
            "dynamic vs generated computation")
        return Observation(validation, computation)
    }

    private fun kernelComputation(signatures: List<String>, target: String): String = signatures
        .singleOrNull { it.startsWith("/Season[1]/$target|") }
        ?.substringAfter("/Season[1]/$target|") ?: "NONE"

    private fun interpreterObservation(model: String, document: String, target: String): Observation {
        val prepared = PreparedModel.loadExpandedJson(model)
        val snapshot = prepared.documents.readDocument(document)
        val interpreter = prepared.createInterpreter()
        val validation = interpreter.validateFull(snapshot).findings.map { it.code }.sorted()
        val result = interpreter.compute(snapshot).results.singleOrNull { it.pointer.path == "$ROOT/$target" }
        val computation = when (result?.outcome) {
            null -> "NONE"
            ComputationOutcome.CLEARED -> "CLEARED"
            ComputationOutcome.ERRORED -> "ERRORED|${result.value}"
            ComputationOutcome.VALUE -> "VALUE|${result.value}"
        }
        return Observation(validation, computation)
    }

    private companion object {
        const val ROOT = "/Season"
        const val ENTRIES = "/Season/Entries"
        const val WRAPPED_TARGET = "WrappedTarget"
        const val ORDERED_TARGET = "OrderedTarget"
    }
}
