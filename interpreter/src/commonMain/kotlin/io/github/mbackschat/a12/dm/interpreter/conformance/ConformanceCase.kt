package io.github.mbackschat.a12.dm.interpreter.conformance

import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.eval.toDocumentJson
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef

/**
 * The portable **conformance case** — a reference to a document model, one instance, one operation, and the
 * kernel-verified expected outcome, all as data (CONFORMANCE-CORPUS-SPEC). It is engine-neutral and kernel-free:
 * a runner resolves [modelRef] against the corpus root, loads that standalone DM-JSON file, reads [document]
 * through the public A12 Document JSON reader, injects [clock], runs [op], and compares its rendered outcome to [expected] / [computeExpected]. This is the
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
    /** The case's document as **A12's canonical Document JSON** — the shape the public ingress reads, so replay
     *  exercises that reader rather than an internal construction path (step 4 of the shape consolidation). */
    val document: String,
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

/**
 * The per-strategy expectation override for [strategy] (§11), in the op's key, or null when the anchor applies —
 * the pure selection every replay runner uses to enforce the ratified oracle (INTERPRETER-FINDINGS IF136): the
 * interpreter never consults it (it always asserts the Groovy-dynamic anchor), a kernel-static-Java replay applies
 * it, and the anchor is never overridden. Engine-neutral, so it lives beside the portable case format it selects
 * from and is shared by the JVM tri-engine runner (`:adapter` `CorpusReplay`) unchanged.
 */
fun ConformanceCase.overrideFor(strategy: String): List<String>? =
    divergences.firstOrNull { it.strategy == strategy }
        ?.let { if (op is Op.Compute) it.computeExpected else it.expected }

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

/**
 * The canonical A12 Document JSON for a capture-time placement scenario — the form a committed case stores.
 *
 * Capture legitimately works in [Placement] records (a generator builds a scenario before any case exists), and
 * this is the one place that scenario becomes the committed shape. It goes through the codec, so the stored bytes
 * are exactly what A12's own writer would produce (IF230).
 */
fun documentJsonOf(placements: List<Placement>, model: ModelDef): String =
    model.newDocument().also { placements.replayInto(it.asSink()) }.toDocumentJson(model)

/**
 * A committed case's document as placement records — the form the **kernel legs** of the corpus differential
 * still take (they build a kernel document from it).
 *
 * The interpreter leg does NOT use this: it reads [ConformanceCase.document] through the public A12 Document JSON
 * reader, which is the point of storing the canonical shape (IF230). This is a harness translation for the oracle
 * side only. Moving the kernel legs onto A12's own `IDocumentV2Serializer.deserializeV2` — so both sides share one
 * ingress regime — is step 5 of the shape consolidation, not a residual of this one.
 */
fun ConformanceCase.placementsForKernelLeg(model: ModelDef): List<Placement> =
    model.readDocumentJson(document).placementsSnapshot().map { cell ->
        Placement(
            kind = when {
                cell.isGroup -> PlacementKind.GROUP
                cell.raw == null -> PlacementKind.EMPTY
                else -> PlacementKind.FIELD
            },
            path = cell.path,
            reps = cell.reps,
            value = cell.raw,
        )
    }

