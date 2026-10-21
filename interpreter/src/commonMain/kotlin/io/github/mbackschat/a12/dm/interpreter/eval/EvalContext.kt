package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.IndexIdentityCandidate
import io.github.mbackschat.a12.dm.interpreter.resolveIndexIdentity
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionData
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.Kind

/**
 * What the interpreter needs from "the document, in the current evaluation context" — the seam that
 * lets the SAME evaluator serve a single row (SingleRowDoc) and per-row / outer contexts of a repeatable
 * group (RepDoc), without the operator logic knowing which it is.
 */
interface EvalContext {
    /** Whether this row is evaluated at all (the kernel's "an entirely empty row is not evaluated"). */
    val evaluated: Boolean

    /** Resolve a scalar field reference (empty handled by type). */
    fun field(path: String): Val

    /**
     * Whether a field has a value — the presence primitive the whole `*Filled` family reduces to, plus the
     * places presence is read without an operator naming it (auto-checks, ordered correlation, polarity walks,
     * the value-list quantifiers). Naming two operators here read as a list and was one.
     */
    fun filled(path: String): Boolean

    /**
     * Whether the field's very **presence** is 3-valued UNKNOWN (not just its value) — distinct from [filled],
     * which can only say present/absent. The only case today: a **parallel iteration** reference into a group
     * whose index is INVALID (a duplicate/empty index value) for an index value that group has no row for — the
     * kernel reads such a reference as UNKNOWN, not "empty" (KERNEL-FINDINGS §9), so a presence predicate like
     * `FieldsNotCollectivelyFilled` is suppressed there. Default false (a normal context knows its presences).
     */
    fun presenceUnknown(path: String): Boolean = false

    /**
     * Whether a NUMBER field is *signed* (the kernel's `hatVorzeichen` — it permits negative values). This
     * drives the **directional** value-vs-omission typing: an empty number can always be filled bigger
     * (`kannGroesserWerden`), but can be filled smaller only if signed (`kannKleinerWerden = hatVorzeichen`,
     * kernel `RuntimeController.feldWertAlsZahl`). Defaults to signed — the kernel's own default, since
     * `NumberType.positivesOnly` defaults to false and is the only input to `hasSign`; the general [Document]
     * answers it from the model's unsigned-field set.
     */
    fun signed(path: String): Boolean = true

    /**
     * Whether a GROUP has structural content in this row. The general [Document] resolves this against the row's
     * instance; full validation overlays formal admission/error state before evaluating group predicates (IF193);
     * the single-row / repetition spike contexts don't model group structure and don't support it (they
     * throw the same way [star] does on a flat doc — they are never used for group predicates).
     */
    fun groupFilled(groupPath: String): Boolean =
        throw UnsupportedConstructException("group-filled needs the general Document context, not $this")

    /** The FILLED values of a starred field across the repeatable group's rows (for aggregates).
     *  [bindDepth] — the segments STRICTLY ABOVE the first `*`-marked group, the levels an iterating row binds
     *  (IF93/IF94, [io.github.mbackschat.a12.dm.interpreter.ast.Expr.Star.bindDepth]); −1 = the leaf group's
     *  parent (the single-star default). Applies to every star-family method below. */
    fun star(path: String, bindDepth: Int = -1): List<Val>

    /**
     * The value of a starred field for **every** row of its repeatable group, in row order — an EMPTY row
     * yields the kind's empty value (a number → 0), a malformed value → [Val.Unknown]. Unlike [star] (which
     * drops empties), this preserves **row alignment**, so two fields of the same repeatable group can be paired
     * row-by-row (`SumOfProducts`). The general [Document] resolves it; the flat spike contexts don't model rows.
     */
    fun starAll(path: String, bindDepth: Int = -1): List<Val> =
        throw UnsupportedConstructException("row-aligned star needs the general Document context, not $this")

    /**
     * The starred field's value **per row, with its repetition indices** — `(reps, value-or-null)` where null is
     * an empty cell (a malformed cell is [Val.Unknown]); the reps-aware twin of [starAll]. Lets a decorator that
     * keys values by `(path, reps)` — the compute **cascade overlay** ([io.github.mbackschat.a12.dm.interpreter.DmInterpreter]'s
     * `OverlayContext`) — apply itself to a STARRED read (`Sum(Group* then ComputedField)`), so an aggregate over
     * a computed field sees the COMPUTED values, not the stored ones. The general [Document] resolves it.
     */
    fun starCells(path: String, bindDepth: Int = -1): List<Pair<List<Int>, Val?>> =
        throw UnsupportedConstructException("row-aligned star cells need the general Document context, not $this")

