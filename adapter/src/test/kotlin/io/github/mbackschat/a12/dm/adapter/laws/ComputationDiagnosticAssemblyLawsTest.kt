package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.adapter.testing.KernelMessageAddressProjector.address
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.*
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral
import java.util.Locale
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Result assembly against both kernel strategies, seven controls (KERNEL-FINDINGS KF541). On kernel 30.8.1 the
 * reserved `testIDNummer` key reported beside a duplicate index and so collided at one pointer; kernel 31.1.1 reads
 * it as an ordinary rejection that excludes the value (KF669), so those two controls now complete. The collision
 * itself is measured on all three engines by `CalendarTargetCompletionDiffTest` (KF666).
 */
class ComputationDiagnosticAssemblyLawsTest {
    private enum class Answer(val code: String?) { ACCEPT(null), REJECT("PROJECT_REJECT"), TEST("testIDNummer") }
    private enum class Programme { COPY, AUDIT, COPY_AND_AUDIT }
    private enum class Witness(
        val source: Answer,
        val second: String,
        val programme: Programme = Programme.COPY,
        val target: Answer = Answer.ACCEPT,
    ) {
        TEST_KEY_DUPLICATE(Answer.TEST, "ABCD"),
        ACCEPTED_DUPLICATE(Answer.ACCEPT, "ABCD"),
        TEST_KEY_DISTINCT(Answer.TEST, "EFGH"),
        REJECTED_DISTINCT(Answer.REJECT, "EFGH"),
        UNUSED_INDEX(Answer.TEST, "ABCD", Programme.AUDIT),
        SEPARATE_TARGET_ERRORS(Answer.ACCEPT, "EFGH", target = Answer.REJECT),
        TEST_KEY_DUPLICATE_BESIDE_CALLBACK(Answer.TEST, "ABCD", Programme.COPY_AND_AUDIT),
    }

    private class Recorder(private val witness: Witness) : ICustomFieldTypeFactory {
        val calls = mutableListOf<String>()
        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            Optional.of(object : ICustomFieldValidator {
                override fun validate(value: String, param: ICustomFieldTypeValidationParam, display: Boolean): Optional<ICustomFieldTypeCheckError> {
                    calls += "$name:$value"
                    val answer = when (name) {
                        "ParcelIdentifier" -> witness.source
                        "CopiedIdentifier" -> witness.target
                        "AuditStamp" -> Answer.ACCEPT
                        else -> error("unexpected custom type $name")
                    }
                    return answer.code?.let { code -> Optional.of(object : ICustomFieldTypeCheckError {
                        override fun getErrorKey() = code
                        override fun getErrorMessage() = "Review the supplied parcel identifier."
                    }) } ?: Optional.empty()
                }
            })
    }

    private fun model(programme: Programme): String {
        val base = DocumentModelDraft.named("parcel-diagnostic-assembly").locale("en_US")
            .rootGroup("Parcel") { p ->
                p.customField("Audit") { it.type("AuditStamp") }.group("Tags") { tags ->
                    tags.repeatable(2).customField("Identifier") { it.type("ParcelIdentifier") }
                        .customField("Copy") { it.type("CopiedIdentifier") }.indexField("Identifier")
                }
            }.build().toJson()
        val bound = BoundModel.ofJson(base)
        val source = bound.custom("/Parcel/Tags/Identifier")
        val copy = bound.custom("/Parcel/Tags/Copy")
        val audit = bound.custom("/Parcel/Audit")
        val edit = EditSession.read(base)
        if (programme != Programme.AUDIT) edit.addComputation { m ->
            m.computation(copy).name("CopyIdentifier").calc(source.asString())
                .message(Dsl.en("Review the copy.")).build()
        }
        if (programme != Programme.COPY) edit.addComputation { m ->
            m.computation(audit).name("AuditParcels").calc(StringLiteral("checked"))
                .message(Dsl.en("Review the parcel audit.")).build()
        }
        return edit.toJson()
    }

    private fun document(witness: Witness): String = ObjectMapper().writeValueAsString(mapOf("Parcel" to mapOf(
        "Audit" to "stale",
        "Tags" to listOf("ABCD", witness.second).map { mapOf("Identifier" to it, "Copy" to "stale") },
    )))

