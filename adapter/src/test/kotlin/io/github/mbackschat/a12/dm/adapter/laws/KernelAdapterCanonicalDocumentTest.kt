package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.adapter.EvalMessage
import io.github.mbackschat.a12.dm.adapter.KernelAdapter
import io.github.mbackschat.a12.dm.adapter.RuleTestOutcome
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/** Public adapter ingress is measured against the owner serializer, independently of the CLI harness. */
class KernelAdapterCanonicalDocumentTest {
    @Test
    fun canonicalJsonMatchesBothOwnerReadersAcrossTheIngressMatrix() {
        val model = RuntimeLaws.model("models/order.dm.json")
        val adapter = KernelAdapter.ofJson(model)
        val dynamic = RuntimeLaws.kernelEngine(model)
        val static = RuntimeLaws.kernelJavaEngine(model)
        for (row in A12JsonIngressKernelTest.rows()) {
            val json = row.documentJson()
            val actual = adapter.evaluate(json, Locale.US).messages()
            assertEquals(row.codes().sorted(), actual.filter { it.field().path() == "/Order/${row.field()}" }
                .map { it.errorCode() }.sorted(), json)
            val expected = dynamic.validationMessages(dynamic.instance(json), null, Locale.US)
                .map(::signature).sorted()
            assertEquals(expected, static.validationMessages(static.instance(json), null, Locale.US)
                .map(::signature).sorted(), "static reader: $json")
            assertEquals(expected, actual.map(::signature).sorted(), "adapter reader: $json")
        }
    }

    @Test
    fun everyRuntimeOverloadConvertsJsonWhileRawMapAuthoringStaysRaw() {
        val base = DocumentModelDraft.named("invoice-canonical-adapter").locale("en_US")
            .rootGroup("Invoice") { group ->
                group.numberField("Amount") { it.fractionDigits(2, 2) }
                    .numberField("Copy") { it.fractionDigits(2, 2) }
            }.build().toJson()
        val edit = EditSession.read(base)
        val invoice = edit.group("/Invoice")
        val amount = invoice.num("Amount")
        val copy = invoice.num("Copy")
        invoice.addRule { it.name("OriginalFive").code("ORIGINAL").errorField(amount).`when`(amount.eq(5))
            .message(Dsl.en("Original is five.")).build() }
        invoice.addRule { it.name("CopyFive").code("COPY").errorField(copy).`when`(copy.eq(5))
            .message(Dsl.en("Copy is five.")).build() }
        invoice.addComputation(copy) { it.name("CopyAmount").calc(amount.value())
            .message(Dsl.en("Copy amount.")).build() }
        val adapter = KernelAdapter.ofJson(edit.toJson())
        val json = """{"Invoice":{"Amount":5}}"""
        assertEquals(setOf("ORIGINAL"), adapter.evaluate(json, Locale.US).firedCodes())
        assertEquals(setOf("COPY", "ORIGINAL"), adapter.evaluateApplied(json, Locale.US).firedCodes())
        assertEquals(RuleTestOutcome.Verdict.FIRED,
            adapter.testRule("/Invoice/OriginalFive", json, Locale.US).verdict())
        assertEquals(RuleTestOutcome.Verdict.PASSED,
            adapter.testRule("/Invoice/CopyFive", json, Locale.US, false).verdict())
        assertEquals(RuleTestOutcome.Verdict.FIRED,
            adapter.testRule("/Invoice/CopyFive", json, Locale.US, true).verdict())
        assertEquals(listOf("/Invoice/Copy|[1, 1]|value|5.00"), adapter.compute(json, Locale.US).computed()
            .map { "${it.path()}|${it.repetitions()}|${it.outcome()}|${it.value().orElse("")}" })
        val raw: Map<String, Any> = mapOf("Invoice" to mapOf("Amount" to "5"))
        assertEquals(setOf("zahlOhneDezimalTrenner"), adapter.evaluate(raw, Locale.US).firedCodes())
        assertEquals(RuleTestOutcome.Verdict.SUPPRESSED,
            adapter.testRule("/Invoice/OriginalFive", raw, Locale.US).verdict())
        val invalid = """{"Invoice":{"Amount":"bad","Copy":9}}"""
        assertEquals(setOf("zahlHatUngueltigeZeichen"), adapter.evaluate(invalid, Locale.US).firedCodes())
        assertEquals(RuleTestOutcome.Verdict.SUPPRESSED,
            adapter.testRule("/Invoice/OriginalFive", invalid, Locale.US).verdict())
        assertEquals(listOf("cleared"), adapter.compute(invalid, Locale.US).computed().map { it.outcome() })
    }

    private fun signature(message: EvalMessage): String = listOf(
        message.errorCode(), message.rulePath().orElse(""),
        message.field().path() to message.field().reps(), message.severity(), message.messageType(), message.message(),
        message.referenced().map { it.path() to it.reps() }.sortedBy { it.toString() },
        message.fillToFix().map { it.path() to it.reps() }.sortedBy { it.toString() },
    ).toString()

    private fun signature(message: IMessage): String = listOf(
        message.errorCode, message.rulePath.orElse(""),
        message.errorFieldPointer.fullName() to message.errorFieldPointer.repetitionIndexes(),
        message.severity.name, message.messageType.name, message.errorText,
        message.referencedFieldsPointers.map { it.fullName() to it.repetitionIndexes() }.sortedBy { it.toString() },
        message.refOmissionErrorResponsiblePointers.map { it.fullName() to it.repetitionIndexes() }.sortedBy { it.toString() },
    ).toString()
}
