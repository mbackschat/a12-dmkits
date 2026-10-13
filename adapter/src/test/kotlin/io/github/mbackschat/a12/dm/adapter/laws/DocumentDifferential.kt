package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer
import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.RelevantCoordinate
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.conformance.kernelCanonicalComputeGranularity
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.pointerComparisonKey
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Lossless comparison keys retain every message coordinate (IG145); readable properties and [brief] preserve
 * the existing field-pointer pins. Terminal group coordinates remain visible in the diagnostic key suffix.
 */
internal data class MessageSignature(
    val code: String,
    val type: String,
    val pointerKey: String,
    val referencedKeys: List<String>,
    val fillToFixKeys: List<String>,
) : Comparable<MessageSignature> {
    val pointer: String get() = displayPointer(pointerKey)
    val referenced: List<String> get() = referencedKeys.map(::displayPointer).sorted()
    val fillToFix: List<String> get() = fillToFixKeys.map(::displayPointer).sorted()
    val brief: String get() = "$code|$type|$pointer"
    private val readable: String get() = "$brief|ref=$referenced|fill=$fillToFix"

    override fun compareTo(other: MessageSignature): Int {
        // SelectedAuthoredScopeDiffTest pins the readable ordering; IG145 requires a lossless tie-break.
        val displayOrder = readable.compareTo(other.readable)
        if (displayOrder != 0) return displayOrder
        val addressOrder = compareValuesBy(this, other, MessageSignature::code, MessageSignature::type,
            MessageSignature::pointerKey)
        if (addressOrder != 0) return addressOrder
        val referencesOrder = compareKeys(referencedKeys, other.referencedKeys)
        return if (referencesOrder != 0) referencesOrder else compareKeys(fillToFixKeys, other.fillToFixKeys)
    }

    override fun toString(): String = "$readable|keys=$pointerKey;ref=$referencedKeys;fill=$fillToFixKeys"

    companion object {
        /** Field expectation pins declare terminal 1; message-sentinel expectations use full keys (Pointer.kt / IG145). */
        fun fields(code: String, type: String, pointer: String, referenced: List<String>, fillToFix: List<String>) =
            MessageSignature(code, type, "$pointer[1]", referenced.map { "$it[1]" }.sorted(), fillToFix.map { "$it[1]" }.sorted())
    }
}

private fun displayPointer(key: String): String =
    if (key.endsWith(']')) key.substringBeforeLast('[') else key

private fun compareKeys(left: List<String>, right: List<String>): Int {
    for (index in 0 until minOf(left.size, right.size)) {
        val order = left[index].compareTo(right[index])
        if (order != 0) return order
    }
    return left.size.compareTo(right.size)
}

/**
 * One engine's view of a Document: its validation messages, its computation outcomes (`VALUE|v`, `ERRORED|v` or
 * `CLEARED`), and the computation's separate channel of formal errors in operands.
 */
internal data class DocumentObservation(
    val messages: List<MessageSignature>,
    val outcomes: Map<String, String>,
    val operandErrors: List<MessageSignature>,
    /**
     * `code address text` of every message an authored rule or computation raised. A signature leaves the text out,
     * and the renderer can then read the wrong row unseen (KERNEL-FINDINGS KF561). Kernel-owned prose is not
     * compared: generated rules carry other codes, and an evaluation fault's sentence is the interpreter's own
     * (INTERPRETER-FINDINGS IF3), so it compares as [AUTHORED_FAULT].
     */
    val authoredTexts: List<String>,
)

private const val AUTHORED_FAULT = "(the evaluation-fault text)"
private val EVALUATION_FAULT = Regex("^An error occurred while evaluati(on|ng) the data check: .*$", RegexOption.DOT_MATCHES_ALL)

/** The codes the model's own rules and computations raise; a computation's generated rule carries its name. */
private fun authoredCodes(model: String): Set<String> {
    val codes = mutableSetOf<String>()
    fun walk(node: com.fasterxml.jackson.databind.JsonNode) {
        when (node["type"]?.asText()) {
            "Rule" -> node["Rule"]?.get("errorCode")?.asText()?.let(codes::add)
            "Computation" -> node["name"]?.asText()?.let(codes::add)
        }
        node.forEach(::walk)
    }
    walk(com.fasterxml.jackson.databind.ObjectMapper().readTree(model))
    return codes
}

