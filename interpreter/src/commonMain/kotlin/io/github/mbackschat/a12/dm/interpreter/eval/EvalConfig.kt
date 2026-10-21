package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Expr
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
    /** Complete call-local formal-admission gate for stored index identities. A semantic-index row is selected
     *  only after this predicate admits its index cell, so built-in constraints, registered custom types,
     *  over-limit coordinates, and partial-call unavailability all precede key projection. Null retains the
     *  document's model-derived built-in check for low-level [Interpreter] consumers. */
    val indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)? = null,
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
     *  kernel evaluates `Sum`/`Max`/`Min`/`NumberOfDifferentValues` only when the relevant set WILDCARDS the
     *  starred level; concrete per-row enumeration (even of all rows) is NOT enough (IF42). This one is
     *  UNIVERSAL over the reduced identifier set: a surviving concrete sibling suppresses it even beside a
     *  wildcard ([IF262]). `SumOfProducts` is NOT one of them — it carries the weaker [starFullyEnumerated]
     *  gate instead ([IF255]); a starred value-list entry and `NumberOfValueInFields` use [starExtentKnown],
     *  which accepts wildcard or complete declared-slot coverage ([KF55], [IF264], [IF310]); `FirstFilled` uses none of the three
     *  (it is order-aware via [cellRelevant]). **Null in full validation.** */
    val starRelevant: ((String, Int, List<Int>) -> Boolean)? = null,
    /** Partial validation only: whether a starred VALUE-LIST or `NumberOfValueInFields` entry's complete extent
     *  is established by a covering wildcard or complete declared-slot coverage. Concrete siblings do not
     *  suppress a wildcard (`PartialExtentGateShapeDiffTest`, IF264); concrete selections must cover the
     *  declaration, not merely held rows (`PartialDeclaredListExtentDiffTest`, KF55). **Null in full validation.** */
    val starExtentKnown: ((String, Int, List<Int>) -> Boolean)? = null,
    /** `SumOfProducts`' weaker completeness gate: wildcard OR a COMPLETE concrete enumeration of the declared
     *  repetitions. Takes the star path plus the CURRENT ROW's repetition coordinates, because completeness is
     *  decided per iteration row and not globally (IF255; predicate in `DmInterpreter`). */
    val starFullyEnumerated: ((String, Int, List<Int>) -> Boolean)? = null,
    /** Partial validation only: the relevant entity set passed to a [CustomCondition]'s `check` (the kernel's
     *  `relevantEntities` — `MainValidatorController` feeds it `getAllPruefungsrelevanteEntitiesExtern`). **Null
     *  in full validation** (the kernel passes `null` there, "all relevant"); a partial validation supplies the
     *  caller's relevant entities + the auto-added global fields, wildcards preserved. So a relevance-aware
     *  custom condition can return false when its fields are not relevant (IF38). */
    val customRelevant: Set<CustomConditionFieldPointer>? = null,
    /** Call-local formal-aware group presence at a group path and coordinate. Null for low-level/compute
     *  consumers, which retain the document's structural Boolean. Validation supplies the admitted-content +
     *  erroneous-group index. A coordinate rather than a context, because a keyed group no row carries is asked
     *  at a coordinate no context stands on (the unbound row, [UNKNOWN_REP]). */
    val groupPresence: ((String, List<Int>) -> GroupPresenceState)? = null,
    /** Partial validation only: whether a `NumberOfFilledGroups` operand is countable at all ([GroupCountGate]).
     *  The count does not take the field-by-field star gates, because A12 iterates the group rather than its
     *  fields (KF464). **Null in full validation.** */
    val groupCountGate: GroupCountGate? = null,
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
    /** The groups whose stored rows extend a `Having` filter's walk ([filterWalkRows]); every group when null.
     *  Validation counts every row. A computation counts only the groups holding a field some computation reads,
     *  measured on both kernel strategies by `adapter.laws.FilterMissingSlotDiffTest`. */
    val filterWalkGroups: ((String) -> Boolean)? = null,
    /** Whether an EMPTY cell at a path can be unavailable in this call, so a starred list reader must scan its rows
     *  for one (KERNEL-FINDINGS KF552). Validation answers it from the field's requiredness, computation from its
     *  preliminary-invalid index cells; true, the safe answer, for a low-level [Interpreter]. Without it every
     *  starred count paid a second row walk, doubling `GroupStarExpansionPerfTest` (2026-10-13). */
    val emptyMayBeUnavailable: (String) -> Boolean = { true },
) {

    /** Validation-owned partial group-predicate extent check. Full validation and computation retain the
     * held-row walk; only partial validation asks whether missing declared positions are covered
     * (`PartialStarredGroupCoverageDiffTest`, SPEC-2026-10-10-20/21). */
    internal var absentGroupsCovered: ((String, Int, List<Int>, Set<List<Int>>) -> Boolean)? = null


    /**
     * The call's deterministic work tally, or null in production — see [WorkLedger] for why a count and not a
     * clock. It rides the config rather than the constructor because the config is allocated ONCE per call and
     * reused wholesale by every sub-interpreter, so one ledger sees a whole `validate` / `compute` including
     * its `Having` and overlap sub-evaluations. Internal, so it stays out of the published ABI.
     */
    internal var work: WorkLedger? = null

    private var correlationShapes: MutableMap<Expr.Star, PreparedCorrelationShape>? = null

    /** SPEC-2026-10-10-02's dispatch and tally inspect the same immutable star across rows. The shape is a function
     *  of the expression alone, so it may outlive a document; anything read from a document may not
     *  ([ScopeMemo.unavailableRows]). */
    internal fun correlationShape(star: Expr.Star): PreparedCorrelationShape {
        val shapes = correlationShapes ?: mutableMapOf<Expr.Star, PreparedCorrelationShape>()
            .also { correlationShapes = it }
        return shapes.getOrPut(star) {
            PreparedCorrelationShape(
                star.having?.let { CorrelationJoin.parse(it, star.path.substringBeforeLast('/')) },
                star.bindDepth >= 0 && star.bindDepth < star.path.trim('/').split('/').size - 2,
            )
        }
    }
}
