package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.classificationEnabled
import io.github.mbackschat.a12.dm.interpreter.eval.containsDateTimeLiteral
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.jvm.JvmSynthetic

/**
 * Everything preparation derives from the MODEL ALONE, owned per model rather than per evaluator.
 *
 * The split this type creates is the P-SERVICE requirement in `docs/INTERPRETER-SPEC.md`
 * "The two consumer profiles": a backend service caches many models and serves requests whose *configuration*
 * varies (per-tenant custom conditions and field types, a per-request clock and locale). Every field of a
 * [PreparedRulePlan] is configuration-independent — configuration reaches rule preparation only through
 * [PreparedEvaluationPlan]'s audit, which decides ACCEPTANCE and contributes no plan content — so owning the
 * plans per interpreter would key a service's cache by (model × configuration) and make two tenants sharing one
 * model each retain a full duplicate of every AST, iteration plan and RNU plan.
 *
 * Two consequences are contract rather than implementation detail:
 *
 * - **Preparation is memoized here, so a second evaluator over the same model does none of it.** That is the
 *   claim NFR-F3's structural half makes, and the sibling-evaluator arm of `PreparationWorkCounterTest` is its
 *   enforcement locus: every [PreparationWork] class must report zero for the second evaluator. The kernel
 *   differential cannot serve as that oracle, because moving ownership has no semantic effect.
 * - **Sharing is thread-safe.** Every memo is a SYNCHRONIZED `lazy`, so concurrent JVM request threads over one
 *   shared instance each observe a single build. Node reaches this one isolate at a time; a multi-isolate host
 *   duplicates the instance per isolate rather than sharing it.
 *
 * What stays OUT, deliberately, because it is configuration-dependent: the support-audit verdict, predefined-type
 * pattern compilation, the clock, and the per-cell auto-checks. [PreparedEvaluationPlan] owns the audit over these
 * plans; [DmInterpreter] owns the rest.
 */
internal class ModelStaticPlans private constructor(private val model: ModelDef) {

    private data class SemanticIndexTarget(
        val group: String,
        val indexValue: String?,
        val indexFieldPath: String?,
        val outer: Boolean,
        val indexFieldOuter: Boolean,
    )

    companion object {
        /**
         * Kotlin emits an `internal` constructor as `ACC_PUBLIC`, so this type is built through a
         * `@JvmSynthetic` factory instead — a Java caller can neither construct it nor hand one to
         * [DmInterpreter.sharing].
         */
        @JvmSynthetic
        internal fun of(model: ModelDef): ModelStaticPlans = ModelStaticPlans(model)
    }

    /** Nullable, instance-local deterministic seam used only by the model-static work guards. */
    internal var preparationObserver: PreparationObserver? = null

    /** Model-static path prefixes and repetition bounds, prepared once rather than rebuilt per validation cell. */
    val paths = ModelPathIndex(model)

    val scopes = IterationScopes(model)

    val repetitionNotUnique = RepetitionNotUniqueEvaluator(model, scopes)

    val parsed = ParsedModel(model) { preparationObserver }

    /**
     * One rule's plan, with NO audit. Building can still fail for a MODEL reason (more than one
     * `RepetitionNotUnique` in a condition), which is configuration-independent and therefore correctly memoized
     * here; a CONFIGURATION rejection belongs to the audit and never reaches this map.
     */
    private val rulePlans: Map<RuleDef, Lazy<PreparedRulePlan>> =
        (model.rules + parsed.implicitRules).associateWith { rule ->
            lazy {
                observe(PreparationWork.RULE_PLAN, rule.path)
                buildRulePlan(rule)
            }
        }

    private val computationPlans: Map<ComputationDef, Lazy<ParsedComputation>> =
        model.computations.associateWith { computation ->
            lazy {
                observe(PreparationWork.COMPUTATION_PLAN, computation.targetPath)
                parsed.parsedOf(computation)
            }
        }

    /**
     * The whole-model computation dependency order and each requested target's transitive closure. Both derive
     * from the model's computations and their parsed operations only, so they belong to the model even though
     * [ComputationEngine] is the consumer that reads them.
     */
    private val allComputationOrder: Lazy<List<ComputationDef>> = lazy {
        observe(PreparationWork.ALL_COMPUTATION_ORDER, "<all>")
        orderedFrom(model.computations.map { it.targetPath })
    }