private fun authoredTexts(codes: Set<String>, messages: List<Triple<String, String, String>>): List<String> =
    messages.filter { it.first in codes }
        .map { (code, address, text) -> "$code $address ${if (EVALUATION_FAULT.matches(text)) AUTHORED_FAULT else text}" }.sorted()

/** The evaluation instant handed to each engine, for a model whose rules read `Today` or `Now`. */
internal data class DifferentialClock(val kernel: Date, val interpreter: EvalClock)

internal object DocumentDifferential {

    /** Exact localized text and address, without collapsing repeated findings by code. */
    fun assertMessageTexts(model: String, json: String, code: String, expected: List<Pair<String, String>>) {
        val dynamic = RuntimeLaws.kernelEngine(model).let { it.validationMessages(it.instance(json), null, Locale.US) }
        val generated = RuntimeLaws.kernelJavaEngine(model).let { it.validationMessages(it.instance(json), null, Locale.US) }
        fun texts(messages: List<IMessage>) = messages.filter { it.errorCode == code }
            .map { displayPointer(pointer(it.errorFieldPointer)) to it.errorText }.sortedBy { it.first }
        assertEquals(expected, texts(dynamic), "dynamic kernel text for $json")
        assertEquals(expected, texts(generated), "static kernel text for $json")
        val prepared = PreparedModel.loadExpandedJson(model)
        val report = prepared.createInterpreter().validateFull(prepared.documents.readDocument(json))
        assertEquals(emptyList<Any>(), report.unsupported)
        assertEquals(expected, report.findings.filter { it.code == code }
            .map { displayPointer(pointer(it.errorPath)) to it.text }.sortedBy { it.first }, "interpreter text for $json")
    }

    fun kernelMessages(model: String, json: String, dynamic: Boolean,
                       relevant: Set<RelevantEntity>? = null, clock: Date? = null,
                       sharedEngine: Boolean = false): List<MessageSignature> {
        val selection = relevant?.map { RuntimeLaws.Relevant.of(it.path, *it.indices.toIntArray()) }?.toSet()
        val messages = if (dynamic) {
            val engine = if (sharedEngine) RuntimeLaws.sharedKernelEngine(model) else RuntimeLaws.kernelEngine(model)
            if (selection == null) engine.validationMessages(engine.instance(json), clock, Locale.US)
            else engine.partialValidationMessages(engine.instance(json), selection, clock, Locale.US)
        } else {
            val engine = if (sharedEngine) RuntimeLaws.sharedKernelJavaEngine(model) else RuntimeLaws.kernelJavaEngine(model)
            if (selection == null) engine.validationMessages(engine.instance(json), clock, Locale.US)
            else engine.partialValidationMessages(engine.instance(json), selection, clock, Locale.US)
        }
        return messages.map(::kernelSignature).sorted()
    }

    fun interpreterMessages(model: String, json: String, relevant: Set<RelevantEntity>? = null,
                            clock: EvalClock? = null): List<MessageSignature> {
        if (clock != null) {
            val def = ModelLoader.load(model)
            val engine = DmInterpreter(def, clock)
            val document = def.readDocumentJson(json)
            val report = if (relevant == null) engine.validate(document) else engine.validatePart(document, relevant)
            assertEquals(emptyList<Any>(), report.unsupported, "every clocked construct must be evaluable")
            return report.messages.map {
                MessageSignature(it.code, typeName(it.type.name), pointer(it.errorPointer),
                    it.referencedPointers.map(::pointer).sorted(), it.fillToFixPointers.map(::pointer).sorted())
            }.sorted()
        }
        val prepared = PreparedModel.loadExpandedJson(model)
        val interpreter = prepared.createInterpreter()
        val document = prepared.documents.readDocument(json)
        val report = if (relevant == null) interpreter.validateFull(document) else interpreter.validatePart(document,
            relevant.map { entity -> prepared.model.relevantPointer(entity.path, entity.indices.map {
                if (it == 0) RelevantCoordinate.all()
                else RelevantCoordinate.exact(it)
            }) })
        assertEquals(emptyList<Any>(), report.unsupported, "every construct must be evaluable")
        return report.findings.map {
            MessageSignature(it.code, typeName(it.type.name), pointer(it.errorPath),
                it.referenced.map(::pointer).sorted(), it.fillToFix.map(::pointer).sorted())
        }.sorted()
    }