    /** Whether a starred aggregate over [path] could still grow (an empty operand, or an
     *  un-instantiated tail below the declared repetition) → types a fired comparison OMISSION. */
    fun starOmission(path: String, bindDepth: Int = -1): Boolean

    /** The normalized evaluation value of a field, or null if empty. */
    fun displayValue(path: String): String?

    /**
     * The original display value used only for validation-message interpolation. General document contexts
     * override this to bypass the normalized evaluation cache; lightweight contexts store one representation.
     */
    fun messageDisplayValue(path: String): String? = displayValue(path)

    /** The declared [Kind] of a field path, or null when unknown (a flat spike context). Lets the evaluator
     *  type-infer an operand — e.g. to canonicalize a German-format date LITERAL only when compared to a date. */
    fun declaredKind(path: String): Kind? = null

    /** A [Kind.DATE] field's declared rendering format (its `DateType.format`), or null. Lets `ValueAsDate`
     *  parse the field's RAW partial value (`00.MM.yyyy`) in its own format. */
    fun dateFormatOf(path: String): String? = null

    /** The temporal format a field DECLARES whatever its kind — a TIME field may declare `dd.MM.yyyy` — or null.
     *  With [declaredKind] it decides how a computed value is stored there ([dateStoreFormat], KF222). */
    fun declaredTemporalFormatOf(path: String): String? = null

    /** A [Kind.DATE] field's partial precision, used with [dateFormatOf] by `ValueAsDate`. */
    fun datePrecisionOf(path: String): DatePrecision = DatePrecision.FULL

    /** The resolved value of [targetPath] in the repetition (within scope) whose [indexField] cell equals
     *  [indexValue] — the `SemanticIndex` (`[field For "value"]`) lookup. Null when no row matches OR the
     *  matched cell is empty (the caller then reads it as the kind's empty value). Default: unsupported. */
    fun indexedCell(targetPath: String, indexField: String, indexValue: String): Val? = null

