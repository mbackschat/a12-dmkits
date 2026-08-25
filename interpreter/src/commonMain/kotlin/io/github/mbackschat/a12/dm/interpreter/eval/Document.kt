package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer
import io.github.mbackschat.a12.dm.interpreter.CacheObserver
import io.github.mbackschat.a12.dm.interpreter.CacheOwnerKind
import io.github.mbackschat.a12.dm.interpreter.messagePointerHouseForm
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.IndexIdentityCandidate
import io.github.mbackschat.a12.dm.interpreter.ModelEntityKind
import io.github.mbackschat.a12.dm.interpreter.indexKeyOf
import io.github.mbackschat.a12.dm.interpreter.resolveIndexIdentity
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.ast.emptyValue
import io.github.mbackschat.a12.dm.interpreter.ast.typedValue
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionData
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.FieldConstraints
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.RangeYearInterpretation
import io.github.mbackschat.a12.dm.interpreter.model.ValuedField

/**
 * The repetition index a parallel-iteration pointer carries for a group that has **no row** for the current
 * index value (the outer-join "not specified" side) — the kernel's `IIdentifier.UNKNOWN`, a fixed sentinel, so
 * the rendered pointer (`…/Demand[-5]/Headcount`) matches the kernel bit-for-bit (KERNEL-FINDINGS §9).
 */
internal const val UNKNOWN_REP = -5

/**
 * A general document instance the interpreter walks — NO kernel types. Addressed exactly like the
 * kernel: every placement carries **one 1-based repetition index per path part** (group AND field), so
 * `/Order/Items/Count` row 2 is `field("/Order/Items/Count", "80", 1, 2, 1)`. This subsumes the flat
 * (single-row) and one-level-repetition shapes the spike's [SingleRowDoc]/[RepDoc] handle; it produces
 * one [EvalContext] per instantiated row of a rule's iteration scope via [contextsFor].
 *
 * Field reference resolution within a row context follows the **common-prefix rule**: a referenced
 * path P shares a prefix with the iteration group G, takes the row's index for each shared group part,
 * and index 1 below the divergence — so an outer field, a same-row field, and a nested non-repeatable
 * field all resolve correctly. A starred aggregate collects the field across every row of its deepest
 * repeatable group ancestor.
 *
 * Building any placement materializes the same group tree the kernel's V1-to-V2 conversion evaluates: ancestors
 * are present, and addressing repetition `n` creates empty predecessor rows `1..n-1` at that level. For nested
 * repetitions, a child gap is closed only below the addressed parent row, never below synthetic predecessors.
 *
 * **Performance (INTERPRETER-PERFORMANCE.md).** Placement lookups are **indexed**, not linear scans: an
 * immutable [Index] — built once on first query, invalidated on mutation — maps `(path, reps) → value` and
 * `groupPath → rows` so `valueAt`/`rowsOf`/`star` are O(1)/O(rows), not O(P). `contextsFor` computes each
 * row's `evaluated` flag in ONE amortized pass over the valued fields (not an O(P) scan per row), and group
 * auto-instantiation dedupes via a set (not an O(P²) build scan). Net: per-row evaluation is ~O(rows · refs),
 * not O(rows · placements).
 */
/**
 * An empty [Document] configured from this [ModelDef] — every kind / reps / format / scale / enum / precision /
 * base-year / constraint / time-zone map the evaluator needs, gathered into ONE immutable [DocumentConfig] so a
 * consumer (corpus replay, a differential, a demo) never hand-lists config maps. Placements are then added via
 * the builder methods or [io.github.mbackschat.a12.dm.interpreter.gen.asSink]. This is the blessed construction
 * path: it threads EVERY slot, so a document over a datetime / partial-date / enum / time / constrained / non-UTC
 * field is configured correctly (the positional-drop class IG75/IF131 fixed).
 */
fun ModelDef.newDocument(): Document = Document(DocumentConfig(
    kinds = kinds,
    declaredReps = declaredReps,
    unsignedFields = unsignedFields,
    rangeConfig = rangeConfig,
    rangeYearInterpretations = rangeYearInterpretations,
    dateFormats = dateFormats,
    scales = scales,
    dateTimeFormats = dateTimeFormats,
    datePrecisions = datePrecisions,
    enumValues = enumValues,
    enumCategories = enumCategories,
    timeFormats = timeFormats,
    baseYear = baseYear,
    fieldConstraints = fieldConstraints,
    timeZone = timeZone,
    repeatableGroups = repeatable,
    effectiveGroupRepetitionMaximums = effectiveGroupRepetitionMaximums,
))

class Document(internal val config: DocumentConfig) {
    /** Kinds-only convenience — a document with no configuration beyond field kinds (the common flat-model
     *  test / ad-hoc case). Any richer configuration uses [DocumentConfig] with named arguments (conspicuous) or
     *  [ModelDef.newDocument] (the full, model-derived config). */
    constructor(kinds: Map<String, Kind>) : this(DocumentConfig(kinds))

    // The configuration, destructured for the class body — all sourced from the single immutable [config], so
    // [emptyCopy] can preserve every slot wholesale (`Document(config)`); the IG75/IF131 fix.
    private val kinds = config.kinds
    private val declaredReps = config.declaredReps
    private val unsignedFields = config.unsignedFields
    private val rangeConfig = config.rangeConfig
    private val rangeYearInterpretations = config.rangeYearInterpretations
    private val dateFormats = config.dateFormats
    private val scales = config.scales
    private val dateTimeFormats = config.dateTimeFormats
    private val datePrecisions = config.datePrecisions
    private val enumValues = config.enumValues
    private val enumCategories = config.enumCategories
    private val timeFormats = config.timeFormats
    private val baseYear = config.baseYear
    private val fieldConstraints = config.fieldConstraints
    private val timeZone = config.timeZone
    private val repeatableGroups = config.repeatableGroups
    private val effectiveGroupRepetitionMaximums = config.effectiveGroupRepetitionMaximums
    /** [raw] is the STORED text and [value] the EVALUATION text. They part for two independent reasons: the
     *  eval-side CRLF→LF normalization (IF124), and A12's number conversion, which renders one stored decimal
     *  twice (IF231 — set by [renderedField] at the Document-JSON ingress). [raw] is what [cellStateAt] and the
     *  encoder report; it defaults to [value] and is preserved by the normalizing `copy`. */
    private data class Placement(
        val path: String,
        val reps: List<Int>,
        val value: String?,
        val isGroup: Boolean,
        val raw: String? = value,
        val numberIdentity: DecimalObjectIdentity? = null,
    )

    /** One declared-tail decision for a starred field in a bound row scope (IF194). */
    private data class StarOmissionKey(val path: String, val bindDepth: Int, val scope: List<Int>)

    private val placements = mutableListOf<Placement>()
    /** Group `(path, reps)` already instantiated — O(1) dedup for [autoGroup] (avoids an O(P²) build scan). */
    private val instantiatedGroups = HashSet<Pair<String, List<Int>>>()
    private val groupTreeMaterializer = DenseGroupTreeMaterializer(::autoGroup)

    /** Every stored placement — explicit and auto-instantiated groups, present-empty cells, raw lexical text —
     *  in insertion order. The Document encoder reads this; the mutable list itself never leaks. */
    internal fun placementsSnapshot(): List<DocumentPlacementRecord> =
        placements.map { DocumentPlacementRecord(it.path, it.reps, it.isGroup, if (it.isGroup) null else it.raw) }

    /** A field at the given 1-based indices (one per path part, the field included). An empty string
     *  materializes the cell but carries no evaluation value, matching the kernel's `valueExists` boundary. */
    fun field(path: String, value: String, vararg reps: Int): Document = add(path, reps, value, false)

    /** A present-but-valueless (unfilled) field. */
    fun emptyField(path: String, vararg reps: Int): Document = add(path, reps, null, false)

    /** A group row at the given 1-based indices (one per path part); its ancestors and predecessor rows appear too. */
    fun group(path: String, vararg reps: Int): Document = add(path, reps, null, true)

    /**
     * A field whose stored value was **converted at ingress**, so A12 renders it twice and the two texts differ:
     * [evaluationValue] is what every evaluation channel reads (the kernel's `IData` rendering) and
     * [storedValue] what the encoder writes back (the serializer rendering). A12's Document JSON ingress is the
     * only caller — see `A12DocumentJson.runtimeNumberForm` and IF231. [numberIdentity] retains the converted
     * Number object's coefficient and scale for source-relative change classification, including a negative
     * scale that [storedValue]'s plain rendering cannot recover.
     *
     * A plain [field] fill sets both to the one text it is given, which is exactly A12's **String-valued** cell:
     * `ValidationData.getValue` returns such a value unchanged, so the authored text IS the evaluation text.
     */
    internal fun renderedField(
        path: String,
        evaluationValue: String,
        storedValue: String,
        numberIdentity: DecimalObjectIdentity? = null,
        vararg reps: Int,
    ): Document = add(path, reps, evaluationValue, false, stored = storedValue, numberIdentity = numberIdentity)

    private fun add(
        path: String,
        reps: IntArray,
        value: String?,
        isGroup: Boolean,
        stored: String? = value,
        numberIdentity: DecimalObjectIdentity? = null,
    ): Document {
        val r = reps.toList()
        groupTreeMaterializer.materialize(
            path,
            r,
            if (isGroup) ModelEntityKind.GROUP else ModelEntityKind.FIELD,
        )
        if (!isGroup) {
            val evaluationValue = if (value.isNullOrEmpty()) null else value
            placements += Placement(path, r, evaluationValue, false, raw = stored, numberIdentity = numberIdentity)
        }
        invalidateDerivedCaches()   // every mutation clears ALL document-derived caches (IF142)
        return this
    }

    /**
     * Invalidate EVERY document-derived cache — the ONE place a mutation clears (IF142, was IG81). A new
     * document-derived cache MUST be added here, or a query → mutate → query can reuse a stale derived view.
     */
    private fun invalidateDerivedCaches() {
        cachedIndex = null                 // the lazily-built lookup index
        rowContextsCache.clear()           // a `Having` filter's per-row contexts
        correlationBucketCache.clear()     // a `$` equality-correlation's hash buckets
        orderedCorrelationCache.clear()    // a comparator-only `$` correlation's ordered prefix tallies
    }

    private fun autoGroup(path: String, reps: List<Int>) {
        if (instantiatedGroups.add(path to reps)) placements += Placement(path, reps, null, true)
    }

    // ---- lookup index (built once on first query, invalidated on mutation) ----------------------------

