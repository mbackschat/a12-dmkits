package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer
import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.Locale

/**
 * One message as an engine reports it: code, type (`VALUE_ERROR`/`OMISSION_ERROR`), the error field's pointer, and
 * the referenced and fill-to-fix pointers, each sorted. [brief] is the first three alone, for a pin that names only
 * where a message fired.
 */
internal data class MessageSignature(
    val code: String,
    val type: String,
    val pointer: String,
    val referenced: List<String>,
    val fillToFix: List<String>,
) : Comparable<MessageSignature> {
    val brief: String get() = "$code|$type|$pointer"

    override fun compareTo(other: MessageSignature): Int = toString().compareTo(other.toString())

    override fun toString(): String = "$brief|ref=$referenced|fill=$fillToFix"
}

/**
 * One engine's view of a Document: its validation messages, its computation outcomes (`VALUE|v`, `ERRORED|v` or
 * `CLEARED`), and the computation's separate channel of formal errors in operands.
 */
internal data class DocumentObservation(
    val messages: List<MessageSignature>,
    val outcomes: Map<String, String>,
    val operandErrors: List<MessageSignature>,
)

internal object DocumentDifferential {

    fun kernel(model: String, json: String, dynamic: Boolean): DocumentObservation {
        val (messages, computation) = if (dynamic) {
            val engine = RuntimeLaws.kernelEngine(model)
            engine.validationMessages(engine.instance(json), null, Locale.US) to
                engine.computation(engine.instance(json), null, Locale.US)
        } else {
            val engine = RuntimeLaws.kernelJavaEngine(model)
            engine.validationMessages(engine.instance(json), null, Locale.US) to
                engine.computation(engine.instance(json), null, Locale.US)
        }
        return DocumentObservation(
            messages.map(::kernelSignature).sorted(),
            computation.outcomes().associate {
                outcomeKey(formatPointer(it.pointer().fullName(), it.pointer().repetitionIndexes())) to when {
                    it.errored() -> "ERRORED|" + it.value().orElse("")
                    it.cleared() -> "CLEARED"
                    else -> "VALUE|" + it.value().orElse("")
                }
            }.toSortedMap(),
            computation.formalErrorsInOperands().map(::kernelSignature).sorted(),
        )
    }

    /** The interpreter's observation; the operand channel is read off the public report, the surface a consumer uses. */
    fun interpreter(model: String, json: String): DocumentObservation {
        val def = ModelLoader.load(model)
        val engine = DmInterpreter(def)
        val messages = engine.validate(def.readDocumentJson(json)).also {
            assertEquals(emptyList<Any>(), it.unsupported, "every construct must be evaluable")
        }.messages.map {
            MessageSignature(it.code, typeName(it.type.name), pointer(it.errorPointer),
                it.referencedPointers.map(::pointer).sorted(), it.fillToFixPointers.map(::pointer).sorted())
        }.sorted()
        val document = def.readDocumentJson(json)
        val signatures = engine.compute(document).map {
            val pointer = formatPointer(it.path, it.indices)
            when (it.outcome) {
                ComputeOutcome.VALUE -> "$pointer|VALUE|${it.value ?: ""}"
                ComputeOutcome.CLEARED -> "$pointer|CLEARED"
                ComputeOutcome.ERRORED -> "$pointer|ERRORED|${it.value ?: ""}"
            }
        }
        val outcomes = kernelComputeGranularity(signatures, document, def).associate {
            outcomeKey(it.substringBefore('|')) to it.substringAfter('|')
        }.toSortedMap()
        val prepared = PreparedModel.loadExpandedJson(model)
        val operandErrors = prepared.createInterpreter().compute(prepared.documents.readDocument(json))
            .formalErrorsInOperands.map {
                MessageSignature(it.code, typeName(it.type.name), pointer(it.errorPath),
                    it.referenced.map(::pointer).sorted(), it.fillToFix.map(::pointer).sorted())
            }.sorted()
        return DocumentObservation(messages, outcomes, operandErrors)
    }

    /** Asserts that static Java and the interpreter each observe every Document as dynamic Groovy does. */
    fun assertAgree(model: String, documents: Map<String, String>) {
        for ((name, json) in documents) {
            val dynamic = kernel(model, json, dynamic = true)
            assertEquals(dynamic, kernel(model, json, dynamic = false), "$name: static Java vs dynamic Groovy")
            assertEquals(dynamic, interpreter(model, json), "$name: interpreter vs dynamic Groovy")
        }
    }

    /** A computation target's key: its pointer below the root group, repetitions other than the first spelled out. */
    private fun outcomeKey(pointer: String): String = pointer.trim('/').substringAfter('/').replace("[1]", "")

    private fun typeName(interpreterType: String) = if (interpreterType == "VALUE") "VALUE_ERROR" else "OMISSION_ERROR"

    private fun pointer(p: PartiallyKnownDocumentMultiPointer): String = formatPointer(p.fullName(), p.repetitionIndexes())

    private fun pointer(p: A12PartiallyKnownMultiPointer): String = formatPointer(p.fullName, p.repetitionIndexes)

    private fun kernelSignature(m: IMessage): MessageSignature = MessageSignature(m.errorCode, m.messageType.name,
        pointer(m.errorFieldPointer), m.referencedFieldsPointers.map(::pointer).sorted(),
        m.refOmissionErrorResponsiblePointers.map(::pointer).sorted())
}