    fun kernel(model: String, json: String, dynamic: Boolean, clock: DifferentialClock? = null): DocumentObservation {
        val (messages, computation) = if (dynamic) {
            val engine = RuntimeLaws.kernelEngine(model)
            engine.validationMessages(engine.instance(json), clock?.kernel, Locale.US) to
                engine.computation(engine.instance(json), clock?.kernel, Locale.US)
        } else {
            val engine = RuntimeLaws.kernelJavaEngine(model)
            engine.validationMessages(engine.instance(json), clock?.kernel, Locale.US) to
                engine.computation(engine.instance(json), clock?.kernel, Locale.US)
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
            authoredTexts(authoredCodes(model), messages.map { Triple(it.errorCode, pointer(it.errorFieldPointer), it.errorText) }),
        )
    }

    /**
     * The interpreter's observation. The [clock] reaches validation and computation; the operand channel is read off
     * the public report, the surface a consumer of it uses, and that surface takes no clock.
     */
    fun interpreter(model: String, json: String, clock: DifferentialClock? = null): DocumentObservation {
        val def = ModelLoader.load(model)
        val engine = DmInterpreter(def, clock?.interpreter)
        val report = engine.validate(def.readDocumentJson(json)).also {
            assertEquals(emptyList<Any>(), it.unsupported, "every construct must be evaluable")
        }
        val messages = report.messages.map {
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
        // The Document JSON reader keeps typed source identity, so the projection is the canonical one: the
        // raw-placement profile drops an unchanged value only for a String target (CONFORMANCE-CORPUS-SPEC §7).
        val outcomes = kernelCanonicalComputeGranularity(signatures, def.readDocumentJson(json)).associate {
            outcomeKey(it.substringBefore('|')) to it.substringAfter('|')
        }.toSortedMap()
        val prepared = PreparedModel.loadExpandedJson(model)
        val operandErrors = prepared.createInterpreter().compute(prepared.documents.readDocument(json))
            .formalErrorsInOperands.map {
                MessageSignature(it.code, typeName(it.type.name), pointer(it.errorPath),
                    it.referenced.map(::pointer).sorted(), it.fillToFix.map(::pointer).sorted())
            }.sorted()
        return DocumentObservation(messages, outcomes, operandErrors,
            authoredTexts(authoredCodes(model), report.messages.map { Triple(it.code, pointer(it.errorPointer), it.text) }))
    }

    /** Asserts that static Java and the interpreter each observe every Document as dynamic Groovy does. */
    fun assertAgree(model: String, documents: Map<String, String>, clock: DifferentialClock? = null) {
        for ((name, json) in documents) {
            val dynamic = kernel(model, json, dynamic = true, clock)
            assertEquals(dynamic, kernel(model, json, dynamic = false, clock), "$name: static Java vs dynamic Groovy")
            assertEquals(dynamic, interpreter(model, json, clock), "$name: interpreter vs dynamic Groovy")
        }
    }

    /**
     * Runs [body] with the JVM default zone pinned to UTC. A model declaring its own zone needs this wherever the
     * kernel's static check would judge a constant against the default instead (`DateTimeConstantZoneSplitDiffTest`).
     */
    fun <T> underUtc(body: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            return body()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    /** A computation target's key: its pointer below the root group, repetitions other than the first spelled out. */
    private fun outcomeKey(pointer: String): String = pointer.trim('/').substringAfter('/').replace("[1]", "")

    private fun typeName(interpreterType: String) = if (interpreterType == "VALUE") "VALUE_ERROR" else "OMISSION_ERROR"

    internal fun pointer(p: PartiallyKnownDocumentMultiPointer): String = pointerComparisonKey(p.fullName(), p.repetitionIndexes())

    internal fun pointer(p: A12PartiallyKnownMultiPointer): String = pointerComparisonKey(p.fullName, p.repetitionIndexes)

    private fun kernelSignature(m: IMessage): MessageSignature = MessageSignature(m.errorCode, m.messageType.name,
        pointer(m.errorFieldPointer), m.referencedFieldsPointers.map(::pointer).sorted(),
        m.refOmissionErrorResponsiblePointers.map(::pointer).sorted())
}