    @Test
    fun bothStrategiesCompleteTheSevenControlsBeforePublicAssemblyIsCompared() {
        val mismatches = mutableListOf<String>()
        val measurements = mutableListOf<String>()
        for (programme in Programme.entries) {
            val json = model(programme)
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json), "$programme admission")
            val dynamic = RuntimeLaws.kernelEngine(json)
            val static = RuntimeLaws.kernelJavaEngine(json)
            val prepared = PreparedModel.loadExpandedJson(json)
            for (witness in Witness.entries.filter { it.programme == programme }) {
                val input = document(witness)
                val calls = mutableListOf<String>()
                val registry = CustomFieldTypeRegistry.builder()
                for (name in listOf("ParcelIdentifier", "CopiedIdentifier", "AuditStamp")) registry.register(name) { value, _ ->
                    calls += "$name:$value"
                    val answer = when (name) {
                        "ParcelIdentifier" -> witness.source
                        "CopiedIdentifier" -> witness.target
                        "AuditStamp" -> Answer.ACCEPT
                        else -> error("unexpected custom type $name")
                    }
                    answer.code?.let { CustomFieldValidationResult.Rejected(it, "Review the supplied parcel identifier.") }
                        ?: CustomFieldValidationResult.Accepted
                }
                val interpreter = prepared.createInterpreter(InterpreterConfiguration.builder().customFieldTypes(registry.build()).build())
                val snapshot = prepared.documents.readDocument(input)
                val original = prepared.documents.encodeDocument(snapshot)
                val findings = interpreter.validateFull(snapshot).findings
                val expectedCalls = mutableListOf<List<String>>()
                val expectedOutcomes = mutableListOf<List<String>>()
                val expectedFormal = mutableListOf<List<String>>()
                for (strategy in 0..1) {
                    val instance = if (strategy == 0) dynamic.instance(input) else static.instance(input)
                    val validation = if (strategy == 0) dynamic.validationMessages(instance, null, Locale.US, Recorder(witness))
                        else static.validationMessages(instance, null, Locale.US, Recorder(witness))
                    assertEquals(validation.map(::signature).sorted(), findings.map(::signature).sorted(), "$witness/$strategy validation")
                    val recorder = Recorder(witness)
                    // Keep the original modern compute ingress; a low-level calculator would bypass result assembly.
                    val result = if (strategy == 0) dynamic.computation(instance, null, Locale.US, recorder)
                        else static.computation(instance, null, Locale.US, recorder)
                    expectedCalls += recorder.calls.toList()
                    expectedOutcomes += result.outcomes().map {
                        "${address(A12PartiallyKnownMultiPointer.of(it.pointer().fullName(), it.pointer().repetitionIndexes()))}|${if (it.errored()) "ERRORED" else if (it.cleared()) "CLEARED" else "VALUE"}|${it.value().orElse(null)}"
                    }.sorted()
                    expectedFormal += result.formalErrorsInOperands().map(::signature).sorted()
                    val formalCodes = when (witness) {
                        Witness.ACCEPTED_DUPLICATE -> listOf("uniqueIndex", "uniqueIndex")
                        Witness.TEST_KEY_DUPLICATE, Witness.TEST_KEY_DISTINCT, Witness.TEST_KEY_DUPLICATE_BESIDE_CALLBACK ->
                            listOf("testIDNummer", "testIDNummer")
                        Witness.REJECTED_DISTINCT -> listOf("PROJECT_REJECT", "PROJECT_REJECT")
                        else -> emptyList()
                    }
                    assertEquals(formalCodes, result.formalErrorsInOperands().map { it.errorCode }.sorted(), "$witness/$strategy formal inventory")
                    if (formalCodes.isNotEmpty()) assertEquals(listOf(listOf(1, 1, 1), listOf(1, 2, 1)),
                        result.formalErrorsInOperands().map { it.errorFieldPointer.repetitionIndexes() }.sortedBy { it[1] },
                        "$witness/$strategy full physical row identity")
                    measurements += "$witness/$strategy outcomes=${expectedOutcomes.last()}; formal=${expectedFormal.last()}; callbacks=${recorder.calls}"
                }
                assertEquals(expectedCalls[0], expectedCalls[1], "$witness raw strategy callback cardinality and order")
                calls.clear()
                val report = interpreter.compute(snapshot)
                if (calls != expectedCalls[0]) mismatches += "$witness compute callbacks expected=${expectedCalls[0]} actual=$calls"
                assertEquals(expectedOutcomes[0], expectedOutcomes[1], "$witness strategy action channels")
                assertEquals(expectedFormal[0], expectedFormal[1], "$witness strategy formal channels")
                assertEquals(expectedOutcomes[0], report.actions.map {
                    "${address(A12PartiallyKnownMultiPointer.of(it.pointer.path, it.pointer.coordinates))}|${it.outcome}|${it.value}"
                }.sorted(), "$witness public actions")
                assertEquals(expectedFormal[0], report.formalErrorsInOperands.map(::signature).sorted(), "$witness public formal diagnostics")
                for (strategy in 0..1) {
                    val result = if (strategy == 0) dynamic.computeResults(dynamic.instance(input), null, Locale.US, Recorder(witness))
                        else static.computeResults(static.instance(input), null, Locale.US, Recorder(witness))
                    assertEquals(result.errored().map { signature(it.errorMessage().orElseThrow()) }.sorted(),
                        report.results.mapNotNull { it.diagnostic }.map(::signature).sorted(), "$witness/$strategy target diagnostics")
                    assertEquals(ObjectMapper().readTree(result.applied()),
                        ObjectMapper().readTree(prepared.documents.encodeDocument(report.applyTo(snapshot))), "$witness/$strategy applied document")
                }
                assertEquals(original, prepared.documents.encodeDocument(snapshot), "$witness caller snapshot is unchanged")
            }
        }
        assertEquals(14, measurements.size, "seven controls retain both kernel strategies separately")
        measurements.forEach(::println)
        assertEquals(emptyList<String>(), mismatches)
    }

    private fun signature(message: IMessage): String = listOf(
        message.errorCode, message.messageType, message.severity,
        address(A12PartiallyKnownMultiPointer.of(message.errorFieldPointer.fullName(), message.errorFieldPointer.repetitionIndexes())),
        message.referencedFieldsPointers.map { address(A12PartiallyKnownMultiPointer.of(it.fullName(), it.repetitionIndexes())) }.sorted(),
        message.refOmissionErrorResponsiblePointers.map { address(A12PartiallyKnownMultiPointer.of(it.fullName(), it.repetitionIndexes())) }.sorted(),
    ).joinToString("|")

    private fun signature(finding: ValidationFinding): String = listOf(
        finding.code, "${finding.type}_ERROR", finding.severity, address(finding.errorPath),
        finding.referenced.map(::address).sorted(), finding.fillToFix.map(::address).sorted(),
    ).joinToString("|")
}
