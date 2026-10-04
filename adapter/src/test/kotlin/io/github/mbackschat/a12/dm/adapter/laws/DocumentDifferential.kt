package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.Locale

/**
 * One engine's view of a Document: its validation signatures (`code|type|pointer`, sorted) and its computation
 * outcomes keyed by the target's field name (`VALUE|v`, `ERRORED|v` or `CLEARED`).
 */
internal data class DocumentObservation(val messages: List<String>, val outcomes: Map<String, String>)

/**
 * The three-engine observation of a Document given as A12 Document JSON: dynamic Groovy (normative), static Java and
 * the interpreter, each validated and computed once.
 *
 * Two conventions make the observations comparable. The kernel reports only computation targets whose stored state
 * changes, while the interpreter reports every target, so a caller seeds each target with a value no alternative
 * produces and passes those seeds: an interpreter VALUE equal to its seed is the unreported no-change. Outcomes are
 * keyed by field name, so targets are root-level fields with distinct names.
 */
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
                it.path().substringAfterLast('/') to when {
                    it.errored() -> "ERRORED|" + it.value().orElse("")
                    it.cleared() -> "CLEARED"
                    else -> "VALUE|" + it.value().orElse("")
                }
            }.toSortedMap(),
        )
    }

    fun interpreter(model: String, json: String, seeds: Map<String, String>): DocumentObservation {
        val def = ModelLoader.load(model)
        val engine = DmInterpreter(def)
        val messages = engine.validate(def.readDocumentJson(json)).also {
            assertEquals(emptyList<Any>(), it.unsupported, "every construct must be evaluable")
        }.messages.map {
            val type = if (it.type == MsgType.VALUE) "VALUE_ERROR" else "OMISSION_ERROR"
            "${it.code}|$type|" + formatPointer(it.errorPointer.fullName, it.errorPointer.repetitionIndexes)
        }.sorted()
        val outcomes = engine.compute(def.readDocumentJson(json)).filter { c ->
            !(c.outcome == ComputeOutcome.VALUE && seeds[c.path.substringAfterLast('/')] == c.value)
        }.associate {
            it.path.substringAfterLast('/') to when (it.outcome) {
                ComputeOutcome.ERRORED -> "ERRORED|" + (it.value ?: "")
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.VALUE -> "VALUE|" + (it.value ?: "")
            }
        }.toSortedMap()
        return DocumentObservation(messages, outcomes)
    }

    /** Asserts that static Java and the interpreter each observe every Document as dynamic Groovy does. */
    fun assertAgree(model: String, documents: Map<String, String>, seeds: Map<String, String>) {
        for ((name, json) in documents) {
            val dynamic = kernel(model, json, dynamic = true)
            assertEquals(dynamic, kernel(model, json, dynamic = false), "$name: static Java vs dynamic Groovy")
            assertEquals(dynamic, interpreter(model, json, seeds), "$name: interpreter vs dynamic Groovy")
        }
    }

    private fun kernelSignature(m: IMessage): String =
        "${m.errorCode}|${m.messageType.name}|" +
            formatPointer(m.errorFieldPointer.fullName(), m.errorFieldPointer.repetitionIndexes())
}
