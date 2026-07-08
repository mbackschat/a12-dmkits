package io.github.mbackschat.a12.dm.interpreter.conformance

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.Computed
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule

/**
 * The **one** interpreter-leg replay of a [ConformanceCase] (CONFORMANCE-CORPUS-SPEC §12), kernel-free and
 * multiplatform. The runner resolves [ConformanceCase.modelRef] to the model's DM-JSON text and passes it in; this
 * loads it with the [ModelLoader], replays [ConformanceCase.placements], injects [ConformanceCase.clock], runs
 * [ConformanceCase.op], and renders the outcome to corpus signatures (§6/§7). It is shared by:
 *
 * - the JVM three-engine runner (`:adapter` `CorpusEngines`/`CorpusReplay`) — its interpreter leg IS this, and
 * - the multiplatform replay suite (`CorpusReplayCommonTest`) that runs on **both** JVM and JS.
 *
 * Sharing the exact rendering is the point: JVM and JS produce byte-identical signatures for the same case, so a
 * JS-vs-JVM interpreter split (a decimal-shim / regex / format platform difference) surfaces as a corpus FAIL —
 * the bug class WI-6 exists to catch. The interpreter always asserts the **top-level** anchor
 * ([ConformanceCase.expected] / [ConformanceCase.computeExpected]); `divergences` are kernel-strategy records and
 * never apply to it.
 */
object InterpreterReplay {

    /** A per-case verdict (§12): [PASS] (rendered multiset equals the anchor) · [FAIL] (any difference) ·
     *  [SKIP] (a construct the interpreter declaredly cannot evaluate — never silent, never a pass). */
    enum class Verdict { PASS, FAIL, SKIP }

    /** A [Verdict] with a human [detail] (the diff for a FAIL, the unsupported reasons for a SKIP). */
    data class Result(val verdict: Verdict, val detail: String = "")

    /** The interpreter's rendered [signatures] plus any [unsupported] constructs it declaredly cannot evaluate
     *  (their reasons) — a non-empty [unsupported] makes a runner report [Verdict.SKIP]. */
    data class Outcome(val signatures: List<String>, val unsupported: List<String>)

    /** Replay [case] (its model already resolved to [modelJson]) on the interpreter and render its [Outcome]. */
    fun run(case: ConformanceCase, modelJson: String): Outcome = run(modelJson, case.placements, case.op, case.clock)

    /** Replay a raw scenario (used by capture, before a [ConformanceCase] exists) and render its [Outcome]. */
    fun run(modelJson: String, placements: List<Placement>, op: Op, clock: String? = null): Outcome {
        val def = ModelLoader.load(modelJson)
        val doc = def.newDocument().also { placements.replayInto(it.asSink()) }
        val engine = DmInterpreter(def, evalClock(clock))
        return when (op) {
            is Op.ValidateFull -> engine.validate(doc)
                .let { Outcome(it.messages.map(::sigOf).sorted(), reasons(it.unsupported)) }
            is Op.ValidatePart -> engine.validatePart(doc, relevantEntities(op))
                .let { Outcome(it.messages.map(::sigOf).sorted(), reasons(it.unsupported)) }
            is Op.Compute -> Outcome(engine.compute(doc).map(::sigOf).sorted(), emptyList())
        }
    }

    /** The interpreter's PASS/FAIL/SKIP [Result] for [case] (model resolved to [modelJson]) against its anchor (§12).
     *  A compute outcome is projected to the kernel's reporting granularity first ([kernelComputeGranularity]) —
     *  the anchor is kernel-captured, so a report-all engine must drop its non-changes before comparing (§7). */
    fun replay(case: ConformanceCase, modelJson: String): Result {
        val outcome = run(case, modelJson)
        if (outcome.unsupported.isNotEmpty()) return Result(Verdict.SKIP, outcome.unsupported.joinToString("; "))
        val actual =
            if (case.op is Op.Compute) kernelComputeGranularity(outcome.signatures, case.placements, ModelLoader.load(modelJson))
            else outcome.signatures
        return compare(actual, anchorOf(case))
    }

    /** The anchor multiset the interpreter is compared to — `computeExpected` for a compute op, else `expected`. */
    fun anchorOf(case: ConformanceCase): List<String> =
        (if (case.op is Op.Compute) case.computeExpected else case.expected)
            ?: throw IllegalArgumentException("case '${case.meta.id}' has neither expected nor computeExpected")

    /** PASS on multiset equality, else FAIL naming the actual-only / expected-only signatures. */
    fun compare(actual: List<String>, expected: List<String>): Result =
        if (actual == expected) Result(Verdict.PASS)
        else Result(Verdict.FAIL, "actual-only=${actual - expected.toSet()} expected-only=${expected - actual.toSet()}")

    // ---- signature rendering (§6/§7) — the ONE form, so JVM capture == JVM replay == JS replay --------------

    private fun sigOf(m: Message): String = "${m.code}|${polarity(m.type)}|${m.errorPath}"

    private fun sigOf(c: Computed): String {
        val p = formatPointer(c.path, c.indices)
        return when (c.outcome) {
            ComputeOutcome.VALUE -> "$p|VALUE|${c.value ?: ""}"
            ComputeOutcome.CLEARED -> "$p|CLEARED"
            ComputeOutcome.ERRORED -> "$p|ERRORED"
        }
    }

    private fun polarity(type: MsgType): String = if (type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"

    private fun reasons(unsupported: List<UnsupportedRule>): List<String> =
        unsupported.map { "${it.rulePath}: ${it.reason}" }

    /** The interpreter clock from a case's instant (`Today` = date part, `Now` = the instant); null when absent. */
    private fun evalClock(clock: String?): EvalClock? =
        clock?.let { EvalClock(today = it.substringBefore('T'), now = it) }

    private fun relevantEntities(op: Op.ValidatePart): Set<RelevantEntity> =
        op.relevant.mapTo(HashSet()) { RelevantEntity(it.path, it.reps) }
}
