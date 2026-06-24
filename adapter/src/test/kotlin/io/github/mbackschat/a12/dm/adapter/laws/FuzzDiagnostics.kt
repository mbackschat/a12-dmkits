package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.perf.PerfScenario
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType

/**
 * **FuzzDiagnostics** (harness B) — when the differential fuzz net catches a diverging seed, this turns the
 * codes-only "kernel-only=[…] interp-only=[…]" into something actionable:
 *
 * - [localize] re-runs the seed and diffs the **(code, pointer)** pairs (both engines render the pointer the
 *   same `formatPointer` way), so the output names the exact **row** and code that diverged — no more manual
 *   "reproduce seed 24 by hand to find which row" (that pain motivated this).
 * - [shrink] **delta-debugs** the generated document: greedily drop repeatable-group rows that aren't needed to
 *   keep the divergence, leaving a MINIMAL failing instance to read.
 *
 * Kernel-only (`:adapter`): the kernel side runs through [RuntimeLaws.messages], the interpreter side through
 * [DmInterpreter.validateFull]. The divergence predicate is the SAME as the fuzz's — the sorted fired-code
 * **multiset** — so a seed [diverges] here iff the fuzz flagged it. A debugging tool, not a gate.
 *
 * It is also the **compute** oracle (harness F — [computeOutcomesAgree]/[computeDivergence]): the same
 * record/replay materializes one generated document into both `compute()` paths and compares the computed-field
 * outcome multiset, the gate the [io.github.mbackschat.a12.dm.adapter.perf.ComputationFuzz] cascade net rides.
 */
object FuzzDiagnostics {

    /** A recorded placement, so a seed's generated document can be replayed (and subsetted for [shrink]). */
    private data class Place(val kind: Kind, val path: String, val reps: List<Int>, val value: String?) {
        enum class Kind { GROUP, FIELD, EMPTY }
    }

    /**
     * A run's outcome: the sorted fired-code multiset (the fuzz's gate) + the (code, pointer) pairs
     * (localization) + the sorted **full-message signatures** `code|type|pointer` (harness D — the
     * full-message differential: a stronger gate that also catches a wrong polarity or a wrong row, not just a
     * missing/extra code).
     */
    private data class Detail(val codes: List<String>, val codePointers: Set<Pair<String, String>>,
        val signatures: List<String>)

