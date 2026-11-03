package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.JoinShape
import io.github.mbackschat.a12.dm.interpreter.eval.FunctionOp
import io.github.mbackschat.a12.dm.interpreter.eval.PredicateOp
import io.github.mbackschat.a12.dm.interpreter.eval.canFireEmpty
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef

/** A rule's value-independent iteration decision, prepared once and consumed by every document evaluation. */
internal sealed interface IterationPlan {
    /** The rows of [group]. A [global] rule visits every declared row under its root on a full call, each one
     *  evaluated whatever it holds ([IterationScopes.isGlobalRows]). */
    data class Rows(val group: String, val global: Boolean = false) : IterationPlan
    /** One evaluation in [rootGroup]. A [global] rule runs with no root-content gate, a root rule only where that
     *  root instance holds content ([IterationScopes.isGlobal]). */
    data class Once(val rootGroup: String, val global: Boolean) : IterationPlan
    data class Parallel(
        val joined: List<String>,
        val indexName: String,
        val commonParent: String,
        val frameGroup: String?,
        val deepestFrame: String?,
        /** The unindexed repeatable level directly below a joined group that the rule or computation iterates
         *  inside each joined row, or null when the join is the deepest iterated level (KERNEL-FINDINGS KF603). */
        val nested: String? = null,
    ) : IterationPlan {
        val join = JoinShape(joined, indexName, commonParent, frameGroup, deepestFrame, nested)
    }
}

/**
 * The iteration-scope machinery — a call-invariant collaborator of the I2 eval-state seam (IF117): which
 * repeatable group a rule/computation iterates ([scopeFromRefs]), the per-row contexts of that scope incl.
 * the error-entity re-anchor ([iterationContexts], IF103), and the parallel-iteration outer join
 * ([prepareParallel], KERNEL-SEMANTICS §9). Shared by the rule path, the `RepetitionNotUnique`
 * guard-scope check, and the computation engine. Allocated once per MODEL (see [ModelStaticPlans]), shared by its evaluators.
 */
internal class IterationScopes(private val model: ModelDef) {

