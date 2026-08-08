package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.ComputationApplication
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.FunctionOp
import io.github.mbackschat.a12.dm.interpreter.eval.GroupRelevance
import io.github.mbackschat.a12.dm.interpreter.eval.renderMessage
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.Computed
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.MessageLabelProvider
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedTypeRegistry
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleResult
import io.github.mbackschat.a12.dm.interpreter.model.Suppression
import io.github.mbackschat.a12.dm.interpreter.model.UndeclaredReference
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule
import io.github.mbackschat.a12.dm.interpreter.model.ValidationOutcome
import io.github.mbackschat.a12.dm.interpreter.model.Verdict
import io.github.mbackschat.a12.dm.interpreter.regex.PatternRegistry
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmSynthetic
import io.github.mbackschat.a12.dm.interpreter.workspace.ImmutableProjection

/**
 * The public entry point — evaluates a [ModelDef]'s rules over a [Document] instance, kernel-free, on
 * JVM and JS alike. The standalone-library façade (proposal goal 2) and the engine dmtool's native
 * image consumes for the runtime verbs (goal 1). Correctness = agrees with the real kernel (verified by
 * the `:adapter` differential tests).
 *
 * Full validation ([validateFull]/[validate]), partial validation ([validatePart]), single-rule eval
 * ([testRule]/[evalCandidate]), and computations ([compute]) — the kernel's `validateFull`/`validatePart`/
 * `compute` twins, kernel-free.
 */
/** A `Long` holds a wildcard mask for a path this deep; beyond it `reducedByPatternScan` takes over. */
private const val MASKABLE_DEPTH = 63