    /** The normalized polarity token, identical on both engines ([MsgType] ⇄ kernel `IMessage.MessageType.name`). */
    private fun polarity(type: MsgType): String = if (type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"

    /** Capture the placements [scenario] generates for [seed] (so we can replay subsets). */
    private fun record(scenario: PerfScenario, seed: Int): List<Place> {
        val out = mutableListOf<Place>()
        scenario.instance(object : PlacementSink {
            override fun group(path: String, vararg reps: Int) { out += Place(Place.Kind.GROUP, path, reps.toList(), null) }
            override fun field(path: String, value: String, vararg reps: Int) { out += Place(Place.Kind.FIELD, path, reps.toList(), value) }
            override fun emptyField(path: String, vararg reps: Int) { out += Place(Place.Kind.EMPTY, path, reps.toList(), null) }
        }, 0, seed)
        return out
    }

    private fun replay(places: List<Place>, sink: PlacementSink) {
        for (p in places) when (p.kind) {
            Place.Kind.GROUP -> sink.group(p.path, *p.reps.toIntArray())
            Place.Kind.FIELD -> sink.field(p.path, p.value!!, *p.reps.toIntArray())
            Place.Kind.EMPTY -> sink.emptyField(p.path, *p.reps.toIntArray())
        }
    }

    private fun interpDoc(model: ModelDef, places: List<Place>): Document {
        val doc = Document(model.kinds, model.declaredReps, model.unsignedFields, model.rangeConfig, model.dateFormats, model.scales)
        replay(places, doc.asSink())
        return doc
    }

    private fun interpDetail(model: ModelDef, places: List<Place>): Detail {
        val msgs = DmInterpreter(model).validateFull(interpDoc(model, places))
        return Detail(msgs.map { it.code }.sorted(), msgs.map { it.code to it.errorPath }.toSet(),
            msgs.map { "${it.code}|${polarity(it.type)}|${it.errorPath}" }.sorted())
    }

    private fun kernelDetail(modelJson: String, places: List<Place>): Detail {
        val builder = RuntimeLaws.doc()
        replay(places, object : PlacementSink {
            override fun group(path: String, vararg reps: Int) { builder.group(path, *reps) }
            override fun field(path: String, value: String, vararg reps: Int) { builder.field(path, value, *reps) }
            override fun emptyField(path: String, vararg reps: Int) { builder.emptyField(path, *reps) }
        })
        val msgs = RuntimeLaws.messages(modelJson, builder)
        return Detail(msgs.map { it.code() }.sorted(), msgs.map { it.code() to it.field() }.toSet(),
            msgs.map { "${it.code()}|${it.type().name}|${it.field()}" }.sorted())
    }

    /** Whether [places] still diverge — the SAME predicate as the fuzz (sorted fired-code multiset differs). */
    private fun diverges(scenario: PerfScenario, places: List<Place>): Boolean =
        interpDetail(scenario.interpModel, places).codes != kernelDetail(scenario.kernelModel, places).codes

    /** A localized report for [seed]: the (code @ pointer) pairs present in only one engine (empty if no divergence). */
    fun localize(scenario: PerfScenario, seed: Int): String {
        val places = record(scenario, seed)
        val k = kernelDetail(scenario.kernelModel, places).codePointers
        val i = interpDetail(scenario.interpModel, places).codePointers
        val kOnly = (k - i).map { "${it.first}@${it.second}" }.sorted()
        val iOnly = (i - k).map { "${it.first}@${it.second}" }.sorted()
        return if (kOnly.isEmpty() && iOnly.isEmpty()) "seed=$seed: no (code,pointer) divergence"
        else "seed=$seed  kernel-only=$kOnly  interp-only=$iOnly"
    }

    /**
     * Whether [seed]'s **full-message signatures** (`code|type|pointer` multiset) agree across both engines —
     * the harness-D gate. Stronger than the codes-only fuzz: it also trips on a wrong **polarity**
     * (VALUE↔OMISSION) or a right code on the **wrong row**, which a code multiset hides.
     */
    fun signaturesAgree(scenario: PerfScenario, seed: Int): Boolean {
        val places = record(scenario, seed)
        return interpDetail(scenario.interpModel, places).signatures ==
            kernelDetail(scenario.kernelModel, places).signatures
    }

    /** A localized full-message report for [seed]: the `code|type|pointer` signatures present in only one engine. */
    fun signatureDivergence(scenario: PerfScenario, seed: Int): String {
        val places = record(scenario, seed)
        val k = kernelDetail(scenario.kernelModel, places).signatures
        val i = interpDetail(scenario.interpModel, places).signatures
        val kOnly = multisetMinus(k, i)
        val iOnly = multisetMinus(i, k)
        return if (kOnly.isEmpty() && iOnly.isEmpty()) "seed=$seed: signatures agree"
        else "seed=$seed  kernel-only=$kOnly  interp-only=$iOnly"
    }

    // ---- compute differential (harness F — computation cascade) ---------------------------------

    /**
     * Whether [seed]'s **compute outcomes** agree across both engines — the harness-F gate. Each engine's
     * `compute()` is reduced to a sorted multiset of `path|VALUE|value` / `path|CLEARED` / `path|ERRORED`
     * signatures (the kernel's per-row repetition indices stripped, so a non-repeatable scope's paths line up
     * with the interpreter's index-free `targetPath`s). A divergence means a computed field resolved to a
     * different value/outcome — the class of bug the cascade fix addressed.
     */
    fun computeOutcomesAgree(scenario: PerfScenario, seed: Int): Boolean {
        val places = record(scenario, seed)
        return interpComputeSignatures(scenario.interpModel, places) == kernelComputeSignatures(scenario.kernelModel, places)
    }

    /** A localized compute report for [seed]: the outcome signatures present in only one engine (null if agree). */
    fun computeDivergence(scenario: PerfScenario, seed: Int): String? {
        val places = record(scenario, seed)
        val k = kernelComputeSignatures(scenario.kernelModel, places)
        val i = interpComputeSignatures(scenario.interpModel, places)
        if (k == i) return null
        return "seed=$seed  kernel-only=${multisetMinus(k, i)}  interp-only=${multisetMinus(i, k)}"
    }

    private fun interpComputeSignatures(model: ModelDef, places: List<Place>): List<String> =
        DmInterpreter(model).compute(interpDoc(model, places)).map {
            when (it.outcome) {
                ComputeOutcome.VALUE -> "${it.path}|VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "${it.path}|CLEARED"
                ComputeOutcome.ERRORED -> "${it.path}|ERRORED"
            }
        }.sorted()

    private fun kernelComputeSignatures(modelJson: String, places: List<Place>): List<String> {
        val builder = RuntimeLaws.doc()
        replay(places, object : PlacementSink {
            override fun group(path: String, vararg reps: Int) { builder.group(path, *reps) }
            override fun field(path: String, value: String, vararg reps: Int) { builder.field(path, value, *reps) }
            override fun emptyField(path: String, vararg reps: Int) { builder.emptyField(path, *reps) }
        })
        return RuntimeLaws.compute(modelJson, builder).map {
            val p = it.path().replace(INDEX, "")
            when {
                it.cleared() -> "$p|CLEARED"
                it.errored() -> "$p|ERRORED"
                else -> "$p|VALUE|${it.value().orElse("")}"
            }
        }.sorted()
    }

    private val INDEX = Regex("\\[\\d+]")

    /** Multiset difference `a − b` (each element of [b] cancels at most one of [a]). */
    private fun multisetMinus(a: List<String>, b: List<String>): List<String> {
        val rem = b.toMutableList()
        return a.filter { !rem.remove(it) }
    }

    /**
     * Greedily drop repeatable-group rows not needed to keep the divergence, returning a readable dump of the
     * MINIMAL failing document. A "row" is a repeatable-group instance and every placement beneath it; removing
     * one that still leaves the seed diverging shrinks the case. Returns "(no divergence)" if [seed] agrees.
     */
    fun shrink(scenario: PerfScenario, seed: Int): String {
        var places = record(scenario, seed)
        if (!diverges(scenario, places)) return "seed=$seed: no divergence to shrink"
        val repeatable = scenario.interpModel.repeatable
        // Candidate rows: each repeatable-group instance, deepest first (drop leaves before their parents).
        val rows = places.filter { it.kind == Place.Kind.GROUP && it.path in repeatable }
            .map { it.path to it.reps }.distinct().sortedByDescending { it.second.size }
        for ((groupPath, rowReps) in rows) {
            val trial = places.filterNot { under(it, groupPath, rowReps) }
            if (trial.size < places.size && diverges(scenario, trial)) places = trial
        }
        return "seed=$seed shrunk to ${places.size} placements:\n" +
            places.joinToString("\n") { "  ${it.kind} ${it.path}${it.reps} ${it.value ?: ""}".trimEnd() }
    }

    /** Whether placement [p] lies under the repeatable row ([groupPath], [rowReps]) — same path-prefix + index-prefix. */
    private fun under(p: Place, groupPath: String, rowReps: List<Int>): Boolean =
        (p.path == groupPath || p.path.startsWith("$groupPath/")) &&
            p.reps.size >= rowReps.size && p.reps.take(rowReps.size) == rowReps
}
