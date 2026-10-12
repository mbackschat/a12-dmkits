package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.conformance.InterpreterReplay
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.replayInto
import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * The **conformance corpus engine core** — runs canonical Document JSON cases and explicit V1 placement scenarios through each of the
 * three JVM engines and renders the outcome to the corpus signature form (CONFORMANCE-CORPUS-SPEC §6/§7). Shared
 * by both [CorpusCapture] (authoring: tri-verify + fill `expected`) and the JVM replay runner (verify a committed
 * case), so a single place owns the modern-`DocumentV2` + clock-pinned kernel path (§9).
 *
 * The three strategies:
 * - **[GROOVY_DYNAMIC]** — the production-normative parity anchor (S3), the kernel's dynamic Groovy service.
 * - **[JAVA_STATIC]** — the kernel's static-JAVA codegen (in-memory javac); a divergence from the anchor is a
 *   language-execution artifact recorded as a `divergences` entry, never the anchor.
 * - **[INTERPRETER]** — the kernel-free interpreter, the engine under test. Its replay + signature rendering is
 *   the multiplatform [InterpreterReplay] (shared with the JS replay, WI-6), so JVM capture == JVM replay == JS
 *   replay byte-for-byte; here it is only re-exposed as [interpret].
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

    // The static-JAVA engine is an in-memory javac compile per model (~hundreds of ms). RuntimeLaws owns the
    // shared bounded exact-model cache, so corpus replay and fuzzing neither recompile nor retain engines twice.
    private fun javaEngine(modelJson: String): RuntimeLaws.JavaKernelEngine =
        RuntimeLaws.sharedKernelJavaEngine(modelJson)

    /** The interpreter's rendered signatures for [op] — delegated to the multiplatform [InterpreterReplay] so the
     *  interpreter leg is identical to what the JS replay runs (a JS-vs-JVM split then shows as a corpus FAIL). */
    fun interpret(modelJson: String, placements: List<Placement>, op: Op, clock: String? = null): List<String> =
        InterpreterReplay.run(modelJson, placements, op, clock).signatures

    /** The Groovy-dynamic kernel's rendered signatures for [op] — the corpus anchor. [locale] defaults to the
     *  corpus-pinned [Locale.US]; the real-corpus sweep overrides it with a model-declared locale (the kernel
     *  rejects an undeclared locale; signatures are locale-independent). */
    fun groovyDynamic(modelJson: String, placements: List<Placement>, op: Op, clock: String? = null,
                      locale: Locale = Locale.US): List<String> {
        val doc = kernelDoc(placements)
        val cd = clockDate(clock)
        return when (op) {
            is Op.ValidateFull -> RuntimeLaws.messagesModern(modelJson, doc, cd, locale).map(::sigOf).sorted()
            is Op.ValidatePart -> RuntimeLaws.messagesPartModern(modelJson, doc, relevants(op), cd).map(::sigOf).sorted()
            is Op.Compute -> RuntimeLaws.computeModern(modelJson, doc, cd, locale).map(::sigOf).sorted()
        }
    }

    /** The static-JAVA kernel codegen's rendered signatures for [op] ([locale] as in [groovyDynamic]). */
    fun javaStatic(modelJson: String, placements: List<Placement>, op: Op, clock: String? = null,
                   locale: Locale = Locale.US): List<String> {
        val engine = javaEngine(modelJson)
        val doc = kernelDoc(placements)
        val cd = clockDate(clock)
        return when (op) {
            is Op.ValidateFull -> engine.messagesModern(doc, cd, locale).map(::sigOf).sorted()
            is Op.ValidatePart -> engine.messagesPartModern(doc, relevants(op), cd).map(::sigOf).sorted()
            is Op.Compute -> engine.computeModern(doc, cd, locale).map(::sigOf).sorted()
        }
    }

    /** Canonical capture uses the same public Document JSON reader as committed interpreter replay (§13). */
    fun interpret(modelJson: String, documentJson: String, op: Op, clock: String? = null): List<String> =
        InterpreterReplay.run(modelJson, documentJson, op, clock).signatures

    /** The canonical case reaches A12's own reader independently of the interpreter's reader (§13). */
    fun groovyDynamic(modelJson: String, documentJson: String, op: Op, clock: String? = null,
                      locale: Locale = Locale.US): List<String> {
        val engine = RuntimeLaws.sharedKernelEngine(modelJson)
        val doc = engine.instance(documentJson)
        val cd = clockDate(clock)
        return when (op) {
            is Op.ValidateFull -> engine.validationMessages(doc, cd, locale).map(::sigOf).sorted()
            is Op.ValidatePart -> engine.partialValidationMessages(doc, relevants(op), cd, locale).map(::sigOf).sorted()
            is Op.Compute -> engine.computation(doc, cd, locale).outcomes().map(::sigOf).sorted()
        }
    }

    /** Static-JAVA independently reads the canonical case through A12's serializer (§13). */
    fun javaStatic(modelJson: String, documentJson: String, op: Op, clock: String? = null,
                   locale: Locale = Locale.US): List<String> {
        val engine = javaEngine(modelJson)
        val doc = engine.instance(documentJson)
        val cd = clockDate(clock)
        return when (op) {
            is Op.ValidateFull -> engine.validationMessages(doc, cd, locale).map(::sigOf).sorted()
            is Op.ValidatePart -> engine.partialValidationMessages(doc, relevants(op), cd, locale).map(::sigOf).sorted()
            is Op.Compute -> engine.computation(doc, cd, locale).outcomes().map(::sigOf).sorted()
        }
    }

    // ---- kernel signature rendering (the interpreter's is InterpreterReplay's, so capture == replay) ----------

    private fun sigOf(m: RuntimeLaws.Msg): String = "${m.code()}|${m.type().name}|${m.field()}"

    private fun sigOf(c: RuntimeLaws.Computed): String = when {
        c.cleared() -> "${c.pointer()}|CLEARED"
        c.errored() -> "${c.pointer()}|ERRORED"
        else -> "${c.pointer()}|VALUE|${c.value().orElse("")}"
    }

    private fun sigOf(m: IMessage): String =
        "${m.errorCode}|${m.messageType.name}|${formatPointer(m.errorFieldPointer.fullName(), m.errorFieldPointer.repetitionIndexes())}"

    private fun sigOf(c: RuntimeLaws.ComputedInstance): String {
        val pointer = formatPointer(c.pointer().fullName(), c.pointer().repetitionIndexes())
        return when {
            c.cleared() -> "$pointer|CLEARED"
            c.errored() -> "$pointer|ERRORED"
            else -> "$pointer|VALUE|${c.value().orElse("")}"
        }
    }

    // ---- kernel instance building + clock --------------------------------------------------------------------

    private fun kernelDoc(placements: List<Placement>): RuntimeLaws.Doc {
        val builder = RuntimeLaws.doc()
        placements.replayInto(object : PlacementSink {
            override fun group(path: String, vararg reps: Int) { builder.group(path, *reps) }
            override fun field(path: String, value: String, vararg reps: Int) { builder.field(path, value, *reps) }
            override fun emptyField(path: String, vararg reps: Int) { builder.emptyField(path, *reps) }
        })
        return builder
    }

    /** The kernel clock ([Date]) — the local wall-clock instant, no zone (§9); null when absent. */
    internal fun clockDate(clock: String?): Date? =
        clock?.let { Date.from(LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant()) }

    private fun relevants(op: Op.ValidatePart): Set<RuntimeLaws.Relevant> =
        op.relevant.map { RuntimeLaws.Relevant.of(it.path, *it.reps.toIntArray()) }.toSet()
}
