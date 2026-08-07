package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType

/**
 * The per-evaluation configuration an [Interpreter] carries **unchanged** across a whole validate / compute
 * call and across every `Having` / overlap sub-interpreter it spawns — everything except the row [ctx] and the
 * two genuinely per-row inputs ([Interpreter]'s `customErrorField` and `invalid`). Bundled so a sub-interpreter
 * reuses it wholesale instead of reconstructing ~12 args (CLAUDE.md's builder-over-long-parameter-list rule),
 * and so it is allocated ONCE per call (in [io.github.mbackschat.a12.dm.interpreter.DmInterpreter]) rather than
 * per row. Constructed with named arguments (the fields are largely same-typed and would otherwise be
 * swap-prone). Supplied by `DmInterpreter`; every field defaults to the differential's no-project-config value.
 */
class EvalConfig(
    val clock: EvalClock? = null,
    /** The model's configured base year (the kernel's `modelInfo.baseYear`), or null when the model declares
     *  none — the value of the `BaseYear` point-in-time constant. Model config (NOT from the clock). */
    val baseYear: Int? = null,
    /** Repeatable groups' index-field paths (the kernel's `indexFieldName`s) — the `SemanticIndex`
     *  (`[field For "value"]`) lookup resolves a group's index field from this. */
    val indexFields: Set<String> = emptySet(),
    /** The model's configured time zone ([ModelTimeZone], `modelConfig.timeZone`, default UTC) — drives
     *  datetime instant math, stateful `DifferenceInDays` calendar steps, wall-day AddDays landings, and
     *  legacy-calendar Date construction (KERNEL-FINDINGS §6). */
    val timeZone: ModelTimeZone = ModelTimeZone.UTC,
    /** The consumer-supplied predefined-type registry (the kernel's `IPredefinedType` client SPI) — the
     *  validation domain for `Valid(field, "Name")` / `Invalid(field, "Name")`. An unregistered type name →
     *  the rule is reported unsupported (fail-fast). Default empty. */
    val predefinedTypes: Map<String, PredefinedType> = emptyMap(),
    /** Consumer-supplied custom conditions (the kernel's `ICustomCondition` SPI, cross-platform), keyed by name —
     *  the validation domain for `CustomCondition X`. An unregistered name → the rule is reported unsupported.
     *  Default empty. */
    val customConditions: Map<String, CustomCondition> = emptyMap(),
    /** Consumer-supplied CODE-BASED custom field types (the kernel's `ICustomFieldType` SPI) — the imperative tail
     *  of `Valid(field, "Name")` / `Invalid(field, "Name")`, consulted ONLY after [predefinedTypes] misses (so that
     *  declarative path stays monomorphic). Default empty. */
    val customFieldTypes: Map<String, CustomFieldType> = emptyMap(),
    /** Engine-owned full-match seam. DmInterpreter supplies its distinct-pattern registry; direct low-level
     *  Interpreter consumers retain the same semantics through the default compiler. */
    val patternMatches: (String, String) -> Boolean = { pattern, value ->
        io.github.mbackschat.a12.dm.interpreter.regex.compilePattern(pattern).matches(value)
    },
    /** Partial validation only: whether a star-aggregate CELL at `(path, reps)` is relevant — "cell" being the
     *  declared house term for a repetition's field value (the House ↔ kernel vocabulary table in
     *  docs/KERNEL-SEMANTICS.md). A starred aggregate with ANY non-relevant cell evaluates to
     *  [io.github.mbackschat.a12.dm.interpreter.ast.Val.Unknown] — the
     *  kernel treats the aggregate as non-evaluable under a subset relevant set (it does NOT apply directional 3VL
     *  to the non-relevant cells, kernel-probed; IF35). **Null in full validation** — the gate is skipped entirely
     *  (no `starCells` call, so the flat-spike `RepDoc` context is unaffected). */
    val cellRelevant: ((String, List<Int>) -> Boolean)? = null,
    /** Partial validation only: whether an ALL-ROWS aggregate's starred path is **wildcard-relevant** — the
     *  kernel evaluates `Sum`/`Max`/`Min`/`NumberOfDifferentValues`/`SumOfProducts` only when the relevant set
     *  WILDCARDS the starred level; concrete per-row enumeration (even of all rows) is NOT enough (IF42).
     *  `FirstFilled` does NOT use this (it is order-aware via [cellRelevant]). **Null in full validation.** */
    val starRelevant: ((String) -> Boolean)? = null,
    /** `SumOfProducts`' weaker completeness gate: wildcard OR a COMPLETE concrete enumeration (DmInterpreter). */
    /** Star path + the CURRENT ROW's repetition coordinates: completeness is per iteration row, not global. */
    val starFullyEnumerated: ((String, List<Int>) -> Boolean)? = null,
    /** Partial validation only: the relevant entity set passed to a [CustomCondition]'s `check` (the kernel's
     *  `relevantEntities` — `MainValidatorController` feeds it `getAllPruefungsrelevanteEntitiesExtern`). **Null
     *  in full validation** (the kernel passes `null` there, "all relevant"); a partial validation supplies the
     *  caller's relevant entities + the auto-added global fields, wildcards preserved. So a relevance-aware
     *  custom condition can return false when its fields are not relevant (IF38). */
    val customRelevant: Set<CustomConditionFieldPointer>? = null,
    /** Call-local formal-aware group presence. Null for low-level/compute consumers, which retain the document's
     *  structural Boolean. Full validation supplies the admitted-content + erroneous-group index. */
    val groupPresence: ((EvalContext, String) -> GroupPresenceState)? = null,
    /** Compute-only refinement for an identity-preserving [ScanCell]. The general document supplies the raw
     *  presence/format state; the computation call supplies its complete call-local formal oracle here so
     *  charset, custom-type, declared-constraint, and over-limit failures poison group/star scans exactly like
     *  direct reads. Null outside the computation engine. */
    val computeScanState: ((ScanCell) -> CellState)? = null,
    /**
     * COMPUTE-mode evaluation semantics (IF85 — the kernel's calculation path, which differs from validation):
     * - a read of a formally-invalid cell POISONS the computation instance ([PoisonedReadException]) instead of
     *   the three-valued "not check-relevant" exclusion — the `invalid` gate THROWS (supplied by
     *   `DmInterpreter.computeInstances`), a star cell resolving to Unknown throws at its read site;
     * - the field-fill quantifiers SCAN their cells in row order with the kernel's per-operator stop — a cell
     *   after the deciding one is never read, so it cannot poison;
     * - `And` short-circuits a not-TRUE left, `Or` a TRUE left (the generated `isKnownAndTrue` collapse);
     * - a quantifier/aggregate `Having` keeps only TRUE rows (the 2-valued collapse — validation's not-FALSE
     *   keep-law does not apply on the compute path).
     * False (the default) = validation semantics, unchanged.
     */
    val computeMode: Boolean = false,
)