    private val computationClosures: Map<String, Lazy<List<ComputationDef>>> =
        model.computations.map { it.targetPath }.distinct().associateWith { target ->
            lazy {
                observe(PreparationWork.COMPUTATION_CLOSURE, target)
                orderedFrom(listOf(target))
            }
        }

    /** The prepared plan for [rule]; an ad-hoc candidate outside the model is built once for that call. */
    fun rule(rule: RuleDef): PreparedRulePlan =
        rulePlans[rule]?.value ?: buildRulePlan(rule)

    /** The parsed form of [computation]; an ad-hoc computation outside the model is parsed for that call. */
    fun computation(computation: ComputationDef): ParsedComputation =
        computationPlans[computation]?.value ?: parsed.parsedOf(computation)

    fun condAstOf(rule: RuleDef): Cond = parsed.condAstOf(rule)

    fun allComputationOrder(): List<ComputationDef> = allComputationOrder.value

    /** [target]'s transitive computation closure; empty when [target] is not a computed field. */
    fun computationClosure(target: String): List<ComputationDef> =
        computationClosures[target]?.value ?: emptyList()

    /** Complete operand coverage, including every field contained by group reads. Scheduling uses
     *  [ComputationDependencies] separately because protected producers remain operands (KF511). */
    fun computationDepRefs(parsed: ParsedComputation): List<String> = (
        (parsed.commonPrecondition?.let { condDepRefs(it) } ?: emptyList()) +
            parsed.alternatives.flatMap {
                (it.precondition?.let { p -> condDepRefs(p) } ?: emptyList()) + referencedFieldPaths(it.operation)
            }
        ).flatMap(::expandGroupDependency)

    /** The operand inventory retains every contained field, including producers omitted from scheduling
     *  by a positive containing-group guard (SPEC-2026-10-03-02). */
    private fun expandGroupDependency(path: String): List<String> =
        if (path in model.kinds) listOf(path)
        else model.kinds.keys.filter { it.startsWith("$path/") }

    private fun buildRulePlan(rule: RuleDef): PreparedRulePlan {
        val ast = parsed.condAstOf(rule)
        val rnu = repetitionNotUnique.prepare(rule, ast)
        val iteration = scopes.prepare(rule, ast)
        val errorPath = resolveErrorPath(rule)
        fun selectedGroup(semanticIndex: Expr.SemanticIndex): String =
            nearestIndexFieldOf(model.indexFields, semanticIndex.path)?.let(::parentGroup)
                ?: if (semanticIndex.path in model.groupPaths) semanticIndex.path else parentGroup(semanticIndex.path)
        fun targetOf(semanticIndex: Expr.SemanticIndex) = SemanticIndexTarget(
            selectedGroup(semanticIndex),
            semanticIndex.indexValue,
            semanticIndex.indexFieldPath,
            semanticIndex.outer,
            semanticIndex.indexFieldOuter,
        )
        val rawMessageRefs = messageReferences(ast, excludeRepetitionNotUniqueKeys = rnu != null)
        val directMessageRefs = rawMessageRefs.flatMap(::expandMessageReference).distinct()
        // Two DIFFERENT questions, which is why this one is asked before the expansion rather than after it.
        // `ordinaryErrorReference` asks how the CONDITION names the error path — an authored star does not fix an
        // error context, anything else does. A group operand's expansion is not an authored star, so it must still
        // count here even though its pointers wildcard (below). Reading it off the expanded list conflated the two
        // and silently changed which rules take the semantic-index error-context path.
        val ordinaryErrorReference = errorPath in nonStarredRefs(ast) || rawMessageRefs.any { reference ->
            reference is MessageReference.Ordinary && errorPath in coveredFieldPaths(reference.path)
        }
        val semanticErrorIndexesByTarget = semanticIndexes(ast)
            .filter { semanticIndex ->
                semanticIndex.path == errorPath ||
                    (semanticIndex.path in model.groupPaths && errorPath.startsWith("${semanticIndex.path}/"))
            }
            .groupBy(::targetOf)
        val semanticErrorIndex = if (ordinaryErrorReference) null else {
            semanticErrorIndexesByTarget.values
                .singleOrNull()
                ?.maxBy { semanticIndex -> pathParts(semanticIndex.path).size }
                ?.takeUnless { semanticIndex ->
                    scopes.bindsRepeatableGroup(iteration, selectedGroup(semanticIndex))
                }
        }
        return PreparedRulePlan(
            ast = ast,
            iteration = iteration,
            repetitionNotUnique = rnu,
            semanticErrorIndex = semanticErrorIndex,
            messageReferences = directMessageRefs,
            hasFilterConditions = starRefs(ast).any { it.having != null },
            canFailAtRuntime = containsDateTimeLiteral(ast),
            classifies = classificationEnabled(ast) { path -> path in model.groupPaths && pathParts(path).size == 1 },
        )
    }

