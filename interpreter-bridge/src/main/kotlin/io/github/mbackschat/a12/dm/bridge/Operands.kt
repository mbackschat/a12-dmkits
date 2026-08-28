package io.github.mbackschat.a12.dm.bridge

import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ValueKind
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.rulekit.dsl.AggregateOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupSemanticIndexFieldValue
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupSemanticIndexValue
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedFieldValue
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedGroupValue
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndex

/**
 * Operand shapes — what a path in the condition text resolves to in rulekit's typed world.
 *
 * Shared by every operator, so a shape added here widens the whole bridge at once. The interpreter gives an
 * operand as a bare path plus a shape (`Field`, `Star`, `SemanticIndex`); rulekit wants a typed ref whose kind
 * and repeatability come from the model.
 *
 * Four slot widths, and a caller picks by what its operator accepts: [fieldRef] for a scalar, [fieldOperand]
 * for a field-only list, [aggregateOperand] for an entity list (which also admits a group scope), [groupRef]
 * for a group. The semantic index is its own pair — [semanticIndex] for the presence handle, [indexedValue]
 * for the value.
 */
internal class Operands(private val mapping: Mapping) {

    /** The model's element paths, read once: an existence test runs per operand. */
    private var declaredPaths: Set<String>? = null

    /**
     * Fail here, before rulekit is asked, when the path names nothing in the model.
     *
     * Asking first rather than letting `BoundModel` raise it is what keeps the two kinds of refusal apart at
     * the source instead of by parsing a message afterwards: past this point every [IllegalArgumentException]
     * is rulekit's verdict on an operand it understood.
     */
    private fun declared(path: String): String {
        val paths = declaredPaths ?: mapping.index().elementPaths().toList().toSet().also {
            declaredPaths = it
        }
        if (path !in paths) {
            throw OperandResolutionException("no element at path: $path", null)
        }
        return path
    }

    /**
     * Resolve an operand that must name one field — a scalar slot. An [Expr.Star] can never satisfy it:
     * rulekit types this slot as [FieldRef] alone, the split [FieldOperand]'s own doc notes as what erases
     * `MVK_NO_WILDCARDS_ALLOWED` (verified 2026-06-04, KERNEL-FINDINGS §9) — there is no runtime shape a
     * repetition could take here for rulekit to reject in kind, so a starred operand in a scalar slot is
     * correctly [UnmappedConstructException], not a gap to close.
     */
    fun fieldRef(node: Expr): FieldRef = when (node) {
        is Expr.Field -> correlate(mapping.model().field(declared(node.path)), node.outer)
        is Expr.EntitySpec -> throw UnmappedConstructException(
            "entitySpec on " + node.source.path +
                (if (node.source is Expr.Star) " (starred)" else "") +
                (if (node.category != null) " with category " + node.category else "") +
                (if (node.semanticIndex != null) " with a semantic index" else "") +
                " in a field operand slot — rulekit's FieldRef resolves one plain field, with no shape for a" +
                " repetition-picked or category-projected cell"
        )
        is Expr.Star -> throw NoTypedOperationException(
            "a star in a scalar field slot: rulekit types the slot to FieldRef alone, which is how it states" +
                " MVK_NO_WILDCARDS_ALLOWED"
        )
        else -> throw UnmappedConstructException("field operand " + node.javaClass.simpleName)
    }

    /** Resolve an operand that must name a group. */
    fun groupRef(node: Expr): GroupRef {
        if (node is Expr.Star && node.having != null) {
            throw UnmappedConstructException(
                "a Having filter on the group operand " + node.path + " — a GroupRef carries no filter"
            )
        }
        if (node is Expr.EntitySource) {
            requireNotCorrelated(node, "a group")
            return mapping.model().group(declared(node.path))
        }
        throw if (node is Expr.SemanticIndex) {
            NoTypedOperationException(
                "a semantic index in a group slot: rulekit's GroupRef names one group, with no shape for a" +
                    " repetition-picked one"
            )
        } else {
            UnmappedConstructException("group operand " + node.javaClass.simpleName)
        }
    }

    /**
     * Resolve an operand in a FIELD-only list slot — a field, a starred field's repetition list, or an
     * index-selected field. A group scope is not a [FieldOperand] in rulekit, so an operator that admits one
     * takes the wider [aggregateOperand] instead.
     *
     * An index-selected FIELD behaves like a single field entity here, so it routes to the same
     * [indexedValue] the scalar comparison path already uses, now that [IndexedFieldValue] is a
     * [FieldOperand]. This is distinct from an [Expr.EntitySpec] with a semantic index (a starred
     * navigational prefix plus a terminal index), which [fieldRef] still refuses — that shape's resolution
     * path is not designed yet.
     */
    fun fieldOperand(node: Expr): FieldOperand {
        if (node is Expr.Star) {
            if (mapping.isGroup(node.path)) {
                throw UnmappedConstructException(
                    "the group scope " + node.path +
                        " in a field-only slot — only an entity-list slot admits a group"
                )
            }
            return fieldList(node)
        }
        if (node is Expr.SemanticIndex && !mapping.isGroup(node.path)) {
            return indexedValue(node)
        }
        return fieldRef(node)
    }

