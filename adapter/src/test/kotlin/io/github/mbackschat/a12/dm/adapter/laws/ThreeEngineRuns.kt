package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * One canonical A12 Document JSON string evaluated by dynamic Groovy, static Java and the interpreter, for the
 * 2026-10-02 runtime grids (SPEC-2026-10-01-04, SPEC-2026-10-02-01, SPEC-2026-10-02-04).
 *
 * Dynamic Groovy is normative; static Java must agree with it independently, and the interpreter's report-all
 * compute result is projected onto the kernel's change granularity before it is compared. Every comparison
 * names the document, so a divergence says which row moved.
 */
internal object ThreeEngineRuns {
    fun admitted(model: String): String {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model).filter { it.startsWith("ERROR") },
            "the kernel must admit the model, or an absent outcome is the gate's silence and not an evaluation")
        return model
    }

    /** Every reported compute outcome as `pointer|OUTCOME[|value]`, agreed by the three engines. */
    fun outcomes(model: String, documentJson: String): List<String> {
        val dynamic = RuntimeLaws.kernelEngine(model)
        val static = RuntimeLaws.kernelJavaEngine(model)
        val fromDynamic = dynamic.computationSignaturesSorted(dynamic.instance(documentJson))
        assertEquals(fromDynamic, static.computationSignaturesSorted(static.instance(documentJson)),
            "static-Java vs dynamic-Groovy — $documentJson")
        val def = ModelLoader.load(model)
        val doc = def.readDocumentJson(documentJson)
        val signatures = DmInterpreter(def).compute(doc).map {
            val pointer = formatPointer(it.path, it.indices)
            when (it.outcome) {
                ComputeOutcome.VALUE -> "$pointer|VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "$pointer|CLEARED"
                ComputeOutcome.ERRORED -> "$pointer|ERRORED|${it.value ?: ""}"
            }
        }
        assertEquals(fromDynamic, kernelComputeGranularity(signatures, doc, def).sorted(),
            "INTERPRETER vs dynamic-Groovy — $documentJson")
        return fromDynamic
    }

    /** Every code full validation raises, sorted, agreed by the three engines. */
    fun fired(model: String, documentJson: String): List<String> {
        val dynamic = RuntimeLaws.kernelEngine(model)
        val static = RuntimeLaws.kernelJavaEngine(model)
        val fromDynamic = dynamic.firedCodesSorted(dynamic.instance(documentJson))
        assertEquals(fromDynamic, static.firedCodesSorted(static.instance(documentJson)),
            "static-Java vs dynamic-Groovy — $documentJson")
        val def = ModelLoader.load(model)
        val outcome = DmInterpreter(def).validate(def.readDocumentJson(documentJson))
        assertEquals(emptyList<Any>(), outcome.unsupported, "declined, not evaluated — $documentJson")
        assertEquals(fromDynamic, outcome.messages.map { it.code }.sorted(), "INTERPRETER vs dynamic-Groovy — $documentJson")
        return fromDynamic
    }

    /** Every validation message as `code|TYPE|pointer`, agreed by the three engines — the [fired] view plus where
     *  each message attaches and its polarity. */
    fun signatures(model: String, documentJson: String): List<String> {
        val dynamic = RuntimeLaws.kernelEngine(model)
        val static = RuntimeLaws.kernelJavaEngine(model)
        val fromDynamic = dynamic.validationSignaturesSorted(dynamic.instance(documentJson))
        assertEquals(fromDynamic, static.validationSignaturesSorted(static.instance(documentJson)),
            "static-Java vs dynamic-Groovy — $documentJson")
        val def = ModelLoader.load(model)
        val outcome = DmInterpreter(def).validate(def.readDocumentJson(documentJson))
        assertEquals(emptyList<Any>(), outcome.unsupported, "declined, not evaluated — $documentJson")
        val interpreted = outcome.messages.map {
            "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}|${it.errorPath}"
        }.sorted()
        assertEquals(fromDynamic, interpreted, "INTERPRETER vs dynamic-Groovy — $documentJson")
        return fromDynamic
    }
}