    private class Index(
        // Non-group placements, FIRST per key wins. Split by path (path → reps → placement) rather than a flat
        // `Map<Pair<String, List<Int>>, _>`: `valueAt` is on the hottest path, and the nested form drops the
        // per-lookup `Pair` allocation + `Pair.equals`/`hashCode` (the JFR profile's largest residual cluster) —
        // the outer `String` lookup is cheap; only the reps are hashed inside.
        val valueByKey: Map<String, Map<List<Int>, Placement>>,
        val rowsByGroup: Map<String, List<List<Int>>>,             // group path → distinct reps, document order
        val valued: List<Placement>,                               // valued (value != null) non-group placements
        // The kernel's "content" notion: a valued field OR an instantiated REPEATABLE-group row (creating a
        // repeatable row is content in itself — KERNEL-FINDINGS §4/§9). Feeds the `evaluated` row gate
        // ([attachByDepth]) AND `GroupFilled` for a non-repeatable group ([nonRepGroupFilledDepths]) — so both
        // see an all-field-empty-but-instantiated repeatable row as content (IF53). A non-repeatable group's
        // structural presence is NOT content (excluded here). Distinct from [valued], which the formal-error walk
        // ([valuedFields]) needs to stay valued-only.
        val content: List<Placement>,
        val partsByPath: Map<String, List<String>>,                // memoized path split
    ) {
        // Per-group memos, deterministic for this (immutable) index, rebuilt with it on mutation. They turn the
        // O(valued) attach-depth scan + row mapping — which [contextsFor]/[rowContextsScoped] otherwise redo on
        // EVERY rule (and every nested row) — into once-per-distinct-group work. This is the lever that keeps the
        // interpreter on-par with the kernel at high rule counts (many rules share a handful of iteration scopes;
        // the kernel computes the row structure once and reuses it across rules — this is the direct analog).
        val attachByGroup: MutableMap<String, Map<Int, Set<List<Int>>>> = HashMap()
        val validationContextsByGroup: MutableMap<String, List<EvalContext>> = HashMap()
        // Computation iterates only stored V2 rows, unlike validation's implicit nested-child domain (IF277).
        val storedContextsByGroup: MutableMap<String, List<EvalContext>> = HashMap()
        // The runtime iteration tree, distinct from stored V2 rows: below every existing repeatable parent, a
        // missing nested repeatable child contributes its implicit all-1 row. Memoized recursively per group so
        // N parent rows and N concrete children are projected once, never rescanned per rule (IF277).
        val iterationRowsByGroup: MutableMap<String, List<List<Int>>> = HashMap()
        // Per-group rows indexed by EVERY prefix of their rep tuple (built once on first use): `prefix → rows
        // whose reps start with it`. This replaces [starRows]'s O(all-rows) `.filter` per call with an O(1)
        // lookup — the lever that kills the **O(rows²)** in semantic-index lookups (`[F For "v"]`) and
        // cross-level star aggregates evaluated per row (the kernel's IndexFieldCache / row-index analog).
        // Space is O(rows × depth) (depth ≤ ~4), built only for the groups actually star-resolved.
        val prefixRowsByGroup: MutableMap<String, Map<List<Int>, List<List<Int>>>> = HashMap()
        // `GroupFilled(nonRepeatableGroup)` support: per (groupPath, iteration-group), the attach-depth →
        // prefix-set of valued DESCENDANTS of groupPath — the GroupFillCache analog for the non-repeatable
        // branch. Mirrors [attachByGroup] but filtered to groupPath's subtree, so `groupFilled` is O(1) per
        // call after a once-per-(groupPath,group) O(valued) build, not an O(valued) scan every row.
        val nonRepFilledByGroup: MutableMap<Pair<String, String>, Map<Int, Set<List<Int>>>> = HashMap()
        // SemanticIndex `[F For "v"]` lookup maps, per (indexField, star-scope prefix) — the kernel's
        // IndexFieldCache analog, DOCUMENT-level. Since the star-binding anchor (IF93) a same-group lookup's
        // row domain is the WHOLE list, so a per-ROW-CONTEXT memo would duplicate one identical O(rows) map
        // across O(rows) contexts — O(rows²) MEMORY (the perf gate's OOM). Keyed by the scope prefix, every
        // row context (and every sibling target of the index field) shares the one map.
        val indexRowsByValue: MutableMap<Pair<String, List<Int>>, Map<String, List<List<Int>>>> = HashMap()
        // The [IndexColumn] state per (index field, star-scope prefix) — the same DOCUMENT-level sharing as
        // [indexRowsByValue], for the same reason: the IF107/IF115 gates consult the column per LOOKUP, so a
        // per-read rebuild is O(rows) each and a per-row indexed rule turns validateFull O(rows²) (the perf
        // gate's semanticIndexStaysSubQuadratic hang).
        val indexColumnByScope: MutableMap<Pair<String, List<Int>>, IndexColumn> = HashMap()
        // Declared-tail polarity for a starred field is invariant across every row context sharing its first-star
        // scope. Cache the complete hierarchical decision, not just the leaf-row count: a deep multi-star path
        // otherwise repeats the same O(rows × star-depth) proof for every firing in that scope (IF194).
        val starOmissionByScope: MutableMap<StarOmissionKey, Boolean> = HashMap()

        // Per group, the rows whose coordinate is OVER-LIMIT (a group ancestor's index exceeds its effective
        // maximum) — computed once from [effectiveGroupRepetitionMaximums] and reused to EXCLUDE such rows from
        // every star aggregate / quantifier / filter domain (SPEC-2026-07-23-13). Empty for a well-formed
        // document, so the exclusion is a no-op on the hot path.
        val overLimitRowsByGroup: MutableMap<String, Set<List<Int>>> = HashMap()

        /** group path → its declared field paths, recursive and in model declaration order. */
        val containedFieldsByGroup: MutableMap<String, List<String>> = HashMap()
        /** Message pointers, partitioned by path to avoid flat-key collision clusters. */
        val messagePointersByPath: MutableMap<String, MutableMap<List<Int>, A12PartiallyKnownMultiPointer>> =
            HashMap()
    }

    private var cachedIndex: Index? = null

    private fun index(): Index = cachedIndex ?: build().also { cachedIndex = it }

    /**
     * Logical entries held by this document's derived caches. The observer sees structure, not heap estimates:
     * every cache container contributes its retained entry count, so a per-evaluation insertion has an exact slope.
     */
    internal fun observeCacheEntries(observer: CacheObserver) {
        val indexEntries = cachedIndex?.let { idx ->
            idx.valueByKey.values.sumOf { it.size } +
                idx.rowsByGroup.values.sumOf { it.size } +
                idx.valued.size +
                idx.content.size +
                idx.partsByPath.size +
                idx.attachByGroup.size +
                idx.validationContextsByGroup.size +
                idx.storedContextsByGroup.size +
                idx.iterationRowsByGroup.size +
                idx.prefixRowsByGroup.size +
                idx.nonRepFilledByGroup.size +
                idx.indexRowsByValue.size +
                idx.indexColumnByScope.size +
                idx.starOmissionByScope.size +
                idx.overLimitRowsByGroup.values.sumOf { it.size } +
                idx.containedFieldsByGroup.size +
                idx.messagePointersByPath.values.sumOf { it.size }
        } ?: 0
        observer.cacheEntries(
            this,
            CacheOwnerKind.DOCUMENT,
            indexEntries + rowContextsCache.size + correlationBucketCache.size + orderedCorrelationCache.size,
        )
    }

    private fun build(): Index {
        val byKey = HashMap<String, HashMap<List<Int>, Placement>>()
        val rows = LinkedHashMap<String, MutableList<List<Int>>>()
        val valued = ArrayList<Placement>()
        val content = ArrayList<Placement>()
        val partsMemo = HashMap<String, List<String>>()
        for (p in placements) {
            val pathParts = partsMemo.getOrPut(p.path) { parts(p.path) }
            // One coordinate per path part. A cell whose coordinate count disagrees with its path has no
            // address — A12's message pointer pairs a name with an index at every level — so such a placement
            // cannot be pointed at. It went unchecked while the house renderer merely IGNORED a surplus, which
            // silently collapsed a whole repeated column onto one rendered pointer (measured: placing `/C/S` at
            // `(1, i, 1)` rendered `/C[1]/S` for every i).
            //
            // Checked HERE and not in `add`: this loop already has the part list, so the check is free, whereas
            // `add` is the document-build hot path and computing it per placement there cost real time —
            // `CharsetPerfTest`'s N/4N growth ratio went to 9.73× against its 9.0× floor, measured, before this
            // moved. The engine's #1 performance rule is that the interpreter never gets worse.
            require(p.reps.size == pathParts.size) {
                "'${p.path}' has ${pathParts.size} parts but was placed at ${p.reps} — a cell carries one " +
                    "1-based coordinate per path part, the field's own included"
            }
            if (p.isGroup) {
                // No `!in` dedup needed: every group placement is already unique by (path, reps) — both
                // group() and autoGroup() gate on the `instantiatedGroups` set before adding. The old
                // membership check was an O(rows) List scan per group → an O(N²) index build that the WARM
                // perf harness never saw (build runs once, before timing) but every COLD first validateFull
                // pays — the startup / CLI-one-shot path, and the reason a single JS pass blew up at scale.
                rows.getOrPut(p.path) { ArrayList() }.add(p.reps)
                if (p.path in declaredReps) content.add(p)   // an instantiated REPEATABLE row is content (IF53)
            } else {
                // CRLF→LF at EVAL ingestion (IF124): the kernel's formal-check stage feeds its eval caches the
                // NORMALIZED value (`CheckCommand` → `FormalChecker.checkAndConvertFieldValue.normalizedValue()`
                // = `RuntimeUtils.normalizeLinebreaks` — the `\r\n` pair only, a lone CR stays), so EVERY
                // downstream EVALUATION read — the format measure, rule operands, compute operands — sees `\n`.
                // Message interpolation deliberately reads [Placement.raw], outside this cache. Only this
                // eval-side index normalizes; the consumer's raw [placements] stay untouched.
                val q = if (p.value != null && p.value.contains("\r\n"))
                    p.copy(value = p.value.replace("\r\n", "\n")) else p
                byKey.getOrPut(q.path) { HashMap() }.getOrPut(q.reps) { q }   // FIRST placement per (path,reps) wins
                if (q.value != null) { valued.add(q); content.add(q) }
            }
        }
        return Index(byKey, rows, valued, content, partsMemo)
    }

    private fun partsOf(path: String): List<String> = index().partsByPath[path] ?: parts(path)

    /**
     * Every present (valued) field instance — its path, 1-based repetition indices, and raw value — in
     * document order. The document-wide formal-error check ([DmInterpreter.validate]) walks these: the
     * kernel formal-checks *all* filled fields, not only the rule-referenced ones. Unfilled (empty) fields
     * and groups are excluded — emptiness is a requiredness concern, not a format one.
     */
    fun valuedFields(): List<ValuedField> =
        index().valued.map { ValuedField(it.path, it.reps, it.value!!) }

    /**
     * Every placed non-group address, path-major and uncopied: `path → its coordinates`. A superset of
     * [valuedFields] (a present-but-empty cell is here too), which is the safe direction for the caller this
     * exists for.
     *
     * The over-repetition gate precondition (`AutoChecks.overRepetitionGateNeeded`, IG127.1) resolves ONE bound set per
     * path and then compares coordinates, so it must iterate path-major; walking [valuedFields] instead cost a
     * [io.github.mbackschat.a12.dm.interpreter.model.ValuedField] allocation per cell and a map probe per cell
     * (measured at 2.95 % of a validate profile before this existed).
     */
    internal fun placedAddresses(): Map<String, Map<List<Int>, *>> = index().valueByKey

    /** Instantiated rows of one group, without constructing evaluation contexts or scanning document content. */
    internal fun instantiatedRows(groupPath: String): List<List<Int>> = rowsOf(groupPath)

    /**
     * <b>The one place a message address is built</b> — A12's `PartiallyKnownDocumentMultiPointer` for the cell
     * or row at `(path, reps)`, retained with the immutable document index and discarded on mutation
     * (IF191/IF142). Every `Message` channel takes its pointer from here, so the engine stores the typed value
     * and never re-derives one from text.
     */
    internal fun messagePointer(path: String, reps: List<Int>): A12PartiallyKnownMultiPointer =
        index().messagePointersByPath.getOrPut(path) { HashMap() }
            .getOrPut(reps) { A12PartiallyKnownMultiPointer.of(path, reps) }

    /**
     * The house-form projection of [messagePointer], for the two channels that are genuinely textual: an
     * [io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule]'s cell subject and the `$pointer$` slot of
     * a diagnostic's rendered text. Derived, so it cannot disagree with the retained pointer.
     */
    internal fun formattedPointer(path: String, reps: List<Int>): String =
        messagePointerHouseForm(messagePointer(path, reps))

    /**
     * A copy of this document with computed values applied — the interpreter's twin of the kernel's
     * `IDocumentComputationResult.applyTo` (KERNEL-FINDINGS "validateFull does NOT run computations"): each
     * [overrides] entry's value REPLACES the cell at `(path, reps)`; a `null` value CLEARS it. (The kernel
     * applies the `WithChanges` values and clears both the `WithErrors` and the `Cleared` instances, so an
     * errored/cleared computation maps to a `null` override here.) The original document is unchanged.
     *
     * Used by [DmInterpreter.applyComputations] to materialize the compute-applied document for the
     * form-engine validation flow (compute → apply → validate); re-adding through the public builders keeps the
     * indices/auto-grouping invariants and document order intact.
     */
    internal fun withComputed(overrides: Map<Pair<String, List<Int>>, String?>): Document {
        val copy = emptyCopy()
        val applied = HashSet<Pair<String, List<Int>>>()
        for (p in placements) {
            if (p.isGroup) {
                copy.group(p.path, *p.reps.toIntArray())
                continue
            }
            val key = p.path to p.reps
            when {
                key in overrides -> {
                    applied += key
                    overrides[key]?.let { copy.field(p.path, it, *p.reps.toIntArray()) }
                        ?: copy.emptyField(p.path, *p.reps.toIntArray())
                }
                else -> copy.copyField(p)
            }
        }
        // A value computed into a cell with no prior placement (an empty computed field never present as a
        // placement): add it. A null override (clear) for an absent cell is a no-op.
        for ((key, value) in overrides) {
            if (key !in applied && value != null) copy.field(key.first, value, *key.second.toIntArray())
        }
        return copy
    }

    /**
     * Apply retained, source-relative computation actions to this destination. VALUE and CLEARED are writes and
     * therefore materialize their exact addressed ancestry; ERRORED is destination-presence-guarded. No action
     * means no destination-relative inference.
     */
    internal fun withComputationApplications(actions: List<ComputationApplication>): Document {
        val byCell = actions.associateBy { it.path to it.reps }
        val copy = emptyCopy()
        val applied = HashSet<Pair<String, List<Int>>>()
        for (placement in placements) {
            if (placement.isGroup) {
                copy.group(placement.path, *placement.reps.toIntArray())
                continue
            }
            val key = placement.path to placement.reps
            val action = byCell[key]
            when {
                action == null -> copy.copyField(placement)
                key in applied -> copy.copyField(placement)
                else -> {
                    applied += key
                    when (action.outcome) {
                        ComputeOutcome.VALUE -> copy.computedField(action)
                        ComputeOutcome.CLEARED, ComputeOutcome.ERRORED ->
                            copy.emptyField(action.path, *action.reps.toIntArray())
                    }
                }
            }
        }
        for (action in actions) {
            val key = action.path to action.reps
            if (key in applied) continue
            when (action.outcome) {
                ComputeOutcome.VALUE -> copy.computedField(action)
                ComputeOutcome.CLEARED -> copy.emptyField(action.path, *action.reps.toIntArray())
                ComputeOutcome.ERRORED -> Unit
            }
        }
        return copy
    }

