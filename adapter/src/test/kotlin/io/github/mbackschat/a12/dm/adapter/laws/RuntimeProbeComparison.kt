package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.adapter.testing.KernelMessageAddressProjector
import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer
import io.github.mbackschat.a12.dm.interpreter.ComputationOutcome
import io.github.mbackschat.a12.dm.interpreter.ComputationResult
import io.github.mbackschat.a12.dm.interpreter.ValidationFinding
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.pointerComparisonKey
import java.nio.file.Files
import java.nio.file.Path

/**
 * One [KernelRuntimeProbe] request over inline Document JSON, and the signatures a differential compares A12's
 * answer and the interpreter's on: a full message on the LOSSLESS pointer key (a group pointer's terminal
 * coordinate is a row, which the house form drops, INTERPRETER-GAPS `IG145`), a partial message on the projected
 * address, and a computation's change on the address A12 reports.
 */
internal object RuntimeProbeComparison {
    private val MAPPER = ObjectMapper()

    /** Runs the probe in [dir] over [documents] by name, each optionally with its relevant pointers' JSON. */
    fun probe(dir: Path, label: String, model: String, observe: String, documents: Map<String, String>,
            relevant: Map<String, String> = emptyMap()): JsonNode {
        val modelFile = Files.writeString(dir.resolve("$label.model.json"), model)
        val rows = documents.entries.joinToString(",") { (name, json) ->
            """{"name":"$name","documentInline":$json""" +
                (relevant[name]?.let { ""","relevant":$it""" } ?: "") + "}"
        }
        val request = Files.writeString(dir.resolve("$label.request.json"),
            """{"model":"${modelFile.fileName}","observe":$observe,"rows":[$rows]}""")
        val out = dir.resolve("$label.artifact.json")
        KernelRuntimeProbe.main(arrayOf(request.toString(), out.toString()))
        return MAPPER.readTree(Files.readString(out))
    }

    /** A12's full message as the comparison key. */
    fun signature(m: RuntimeLaws.Msg): String =
        signature(m.code(), m.key(), m.type().name.removeSuffix("_ERROR"), m.referenced(), m.omissionResponsible())

    fun signature(finding: ValidationFinding): String {
        val render = { p: A12PartiallyKnownMultiPointer -> formatPointer(p.fullName, p.repetitionIndexes) }
        return signature(finding.code, pointerComparisonKey(finding.errorPath.fullName, finding.errorPath.repetitionIndexes),
            finding.type.name, finding.referenced.map(render), finding.fillToFix.map(render))
    }

    /** One partial message of the probe's first engine. */
    fun projected(message: JsonNode): String = projected(message.path("code").asText(), message.path("field").asText(),
        message.path("type").asText().removeSuffix("_ERROR"), message.path("referenced").map(JsonNode::asText))

    fun projected(finding: ValidationFinding): String = projected(finding.code,
        KernelMessageAddressProjector.address(finding.errorPath), finding.type.name,
        finding.referenced.map(KernelMessageAddressProjector::address))

    /** One computation change of the probe's first engine. */
    fun outcome(change: JsonNode): String =
        outcome(change.path("field").asText(), change.path("cleared").asBoolean(), change.path("value").textValue())

    /** The interpreter's source-relative action, as A12 reports what computation changes. */
    fun outcome(action: ComputationResult): String = outcome(KernelMessageAddressProjector.address(
        A12PartiallyKnownMultiPointer.of(action.pointer.path, action.pointer.coordinates)),
        action.outcome == ComputationOutcome.CLEARED, action.value)

    private fun outcome(address: String, cleared: Boolean, value: String?) =
        "$address|" + if (cleared) "CLEARED" else "=$value"

    private fun projected(code: String, address: String, type: String, referenced: List<String>) =
        "$code@$address|$type|ref=${referenced.sorted()}"

    private fun signature(code: String, key: String, type: String, referenced: List<String>, fillToFix: List<String>) =
        "$code@$key|$type|ref=${referenced.sorted()}|fix=${fillToFix.sorted()}"
}
