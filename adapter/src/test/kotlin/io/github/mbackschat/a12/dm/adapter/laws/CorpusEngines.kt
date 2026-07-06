package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.replayInto
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.Computed
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * The **conformance corpus engine core** — runs a case's `(model, placements, op, clock?)` through each of the
 * three JVM engines and renders the outcome to the corpus signature form (CONFORMANCE-CORPUS-SPEC §6/§7). Shared
 * by both [CorpusCapture] (authoring: tri-verify + fill `expected`) and the JVM replay runner (verify a committed
 * case), so a single place owns the signature rendering and the modern-`DocumentV2` + clock-pinned path (§9).
 *
 * The three strategies:
 * - **[GROOVY_DYNAMIC]** — the production-normative parity anchor (S3), the kernel's dynamic Groovy service.
 * - **[JAVA_STATIC]** — the kernel's static-JAVA codegen (in-memory javac); a divergence from the anchor is a
 *   language-execution artifact recorded as a `divergences` entry, never the anchor.
 * - **[INTERPRETER]** — the kernel-free interpreter (loader + `DmInterpreter`), the engine under test.
 *
 * A validate op renders `code|type|pointer`; a compute op renders `pointer|VALUE|value` / `…|CLEARED` /
 * `…|ERRORED` — both sorted (UTF-16 order) into a multiset the runner compares for list equality.
 */
object CorpusEngines {

    const val GROOVY_DYNAMIC = "kernel-groovy-dynamic"
    const val JAVA_STATIC = "kernel-java-static"
    const val INTERPRETER = "interpreter"

    /** The kernel version the corpus is captured against — bump on a KERNEL-UPGRADE (drives `meta.kernelVersion`). */
    const val KERNEL_VERSION = "30.8.1"

    // The static-JAVA engine is an in-memory javac compile per model (~hundreds of ms); cache it per model JSON
    // so a corpus with many cases over one model compiles once.
    private val javaEngines = ConcurrentHashMap<String, RuntimeLaws.JavaKernelEngine>()

    private fun javaEngine(modelJson: String): RuntimeLaws.JavaKernelEngine =
        javaEngines.getOrPut(modelJson) { RuntimeLaws.kernelJavaEngine(modelJson) }

    /** The interpreter's rendered signatures for [op] over the case instance. */
    fun interpret(modelJson: String, placements: List<Placement>, op: Op, clock: String? = null): List<String> {
        val def = ModelLoader.load(modelJson)
        val doc = interpDoc(def, placements)
        val engine = DmInterpreter(def, evalClock(clock))
        return when (op) {
            is Op.ValidateFull -> engine.validate(doc).messages.map(::sigOf).sorted()
            is Op.ValidatePart -> engine.validatePart(doc, relevantEntities(op)).messages.map(::sigOf).sorted()
            is Op.Compute -> engine.compute(doc).map(::sigOf).sorted()
        }
    }

    /** The Groovy-dynamic kernel's rendered signatures for [op] — the corpus anchor. */
    fun groovyDynamic(modelJson: String, placements: List<Placement>, op: Op, clock: String? = null): List<String> {
        val doc = kernelDoc(placements)
        val cd = clockDate(clock)
        return when (op) {
            is Op.ValidateFull -> RuntimeLaws.messagesModern(modelJson, doc, cd).map(::sigOf).sorted()
            is Op.ValidatePart -> RuntimeLaws.messagesPartModern(modelJson, doc, relevants(op), cd).map(::sigOf).sorted()
            is Op.Compute -> RuntimeLaws.computeModern(modelJson, doc, cd).map(::sigOf).sorted()
        }
    }

    /** The static-JAVA kernel codegen's rendered signatures for [op]. */
    fun javaStatic(modelJson: String, placements: List<Placement>, op: Op, clock: String? = null): List<String> {
        val engine = javaEngine(modelJson)
        val doc = kernelDoc(placements)
        val cd = clockDate(clock)
        return when (op) {
            is Op.ValidateFull -> engine.messagesModern(doc, cd).map(::sigOf).sorted()
            is Op.ValidatePart -> engine.messagesPartModern(doc, relevants(op), cd).map(::sigOf).sorted()
            is Op.Compute -> engine.computeModern(doc, cd).map(::sigOf).sorted()
        }
    }

    // ---- signature rendering (one form per engine, so capture == replay) -------------------------

    private fun sigOf(m: RuntimeLaws.Msg): String = "${m.code()}|${m.type().name}|${m.field()}"

    private fun sigOf(m: Message): String = "${m.code}|${polarity(m.type)}|${m.errorPath}"

    private fun sigOf(c: RuntimeLaws.Computed): String = when {
        c.cleared() -> "${c.pointer()}|CLEARED"
        c.errored() -> "${c.pointer()}|ERRORED"
        else -> "${c.pointer()}|VALUE|${c.value().orElse("")}"
    }

    private fun sigOf(c: Computed): String {
        val p = formatPointer(c.path, c.indices)
        return when (c.outcome) {
            ComputeOutcome.VALUE -> "$p|VALUE|${c.value ?: ""}"
            ComputeOutcome.CLEARED -> "$p|CLEARED"
            ComputeOutcome.ERRORED -> "$p|ERRORED"
        }
    }

    private fun polarity(type: MsgType): String = if (type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"

    // ---- instance building + clock --------------------------------------------------------------

    private fun interpDoc(def: ModelDef, placements: List<Placement>): Document =
        def.newDocument().also { placements.replayInto(it.asSink()) }

    private fun kernelDoc(placements: List<Placement>): RuntimeLaws.Doc {
        val builder = RuntimeLaws.doc()
        placements.replayInto(object : PlacementSink {
            override fun group(path: String, vararg reps: Int) { builder.group(path, *reps) }
            override fun field(path: String, value: String, vararg reps: Int) { builder.field(path, value, *reps) }
            override fun emptyField(path: String, vararg reps: Int) { builder.emptyField(path, *reps) }
        })
        return builder
    }

    /** The interpreter clock from a case's instant (`Today` = date part, `Now` = the instant); null when absent. */
    private fun evalClock(clock: String?): EvalClock? =
        clock?.let { EvalClock(today = it.substringBefore('T'), now = it) }

    /** The kernel clock ([Date]) — the local wall-clock instant, no zone (§9); null when absent. */
    private fun clockDate(clock: String?): Date? =
        clock?.let { Date.from(LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant()) }

    private fun relevants(op: Op.ValidatePart): Set<RuntimeLaws.Relevant> =
        op.relevant.map { RuntimeLaws.Relevant.of(it.path, *it.reps.toIntArray()) }.toSet()

    private fun relevantEntities(op: Op.ValidatePart): Set<RelevantEntity> =
        op.relevant.map { RelevantEntity(it.path, it.reps) }.toSet()
}
