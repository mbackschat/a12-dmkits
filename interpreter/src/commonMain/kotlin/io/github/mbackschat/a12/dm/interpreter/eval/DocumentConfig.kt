package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldConstraints
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.RangeYearInterpretation

/**
 * The full evaluator configuration a [Document] carries — every kind / reps / format / scale / enum / precision /
 * base-year / constraint / time-zone map the evaluator needs, derived ONCE from a [io.github.mbackschat.a12.dm.interpreter.model.ModelDef]
 * (via `ModelDef.newDocument()`). It is a single immutable value so every reconstruction preserves ALL of it
 * **wholesale**: [Document.withComputed] (through `emptyCopy`) and every harness/loader consumer pass this one
 * object, never a positional argument list. That is the point — the positional threading this replaced
 * silently dropped trailing slots (the `emptyCopy` `timeZone` loss, IG75 → IF131). Only [kinds] is required; every
 * other slot defaults to the kernel default, so a kinds-only document is `DocumentConfig(kinds)` and any partial
 * configuration is **conspicuous** (named arguments), never a silent positional drop.
 */
data class DocumentConfig(
    val kinds: Map<String, Kind>,
    val declaredReps: Map<String, Int> = emptyMap(),
    /** NUMBER fields that are *unsigned* (the kernel's `hatVorzeichen == false`, which the model->runtime
     *  conversion derives from `NumberType.positivesOnly` alone: `TypeConverter` sets `hasSign(!positivesOnly)`).
     *  A non-negative `minValue` does NOT make a field unsigned here — that pairing appears in the kernel's
     *  STATIC date-construction gate, not in the runtime polarity. Drives the directional empty-operand polarity
     *  ([EvalContext.signed]) and the `zahlMitVorzeichen` formal check. Default: all fields signed. */
    val unsignedFields: Set<String> = emptySet(),
    /** Each [Kind.DATE_RANGE] field's `(format, rangeSeparator)` — lets `resolveValue` canonicalize a rendered
     *  range value (`01.06.2021-30.06.2021`) to a [io.github.mbackschat.a12.dm.interpreter.ast.Val.Range] of ISO dates, format-aware (locale variants). */
    val rangeConfig: Map<String, Pair<String, String>> = emptyMap(),
    /** Each DATE_RANGE field's `interpretationOfYear`; absent means [RangeYearInterpretation.STANDARD]. */
    val rangeYearInterpretations: Map<String, RangeYearInterpretation> = emptyMap(),
    /** Each [Kind.DATE] field's rendering format (the kernel's `DateType.format`) — lets `resolveValue`
     *  canonicalize a rendered date (`15.06.2024` for `dd.MM.yyyy`) to its ISO form, format-aware. Missing/ISO
     *  fields stay ISO. */
    val dateFormats: Map<String, String> = emptyMap(),
    /** Each NUMBER field's scale (`maxFractionalDigits`) — lets `resolveValue` formal-check a value so a
     *  formally-invalid one (signed-zero, too-many-decimals, …) resolves to `Val.Unknown` even when it parses. */
    val scales: Map<String, Int?> = emptyMap(),
    /** Each [Kind.DATE_TIME] field's rendering format (the kernel's `DateTimeType.format`) — lets `resolveValue`
     *  canonicalize a rendered date-time to the ISO `yyyy-MM-dd'T'HH:mm:ss` form, format-aware. ISO default when unset. */
    val dateTimeFormats: Map<String, String> = emptyMap(),
    /** Each [Kind.DATE] field's [DatePrecision] — a `DAY_OPTIONAL` field accepts a day-omitted `00.MM.yyyy`
     *  value (well-formed, but resolved to `Val.Unknown` directly; `ValueAsDate` resolves it). Default FULL. */
    val datePrecisions: Map<String, DatePrecision> = emptyMap(),
    /** Each enumeration field's XML value list (declaration order) — the index domain for `EnumCategory`
     *  (`[Field -> Category]`) and the membership check. */
    val enumValues: Map<String, List<String>> = emptyMap(),
    /** Each enumeration field's category map (name → positional value list) — the `EnumCategory` mapping:
     *  the stored value's index in [enumValues] selects the category's value. */
    val enumCategories: Map<String, Map<String, List<String>>> = emptyMap(),
    /** Each [Kind.TIME] field's rendering format (the kernel's `TimeType.format`, e.g. `HH:mm:ss`) — lets
     *  `resolveValue` formal-check a TIME so a malformed value resolves to `Val.Unknown` (KERNEL-FINDINGS §6). */
    val timeFormats: Map<String, String> = emptyMap(),
    /** The model's declared base year (the kernel's `modelInfo.baseYear`), or null → the kernel default 2000 —
     *  the YEAR a year-less date FRAGMENT (`MM`, `MM-dd`) completes to when `resolveValue` canonicalizes it. */
    val baseYear: Int? = null,
    /** Each field's declared-constraint set (IF20/IF84) — so `resolveValue` applies the FULL formal check
     *  (min-fractional-digits, range, length, pattern, enum-domain, …), not just the format/scale check. A
     *  constraint-violating value then resolves to `Val.Unknown` — not-check-relevant — everywhere resolution
     *  is used (aggregate `starCells`, the `fillScope` fill tally), matching the kernel's `isWertAngegeben`. */
    val fieldConstraints: Map<String, FieldConstraints> = emptyMap(),
    /** Custom admission belongs to the call-bound validator, not ordinary StringType constraints (SPEC-2026-10-10-11). */
    val customFields: Set<String> = emptySet(),
    /** The model's configured time zone ([ModelTimeZone], `modelConfig.timeZone`, default UTC) — the formal
     *  check ([Document.formallyInvalid]) needs it because a Julian leap day such as `1500-02-29` is the kernel's
     *  `datumFormatFalsch` under Europe/Berlin and a real date under UTC (KERNEL-FINDINGS KF665). */
    val timeZone: ModelTimeZone = ModelTimeZone.UTC,
    /** Every repeatable group, including an unbounded one with no [declaredReps] entry. Starred-aggregate
     *  polarity needs the distinction: an unbounded repetition always retains a fillable tail. */
    val repeatableGroups: Set<String> = declaredReps.keys,
    /**
     * Every bounded GROUP address level: [declaredReps] plus the implicit maximum 1 of non-repeatable groups.
     * This is separate from [declaredReps] because declared-range iteration and aggregate polarity must not treat
     * a non-repeatable group as an authored repetition. Field terminal coordinates are excluded at transport.
     */
    val effectiveGroupRepetitionMaximums: Map<String, Int> = declaredReps,
    /** The characters a stored value may hold: the model's `supportedCharacters`, else every BMP character. A value
     *  outside it is formally invalid on every read, a computation's included (KERNEL-FINDINGS KF555). */
    val legalCharset: LegalCharset = LegalCharset.BMP,
)