    /** Whether this source cell's stored V2 object equals a clean computed value. */
    internal fun computedValueEquals(path: String, reps: List<Int>, computed: String): Boolean {
        val placement = valueAt(path, reps) ?: return false
        return when (kinds[path]) {
            Kind.TIME -> false
            Kind.NUMBER -> placement.numberIdentity == DecimalObjectIdentity.parse(computed)
            else -> placement.raw == computed
        }
    }

    private fun Document.copyField(placement: Placement) {
        add(
            path = placement.path,
            reps = placement.reps.toIntArray(),
            value = placement.value,
            isGroup = false,
            stored = placement.raw,
            numberIdentity = placement.numberIdentity,
        )
    }

    private fun Document.computedField(action: ComputationApplication) {
        val value = checkNotNull(action.value) { "a VALUE computation action must carry its stored value" }
        add(
            path = action.path,
            reps = action.reps.toIntArray(),
            value = value,
            isGroup = false,
            stored = value,
            numberIdentity = if (kinds[action.path] == Kind.NUMBER) DecimalObjectIdentity.parse(value) else null,
        )
    }

    /** An empty document carrying this one's [config] but no placements. Passing the single immutable
     *  [DocumentConfig] wholesale is the fix for IG75/IF131: the former positional reconstruction silently
     *  dropped `timeZone`, so a copy could not stay in lockstep. Now it cannot drop a slot — there is no
     *  per-slot list. */
    private fun emptyCopy(): Document = Document(config)

    /**
     * One [EvalContext] per runtime iteration row of [groupPath]. This is normally its instantiated V2 rows.
     * Below an existing repeatable parent, however, a nested repeatable group with no concrete row contributes
     * one implicit all-1 child; the rule composes recursively across any nesting depth (IF277). An absent
     * outermost repeatable group still yields no context.
     *
     * Each row's `evaluated` flag is computed here in a single amortized pass over the content-bearing fields
     * and rows: a row is evaluated iff some content is in its scope, i.e. its index-prefix matches at the depth
     * where the content path diverges from the group (`common`). Recording each content item's prefix at its
     * attach-depth turns the per-row "is anything in scope?" check into a set lookup.
     */
    fun contextsFor(groupPath: String): List<EvalContext> =
        index().validationContextsByGroup.getOrPut(groupPath) { computeValidationContextsFor(groupPath) }

    /** One already-selected concrete row context. Index resolution owns selection; this reconstructs the normal
     *  evaluation environment so the rule and its message pointer inherit the identical repetition. */
    internal fun contextFor(groupPath: String, repetitions: List<Int>): EvalContext =
        RowContext(groupPath, repetitions, evaluated = true)

    /**
     * Contexts for a COMPUTATION's iteration scope. Like [contextsFor], but when [groupPath] is
     * **all-non-repeatable** (no repeatable ancestor, itself included) and has NO instantiated row, it still
     * yields ONE implicit context at reps `[1,…]` — the kernel treats a non-repeatable group as always present,
     * so an all-non-repeatable computation computes its single instance even on an uninstantiated target (IF89).
     * A REPEATABLE scope with zero rows still yields nothing (no instance to compute), and an instantiated scope
     * yields its real rows unchanged (so the row's `evaluated` flag / content are preserved).
     */
    fun computeContexts(groupPath: String): List<EvalContext> {
        val rows = instantiatedContextsFor(groupPath)
        if (rows.isNotEmpty()) return rows
        val segs = parts(groupPath)
        val allNonRepeatable = (1..segs.size).none { d -> "/" + segs.take(d).joinToString("/") in declaredReps }
        return if (allNonRepeatable) listOf(RowContext(groupPath, List(segs.size) { 1 }, false)) else emptyList()
    }

    /** Stored V2 row contexts. Computations and index-cache scans must not inherit validation's implicit nested
     * child rows: the kernel evaluates validation rules on those rows but computes only concrete targets (IF277). */
    internal fun instantiatedContextsFor(groupPath: String): List<EvalContext> =
        index().storedContextsByGroup.getOrPut(groupPath) { contextsForRows(groupPath, rowsOf(groupPath)) }

    /**
     * The ONCE-evaluation context at [groupPath] — a rule with no repeatable reference level evaluates exactly
     * once (KERNEL-FINDINGS §9 supplement, the star-only scope law): the group's real row when instantiated,
     * else ONE synthetic all-1s row. The synthetic row exists even on an empty document because the kernel's
     * once-frame always runs — the content gate rides the `evaluated` flag (computed from the valued-field
     * attach map exactly as a real row's), so a comparison stays suppressed on a contentless instance while a
     * structural can-fire-empty condition (`GroupNotFilled(RuleGroup)` on an empty group) still fires.
     */
    fun onceContextFor(groupPath: String): List<EvalContext> {
        val rows = contextsFor(groupPath)
        if (rows.isNotEmpty()) return rows
        val reps = List(parts(groupPath).size) { 1 }
        val attach = attachByDepth(groupPath)
        return listOf(RowContext(groupPath, reps, attach.any { (d, pfx) -> reps.take(d) in pfx }))
    }

    private fun computeValidationContextsFor(groupPath: String): List<EvalContext> {
        return contextsForRows(groupPath, iterationRowsOf(groupPath))
    }

    private fun contextsForRows(groupPath: String, rows: List<List<Int>>): List<EvalContext> {
        if (rows.isEmpty()) return emptyList()
        val attach = attachByDepth(groupPath)
        return rows.map { reps ->
            RowContext(groupPath, reps, attach.any { (depth, prefixes) -> reps.take(depth) in prefixes })
        }
    }

    private fun iterationRowsOf(groupPath: String): List<List<Int>> =
        index().iterationRowsByGroup.getOrPut(groupPath) { computeIterationRowsFor(groupPath) }

    /**
     * Project A12's nested iteration domain without mutating the stored document. A nested group with no actual
     * row below one concrete parent gets exactly one row with every lower coordinate set to 1. Grouping actual
     * children by parent makes the projection O(parent rows + child rows), and recursion supplies the same rule
     * when one missing level sits below another.
     */
    private fun computeIterationRowsFor(groupPath: String): List<List<Int>> {
        val actualRows = rowsOf(groupPath)
        val repeatableParent = ancestorGroups(groupPath)
            .filter { it in repeatableGroups }
            .maxByOrNull { partsOf(it).size }
            ?: return actualRows
        val parentRows = iterationRowsOf(repeatableParent)
        if (parentRows.isEmpty()) return emptyList()

        val parentDepth = partsOf(repeatableParent).size
        val childDepth = partsOf(groupPath).size
        val actualByParent = actualRows.groupBy { it.take(parentDepth) }
        return buildList {
            for (parentReps in parentRows) {
                val children = actualByParent[parentReps]
                if (children.isNullOrEmpty()) add(parentReps + List(childDepth - parentDepth) { 1 })
                else addAll(children)
            }
        }
    }

