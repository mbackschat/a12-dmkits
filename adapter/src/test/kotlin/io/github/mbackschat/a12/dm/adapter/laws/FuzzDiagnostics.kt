package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.perf.PerfScenario
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.EffectiveRelevanceIndex
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import kotlin.random.Random

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
 * Kernel-only (`:adapter`): the interpreter side runs through [DmInterpreter.validateFull]; the kernel side runs
 * **BOTH codegen strategies** — the Groovy-dynamic service ([RuntimeLaws.messages], the production-normative
 * parity anchor, S3) AND the static-JAVA codegen ([RuntimeLaws.JavaKernelEngine], WI-1's third engine). So every
 * agreement check ([signaturesAgree], [computeOutcomesAgree], [partialSignaturesAgree], [diverges]) is
 * **three-way** — `interp == kernel-groovy-dynamic == kernel-java-static` at `code|type|pointer` granularity —
 * catching a codegen-only bug the runtime differential structurally cannot see. The divergence reporters name
 * *which* pair split: an `interp≠kernel` split is a real interpreter bug (→ WI-T handoff, §5); a
 * `kernel≠java-static` split is a language-execution artifact of the same generated logic (recorded, never
 * adopted as expected — S3). A debugging tool AND the gate the fuzz nets ride.
 *
 * The static-JAVA engine is **cached per model** ([javaEngine]) — the in-memory javac compile is once-per-model
 * (~hundreds of ms), reused across every seed of a scenario — so the tri-comparison stays affordable at fuzz
 * cadence.
 *
 * It is also the **compute** oracle (harness F — [computeOutcomesAgree]/[computeDivergence]) and the **partial**
 * oracle ([partialSignaturesAgree]): the same record/replay materializes one generated document into all three
 * engines and compares the outcome multiset — the gate the fuzz nets in `…adapter.perf` ride.
 */
object FuzzDiagnostics {

    /** Structural relevant-set shapes the partial fuzz must generate, independently of document values. */
    enum class PartialRelevantShape {
        EXPLICIT_WILDCARD,
        MIXED_COORDINATES,
        MULTIPLE_PATTERNS_AT_ONE_PATH,
    }

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

    /** Replay [places] into a fresh [RuntimeLaws.Doc] — the kernel-side document shared by BOTH codegen legs. */
    private fun kernelDoc(places: List<Place>): RuntimeLaws.Doc {
        val builder = RuntimeLaws.doc()
        replay(places, object : PlacementSink {
            override fun group(path: String, vararg reps: Int) { builder.group(path, *reps) }
            override fun field(path: String, value: String, vararg reps: Int) { builder.field(path, value, *reps) }
            override fun emptyField(path: String, vararg reps: Int) { builder.emptyField(path, *reps) }
        })
        return builder
    }

    // The static-JAVA codegen engine per model — WI-1's third engine. The shared exact-model cache is bounded,
    // so a long fuzz task cannot retain every generated class loader for the lifetime of its JVM fork.
    private fun javaEngine(kernelModel: String): RuntimeLaws.JavaKernelEngine =
        RuntimeLaws.sharedKernelJavaEngine(kernelModel)

    /** The `code|type|pointer` signature of a kernel [RuntimeLaws.Msg] — the full-message granularity. */
    private fun msgSig(m: RuntimeLaws.Msg): String = "${m.code()}|${m.type().name}|${m.field()}"

    /** A [Detail] over a kernel [RuntimeLaws.Msg] list — shared by the Groovy and java-static kernel legs. */
    private fun detailOf(msgs: List<RuntimeLaws.Msg>): Detail =
        Detail(msgs.map { it.code() }.sorted(), msgs.map { it.code() to it.field() }.toSet(), msgs.map(::msgSig).sorted())

    private fun interpDoc(model: ModelDef, places: List<Place>): Document {
        val doc = model.newDocument()   // ALL config maps threaded (dateTime/precision/enum/time too) — not a partial
        replay(places, doc.asSink())
        return doc
    }

    private fun interpDetail(model: ModelDef, places: List<Place>): Detail {
        val msgs = DmInterpreter(model).validateFull(interpDoc(model, places))
        return Detail(msgs.map { it.code }.sorted(), msgs.map { it.code to it.errorPath }.toSet(),
            msgs.map { "${it.code}|${polarity(it.type)}|${it.errorPath}" }.sorted())
    }

    /** The Groovy-dynamic kernel detail (the production-normative parity anchor, S3). */
    private fun kernelDetail(modelJson: String, places: List<Place>): Detail =
        detailOf(RuntimeLaws.messages(modelJson, kernelDoc(places)))

    /** The static-JAVA codegen kernel detail — WI-1's third engine (same generated logic, different language). */
    private fun javaDetail(kernelModel: String, places: List<Place>): Detail {
        val engine = javaEngine(kernelModel)
        return detailOf(engine.messages(engine.instance(kernelDoc(places))))
    }

    /** Whether [places] still diverge — three-way on the fired-code multiset (interp vs kernel-Groovy vs java-static). */
    private fun diverges(scenario: PerfScenario, places: List<Place>): Boolean {
        val i = interpDetail(scenario.interpModel, places).codes
        val k = kernelDetail(scenario.kernelModel, places).codes
        val j = javaDetail(scenario.kernelModel, places).codes
        return i != k || k != j
    }

    /**
     * Which engine pair split, as report parts (empty string = all three agree). An `interp≠kernel` split is a
     * real interpreter bug (→ WI-T handoff); a `kernel≠java-static` split is a codegen language-execution
     * artifact of the same generated logic (recorded, never adopted as expected — S3). "kernel" = the
     * Groovy-dynamic parity anchor.
     */
    private fun splitReport(interp: List<String>, kernel: List<String>, java: List<String>): String = buildString {
        if (interp != kernel)
            append("  [interp≠kernel] interp-only=${multisetMinus(interp, kernel)} kernel-only=${multisetMinus(kernel, interp)}")
        if (kernel != java)
            append("  [kernel≠java-static] java-only=${multisetMinus(java, kernel)} kernel-only=${multisetMinus(kernel, java)}")
    }

    /** A localized report for [seed]: the (code @ pointer) pairs present in only one engine (empty if no divergence). */
    fun localize(scenario: PerfScenario, seed: Int): String {
        val places = record(scenario, seed)
        val k = kernelDetail(scenario.kernelModel, places).codePointers
        val i = interpDetail(scenario.interpModel, places).codePointers
        val j = javaDetail(scenario.kernelModel, places).codePointers
        fun only(a: Set<Pair<String, String>>, b: Set<Pair<String, String>>) = (a - b).map { "${it.first}@${it.second}" }.sorted()
        return buildString {
            append("seed=$seed")
            if (i != k) append("  kernel-only=${only(k, i)}  interp-only=${only(i, k)}")
            if (k != j) append("  [kernel≠java-static] java-only=${only(j, k)}  kernel-only=${only(k, j)}")
            if (i == k && k == j) append(": no (code,pointer) divergence")
        }
    }

    /**
     * Whether [seed]'s **full-message signatures** (`code|type|pointer` multiset) agree across all THREE engines —
     * the harness-D gate, now the codegen tri-differential (interp == kernel-Groovy-dynamic == kernel-java-static).
     * Stronger than the codes-only fuzz: it also trips on a wrong **polarity** (VALUE↔OMISSION) or a right code on
     * the **wrong row**, which a code multiset hides — AND on a codegen-only divergence the runtime differential
     * can't see.
     */
    fun signaturesAgree(scenario: PerfScenario, seed: Int): Boolean {
        val places = record(scenario, seed)
        val i = interpDetail(scenario.interpModel, places).signatures
        val k = kernelDetail(scenario.kernelModel, places).signatures
        val j = javaDetail(scenario.kernelModel, places).signatures
        return i == k && k == j
    }

    /** A localized full-message report for [seed]: which engine pair's `code|type|pointer` signatures split. */
    fun signatureDivergence(scenario: PerfScenario, seed: Int): String {
        val places = record(scenario, seed)
        val i = interpDetail(scenario.interpModel, places).signatures
        val k = kernelDetail(scenario.kernelModel, places).signatures
        val j = javaDetail(scenario.kernelModel, places).signatures
        val report = splitReport(i, k, j)
        return if (report.isEmpty()) "seed=$seed: signatures agree" else "seed=$seed$report"
    }

    /** The kernel's fired-code set for [seed]'s document — the sanity oracle for the parallel-iteration fuzz
     *  (confirming the kernel really fires the joined rule, so the agreement check isn't vacuous). */
    fun kernelCodes(scenario: PerfScenario, seed: Int): Set<String> =
        kernelDetail(scenario.kernelModel, record(scenario, seed)).codes.toSet()

    /** The placements [scenario] generates for [seed], one per line — the document behind a divergence report.
     *  [signatureDivergence] names the split; this says what was fed in. */
    fun placementDump(scenario: PerfScenario, seed: Int): String =
        record(scenario, seed).joinToString("\n") { "  ${it.kind} ${it.path}${it.reps} ${it.value ?: ""}".trimEnd() }

    // ---- compute differential (harness F — computation cascade) ---------------------------------

    /**
     * Whether [seed]'s **compute outcomes** agree across all THREE engines (interp == kernel-Groovy-dynamic ==
     * kernel-java-static) — the harness-F gate. Each engine's
     * `compute()` is reduced to a sorted multiset of `path|VALUE|value` / `path|CLEARED` / `path|ERRORED`
     * signatures (the kernel's per-row repetition indices stripped, so a non-repeatable scope's paths line up
     * with the interpreter's index-free `targetPath`s). A divergence means a computed field resolved to a
     * different value/outcome — the class of bug the cascade fix addressed.
     */
    fun computeOutcomesAgree(scenario: PerfScenario, seed: Int): Boolean {
        val places = record(scenario, seed)
        val i = interpComputeSignatures(scenario.interpModel, places)
        val k = kernelComputeSignatures(scenario.kernelModel, places)
        val j = javaComputeSignatures(scenario.kernelModel, places)
        return i == k && k == j
    }

    /** A localized compute report for [seed]: which engine pair's outcome signatures split (null if all three agree). */
    fun computeDivergence(scenario: PerfScenario, seed: Int): String? {
        val places = record(scenario, seed)
        val i = interpComputeSignatures(scenario.interpModel, places)
        val k = kernelComputeSignatures(scenario.kernelModel, places)
        val j = javaComputeSignatures(scenario.kernelModel, places)
        val report = splitReport(i, k, j)
        return if (report.isEmpty()) null else "seed=$seed$report"
    }

    // Projected to the kernel result's three-channel change projection (change-only / input-filled clears;
    // the full result has five channels — KERNEL-FINDINGS §11) so the report-all interpreter compares
    // one-to-one with the kernel legs — see kernelComputeGranularity (SPEC §7).
    private fun interpComputeSignatures(model: ModelDef, places: List<Place>): List<String> =
        kernelComputeGranularity(
            DmInterpreter(model).compute(interpDoc(model, places)).map {
                val p = formatPointer(it.path, it.indices)   // PER-ROW pointer — a wrong value on the wrong row now splits
                when (it.outcome) {
                    ComputeOutcome.VALUE -> "$p|VALUE|${it.value}"
                    ComputeOutcome.CLEARED -> "$p|CLEARED"
                    ComputeOutcome.ERRORED -> "$p|ERRORED"
                }
            },
            places.map {
                Placement(
                    when (it.kind) {
                        Place.Kind.GROUP -> PlacementKind.GROUP
                        Place.Kind.FIELD -> PlacementKind.FIELD
                        Place.Kind.EMPTY -> PlacementKind.EMPTY
                    },
                    it.path, it.reps, it.value,
                )
            },
            model,
        ).sorted()

    /** The `pointer|VALUE|v` / `pointer|CLEARED` / `pointer|ERRORED` signature of a kernel [RuntimeLaws.Computed] —
     *  the PER-ROW pointer (shared `formatPointer` form, CONFORMANCE-CORPUS-SPEC §7), shared by the Groovy and
     *  java-static compute legs. Supersedes the historical index-strip, which could not tell the right value on
     *  the wrong row from the right value on the right row. */
    private fun computeSigOf(outcomes: List<RuntimeLaws.Computed>): List<String> =
        outcomes.map {
            val p = it.pointer()
            when {
                it.cleared() -> "$p|CLEARED"
                it.errored() -> "$p|ERRORED"
                else -> "$p|VALUE|${it.value().orElse("")}"
            }
        }.sorted()

    /** The Groovy-dynamic kernel compute signatures (the parity anchor). */
    private fun kernelComputeSignatures(modelJson: String, places: List<Place>): List<String> =
        computeSigOf(RuntimeLaws.compute(modelJson, kernelDoc(places)))

    /** The static-JAVA codegen compute signatures — WI-1's third engine. */
    private fun javaComputeSignatures(kernelModel: String, places: List<Place>): List<String> {
        val engine = javaEngine(kernelModel)
        return computeSigOf(engine.compute(engine.instance(kernelDoc(places))))
    }

    // ---- partial-validation differential (harness: random relevant subsets over a generated document) ----

    /**
     * Build matching relevant sets — the kernel's [RuntimeLaws.Relevant] form and the interpreter's
     * [RelevantEntity] form from [places]. Each placement is included with ~50% probability under a
     * [relevantSeed]-seeded RNG. One seed class keeps its coordinates concrete; the others replace deterministic
     * coordinate subsets with ALL. A relevant GROUP makes its descendants relevant on BOTH engines (kernel
     * `expandRelevantGroup` ⇄ interpreter ancestor-prefix match), so including groups exercises that expansion.
     * The two sets are the same instances in the two encodings.
     */
    private fun relevantSets(places: List<Place>, relevantSeed: Int): Pair<Set<RuntimeLaws.Relevant>, Set<RelevantEntity>> {
        val rng = Random(relevantSeed)
        val k = HashSet<RuntimeLaws.Relevant>()
        val i = HashSet<RelevantEntity>()
        for ((ordinal, p) in places.withIndex()) if (rng.nextBoolean()) {
            val indices = relevantIndices(p.reps, ordinal, relevantSeed)
            k += RuntimeLaws.Relevant.of(p.path, *indices.toIntArray())
            i += RelevantEntity(p.path, indices)
        }
        return k to i
    }

    /**
     * Concrete-only seeds retain the old subset net. The other two deterministic seed classes wildcard a
     * different subset of coordinate levels for each placement, which produces the mixed wildcard/concrete
     * patterns the starred gates branch on without making a failing case irreproducible.
     */
    private fun relevantIndices(indices: List<Int>, ordinal: Int, relevantSeed: Int): List<Int> =
        when (relevantSeed % RELEVANT_SHAPE_CLASSES) {
            0 -> indices
            1 -> indices.mapIndexed { level, index ->
                if ((ordinal + level + relevantSeed) % 2 == 0) RelevantEntity.ALL else index
            }
            else -> indices.mapIndexed { level, index ->
                if ((ordinal * 31 + level * 17 + relevantSeed) % 3 == 0) RelevantEntity.ALL else index
            }
        }

    /** The structural relevant-set shapes reached by the same deterministic seed matrix as the differential. */
    fun partialRelevantShapeCoverage(
        scenario: PerfScenario,
        docSeeds: IntRange,
        relevantSeeds: IntRange,
    ): Set<PartialRelevantShape> = buildSet {
        for (docSeed in docSeeds) for (relevantSeed in relevantSeeds) {
            val effectiveSeed = partialRelevantSeed(docSeed, relevantSeed)
            val entities = relevantSets(record(scenario, docSeed), effectiveSeed).second
            val patternsByPath = HashMap<String, MutableSet<List<Boolean>>>()
            for (entity in entities) {
                val pattern = entity.indices.map { it == RelevantEntity.ALL }
                if (pattern.any { it }) add(PartialRelevantShape.EXPLICIT_WILDCARD)
                if (pattern.any { it } && pattern.any { !it }) add(PartialRelevantShape.MIXED_COORDINATES)
                patternsByPath.getOrPut(entity.path) { HashSet() } += pattern
            }
            if (patternsByPath.values.any { it.size > 1 }) {
                add(PartialRelevantShape.MULTIPLE_PATTERNS_AT_ONE_PATH)
            }
        }
    }

    /** Single source of the partial fuzz's reproducible document-seed/relevant-seed pairing. */
    fun partialRelevantSeed(docSeed: Int, relevantSeed: Int): Int = docSeed * 131 + relevantSeed

    /** One placement of a generated document, in the shape a relevance meter classifies. [value] is null for a
     *  group row and for an empty field, so a meter that asks "do these two rows carry the SAME key" can tell a
     *  shared value from two absent ones. */
    data class Cell(val path: String, val reps: List<Int>, val isGroup: Boolean, val value: String?)

    /**
     * A generated document's cells paired with the **EFFECTIVE** relevance a relevant set induces — the query a
     * coverage meter must ask. Raw set membership is the wrong question: a cell is also relevant through an
     * ANCESTOR GROUP entity (kernel `expandRelevantGroup` ⇄ the interpreter's ancestor-prefix match) and through
     * an [RelevantEntity.ALL] wildcard at any coordinate level, both of which [relevantSets] generates. A meter
     * that tested `path in entities` would report shapes the runtime never sees — e.g. "payload relevant but its
     * index cell is not" for a row whose whole GROUP was selected, where in fact both are relevant.
     *
     * The view delegates to [EffectiveRelevanceIndex], the exact production query used by partial validation.
     * This prevents the relevance half of the coverage meter from drifting. The key-admission and collision
     * classifier remains an independent implementation, directly locked by `ParallelRelevanceShapeTest`, so the
     * meter does not hide production identity defects by asking production to classify itself.
     */
    class RelevantView internal constructor(
        val cells: List<Cell>,
        entities: Set<RelevantEntity>,
        globalFields: Set<String>,
    ) {
        private val relevance = EffectiveRelevanceIndex.build(globalFields, entities)

        fun isRelevant(path: String, reps: List<Int>): Boolean = relevance.isRelevant(path, reps)
    }

    /** [scenario]'s [docSeed] document and the effective relevance [relevantSeed]'s set induces over it. */
    fun relevantView(scenario: PerfScenario, docSeed: Int, relevantSeed: Int): RelevantView {
        val places = record(scenario, docSeed)
        return RelevantView(
            places.map { Cell(it.path, it.reps, it.kind == Place.Kind.GROUP, it.value) },
            relevantSets(places, relevantSeed).second,
            scenario.interpModel.globalFields,
        )
    }

    /** The kernel's fired-code set under PARTIAL validation — the vacuity oracle for a partial fuzz regime.
     *  [kernelCodes] answers the same question for full validation and cannot stand in for it: a partial run can
     *  be silent for its own reasons, so "the kernel ever fired here" has to be measured under the relevant set. */
    fun kernelPartialCodes(scenario: PerfScenario, seed: Int, relevantSeed: Int): Set<String> {
        val places = record(scenario, seed)
        val (kRel, _) = relevantSets(places, relevantSeed)
        return RuntimeLaws.messagesPart(scenario.kernelModel, kernelDoc(places), kRel).map { it.code() }.toSet()
    }

    private const val RELEVANT_SHAPE_CLASSES = 3

    private fun interpPartialSignatures(model: ModelDef, places: List<Place>, rel: Set<RelevantEntity>): List<String> =
        DmInterpreter(model).validatePart(interpDoc(model, places), rel).messages
            .map { "${it.code}|${polarity(it.type)}|${it.errorPath}" }.sorted()

    /** The Groovy-dynamic kernel partial-validation signatures (the parity anchor). */
    private fun kernelPartialSignatures(modelJson: String, places: List<Place>, rel: Set<RuntimeLaws.Relevant>): List<String> =
        RuntimeLaws.messagesPart(modelJson, kernelDoc(places), rel).map(::msgSig).sorted()

    /** The static-JAVA codegen partial-validation signatures — WI-1's third engine (static `validatePart`, the
     *  modern-path conversion; its support is pinned by `JavaStaticPartialBaselineDiffTest`). */
    private fun javaPartialSignatures(kernelModel: String, places: List<Place>, rel: Set<RuntimeLaws.Relevant>): List<String> =
        javaEngine(kernelModel).messagesPart(kernelDoc(places), rel).map(::msgSig).sorted()

    /** One materialization and one call to each engine for a partial-validation input. The kernel code set is
     *  derived from the same normative Groovy result used by [agrees], so a vacuity control does not pay for a
     *  second kernel validation of the identical document and relevant set. */
    class PartialObservation internal constructor(val agrees: Boolean, val kernelCodes: Set<String>)

    /** Observe one partial-validation input once across interpreter, dynamic Groovy, and static Java. */
    fun partialObservation(scenario: PerfScenario, seed: Int, relevantSeed: Int): PartialObservation {
        val places = record(scenario, seed)
        val (kRel, iRel) = relevantSets(places, relevantSeed)
        val i = interpPartialSignatures(scenario.interpModel, places, iRel)
        val k = kernelPartialSignatures(scenario.kernelModel, places, kRel)
        val j = javaPartialSignatures(scenario.kernelModel, places, kRel)
        return PartialObservation(
            agrees = i == k && k == j,
            kernelCodes = k.mapTo(mutableSetOf()) { it.substringBefore('|') },
        )
    }

    /**
     * Whether [seed]'s generated document, under a random relevant subset ([relevantSeed]), produces the SAME
     * fired-message signatures (`code|type|pointer`) from the interpreter's `validatePart`, the kernel's
     * Groovy-dynamic `validatePart`, AND the kernel's static-JAVA `validatePart` (WI-1's third engine) — the
     * partial-validation differential gate (rule-gating + 3VL + non-aggregate rules + formal/uniqueness errors).
     *
     * The former **IG20 boundary** — STARRED AGGREGATES over a repeatable group under *mixed* partial relevance
     * (some cells relevant via their group, some via a direct field pointer, some non-relevant) — is CLOSED by
     * IF42: the interpreter is kernel-exact there (the kernel's per-operator rule, e.g. `FirstFilled` needs only
     * the first filled relevant cell; `Sum` treats a cell whose GROUP row is non-relevant as Unknown even when the
     * field pointer is listed — subtler than the blanket IF35 gate), so the fuzz runs those codes unfiltered (the
     * former `ignoreCodes` filter is retired).
     */
    fun partialSignaturesAgree(scenario: PerfScenario, seed: Int, relevantSeed: Int): Boolean =
        partialObservation(scenario, seed, relevantSeed).agrees

    /** A localized partial report for ([seed], [relevantSeed]): which engine pair's signatures split. */
    fun partialDivergence(scenario: PerfScenario, seed: Int, relevantSeed: Int): String {
        val places = record(scenario, seed)
        val (kRel, iRel) = relevantSets(places, relevantSeed)
        val i = interpPartialSignatures(scenario.interpModel, places, iRel)
        val k = kernelPartialSignatures(scenario.kernelModel, places, kRel)
        val j = javaPartialSignatures(scenario.kernelModel, places, kRel)
        val report = splitReport(i, k, j)
        return if (report.isEmpty()) "seed=$seed/rel=$relevantSeed: signatures agree"
        else "seed=$seed/rel=$relevantSeed  relevant=${iRel.size}/${places.size}$report\n" +
            "  full-interpreter=${interpDetail(scenario.interpModel, places).signatures}\n" +
            "  full-kernel=${kernelDetail(scenario.kernelModel, places).signatures}\n" +
            iRel.sortedWith(compareBy<RelevantEntity> { it.path }.thenBy { it.indices.joinToString(",") })
                .joinToString("\n") { "  relevant ${it.path}${it.indices}" } + "\n" +
            places.joinToString("\n") { "  ${it.kind} ${it.path}${it.reps} ${it.value ?: ""}".trimEnd() }
    }

    /**
     * The relevant-set twin of [shrink]: greedily drop relevant ENTITIES that are not needed to keep a partial
     * divergence, leaving the minimal set to read. A partial divergence is usually about which entities are
     * *missing*, so shrinking the document alone does not localize it — and a relevant set that contains an
     * ancestor GROUP expands onto every descendant, which makes hand-built isolation attempts collapse into
     * "everything relevant" without saying so.
     */
    fun shrinkPartial(scenario: PerfScenario, seed: Int, relevantSeed: Int): String {
        val places = record(scenario, seed)
        fun diverges(entities: List<RelevantEntity>): Boolean {
            val k = entities.mapTo(HashSet()) { RuntimeLaws.Relevant.of(it.path, *it.indices.toIntArray()) }
            val i = interpPartialSignatures(scenario.interpModel, places, entities.toSet())
            return i != kernelPartialSignatures(scenario.kernelModel, places, k)
        }
        var kept = relevantSets(places, relevantSeed).second.sortedWith(
            compareBy<RelevantEntity> { it.path }.thenBy { it.indices.joinToString(",") })
        if (!diverges(kept)) return "seed=$seed/rel=$relevantSeed: no partial divergence to shrink"
        for (entity in kept.toList()) {
            val trial = kept.filterNot { it == entity }
            if (diverges(trial)) kept = trial
        }
        val kRel = kept.mapTo(HashSet()) { RuntimeLaws.Relevant.of(it.path, *it.indices.toIntArray()) }
        return "seed=$seed/rel=$relevantSeed shrunk to ${kept.size} relevant entities:\n" +
            kept.joinToString("\n") { "  relevant ${it.path}${it.indices}" } + "\n" +
            "  interp=${interpPartialSignatures(scenario.interpModel, places, kept.toSet())}\n" +
            "  kernel=${kernelPartialSignatures(scenario.kernelModel, places, kRel)}\n" +
            placementDump(scenario, seed)
    }

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