    /** Admission-aware twin used by a complete validation/computation call. The default preserves the
     *  low-level context contract; the general [Document] filters every candidate before choosing a row. */
    fun indexedCell(
        targetPath: String,
        indexField: String,
        indexValue: String,
        indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)?,
    ): Val? = indexedCell(targetPath, indexField, indexValue)

    /** The repetitions of the admitted row selected by the semantic index. This is distinct from the value:
     *  an empty target cell still selects a row, and the rule error context follows that selected repetition. */
    fun indexedCellRepetitions(
        targetPath: String,
        indexField: String,
        indexValue: String,
        indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)?,
    ): List<Int>? = null

    /** The row a fired message's SELECTED pointer names: the admitted row carrying [indexValue], and one row even
     *  where several do, which the value lookup above refuses (SPEC-2026-10-07-02). Null when no admitted row
     *  carries the key. Which duplicate the kernel's index cache names is not the first row in general; this
     *  names the first, a characterized divergence (INTERPRETER-FINDINGS IF376). */
    fun indexPointerRepetitions(
        targetPath: String,
        indexField: String,
        indexValue: String,
        indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)?,
    ): List<Int>? = null

    /** The semantic-index payload at an already selected [repetitions] identity. Kept separate from row
     * selection so a computation overlay can project a value produced earlier in the cascade. */
    fun indexedValueAt(targetPath: String, repetitions: List<Int>): Val? = null

    /** The earliest day, or with [lastDay] the latest, of the well-formed partial Date stored at [path] /
     *  [repetitions] when its year is known, else null. A value read leaves such a cell unknown; a SELECTION reads
     *  its earliest day (KERNEL-FINDINGS KF508) and `ValueAsDate` the boundary it names. The default answers null,
     *  which keeps the cell unknown. */
    fun partialDateBoundaryAt(path: String, repetitions: List<Int>, lastDay: Boolean = false): Val? = null

    /** The [IndexColumn] state of a starred INDEX field's column. The general [Document]'s raw row context
     *  memoizes it per (column, star-scope prefix) at the DOCUMENT level (the kernel's `IndexFieldCache`
     *  analog, like [indexedCell]'s row map); this default rebuilds per read — a cascade OVERLAY's view
     *  changes as computations land, so only the RAW context may cache. */
    fun indexColumn(path: String, bindDepth: Int = -1): IndexColumn = IndexColumn(starCells(path, bindDepth))

    /** Whether a call-local admission-aware [IndexColumn] may be memoized. A computation overlay that writes
     * [path] overrides this because its column changes between row evaluations of one computation step. */
    fun indexColumnCacheable(path: String): Boolean = true

    /** One [EvalContext] per row of [groupPath] WITHIN this context's scope — for evaluating a `Having`
     *  per-row filter inside an aggregate. Default empty (the flat spike contexts don't model rows). */
    fun rowContexts(groupPath: String, bindDepth: Int = -1): List<EvalContext> = emptyList()

    /**
     * The repetition-index prefix identifying the row-SCOPE a starred aggregate / `$`-correlation over [groupPath]'s
     * rows ranges within in THIS context — the levels bound to this row (the `*`-marked level and below iterate
     * fully; [bindDepth] caps it for a multi-star operand, IF93/IF94). The **compute-cascade overlay's** derived-view
     * cache ([io.github.mbackschat.a12.dm.interpreter.eval.OverlayViews], IF143) keys on it so an overlaid
     * index-column / correlation-bucket view is built once per (column, scope) within a computation step and shared
     * across the outer rows — the SAME scope key the raw [Document]'s document-level cache uses, which the overlay
     * otherwise bypassed and rebuilt per outer row (O(rows²)). Default empty (a flat single-row context has one
     * implicit scope and never reaches the overlay cache).
     */
    fun scopePrefixOf(groupPath: String, bindDepth: Int = -1): List<Int> = emptyList()

    /**
     * How many of [path]'s repetition levels THIS row context actually **binds** — the levels whose coordinate
     * the iteration chose, as opposed to the ones [repsOf] resolves to a default because nothing selected them.
     *
     * The partial-validation rule-run gate compares the error field's relevance over exactly these levels
     * ([IF268]). It must come from the CONTEXT and never from the rule's declared group: rule iteration is
     * REFERENCE-driven (see `IterationScopes.prepare`), so a rule declared on `/Order` whose references reach
     * `/Order/Items` iterates the Items rows and binds that level, while a star-only rule on the same paths
     * does not. Default 0 — a context that models no rows binds nothing.
     */
    fun boundLevelsOf(path: String): Int = 0

    /** The declared FIELD paths inside [groupPath], recursively incl. nested subgroups (the kernel
     *  `ExpandService` → `getAllContainedFields`) — how a GROUP operand of a value aggregate expands (IF99).
     *  Empty when [groupPath] contains no fields (or the context doesn't model structure). */
    fun containedFields(groupPath: String): List<String> = emptyList()

    /**
     * Hash-join buckets for a `$` EQUALITY-correlation `Having` over [groupPath]'s rows (IF47 perf) — keyed by
     * the [joinFields]' value tuple, each bucket an `[t, f, n]` tally of [starPath]'s fill across the rows in it
     * (`t` = present + check-relevant, `f` = empty, `n` = rows). A row whose join tuple contains a [Val.Unknown]
     * (an empty string / malformed join field) is EXCLUDED — an `==` with Unknown never matches; an all-empty
     * (un-evaluated) row is excluded too (the filter's row-gate). This lets a filtered quantifier resolve
     * `AtLeastOneFieldFilled(G* / F Having [X] == [$X] …)` in O(1) per outer row (a hash lookup) instead of an
     * O(N) inner scan — the kernel's own linear strategy. Built + **cached per (group, scope)** by the general
     * [Document] (shared across every outer row); the flat spike contexts don't model rows. [bindDepth] widens the
     * bucket scope to the FIRST `*`-marked level for a MULTI-star operand (`A* / B* / F`, IF94) so the join spans
     * every A's B-rows, not just the current one (IF144); −1 = the single-star leaf-parent scope. */
    fun correlationBuckets(groupPath: String, joinFields: List<String>, starPath: String, bindDepth: Int = -1): Map<List<Val>, IntArray> =
        throw UnsupportedConstructException("correlation buckets need the general Document context, not $this")

    /**
     * Ordered prefix tallies for one comparator-only `$` correlation over [groupPath]'s rows. Built and cached per
     * immutable (group, scope, [innerField], [starPath]) view, so one O(rows log rows) preparation serves every
     * outer-row O(log rows) query. [bindDepth] has the same multi-star scope semantics as [correlationBuckets].
     */
    fun orderedCorrelation(
        groupPath: String,
        innerField: String,
        starPath: String,
        bindDepth: Int = -1,
    ): OrderedCorrelation =
        throw UnsupportedConstructException("ordered correlation needs the general Document context, not $this")

    /**
     * The fill tally of a **group-scoped** (or starred-field) fill-quantifier operand — `AllFieldsFilled(/Group*)`,
     * `AtLeastOneFieldFilled(/Addr)` (IF49). A group scope means "the fields INSIDE the group" (recursively, incl.
     * nested subgroups — the kernel `ExpandService` → `getAllContainedFields`); a bare field path is that one field.
     * Two ranges, because the kernel uses two: the **instantiated** tally ([FillScope.tInst]/[FillScope.fInst]/
     * [FillScope.nInst] over present reps, malformed cell → [FillScope.hasUnknownInst]) drives `AtLeastOne`/`NoField`/
     * `MoreThanOne`/`NotExactlyOne`, while the **declared** range ([FillScope.allFilledDeclared] = every declared
     * rep's cell filled+valid; [FillScope.anyEmptyDeclared] = some declared rep's cell empty — an un-instantiated rep
     * reads empty) drives `AllFieldsFilled`/`NotAllFieldsFilled` and the declared half of `FieldsNotCollectivelyFilled`.
     * Resolved by the general [Document]; the flat spike contexts don't model group structure. */
    fun fillScope(scopePath: String, isGroup: Boolean, bindDepth: Int = -1): FillScope =
        throw UnsupportedConstructException("group-scoped field-fill needs the general Document context, not $this")

    /**
     * [fillScope] with every cell read through [cellValue] — the same scope, tallied over values a decorator owns.
     * The computation cascade's overlay holds the values computed so far, and the scope rule stays with the context
     * that defines it rather than being restated beside the overlay.
     */
    fun fillScope(scopePath: String, isGroup: Boolean, bindDepth: Int, cellValue: CellValueSource): FillScope =
        throw UnsupportedConstructException("group-scoped field-fill needs the general Document context, not $this")

    /** The document-wide data view for a `CustomCondition` — the kernel's `IData` surface (read field values by
     *  path+indices, enumerate present fields). The row is carried separately by the error-field pointer; flat
     *  spike contexts do not model this document view. */
    fun customConditionData(): CustomConditionData =
        throw UnsupportedConstructException("custom-condition data needs the general Document context, not $this")

    /** The category value of an enumeration field — `[field -> category]` (the `EnumCategory` operator): the
     *  field's stored value mapped POSITIONALLY to the named category's value, as a [Val.Txt]; [Val.Unknown]
     *  for an empty field or a value not in the enumeration (→ the comparison does not fire). The general
     *  [Document] resolves it; the flat spike contexts don't model enum categories. */
    fun enumCategory(path: String, category: String): Val =
        throw UnsupportedConstructException("enum-category needs the general Document context, not $this")

    /** Project an already-resolved stored enum [value] through [category]. This is the category half of a
     * compositional owner `entitySpec`, where a semantic index or wildcard row selected the value first. */
    fun enumCategoryValue(path: String, category: String, value: Val): Val =
        throw UnsupportedConstructException("enum-category projection needs the general Document context, not $this")

    /**
     * The 1-based repetition indices of a field path **in this row** — one per path part (the kernel's
     * `repetitionIndexes()`), for building a field-instance pointer. Defaults to all-1s (correct for a flat
     * single-row context); a repeatable context overrides it with the row's real indices.
     */
    fun repsOf(path: String): List<Int> = List(path.trim('/').split('/').size) { 1 }

    /**
     * The COMPUTE-mode read state of this row's cell at [path] (IF85): [CellState.EMPTY] (no value),
     * [CellState.INVALID] (a present value that is **formally invalid** — the poison trigger), else
     * [CellState.FILLED]. Distinct from "resolves to [Val.Unknown]": a well-formed day-omitted date resolves
     * Unknown for a direct reference but is NOT invalid (it must not poison). The general [Document] answers
     * from the formal check; the default (no invalidity knowledge) never reports INVALID.
     */
    fun cellState(path: String): CellState =
        if (displayValue(path) == null) CellState.EMPTY else CellState.FILLED

    /**
     * Whether [path] holds a value a computation of this call produced. Its computation judged it by the target's
     * type format, which is all A12 asks of a computed value (KERNEL-FINDINGS KF547), so no read checks it again:
     * an input gate such as the model charset never reaches it. Only the cascade overlay holds one.
     */
    fun holdsComputedValue(path: String): Boolean = false

    /** The COMPUTE-mode scan states of a starred field's cells, one per row in row order — the per-cell
     *  [cellState] twin of [starCells] (IF85). The default derives from [starCells] (a [Val.Unknown] cell reads
     *  INVALID); the general [Document] overrides with the precise formal check. */
    fun starScanStates(path: String, bindDepth: Int = -1): List<CellState> = starCells(path, bindDepth).map { (_, v) ->
        when {
            v == null -> CellState.EMPTY
            v is Val.Unknown -> CellState.INVALID
            else -> CellState.FILLED
        }
    }

    /** Identity-preserving COMPUTE scan of a starred field. [declaredRange] includes uninstantiated declared
     *  repetitions in their exact position; false emits instantiated rows only. The general [Document] supplies
     *  raw values lazily. */
    fun starScanCells(path: String, bindDepth: Int = -1, declaredRange: Boolean = false): Sequence<ScanCell> {
        if (declaredRange) {
            throw UnsupportedConstructException("declared star scan needs the general Document context, not $this")
        }
        val cells = starCells(path, bindDepth)
        val states = starScanStates(path, bindDepth)
        return cells.indices.asSequence().map { i ->
            ScanCell(path, cells[i].first, null, states[i], cells[i].second)
        }
    }

    /** Identity-preserving COMPUTE scan of a bare GROUP's contained fields in declaration order. The returned
     *  sequence must be lazy: a deciding cell prevents every later lookup and formal check. [declaredRange]
     *  interleaves each field's missing declared repetitions before advancing to the next field. */
    fun containedFieldCells(groupPath: String, declaredRange: Boolean): Sequence<ScanCell> =
        throw UnsupportedConstructException("bare-group compute scan needs the general Document context, not $this")

    /** The context rooted at the one [groupPath] instance selected by [indexField]=[indexValue], or null on a
     *  clean no-match. Every keyed-GROUP consumer reads through it — a contained-field scan and a presence
     *  product alike — so the selected row has one owner. The caller owns the index-column validity gate so it
     *  can retain validation-vs-computation poison semantics. */
    fun indexedGroupRow(
        groupPath: String,
        indexField: String,
        indexValue: String,
        indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)?,
    ): EvalContext? =
        throw UnsupportedConstructException("indexed-group selection needs the general Document context, not $this")

    /** The context rooted at the [groupPath] instance a key no row carries names, at [repetitions] with the keyed
     *  level unbound ([UNKNOWN_REP]): every cell below it is missing, and a coverage gate asked there is met only
     *  by a selection wildcarded at that level. */
    fun absentGroupRow(groupPath: String, repetitions: List<Int>): EvalContext =
        throw UnsupportedConstructException("indexed-group selection needs the general Document context, not $this")
}

