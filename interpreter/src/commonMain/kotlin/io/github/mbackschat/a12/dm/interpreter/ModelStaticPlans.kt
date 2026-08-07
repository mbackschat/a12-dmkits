package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
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

    /** All field/star refs of a whole tiered computation — its cascade DEPENDENCY input: each operation's full
     *  refs (incl. starred and `Having`-filter refs, so a dependency through an aggregate over a computed field
     *  is ordered) plus each precondition's + the common precondition's FULL refs ([condDepRefs] — a
     *  precondition's quantifier over a computed field is a dependency too). */
    fun computationDepRefs(parsed: ParsedComputation): List<String> = (
        (parsed.commonPrecondition?.let { condDepRefs(it) } ?: emptyList()) +
            parsed.alternatives.flatMap {
                (it.precondition?.let { p -> condDepRefs(p) } ?: emptyList()) + referencedFieldPaths(it.operation)
            }
        ).flatMap(::expandGroupDependency)

    /** A bare-group fill predicate depends on every computed target contained by that group, not on the
     *  non-field group path itself. Expansion here serves both whole-model ordering and computeField closure. */
    private fun expandGroupDependency(path: String): List<String> =
        if (path in model.kinds) listOf(path)
        else model.kinds.keys.filter { it.startsWith("$path/") }

    private fun buildRulePlan(rule: RuleDef): PreparedRulePlan {
        val ast = parsed.condAstOf(rule)
        val rnu = repetitionNotUnique.prepare(rule, ast)
        return PreparedRulePlan(
            ast = ast,
            iteration = scopes.prepare(rule, ast),
            repetitionNotUnique = rnu,
            messageReferences = messageReferences(ast, excludeRepetitionNotUniqueKeys = rnu != null)
                .flatMap(::expandMessageReference).distinct(),
            hasFilterConditions = starRefs(ast).any { it.having != null },
        )
    }

    /** The kernel's meta-reference inventory is field-only and consumes the partially expanded condition: a
     *  group operand recursively contributes its contained fields, never a group pointer. */
    private fun expandMessageReference(reference: MessageReference): List<MessageReference> {
        if (model.kinds[reference.path] != null) return listOf(reference)
        return model.fields.asSequence().map { it.path }
            .filter { it.startsWith("${reference.path}/") }
            .map { path ->
                when (reference) {
                    is MessageReference.Ordinary -> MessageReference.Ordinary(path)
                    is MessageReference.Wildcard -> MessageReference.Wildcard(path, reference.bindDepth)
                }
            }
            .toList()
    }

    /**
     * Dependency-order the computations reachable from [roots]. A field's computations are GROUPED by target in
     * document order so a later computation cannot be dropped (IF171's shared root), and a cycle is a model
     * error rather than a configuration one.
     */
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
                for (ref in computationDepRefs(computation(comp)))
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