    /** Resolve a raw group-reference token — absolute, relative to the base path, or the rule-group keyword. */
    fun groupReference(rawReference: String): GroupRef {
        val reference = rawReference.trim()
        if (reference.isEmpty()) {
            throw UnmappedConstructException("empty group reference")
        }
        if (reference in RULE_GROUP) {
            return mapping.model().group(declared(mapping.basePath()))
        }
        return mapping.model().group(declared(ModelIndex.resolvePath(mapping.basePath(), walk(reference))))
    }

    /**
     * The typed ref for an operand, chosen by the kind the model gives it.
     *
     * rulekit publishes a good deal of its kernel knowledge on the TYPED refs rather than on `FieldRef` — the
     * value-list membership operators, the comparisons, the domain check on an enum literal — so an operator
     * that needs one of those has to narrow first. The narrowing itself is a rulekit check: `str` on a number
     * field refuses, and that refusal is a finding.
     */
    fun typedRef(node: Expr): Any {
        val field = fieldRef(node)
        val path = field.fieldPath()
        return when (field.kind()) {
            ValueKind.STRING -> mapping.model().str(path)
            ValueKind.CUSTOM -> mapping.model().custom(path)
            ValueKind.NUMBER -> mapping.model().num(path)
            ValueKind.ENUMERATION -> mapping.model().enumOf(path)
            ValueKind.DATE, ValueKind.DATE_FRAGMENT -> mapping.model().date(path)
            ValueKind.TIME -> mapping.model().time(path)
            ValueKind.DATE_TIME -> mapping.model().dateTime(path)
            ValueKind.DATE_RANGE -> mapping.model().dateRange(path)
            else -> throw UnmappedConstructException(
                "no typed rulekit ref for " + field.kind() + " at " + path
            )
        }
    }

    /**
     * Resolve an operand in an entity-list slot, which accepts a field, a repetition list, a group scope, an
     * index-selected FIELD, or — since [IndexedGroupValue] became an [AggregateOperand] — an index-selected
     * GROUP. Both indexed forms behave like a single entity here: they never satisfy arity alone the way a
     * starred [FieldListRef]/[GroupListRef] does, and the duplicate gate keys on path AND index (the identity
     * switches [IndexedFieldValue.duplicateIdentity] / [IndexedGroupValue.duplicateIdentity] feed are
     * rulekit's, not this bridge's).
     */
    fun aggregateOperand(node: Expr): AggregateOperand {
        if (node is Expr.Star) {
            // A starred GROUP is a group scope; a starred field is a flattened repetition list. Asking the
            // model which it is beats guessing from the path's shape.
            return if (mapping.isGroup(node.path)) groupScope(node) else fieldList(node)
        }
        if (node is Expr.SemanticIndex) {
            return if (mapping.isGroup(node.path)) indexedGroup(node) else indexedValue(node)
        }
        if (node is Expr.Field && mapping.isGroup(node.path)) {
            requireNotCorrelated(node, "a group scope")
            return mapping.model().flattenGroup(declared(node.path))
        }
        return fieldRef(node)
    }

    /**
     * The `For`-indexed cell as a PRESENCE handle — `FieldFilled([Sku For "S-1"])`. The literal key and the
     * field key are the two grammar alternatives, and rulekit keeps them apart the same way.
     */
    fun semanticIndex(node: Expr): SemanticIndex {
        if (node is Expr.SemanticIndex) {
            val field = correlate(mapping.model().field(declared(node.path)), node.outer)
            val keyField = node.indexFieldPath
            return if (keyField == null) {
                field.semanticIndex(node.indexValue)
            } else {
                field.semanticIndex(correlate(mapping.model().field(declared(keyField)), node.indexFieldOuter))
            }
        }
        throw UnmappedConstructException("semantic index operand " + node.javaClass.simpleName)
    }

    /** The same cell as a comparison operand — its VALUE rather than its presence. */
    fun indexedValue(node: Expr): IndexedFieldValue = semanticIndex(node).index()