/**
 * Internal narrow seam for a starred GROUP-list operand whose terminal may be non-repeatable. It reuses the
 * document's first-star row topology without changing [EvalContext.rowContexts], whose group argument remains
 * the actual aggregate/Having row group. Non-document contexts retain the terminal-repeatable fallback.
 */
internal interface GroupListEnvironmentProvider {
    fun groupListEnvironments(groupPath: String, bindDepth: Int): List<EvalContext>

    /**
     * The DECLARED number of instances the starred operand [groupPath] spans: the product of the declared
     * maximum repetition counts of every repeatable level below [bindDepth], down to the environment
     * [groupListEnvironments] ranges over. So a fixed group below a starred level spans one instance per row of
     * its repeatable ancestor, and an operand crossing two starred levels spans both capacities multiplied
     * (KF463). Null when one of those levels carries no maximum — an unbounded repeatable group — or when the
     * context does not know the model. It is the total the kernel's entity iterator declares before it trims its
     * end indices to the filled rows, so a group-counting aggregate's fillability reads it: unused declared
     * capacity is an uncounted entity even at zero instantiated rows, and "no declared maximum" means the tail
     * never closes. Saturates at [Int.MAX_VALUE].
     */
    fun declaredGroupListExtent(groupPath: String, bindDepth: Int): Int? = null
}