    /**
     * **Parallel iteration** — joint iteration of sibling repeatable [groups] (neither nested in the other),
     * keyed by their shared **index field** [indexFieldName], within each row of their deepest common ancestor
     * [commonParent]. The kernel evaluates this as an *outer join over the index VALUES* (KERNEL-SEMANTICS §9 /
     * KERNEL-FINDINGS §9 — its `IndexFieldCache`): for each value present in ANY group, each group resolves to
     * the row whose index cell equals that value, or counts as **not specified** when it has no such row. One
     * [EvalContext] per (common-parent row × index value) is produced; a group with no matching row resolves its
     * fields empty (and its pointer level to [UNKNOWN_REP], -5).
     *
     * The iteration value set is the **union of the groups' index-field values** within each common-parent row
     * (the kernel's `ValidationCache.getIterationValuesForGroups` — the per-group value→repetition map keys, with
     * empty cells excluded and a DUPLICATED value keying nothing at all, [IF270]). Each value resolves each group
     * to the row that carries it, or **not specified** when the group has no row for it. Values are compared as
     * canonical [indexKeyOf] keys, not as raw text ([IF272]).
     *
     * **Invalid index → 3VL (KERNEL-FINDINGS §9):** a group whose index is *invalid* here (a duplicate value or
     * an empty cell — `invalidIndexGroups`) reads its **unmatched** references as 3-valued **UNKNOWN**, not
     * "empty" — matching the kernel's behavior, which suppresses a negative operator like
     * `FieldsNotCollectivelyFilled` on the compromised side. A *valid* group's unmatched reference is definite
     * "empty". A *matched* reference always resolves to its row (definite), regardless of group validity. The
     * duplicate/empty rows additionally carry the auto `uniqueIndex`/`mandatoryField` errors (emitted
     * independently). Iteration order is irrelevant (the message multiset is compared).
     *
     * **Frame (a nested repeatable above one joined group):** when one joined group [frameGroup] sits under a
     * repeatable non-indexed ancestor [deepestFrame] (between [commonParent] and it), the kernel iterates that
     * frame normally and does the join INSIDE each frame row (KERNEL-FINDINGS §9 — the `/Plan/X/A ⋈ B` case):
     * the framed group's rows + index values are scoped to the current frame row, while the other (clean) joined
     * groups stay shared under [commonParent]. With no frame ([deepestFrame] null) this reduces to one join per
     * common-parent row.
     */
    fun parallelContexts(groups: List<String>, indexFieldName: String, commonParent: String,
            frameGroup: String? = null, deepestFrame: String? = null,
            /** Partial validation only: gates which INDEX cells key the join ([IF269]). Null = every cell. */
            cellRelevant: ((String, List<Int>) -> Boolean)? = null,
            /** Complete formal-admission gate supplied by the validation/compute pass. */
            indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)? = null): List<EvalContext> {
        val out = ArrayList<EvalContext>()
        for (cReps in parentRowsOf(commonParent)) {
            // The NON-framed (clean) groups are shared across every frame row — build their value maps + invalid
            // flags ONCE per common-parent row, NOT per frame row (else a clean group of M rows is rescanned per
            // frame row → O(frames × M)). Only the framed group's map depends on the current frame row.
            val cleanInvalid = HashSet<String>()
            val cleanMaps = groups.filter { it != frameGroup }
                .associateWith {
                    indexMap(it, indexFieldName, cReps, cleanInvalid, cellRelevant, indexIdentityAdmitted)
                }
            // An over-limit FRAME row is outside the kernel's cache, so the join never runs inside it (the same
            // exclusion as the joined groups below and the star domain, SPEC-2026-07-23-13 / IF220).
            val frameRows = if (deepestFrame != null)
                rowsWithPrefix(deepestFrame, cReps).filter { it !in overLimitRows(deepestFrame) }
            else listOf(cReps)
            for (fr in frameRows) {
                // each group's parent context: the framed group is scoped to the current frame row, others to cReps
                val parentReps = groups.associateWith { g -> if (g == frameGroup) fr else cReps }
                val invalid = HashSet(cleanInvalid)
                val perGroup = if (frameGroup == null) cleanMaps
                    else cleanMaps + (frameGroup to indexMap(
                        frameGroup,
                        indexFieldName,
                        fr,
                        invalid,
                        cellRelevant,
                        indexIdentityAdmitted,
                    ))
                val values = LinkedHashSet<String>().apply { groups.forEach { addAll(perGroup.getValue(it).keys) } }
                for (v in values) {
                    out += ParallelRowContext(commonParent, cReps, groups, parentReps,
                        groups.associateWith { perGroup.getValue(it)[v] }, invalid)
                }
            }
        }
        return out
    }

    /** Common-parent context rows for a parallel iteration: the parent group's instantiated rows, or the all-1s
     *  prefix when a non-repeatable parent is not separately instantiated. An over-limit parent row is outside the
     *  cache (SPEC-2026-07-23-13 / IF220), so it is no common-parent frame (a no-op for a non-repeatable parent). */
    private fun parentRowsOf(commonParent: String): List<List<Int>> =
        rowsOf(commonParent).filter { it !in overLimitRows(commonParent) }.ifEmpty {
            if (commonParent in declaredReps) emptyList() else listOf(List(parts(commonParent).size) { 1 })
        }

    /** [group]'s `indexValue → matching-row reps` map under parent context [ctxReps]; adds [group] to
     *  [invalidOut] if its index has a duplicate value or an empty cell here. An OVER-LIMIT row is EXCLUDED from
     *  the join: the kernel never inserts it into the `IndexFieldCache`, so its index value is not a join key and
     *  a reference to it reads UNMATCHED (empty-as-0), never its poisoned cell — the same [overLimitRows]
     *  exclusion the star/quantifier domain uses (SPEC-2026-07-23-13 / IF220).
     *
     *  **A DUPLICATED value keys NOTHING** ([IF270]): it is not a selectable entry and is never resolved by row
     *  order, so every colliding row drops out and the value is a join key only if another group carries it
     *  cleanly. Since the group is also marked invalid here, a reference to that value is then
     *  unmatched-into-an-invalid-group = 3VL UNKNOWN, which is what suppresses a negative operator over it. This
     *  is the same rule the semantic-index column already applies ([IndexColumn]); the parallel join was the one
     *  index-keyed lookup that resolved a duplicate by row order.
     *
     *  Both the collision test and the join key itself are the CANONICAL [indexKeyOf] form, never the raw stored
     *  text ([IF272]) — on a Number index field `5` and `5.00` are one kernel key, so they collide within a group
     *  and match across groups. */
    private fun indexMap(group: String, indexFieldName: String, ctxReps: List<Int>, invalidOut: MutableSet<String>,
            cellRelevant: ((String, List<Int>) -> Boolean)?,
            indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)?): Map<String, List<Int>> {
        val idxPath = "$group/$indexFieldName"
        val overLimit = overLimitRows(group)
        val candidates = ArrayList<IndexIdentityCandidate<String>>()
        for (row in rowsWithPrefix(group, ctxReps)) {
            if (row in overLimit) continue                                  // over-limit row: outside the join
            // Partial validation: the join is keyed by the INDEX field and the kernel builds that cache from the
            // RELEVANT cells only (IF39), so a row whose own index cell is outside the relevant set contributes
            // no key — the group is then the outer join's "not specified" side for that value, pointer
            // `Demand[-5]` ([IF269]). A relevant GROUP covers its index cell, which is why selecting a whole row
            // keeps its iteration.
            //
            // It also INVALIDATES the column, exactly like an empty cell ([IF271]): a non-relevant index cell is
            // "not given" to the relevance-scoped cache, so ONE of them makes every unmatched reference into
            // this group read 3VL UNKNOWN rather than a definite empty. Only a negative operator can see the
            // difference, which is why [IF269]'s shapes — all measured with `FieldFilled(…) Or FieldFilled(…)`,
            // true whenever the far side is filled — pinned the pointer but not the validity.
            if (cellRelevant?.invoke(idxPath, row + 1) == false) {
                candidates += IndexIdentityCandidate(row, null, compromises = true)
                continue
            }
            val raw = valueAt(idxPath, row + 1)?.value
            if (raw.isNullOrEmpty()) {                                      // empty index cell → group invalid here
                candidates += IndexIdentityCandidate(row, null, compromises = true)
                continue
            }
            val cellReps = row + 1
            val admitted = indexIdentityAdmitted?.invoke(idxPath, cellReps, raw)
                ?: !formallyInvalid(idxPath, raw)
            if (!admitted) {
                // Formal admission precedes identity. The invalid cell compromises the column like an empty one,
                // but contributes no key and therefore cannot collide with or match a valid value.
                candidates += IndexIdentityCandidate(row, null, compromises = true)
                continue
            }
            candidates += IndexIdentityCandidate(
                row,
                indexKeyOf(raw, kinds[idxPath]),
                compromises = false,
            )
        }
        val resolved = resolveIndexIdentity(candidates)
        if (resolved.compromised) invalidOut += group
        return resolved.uniqueRowsByKey
    }

    /**
     * The per-group attach-depth index: `attach-depth → the set of row index-prefixes that have CONTENT in scope
     * at that depth`, over [Index.content] (a valued field OR an INSTANTIATED REPEATABLE-group row — the kernel
     * treats creating a repeatable row as content in itself, KERNEL-FINDINGS §4/§9). So a rule is EVALUATED on an
     * all-field-empty but instantiated row — a per-row rule on the row itself, an ancestor-scoped aggregate whose
     * descendant rows make the ancestor content-bearing (IF53). A NON-repeatable group's mere structural presence
     * is NOT content (excluded from [Index.content]) — matching the truly-empty `/Order` in `EmptyRowGateDiffTest`.
     * The O(content) scan that builds it is **memoized per group** (rebuilt with the [Index] on mutation), so many
     * rules sharing one iteration scope — and nested per-row [rowContextsScoped] lookups — pay it once, not per
     * rule/row. A row is `evaluated` iff some prefix matches.
     */
    private fun attachByDepth(groupPath: String): Map<Int, Set<List<Int>>> =
        index().attachByGroup.getOrPut(groupPath) {
            val gParts = partsOf(groupPath)
            val m = HashMap<Int, HashSet<List<Int>>>()
            for (p in index().content) {
                val common = commonPrefixLen(partsOf(p.path), gParts)
                m.getOrPut(common) { HashSet() }.add(p.reps.take(common))
            }
            m
        }

    /**
     * For `groupFilled(nonRepeatableGroupPath)` evaluated from iteration group [group]: `attach-depth →
     * prefix-set` over the CONTENT **descendants of [groupPath]** (paths under `groupPath/` in [Index.content] —
     * a valued field OR an instantiated repeatable descendant row, so an empty-but-instantiated repeatable child
     * fills its non-repeatable ancestor exactly as the kernel's `GroupFillCache` does, IF53). Memoized per
     * (groupPath, group) in the [Index]. A row's instance of [groupPath] is filled iff `rowReps.take(d)` is in
     * the set at some depth `d` — which mirrors the per-descendant `commonPrefixLen(p.path, group)` shared-index
     * scope check exactly, only grouped so it is computed once per (groupPath, group) instead of scanned per row.
     */
    private fun nonRepGroupFilledDepths(groupPath: String, group: String): Map<Int, Set<List<Int>>> =
        index().nonRepFilledByGroup.getOrPut(groupPath to group) {
            val gParts = partsOf(group)
            val prefix = "$groupPath/"
            val m = HashMap<Int, HashSet<List<Int>>>()
            for (p in index().content) {
                if (!p.path.startsWith(prefix)) continue
                val common = commonPrefixLen(partsOf(p.path), gParts)
                m.getOrPut(common) { HashSet() }.add(p.reps.take(common))
            }
            m
        }

    /** The distinct 1-based index tuples at which [groupPath] is instantiated, in document order. */
    private fun rowsOf(groupPath: String): List<List<Int>> = index().rowsByGroup[groupPath] ?: emptyList()

    /** [EvalContext]s for the rows of [groupPath] whose reps extend [scopeReps] (the rows under a given parent
     *  row) — the scoped form of [contextsFor], for a `Having` per-row filter. Uses the **prefix index**
     *  ([rowsWithPrefix], O(in-scope rows)) rather than scanning ALL rows of [groupPath] and prefix-filtering:
     *  the `Having` clause asks for this per parent scope, so the old O(all-rows) scan was O(scopes·rows) =
     *  quadratic on a deep nested doc (the `deepRichScaling` cliff — JFR-confirmed). The prefix map is built
     *  once per group; document order is preserved (the same rows the filter returned). */
    // Memoized (group, scope) → row contexts: the document is immutable during evaluation, so the rows under a
    // given parent scope are fixed. A `$` self-correlation is the hot case — a rule iterating group G evaluates a
    // `Having` over G* once PER outer row, all sharing G's PARENT scope, so without this every outer row rebuilt
    // the SAME N RowContexts → O(N²) allocation. Cached, that is O(N) once. (The reused RowContexts also keep
    // their warm per-row value caches across outer rows — a further constant win. Bounded by distinct scopes.)
    private val rowContextsCache = HashMap<Pair<String, List<Int>>, List<EvalContext>>()

    private fun rowContextsScoped(groupPath: String, scopeReps: List<Int>): List<EvalContext> =
        rowContextsCache.getOrPut(groupPath to scopeReps) {
            val attach = attachByDepth(groupPath)   // memoized per group — no O(valued) rescan per nested row
            val overLimit = overLimitRows(groupPath)   // over-limit rows are outside the star/quantifier domain (SPEC-2026-07-23-13)
            rowsWithPrefix(groupPath, scopeReps)
                .filter { it !in overLimit }
                .map { reps -> RowContext(groupPath, reps, attach.any { (d, pfx) -> reps.take(d) in pfx }) }
        }

    // Hash-join buckets for a `$` equality-correlation Having, cached per (group, scope, joinFields, starPath):
    // built ONCE from the group's rows (O(N)) and reused by every outer row (each an O(1) lookup) — the O(N)
    // strategy that matches/beats the kernel's linear self-correlation. The document is immutable during eval.
    private val correlationBucketCache = HashMap<List<Any>, Map<List<Val>, IntArray>>()

    private fun correlationBucketsScoped(
        groupPath: String, scopeReps: List<Int>, joinFields: List<String>, starPath: String,
    ): Map<List<Val>, IntArray> =
        correlationBucketCache.getOrPut(listOf(groupPath, scopeReps, joinFields, starPath)) {
            correlationBucketsOf(rowContextsScoped(groupPath, scopeReps), joinFields, starPath)
        }

    // Comparator-only `$` correlation, cached at the same immutable group/scope lifetime as equality buckets.
    private val orderedCorrelationCache = HashMap<List<Any>, OrderedCorrelation>()

    private fun orderedCorrelationScoped(
        groupPath: String,
        scopeReps: List<Int>,
        innerField: String,
        starPath: String,
    ): OrderedCorrelation =
        orderedCorrelationCache.getOrPut(listOf(groupPath, scopeReps, innerField, starPath)) {
            OrderedCorrelation.build(rowContextsScoped(groupPath, scopeReps), innerField, starPath)
        }

    /** Indices for a referenced path P under a row of group G: shared-prefix indices from the row, else 1. */
    private fun repsFor(refPath: String, group: String, rowReps: List<Int>): List<Int> {
        val refParts = partsOf(refPath)
        val common = commonPrefixLen(refParts, partsOf(group))
        return refParts.indices.map { i -> if (i < common) rowReps[i] else 1 }
    }

    private fun valueAt(path: String, reps: List<Int>): Placement? = index().valueByKey[path]?.get(reps)

    /**
     * Whether the field instance at [path]/[reps] carries a value — the requiredness check for an
     * **unconditionally** required field ([DmInterpreter]), which the kernel flags at the canonical all-1s
     * instance even on an otherwise un-instantiated document (so it can't go through [contextsFor]).
     */
    fun filledAt(path: String, reps: List<Int>): Boolean = valueAt(path, reps)?.value != null

    /**
     * The message-interpolation display value ([Placement.raw]) of the field instance at [path]/[reps], or null
     * when the cell is absent or empty — read at EXPLICIT reps so a rule's `$field.value$` can resolve against
     * the ORIGINAL caller document rather than a transient evaluation view. The kernel's `$field.value$`
     * substitution reads `validationCache.getDaten().getValueForDisplay` — the caller `IData`, which never sees
     * the index-default staging cache (`datenCache`); so a STAGED index cell interpolates as if empty (IF221).
     */
    internal fun messageDisplayValueAt(path: String, reps: List<Int>): String? = valueAt(path, reps)?.raw

    /**
     * The stored state of the field cell at [path]/[reps] — [StoredCellState.Absent] (no placement),
     * [StoredCellState.PresentEmpty] (a placement with no value, a `""` value included), or
     * [StoredCellState.PresentValue] carrying the raw stored text. The public read-back for the
     * apply-then-inspect loop (`validate(applyComputations(doc))` — IG72 → IF126): the states mirror what
     * the kernel's `IDocumentComputationResult.applyTo(DocumentV2)` leaves per cell (the [StoredCellState]
     * KDoc carries the per-result-kind contract). [reps] are 1-based, one per path segment, the field's
     * own (always 1) included — the same addressing as [filledAt] and the builders.
     */
    fun cellStateAt(path: String, reps: List<Int>): StoredCellState {
        val placement = valueAt(path, reps) ?: return StoredCellState.Absent
        val raw = placement.raw
        return if (raw.isNullOrEmpty()) StoredCellState.PresentEmpty else StoredCellState.PresentValue(raw)
    }

    private inner class RowContext(
        private val group: String,
        private val rowReps: List<Int>,
        override val evaluated: Boolean,
    ) : EvalContext, GroupListEnvironmentProvider {

        /**
         * Resolve a referenced field to its placement (or null) ONCE per path, cached for this row context —
         * the kernel's single-resolution `vergleiche`/`feldWertAlsZahl` analog. `field`, `filled`, and
         * `displayValue` all resolved the SAME `(path, reps)` independently (each `repsFor` allocating a reps
         * list + a `valueAt` map lookup), so a comparison's field operand was resolved ~2× per row — and the
         * row contexts are reused across every rule iterating this scope, so caching pays across all of them.
         * The document is immutable during a validation, so the placement is stable (correct for full + partial).
         */
        // The reps (1-based repetition indices) of a referenced path in THIS row context — `repsFor` is
        // deterministic in (path, group, rowReps), and group/rowReps are fixed here, so memoize by path. The
        // profile (#64) put `repsFor` + its `commonPrefixLen` string-compares + the `List<Int>` alloc among the
        // top costs: `repsOf` is called many times per row (the error path, each referenced field, the
        // FormalCache key, the suppressor scan) and recomputed every time. Caching also returns the SAME list
        // instance, so the FormalCache `Pair` key stops re-allocating it. (Stable: the document is immutable.)
        private val repsCache = HashMap<String, List<Int>>()
        private fun reps(path: String): List<Int> = repsCache.getOrPut(path) { repsFor(path, group, rowReps) }

        private val placeCache = HashMap<String, Placement?>()
        private fun placeOf(path: String): Placement? {
            placeCache[path]?.let { return it }
            if (placeCache.containsKey(path)) return null
            val p = valueAt(path, reps(path))
            placeCache[path] = p
            return p
        }

        // The resolved [Val] per path, cached for this row context — `field` is called once per operand per
        // RULE, so many rules referencing the same field on the same row re-resolved (`resolveValue`, a `Val`
        // allocation) independently. The value is stable (immutable document), so cache it once per row.
        private val valCache = HashMap<String, Val>()
        override fun field(path: String): Val = valCache.getOrPut(path) {
            val v = placeOf(path)?.value
            if (v != null) resolveValue(path, v) else emptyValue(kindOf(path))
        }

        override fun filled(path: String): Boolean = placeOf(path)?.value != null

        override fun signed(path: String): Boolean = path !in unsignedFields

        /**
         * Whether [groupPath] has structural raw content in this row, split on repeatability:
         * - a **repeatable** group is filled once its row is **instantiated** (creating the row is itself
         *   content — the row index is meaningful); a created-but-empty repeat row counts. A filled descendant
         *   also instantiates the row (via `autoGroup`), so "the row exists" subsumes "a descendant is filled".
         * - a **non-repeatable** group is filled only when **at least one descendant field is filled** — its
         *   mere structural presence is not content.
         *
         * Full validation refines this raw answer through its call-local admitted-content/error index (IF193):
         * malformed-only raw content is not filled, and makes negative presence UNKNOWN. Compute and low-level
         * contexts retain this structural answer. (KERNEL-FINDINGS §4/§9.)
         */
        override fun groupFilled(groupPath: String): Boolean =
            if (groupPath in declaredReps)
                // A repeatable group is "filled" iff its instance at this row's reps is instantiated — an O(1)
                // membership via the row-prefix index (was an O(rows) `in` on the row List, i.e. O(rows²) when
                // hit per row by requiredness over a sparse-required document).
                rowsWithPrefix(groupPath, reps(groupPath)).isNotEmpty()
            else
                // A NON-repeatable group is "filled" iff some valued DESCENDANT is in this row's scope.
                // O(1) per call via the per-(groupPath, group) attach-depth index (the GroupFillCache analog) —
                // exactly mirrors the per-descendant scope check (group descendants by their common-with-`group`
                // depth, match the row's prefix at that depth), but built once per (groupPath, group), not per row.
                nonRepGroupFilledDepths(groupPath, group).any { (depth, prefixes) -> rowReps.take(depth) in prefixes }

        override fun star(path: String, bindDepth: Int): List<Val> =
            starRows(path, bindDepth).mapNotNull { reps -> valueAt(path, reps)?.value?.let { resolveValue(path, it) } }

        // Row-aligned: one entry per group row — empty → the kind's empty value (number → 0), malformed → Unknown.
        override fun starAll(path: String, bindDepth: Int): List<Val> =
            starRows(path, bindDepth).map { reps -> valueAt(path, reps)?.value?.let { resolveValue(path, it) } ?: emptyValue(kindOf(path)) }

        // Reps-aware (for the cascade overlay): one entry per group row, null = empty cell, malformed = Unknown.
        override fun starCells(path: String, bindDepth: Int): List<Pair<List<Int>, Val?>> =
            starRows(path, bindDepth).map { reps -> reps to valueAt(path, reps)?.value?.let { resolveValue(path, it) } }

        // COMPUTE-mode read states (IF85): the precise formal check, so a well-formed day-omitted date (which
        // RESOLVES Unknown for a direct reference) reads FILLED, not INVALID — only real invalidity poisons.
        override fun cellState(path: String): CellState {
            val raw = placeOf(path)?.value ?: return CellState.EMPTY
            return if (formallyInvalid(path, raw)) CellState.INVALID else CellState.FILLED
        }

        override fun starScanStates(path: String, bindDepth: Int): List<CellState> = starRows(path, bindDepth).map { reps ->
            val raw = valueAt(path, reps)?.value
            when {
                raw == null -> CellState.EMPTY
                formallyInvalid(path, raw) -> CellState.INVALID
                else -> CellState.FILLED
            }
        }

        override fun starScanCells(path: String, bindDepth: Int, declaredRange: Boolean): Sequence<ScanCell> {
            val fieldParts = partsOf(path)
            // The DECLARED branch caps the bound prefix exactly as [groupScopePrefix] does for the instantiated
            // one — same `cap`, same meaning. It previously ignored `bindDepth` outright, although the parameter
            // is right here and the `else` branch below consumes it, so `common` was the shared prefix with the
            // EVALUATING group alone. A larger `common` binds MORE levels to this row, so the scan under-reported:
            // with a MULTI-star operand and the computation authored ON an inner starred level, every level the
            // stars say must iterate was pinned to the current row. Measured — `AllFieldsFilled(A*/B*/F)` as a
            // computation precondition with one `F` empty: both kernel strategies clear every target, and this
            // engine computed the three rows whose OWN cell was filled ([IF287]'s twentieth instance; the third
            // and last member of the `fillScope`/`containedFieldCells`/`starScanCells` family, all three of which
            // turned out to carry the same defect).
            val parentParts = partsOf(parentGroup(path))
            val cap = if (bindDepth >= 0) minOf(parentParts.size - 1, bindDepth) else parentParts.size - 1
            val common = minOf(commonPrefixLen(fieldParts, partsOf(group)), cap)
            val reps = if (declaredRange) declaredCellReps(fieldParts, common)
                else starRows(path, bindDepth).asSequence()
            return reps.map { scanCell(path, it) }
        }

        override fun starOmission(path: String, bindDepth: Int): Boolean {
            val scope = starScopePrefix(path, bindDepth)
            return index().starOmissionByScope.getOrPut(StarOmissionKey(path, bindDepth, scope)) {
                val groupParts = partsOf(parentGroup(path))
                val firstStarLevel = if (bindDepth >= 0) minOf(bindDepth, groupParts.lastIndex) else groupParts.lastIndex
                var expectedParents = listOf(scope.padWithOnes(firstStarLevel))

                // A multi-star operand reopens every repeatable level from its FIRST star downward. Missing
                // capacity at ANY of those levels makes the aggregate result fillable/omission-typed. Comparing
                // only the flattened leaf-row count with the leaf cap (the old shortcut) loses an absent outer
                // or middle branch. Count children per actual parent instead: exact even for an over-limit input,
                // O(actual rows × star depth), and never enumerates the declared Cartesian domain (IF194).
                for (level in firstStarLevel..groupParts.lastIndex) {
                    val groupPath = "/" + groupParts.take(level + 1).joinToString("/")
                    if (groupPath !in repeatableGroups) continue
                    val declared = declaredReps[groupPath] ?: Int.MAX_VALUE
                    val parentsAtLevel = expectedParents.map { it.padWithOnes(level) }
                    val actualRows = rowsWithPrefix(groupPath, scope)
                    val childCount = HashMap<List<Int>, Int>()
                    for (row in actualRows) {
                        val parent = row.take(level)
                        childCount[parent] = (childCount[parent] ?: 0) + 1
                    }
                    if (parentsAtLevel.any { (childCount[it] ?: 0) < declared }) return@getOrPut true
                    expectedParents = actualRows
                }

                starRows(path, bindDepth).any { reps -> valueAt(path, reps)?.value == null }
            }
        }

        private fun List<Int>.padWithOnes(size: Int): List<Int> =
            if (this.size >= size) take(size) else this + List(size - this.size) { 1 }

        override fun displayValue(path: String): String? = placeOf(path)?.value

        override fun messageDisplayValue(path: String): String? = placeOf(path)?.raw

        override fun declaredKind(path: String): Kind? = kinds[path]

        override fun dateFormatOf(path: String): String? = dateFormats[path]

        override fun datePrecisionOf(path: String): DatePrecision =
            datePrecisions[path] ?: DatePrecision.FULL

        /**
         * SemanticIndex `[F For "v"]`: the value of [targetPath] in the in-scope row whose sibling [indexField]
         * cell equals [indexValue]. The kernel's `IndexFieldCache` analog — an `indexValue → row` memo built
         * ONCE per (indexField, star-scope prefix) at the DOCUMENT level ([Index.indexRowsByValue]) and shared
         * across every reference, every rule, and every ROW CONTEXT of the scope (only a unique admitted key
         * selects) — a per-context memo would duplicate the whole-list map per row since IF93 (O(rows²) memory).
         * Rows of the index field's group == `starRows(targetPath)` (same group, sibling leaves), and the row
         * reps address the leaf for any sibling, so the map is shared across sibling targets too.
         */
        override fun indexedCell(targetPath: String, indexField: String, indexValue: String): Val? =
            indexedCell(targetPath, indexField, indexValue, null)

        override fun indexedCell(
            targetPath: String,
            indexField: String,
            indexValue: String,
            indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)?,
        ): Val? {
            val reps = indexedCellReps(targetPath, indexField, indexValue, indexIdentityAdmitted) ?: return null
            return indexedValueAt(targetPath, reps)
        }

        override fun indexedValueAt(targetPath: String, repetitions: List<Int>): Val? {
            val raw = valueAt(targetPath, repetitions)?.value
            return if (!raw.isNullOrEmpty()) resolveValue(targetPath, raw) else null
        }

        override fun indexedCellRepetitions(
            targetPath: String,
            indexField: String,
            indexValue: String,
            indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)?,
        ): List<Int>? = indexedCellReps(targetPath, indexField, indexValue, indexIdentityAdmitted)

        private fun indexedCellReps(
            targetPath: String,
            indexField: String,
            indexValue: String,
            indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)? = null,
        ): List<Int>? {
            val kind = declaredKind(indexField)
            val indexGroup = parentGroup(indexField)
            val indexDepth = partsOf(indexGroup).size
            val scope = groupScopePrefix(indexGroup, bindDepth = -1)
            val byValue = index().indexRowsByValue.getOrPut(indexField to scope) {
                val m = HashMap<String, MutableList<List<Int>>>()
                val overLimit = overLimitRows(indexGroup)
                for (row in rowsWithPrefix(indexGroup, scope)) {
                    if (row in overLimit) continue
                    val reps = row + 1
                    val raw = valueAt(indexField, reps)?.value ?: continue
                    val key = indexKeyOf(raw, kind)
                    m.getOrPut(key) { ArrayList(1) }.add(reps)
                }
                m
            }
            val queryKey = indexKeyOf(indexValue, kind)
            val candidates = byValue[queryKey].orEmpty().mapNotNull { reps ->
                val raw = valueAt(indexField, reps)?.value ?: return@mapNotNull null
                val admitted = indexIdentityAdmitted?.invoke(indexField, reps, raw) ?: !formallyInvalid(indexField, raw)
                IndexIdentityCandidate(
                    repetitions = reps,
                    key = queryKey.takeIf { admitted },
                    compromises = !admitted,
                )
            }
            val selected = resolveIndexIdentity(candidates).uniqueRowsByKey[queryKey] ?: return null
            val currentTarget = reps(targetPath)
            return selected.take(indexDepth) + currentTarget.drop(indexDepth)
        }

        override fun indexedContainedFieldCells(
            groupPath: String,
            indexField: String,
            indexValue: String,
            indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)?,
        ): Sequence<ScanCell> {
            val selectedGroupReps = indexedCellReps(
                groupPath,
                indexField,
                indexValue,
                indexIdentityAdmitted,
            ) ?: return emptySequence()
            return RowContext(groupPath, selectedGroupReps, evaluated = true)
                .containedFieldCells(groupPath, declaredRange = false)
        }

        /** The memoized [IndexColumn] — built ONCE per (column, star-scope prefix) at the DOCUMENT level and
         *  shared across every row context of the scope (the [Index.indexColumnByScope] twin of
         *  [indexedCell]'s row map). Raw stored cells only — an overlay context rebuilds per read instead. */
        override fun indexColumn(path: String, bindDepth: Int): IndexColumn =
            index().indexColumnByScope.getOrPut(path to starScopePrefix(path, bindDepth)) {
                IndexColumn(starCells(path, bindDepth))
            }

        // Rows of groupPath in this row's SCOPE (the Having filter's per-row contexts). An aggregate/quantifier
        // over groupPath spans all rows under groupPath's PARENT (the common ancestor) — so scope to the
        // parent-rep prefix, NOT the full rowReps. This matters when THIS context is itself a row of groupPath
        // (a rule iterating the same group the aggregate ranges over — the `$` outer-correlation case): scoping
        // to the full rowReps would wrongly return only this one row. A DEEPER (nested) aggregate keeps the full
        // prefix (take(n) with n ≥ this depth ≡ rowReps), so it stays scoped to this row — unchanged.
        override fun rowContexts(groupPath: String, bindDepth: Int): List<EvalContext> =
            rowContextsScoped(groupPath, groupScopePrefix(groupPath, bindDepth))

        /**
         * Reuse the first-star row topology, but choose its deepest repeatable ANCESTOR environment when the
         * addressed terminal is non-repeatable. Resolving [groupPath] from that row keeps the complete ancestry,
         * so [GroupPresenceState] remains the sole terminal product owner (SPEC-2026-07-26-02).
         */
        override fun declaredGroupRepetitions(groupPath: String): Int? = declaredReps[groupPath]

        override fun groupListEnvironments(groupPath: String, bindDepth: Int): List<EvalContext> {
            var environment = groupPath
            while (environment !in repeatableGroups && partsOf(environment).size > bindDepth) {
                environment = parentGroup(environment)
            }
            return rowContextsScoped(environment, groupScopePrefix(environment, bindDepth))
        }

        // The declared fields under a group path, recursively and in model declaration order. Numeric `Sum`
        // observes this order because every addition rounds at precision 50; compute scans share this same plan.
        override fun containedFields(groupPath: String): List<String> =
            index().containedFieldsByGroup.getOrPut(groupPath) {
                kinds.keys.filter { it.startsWith("$groupPath/") }
            }

        // [bindDepth] widens the bucket scope for a MULTI-star operand (IF144): −1 keeps the single-star leaf-parent
        // scope; a multi-star first-star bind depth caps the shared prefix higher, so the buckets span every A's
        // B-rows (the same rows `rowContexts(groupPath, bindDepth)` — the naive scan — iterates).
        override fun correlationBuckets(groupPath: String, joinFields: List<String>, starPath: String, bindDepth: Int): Map<List<Val>, IntArray> =
            correlationBucketsScoped(groupPath, groupScopePrefix(groupPath, bindDepth), joinFields, starPath)

        override fun orderedCorrelation(
            groupPath: String,
            innerField: String,
            starPath: String,
            bindDepth: Int,
        ): OrderedCorrelation =
            orderedCorrelationScoped(groupPath, groupScopePrefix(groupPath, bindDepth), innerField, starPath)

        // The scope key an overlay-aware derived-view cache shares across outer rows (IF143) — the SAME prefix the
        // raw indexColumnByScope / correlationBucketCache key on, so the overlay's per-step cache matches this row's view.
        override fun scopePrefixOf(groupPath: String, bindDepth: Int): List<Int> = groupScopePrefix(groupPath, bindDepth)

        // The levels this row fixes for [path]: its shared named ancestry with the iterated group, capped by the
        // coordinates the row actually carries. Everything deeper is defaulted by [repsFor], not chosen.
        override fun boundLevelsOf(path: String): Int =
            minOf(commonPrefixLen(partsOf(path), partsOf(group)), rowReps.size)

        // A group-scoped / starred-field fill quantifier — "the fields inside the group" (recursively, the kernel
        // ExpandService → getAllContainedFields), across the two kernel ranges (IF49). See [EvalContext.fillScope].
        override fun fillScope(scopePath: String, isGroup: Boolean, bindDepth: Int): FillScope {
            val descendants = if (isGroup) kinds.keys.filter { it.startsWith("$scopePath/") }.sorted()
                else listOf(scopePath)
            var t = 0; var f = 0; var n = 0; var unknown = false
            var allFilled = true; var anyEmpty = false
            // The bound prefix is capped at the OPERAND'S OWN PATH depth. For an UNSTARRED group operand that is
            // the same quantity `ast.expansionBindDepth` yields; for a STARRED one it is one level deeper, because
            // this seam takes no bindDepth at all — the same structural gap as the missing `having` one branch up
            // — so the star's own level stays bound here while `expandGroupStars` frees it. That difference is
            // UNMEASURED and is recorded in [IG136]; the original comment claimed the two were the same rule, which
            // a cold review corrected. Without the cap, `common` was the shared prefix with the EVALUATING group
            // alone, so a rule authored ON a repeatable level below the operand already had that level inside
            // `common`; `multi` then read false and `instCells` returned the single current row's cell. Measured
            // from a rule on the nested level with the operand naming its ancestor: `AllFieldsFilled` fired on the
            // row whose own cells were complete where the kernel fires on none, and `NotAllFieldsFilled` moved the
            // opposite way — two fire sets displacing in opposite directions, which is the signature of a scope
            // pin rather than of a wrong verdict ([IF287]; `GroupScopeFromADeepRuleRowDiffTest`). A FIELD scope is
            // unaffected: `scopePath` is then the field itself, so the cap is never the binding term.
            // `bindDepth >= 0` is a STARRED operand's own binding level — its `*`-marked level must ITERATE, so
            // the cap is the star's parent and not the operand's path depth. That distinction was missing while
            // this seam took no bindDepth at all: from a rule authored ON the starred level, `AllFieldsFilled(G*)`
            // bound `G`'s own row and fired where both kernel strategies fire on nothing, while the SAME operand
            // under `NumberOfFilledFields` went through `expandGroupStars` and spanned every row. Two channels of
            // one operand disagreeing about its extent is this class's cheapest tell ([IF287]'s twenty-first
            // instance). `-1` keeps the unstarred group's path-depth cap, which is what that shape needs.
            val scopeDepth = if (bindDepth >= 0) bindDepth else partsOf(scopePath).size
            for (field in descendants) {
                val parts = partsOf(field)
                val common = minOf(commonPrefixLen(parts, partsOf(group)), scopeDepth)
                for (v in instCells(field, parts, common)) {           // INSTANTIATED tally
                    n++
                    when {
                        v == null -> f++
                        v == Val.Unknown -> unknown = true
                        else -> t++
                    }
                }
                val (af, ae) = declaredFlags(field, parts, common)     // DECLARED-range flags
                allFilled = allFilled && af
                anyEmpty = anyEmpty || ae
            }
            return FillScope(t, f, n, unknown, allFilled, anyEmpty)
        }

        // The contained fields in declaration order, then each field's repetitions in row order. Both the field
        // plan and every cell lookup are lazy: a quantifier witness prevents all later reads/allocation. The
        // declared-range form interleaves a field's uninstantiated repetitions BEFORE the next field (RV04-1).
        override fun containedFieldCells(groupPath: String, declaredRange: Boolean): Sequence<ScanCell> = sequence {
            val fields = containedFields(groupPath)
            // Capped at the OPERAND'S OWN depth, exactly as [fillScope] is — this is that fix's sibling site, and
            // a cold review caught it unswept. Uncapped, `common` is the shared prefix with the EVALUATING group
            // alone, so a rule or computation authored ON a repeatable level below the operand already has that
            // level inside `common` and the scan collapses to the single current row. The two callers that reach
            // it are the compute-path bare-group fill scan and `FirstFilledValue`'s group operand, so the same pin
            // that displaced two validation fire sets in opposite directions was live on both of those.
            val scopeDepth = partsOf(groupPath).size
            for (field in fields) {
                val fieldParts = partsOf(field)
                val common = minOf(commonPrefixLen(fieldParts, partsOf(group)), scopeDepth)
                val reps = if (declaredRange) declaredCellReps(fieldParts, common)
                    else instantiatedCellReps(field, fieldParts, common)
                for (r in reps) yield(scanCell(field, r))
            }
        }

        private fun scanCell(path: String, reps: List<Int>): ScanCell {
            val raw = valueAt(path, reps)?.value
            val state = when {
                raw == null -> CellState.EMPTY
                formallyInvalid(path, raw) -> CellState.INVALID
                else -> CellState.FILLED
            }
            return ScanCell(path, reps, raw, state, raw?.let { resolveValue(path, it) })
        }

        private fun instantiatedCellReps(
            field: String,
            fieldParts: List<String>,
            common: Int,
        ): Sequence<List<Int>> {
            val repeated = (common until fieldParts.size - 1).any {
                "/" + fieldParts.take(it + 1).joinToString("/") in declaredReps
            }
            return if (repeated) starRows(field).asSequence() else sequenceOf(reps(field))
        }

        /** Full declared cell identities for one field, scoped to this row and yielded repetition-major. */
        private fun declaredCellReps(fieldParts: List<String>, common: Int): Sequence<List<Int>> = sequence {
            suspend fun SequenceScope<List<Int>>.visit(level: Int, prefixReps: List<Int>) {
                if (level == fieldParts.size) {
                    yield(prefixReps)
                    return
                }
                val prefix = "/" + fieldParts.take(level + 1).joinToString("/")
                when {
                    level < common -> visit(level + 1, prefixReps + rowReps[level])
                    prefix in declaredReps -> {
                        for (rep in 1..declaredReps.getValue(prefix)) visit(level + 1, prefixReps + rep)
                    }
                    else -> visit(level + 1, prefixReps + 1)
                }
            }
            visit(0, emptyList())
        }

        /** The INSTANTIATED cells of a descendant [field]: one cell (its scope reps) when no repeatable level lies
         *  below the scope, else one per instantiated rep-tuple. Each entry is the resolved value, or null (empty). */
        private fun instCells(field: String, parts: List<String>, common: Int): List<Val?> {
            val multi = (common until parts.size - 1).any { "/" + parts.take(it + 1).joinToString("/") in declaredReps }
            val cellReps = if (multi) starRows(field) else listOf(reps(field))
            return cellReps.map { r -> valueAt(field, r)?.value?.let { resolveValue(field, it) } }
        }

        /** Over the FULL DECLARED repetition range of [field] in this row's scope: `(allFilledValid, anyEmpty)`. An
         *  un-instantiated declared rep reads empty. Iterates 1..declaredMax at each repeatable level below the
         *  scope (shared-prefix levels fixed to this row); short-circuits once an empty cell is found. */
        private fun declaredFlags(field: String, parts: List<String>, common: Int): Pair<Boolean, Boolean> {
            var allFilled = true
            var anyEmpty = false
            fun rec(level: Int, repsSoFar: List<Int>) {
                if (anyEmpty) return                                   // settled (anyEmpty ⟹ !allFilled)
                if (level == parts.size) {
                    when (valueAt(field, repsSoFar)?.value?.let { resolveValue(field, it) }) {
                        null -> { anyEmpty = true; allFilled = false }
                        Val.Unknown -> allFilled = false               // present-but-malformed: not filled, not empty
                        else -> {}
                    }
                    return
                }
                val prefix = "/" + parts.take(level + 1).joinToString("/")
                when {
                    level < common -> rec(level + 1, repsSoFar + rowReps[level])
                    prefix in declaredReps -> {
                        val max = declaredReps.getValue(prefix)
                        var r = 1
                        while (r <= max && !anyEmpty) { rec(level + 1, repsSoFar + r); r++ }
                    }
                    else -> rec(level + 1, repsSoFar + 1)
                }
            }
            rec(0, emptyList())
            return allFilled to anyEmpty
        }

        // The data view a CustomCondition reads (the kernel's IData): document-wide raw-value lookup by
        // (path, indices) and the set of present field instances — a custom condition's IData spans the whole
        // document, the current row is carried by the error-field pointer (not row-scoped here).
        override fun customConditionData(): CustomConditionData = object : CustomConditionData {
            override fun getValue(field: CustomConditionFieldPointer): String? = valueAt(field.path, field.indices)?.value
            override fun fields(): List<CustomConditionFieldPointer> = index().valued.map { CustomConditionFieldPointer(it.path, it.reps) }
        }

        override fun enumCategory(path: String, category: String): Val =
            enumCategoryOf(path, category, valueAt(path, reps(path))?.value)

        override fun enumCategoryValue(path: String, category: String, value: Val): Val =
            enumCategoryOf(path, category, (value as? Val.Txt)?.v)

        override fun repsOf(path: String): List<Int> = reps(path)

        /** Index tuples of [starPath] across every row of its deepest repeatable group ancestor in scope. The
         *  in-scope rows are fetched by prefix in O(1) (via the [Index] prefix index), not by an O(all-rows)
         *  filter — so a semantic-index / cross-level star resolved per row stays linear, not O(rows²). */
        // The bound-row prefix of a starred FIELD path read from this row — the leaf-group form of
        // [groupScopePrefix] (one function owns the anchor rule; this adapter only drops the field segment).
        private fun starScopePrefix(starPath: String, bindDepth: Int = -1): List<Int> =
            groupScopePrefix(parentGroup(starPath), bindDepth)

        // The bound-row prefix for iterating [groupPath]'s rows from this row — the `*`-marked level ALWAYS
        // iterates fully; only levels STRICTLY ABOVE the starred group bind to this row's indices, so the
        // shared prefix is capped at the starred group's PARENT (IF93) — earlier for a MULTI-star operand (the
        // FIRST `*`-marked level's parent, [bindDepth], IF94) — and ONLY along the SHARED named ancestry
        // (`commonPrefixLen`): a CROSS-SUBTREE star must not inherit this row's indices POSITIONALLY (from
        // `Claims[2]`, a `/Settings/Regions*` scope prefix `[1,2]` addressed the nonexistent `Settings[2]` →
        // 0 rows — under-fired counts/presence, over-fired `NoGroupFilled`; the first kernel-mm divergence).
        // A bare same-group or ancestor star from an iterating row spans ALL rows — `$` (inside a Having) is
        // the ONLY outer-row correlation (KERNEL-SEMANTICS §9); a star NESTED BELOW this row keeps binding
        // this row's indices (common < the cap) — unchanged.
        private fun groupScopePrefix(groupPath: String, bindDepth: Int): List<Int> {
            val parts = partsOf(groupPath)
            val cap = if (bindDepth >= 0) minOf(parts.size - 1, bindDepth) else parts.size - 1
            return rowReps.take(minOf(commonPrefixLen(parts, partsOf(group)), cap))
        }

        private fun starRows(starPath: String, bindDepth: Int = -1): List<List<Int>> {
            val group = parentGroup(starPath)
            val overLimit = overLimitRows(group)   // over-limit rows are outside the star domain (SPEC-2026-07-23-13)
            return rowsWithPrefix(group, starScopePrefix(starPath, bindDepth))
                .filter { it !in overLimit }
                .map { it + 1 }   // append the field's own index (1) to address the leaf
        }
    }

    /**
     * One iteration of a [parallelContexts] outer join: the common-parent row [cReps] and, per parallel group,
     * the matched row reps in [matches] (a `null` entry = no row for this index value → the group counts as
     * "not specified"). A field reference resolves through whichever parallel group it belongs to — empty when
     * that group has no matching row, with the group's pointer level set to [UNKNOWN_REP]; a reference outside
     * the parallel groups resolves relative to [commonParent], the common-prefix way ([repsFor]).
     */
    private inner class ParallelRowContext(
        private val commonParent: String,
        private val cReps: List<Int>,
        private val groups: List<String>,
        private val parentReps: Map<String, List<Int>>,
        private val matches: Map<String, List<Int>?>,
        private val invalidIndexGroups: Set<String>,
    ) : EvalContext {

        // A row over a present index value is always evaluated (the value exists in at least one group).
        override val evaluated: Boolean = true

        /**
         * Everything below is memoized by path for this row, exactly as [RowContext] memoizes `reps`/`placeOf`
         * and for the same measured reason — this class simply never got it. `groupOf`, `repsOf` and the
         * placement are deterministic in the path plus this context's immutable fields, and each is asked many
         * times per row: once per referenced field, again for the error pointer, again for the formal-cache key,
         * again by the suppressor scan, and `field`/`filled`/`displayValue` each resolve independently. Profiled
         * on `PF-framed`, `repsOf` was 63.6 % of samples inclusive and the biggest single self cost in the whole
         * run was the `ArrayList.add` behind its per-call `List(segs.size)` (16.7 %). Stable for the same reason
         * [RowContext]'s caches are: the document's structure is immutable for the length of a call, and an
         * overlay changes values rather than rows.
         */
        private val groupCache = HashMap<String, String?>()
        private val repsCache = HashMap<String, List<Int>>()
        private val placeCache = HashMap<String, Placement?>()

        /** Group depths, once per context rather than a path split per lookup — [groups] is fixed here. */
        private val groupDepths: Map<String, Int> = groups.associateWith { partsOf(it).size }

        /** The parallel group [path] belongs to (itself or a descendant), or null when [path] is outside them. */
        private fun groupOf(path: String): String? {
            if (path in groupCache) return groupCache[path]
            return groups.firstOrNull { path == it || path.startsWith("$it/") }
                .also { groupCache[path] = it }
        }

        /** Whether [path]'s group has no row for the current index value (the outer-join "not specified" side). */
        private fun unmatched(path: String): Boolean = groupOf(path)?.let { matches[it] == null } ?: false

        override fun repsOf(path: String): List<Int> = repsCache.getOrPut(path) { computeReps(path) }

        private fun computeReps(path: String): List<Int> {
            val g = groupOf(path) ?: return repsFor(path, commonParent, cReps)
            val gDepth = groupDepths.getValue(g)
            val parent = parentReps.getValue(g)   // the group's join parent: a frame row, else the common parent
            val matched = matches[g]
            val segs = partsOf(path)
            return List(segs.size) { i ->
                when {
                    i < parent.size -> parent[i]            // the group's parent prefix (frame / common-parent — real)
                    i >= gDepth -> 1                        // the field / deeper level
                    matched != null -> matched[i]           // a matched row carries the real index at every level
                    // Unmatched (no row for this value): a REPEATABLE level is UNKNOWN (-5); a non-repeatable
                    // intermediate wrapper is always 1 (the kernel renders `…/W1[1]/A[-5]/…`, not `W1[-5]`).
                    "/" + segs.take(i + 1).joinToString("/") in declaredReps -> UNKNOWN_REP
                    else -> 1
                }
            }
        }

        // The join binds each participating group's own level, so a path inside a MATCHED group is bound down
        // to that group's depth; anything else is bound only by the shared common-parent ancestry. An UNMATCHED
        // group has no row for this value, so its own level was never chosen — [repsOf] reports the kernel's
        // UNKNOWN repetition there, and the gate must not ask the relevant set to name a repetition that does
        // not exist. Same principle as [IF268] one step further: what the iteration did not choose is not
        // compared, whether because the level sits below it or because the join found nothing.
        override fun boundLevelsOf(path: String): Int {
            val g = groupOf(path) ?: return commonPrefixLen(partsOf(path), partsOf(commonParent))
            val bound = if (matches[g] == null) parentReps.getValue(g).size else groupDepths.getValue(g)
            return minOf(bound, partsOf(path).size)
        }

        /** The placement of [path] in this iteration, resolved once — `resolved`, `resolvedDisplay`, `field`,
         *  `filled` and `displayValue` otherwise repeat the same reps walk and map lookup independently. */
        private fun placeOf(path: String): Placement? {
            if (path in placeCache) return placeCache[path]
            val reps = repsOf(path)
            val place = if (reps.any { it == UNKNOWN_REP }) null else valueAt(path, reps)
            placeCache[path] = place
            return place
        }

        /** The raw value of [path] in this iteration, or null when its group has no matching row (or it is empty). */
        private fun resolved(path: String): String? = placeOf(path)?.value

        private fun resolvedDisplay(path: String): String? = placeOf(path)?.raw

        // An UNMATCHED reference into an INVALID-index group reads as 3VL UNKNOWN (not empty) — the kernel's
        // invalid-index behavior (KERNEL-FINDINGS §9), routed through [presenceUnknown] for presence predicates
        // and Val.Unknown here for value comparisons. A valid group's unmatched ref is the kind's empty value.
        override fun field(path: String): Val = when {
            unmatched(path) -> if (groupOf(path) in invalidIndexGroups) Val.Unknown else emptyValue(kindOf(path))
            else -> resolved(path)?.let { resolveValue(path, it) } ?: emptyValue(kindOf(path))
        }

        override fun filled(path: String): Boolean = resolved(path) != null

        override fun presenceUnknown(path: String): Boolean = unmatched(path) && groupOf(path) in invalidIndexGroups

        override fun displayValue(path: String): String? = resolved(path)

        override fun messageDisplayValue(path: String): String? = resolvedDisplay(path)

        override fun signed(path: String): Boolean = path !in unsignedFields

        override fun declaredKind(path: String): Kind? = kinds[path]

        override fun dateFormatOf(path: String): String? = dateFormats[path]

        override fun datePrecisionOf(path: String): DatePrecision =
            datePrecisions[path] ?: DatePrecision.FULL

        // A parallel group is "filled" for this iteration iff it has a matching row.
        override fun groupFilled(groupPath: String): Boolean =
            if (groupPath in groups) matches[groupPath] != null
            else throw UnsupportedConstructException(
                "group-filled on a non-parallel group ($groupPath) inside a parallel iteration is not modeled")

        override fun enumCategory(path: String, category: String): Val =
            enumCategoryOf(path, category, resolved(path))

        override fun enumCategoryValue(path: String, category: String, value: Val): Val =
            enumCategoryOf(path, category, (value as? Val.Txt)?.v)

        // Aggregates / filters / semantic index / custom conditions are NOT valid inside a parallel iteration
        // (KERNEL-SEMANTICS §9: a filter condition may not contain a parallel iteration, and vice versa) — fail
        // loud rather than risk a silently-wrong answer.
        override fun star(path: String, bindDepth: Int): List<Val> = unsupportedInParallel()
        override fun starOmission(path: String, bindDepth: Int): Boolean = unsupportedInParallel()
        override fun starScanCells(path: String, bindDepth: Int, declaredRange: Boolean): Sequence<ScanCell> =
            unsupportedInParallel()
        override fun containedFieldCells(groupPath: String, declaredRange: Boolean): Sequence<ScanCell> =
            unsupportedInParallel()
        override fun indexedContainedFieldCells(
            groupPath: String,
            indexField: String,
            indexValue: String,
            indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)?,
        ): Sequence<ScanCell> = unsupportedInParallel()
        private fun unsupportedInParallel(): Nothing =
            throw UnsupportedConstructException("an aggregate/filter inside a parallel iteration is not modeled")
    }

    /**
     * Rows of [repGroup] whose rep tuple starts with [prefix] — O(1) via the per-group all-prefixes index
     * (built once, dropped on mutation with the [Index]). Replaces the O(all-rows) scan that made per-row
     * star/semantic-index resolution O(rows²). Document order is preserved (the index is built by iterating
     * [rowsOf] in order).
     */
    private fun rowsWithPrefix(repGroup: String, prefix: List<Int>): List<List<Int>> {
        val byPrefix = index().prefixRowsByGroup.getOrPut(repGroup) {
            val m = HashMap<List<Int>, MutableList<List<Int>>>()
            for (row in rowsOf(repGroup)) for (k in 0..row.size) m.getOrPut(row.take(k)) { ArrayList() }.add(row)
            m
        }
        return byPrefix[prefix] ?: emptyList()
    }

    /**
     * The rows of [groupPath] that are OVER-LIMIT — some group ancestor level's 1-based index exceeds its
     * effective maximum (the kernel's `checkIndicesLessOrEqualThanMax` walk, SPEC-2026-07-23-13).
     * The kernel does NOT insert an over-limit cell/group into its evaluation cache, so such a row is OUTSIDE every
     * star aggregate / quantifier / filter domain — a `Sum`/`NumberOfFilledFields`/`NumberOfFilledGroups(G*)` ranges
     * over the in-cap (declared-range) rows only. It stays instantiated for per-row rule suppression and structural
     * over-repetition emission, which read [rowsOf] directly (unfiltered). Decided from the SAME
     * [effectiveGroupRepetitionMaximums] the emission gate
     * ([io.github.mbackschat.a12.dm.interpreter.ModelPathIndex.exceededBound]) uses — one address-bound fact, now
     * THREE readers (this one, `exceededBound`, and its hoisted twin `anyExceedsBound`) — so the star-domain bound
     * and the emitted over-repetition codes can never disagree. The two `ModelPathIndex` walks are held in
     * agreement by `OverLimitAddressPresenceMultiplatformTest`; this one is a separate implementation reading the
     * DOCUMENT's own configured maximums rather than the model's, which is pre-existing and outside that lock.
     * Computed once per group (empty for a well-formed document → the exclusion is a no-op) and memoized with the [Index].
     */
    private fun overLimitRows(groupPath: String): Set<List<Int>> {
        if (effectiveGroupRepetitionMaximums.isEmpty()) return emptySet()
        return index().overLimitRowsByGroup.getOrPut(groupPath) {
            val parts = partsOf(groupPath)
            val bounds = parts.indices.mapNotNull { level ->
                effectiveGroupRepetitionMaximums["/" + parts.take(level + 1).joinToString("/")]
                    ?.let { level to it }
            }
            if (bounds.isEmpty()) emptySet()
            else rowsOf(groupPath).filterTo(HashSet()) { reps ->
                bounds.any { (level, max) -> level < reps.size && reps[level] > max }
            }
        }
    }

    /** Whether [reps] belongs to the kernel-equivalent evaluation/index cache for [groupPath]. Over-limit rows
     * remain instantiated for formal reporting but never reach an index identity relation. */
    internal fun indexCacheContainsRow(groupPath: String, reps: List<Int>): Boolean =
        reps !in overLimitRows(groupPath)

    private fun kindOf(path: String): Kind = kinds[path] ?: error("no kind declared for $path")

    /**
     * EnumCategory `[field -> Category]`: map an enum field's [raw] stored value to its [category] value by its
     * POSITION in the enum's value list. An empty enum / an out-of-enum value / no such category yields no
     * comparison value (Unknown → the rule does not fire / is suppressed, matching the kernel's empty-string
     * compare / formal-error-suppression on a value not in the enumeration). The shared core of both row
     * contexts' `enumCategory` — [RowContext] and [ParallelRowContext] differ ONLY in how they read the raw
     * value (bound-reps lookup vs the parallel-join resolution), which each passes in as [raw].
     */
    private fun enumCategoryOf(path: String, category: String, raw: String?): Val {
        if (raw.isNullOrEmpty()) return Val.Unknown
        val idx = (enumValues[path] ?: return Val.Unknown).indexOf(raw)
        if (idx < 0) return Val.Unknown
        return enumCategories[path]?.get(category)?.getOrNull(idx)?.let { Val.Txt(it) } ?: Val.Unknown
    }

    /**
     * A present raw value → typed [Val], format-aware. A [Kind.DATE_RANGE] value is parsed to a [Val.Range] of
     * ISO dates via its field's `(format, separator)` ([rangeConfig]); a [Kind.DATE] value is canonicalized to
     * ISO via its field's `format` ([dateFormats], ISO default) — so a non-ISO `dd.MM.yyyy` field's `15.06.2024`
     * becomes `2024-06-15` and the extractors/comparisons read it like any date. A [Kind.DATE_TIME] value is
     * likewise canonicalized to ISO `yyyy-MM-dd'T'HH:mm:ss` via its field's `format` ([dateTimeFormats]) so it
     * compares chronologically against `Now`. All are locale-variant by
     * construction (the format is read, not hardcoded); a malformed value → [Val.Unknown] (the referencing rule
     * is suppressed, and the document-wide formal check reports it). Other kinds go through the plain [typedValue].
     */
    /** Whether a PRESENT raw value at [path] is **formally invalid** (not check-relevant) — the [resolveValue]
     *  gate, and the COMPUTE-mode poison trigger ([EvalContext.cellState], IF85). Format + declared constraints,
     *  the full per-cell check. */
    private fun formallyInvalid(path: String, raw: String): Boolean {
        val kind = kindOf(path)
        val precision = datePrecisions[path] ?: DatePrecision.FULL
        // The field's date-ish format for the formal check — DATE/TIME/DATE_TIME/DATE_RANGE each from their own
        // map (so a malformed TIME/DATE_TIME/range also resolves to Unknown, not just DATE; KERNEL-FINDINGS §6).
        val dateLikeFormat = when (kind) {
            Kind.DATE -> dateFormats[path]
            Kind.TIME -> timeFormats[path]
            Kind.DATE_TIME -> dateTimeFormats[path]
            Kind.DATE_RANGE -> rangeConfig[path]?.first
            else -> null
        }
        return formalError(kind, raw, scales[path], dateLikeFormat, precision, rangeConfig[path]?.second,
            fieldConstraints[path] ?: FieldConstraints.NONE, baseYear = baseYear ?: 2000, timeZone = timeZone,
            rangeYearInterpretation = yearInterpretationOf(path)) != null
    }

    /** The DATE_RANGE field's declared `interpretationOfYear`, STANDARD when it declares none. */
    private fun yearInterpretationOf(path: String): RangeYearInterpretation =
        rangeYearInterpretations[path] ?: RangeYearInterpretation.STANDARD

    private fun resolveValue(path: String, raw: String): Val {
        val kind = kindOf(path)
        // A formally-invalid value is "unknown" EVERYWHERE — a direct ref, an aggregate element, a computation
        // operand — matching the kernel (a formal error makes the value non-evaluable). This catches values
        // that parse but are still invalid: signed-zero `-0`, leading-plus `+5`, too-many-decimals. Without
        // this, an aggregate would silently fold a `-0` as 0 and fire where the kernel suppresses.
        if (formallyInvalid(path, raw)) return Val.Unknown
        val precision = datePrecisions[path] ?: DatePrecision.FULL
        return when (kind) {
            // A FRAGMENT-format range (`yyyy` year-range) completes its two endpoints ASYMMETRICALLY —
            // start to the interval's earliest date, end to its latest (IF118) — so a rule reading it
            // evaluates instead of going Unknown; a full-format range is unchanged. The base year fills a
            // year-less fragment endpoint (a `MM`/`MM-dd` range), matching the kernel's completion.
            Kind.DATE_RANGE -> rangeConfig[path]?.let { (format, sep) ->
                parseRangeToIso(raw, format, sep, baseYear ?: 2000, yearInterpretationOf(path))
                    ?.let { Val.Range(it.first, it.second) }
            } ?: Val.Unknown
            // A DATE field canonicalizes to ISO, precision-aware:
            //  - FRAGMENT: the format carries only some components — complete the rest to the earliest concrete
            //    date (missing month/day → 01, missing year → base year), so it compares/shifts/extracts like a
            //    plain date (KERNEL-FINDINGS §6 "a date fragment IS a DateOperand"; the kernel's feldWertAlsDatum).
            //  - partial DateType: a value carrying any admitted zero component is well-formed but not a single
            //    concrete date → Unknown for a direct reference (`ValueAsDate` selects its boundary).
            //  - FULL: a plain full date.
            Kind.DATE -> {
                val fmt = dateFormats[path] ?: "yyyy-MM-dd"
                when {
                    precision == DatePrecision.FRAGMENT ->
                        completeFragmentToIso(raw, fmt, baseYear ?: 2000)?.let { Val.Txt(it) } ?: Val.Unknown
                    precision == DatePrecision.DAY_OPTIONAL ||
                        precision == DatePrecision.MONTH_OPTIONAL ||
                        precision == DatePrecision.YEAR_OPTIONAL -> {
                        val parts = partialDateParts(raw, fmt, precision) ?: return Val.Unknown
                        if (parts.first == 0 || parts.second == 0 || parts.third == 0) Val.Unknown
                        else Val.Txt(isoDate(parts.first, parts.second, parts.third))
                    }
                    // A FULL-precision DateType whose declared FORMAT omits components (`yyyy`, `yyyy-MM`,
                    // `yyyyMM`, `MM` — KF176) completes the missing ones exactly as a FRAGMENT does: month and
                    // day to 01, a missing year to the model's base year (measured against a non-default base
                    // year, so it is the base year and not a constant). The formal gate above admits such a
                    // cell, so resolution MUST produce a value for it — returning Unknown here made every
                    // referencing rule silently non-evaluable while the cell was perfectly legal (IG110).
                    else -> (parseDateToIso(raw, fmt) ?: completeFragmentToIso(raw, fmt, baseYear ?: 2000))
                        ?.let { Val.Txt(it) } ?: Val.Unknown
                }
            }
            // A DATE_TIME canonicalizes by whichever of the kind's TWO legal declared formats its field carries:
            // the ISO `yyyy-MM-dd'T'HH:mm:ss`, or the degenerate time-only `HH:mm:ss` — which omits every
            // calendar component and canonicalizes to `HH:mm:ss`, comparing exactly like a TIME cell. The
            // formal gate above admits such a cell, so resolution MUST produce a value for it; returning
            // Unknown made every referencing rule silently non-evaluable (IF301, the DATE_TIME sibling of the
            // IG110/IF250 class its sweep had ruled out).
            Kind.DATE_TIME -> parseDeclaredDateTime(raw, dateTimeFormats[path] ?: "yyyy-MM-dd'T'HH:mm:ss")
                ?.let { Val.Txt(it) } ?: Val.Unknown
            else -> typedValue(kind, raw)
        }
    }

    private companion object {
        fun parts(path: String): List<String> = path.trim('/').split('/')

        fun commonPrefixLen(a: List<String>, b: List<String>): Int {
            var n = 0
            while (n < a.size && n < b.size && a[n] == b[n]) n++
            return n
        }

        /** `/A/B/F` → `[/A, /A/B]`. */
        fun ancestorGroups(fieldPath: String): List<String> {
            val p = parts(fieldPath)
            return (1 until p.size).map { "/" + p.take(it).joinToString("/") }
        }

        /** The immediate parent group path of a field, e.g. `/Order/Items/Count` → `/Order/Items`. */
        fun parentGroup(fieldPath: String): String {
            val p = parts(fieldPath)
            return "/" + p.take(p.size - 1).joinToString("/")
        }
    }
}

