package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Locale

/**
 * Every formal message names its own field as referenced, and an omission-typed one also names it as responsible
 * for the omission — on every formal family, because the kernel adds them all through one funnel
 * (`FormalChecker.addFormalError`, and `CalculationController.addBerechnungsFehler` for the computed-value error,
 * runtime classes). One witness per family: a date and a number format error, a missing required field, a
 * duplicate index key, both over-repetition codes, and a computation's domain failure on the operand channel.
 *
 * Own domain (online order fulfilment).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FormalMessageReferenceDiffTest {
    private val model: String = run {
        val draft = DocumentModelDraft.named("order-formal-references").locale("en_US")
            .rootGroup("Order") { order ->
                order.dateField("Placed") { it.format("yyyy-MM-dd") }
                order.numberField("Quantity") { it.fractionDigits(0) }
                order.numberField("Divisor") { it.fractionDigits(0) }
                order.numberField("PerUnit") { it.fractionDigits(0) }
                order.stringField("Customer") { it.required() }
                order.group("Lines") { lines ->
                    lines.repeatable(2)
                    lines.indexField("LineNo")
                    lines.stringField("LineNo") { }
                    lines.numberField("Qty") { it.fractionDigits(0) }
                }
            }.build().toJson()
        val session = EditSession.read(draft)
        val order = session.group("/Order")
        TypedRules.inject(session.toJson(), order.computation(order.num("PerUnit")) {
            it.name("PerUnitComp")
                .calc(Dsl.roundAccounting(order.num("Quantity").asNumber().dividedBy(order.num("Divisor").asNumber()), 0).expression())
                .message(Dsl.en("Check the unit price.")).build()
        })
    }

    private val documents = linkedMapOf(
        "formats-and-omission" to """{"Order":{"Placed":"soon","Quantity":"many","Lines":[{"LineNo":"1","Qty":1}]}}""",
        "duplicate-and-over-capacity" to """{"Order":{"Placed":"2024-05-01","Quantity":4,"Customer":"Ada",""" +
            """"Lines":[{"LineNo":"1","Qty":1},{"LineNo":"1","Qty":2},{"LineNo":"3","Qty":3}]}}""",
        "domain-failure" to """{"Order":{"Placed":"2024-05-01","Quantity":4,"Divisor":0,"PerUnit":9,"Customer":"Ada",""" +
            """"Lines":[{"LineNo":"1","Qty":1}]}}""",
    )

    private fun pointers(list: List<String>) = list.sorted().joinToString(",", "[", "]")

    private fun kernelMessages(m: IMessage): String =
        "${m.errorCode}|${m.messageType.name}|" +
            formatPointer(m.errorFieldPointer.fullName(), m.errorFieldPointer.repetitionIndexes()) +
            "|ref=" + pointers(m.referencedFieldsPointers.map { formatPointer(it.fullName(), it.repetitionIndexes()) }) +
            "|fill=" + pointers(m.refOmissionErrorResponsiblePointers.map {
                formatPointer(it.fullName(), it.repetitionIndexes())
            })

    private fun kernel(dynamic: Boolean, json: String): List<String> {
        val messages = if (dynamic) RuntimeLaws.kernelEngine(model).let { it.validationMessages(it.instance(json), null, Locale.US) }
            else RuntimeLaws.kernelJavaEngine(model).let { it.validationMessages(it.instance(json), null, Locale.US) }
        val operand = if (dynamic) RuntimeLaws.kernelEngine(model).let { it.computation(it.instance(json), null, Locale.US) }
            else RuntimeLaws.kernelJavaEngine(model).let { it.computation(it.instance(json), null, Locale.US) }
        return (messages.map(::kernelMessages) + operand.formalErrorsInOperands().map { "operand:" + kernelMessages(it) })
            .sorted()
    }

    private fun interpreter(json: String): List<String> {
        val def = ModelLoader.load(model)
        val messages = DmInterpreter(def).validate(def.readDocumentJson(json)).messages.map {
            val type = if (it.type == MsgType.VALUE) "VALUE_ERROR" else "OMISSION_ERROR"
            "${it.code}|$type|" + formatPointer(it.errorPointer.fullName, it.errorPointer.repetitionIndexes) +
                "|ref=" + pointers(it.referencedPointers.map { p -> formatPointer(p.fullName, p.repetitionIndexes) }) +
                "|fill=" + pointers(it.fillToFixPointers.map { p -> formatPointer(p.fullName, p.repetitionIndexes) })
        }
        val prepared = PreparedModel.loadExpandedJson(model)
        val operand = prepared.createInterpreter().compute(prepared.documents.readDocument(json)).formalErrorsInOperands
            .map {
                val type = if (it.type.name == "VALUE") "VALUE_ERROR" else "OMISSION_ERROR"
                "operand:${it.code}|$type|" + formatPointer(it.errorPath.fullName, it.errorPath.repetitionIndexes) +
                    "|ref=" + pointers(it.referenced.map { p -> formatPointer(p.fullName, p.repetitionIndexes) }) +
                    "|fill=" + pointers(it.fillToFix.map { p -> formatPointer(p.fullName, p.repetitionIndexes) })
            }
        return (messages + operand).sorted()
    }

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    @Test
    fun everyFormalFamilyReferencesItsOwnFieldOnAllThreeEngines() {
        val codesSeen = mutableSetOf<String>()
        for ((name, json) in documents) {
            val dynamic = kernel(dynamic = true, json)
            assertEquals(dynamic, kernel(dynamic = false, json), "$name: static Java vs dynamic Groovy")
            assertEquals(dynamic, interpreter(json), "$name: interpreter vs dynamic Groovy")
            codesSeen += dynamic.map { it.removePrefix("operand:").substringBefore('|') }
        }
        // The positive control: each family this file claims was actually emitted, so an empty agreement cannot pass.
        assertEquals(setOf("datumFormatFalsch", "zahlHatUngueltigeZeichen", "mandatoryField", "uniqueIndex",
            "zuGrosseZeile", "zuGrosseKontextnummer", "berechnungsWertFehler"),
            codesSeen.filter { it.first().isLowerCase() }.toSet())
    }
}