internal fun EvalContext.groupListEnvironments(groupPath: String, bindDepth: Int): List<EvalContext> =
    (this as? GroupListEnvironmentProvider)?.groupListEnvironments(groupPath, bindDepth)
        ?: rowContexts(groupPath, bindDepth)

internal fun EvalContext.declaredGroupListExtent(groupPath: String, bindDepth: Int): Int? =
    (this as? GroupListEnvironmentProvider)?.declaredGroupListExtent(groupPath, bindDepth)

/** A cell's COMPUTE-mode read state ([EvalContext.cellState], IF85) — a visited [INVALID] cell poisons the
 *  reading computation instance ([PoisonedReadException]). "Cell" (a repetition's field value) is a HOUSE
 *  term — the kernel has no cell noun; it is declared once in KERNEL-SEMANTICS "House ↔ kernel vocabulary",
 *  where these states map to the kernel predicates FILLED=`wertAngegeben`, EMPTY=not-specified,
 *  INVALID=`nichtPruefungsrelevant`. */
enum class CellState { EMPTY, FILLED, INVALID }

/** One identity-preserving cell event in a compute-mode fill scan. [baseState] is the document/overlay state;
 *  the compute call's complete formal oracle may refine FILLED to INVALID before the event is consumed. */
data class ScanCell(
    val path: String,
    val reps: List<Int>,
    val raw: String?,
    val baseState: CellState,
    /** The field-format-aware evaluation value for this exact cell; null only for an empty cell. */
    val value: Val?,
    /** Whether a computation of this call produced [value]; see [EvalContext.holdsComputedValue]. */
    val computed: Boolean = false,
)

