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

    /** The FILLED values of a starred field across the repeatable group's rows (for aggregates). */
    fun star(path: String): List<Val>

    /**
     * The value of a starred field for **every** row of its repeatable group, in row order — an EMPTY row
     * yields the kind's empty value (a number → 0), a malformed value → [Val.Unknown]. Unlike [star] (which
     * drops empties), this preserves **row alignment**, so two fields of the same repeatable group can be paired
     * row-by-row (`SumOfProducts`). The general [Document] resolves it; the flat spike contexts don't model rows.
     */
    fun starAll(path: String): List<Val> =
        throw UnsupportedConstructException("row-aligned star needs the general Document context, not $this")

    /** Whether a starred aggregate over [path] could still grow (an empty operand, or an
     *  un-instantiated tail below the declared repetition) → types a fired comparison OMISSION. */
    fun starOmission(path: String): Boolean

    /**
     * Whether a `FirstFilledValue` over [path] could be **displaced by filling** — i.e. an instantiated
     * EMPTY row precedes (lower repetition index than) the first filled row, so filling it could become the
     * new first value (→ a fired comparison is OMISSION). False when the first row is already filled (the
     * first value is fixed; filling later rows can't change it → VALUE) or no row is filled. This is NOT the
     * broad [starOmission] (any empty cell): only an empty row BEFORE the first filled one matters
     * (kernel-probed, KERNEL-FINDINGS §1 supplement). Default false (the flat spike contexts don't model rows).
     */
    fun firstStarRowEmpty(path: String): Boolean = false

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

    /** One [EvalContext] per row of [groupPath] WITHIN this context's scope — for evaluating a `Having`
     *  per-row filter inside an aggregate. Default empty (the flat spike contexts don't model rows). */
    fun rowContexts(groupPath: String): List<EvalContext> = emptyList()

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
}
