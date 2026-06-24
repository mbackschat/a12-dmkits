package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.perf.PerfScenario
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef

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
 */
object FuzzDiagnostics {

    /** A recorded placement, so a seed's generated document can be replayed (and subsetted for [shrink]). */
    private data class Place(val kind: Kind, val path: String, val reps: List<Int>, val value: String?) {
        enum class Kind { GROUP, FIELD, EMPTY }
    }

    /** A run's outcome: the sorted fired-code multiset (the fuzz's gate) + the (code, pointer) pairs (localization). */
    private data class Detail(val codes: List<String>, val codePointers: Set<Pair<String, String>>)

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

    private fun interpDetail(model: ModelDef, places: List<Place>): Detail {
        val doc = Document(model.kinds, model.declaredReps, model.unsignedFields, model.rangeConfig, model.dateFormats, model.scales)
        replay(places, doc.asSink())
        val msgs = DmInterpreter(model).validateFull(doc)
        return Detail(msgs.map { it.code }.sorted(), msgs.map { it.code to it.errorPath }.toSet())
    }

    private fun kernelDetail(modelJson: String, places: List<Place>): Detail {
        val builder = RuntimeLaws.doc()
        replay(places, object : PlacementSink {
            override fun group(path: String, vararg reps: Int) { builder.group(path, *reps) }
            override fun field(path: String, value: String, vararg reps: Int) { builder.field(path, value, *reps) }
            override fun emptyField(path: String, vararg reps: Int) { builder.emptyField(path, *reps) }
        })
        val msgs = RuntimeLaws.messages(modelJson, builder)
        return Detail(msgs.map { it.code() }.sorted(), msgs.map { it.code() to it.field() }.toSet())
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