/** What one cell reads as, given [stored] — its value in the context that owns the scope; null is an empty cell. */
fun interface CellValueSource {
    fun valueOf(path: String, reps: List<Int>, stored: Val?): Val?
}

/**
 * The resolved state of an INDEX column ([EvalContext.indexColumn]): the [EvalContext.starCells]-aligned
 * [cells], the [duplicated] values (each duplicate-valued cell carries the kernel's uniqueIndex error; values
 * compare as [Val] — a Txt index compares by text), and the derived flags/tally the indexed-read gates consult
 * (the whole-column invalid marking IF107/IF115; the index-aware quantifier/aggregate classification IF15/IF60).
 * Everything is derived ONCE at construction so a consulting site is O(1) — the per-lookup column rebuild made
 * every indexed read O(rows), and a `validateFull` over a per-row indexed rule O(rows²) (caught by the perf
 * gate's `semanticIndexStaysSubQuadratic`).
 *
 * [relevantCell] is the identity-stage relevance gate. A non-relevant cell participates in neither key projection
 * nor collision and compromises the column as unavailable ([IF271]); a relevant admitted cell participates, while
 * an empty or malformed cell compromises it. The interpreter's call-level path normally maps those classifications
 * into [cells] first; the predicate remains the equivalent low-level constructor seam. Default: every cell relevant.
 */
class IndexColumn(
    val cells: List<Pair<List<Int>, Val?>>,
    relevantCell: (List<Int>) -> Boolean = { true },
) {
    /** The values appearing MORE than once among the RELEVANT [cells]. */
    val duplicated: Set<Val>

    /** Any empty, malformed, or duplicated cell — the whole-column not-check-relevant marking (IF107/IF115). */
    val anyNotCheckRelevant: Boolean

    /** Any empty, unavailable, or DUPLICATED cell. The aggregate caller sees built-in malformed values as
     * [Val.Unknown] directly; this flag additionally carries call-local custom/relevance unavailability that the
     * raw document value cannot encode. */
    val anyEmptyOrDuplicated: Boolean

    /** The count of clean cells (filled, well-formed, unique) — the index-aware fill tally's `t`. */
    val cleanFilled: Int

    init {
        val candidates = cells.map { (reps, value) ->
            val relevant = relevantCell(reps)
            IndexIdentityCandidate(
                repetitions = reps,
                key = value?.takeUnless { it is Val.Unknown }?.takeIf { relevant },
                compromises = !relevant || value == null || value is Val.Unknown,
            )
        }
        val resolved = resolveIndexIdentity(candidates)
        duplicated = resolved.duplicatedKeys
        anyNotCheckRelevant = resolved.compromised
        anyEmptyOrDuplicated = resolved.compromised
        cleanFilled = candidates.count { candidate ->
            candidate.key != null && candidate.key !in duplicated
        }
    }
}

