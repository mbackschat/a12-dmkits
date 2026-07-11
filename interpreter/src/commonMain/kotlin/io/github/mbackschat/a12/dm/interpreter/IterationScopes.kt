package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
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
     * The contexts a rule is evaluated over. The normal case: one per row of its iteration **scope** — the
     * **deepest repeatable group** that is an ancestor of any *non-aggregated* field reference (a starred ref
     * is an aggregate and collapses the repetition, so it does NOT force iteration); with no repeatable ref, the
     * scope is the rule's declared [RuleDef.group]. This is the kernel law (KERNEL-FINDINGS §9 supplement,
     * `IterationLawsTest`): the iteration scope is the set of *referenced* repeatable fields, NOT the rule's node
     * placement — a rule at `/Order` reaching into `/Order/Items` iterates the Items rows.
     *
     * **Parallel iteration** — when the condition references TWO (or more) NON-nested repeatable groups per-row
     * (siblings, neither an ancestor of the other), the kernel iterates them JOINTLY as an outer join keyed by a
     * shared index field (KERNEL-SEMANTICS §9). [parallelIterationContexts] builds that join; an unsupported
     * parallel shape (non-sibling depth, missing / mismatched index field) fails loud (reported unsupported),
     * never silently wrong.
     */
    fun iterationContexts(rule: RuleDef, ast: Cond, doc: Document): List<EvalContext> {
        val refs = nonStarredRefs(ast)
        val scope = scopeFromRefs(refs, rule.group)
        val parallelGroups = refs.flatMap { ancestorGroups(it).filter { g -> g in model.repeatable } }
            .distinct().filter { it != scope && !scope.startsWith("$it/") }
        if (parallelGroups.isNotEmpty()) return parallelIterationContexts(scope, parallelGroups, doc)
        val contexts = doc.contextsFor(scope)
        // A rule with NO repeatable non-starred ref, declared in a container without instance content (a
        // real-workspace `*_Rules` root referencing + erroring into OTHER roots), still evaluates ONCE — the
        // kernel's iteration is reference-driven, and the rule fires at its ERROR ENTITY (IF103, the ordinary-
        // rule sibling of IF100's implicit-validation anchor). Re-anchor at the error entity's parent ONLY when
        // the declaring scope yields nothing, so every currently-anchored rule is bit-identical.
        if (contexts.isEmpty() && scope == rule.group) {
            val errorAnchor = parentGroup(resolveErrorPath(rule))
            if (errorAnchor != scope) return doc.contextsFor(errorAnchor)
        }
        return contexts
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
        refs.flatMap { ref ->
            // ancestor-or-SELF: a bare GROUP reference (a group-scope quantifier arg — legal un-starred on an
            // indexed repeatable group) is itself an iteration anchor: the kernel iterates the rule PER ROW of
            // the referenced repeatable group and judges each row's cells (the bare-group
            // FieldsNotCollectivelyFilled idiom — kernel-pinned per-row fire set + pointers, IF106). The error
            // entity is NOT an anchor (the kernel-captured corpus cases pin that), only the references are.
            (ancestorGroups(ref) + listOfNotNull(ref.takeIf { model.kinds[ref] == null }))
                .filter { it in model.repeatable }
        }.maxByOrNull { it.count { c -> c == '/' } }
            ?: defaultGroup
}