    /**
     * The kernel's meta-reference inventory is field-only and consumes the partially expanded condition: a
     * group operand recursively contributes its contained fields, never a group pointer.
     *
     * **An expanded field is WILDCARDED below the group's own depth, and that is not the same as keeping the
     * group reference's kind.** A group operand's expansion descends through nested *repeatable* subgroups whose
     * rows the reference cannot name — there is no coordinate to put there, and the kernel writes its wildcard.
     * Preserving `Ordinary` instead resolved those levels through `ctx.repsOf`, which pins row 1: measured against
     * both kernel strategies, `FieldValuesNotUnique(/Shipment/Carrier)` referenced
     * `…/Carrier[1]/Handoffs[1]/Site` where the kernel writes `…/Carrier[1]/Handoffs[0]/Site` ([IF287]). It is the
     * third row-1 pin found in this operator's group expansion and the first in the *reference* channel rather
     * than the compared value set.
     *
     * Binding at the group's own group-depth is what makes one rule serve both shapes: the operand's own
     * level and everything above it keep their concrete coordinate, and levels **strictly below** it wildcard
     * **iff repeatable** — the measured example shows it, keeping `Carrier[1]`, the operand's own level, concrete. A group with no
     * repeatable level below it therefore expands to all-concrete references exactly as before, which is why this
     * needs no special case for the non-nesting shape. A reference that is already a wildcard keeps its own
     * `bindDepth`, since a star binds where it was authored rather than where its group sits.
     */
    private fun expandMessageReference(reference: MessageReference): List<MessageReference> {
        if (model.kinds[reference.path] != null) return listOf(reference)
        // `pathParts`, not `paths.groupsThrough`: they agree on every declared group, but `groupsThrough` is
        // TOTAL only for a path the prepared model knows — it resolves through `ModelPathIndex.info`, whose
        // `checkNotNull` throws. A rule may reference a path that is declared nowhere, and the per-rule degrade
        // net catches only `UnsupportedConstructException`, so computing the depth eagerly here turned one
        // illegal rule into an `IllegalStateException` that sank `audit()`/`validate()` for the WHOLE model
        // (IF61's per-rule net, defeated). The expansion of such a path is empty anyway.
        val groupDepth = pathParts(reference.path).size
        return coveredFieldPaths(reference.path).map { path ->
            when (reference) {
                is MessageReference.Ordinary -> MessageReference.Wildcard(path, groupDepth)
                is MessageReference.Wildcard -> MessageReference.Wildcard(path, reference.bindDepth)
            }
        }
    }

    /** The field paths a reference covers: itself when it names a field, else every field the group contains. */
    private fun coveredFieldPaths(path: String): List<String> =
        if (model.kinds[path] != null) listOf(path)
        else model.fields.map { it.path }.filter { it.startsWith("$path/") }

    /**
     * Dependency-order the computations reachable from [roots]. A field's computations are GROUPED by target in
     * document order so a later computation cannot be dropped (IF171's shared root), and a cycle is a model
     * error rather than a configuration one.
     */
    private val schedulingDependencies by lazy { ComputationDependencies(model, ::computation) }

    private fun orderedFrom(roots: List<String>): List<ComputationDef> {
        val byTarget: Map<String, List<ComputationDef>> = model.computations.groupBy { it.targetPath }
        val ordered = mutableListOf<ComputationDef>()
        val done = HashSet<String>()
        val onStack = HashSet<String>()
        fun visit(target: String) {
            if (target in done) return
            if (!onStack.add(target)) {
                throw UnsupportedConstructException("cyclic computation dependency involving $target")
            }
            for (comp in byTarget[target].orEmpty())
                for (ref in schedulingDependencies.reads(comp))
                    if (ref != target && ref in byTarget) visit(ref)
            onStack.remove(target)
            if (done.add(target)) ordered.addAll(byTarget[target].orEmpty())
        }
        roots.forEach { visit(it) }
        return ordered
    }

    private fun observe(work: PreparationWork, key: String) {
        preparationObserver?.performed(PreparationEvent(work, key))
    }
}
