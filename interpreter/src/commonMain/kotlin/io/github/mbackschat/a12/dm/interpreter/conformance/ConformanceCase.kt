package io.github.mbackschat.a12.dm.interpreter.conformance

import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink

/**
 * The portable **conformance case** — a reference to a document model, one instance, one operation, and the
 * kernel-verified expected outcome, all as data (CONFORMANCE-CORPUS-SPEC). It is engine-neutral and kernel-free:
 * a runner resolves [modelRef] against the corpus root, loads that standalone DM-JSON file, replays [placements],
 * injects [clock], runs [op], and compares its rendered outcome to [expected] / [computeExpected]. This is the
 * in-memory twin of a `corpus/cases/…` file; (de)serialization is [CaseJson].
 *
 * The model lives **outside** the case: [modelRef] is the corpus-root-relative path of a shared `models/<name>.json`
 * file (envelope-free DM-JSON — openable in the A12 modeling tools, consumable by `dmtool` directly). Many cases
 * reference one model. The runner reads the file and feeds its text straight to
 * [io.github.mbackschat.a12.dm.interpreter.load.ModelLoader].
 *
 * @property modelRef the corpus-root-relative path of the case's model file (e.g. `"models/order-basic.json"`).
 * @property clock the single wall-clock instant (`yyyy-MM-dd'T'HH:mm:ss`, no zone), present iff evaluation
 *   touches `Today`/`Now` (§9).
 * @property expected the sorted `code|type|pointer` signature multiset for a validate op (§6); null for compute.
 * @property computeExpected the sorted compute-outcome multiset for a compute op (§7); null for validate.
 */
data class ConformanceCase(
    val meta: CaseMeta,
    val modelRef: String,
    val placements: List<Placement>,
    val op: Op,
    val clock: String? = null,
    val expected: List<String>? = null,
    val computeExpected: List<String>? = null,
    val divergences: List<Divergence> = emptyList(),
)

/** The case metadata (§3). [source] provenance; [sections] the KERNEL-SEMANTICS taxonomy join key. */
data class CaseMeta(
    val id: String,
    val kernelVersion: String,
    val source: CaseSource,
    val sections: List<String>,
    val tags: List<String> = emptyList(),
    val seed: Int? = null,
    val scenario: String? = null,
    val notes: String? = null,
)

/** How a case was produced (§3 `meta.source`). [wire] is its JSON token. */
enum class CaseSource(val wire: String) {
    FUZZ_SEED("fuzz-seed"),
    CURATED("curated"),
    FACET_GENERATED("facet-generated");

    companion object {
        fun ofWire(wire: String): CaseSource = entries.firstOrNull { it.wire == wire }
            ?: throw IllegalArgumentException("unknown meta.source: $wire")
    }
}

/**
 * One placement — the serialized form of the [PlacementSink] protocol (§4): one group row or field cell,
 * addressed by [reps] (one 1-based repetition index per path segment, the addressed segment included). The
 * public, serializable promotion of the capture seam's former private record.
 */
data class Placement(
    val kind: PlacementKind,
    val path: String,
    val reps: List<Int>,
    val value: String? = null,
)

/** A placement's kind (§4): a group row, a valued field cell, or a present-but-valueless ([EMPTY]) cell. */
enum class PlacementKind { GROUP, FIELD, EMPTY }

/** The operation a case runs (§8). [kind] is its JSON token. */
sealed interface Op {
    val kind: String

    /** Full validation over the whole instance. */
    data object ValidateFull : Op {
        override val kind: String get() = "validateFull"
    }

    /** Partial validation over the [relevant] instance pointers (§8). */
    data class ValidatePart(val relevant: List<RelevantPointer>) : Op {
        override val kind: String get() = "validatePart"
    }

    /** Run the model's computations. */
    data object Compute : Op {
        override val kind: String get() = "compute"
    }
}

/** A concrete relevant instance pointer for [Op.ValidatePart] (§8): a [path] with one index per segment. */
data class RelevantPointer(val path: String, val reps: List<Int>)

/**
 * A per-strategy expectation override (§11): [strategy] observably differs from the groovy-dynamic anchor, so
 * its full replacement [expected] (or [computeExpected] — the op's key) is recorded. Never adopted as the
 * anchor; a runner replaying [strategy] asserts it.
 */
data class Divergence(
    val strategy: String,
    val expected: List<String>? = null,
    val computeExpected: List<String>? = null,
)

/** Replay these placements into [sink] in order (§4: parent row before its contents; replay is sequential). */
fun List<Placement>.replayInto(sink: PlacementSink) {
    for (p in this) when (p.kind) {
        PlacementKind.GROUP -> sink.group(p.path, *p.reps.toIntArray())
        PlacementKind.FIELD -> sink.field(
            p.path, p.value ?: throw IllegalArgumentException("FIELD placement ${p.path} carries no value"),
            *p.reps.toIntArray(),
        )
        PlacementKind.EMPTY -> sink.emptyField(p.path, *p.reps.toIntArray())
    }
}