    /**
     * The group analog of [semanticIndex] — an index-selected GROUP, resolved the same way as the plain
     * (unindexed) group arm above: `flattenGroup` reads the model's own repeatability, and its result is never
     * re-guessed here. A group can never itself carry `$`, so an outer-marked target is refused the same way.
     * The wildcard/index conflict (`MVK_SEMANTIC_INDEX_AND_WILDCARD`) is rulekit's own guard, in
     * [IndexedGroupValue]'s leaf records — not pre-checked here.
     */
    fun indexedGroup(node: Expr): IndexedGroupValue {
        if (node is Expr.SemanticIndex) {
            if (node.outer) {
                throw UnmappedConstructException(
                    "the \$-correlation on a group's own semantic index, " + node.path +
                        " — rulekit correlates a field or a repetition list, not a group"
                )
            }
            val group = mapping.model().flattenGroup(declared(node.path))
            val keyField = node.indexFieldPath
            return if (keyField == null) {
                GroupSemanticIndexValue(group, node.indexValue)
            } else {
                GroupSemanticIndexFieldValue(
                    group,
                    correlate(mapping.model().field(declared(keyField)), node.indexFieldOuter),
                )
            }
        }
        throw UnmappedConstructException("group semantic index operand " + node.javaClass.simpleName)
    }

    private fun fieldList(star: Expr.Star): FieldListRef {
        declared(star.path)
        var list = mapping.model().flatten(starPath(star))
        val having = star.having
        if (having != null) {
            list = list.having(mapping.condition(having))
        }
        return if (star.outer) list.outer() else list
    }

    private fun groupScope(star: Expr.Star): GroupListRef {
        if (star.having != null) {
            throw UnmappedConstructException(
                "a Having filter on the group scope " + star.path + " — only a repetition list carries one"
            )
        }
        requireNotCorrelated(star, "a group scope")
        declared(star.path)
        return mapping.model().flattenGroup(starPath(star))
    }

    /**
     * The starred path rulekit's `flatten`/`flattenGroup` read, rebuilt from the AST.
     *
     * [Expr.Star] holds the path with its stars STRIPPED plus `bindDepth` — the count of segments strictly
     * above the FIRST starred one — so only that first star survives parsing verbatim. The deeper ones come
     * back off the model, by the split the interpreter's own `starScope` makes: a repeatable level below the
     * first star always iterates. The authored star is re-emitted whatever the model says about ITS level,
     * because a star on a non-repeatable group is `MVK_INVALID_WILDCARD` and a star dropped here would take
     * that finding with it.
     */
    private fun starPath(star: Expr.Star): String {
        val parts = star.path.substring(1).split("/")
        val firstStar = if (star.bindDepth >= 0) {
            minOf(star.bindDepth, parts.size - 1)
        } else {
            deepestRepeatable(parts)
        }
        val rendered = StringBuilder()
        val prefix = StringBuilder()
        for (level in parts.indices) {
            prefix.append('/').append(parts[level])
            rendered.append('/').append(parts[level])
            if (level == firstStar || (level > firstStar && repeats(prefix.toString()))) {
                rendered.append('*')
            }
        }
        return rendered.toString()
    }

    /** Where a hand-built node's `-1` points: the deepest repeatable level, the leaf group's own. */
    private fun deepestRepeatable(parts: List<String>): Int {
        val prefix = StringBuilder()
        var deepest = 0
        for (level in parts.indices) {
            prefix.append('/').append(parts[level])
            if (repeats(prefix.toString())) {
                deepest = level
            }
        }
        return deepest
    }

    /** Guarded by `isGroup` because `isRepeating` reads a group's repeatability and a field has none. */
    private fun repeats(path: String): Boolean = mapping.isGroup(path) && mapping.index().isRepeating(path)

    private companion object {
        /**
         * Both spellings of the rule-group self-reference. The parser captures a scope token raw, without the
         * canonicalisation it applies to operator keywords, so the German form arrives verbatim and matching
         * only the English one would silently resolve it to a nonexistent child group.
         */
        private val RULE_GROUP = setOf("RuleGroup", "RegelKontext")

        /**
         * The grammar's turning-point form: `..Name` means one level up, and the label names the ancestor
         * expected there rather than adding a segment. The parser reads it the same way —
         * `skipTurningPointLabel` consumes and discards the identifier — so `..kx` from `/A/B/kx/C` is
         * `/A/B/kx`, not a child called `..kx`. A scope token reaches a mapper raw, without the path machinery
         * the operands go through, so the form has to be recognised here too.
         */
        private fun walk(reference: String): String {
            if (!reference.startsWith("..") || reference.length == 2 || reference[2] == '/') {
                return reference
            }
            val slash = reference.indexOf('/', 2)
            return if (slash < 0) ".." else ".." + reference.substring(slash)
        }

        private fun correlate(ref: FieldRef, outer: Boolean): FieldRef = if (outer) ref.outer() else ref

        private fun requireNotCorrelated(source: Expr.EntitySource, what: String) {
            if (source.outer) {
                throw UnmappedConstructException(
                    "the \$-correlation on " + what + ", " + source.path +
                        " — rulekit correlates a field or a repetition list, not a group"
                )
            }
        }
    }
}
