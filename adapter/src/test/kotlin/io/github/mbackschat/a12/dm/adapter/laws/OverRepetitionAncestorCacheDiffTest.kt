package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.document.apiV2.DocumentMultiPointer
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer
import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.DocumentV2
import com.mgmtp.a12.kernel.md.rt.api.IMessage
import com.mgmtp.a12.kernel.md.rt.api.ICustomCondition
import io.github.mbackschat.a12.dm.interpreter.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.CustomConditionRegistry
import io.github.mbackschat.a12.dm.interpreter.InterpreterConfiguration
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.ValidationFinding
import io.github.mbackschat.a12.dm.interpreter.RelevantCoordinate
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/** Structural rejection and ancestor error state are separate channels (SPEC-2026-10-10-18). */
class OverRepetitionAncestorCacheDiffTest {
    private enum class Fault { NONE, IN_CAPACITY, EXCESS_INNER, EXCESS_MIDDLE }

    private val model = run {
        val base = DocumentModelDraft.named("shipment-ancestor-errors").locale("en_US")
            .rootGroup("Shipment") { s ->
                s.stringField("Title") { }
                    .group("Manifests") { m ->
                        m.repeatable(2).stringField("Label") { }
                            .group("Crates") { c ->
                                c.repeatable(2).stringField("Tag") { }
                                    .group("Items") { i ->
                                        i.repeatable(2).numberField("Quantity") { it.fractionDigits(0) }
                                    }
                            }
                    }
            }.build().toJson()
        val session = EditSession.read(base)
        val shipment = session.group("/Shipment")
        val manifests = session.group("/Shipment/Manifests")
        var result = session.toJson()
        for (n in 0L..2L) {
            result = TypedRules.inject(result, shipment.rule {
                it.name("RootCount$n").code("ROOT$n").errorField(shipment.str("Title").field())
                    .`when`(shipment.str("Title").filled().and(Dsl.numberOfFilledGroups(shipment.flattenGroup("Manifests*")).eq(n)))
                    .message(Dsl.en("Check manifest count.")).build()
            })
            result = TypedRules.inject(result, manifests.rule {
                it.name("BoundCount$n").code("BOUND$n").errorField(manifests.str("Label").field())
                    .`when`(manifests.str("Label").filled().and(Dsl.numberOfFilledGroups(manifests.flattenGroup("Crates*")).eq(n)))
                    .message(Dsl.en("Check crate count.")).build()
            })
        }
        result
    }

    private fun document(fault: Fault, parent: Int, malformed: Boolean): String {
        val quantity = if (malformed) "\"bad\"" else "1"
        val manifests = (1..2).joinToString(",") { row ->
            val affected = row == parent
            val crates = (1..if (affected && fault == Fault.EXCESS_MIDDLE) 3 else 2).joinToString(",") { crate ->
                val items = when {
                    affected && fault == Fault.EXCESS_INNER && crate == 1 -> "{\"Quantity\":1},{},{\"Quantity\":$quantity}"
                    affected && fault == Fault.EXCESS_MIDDLE && crate == 3 -> "{\"Quantity\":$quantity}"
                    affected && fault == Fault.IN_CAPACITY && crate == 1 -> "{\"Quantity\":$quantity}"
                    else -> "{\"Quantity\":1}"
                }
                """{"Tag":"crate","Items":[$items]}"""
            }
            """{"Label":"manifest","Crates":[$crates]}"""
        }
        return """{"Shipment":{"Title":"shipment","Manifests":[$manifests]}}"""
    }