/** One stored placement as the transport encoder sees it: [raw] is the exact lexical text for a field
 *  (empty string = present-empty as authored, null = present-but-valueless), and always null for a group row. */
internal data class DocumentPlacementRecord(
    val path: String,
    val reps: List<Int>,
    val isGroup: Boolean,
    val raw: String?,
)

/**
 * Materializes the dense group structure that A12's V1-to-V2 runtime conversion evaluates. The high-water mark
 * is scoped by `(group path, concrete parent repetitions)`, so addressing row `n` emits only the predecessor
 * suffix not already seen. Sequential construction is therefore linear in the number of rows, including when
 * several fields are placed in every row.
 */
internal class DenseGroupTreeMaterializer(
    private val emit: (path: String, reps: List<Int>) -> Unit,
) {
    private data class Scope(val groupPath: String, val parentReps: List<Int>)
    private data class PathInfo(val coordinateCount: Int, val groupPaths: List<String>)

    private val fieldPathInfo = HashMap<String, PathInfo>()
    private val groupPathInfo = HashMap<String, PathInfo>()
    private val highestRepetitionByScope = HashMap<Scope, Int>()

    fun materialize(path: String, reps: List<Int>, kind: ModelEntityKind) {
        val pathInfo = when (kind) {
            ModelEntityKind.FIELD -> fieldPathInfo.getOrPut(path) { pathInfoOf(path, kind) }
            ModelEntityKind.GROUP -> groupPathInfo.getOrPut(path) { pathInfoOf(path, kind) }
        }
        if (reps.size != pathInfo.coordinateCount) {
            emitMalformedPlacement(path, reps, kind, pathInfo.groupPaths)
            return
        }

        for (level in pathInfo.groupPaths.indices) {
            val parentReps = reps.take(level)
            val addressedRepetition = reps[level]
            if (addressedRepetition < 1) {
                emit(pathInfo.groupPaths[level], parentReps + addressedRepetition)
                continue
            }

            val scope = Scope(pathInfo.groupPaths[level], parentReps)
            val previous = highestRepetitionByScope[scope] ?: 0
            if (addressedRepetition <= previous) continue
            for (repetition in previous + 1..addressedRepetition) {
                emit(pathInfo.groupPaths[level], parentReps + repetition)
            }
            highestRepetitionByScope[scope] = addressedRepetition
        }
    }

    /** Preserve the former deferred-validation behavior for malformed addresses; [Document.build] owns refusal. */
    private fun emitMalformedPlacement(
        path: String,
        reps: List<Int>,
        kind: ModelEntityKind,
        groupPaths: List<String>,
    ) {
        if (kind == ModelEntityKind.GROUP) {
            emit(path, reps)
            return
        }
        groupPaths.forEachIndexed { level, groupPath -> emit(groupPath, reps.take(level + 1)) }
    }

    private fun pathInfoOf(path: String, kind: ModelEntityKind): PathInfo {
        val parts = pathParts(path)
        val groupCount = if (kind == ModelEntityKind.GROUP) parts.size else parts.size - 1
        return PathInfo(
            coordinateCount = parts.size,
            groupPaths = (1..groupCount).map { depth -> "/" + parts.take(depth).joinToString("/") },
        )
    }

    private fun pathParts(path: String): List<String> = path.trim('/').split('/')
}

