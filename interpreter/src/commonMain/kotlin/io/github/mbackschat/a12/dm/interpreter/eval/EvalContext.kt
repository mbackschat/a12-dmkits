package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionData
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

    /** Whether a field has a value (for FieldFilled / AllFieldsFilled). */
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
     * kernel `RuntimeController.feldWertAlsZahl`). Defaults to signed — the common case (a number field with
     * no non-negative `minValue`); the general [Document] answers it from the model's unsigned-field set.
     */
    fun signed(path: String): Boolean = true

    /**
     * Whether a GROUP is filled in this row — i.e. at least one field under [groupPath] carries a value
     * (for GroupFilled / GroupNotFilled). The general [Document] resolves this against the row's instance;
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

    /**
     * Whether a `FirstFilledValue` over [path] could be **displaced by filling** — i.e. an instantiated
     * EMPTY row precedes (lower repetition index than) the first filled row, so filling it could become the
     * new first value (→ a fired comparison is OMISSION). False when the first row is already filled (the
     * first value is fixed; filling later rows can't change it → VALUE) or no row is filled. This is NOT the
     * broad [starOmission] (any empty cell): only an empty row BEFORE the first filled one matters
     * (kernel-probed, KERNEL-FINDINGS §1 supplement). Default false (the flat spike contexts don't model rows).
     */
    fun firstStarRowEmpty(path: String, bindDepth: Int = -1): Boolean = false

    /** The raw display value of a field (for message interpolation), or null if empty. */
    fun displayValue(path: String): String?

    /** The declared [Kind] of a field path, or null when unknown (a flat spike context). Lets the evaluator
     *  type-infer an operand — e.g. to canonicalize a German-format date LITERAL only when compared to a date. */
    fun declaredKind(path: String): Kind? = null

    /** A [Kind.DATE] field's declared rendering format (its `DateType.format`), or null. Lets `ValueAsDate`
     *  parse the field's RAW partial value (`00.MM.yyyy`) in its own format. */
    fun dateFormatOf(path: String): String? = null

    /** The resolved value of [targetPath] in the repetition (within scope) whose [indexField] cell equals
     *  [indexValue] — the `SemanticIndex` (`[field For "value"]`) lookup. Null when no row matches OR the
     *  matched cell is empty (the caller then reads it as the kind's empty value). Default: unsupported. */
    fun indexedCell(targetPath: String, indexField: String, indexValue: String): Val? = null

    /** The [IndexColumn] state of a starred INDEX field's column. The general [Document]'s raw row context
     *  memoizes it per (column, star-scope prefix) at the DOCUMENT level (the kernel's `IndexFieldCache`
     *  analog, like [indexedCell]'s row map); this default rebuilds per read — a cascade OVERLAY's view
     *  changes as computations land, so only the RAW context may cache. */
    fun indexColumn(path: String, bindDepth: Int = -1): IndexColumn = IndexColumn(starCells(path, bindDepth))

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
     * [Document] (shared across every outer row); the flat spike contexts don't model rows. */
    fun correlationBuckets(groupPath: String, joinFields: List<String>, starPath: String): Map<List<Val>, IntArray> =
        throw UnsupportedConstructException("correlation buckets need the general Document context, not $this")

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
    fun fillScope(scopePath: String, isGroup: Boolean): FillScope =
        throw UnsupportedConstructException("group-scoped field-fill needs the general Document context, not $this")

    /** The row's data view for a `CustomCondition` — the kernel's `IData` surface (read field values by
     *  path+indices, enumerate present fields). The general [Document] resolves it document-wide (a custom
     *  condition's `IData` spans the document; the row is carried by the error-field pointer); the flat spike
     *  contexts don't model it. */
    fun customConditionData(): CustomConditionData =
        throw UnsupportedConstructException("custom-condition data needs the general Document context, not $this")

    /** The category value of an enumeration field — `[field -> category]` (the `EnumCategory` operator): the
     *  field's stored value mapped POSITIONALLY to the named category's value, as a [Val.Txt]; [Val.Unknown]
     *  for an empty field or a value not in the enumeration (→ the comparison does not fire). The general
     *  [Document] resolves it; the flat spike contexts don't model enum categories. */
    fun enumCategory(path: String, category: String): Val =
        throw UnsupportedConstructException("enum-category needs the general Document context, not $this")

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
}

/** A cell's COMPUTE-mode read state ([EvalContext.cellState], IF85) — a visited [INVALID] cell poisons the
 *  reading computation instance ([PoisonedReadException]). "Cell" (a repetition's field value) is a HOUSE
 *  term — the kernel has no cell noun; it is declared once in KERNEL-SEMANTICS "House ↔ kernel vocabulary",
 *  where these states map to the kernel predicates FILLED=`wertAngegeben`, EMPTY=not-specified,
 *  INVALID=`nichtPruefungsrelevant`. */
enum class CellState { EMPTY, FILLED, INVALID }

/**
 * The resolved state of an INDEX column ([EvalContext.indexColumn]): the [EvalContext.starCells]-aligned
 * [cells], the [duplicated] values (each duplicate-valued cell carries the kernel's uniqueIndex error; values
 * compare as [Val] — a Txt index compares by text), and the derived flags/tally the indexed-read gates consult
 * (the whole-column invalid marking IF107/IF115; the index-aware quantifier/aggregate classification IF15/IF60).
 * Everything is derived ONCE at construction so a consulting site is O(1) — the per-lookup column rebuild made
 * every indexed read O(rows), and a `validateFull` over a per-row indexed rule O(rows²) (caught by the perf
 * gate's `semanticIndexStaysSubQuadratic`).
 */
class IndexColumn(val cells: List<Pair<List<Int>, Val?>>) {
    /** The values appearing MORE than once among [cells]. */
    val duplicated: Set<Val>

    /** Any empty, malformed, or duplicated cell — the whole-column not-check-relevant marking (IF107/IF115). */
    val anyNotCheckRelevant: Boolean

    /** Any empty or DUPLICATED cell — malformed NOT re-counted (the aggregate caller already reads a malformed
     *  cell as [Val.Unknown], IF15/IF60). */
    val anyEmptyOrDuplicated: Boolean

    /** The count of clean cells (filled, well-formed, unique) — the index-aware fill tally's `t`. */
    val cleanFilled: Int

    init {
        val seen = HashSet<Val>()
        val dups = HashSet<Val>()
        for ((_, v) in cells) if (v != null && v !is Val.Unknown && !seen.add(v)) dups += v
        duplicated = dups
        var invalid = false
        var emptyOrDup = false
        var clean = 0
        for ((_, v) in cells) when {
            v == null -> { invalid = true; emptyOrDup = true }
            v is Val.Unknown -> invalid = true
            v in dups -> { invalid = true; emptyOrDup = true }
            else -> clean++
        }
        anyNotCheckRelevant = invalid
        anyEmptyOrDuplicated = emptyOrDup
        cleanFilled = clean
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
data class FillScope(
    val tInst: Int,
    val fInst: Int,
    val nInst: Int,
    val hasUnknownInst: Boolean,
    val allFilledDeclared: Boolean,
    val anyEmptyDeclared: Boolean,
)
