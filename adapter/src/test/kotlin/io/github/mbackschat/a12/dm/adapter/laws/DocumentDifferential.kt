package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.Locale

/**
 * One engine's view of a Document: its validation signatures (`code|type|pointer`, sorted), its computation outcomes
 * (`VALUE|v`, `ERRORED|v` or `CLEARED`), and the computation's separate channel of formal errors in operands
 * (`code@pointer`, sorted).
 */
internal data class DocumentObservation(
    val messages: List<String>,
    val outcomes: Map<String, String>,
    val operandErrors: List<String>,
)

/**
 * The three-engine observation of a Document given as A12 Document JSON: dynamic Groovy (normative), static Java and
 * the interpreter, each validated and computed once.
 *
 * Two conventions make the observations comparable. The kernel reports only computation targets whose stored state
 * changes, while the interpreter reports every target, so the interpreter's outcomes pass through the shipped
 * [kernelComputeGranularity] projection; a caller seeds each target with a value no alternative produces, so every
 * computed value is a change. Outcomes are keyed by the target's path below the root group with every repetition
 * other than the first spelled out, so a root-level target is keyed by its name and a row target as `Items[4]/Stamp`.
 */
internal object DocumentDifferential {

    fun kernel(model: String, json: String, dynamic: Boolean): DocumentObservation {
        val (messages, computation, channels) = if (dynamic) {
            val engine = RuntimeLaws.kernelEngine(model)
            Triple(engine.validationMessages(engine.instance(json), null, Locale.US),
                engine.computation(engine.instance(json), null, Locale.US), engine.resultChannels(engine.instance(json)))
        } else {
            val engine = RuntimeLaws.kernelJavaEngine(model)
            Triple(engine.validationMessages(engine.instance(json), null, Locale.US),
                engine.computation(engine.instance(json), null, Locale.US), engine.resultChannels(engine.instance(json)))
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
            channels.formalErrorsInOperands().map { "${it.code()}@${it.field()}" }.sorted(),
        )
    }

    fun interpreter(model: String, json: String): DocumentObservation {
        val def = ModelLoader.load(model)
        val engine = DmInterpreter(def)
        val messages = engine.validate(def.readDocumentJson(json)).also {
            assertEquals(emptyList<Any>(), it.unsupported, "every construct must be evaluable")
        }.messages.map {
            val type = if (it.type == MsgType.VALUE) "VALUE_ERROR" else "OMISSION_ERROR"
            "${it.code}|$type|" + formatPointer(it.errorPointer.fullName, it.errorPointer.repetitionIndexes)
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
        // The operand channel is read off the public report, the surface a consumer of it uses.
        val prepared = PreparedModel.loadExpandedJson(model)
        val operandErrors = prepared.createInterpreter().compute(prepared.documents.readDocument(json))
            .formalErrorsInOperands.map { "${it.code}@${formatPointer(it.errorPath.fullName, it.errorPath.repetitionIndexes)}" }
            .sorted()
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

    private fun kernelSignature(m: IMessage): String =
        "${m.errorCode}|${m.messageType.name}|" +
            formatPointer(m.errorFieldPointer.fullName(), m.errorFieldPointer.repetitionIndexes())
}