    @Test
    fun excessDescendantsReportWithoutPoisoningTheirAncestors() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
        val dynamic = RuntimeLaws.kernelEngine(model)
        val static = RuntimeLaws.kernelJavaEngine(model)
        val prepared = PreparedModel.loadExpandedJson(model)
        val kernelRelevant = setOf(RuntimeLaws.Relevant.of("/Shipment", 0))
        val relevant = listOf(prepared.model.relevantPointer("/Shipment", listOf(RelevantCoordinate.All)))
        for (fault in Fault.entries) for (parent in 1..2) for (malformed in listOf(false, true)) {
            val label = "$fault / parent$parent / malformed$malformed"
            val json = document(fault, parent, malformed)
            val expected = dynamic.partialValidationMessages(dynamic.instance(json), kernelRelevant, null, Locale.US)
            val signatures = expected.map(::signature).sorted()
            assertEquals(signatures, static.partialValidationMessages(static.instance(json), kernelRelevant, null, Locale.US)
                .map(::signature).sorted(), "$label: static")
            val inCapacityError = fault == Fault.IN_CAPACITY && malformed
            val counts = buildList {
                if (!inCapacityError || parent == 2) add("ROOT2@[1, 1]")
                for (row in 1..2) if (!inCapacityError || row != parent) add("BOUND2@[1, $row, 1]")
            }.sorted()
            assertEquals(counts, expected.filter { it.errorCode.startsWith("ROOT") || it.errorCode.startsWith("BOUND") }
                .map { "${it.errorCode}@${it.errorFieldPointer.repetitionIndexes()}" }.sorted(), "$label: definite count ladder")
            assertEquals(if (inCapacityError) 1 else 0, expected.count { it.errorCode == "zahlHatUngueltigeZeichen" }, "$label: value error")
            if (fault == Fault.EXCESS_INNER || fault == Fault.EXCESS_MIDDLE) {
                assertTrue(expected.any { it.errorCode == "zuGrosseZeile" }, "$label: structural rejection is still reported")
            }
            val actual = prepared.createInterpreter().validatePart(prepared.documents.readDocument(json), relevant)
            assertEquals(emptyList<Any>(), actual.unsupported, label)
            assertEquals(signatures, actual.findings.map(::signature).sorted(), "$label: interpreter")
            DocumentDifferential.assertAgree(model, mapOf(label to json))
        }
    }

    @Test
    fun customConditionsObserveOnlyOrdinaryInvalidFields() {
        val edit = EditSession.read(model)
        val shipment = edit.group("/Shipment")
        shipment.addRule {
            it.name("ObserveFailures").code("OBSERVE").errorField(shipment.str("Title"))
                .`when`(shipment.str("Title").filled().and(Dsl.customCondition("ObserveFailures"))).message(Dsl.en("Observe failures.")).build()
        }
        val jsonModel = edit.toJson()
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(jsonModel))
        val dynamic = RuntimeLaws.kernelEngine(jsonModel)
        val static = RuntimeLaws.kernelJavaEngine(jsonModel)
        val prepared = PreparedModel.loadExpandedJson(jsonModel)
        for (fault in Fault.entries) for (parent in 1..2) for (malformed in listOf(false, true)) {
            val label = "$fault / parent$parent / malformed$malformed"
            val json = document(fault, parent, malformed)
            val expected = if (fault == Fault.IN_CAPACITY && malformed)
                listOf("/Shipment/Manifests/Crates/Items/Quantity@[1, $parent, 1, 1, 1]") else emptyList()
            val observations = mutableListOf<List<String>>()
            val callback = object : ICustomCondition {
                override fun check(document: DocumentV2, relevant: Set<out DocumentMultiPointer>?,
                    invalid: Set<DocumentPointer>, error: PartiallyKnownDocumentMultiPointer): Boolean {
                    assertEquals(null, relevant)
                    assertEquals("/Shipment/Title", error.path)
                    assertEquals(listOf(1, 1), error.repetitions.toList())
                    observations += invalid.map { "${it.path}@${it.repetitions.toList()}" }.sorted()
                    return false
                }
            }
            dynamic.validationSignaturesSorted(dynamic.instance(json), Locale.US, null, mapOf("ObserveFailures" to callback))
            assertEquals(listOf(expected), observations, "$label: dynamic callback")
            observations.clear()
            static.validationSignaturesSorted(static.instance(json), Locale.US, null, mapOf("ObserveFailures" to callback))
            assertEquals(listOf(expected), observations, "$label: static callback")
            observations.clear()
            val registry = CustomConditionRegistry.builder().register("ObserveFailures", CustomCondition { invocation ->
                assertEquals(null, invocation.relevantEntities)
                observations += invocation.formallyIncorrectEntities.map { "${it.path}@${it.coordinates}" }.sorted()
                false
            }).build()
            val interpreter = prepared.createInterpreter(InterpreterConfiguration.builder().customConditions(registry).build())
            val report = interpreter.validateFull(prepared.documents.readDocument(json))
            assertEquals(emptyList<Any>(), report.unsupported, label)
            assertEquals(listOf(expected), observations, "$label: public callback")
        }
    }

    private fun signature(message: IMessage): String =
        "${message.errorCode}|${message.messageType}|${message.errorFieldPointer.fullName()}|${message.errorFieldPointer.repetitionIndexes()}"

    private fun signature(message: ValidationFinding): String =
        "${message.code}|${message.type.name}_ERROR|${message.errorPath.fullName}|${message.errorPath.repetitionIndexes}"
}
