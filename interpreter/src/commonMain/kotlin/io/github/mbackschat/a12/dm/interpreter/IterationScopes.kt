package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef

/**
 * The iteration-scope machinery — a call-invariant collaborator of the I2 eval-state seam (IF117): which
 * repeatable group a rule/computation iterates ([scopeFromRefs]), the per-row contexts of that scope incl.
 * the error-entity re-anchor ([iterationContexts], IF103), and the parallel-iteration outer join
 * ([parallelIterationContexts], KERNEL-SEMANTICS §9). Shared by the rule path, the `RepetitionNotUnique`
 * guard-scope check, and the computation engine. Allocated once per [DmInterpreter].
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
     * shared index field (KERNEL-SEMANTICS §9). [parallelIterationContexts] builds that join; an unsupported
     * parallel shape (non-sibling depth, missing / mismatched index field) fails loud (reported unsupported),
     * never silently wrong.
     */
    fun iterationContexts(rule: RuleDef, ast: Cond, doc: Document): List<EvalContext> {
        val refs = nonStarredRefs(ast)
        val scope = deepestRepeatableAnchor(refs) ?: run {
            val starAnchor = starBindingAnchor(ast)
            return if (starAnchor != null) doc.contextsFor(starAnchor)
            else doc.onceContextFor(rootGroupOf(resolveErrorPath(rule)))
        }
        val parallelGroups = refs.flatMap { ancestorGroups(it).filter { g -> g in model.repeatable } }
            .distinct().filter { it != scope && !scope.startsWith("$it/") }
        if (parallelGroups.isNotEmpty()) return parallelIterationContexts(scope, parallelGroups, doc)
        return doc.contextsFor(scope)
    }

    /** The deepest repeatable BINDING level across the condition's starred refs (tier 2), or null. */
    private fun starBindingAnchor(ast: Cond): String? =
        starRefs(ast).flatMap { bindingLevels(it) }.filter { it in model.repeatable }
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
     * Build the parallel-iteration outer-join contexts: the joined groups (the [scope] group + the
     * [parallelGroups]) iterated jointly over the union of their shared index field's values (delegated to
     * [Document.parallelContexts]). Each joined group must carry the **same-named** index field. Non-repeatable
     * intermediates between the common ancestor and a joined group are transparent — sibling, wrapped, and
     * asymmetric-depth groups all qualify (the kernel renders the wrappers `[1]`). A *repeatable* non-indexed
     * intermediate above ONE joined group is a **frame**: the kernel iterates it normally and joins inside each
     * frame row (the `/Plan/X/A ⋈ B` case), supported via the `frameGroup` argument. The one **unsupported
     * shape** is a repeatable frame above MORE THAN ONE joined group (an exotic multi-frame cross-product),
     * which throws [UnsupportedConstructException] (→ rule reported unsupported, loud-not-wrong). An **invalid**
     * index (duplicate/empty) is NOT declined — modeled exactly: the compromised group's unmatched references
     * read 3VL UNKNOWN (KERNEL-FINDINGS §9), and the auto checks still emit `uniqueIndex`/`mandatoryField`.
     */
    fun parallelIterationContexts(scope: String, parallelGroups: List<String>, doc: Document): List<EvalContext> {
        val joined = (listOf(scope) + parallelGroups).distinct()
        val commonParent = commonAncestor(joined)
        val parentDepth = pathParts(commonParent).size
        val indexNames = joined.map { g ->
            val indexField = model.indexFields.firstOrNull { parentGroup(it) == g }
                ?: throw UnsupportedConstructException("parallel iteration group $g has no index field")
            pathParts(indexField).last()
        }.distinct()
        if (indexNames.size != 1) {
            throw UnsupportedConstructException(
                "parallel-iterated groups must share one index field name, found $indexNames")
        }
        // A "framed" joined group sits under a repeatable non-indexed ancestor (strictly between the common
        // ancestor and it) — the kernel iterates that frame normally and joins inside each frame row (the
        // `/Plan/X/A ⋈ B` case). At most ONE joined group may be framed; a frame above two joined groups is an
        // exotic cross-product, not modeled → loud decline.
        fun frameAncestors(g: String) = ancestorGroups(g).filter { pathParts(it).size > parentDepth && it in model.repeatable }
        val framed = joined.filter { frameAncestors(it).isNotEmpty() }
        if (framed.size > 1) {
            throw UnsupportedConstructException(
                "parallel iteration with repeatable frames above multiple joined groups ($framed) is not modeled")
        }
        val frameGroup = framed.firstOrNull()
        val deepestFrame = frameGroup?.let { g -> frameAncestors(g).maxByOrNull { pathParts(it).size } }
        return doc.parallelContexts(joined, indexNames.single(), commonParent, frameGroup, deepestFrame)
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

    /** The deepest repeatable group ancestor-or-self of any [refs] path, or null when none is repeatable. */
    private fun deepestRepeatableAnchor(refs: List<String>): String? =
        refs.flatMap { ref ->
            // ancestor-or-SELF: a bare GROUP reference (a group-scope quantifier arg — legal un-starred on an
            // indexed repeatable group) is itself an iteration anchor: the kernel iterates the rule PER ROW of
            // the referenced repeatable group and judges each row's cells (the bare-group
            // FieldsNotCollectivelyFilled idiom — kernel-pinned per-row fire set + pointers, IF106). The error
            // entity is NOT an anchor (the kernel-captured corpus cases pin that), only the references are.
            (ancestorGroups(ref) + listOfNotNull(ref.takeIf { model.kinds[ref] == null }))
                .filter { it in model.repeatable }
        }.maxByOrNull { it.count { c -> c == '/' } }
}