    /**
     * The contexts a rule is evaluated over — REFERENCE-driven, never placement-driven (the kernel law,
     * KERNEL-FINDINGS §9 supplement, `IterationLawsTest`/`StarOnlyIterationScopeDiffTest`): a rule at `/Order`
     * reaching into `/Order/Items` iterates the Items rows, and the declared [RuleDef.group] drives nothing.
     * Three tiers, mirroring the kernel's per-level ITERATION markers:
     *
     * 1. The deepest repeatable ancestor-or-self of a *non-aggregated* field reference — one context per row.
     * 2. With no per-row ref: the deepest repeatable **BINDING level** of a starred ref (the levels STRICTLY
     *    ABOVE the first `*`-marked group — the same levels the star binds, IF93/IF94). The starred level and
     *    below are a WILDCARD collapse and never iterate (IF127; the old fallback to [RuleDef.group] over-fired
     *    a star-only rule per row of its OWN group).
     * 3. With no repeatable reference level at all: exactly ONE evaluation — the context of the error path's
     *    ROOT group, whose `evaluated` flag is the kernel's content gate (fires only when the root instance
     *    carries any value), with every unreferenced repeatable level of the error path pinned to row 1
     *    (phantom anchors included — the kernel fires at an UNINSTANTIATED `Fees[1]`). This subsumes the IF103
     *    cross-root re-anchor: an empty declaring container never silences the once-evaluation.
     *
     * **Parallel iteration** — when the condition references TWO (or more) NON-nested repeatable groups per-row
     * (siblings, neither an ancestor of the other), the kernel iterates them JOINTLY as an outer join keyed by a
     * shared index field (KERNEL-SEMANTICS §9). [prepareParallel] shapes that join; an unsupported
     * parallel shape (non-sibling depth, missing / mismatched index field) fails loud (reported unsupported),
     * never silently wrong.
     */
    fun prepare(rule: RuleDef, ast: Cond): IterationPlan {
        val errorPath = resolveErrorPath(rule)
        // A rule reporting on a group iterates the levels above it and never the group's own: its pointer names the
        // group's first row, and its reads at that level read that row (KERNEL-FINDINGS KF603,
        // `adapter.laws.GroupTargetRuntimeDiffTest`). Only the target's own level is withheld; a level below it
        // keeps the ordinary account, unmeasured.
        val ownLevel = errorPath.takeIf { it in model.groupPaths }
        val refs = nonStarredRefs(ast)
        val semanticAnchors = semanticIndexSpecs(ast, includeFilterBodies = false).flatMap(::ordinaryLevelsOfSelection)
            .filter { it != ownLevel }
        val scope = (listOfNotNull(deepestRepeatableAnchor(refs, ownLevel)) + semanticAnchors)
            .maxByOrNull { it.count { c -> c == '/' } } ?: starBindingAnchor(ast, withheld = ownLevel)
            ?: return rootGroupOf(errorPath).let { root -> IterationPlan.Once(root, isGlobal(root, ast)) }
        // A star bound to another indexed group joins it as a field read does: alone it anchors the join, and a
        // rule reading both sides only through stars joins them too (KERNEL-FINDINGS KF573). A level below the
        // scope is the scope's own child, not a join partner.
        val starAnchors = starRefs(ast).flatMap(::bindingLevels)
            .filter { it in model.repeatable && !it.startsWith("$scope/") }
        val parallelGroups = (refs.flatMap(::repeatableLevels) + semanticAnchors + starAnchors)
            .distinct().filter { it != scope && it != ownLevel && !scope.startsWith("$it/") }
        if (parallelGroups.isNotEmpty()) {
            // A12 joins at the nearest keyed group on the error entity's path, the entity itself first
            // (`AnalyseService.getParallelIterationLevel`, kernel 30.8.1, read 2026-10-23), so a keyed group target
            // is its own join level, which this plan does not model (INTERPRETER-GAP-DISPOSITIONS IG180).
            if (ownLevel != null && hasIndexField(ownLevel)) throw UnsupportedConstructException(
                "a parallel rule reporting on the keyed group $ownLevel joins at that group's own level, which is not modeled")
            val plan = prepareParallel(scope, parallelGroups)
            requireParallelDocumentSupport(ast, plan)
            return plan
        }
        return IterationPlan.Rows(scope, isGlobalRows(rootGroupOf(errorPath), ast))
    }