class DmInterpreter private constructor(
    private val model: ModelDef,
    private val clock: EvalClock?,
    /** The consumer-supplied predefined-type registry (`Valid`/`Invalid(field, "Name")`) — the kernel's
     *  `IPredefinedType` client SPI. Defaults to the built-in common set ([PredefinedTypeRegistry.DEFAULT]);
     *  a consumer may pass its own (built-ins + project types). An unregistered name → the rule is unsupported. */
    private val predefinedTypes: Map<String, PredefinedType>,
    /** Consumer-supplied custom conditions (the kernel's `ICustomCondition` SPI, cross-platform JVM+JS), keyed
     *  by name — `CustomCondition X`. Default empty: a model using an unregistered custom condition reports it
     *  unsupported (fail-fast). A JVM consumer adapts existing kernel `ICustomCondition` impls onto the SPI; a
     *  JS consumer implements the SPI directly. */
    private val customConditions: Map<String, CustomCondition>,
    /** Consumer-supplied CODE-BASED custom field types ([CustomFieldType] — the kernel's `ICustomFieldType` SPI,
     *  for imperative checksum types), keyed by name. Default empty. Consulted ONLY after [predefinedTypes] misses,
     *  so the declarative per-cell path stays monomorphic ([CustomFieldType]'s KDoc; `CustomTypePerfTest`). Each
     *  relevant concrete value is checked once per call with its locale/effective bounds/stored-value context;
     *  the cached code/message result drives both emission and suppression. A custom-typed cell whose name is in
     *  NEITHER registry is reported unsupported (the lenient degrade). */
    private val customFieldTypes: Map<String, CustomFieldType>,
    /** The consumer-supplied legal character set field values are validated against — the SPI for a project charset
     *  the DM-JSON does NOT carry (e.g. **DIN 91379**, the EU public-administration set), IF43. **Default
     *  [LegalCharset.BMP]** (the kernel's runtime default: an emoji / supplementary-plane char is illegal). This is
     *  the FALLBACK: when the model itself declares `modelConfig.supportedCharacters` ([ModelDef.legalCharset],
     *  IF120) that model-declared slot takes precedence (it is what the kernel bakes into codegen and enforces), and
     *  this SPI applies only when the model declares none. The interpreter must expose every kernel customization to
     *  be 100% compatible. */
    private val legalCharset: LegalCharset,
    /** Consumer override for `$field$` message names. A non-null result wins exactly (including empty); null
     *  falls through to the nonempty model label, then the field's indexed debug identity. */
    private val messageLabelProvider: MessageLabelProvider?,
    plans: ModelStaticPlans?,
) {

    /**
     * The public construction path, unchanged. It owns a private [ModelStaticPlans], so a directly constructed
     * evaluator behaves exactly as before; sharing happens only through [PreparedModel].
     */
    @JvmOverloads
    constructor(
        model: ModelDef,
        clock: EvalClock? = null,
        predefinedTypes: Map<String, PredefinedType> = PredefinedTypeRegistry.DEFAULT,
        customConditions: Map<String, CustomCondition> = emptyMap(),
        customFieldTypes: Map<String, CustomFieldType> = emptyMap(),
        legalCharset: LegalCharset = LegalCharset.BMP,
        messageLabelProvider: MessageLabelProvider? = MessageLabelProvider.MODEL_LABEL_OR_NAME,
    ) : this(
        model, clock, predefinedTypes, customConditions, customFieldTypes, legalCharset, messageLabelProvider,
        plans = null,
    )

    /** The model-static preparation this evaluator reads: shared when supplied, private otherwise. */
    private val plans: ModelStaticPlans = plans ?: ModelStaticPlans.of(model)

    companion object {
        /**
         * Build an evaluator that SHARES [plans] with every other evaluator over the same model — the sharing
         * boundary the P-SERVICE profile needs, reached through [PreparedModel.createInterpreter] rather than by
         * a consumer assembling one.
         *
         * This is a factory rather than an `internal` constructor on purpose: Kotlin emits an `internal`
         * constructor as `ACC_PUBLIC`, so a Java caller could reach it and bypass the boundary. That exact trap
         * was closed once before for `A12PartiallyKnownMultiPointer` (see the interpreter CHANGELOG) and must not
         * be reopened. `@JvmSynthetic` hides this function from Java; it cannot be applied to a constructor.
         */
        @JvmSynthetic
        internal fun sharing(
            model: ModelDef,
            clock: EvalClock?,
            predefinedTypes: Map<String, PredefinedType>,
            customConditions: Map<String, CustomCondition>,
            customFieldTypes: Map<String, CustomFieldType>,
            legalCharset: LegalCharset,
            messageLabelProvider: MessageLabelProvider?,
            plans: ModelStaticPlans,
        ): DmInterpreter = DmInterpreter(
            model, clock, predefinedTypes, customConditions, customFieldTypes, legalCharset,
            messageLabelProvider, plans,
        )
    }

    /** The effective legal charset: the model-declared `supportedCharacters` ([ModelDef.legalCharset], IF120) when
     *  present, else the consumer-supplied [legalCharset] SPI. */
    private val effectiveLegalCharset: LegalCharset = model.legalCharset ?: legalCharset
    private val patterns = PatternRegistry()

    /** Model-static path prefixes and repetition bounds, prepared once per MODEL rather than per evaluator. */
    private val paths = this.plans.paths

    /** The auto-check emitter + formal-status oracle — the call-invariant I2 seam collaborator (IF117). */
    private val checks = AutoChecks(
        model,
        effectiveLegalCharset,
        predefinedTypes,
        customFieldTypes,
        patterns,
        paths,
        ::messageLabel,
    )

    /** The iteration-scope machinery + the RepetitionNotUnique evaluator — call-invariant I2 seam collaborators
     *  (IF117), now model-owned so per-request evaluators share one build. */
    private val scopes = this.plans.scopes
    private val rnuEval = this.plans.repetitionNotUnique

    /** Nullable deterministic seam used only by model-static performance guards. It delegates to the shared
     *  preparation, because that is where the model-static work now happens. */
    internal var preparationObserver: PreparationObserver?
        get() = plans.preparationObserver
        set(value) { plans.preparationObserver = value }

    private val parsedModel = this.plans.parsed
    /** Nullable, instance-local structural seam used only by the cache-lifetime guard. */
    internal var cacheObserver: CacheObserver? = null
    private val preparedModel = PreparedEvaluationPlan(
        model,
        this.plans,
        support = PreparationSupport(
            clock = clock,
            predefinedTypes = predefinedTypes,
            customConditions = customConditions,
            customFieldTypes = customFieldTypes,
            patterns = patterns,
        ),
    )
    private val engine = ComputationEngine(
        model,
        clock,
        predefinedTypes,
        customFieldTypes,
        preparedModel,
        this.plans,
        checks,
        patterns = patterns,
        cacheObserver = { cacheObserver },
    )

    /**
     * Audit this prepared model without consulting document values. Every stored rule, implicit computation
     * validation, computation guard/alternative, customization requirement, and host-sensitive primitive is
     * traversed. A non-empty result means an evaluator must refuse performance/certification work up front;
     * [validate] remains the per-instance outcome-bearing check for failures raised by a registered callback.
     */
    fun supportAudit(): List<UnsupportedRule> = preparedModel.audit()

    /**
     * Every reference in this model's rules and computations that names an entity the model does not declare —
     * value-independent, so it answers before any document is read.
     *
     * Separate from [supportAudit] on purpose: an undeclared reference is not a construct we cannot evaluate but
     * a model the kernel's consistency gate rejects (`MVK_INVALID_ENTITY`), and evaluating it produces a
     * confident answer about a rule that cannot exist. It has no single failure shape either — a presence
     * predicate reads the missing cell as empty and FIRES, a starred aggregate collapses to an empty domain and
     * PASSES, and a value position throws — so a caller that wants to be honest has to ask up front rather than
     * interpret whatever came back.
     *
     * The domain checked against is the model's declared entity set, and the reference has already been bound by
     * the model's own rules, so a legal absolute / `..`-relative / `..Name` / short-name / starred / multi-star
     * reference resolves to a declared path and is not reported. Implicit computation-validation rules are
     * excluded because they are generated from the computations that are checked here.
     */
    fun undeclaredReferences(): List<UndeclaredReference> =
        (model.rules.flatMap { undeclaredReferences(it) } + undeclaredComputationReferences()).distinct()

    /**
     * The same check for ONE rule's condition — a stored rule or an ad-hoc `--condition`/`--field` candidate.
     *
     * A focused caller uses this rather than [undeclaredReferences] so it stays focused: the whole-model form
     * forces every rule's AST, which would defeat the parse-once-lazily design that lets [testRule] prepare only
     * the rule it touches. It also keeps the refusal proportionate — an unrelated rule's bad reference cannot
     * change this rule's verdict, so it is not this call's business to report.
     */
    fun undeclaredReferences(rule: RuleDef): List<UndeclaredReference> =
        undeclared(rule.path, namedEntityPaths(parsedModel.condAstOf(rule))).distinct()

    /**
     * The computation half on its own — every guard and operation. Two callers need it apart from the whole-model
     * query: [compute], which validates nothing; and a FOCUSED rule evaluation, which reaches a computation's
     * reference despite being focused whenever the form-engine flow applies the computations first. A focused
     * evaluation that skips that step reaches only its own rule, because [testRule]/[evalCandidate] never touch
     * the implicit computation-validation rules that whole-model [validate] evaluates.
     */
    fun undeclaredComputationReferences(): List<UndeclaredReference> = model.computations.flatMap { comp ->
        val locus = comp.name?.let { "${comp.group}/$it" } ?: comp.targetPath
        val parsed = parsedModel.parsedOf(comp)
        val conditions = listOfNotNull(parsed.commonPrecondition) + parsed.alternatives.mapNotNull { it.precondition }
        conditions.flatMap { undeclared(locus, namedEntityPaths(it)) } +
            parsed.alternatives.flatMap { undeclared(locus, namedEntityPaths(it.operation)) }
    }.distinct()

    private fun undeclared(locus: String, paths: List<String>): List<UndeclaredReference> =
        paths.filter { it !in model.entityPaths }.map { UndeclaredReference(locus, it) }

    /** Test/measurement seam: number of distinct sources compiled by this engine owner. */
    internal fun patternCompilationCount(): Int = patterns.compilationCount

    /**
     * Run every rule over each row of its iteration scope and return one [Message] per firing — the
     * runtime twin of the kernel's `validateFull`. A rule fires when its error condition is TRUE on a row.
     *
     * The messages-only convenience over [validate]. It **fails loudly** (throws) when any rule is
     * [ValidationOutcome.unsupported] — a state the kernel can never reach (it supports every construct), so a
     * silent messages-only projection there would be a FALSE-CLEAN result (IF139). Callers that want the raw
     * firings plus the unsupported report use [validate]; a caller that deliberately wants messages while
     * ignoring unsupported writes `validate(doc).messages` explicitly.
     */
    fun validateFull(doc: Document, locale: String = "en_US"): List<Message> {
        val outcome = validate(doc, locale)
        check(outcome.unsupported.isEmpty()) {
            "validateFull would silently drop ${outcome.unsupported.size} unsupported rule(s): " +
                outcome.unsupported.joinToString(prefix = "[", postfix = "]") { "${it.rulePath} (${it.reason})" } +
                " — use validate(doc) for the ValidationOutcome (messages + unsupported), or " +
                "validate(doc).messages to project messages only on purpose."
        }
        return outcome.messages
    }

    /**
     * The tolerant whole-document validation: each rule's firings as [Message]s, plus the rules that use
     * a construct the interpreter does not model (an unknown operator, or a deliberately external one
     * like `CustomCondition`) reported as [ValidationOutcome.unsupported] instead of crashing the run —
     * mirroring the kernel-adapter's `Opaque` read. Genuine bugs still throw.
     */
    fun validate(doc: Document, locale: String = "en_US"): ValidationOutcome =
        validateWith(doc, locale, ALL_RELEVANT)

    /**
     * Tolerant **partial** validation — the runtime twin of the kernel's `validatePart(DocumentV2,
     * relevantEntities, config)`. Only the rules whose **error field** is relevant **at the repetition levels the rule's ITERATION bound** run — a level the iteration never bound (rule iteration is reference-driven, so the declared group does not decide this) counts as a wildcard, and the message reports at the default first repetition (IF268) — measured against
     * [relevant]; a rule whose error field is in a repeatable group the rule does not iterate therefore
     * runs when ANY of that group's rows is relevant. Those rules are
     * evaluated; of those, any reference to a field/group instance **not** in [relevant] resolves to
     * `Val.Unknown`, so the existing 3VL produces the kernel's exact suppression — `true And Unknown`
     * suppresses, `true Or Unknown` still fires (the "no possible value could prevent the error" clause is
     * just Kleene logic). The auto-generated checks (formal/mandatory/unique) are likewise gated by their
     * field's relevance. A relevant group makes all its descendants relevant; [RelevantEntity.ALL] wildcards
     * a repetition level (the encouraged form). With the whole document relevant this equals [validate].
     * KERNEL-FINDINGS "Partial validation".
     *
     * Matches the kernel across the partial surface: rule-gating + 3VL suppression; starred aggregates under a
     * *subset* of relevant repetitions gated UNKNOWN (IF35); GLOBAL fields auto-relevant (IF37, the kernel's
     * md-layer behavior); a `CustomCondition` receives the relevant set (IF38); and uniqueness (`uniqueIndex` /
     * `RepetitionNotUnique`) needs the duplicate partner relevant too (IF39). All locked by `…laws/PartialValidation*DiffTest`.
     */
    fun validatePart(doc: Document, relevant: Set<RelevantEntity>, locale: String = "en_US"): ValidationOutcome {
        val queries = relevanceQueriesOf(relevant)
        return validateWith(
            doc,
            locale,
            queries.relevant,
            customRelevantOf(relevant),
            starRelevantOf(relevant),
            starExtentKnownOf(relevant),
            starFullyEnumeratedOf(relevant),
            groupRelevanceOf(relevant),
            queries::relevantWithin,
        )
    }

    /**
     * Whether a STARRED operation has complete extent in PARTIAL mode (IF42, IF262). The kernel evaluates an
     * all-rows aggregate (`Sum`/`Max`/`Min`/`NumberOfDifferentValues`, but NOT `SumOfProducts`) only when its reduced
     * relevant identifiers wildcard every repeatable level traversed by the star. Relevant ancestor groups first
     * expand to the operand path, and a broader wildcard identifier removes every narrower identifier it encompasses.
     * After that reduction, ANY remaining concrete coordinate at a starred level makes the extent unknown.
     * `FirstFilled` does not use this path because it is order-aware. The same fact also gates a starred VALUE-LIST
     * quantifier's complete extent (`No` either side / `NotAll` values side, SPEC-2026-07-22-09).
     */
    private fun starRelevantOf(relevant: Set<RelevantEntity>): (String, Int, List<Int>) -> Boolean {
        val candidates = wellFormed(relevant)
        val reducedByPath = HashMap<String, List<RelevantEntity>>()
        val decided = HashMap<Triple<String, Int, List<Int>>, Boolean>()
        return { starPath, bindDepth, rowReps ->
            val scope = starScope(starPath, bindDepth, rowReps)
            decided.getOrPut(Triple(starPath, bindDepth, scope.bound)) {
                reducedByPath.getOrPut(starPath) { reducedStarRelevantCandidates(candidates, starPath) }
                    .filter { addressesRow(it, scope.bound) }
                    .let { scoped ->
                        scoped.isNotEmpty() && scope.levels.none { level ->
                            scoped.any { it.indices[level] != RelevantEntity.ALL }
                        }
                    }
            }
        }
    }

    /**
     * A starred VALUE-LIST entry's complete-extent gate — the same star anchor and per-iteration-row scope as
     * [starRelevantOf], but **EXISTENTIAL**: one covering relevant identifier that wildcards every reopened
     * level establishes the extent, and concrete siblings surviving beside it do NOT suppress it.
     *
     * The two gates were briefly one predicate, on the reasoning that they answer the same structural question.
     * They do not. On `{field wildcard} + {concrete group rows}` the kernel FIRES a starred `No` and is SILENT
     * on `Sum` over the same rows, on both codegen strategies — so folding the value-list site into [IF262]'s
     * reduction made the interpreter suppress where the kernel fires. Seven relevant-set shapes measured side by
     * side in `PartialExtentGateShapeDiffTest`; the split is IF264.
     */
    private fun starExtentKnownOf(relevant: Set<RelevantEntity>): (String, Int, List<Int>) -> Boolean {
        val candidates = wellFormed(relevant)
        val decided = HashMap<Triple<String, Int, List<Int>>, Boolean>()
        return { starPath, bindDepth, rowReps ->
            val scope = starScope(starPath, bindDepth, rowReps)
            decided.getOrPut(Triple(starPath, bindDepth, scope.bound)) {
                candidates.any { e ->
                    covers(e, starPath) && addressesRow(e, scope.bound) &&
                        scope.levels.all { i -> i >= e.indices.size || e.indices[i] == RelevantEntity.ALL }
                }
            }
        }
    }

    /**
     * The kernel expands each relevant ancestor group into an identifier for every contained field, then removes
     * identifiers encompassed by a broader wildcard identifier of the same field name. Its `EntityIterator`
     * rejects an aggregate extent when ANY identifier left for that operand is concrete at a starred level.
     * Therefore "any wildcard wins" is wrong: `[1,*,1]` beside an expanded `[0,1,*]` stays mixed and is UNKNOWN,
     * while `[*,*,*]` encompasses and removes the concrete sibling before the iterator asks.
     *
     * Reduction happens before the per-row subtree filter, matching `ValidationCache.reduceRedundancies` followed
     * by `EntityIterator.certainlyNotInSameSubtree`. This preserves IF257's row-local answer when one outer row is
     * wildcarded and another is concrete. SumOfProducts does not use this combiner iterator and keeps the separate
     * [starFullyEnumeratedOf] path. See IF262 for the source trace and discriminating cases.
     *
     * **Cost.** A candidate is removed exactly when the set contains a tuple whose wildcard mask is a STRICT
     * SUPERSET of the candidate's and whose remaining coordinates match it — so only masks with a strictly
     * larger wildcard count can remove anything, which is what [wildcardMasksByBreadth] orders by. Testing a
     * mask first and materializing the broader tuple only on a hit is what keeps this off the quadratic path:
     * scanning every distinct mask and hashing a fresh list for each took **1306 ms** on 3000 relevant
     * identifiers whose masks form an antichain (equal wildcard counts, so nothing removes anything and every
     * probe was wasted), growing 3.86x when the set doubled — measured, not predicted, by
     * `StarRelevanceReductionGrowthTest`. The residual worst case is a set mixing many wildcard counts, bounded
     * by cheap `Long` comparisons rather than allocation.
     */
    private fun reducedStarRelevantCandidates(
        relevant: List<RelevantEntity>,
        starPath: String,
    ): List<RelevantEntity> {
        val depth = pathParts(starPath).size
        val expanded = relevant.asSequence()
            .filter { covers(it, starPath) }
            .map { entity ->
                RelevantEntity(starPath, entity.indices + List(depth - entity.indices.size) { RelevantEntity.ALL })
            }
            .distinctBy { it.indices }
            .toList()
        if (depth > MASKABLE_DEPTH) return reducedByPatternScan(expanded)
        val tuples = expanded.mapTo(HashSet()) { it.indices }
        val byBreadth = wildcardMasksByBreadth(expanded)
        return expanded.filter { candidate ->
            val own = wildcardMask(candidate.indices)
            val ownBreadth = countOneBits(own)
            byBreadth.none { (breadth, masks) ->
                breadth > ownBreadth && masks.any { mask ->
                    mask and own == own && broaderTuple(candidate.indices, mask) in tuples
                }
            }
        }
    }

    /**
     * The same reduction for an operand path deeper than a `Long` has bits for — the direct pairwise scan,
     * kept because it needs no mask and is provably equivalent. It is quadratic, but a path this deep does not
     * occur: reaching it needs more than [MASKABLE_DEPTH] segments in one field path.
     */
    private fun reducedByPatternScan(expanded: List<RelevantEntity>): List<RelevantEntity> {
        val tuples = expanded.mapTo(HashSet()) { it.indices }
        val patterns = expanded.asSequence()
            .map { entity -> entity.indices.map { it == RelevantEntity.ALL } }
            .filter { pattern -> pattern.any { it } }
            .distinct()
            .toList()
        return expanded.filter { candidate ->
            patterns.none { pattern ->
                val broader = candidate.indices.mapIndexed { level, index ->
                    if (pattern[level]) RelevantEntity.ALL else index
                }
                broader != candidate.indices && broader in tuples
            }
        }
    }

    /**
     * The distinct wildcard masks present, grouped by how many levels each wildcards and ordered by that count.
     * Only a mask that wildcards strictly MORE levels than a candidate can encompass it, so a candidate whose
     * own breadth is maximal — the antichain case — skips every group without touching a single mask.
     */
    private fun wildcardMasksByBreadth(expanded: List<RelevantEntity>): List<Pair<Int, List<Long>>> =
        expanded.asSequence()
            .map { wildcardMask(it.indices) }
            .filter { it != 0L }
            .distinct()
            .groupBy { countOneBits(it) }
            .toList()
            .sortedBy { it.first }

    private fun wildcardMask(indices: List<Int>): Long {
        var mask = 0L
        indices.forEachIndexed { level, index -> if (index == RelevantEntity.ALL) mask = mask or (1L shl level) }
        return mask
    }

    private fun broaderTuple(indices: List<Int>, mask: Long): List<Int> =
        indices.mapIndexed { level, index ->
            if (mask shr level and 1L == 1L) RelevantEntity.ALL else index
        }

    private fun countOneBits(mask: Long): Int {
        var rest = mask
        var count = 0
        while (rest != 0L) {
            rest = rest and (rest - 1)
            count++
        }
        return count
    }

    /** The relevant entities carrying one index per path segment — the shape both gates can reason about. */
    private fun wellFormed(relevant: Set<RelevantEntity>): List<RelevantEntity> =
        relevant.filter { it.indices.size == pathParts(it.path).size }

    /** Whether [e] speaks about [starPath] at all: its path is an ancestor-or-equal prefix. */
    private fun covers(e: RelevantEntity, starPath: String): Boolean {
        val sParts = pathParts(starPath)
        val eParts = pathParts(e.path)
        return eParts.size <= sParts.size && eParts == sParts.take(eParts.size)
    }

    /** Whether [e] says anything about the row [bound] pins — an entity on a different outer row does not. */
    private fun addressesRow(e: RelevantEntity, bound: List<Int>): Boolean =
        bound.indices.all { i ->
            i >= e.indices.size || e.indices[i] == RelevantEntity.ALL || e.indices[i] == bound[i]
        }

    /** The repetition coordinates the current row BINDS for a starred operand, and the levels the star iterates. */
    private class StarScope(val bound: List<Int>, val levels: List<Int>)

    /**
     * Split [starPath]'s repeatable levels into the ones the current row fixes and the ones the star iterates.
     *
     * The split is [io.github.mbackschat.a12.dm.interpreter.ast.Expr.Star.bindDepth] — the segments strictly
     * above the FIRST `*`-marked group (IF94) — and **not** the path's deepest repeatable level. For a
     * single-star operand the two coincide, which is why deriving it from the path alone survived every
     * single-star probe. For `Outer* then Inner* then Qty` they differ by exactly one level, and that level is
     * one the row would claim to bind while the operand in fact spans every one of its rows: pinning `Outer` to
     * row 1 then read as "everything unbound is wildcarded", and the interpreter fired where the kernel is
     * silent (IF259). A hand-built node (−1) keeps the leaf-parent default, where the two coincide by definition.
     */
    private fun starScope(starPath: String, bindDepth: Int, rowReps: List<Int>): StarScope {
        val parts = pathParts(starPath)
        fun repeatableAt(i: Int) = "/" + parts.take(i + 1).joinToString("/") in model.repeatable
        val firstStar = if (bindDepth >= 0) minOf(bindDepth, parts.size)
            else parts.indices.lastOrNull { repeatableAt(it) } ?: 0
        val bound = rowReps.take(minOf(firstStar, rowReps.size))
        return StarScope(bound, parts.indices.filter { it >= bound.size && repeatableAt(it) })
    }

    /**
     * `SumOfProducts`' own, WEAKER completeness notion. It does not share [starRelevantOf]'s wildcard-only gate
     * (it runs a hand-written two-list loop with no three-value pre-survey), but it is not ungated either: the
     * kernel evaluates it under a CONCRETE enumeration only when that enumeration is COMPLETE. Measured against
     * the dynamic kernel on `/Cart/Lines[2]` (`PartialValidationAggregateDiffTest`):
     *
     * | relevant set | kernel |
     * |---|---|
     * | wildcard | fires |
     * | both rows enumerated | fires |
     * | row 1 only / row 2 only | **silent** |
     *
     * Exempting it from the gate ENTIRELY — reading "concrete enumeration evaluates" off the both-rows case
     * alone — made the interpreter fire where the kernel is silent, on 5 of the fuzz net's 180 (document,
     * relevant-subset) combinations. Completeness is measured against the DECLARED repetitions, which is what
     * the probe varied; a document with fewer rows than declared is not separately measured.
     *
     * **Completeness is a property of the TUPLES, not of each level independently** — the distinction only
     * appears once two repeatable levels are nested, which is why the one-level probe above could not see it.
     * Enumerating `Outer[1]/Inner[1]` and `Outer[2]/Inner[2]` covers `{1,2}` at both levels while covering only
     * the diagonal, half of the four rows; reading per-level unions called that complete and fired where the
     * kernel is silent. So a candidate must cover the full CROSS-PRODUCT of the declared repetitions of the
     * levels the star iterates.
     *
     * The cover is taken over the **union of every addressing entity**, not per wildcard pattern. Deciding it
     * per pattern suppressed a set whose union is complete but whose individual patterns are not — `[*,*,1]`
     * together with `[*,1,2]` and `[*,2,2]` covers all four pairs, and the kernel fires (IF260).
     */
    private fun starFullyEnumeratedOf(relevant: Set<RelevantEntity>): (String, Int, List<Int>) -> Boolean {
        // Filtered ONCE per call. Deciding completeness by rescanning the relevant set for every declared
        // repetition is O(relevant² × reps), and enumerating every row makes the relevant set grow WITH the
        // document — measured 8.63× growth for 4× rows on `laws/PartialSumOfProductsPerfTest` (a growth
        // figure, so interpreter-only and unaffected by that guard's since-corrected kernel baseline).
        val candidates = wellFormed(relevant)
        val decided = HashMap<Triple<String, Int, List<Int>>, Boolean>()
        return { starPath, bindDepth, rowReps ->
            val scope = starScope(starPath, bindDepth, rowReps)
            decided.getOrPut(Triple(starPath, bindDepth, scope.bound)) {
                coversEveryRepetition(candidates, starPath, scope)
            }
        }
    }

    /**
     * Whether the relevant entities addressing this row cover every declared repetition TUPLE of the levels
     * [starPath]'s star iterates. Levels **deeper** than an entity's own path need no enumeration: a relevant
     * group makes every descendant relevant, which is the kernel's own rule, so such a level reads as wildcarded.
     */
    private fun coversEveryRepetition(
        candidates: List<RelevantEntity>,
        starPath: String,
        scope: StarScope,
    ): Boolean {
        if (scope.levels.isEmpty()) return candidates.any { covers(it, starPath) && addressesRow(it, scope.bound) }

        val parts = pathParts(starPath)
        val declared = IntArray(scope.levels.size) { k ->
            model.declaredReps["/" + parts.take(scope.levels[k] + 1).joinToString("/")] ?: return false
        }
        // Each addressing entity becomes a BOX over the iterated levels — a concrete index pins that level,
        // ALL (and any level deeper than the entity's own path) spans it.
        val boxes = candidates.mapNotNull { e ->
            if (!covers(e, starPath) || !addressesRow(e, scope.bound)) null
            else IntArray(scope.levels.size) { k ->
                val i = scope.levels[k]
                if (i >= e.indices.size) RelevantEntity.ALL else e.indices[i]
            }
        }
        return boxesCoverEveryTuple(0, declared, boxes)
    }

    /**
     * Whether a union of boxes covers the full cross-product of [declared], from level [k] down.
     *
     * Recursing on the boxes that WILDCARD level `k` first is what keeps this linear in practice: if they alone
     * cover the remaining levels, every index of `k` is covered and no per-index descent is needed. Otherwise
     * each declared index must be covered by the wildcards plus the boxes pinning it — and an index no box
     * pins at all fails immediately, so the descent is bounded by the concrete indices actually present rather
     * than by the declared repetition counts.
     */
    private fun boxesCoverEveryTuple(k: Int, declared: IntArray, boxes: List<IntArray>): Boolean {
        if (k == declared.size) return boxes.isNotEmpty()
        val wild = boxes.filter { it[k] == RelevantEntity.ALL }
        if (boxesCoverEveryTuple(k + 1, declared, wild)) return true
        val pinned = boxes.filter { it[k] != RelevantEntity.ALL }.groupBy { it[k] }
        return (1..declared[k]).all { v ->
            val here = pinned[v] ?: return false
            boxesCoverEveryTuple(k + 1, declared, wild + here)
        }
    }

    /** The relevant set a [io.github.mbackschat.a12.dm.interpreter.model.CustomCondition] sees in PARTIAL mode —
     *  the kernel feeds it the caller's relevant entities (wildcards preserved, groups NOT expanded) **plus the
     *  auto-added global fields** (IF37/IF38, `MainValidatorController` → `getAllPruefungsrelevanteEntitiesExtern`).
     *  Full validation passes `null` ("all relevant"); here the caller's [RelevantEntity]s map straight to
     *  [CustomConditionFieldPointer]s and each global field rides as an all-reps-wildcard pointer. */
    private fun customRelevantOf(relevant: Set<RelevantEntity>): Set<CustomConditionFieldPointer> =
        relevant.mapTo(HashSet()) { CustomConditionFieldPointer(it.path, it.indices) } +
            model.globalFields.map { CustomConditionFieldPointer(it, List(pathParts(it).size) { RelevantEntity.ALL }) }

    /**
     * The shared validation pipeline, parameterized by [isRelevant] (`(path, reps) → Boolean`). Full
     * validation passes [ALL_RELEVANT]; partial passes a relevance predicate. Formal errors first — the
     * kernel checks every field before evaluating rules: format errors on every filled-but-malformed field,
     * and `mandatoryField` on every required-but-empty field. Both make the field "unknown", so a rule
     * referencing it sees Val.Unknown (the 3VL propagates it). Every emitted message is gated by its error
     * field's relevance.
     */
    private fun validateWith(doc: Document, locale: String, isRelevant: Relevance,
            customRelevant: Set<CustomConditionFieldPointer>? = null,
            starRelevant: ((String, Int, List<Int>) -> Boolean)? = null,
            starExtentKnown: ((String, Int, List<Int>) -> Boolean)? = null,
            starFullyEnumerated: ((String, Int, List<Int>) -> Boolean)? = null,
            groupRelevance: (String, List<Int>) -> GroupRelevance = { _, _ -> GroupRelevance.FULL },
            /** Partial only: [RelevanceQueries.relevantWithin] — the rule-run gate's bound-level query ([IF268]). */
            relevantWithin: ((String, List<Int>, Int) -> Boolean)? = null): ValidationOutcome =
        observeCaches(doc) {
            // The index-default staging split (SPEC-2026-07-23-14): a FULL call stages each eligible
            // clean-empty index cell's S-value into a call-local view — before the generated checks and
            // authored rules, never mutating the caller's document (the kernel's transient evaluation
            // cache); a PARTIAL call does NOT stage and instead silently marks those cells unavailable
            // (the `unavailable` seam of the formal-status cache below).
            val indexDefaults = checks.eligibleIndexDefaults(doc)
            val full = isRelevant === ALL_RELEVANT
            val call = if (full && indexDefaults.isNotEmpty()) doc.withComputed(indexDefaults) else doc
            val unavailable = if (full) emptySet() else indexDefaults.keys
            val dup = checks.duplicateIndexCells(call, isRelevant)   // computed ONCE: feeds both the uniqueIndex messages and the suppression
            lateinit var formalResults: FormalResultCache
            val admittedGroupContent by lazy(LazyThreadSafetyMode.NONE) {
                AdmittedGroupContentIndex.build(model, call, isRelevant, groupRelevance, formalResults, paths)
            }
            formalResults = FormalResultCache(checks, locale, dup, unavailable) { ctx, path ->
                admittedGroupContent.isFilled(ctx, path)
            }
            // Custom-type unsupported is collected up front (cells whose type is in NEITHER registry); formalErrors
            // ADDS to it any REGISTERED code-based type whose validate() can't run (a JS worker down / errored), so
            // that degrades to unsupported per cell rather than crashing the whole validation.
            val unsupported = checks.customTypeUnsupported(call, isRelevant).toMutableList()
            val messages = (checks.formalErrors(call, locale, isRelevant, formalResults, unsupported) +
                    checks.mandatoryErrors(call, locale, isRelevant, formalResults, unsupported)
                    + checks.uniquenessMessages(call, dup, locale, isRelevant)
                    + checks.overRepetitionMessages(call, locale, isRelevant)).toMutableList()
            // The per-call seam (IF117): the formal-status memo spans ALL rules of this validation (the doc is
            // immutable here), and the EvalConfig is built once for the whole call.
            val formallyIncorrect by lazy(LazyThreadSafetyMode.NONE) {
                formalResults.invalidPointers()
            }
            val groupPresence by lazy(LazyThreadSafetyMode.NONE) {
                GroupPresenceIndex(admittedGroupContent, formallyIncorrect, groupRelevance)
            }
            val pass = ValidationPass(call, doc, locale, isRelevant, relevantWithin, dup,
                validationConfig(isRelevant, customRelevant, starRelevant, starExtentKnown, starFullyEnumerated) { ctx, path -> groupPresence.state(ctx, path) },
                formalResults, { formallyIncorrect })
            for (rule in model.rules) {
                try {
                    // RepetitionNotUnique now flows through the ordinary rule path as a 3VL leaf (IF149) — no
                    // recognizer/emitter special-casing; [rowOutcomes] builds its duplicate cache and threads it in.
                    messages += messagesFor(rule, pass)
                } catch (e: UnsupportedConstructException) {
                    unsupported += UnsupportedRule(rule.path, e.message ?: "unsupported construct")
                }
            }
            // Every computation is ALSO a validation rule (KERNEL-FINDINGS, IF32/IF65): the kernel auto-generates one
            // implicit validation per computation — a field gate `FieldFilled(F) And` then one guarded
            // `[F] != <operation>` branch PER alternative. Unlike computation's first-holding-alternative scan,
            // every holding mismatch remains in this OR, so an overlapping later tier can fire validation even
            // though it cannot supply the computed value (IF175). Evaluated through the same rule machinery.
            for (implicit in parsedModel.implicitRules) {
                try {
                    messages += messagesFor(implicit, pass)
                } catch (e: UnsupportedConstructException) {
                    unsupported += UnsupportedRule(
                        implicit.errorEntityRelPath,
                        e.message ?: "unsupported computation validation",
                    )
                }
            }
            ValidationOutcome(messages, unsupported)
        }

    /**
     * Evaluate a SINGLE stored rule against the instance — the `rule eval` shape. [Verdict.FIRED] (a
     * violation, with the rendered [RuleResult.message]) / [Verdict.PASSED] (evaluated, clean) /
     * [Verdict.SUPPRESSED] (never evaluated — a referenced field is formally invalid, named in
     * [RuleResult.suppressedBy]).
     */
    fun testRule(rulePath: String, doc: Document, locale: String = "en_US"): RuleResult {
        val rule = model.rules.firstOrNull { it.path == rulePath }
            ?: throw IllegalArgumentException("no such rule: $rulePath")
        return evalRule(rule, doc, locale)
    }

    /** Evaluate an injected, NON-stored candidate rule (the `model eval --condition/--field` shape). */
    fun evalCandidate(rule: RuleDef, doc: Document, locale: String = "en_US"): RuleResult =
        evalRule(rule, doc, locale)

    /**
     * Run every computation over each row of its iteration scope and return each computed-field outcome
     * — the runtime twin of the kernel's `compute()`. VALUE (the expression resolved, empty-as-0 applies)
     * / CLEARED (the expression computed nothing) / ERRORED (the computed value is formally invalid for
     * the target).
     *
     * **Reporting granularity — report-ALL, deliberately wider than the kernel's change projection.** This
     * returns an outcome for **every** computed instance. The kernel's `IDocumentComputationResult` is a
     * five-channel result (successful-incl-unchanged / its changed subset / errored / cleared / operand
     * formal errors; KERNEL-FINDINGS §11 five-channel supplement); our corpus/differential compares against
     * its **change projection** (changed values / clears of input-filled cells). Report-all is the more useful
     * library contract ("what does this field compute to?"), and result-envelope granularity is not
     * behavior-parity territory. The public [ComputationReport] retains the source-relative action subset beside
     * these report-all results; a comparison against a kernel-granularity expectation projects via
     * [io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity].
     */
    fun compute(doc: Document, locale: String = "en_US"): List<Computed> =
        retainedComputations(doc, locale).results

    /** One compute pass retaining the source-relative actions consumed by both application surfaces. */
    internal fun retainedComputations(doc: Document, locale: String = "en_US"): RetainedComputationRun =
        observeCaches(doc) {
            val run = engine.computeRun(doc, locale)
            val applications = run.results.mapNotNull { computed ->
                val retain = when (computed.outcome) {
                    ComputeOutcome.VALUE ->
                        !doc.computedValueEquals(
                            computed.path,
                            computed.indices,
                            checkNotNull(computed.value),
                        )
                    ComputeOutcome.CLEARED -> doc.filledAt(computed.path, computed.indices)
                    ComputeOutcome.ERRORED -> true
                }
                if (retain) {
                    ComputationApplication(
                        path = computed.path,
                        reps = computed.indices,
                        outcome = computed.outcome,
                        value = computed.value,
                    )
                } else {
                    null
                }
            }
            RetainedComputationRun(
                results = run.results,
                applications = applications,
                formalErrorsInOperands = run.formalErrorsInOperands,
                computedErrors = run.computedErrors,
                unsupported = run.unsupported,
            )
        }

    /**
     * Evaluate a SINGLE computed field against the instance — the single-computation twin of [testRule], the
     * "run this field like a function" primitive (IF129, graduated from IG74). Computes [fieldPath]'s transitive
     * computed-dependency CLOSURE in dependency order (so a cascade operand is never read stale/empty) and returns
     * the target's outcome PER INSTANCE — one [Computed] per row of a repeated computed field, VALUE / CLEARED /
     * ERRORED, with poison + precondition semantics intact. Behaviour-identical to [compute] filtered to
     * [fieldPath], just pruned to the needed subset — an unrelated computation cannot feed the target
     * (`ComputeFieldInvariantTest`/`ComputeFieldDiffTest`). Throws [IllegalArgumentException] if no computation
     * targets [fieldPath] (a non-computed field has no outcome). [locale] rides for compute-family symmetry; like
     * [compute] the result is locale-independent (computed values, not rendered messages).
     */
    fun computeField(fieldPath: String, doc: Document, locale: String = "en_US"): List<Computed> {
        require(model.computations.any { it.targetPath == fieldPath }) { "no computation targets field: $fieldPath" }
        return observeCaches(doc) { engine.computeField(fieldPath, doc, locale) }
    }

    /**
     * Apply this model's computations to [doc] and return the **updated document** (old + computed values) —
     * the interpreter's twin of the kernel's `compute().applyTo(doc)` (KERNEL-FINDINGS "validateFull does NOT
     * run computations": the kernel keeps validate and compute separate and the consumer applies). A VALUE
     * outcome sets the computed cell; an ERRORED or CLEARED outcome empties it (matching the kernel's `applyTo`,
     * which applies the changed values and CLEARS both the errored and the cleared instances). The original
     * [doc] is unchanged.
     *
     * This is the apply-computed-back primitive behind the **form-engine validation flow**
     * `validate(applyComputations(doc))` — what the running application does, and the default behaviour of the
     * `model eval` verb (a required-but-computed field is filled by the computation before the required check
     * runs, so it is not a false `mandatoryField`).
     */
    fun applyComputations(doc: Document, locale: String = "en_US"): Document =
        retainedComputations(doc, locale).applyTo(doc)

    private fun evalRule(rule: RuleDef, doc: Document, locale: String): RuleResult = observeCaches(doc) {
        // A single-rule pass (fresh formal-status memo, everything relevant) — the testRule/evalCandidate shape.
        // Full-call semantics, so the eligible index defaults are staged exactly as in [validate] (SPEC-2026-07-23-14).
        val indexDefaults = checks.eligibleIndexDefaults(doc)
        val call = if (indexDefaults.isEmpty()) doc else doc.withComputed(indexDefaults)
        val dup = checks.duplicateIndexCells(call)
        val groupRelevance = { _: String, _: List<Int> -> GroupRelevance.FULL }
        lateinit var formalResults: FormalResultCache
        val admittedGroupContent by lazy(LazyThreadSafetyMode.NONE) {
            AdmittedGroupContentIndex.build(model, call, ALL_RELEVANT, groupRelevance, formalResults, paths)
        }
        formalResults = FormalResultCache(checks, locale, dup) { ctx, path ->
            admittedGroupContent.isFilled(ctx, path)
        }
        val formallyIncorrect by lazy(LazyThreadSafetyMode.NONE) {
            formalResults.invalidPointers()
        }
        val groupPresence by lazy(LazyThreadSafetyMode.NONE) {
            GroupPresenceIndex(admittedGroupContent, formallyIncorrect, groupRelevance)
        }
        val pass = ValidationPass(
            call,
            doc,
            locale,
            ALL_RELEVANT,
            null,
            dup,
            validationConfig(ALL_RELEVANT, null, null, null, null) { ctx, path -> groupPresence.state(ctx, path) },
            formalResults,
            { formallyIncorrect },
        )
        val rows = rowOutcomes(rule, pass)
        rows.firstNotNullOfOrNull { it as? RowOutcome.Fired }?.let {
            return@observeCaches RuleResult(rule.path, Verdict.FIRED, it.message)
        }
        // No firing: a referenced field being formally invalid means the rule was NOT evaluated (the
        // kernel skips it) → SUPPRESSED, not PASSED. PASSED only when every row was cleanly evaluated.
        val suppressed = rows.filterIsInstance<RowOutcome.Suppressed>().map { it.suppression }
        if (suppressed.isNotEmpty()) RuleResult(rule.path, Verdict.SUPPRESSED, suppressedBy = suppressed)
        else RuleResult(rule.path, Verdict.PASSED)
    }

    private inline fun <T> observeCaches(doc: Document, action: () -> T): T {
        val observer = cacheObserver ?: return action()
        return try {
            action()
        } finally {
            doc.observeCacheEntries(observer)
            parsedModel.observeCacheEntries(observer)
        }
    }

    /** Every firing of one rule across its iteration-scope rows, as [Message]s (suppressed rows produce none). */
    private fun messagesFor(rule: RuleDef, pass: ValidationPass): List<Message> =
        rowOutcomes(rule, pass).filterIsInstance<RowOutcome.Fired>().map { it.message }

    /**
     * The invariant [EvalConfig] for one validate call — the bundle every rule's per-row [Interpreter] shares,
     * built ONCE (not per row). The relevance seams carry the partial-validation set: [EvalConfig.cellRelevant]
     * is per-cell relevance for `FirstFilled`'s order-aware gate (IF42; null in full validation so the gate is
     * skipped and the flat-spike `RepDoc` path is untouched), [EvalConfig.starRelevant] whether an all-rows
     * aggregate's star is wildcard-relevant (IF42), [EvalConfig.customRelevant] the set a `CustomCondition` sees
     * (null in full, the caller's set + globals in partial — IF38).
     */
    private fun validationConfig(isRelevant: Relevance, customRelevant: Set<CustomConditionFieldPointer>?,
            starRelevant: ((String, Int, List<Int>) -> Boolean)?,
            starExtentKnown: ((String, Int, List<Int>) -> Boolean)?,
            starFullyEnumerated: ((String, Int, List<Int>) -> Boolean)?,
            groupPresence: (EvalContext, String) -> io.github.mbackschat.a12.dm.interpreter.eval.GroupPresenceState): EvalConfig =
        EvalConfig(clock, model.baseYear, model.indexFields, timeZone = model.timeZone,
            predefinedTypes = predefinedTypes, customConditions = customConditions, customFieldTypes = customFieldTypes,
            patternMatches = patterns::matches,
            cellRelevant = if (isRelevant === ALL_RELEVANT) null else { path, reps -> isRelevant(path, reps) },
            starRelevant = starRelevant, starExtentKnown = starExtentKnown,
            starFullyEnumerated = starFullyEnumerated,
            customRelevant = customRelevant, groupPresence = groupPresence)

    /** The kernel's allowed-and-eliminated raw-type length shape (see [rowOutcomes]): the whole condition is
     *  `Length(nvv) > constant` or the mirrored `constant < Length(nvv)` — GT/LT only (`>=`-style variants are
     *  model-rejected `MVK_INVALID_LENGTH_OF_RAW_TYPE`, so they never reach a legal eval), the one `Length`
     *  argument a plain field ref whose declared constraints carry `noValueValidation`. */
    private fun eliminatedNvvMetaLengthRule(ast: Cond): Boolean {
        if (ast !is Cond.Cmp) return false
        val (fn, lit) = when (ast.op) {
            CmpOp.GT -> ast.left to ast.right
            CmpOp.LT -> ast.right to ast.left
            else -> return false
        }
        if (lit !is Expr.NumLit || fn !is Expr.Func || FunctionOp.byName(fn.name) != FunctionOp.LENGTH) return false
        val field = fn.args.singleOrNull() as? Expr.Field ?: return false
        return model.constraintsOf(field.path).noValueValidation
    }

    /** The per-row outcome of one rule: fired (with its message), passed, or suppressed (which field/code). */
    private sealed interface RowOutcome {
        data class Fired(val message: Message) : RowOutcome
        data object Passed : RowOutcome
        data class Suppressed(val suppression: Suppression) : RowOutcome
    }

    private fun rowOutcomes(rule: RuleDef, pass: ValidationPass): List<RowOutcome> {
        val prepared = preparedModel.rule(rule)
        // Kernel codegen puts this method-entry return before iteration and evaluation for every rule whose
        // AST contains a Having filter. Keep the partial path O(1); full validation continues normally.
        if (pass.partial && prepared.hasFilterConditions) return emptyList()
        val ast = prepared.ast
        // The nvv META-LENGTH rule is ELIMINATED — never evaluated, no rows (IG71 → IF125). A rule whose WHOLE
        // condition is `Length(nvvField) > constant` (or the mirrored `constant < Length(nvvField)`) is the
        // kernel's disguised max-length declaration for a raw-type field: the codegen REMOVES the rule and only
        // tightens the generated meta-model's max length (`ValidationCodeGenerator.
        // handleLengthOfAllowedValueValidationRule` → `addMaxLengthDefinedByRules`; the same extraction as
        // `AnalyseService.getNameAndLengthOfAllowedValueValidation` — GT/LT-mirrored only), in BOTH strategies.
        // This is the ONLY kernel-legal value-ish read of an nvv field (every other value position is
        // model-rejected: MVK_INVALID_RAW_TYPE / MVK_INVALID_LENGTH_OF_RAW_TYPE / INVALID_NO_VALUE_VALIDATION),
        // so eliminating the rule — not inventing a per-read np value state — is the mechanism-exact twin.
        if (eliminatedNvvMetaLengthRule(ast)) return emptyList()
        val refs = nonStarredRefs(ast).distinct()
        val errorPath = resolveErrorPath(rule)
        // A rule whose condition contains a RepetitionNotUnique leaf gets its duplicate cache built ONCE (IF149),
        // guard-independent, keyed by error-path reps; the per-row seam looks the current row up in O(1). Null for
        // a normal rule (no cache, no seam). Building here can throw UnsupportedConstructException (>1 RNU, a
        // group/asterisk key, a too-deep scope) — the caller's per-rule tolerance reports it unsupported.
        val rnuCache = rnuEval.outcomes(prepared.repetitionNotUnique, pass)
        val directMessageRefs = prepared.messageReferences
        val directRefsOutsideRnuKeys = rnuCache?.let { cache ->
            val rnuKeys = cache.keys.toSet()
            directMessageRefs.filterNot { it.path in rnuKeys }
        }
        val rnuSeam: ((EvalContext) -> MsgType?)? =
            rnuCache?.let { cache -> { context -> cache.rows[context.repsOf(errorPath)]?.type } }
        return scopes.iterationContexts(prepared.iteration, pass.doc).map { ctx ->
            // Partial validation: the kernel runs a rule only if its error field is relevant at this row —
            // else the rule is NOT executed for it (no message, not a suppression). "At this row" is the
            // BOUND levels only ([ValidationPass.runsForRow], IF268). Full validation: always.
            if (!pass.runsForRow(errorPath, ctx)) return@map RowOutcome.Passed
            // A formally-invalid referenced field resolves to Val.Unknown (NOT a whole-rule skip): the 3VL
            // propagates it, so a bare comparison goes UNKNOWN but `… Or <true>` still FIRES — matching the
            // kernel's operand-level "not check-relevant" behavior (verified). A duplicate-index field is
            // likewise unknown (branch-scoped, same as a formal error) via [formalErrorAt]. In partial mode a
            // referenced field instance that is NOT relevant is also unknown — the same 3VL yields the kernel's
            // suppression (`And` suppresses, `Or` with a relevant-true operand still fires). The custom-condition
            // registry + the row's error-field pointer ride along for a `CustomCondition X` leaf; [rnuSeam] the
            // per-row RepetitionNotUnique cache outcome for an RNU leaf (IF149).
            val interp = pass.interpreterFor(ctx, errorPath, rnuSeam)
            // ONE typed walk per row (the fired MsgType, or null if it doesn't fire) — firedType != null ⟺ the
            // rule fires (truth == TRUE), and the value IS the polarity. The walk is LAZY-polarity: it skips the
            // VALUE/OMISSION typing on non-firing nodes (the kernel types only firing rows), measured faster in
            // both regimes. (A kernel-style evalCond-then-evalTyped two-phase guard was measured a net loss — the
            // second walk re-traverses + re-evals leaves; the lazy single walk gets the skip without it.
            // INTERPRETER-PERFORMANCE.md #62 / results log.)
            val firedType = interp.firedType(ast)
            if (firedType != null) {
                val type = firedType
                val text = rule.messages[pass.locale]?.let {
                    renderMessage(
                        it,
                        rule.group,
                        ctx,
                        defaultValueOf = model::messageDefaultOf,
                        labelOf = { path -> messageLabel(path, pass.locale, ctx) },
                        // `$field.value$` reads the ORIGINAL caller document, not the staged eval view: a staged
                        // enum index cell interpolates as if empty (kernel `getDaten().getValueForDisplay`; IF221).
                        messageValueOf = { path -> pass.callerDoc.messageDisplayValueAt(path, ctx.repsOf(path)) },
                    )
                } ?: ""
                val matchingRnuOutcome = rnuCache?.rows?.get(ctx.repsOf(errorPath))?.takeIf { it.type == type }
                // When the RNU leaf contributes this message's polarity, its cluster projection already contains
                // every occurrence of an RNU key path. Drop a same-path ordinary occurrence, render the remaining
                // small prefix, and expose an O(1) immutable joined view over the shared cluster list. This keeps
                // exact list semantics without copying the N-entry peer projection into every one of N messages.
                val directRefs = if (matchingRnuOutcome != null) directRefsOutsideRnuKeys.orEmpty() else directMessageRefs
                val directPointers = directRefs.map { messagePointer(it, pass.doc, ctx) }.distinct()
                val rnuPointers = matchingRnuOutcome?.peerKeyPointers.orEmpty()
                val referenced = joinedList(directPointers, rnuPointers)
                // refOmissionErrorResponsible: for an OMISSION error the kernel attaches ALL the operands the
                // omission depends on (verified — both the filled and the empty operand of an And), so filling/
                // changing any could resolve it. Empty for a VALUE error (no fill fixes a value).
                val fillToFix = if (type == MsgType.OMISSION) referenced else emptyList()
                RowOutcome.Fired(Message(rule.errorCode, rule.path, pass.doc.messagePointer(errorPath, ctx.repsOf(errorPath)),
                    rule.severity, type, text, referenced, fillToFix))
            } else {
                // Not fired: a formally-invalid operand means the rule was NOT cleanly evaluated → SUPPRESSED
                // (the `rule eval` third verdict), naming the offending field/code; else a clean PASSED.
                pass.firstSuppressor(refs, ctx)?.let { RowOutcome.Suppressed(it) } ?: RowOutcome.Passed
            }
        }
    }

    /** Project one prepared message operand into A12's partially-known pointer domain. The wildcard coordinates
     *  belong only to the rich message channel; iteration/suppression deliberately continue to use the
     *  star-free reference view. */
    private fun messagePointer(reference: MessageReference, doc: Document, ctx: EvalContext) = when (reference) {
        is MessageReference.Ordinary -> doc.messagePointer(reference.path, ctx.repsOf(reference.path))
        is MessageReference.Wildcard -> {
            val prefixes = paths.groupsThrough(reference.path)
            val bindDepth = if (reference.bindDepth >= 0) minOf(reference.bindDepth, prefixes.size)
            else prefixes.size - if (model.kinds[reference.path] == null) 1 else 2
            val repetitions = ctx.repsOf(reference.path).mapIndexed { level, repetition ->
                if (level >= bindDepth && prefixes[level] in model.repeatable) A12PartiallyKnownMultiPointer.WILDCARD
                else repetition
            }
            doc.messagePointer(reference.path, repetitions)
        }
    }

    private fun messageLabel(path: String, locale: String, ctx: EvalContext): String {
        return messageLabel(path, locale, ctx.repsOf(path))
    }

    private fun messageLabel(path: String, locale: String, repetitions: List<Int>): String {
        val supplied = model.fieldOf(path)?.let { field -> messageLabelProvider?.label(field, locale) }
        if (supplied != null) return supplied
        model.declaredLabelOf(path, locale)?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (repetitions.isEmpty()) path else "$path/${repetitions.joinToString(".")}"
    }

    /** Immutable concatenation without copying either input; both inputs are call-local immutable projections. */
    private fun <T> joinedList(prefix: List<T>, suffix: List<T>): List<T> = when {
        prefix.isEmpty() -> suffix
        suffix.isEmpty() -> prefix
        else -> JoinedList(prefix, suffix)
    }

    /** Immutable by construction over two call-local immutable inputs, so it publishes without a copy: an
     *  RNU message that also carries direct references lands here rather than on the bare cluster snapshot, and
     *  a boundary that recognized only the snapshot copied this one per message (IF261). */
    private class JoinedList<T>(private val prefix: List<T>, private val suffix: List<T>)
        : AbstractList<T>(), ImmutableProjection {
        override val size: Int = prefix.size + suffix.size

        override fun get(index: Int): T = when {
            index < 0 || index >= size -> throw IndexOutOfBoundsException("index $index, size $size")
            index < prefix.size -> prefix[index]
            else -> suffix[index - prefix.size]
        }
    }

    /**
     * Build the [Relevance] predicate from a relevant-entity set: an instance `(path, reps)` is relevant iff
     * some [RelevantEntity] **is** it or is an **ancestor group** of it, with the shared-prefix repetition
     * indices matching ([RelevantEntity.ALL] wildcards a level). A relevant group thus makes every descendant
     * relevant (the kernel rule). An empty set → nothing relevant (no rule runs).
     */
    private fun relevanceOf(relevant: Set<RelevantEntity>): Relevance =
        // The kernel's md-runtime-service auto-adds every GLOBAL field (wildcarded at all reps) to the relevant
        // set before validating (LowKernelApiUtils.addGlobalFieldsToRelevantEntities), so a global field's
        // instances are relevant regardless of the caller's set — IG11→IF37, KERNEL-FINDINGS "Partial validation".
        relevanceIndexOf(relevant)

    /**
     * The relevance query, INDEXED. It used to be `relevant.any { matches(it, path, reps) }` — a linear scan of
     * the caller's relevant set, run once per field INSTANCE. That is quadratic **when the relevant set itself
     * grows with the document**, which is exactly what a concrete row enumeration does — measured 8.36× growth
     * for 4× rows on `laws/PartialSumOfProductsPerfTest`. It is not aggregate-specific: every partial
     * validation over such a set paid it. It is equally not universal — a single wildcard entity keeps the set
     * constant and the same scan stays linear, so "partial validation was quadratic" overstates it.
     *
     * (The kernel RATIOS once quoted here came from a harness that rebuilt the kernel engine inside the timed
     * call, so they were inflated and have been dropped rather than restated; the growth figures above are
     * interpreter-only and stand. Corrected 2026-08-08, IF255.)
     *
     * [matches] admits an entity whose path is an ancestor-or-equal prefix and whose every index is either the
     * query's repetition or [RelevantEntity.ALL]. So bucket the set by path, and answer a query by trying each
     * ancestor prefix. A concrete repetition is 1-based, so it can never collide with the `ALL` sentinel.
     *
     * Within a prefix, probe **one key per distinct wildcard PATTERN the caller actually supplied**, not every
     * combination of the level count. The two are equivalent — an entity with pattern `p` matches iff its
     * indices equal the query's repetitions with `ALL` substituted at `p`'s wildcard positions, so one probe
     * settles the whole pattern — but the cost is completely different, and the first version enumerated
     * `2^depth` combinations on the strength of a comment asserting "depth is the model's nesting depth
     * (single digits)". [ModelPreparation] admits **64** by default and 128 as its hard limit, so that bound
     * was never the model's: measured, a depth-24 model took 4.7 s per partial validation of a ONE-FIELD
     * document, and at depth 31 `1 shl depth` overflows `Int` to a negative bound, which skipped the probe
     * loop entirely and silently answered "not relevant" for every query — messages simply vanished
     * ([PartialRelevanceDepthTest]). Patterns are bounded by the relevant set, so the query can never cost
     * more than the data the caller passed in.
     */
    private fun relevanceIndexOf(relevant: Set<RelevantEntity>): Relevance = relevanceQueriesOf(relevant).relevant

    /**
     * The relevant set's two queries over one shared index: [RelevanceQueries.relevant] (the [Relevance]
     * predicate above) and [RelevanceQueries.relevantWithin], the rule-run gate's coarser sibling.
     */
    private fun relevanceQueriesOf(relevant: Set<RelevantEntity>): RelevanceQueries {
        val tuples = HashMap<String, HashSet<List<Int>>>()
        val patterns = HashMap<String, MutableSet<List<Boolean>>>()
        for (r in relevant) {
            // matches() requires exactly one index per path part; anything else can never match.
            if (r.indices.size != pathParts(r.path).size) continue
            tuples.getOrPut(r.path) { HashSet() } += r.indices
            patterns.getOrPut(r.path) { LinkedHashSet() } += r.indices.map { it == RelevantEntity.ALL }
        }
        return RelevanceQueries(model.globalFields, tuples, patterns)
    }

    private class RelevanceQueries(
        private val globalFields: Set<String>,
        private val tuples: Map<String, HashSet<List<Int>>>,
        private val patterns: Map<String, MutableSet<List<Boolean>>>,
    ) {
        /** [tuples]/[patterns] projected onto the first `bound` levels, built once per (path, bound). A rule
         *  fixes both, so the projection is built once per rule and probed once per iteration row. */
        private val projected = HashMap<Pair<String, Int>, Pair<Set<List<Int>>, Set<List<Boolean>>>>()

        val relevant: Relevance = { path, reps -> relevantWithin(path, reps, reps.size) }

        /**
         * `(path, reps)` relevance with every level from [bound] on treated as a WILDCARD — an entity filed
         * under a deeper prefix matches when its first [bound] indices do, whatever it carries below. With
         * `bound = reps.size` this is exactly [relevant]; the rule-run gate passes the count of levels the
         * rule's iteration actually BOUND ([IF268]).
         */
        fun relevantWithin(path: String, reps: List<Int>, bound: Int): Boolean {
            if (path in globalFields) return true
            val pParts = pathParts(path)
            var depth = 1
            while (depth <= pParts.size && depth <= reps.size) {
                val prefix = "/" + pParts.take(depth).joinToString("/")
                // Every pattern filed under `prefix` has exactly `depth` entries: entities are indexed
                // only when their index count equals their own path-part count. Below [bound] they are
                // projected onto the bound levels, which collapses the free indices away.
                // The ORDINARY query never leaves this branch — its bound is the full tuple length, so it stays
                // the exact lookup it was before the gate needed a coarser sibling. Keep it that way: this runs
                // once per field INSTANCE, and a `Pair` allocated here to unify the two branches was enough to
                // push `PartialSumOfProductsPerfTest` from 3.7x to 9.27x growth.
                if (depth <= bound) {
                    val bucket = tuples[prefix]
                    if (bucket != null) {
                        val base = reps.take(depth)
                        for (pattern in patterns.getValue(prefix)) {
                            val key = base.mapIndexed { i, rep -> if (pattern[i]) RelevantEntity.ALL else rep }
                            if (key in bucket) return true
                        }
                    }
                } else {
                    val projection = projection(prefix, bound)
                    if (projection != null) {
                        val base = reps.take(bound)
                        for (pattern in projection.second) {
                            val key = base.mapIndexed { i, rep -> if (pattern[i]) RelevantEntity.ALL else rep }
                            if (key in projection.first) return true
                        }
                    }
                }
                depth++
            }
            return false
        }

        /** Null when nothing at all is filed under [prefix], so the caller skips it like the exact branch does. */
        private fun projection(prefix: String, levels: Int): Pair<Set<List<Int>>, Set<List<Boolean>>>? {
            val bucket = tuples[prefix] ?: return null
            return projected.getOrPut(prefix to levels) {
                bucket.mapTo(HashSet()) { it.take(levels) } to
                    patterns.getValue(prefix).mapTo(LinkedHashSet()) { it.take(levels) }
            }
        }
    }

    /**
     * Partial validation's two group-relevance levels. Any selected descendant makes its ancestors PARTIAL,
     * which is enough to prove positive presence. Absence is known only when an ancestor selection covers the
     * whole group, or every declared descendant field is covered across every deeper repeatable level.
     */
    private fun groupRelevanceOf(relevant: Set<RelevantEntity>): (String, List<Int>) -> GroupRelevance {
        val effective = relevant + model.globalFields.map(RelevantEntity::anyRep)
        val fieldsByAncestor = HashMap<String, MutableList<String>>()
        for (field in model.fields) {
            val parts = pathParts(field.path)
            for (depth in 1 until parts.size) {
                fieldsByAncestor.getOrPut("/" + parts.take(depth).joinToString("/")) { ArrayList() } += field.path
            }
        }
        val cache = HashMap<String, HashMap<List<Int>, GroupRelevance>>()

        fun touchesGroup(entity: RelevantEntity, groupPath: String, groupReps: List<Int>): Boolean {
            val entityParts = pathParts(entity.path)
            val groupParts = pathParts(groupPath)
            val common = minOf(entityParts.size, groupParts.size)
            if (entityParts.take(common) != groupParts.take(common)) return false
            return (0 until minOf(common, entity.indices.size, groupReps.size)).all { level ->
                entity.indices[level] == RelevantEntity.ALL || entity.indices[level] == groupReps[level]
            }
        }

        fun fullyCoversField(
            entity: RelevantEntity,
            fieldPath: String,
            groupPath: String,
            groupReps: List<Int>,
        ): Boolean {
            if (!touchesGroup(entity, groupPath, groupReps)) return false
            val entityParts = pathParts(entity.path)
            val fieldParts = pathParts(fieldPath)
            if (entityParts.size > fieldParts.size || entityParts != fieldParts.take(entityParts.size)) return false
            val groupDepth = pathParts(groupPath).size
            for (level in groupDepth until entityParts.size) {
                val prefix = "/" + entityParts.take(level + 1).joinToString("/")
                if (prefix in model.repeatable && entity.indices[level] != RelevantEntity.ALL) return false
            }
            return true
        }

        return { groupPath, groupReps ->
            cache.getOrPut(groupPath) { HashMap() }.getOrPut(groupReps) {
                if (effective.none { touchesGroup(it, groupPath, groupReps) }) {
                    GroupRelevance.NONE
                } else if (effective.any { matches(it, groupPath, groupReps) } ||
                    fieldsByAncestor[groupPath].orEmpty().all { field ->
                        effective.any { fullyCoversField(it, field, groupPath, groupReps) }
                    }) {
                    GroupRelevance.FULL
                } else {
                    GroupRelevance.PARTIAL
                }
            }
        }
    }

    /** Whether relevant entity [r] covers the instance at [path]/[reps] — [r.path] a prefix of [path] (== or a
     *  group ancestor) with each of [r]'s indices either [RelevantEntity.ALL] or equal to the instance's. */
    private fun matches(r: RelevantEntity, path: String, reps: List<Int>): Boolean {
        val rParts = pathParts(r.path)
        val pParts = pathParts(path)
        if (rParts.size > pParts.size || r.indices.size != rParts.size) return false
        if (rParts != pParts.take(rParts.size)) return false
        return r.indices.indices.all { i -> r.indices[i] == RelevantEntity.ALL || r.indices[i] == reps[i] }
    }
}