/**
 * The fill tally of a group-scoped / starred-field fill-quantifier operand ([EvalContext.fillScope]) — the two
 * kernel iteration ranges. [tInst]/[fInst]/[nInst] tally the **instantiated** cells (present reps): `t` = filled +
 * valid, `f` = empty, and a present-but-malformed cell is counted in neither ([hasUnknownInst] flags it), matching
 * the kernel's 3-valued `isWertAngegeben`. [allFilledDeclared]/[anyEmptyDeclared] describe the **full declared**
 * repetition range (an un-instantiated rep reads empty). Aggregated across a multi-scope operand list by summing
 * the counts and OR/AND-ing the declared flags.
 */
class FillScope internal constructor(
    val tInst: Int,
    val fInst: Int,
    val nInst: Int,
    val hasUnknownInst: Boolean,
    declared: () -> DeclaredFill,
) {
    constructor(
        tInst: Int,
        fInst: Int,
        nInst: Int,
        hasUnknownInst: Boolean,
        allFilledDeclared: Boolean,
        anyEmptyDeclared: Boolean,
    ) : this(tInst, fInst, nInst, hasUnknownInst, { DeclaredFill(allFilledDeclared, anyEmptyDeclared) })

    // Computed on first read: only `AllFieldsFilled`, `NotAllFieldsFilled` and `FieldsNotCollectivelyFilled` read the
    // declared range, so a threshold over a filtered list never walks its uninstantiated rows (IG161,
    // `FilteredThresholdDeclaredWorkTest`).
    private val declaredFill by lazy(LazyThreadSafetyMode.NONE, declared)

    val allFilledDeclared: Boolean get() = declaredFill.allFilled

    val anyEmptyDeclared: Boolean get() = declaredFill.anyEmpty
}

/** The declared-range half of a [FillScope]. */
internal data class DeclaredFill(val allFilled: Boolean, val anyEmpty: Boolean)

/**
 * The candidate rows a `Having` filter is asked of. A12's filtered walk visits, at each starred level, every
 * coordinate from 1 up to the highest repetition stored at that depth by any counted group, never fewer than one
 * and never past the declared capacity; a coordinate no row holds reads empty. [counts] admits the groups whose rows
 * count, every group when null (INTERPRETER-GAPS IG165 closure, `adapter.laws.FilterMissingSlotDiffTest`).
 */
internal interface FilterWalkRowProvider {
    fun filterWalkRows(groupPath: String, bindDepth: Int, counts: ((String) -> Boolean)?): List<EvalContext>
    fun sparseFilterPrefixes(groupPath: String, bindDepth: Int, counts: ((String) -> Boolean)?): Set<List<Int>>?
}

internal fun EvalContext.filterWalkRows(groupPath: String, bindDepth: Int, counts: ((String) -> Boolean)?): List<EvalContext> =
    (this as? FilterWalkRowProvider)?.filterWalkRows(groupPath, bindDepth, counts) ?: rowContexts(groupPath, bindDepth)

/** Validation filters may select a missing declared slot by coordinate (SPEC-2026-10-03-03). */
internal interface DeclaredStarRowProvider {
    fun declaredStarRows(fieldPath: String, bindDepth: Int): Sequence<EvalContext>
}

internal fun EvalContext.declaredStarRows(fieldPath: String, bindDepth: Int): Sequence<EvalContext> =
    (this as? DeclaredStarRowProvider)?.declaredStarRows(fieldPath, bindDepth)
        ?: rowContexts(fieldPath.substringBeforeLast('/'), bindDepth).asSequence()

/** First-filled retains implicit empty descendants before a later value (SPEC-2026-10-10-14). */
internal interface FirstFilledScanProvider {
    fun firstFilledRows(groupPath: String, bindDepth: Int): Sequence<EvalContext>
    fun firstFilledContainedCells(groupPath: String): Sequence<ScanCell>
}

internal fun EvalContext.firstFilledRows(groupPath: String, bindDepth: Int): Sequence<EvalContext> =
    (this as? FirstFilledScanProvider)?.firstFilledRows(groupPath, bindDepth)
        ?: rowContexts(groupPath, bindDepth).asSequence()

internal fun EvalContext.firstFilledContainedCells(groupPath: String): Sequence<ScanCell> =
    (this as? FirstFilledScanProvider)?.firstFilledContainedCells(groupPath)
        ?: containedFieldCells(groupPath, declaredRange = false)