    /** Execute the already-prepared iteration decision against one document. */
    fun iterationContexts(
        plan: IterationPlan,
        doc: Document,
        /** Partial validation only: gates which INDEX cells key a parallel join ([IF269]). Null = every cell,
         *  which is full validation and every compute consumer. */
        cellRelevant: ((String, List<Int>) -> Boolean)? = null,
        /** Complete formal-admission gate for a stored index cell. Null is reserved for callers without a
         * validation pass; [Document] then applies its model-derived built-in formal check. */
        indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)? = null,
        /** Validation, full or partial, iterates the kernel's declared/occupied domain
         *  ([Document.fullValidationContextsFor]); computation keeps its stored rows. */
        validation: Boolean = false,
    ): List<EvalContext> = when (plan) {
        is IterationPlan.Rows -> when {
            !validation -> doc.contextsFor(plan.group)
            plan.global && cellRelevant == null -> doc.globalValidationContextsFor(plan.group, ::capacityOf)
            else -> doc.fullValidationContextsFor(plan.group, ::capacityOf)
        }
        is IterationPlan.Once -> if (plan.global) doc.globalContextFor(plan.rootGroup) else doc.onceContextFor(plan.rootGroup)
        is IterationPlan.Parallel ->
            if (validation) doc.validationParallelContexts(plan.join, cellRelevant, indexIdentityAdmitted, ::capacityOf)
            else doc.parallelContexts(plan.join, cellRelevant, indexIdentityAdmitted)
    }

    /** A group's declared capacity: its repeatability, unbounded when undeclared, and 1 when not repeatable. */
    private fun capacityOf(group: String): Int =
        model.declaredReps[group] ?: if (group in model.repeatable) Int.MAX_VALUE else 1

    /** Whether [plan] directly iterates [group]. A descendant iteration still needs semantic selection to replace
     * the indexed ancestor coordinate while retaining its own lower repetition coordinates. */
    fun bindsRepeatableGroup(plan: IterationPlan, group: String): Boolean = when (plan) {
        is IterationPlan.Once -> false
        is IterationPlan.Rows -> plan.group == group
        // Not [IterationPlan.Parallel.nested]: a selection names a keyed group, and the nested level has no key.
        is IterationPlan.Parallel -> group in plan.joined
    }

    /**
     * Whether A12 files this once-evaluated rule in its GLOBAL bucket rather than under its error field's root.
     *
     * A root rule runs only inside a root instance holding content, while a global one runs once with no such
     * gate, at the all-ones row (codegen `CollectedRulesPerRootGroupCreator` + `RulesInRootGroup_method`, measured
     * in `adapter.laws.RuleBucketGateDiffTest`). Three of A12's reasons apply here:
     *  - the error root is repeatable, which for a once plan means the rule does not iterate that root;
     *  - the condition names a second root group, in any role;
     *  - a comparison counts on its small side ([comparesACountOnItsSmallSide]).
     *
     * A12's fourth reason, a condition that can fire empty, needs no bucket here, because [Interpreter]'s row gate
     * already admits it, and its legacy `Usb` and root-count reasons have no interpreter construct.
     */
    private fun isGlobal(errorRoot: String, ast: Cond): Boolean =
        errorRoot in model.repeatable || namesASecondRoot(errorRoot, ast) || comparesACountOnItsSmallSide(ast)

    /**
     * Whether a rule iterating rows below the nonrepeatable [errorRoot] sits in A12's GLOBAL bucket, which runs with
     * no root gate and no row-content gate at every declared row ([KERNEL-FINDINGS KF507]; INTERPRETER-FINDINGS
     * IF434, `adapter.laws.GlobalRowRuleDiffTest`). Here the fourth reason counts too, a condition that can fire
     * empty, because the row gate admitting it does not supply the root instance a blank document lacks. Below a
     * repeatable root the dispatch is unmeasured, so such a rule keeps its stored root instances.
     */
    private fun isGlobalRows(errorRoot: String, ast: Cond): Boolean =
        errorRoot !in model.repeatable &&
            (namesASecondRoot(errorRoot, ast) || comparesACountOnItsSmallSide(ast) || ast.canFireEmpty())

    private fun namesASecondRoot(errorRoot: String, ast: Cond): Boolean =
        (entityPaths(ast).map(::rootGroupOf) + errorRoot).toSet().size > 1

    /**
     * The deepest repeatable BINDING level across the condition's starred refs (tier 2), or null. [withheld] leaves
     * before the choice, as in [deepestRepeatableAnchor]: dropped after it, a tie between it and another level
     * depended on which star was written first (`JoinPlanningShapeTest`).
     */
    private fun starBindingAnchor(ast: Cond, withheld: String?): String? =
        starRefs(ast).flatMap { bindingLevels(it) }.filter { it in model.repeatable && it != withheld }
            .maxByOrNull { it.count { c -> c == '/' } }

    /** The group paths STRICTLY ABOVE [star]'s first `*`-marked group — the levels the star binds (its
     *  [Cond]-side twin is [io.github.mbackschat.a12.dm.interpreter.eval.Document]'s `groupScopePrefix` cap):
     *  [Expr.Star.bindDepth] segments when parsed, else the hand-built default (the leaf group's parent). */
    private fun bindingLevels(star: Expr.Star): List<String> {
        val parts = pathParts(star.path)
        val depth = if (star.bindDepth >= 0) minOf(star.bindDepth, parts.size)
        else parts.size - (if (model.kinds[star.path] == null) 1 else 2)
        return (1..depth).map { d -> "/" + parts.take(d).joinToString("/") }
    }

    /**
     * Prepare and validate the document-independent shape of one parallel join of [scope] with [parallelGroups]:
     * the joined groups, iterated jointly over the union of their shared index field's values by
     * [Document.parallelContexts]. Each joined group must carry the **same-named** index field. Non-repeatable
     * intermediates between the common ancestor and a joined group are transparent — sibling, wrapped, and
     * asymmetric-depth groups all qualify (the kernel renders the wrappers `[1]`). A *repeatable* non-indexed
     * intermediate above ONE joined group is a **frame**: the kernel iterates it normally and joins inside each
     * frame row (the `/Plan/X/A ⋈ B` case). A repeatable frame above MORE THAN ONE joined group (an exotic
     * multi-frame cross-product) throws [UnsupportedConstructException] (→ rule reported unsupported,
     * loud-not-wrong). An **invalid** index (duplicate/empty) is NOT declined — modeled exactly: the compromised
     * group's unmatched references read 3VL UNKNOWN (KERNEL-FINDINGS §9), and the auto checks still emit
     * `uniqueIndex`/`mandatoryField`.
     *
     * A [scope] without an index field joins through its repeatable parent when that parent carries one: A12 then
     * iterates [scope] inside each joined row, an unmatched partner included (KERNEL-FINDINGS KF603,
     * `adapter.laws.GroupTargetRuntimeDiffTest`). Only a direct child of the joined group is measured, so a deeper
     * or wrapped level keeps [scope] as the join side and is refused below for its missing index.
     */
    fun prepareParallel(scope: String, parallelGroups: List<String>): IterationPlan.Parallel {
        val parent = parentGroup(scope)
        val side = if (!hasIndexField(scope) && parent in model.repeatable && hasIndexField(parent)) parent else scope
        val joined = (listOf(side) + parallelGroups).distinct()
        // A12 refuses a reference chain crossing two keyed levels beside a join (`MVK_MULTIPLE_INDEX_FIELDS`,
        // KERNEL-FINDINGS KF602), so a joined group below another keyed group has no runtime behaviour to match.
        // The outer keyed group may itself be joined, or may have left the join because the planner started from
        // the inner group, which would otherwise make it a frame: both are declined, whichever was written first.
        joined.firstOrNull { inner -> joined.any { inner.startsWith("$it/") } }?.let { inner ->
            throw UnsupportedConstructException("parallel iteration joining $inner inside another joined group is not modeled")
        }
        val commonParent = commonAncestor(joined)
        val parentDepth = pathParts(commonParent).size
        // A "framed" joined group sits under a repeatable non-indexed ancestor (strictly between the common
        // ancestor and it) — the kernel iterates that frame normally and joins inside each frame row (the
        // `/Plan/X/A ⋈ B` case). At most ONE joined group may be framed; a frame above two joined groups is an
        // exotic cross-product, not modeled → loud decline. A keyed one is the second keyed level declined here.
        fun frameAncestors(g: String) = ancestorGroups(g).filter { pathParts(it).size > parentDepth && it in model.repeatable }
        joined.firstOrNull { g -> frameAncestors(g).any(::hasIndexField) }?.let { inner ->
            throw UnsupportedConstructException("parallel iteration joining $inner below another keyed group is not modeled")
        }
        val indexNames = joined.map { g ->
            val indexField = indexFieldOf(g)
                ?: throw UnsupportedConstructException("parallel iteration group $g has no index field")
            pathParts(indexField).last()
        }.distinct()
        if (indexNames.size != 1) {
            throw UnsupportedConstructException(
                "parallel-iterated groups must share one index field name, found $indexNames")
        }
        val framed = joined.filter { frameAncestors(it).isNotEmpty() }
        if (framed.size > 1) {
            throw UnsupportedConstructException(
                "parallel iteration with repeatable frames above multiple joined groups ($framed) is not modeled")
        }
        val frameGroup = framed.firstOrNull()
        val deepestFrame = frameGroup?.let { g -> frameAncestors(g).maxByOrNull { pathParts(it).size } }
        val nested = scope.takeIf { it != side }
        // The nested level is measured only below a two-group join without a frame (KERNEL-FINDINGS KF603).
        if (nested != null && (frameGroup != null || joined.size > 2)) throw UnsupportedConstructException(
            "a level iterated below a joined group of a framed or three-group join ($joined) is not modeled")
        return IterationPlan.Parallel(joined, indexNames.single(), commonParent, frameGroup, deepestFrame, nested)
    }

    private fun indexFieldOf(group: String): String? = model.indexFields.firstOrNull { parentGroup(it) == group }

    private fun hasIndexField(group: String): Boolean = indexFieldOf(group) != null

    /**
     * Validate the views a [io.github.mbackschat.a12.dm.interpreter.eval.Document]'s parallel row can supply.
     * This lives on the preparation boundary used by execution, so value-independent declines cannot hide behind
     * a branch or an empty document and cannot drift from a separate support-only AST recognizer.
     */
    private fun requireParallelDocumentSupport(ast: Cond, plan: IterationPlan.Parallel) {
        val joined = plan.joined
        val commonParent = plan.commonParent
        // A joined row reads a starred domain from its common-parent row (KERNEL-FINDINGS KF567), or, below a joined
        // group, from that group's joined row (KF573); the runtime twin is the parallel row's `domainFor`. So a star
        // may bind no repeatable level below the common parent other than a joined group; a framed join's frame is
        // not measured, so it stays declined. A keyed read needs no twin here: A12 refuses one below a joined group
        // (`MVK_MULTIPLE_INDEX_FIELDS`) and one under an unstarred repeatable ancestor (`MVK_NO_WILDCARD`),
        // `adapter.laws.JoinedRowAggregateDiffTest`.
        val commonDepth = pathParts(commonParent).size
        fun boundByJoin(star: Expr.Star, level: String) = level in joined && star.path.startsWith("$level/")
        starRefs(ast).firstOrNull { star ->
            bindingLevels(star).drop(commonDepth).any { it in model.repeatable && !boundByJoin(star, it) }
        }
            ?.let { star ->
                throw UnsupportedConstructException(
                    "an aggregate over ${star.path} bound below the parallel iteration's common parent $commonParent is not modeled")
            }
        // A group below a joined side reads within that side's joined row (`t8`, KERNEL-FINDINGS KF603); one at or
        // below a nested level is unmeasured.
        fun readable(group: String) = group in joined || group == commonParent ||
            (joined.any { group.startsWith("$it/") } &&
                plan.nested?.let { group == it || group.startsWith("$it/") } != true)
        parallelGroupReads(ast).firstOrNull { !readable(it) }?.let { group ->
            throw UnsupportedConstructException(
                "group-filled on a non-parallel group ($group) inside a parallel iteration is not modeled")
        }
    }

    /**
     * A computation's joined row answers a group's presence only for a joined group or the common parent (the
     * parallel row's `groupFilled`), and a read of any other group there is unmeasured. Declined here, before any
     * document is read, because the runtime refusal is reached only where a precondition gets that far, so it
     * declined one document and computed the next (`adapter.laws.GroupTargetCarrierDiffTest`).
     */
    internal fun requireParallelComputationSupport(computation: ParsedComputation, plan: IterationPlan.Parallel) {
        val reads = listOfNotNull(computation.commonPrecondition).flatMap(::parallelGroupReads) +
            computation.alternatives.flatMap { alternative ->
                alternative.precondition?.let(::parallelGroupReads).orEmpty() + parallelGroupReads(alternative.operation)
            }
        reads.firstOrNull { it !in plan.joined && it != plan.commonParent }?.let { group ->
            throw UnsupportedConstructException(
                "group-filled on a non-parallel group ($group) inside a parallel computation is not modeled")
        }
    }

    /** Bare-group operands that dispatch to [EvalContext.groupFilled] in a parallel row. */
    private fun parallelGroupReads(cond: Cond): List<String> = when (cond) {
        is Cond.Cmp -> parallelGroupReads(cond.left) + parallelGroupReads(cond.right)
        is Cond.And -> parallelGroupReads(cond.left) + parallelGroupReads(cond.right)
        is Cond.Or -> parallelGroupReads(cond.left) + parallelGroupReads(cond.right)
        is Cond.Not -> parallelGroupReads(cond.c)
        is Cond.Pred -> {
            val op = PredicateOp.byName(cond.name)
            val own = if (op?.countsGroups == true ||
                op == PredicateOp.GROUP_FILLED ||
                op == PredicateOp.GROUP_NOT_FILLED
            ) {
                cond.args.mapNotNull { (it as? Expr.Field)?.path }
            } else {
                emptyList()
            }
            own + cond.args.flatMap(::parallelGroupReads)
        }
        is Cond.Pattern -> parallelGroupReads(cond.value) + parallelGroupReads(cond.pattern)
        is Cond.Tolerance -> parallelGroupReads(cond.left) + parallelGroupReads(cond.right)
        is Cond.ValueListQuant ->
            cond.fields.flatMap(::parallelGroupReads) + cond.values.flatMap(::parallelGroupReads)
        is Cond.Custom -> emptyList()
    }

    private fun parallelGroupReads(expr: Expr): List<String> = when (expr) {
        is Expr.Func -> {
            val own = if (FunctionOp.byName(expr.name) == FunctionOp.NUMBER_OF_FILLED_GROUPS) {
                expr.args.mapNotNull { (it as? Expr.Field)?.path }
            } else {
                emptyList()
            }
            own + expr.args.flatMap(::parallelGroupReads)
        }
        is Expr.Arith -> parallelGroupReads(expr.left) + parallelGroupReads(expr.right)
        is Expr.Star -> expr.having?.let(::parallelGroupReads) ?: emptyList()
        is Expr.TermSum -> parallelGroupReads(expr.iteration) + parallelGroupReads(expr.term)
        is Expr.EntitySpec -> when (val source = expr.source) {
            is Expr.Star -> source.having?.let(::parallelGroupReads) ?: emptyList()
            is Expr.Field -> emptyList()
        }
        is Expr.SemanticIndex -> emptyList()
        is Expr.Field, is Expr.NumLit, is Expr.StrLit, is Expr.BoolLit, is Expr.Const, is Expr.EnumCategory ->
            emptyList()
    }

    /** The deepest common ancestor group path of [groups] (their longest shared path prefix). */
    private fun commonAncestor(groups: List<String>): String {
        val partsList = groups.map { pathParts(it) }
        var n = 0
        while (n < partsList.minOf { it.size } && partsList.all { it[n] == partsList[0][n] }) n++
        return "/" + partsList[0].take(n).joinToString("/")
    }

    /** The deepest repeatable group ancestor of any [refs] path, else [defaultGroup]. */
    fun scopeFromRefs(refs: List<String>, defaultGroup: String): String =
        deepestRepeatableAnchor(refs) ?: defaultGroup

    /** The deepest repeatable group ancestor-or-self of any [refs] path other than [withheld], or null. */
    private fun deepestRepeatableAnchor(refs: List<String>, withheld: String? = null): String? =
        refs.flatMap(::repeatableLevels).filter { it != withheld }.maxByOrNull { it.count { c -> c == '/' } }

    // A referenced group owns its level in both ordinary scope and join detection (SPEC05).
    private fun repeatableLevels(ref: String): List<String> =
        (ancestorGroups(ref) + listOfNotNull(ref.takeIf { model.kinds[ref] == null }))
            .filter { it in model.repeatable }

    /** Selection replaces only the indexed level. Ordinary ancestors still iterate; a lower star collapses
     * its own suffix (SelectedAuthoredScopeDiffTest, SPEC-2026-10-10-03). */
    private fun ordinaryLevelsOfSelection(spec: Expr.EntitySpec): List<String> {
        val source = spec.source
        val indexGroup = nearestIndexFieldOf(model.indexFields, source.path)?.let(::parentGroup)
            ?: return emptyList()
        val targetGroups = when (source) {
            is Expr.Field -> ancestorGroups(source.path) + listOfNotNull(source.path.takeIf { it in model.groupPaths })
            is Expr.Star -> bindingLevels(source)
        }
        return targetGroups.filter { it in model.repeatable && it != indexGroup }
    }
}
