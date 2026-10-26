package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import java.nio.file.Path
import kotlin.system.exitProcess

/** The two calls a row is compared on: `validateFull` with computation, and `validatePart` over its relevant set. */
internal enum class Call(val wireName: String) { FULL("validateFull"), PARTIAL("validatePart") }

/** One call's verdict on one row of a probe request. */
internal sealed interface RowVerdict {
    val row: String

    /** Both engines observed the same; a count is `null` for a channel the call does not publish. */
    data class Agree(override val row: String, val call: Call, val messages: Int, val outcomes: Int?,
                     val operandErrors: Int?) : RowVerdict

    /** The engines differ; each line names its channel and the side that alone observed it (`K>` A12, `I>` us). */
    data class Differ(override val row: String, val call: Call, val lines: List<String>) : RowVerdict

    /** The interpreter declines a rule of the model, so there is no answer of ours to compare. */
    data class Unsupported(override val row: String, val rules: List<String>) : RowVerdict
}

/** Every row's verdicts in request order, and the observed channels this differential leaves to the probe. */
internal data class ProbeDifferentialReport(val notCompared: Set<KernelRuntimeProbe.Observation>,
                                            val rows: List<RowVerdict>) {
    val agrees: Boolean get() = rows.all { it is RowVerdict.Agree }

    fun render(): String = buildString {
        if (notCompared.isNotEmpty()) appendLine("not compared: ${notCompared.joinToString { it.wireName() }}")
        rows.forEach { verdict ->
            when (verdict) {
                is RowVerdict.Agree -> appendLine("ok ${verdict.row} ${verdict.call.wireName}: ${verdict.messages} " +
                    "messages" + (verdict.outcomes?.let { ", $it outcomes" } ?: "") +
                    (verdict.operandErrors?.let { ", $it operand errors" } ?: ""))
                is RowVerdict.Differ -> {
                    appendLine("DIFF ${verdict.row} ${verdict.call.wireName}")
                    verdict.lines.forEach { appendLine("  $it") }
                }
                is RowVerdict.Unsupported -> appendLine("UNSUPPORTED ${verdict.row}: ${verdict.rules}")
            }
        }
    }
}

/**
 * The interpreter against the dynamic Kernel over one `:adapter:kernelProbe` request: the comparison behind every
 * "reconciled" in an a12-kernel-lean letter, beside the probe re-run that reconciles their artifact with ours.
 *
 * The request is read by the probe's own parser and its model refused by the probe's own consistency gate, so the
 * two tools cannot read one request two ways. Each row is compared on every channel [DocumentDifferential]
 * observes: messages, computed outcomes and operand errors under `validateFull`, and messages under `validatePart`
 * where the row carries a relevant set. Dynamic Groovy is the side compared, being normative where the two kernel
 * strategies split (interpreter/CLAUDE.md, rule A).
 *
 * Two limits are refusals rather than silent gaps. A request binding custom conditions or custom field types is
 * refused, because [DocumentDifferential] binds neither on the kernel side. An observed channel it does not compare
 * is named in the report. Messages are compared in `en_US`, whatever the request's locale.
 */
internal object ProbeRequestDifferential {

    private val COMPARED = setOf(KernelRuntimeProbe.Observation.VALIDATE_FULL,
        KernelRuntimeProbe.Observation.COMPUTE, KernelRuntimeProbe.Observation.VALIDATE_PART)

    fun compare(requestFile: Path): ProbeDifferentialReport {
        val request = KernelRuntimeProbe.parse(requestFile)
        val bindings = request.bindings()
        val bound = listOfNotNull("customConditions".takeIf { bindings.customConditions().isNotEmpty() },
            "customFieldTypes".takeIf { bindings.customFieldTypes().isNotEmpty() })
        require(bound.isEmpty()) {
            "the request binds $bound, which this differential cannot bind on the kernel side; " +
                "compare the interpreter with the artifact of `:adapter:kernelProbe` instead"
        }
        KernelRuntimeProbe.refuseInconsistentModel(request)
        val model = request.modelJson()
        val def = ModelLoader.load(model)
        val clock = request.clock().map { DifferentialClock(it, EvalClock.at(it.time, def.timeZone)) }.orElse(null)
        val rows = request.rows().flatMap { row ->
            val unsupported = DmInterpreter(def, clock?.interpreter).validate(def.readDocumentJson(row.documentJson()))
                .unsupported.map { it.rulePath }
            if (unsupported.isNotEmpty()) return@flatMap listOf(RowVerdict.Unsupported(row.name(), unsupported))
            val full = verdict(row.name(), DocumentDifferential.kernel(model, row.documentJson(), dynamic = true, clock),
                DocumentDifferential.interpreter(model, row.documentJson(), clock))
            val partial = row.relevant().map { selection ->
                val relevant = selection.map { RelevantEntity(it.path(), it.reps()) }.toSet()
                partialVerdict(row.name(),
                    DocumentDifferential.kernelMessages(model, row.documentJson(), true, relevant, clock?.kernel),
                    DocumentDifferential.interpreterMessages(model, row.documentJson(), relevant, clock?.interpreter))
            }
            listOfNotNull(full, partial.orElse(null))
        }
        return ProbeDifferentialReport(request.observe() - COMPARED, rows)
    }

    /** The `validateFull` verdict, from the two engines' observations of one Document. */
    fun verdict(row: String, kernel: DocumentObservation, interpreter: DocumentObservation): RowVerdict {
        val lines = channel("messages", kernel.messages, interpreter.messages) +
            channel("outcomes", kernel.outcomes.map { (k, v) -> "$k|$v" }, interpreter.outcomes.map { (k, v) -> "$k|$v" }) +
            channel("operand errors", kernel.operandErrors, interpreter.operandErrors) +
            channel("authored texts", kernel.authoredTexts, interpreter.authoredTexts)
        return if (lines.isEmpty()) {
            RowVerdict.Agree(row, Call.FULL, kernel.messages.size, kernel.outcomes.size, kernel.operandErrors.size)
        } else RowVerdict.Differ(row, Call.FULL, lines)
    }

    private fun partialVerdict(row: String, kernel: List<MessageSignature>, interpreter: List<MessageSignature>) =
        channel("messages", kernel, interpreter).let { lines ->
            if (lines.isEmpty()) RowVerdict.Agree(row, Call.PARTIAL, kernel.size, null, null)
            else RowVerdict.Differ(row, Call.PARTIAL, lines)
        }

    private fun channel(name: String, kernel: List<Any>, interpreter: List<Any>): List<String> =
        (kernel - interpreter.toSet()).map { "$name K> $it" } + (interpreter - kernel.toSet()).map { "$name I> $it" }

    /** `:adapter:probeDifferential`'s entry point: prints the report and fails the task on any disagreement. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && args[0].isNotEmpty()) { USAGE }
        val report = compare(Path.of(args[0]))
        print(report.render())
        if (!report.agrees) exitProcess(1)
    }

    const val USAGE = "usage: ./build.sh :adapter:probeDifferential -Prequest=<request.json>"
}