/**
 * Build the `$`-correlation hash-join buckets — per join-key: `[t, f, n]` over [rows] (`t`/`f` = the star cell
 * present/empty, skipping a parallel-index-UNKNOWN presence; `n` = rows in the bucket; an all-empty row or an
 * Unknown join value contributes nothing). Shared by [Document]'s cached per-scope path and the compute-cascade
 * overlay, whose rows must READ THROUGH the overlay (a `$`-join over a computed field sees the cascade's value,
 * not the stale stored one).
 */
internal fun correlationBucketsOf(rows: List<EvalContext>, joinFields: List<String>, starPath: String): Map<List<Val>, IntArray> {
    val buckets = HashMap<List<Val>, IntArray>()
    for (rc in rows) {
        if (!rc.evaluated) continue                                   // all-empty row → filter row-gate excludes it
        val key = joinFields.map { rc.field(it) }
        if (key.any { it is Val.Unknown }) continue                   // an Unknown join value never matches via ==
        val tfn = buckets.getOrPut(key) { IntArray(3) }
        tfn[2]++                                                      // n = rows in this bucket
        when {
            rc.presenceUnknown(starPath) -> {}                        // parallel-index 3VL → neither
            !rc.filled(starPath) -> tfn[1]++                          // empty → f
            rc.field(starPath) is Val.Unknown -> {}                   // filled-but-INVALID → neither (IF105)
            else -> tfn[0]++                                          // present → t
        }
    }
    return buckets
}
