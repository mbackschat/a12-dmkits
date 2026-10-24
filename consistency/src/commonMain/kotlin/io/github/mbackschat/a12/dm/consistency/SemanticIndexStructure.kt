package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.SEMANTIC_INDEX
import io.github.mbackschat.a12.dm.consistency.syntax.SemanticIndexUse
import io.github.mbackschat.a12.dm.consistency.syntax.filterBodiesIn
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexUsesIn
import io.github.mbackschat.a12.dm.consistency.syntax.encloses

/**
 * What a semantic index may CARRY, and where the indexed group has to be.
 *
 * `[Coverages/Limit For key]` addresses one repetition, and its own structure carries nine rejection classes
 * decided by one chain ([KERNEL-FINDINGS KF351], `rulekit.validate.laws.SemanticIndexStructureLawsTest`).
 * [semanticIndexKeyContributions] owns the first of them — the key must be a field — and this family owns the
 * rest, including the two that read the KEY against the indexed group ([KERNEL-FINDINGS KF404],
 * `rulekit.validate.laws.SemanticIndexKeyGateLawsTest`).
 *
 * **The chain's order is measured, not read off the source**, because the pairs an author actually writes
 * violate two gates at once — and reading it wrongly is not hypothetical here. A star ABOVE the indexed
 * group looks from `CheckSemanticIndexImpl` like the FIRST gate, since the group is located before the key
 * is ever examined; it is in fact the LAST class of the whole chain, because it is raised by a later pass
 * over the finished index list rather than while the index is being read. Every other pair is measured the
 * same way: the missing index field beats the empty key, the key's type beats its placement, its placement
 * beats the missing value validation, and each of those beats the star at the indexed group's own level.
 *
 * **Three of the classes are about a STAR and are told apart by where it sits**, which is the sharpest
 * distinction here: inside the KEY it is `MVK_SEMANTIC_INDEX_WITH_WILDCARD`, at the indexed group's own
 * level `MVK_SEMANTIC_INDEX_AND_WILDCARD`, and above that group `MVK_SEMANTIC_INDEX_BELOW_WILDCARD`. Naming
 * the wrong one hands an author the fix for a gate they did not trip.
 *
 * **Silent where A12's ITERATION gate answers first.** An entity key whose read ITERATES — one reaching into
 * a repetition the rule does not itself sit in, without a star — draws `MVK_SEMANTIC_INDEX_INVALID_ITER`
 * whatever else is wrong with the index, and [semanticIndexKeyContributions] owns that class. What binds a level
 * is the key's [KeyAnchoring] — the declaring group's repetitions always, and outside a `Having` body the error
 * target's too ([KERNEL-FINDINGS KF605]) — so a key inside a repetition is judged here whenever one of those
 * binds it; treating every such key as opaque would lose `MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX`
 * entirely, since a key inside the indexed group is anchored by definition. Silent too on a GROUP key, which
 * is the same family's, and on a value spec or key that does not resolve.
 *
 * **Silent where the key's DECLARED type is one A12's base-type comparison leaves unsettled here.** The
 * comparison is [BaseTypeClass], not name equality, and a type definition takes the type it defines
 * ([resolvedFieldTypeOf]); a declaration outside that table — an unspecified field, or a definition that does
 * not resolve — leaves the whole index unjudged rather than guessing whether A12 calls it consistent.
 *
 * **Silent on a literal key whose index field this reader cannot judge.** `isValueApplicable` runs the index
 * field's own value validation over the constant, and [admitsIndexConstant] reproduces it per kind except where
 * the kind's rule is not one it can reproduce, such as a declared String pattern outside [KeyPattern]'s represented domain; a
 * later class named on such an index would be the wrong class on a correct rejection.
 */
internal fun TrackedFactBase.semanticIndexStructureContributions(element: TrackedDeclaration): List<ConditionFinding> {
    val execution = conditionExecution(element) ?: return emptyList()
    val keyword = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(SEMANTIC_INDEX) ?: return emptyList()
    val text = execution.carrier.text
    val uses = semanticIndexUsesIn(text, conditionLanguage, keyword).associateBy { it.keywordAt }
    val bodies = filterBodiesIn(text, conditionLanguage)
    return execution.recognition.sites.filterIsInstance<SyntaxSite.Completion>().mapNotNull { site ->
        if (site.kind != SyntaxSite.Kind.INDEX) return@mapNotNull null
        val use = uses[site.start] ?: return@mapNotNull null
        val inABody = bodies.any { it.encloses(use.keywordAt, use.keywordAt + 1) }
        // In a body A12 asks the key no iteration question, and the index's own gates answer ahead of the filter walk
        // (KERNEL-FINDINGS KF605, `adapter.laws.CheckerKeyedOperandFamilyAgreementTest`).
        val anchoring = keyAnchoringOf(inABody)
        val verdict = firstClassIn(listOf(use), element.path.substringBeforeLast('/'), element, anchoring)
            ?: return@mapNotNull null
        val span = execution.carrier.semanticSpan(site.start, site.end) ?: return@mapNotNull null
        ConditionFinding(element.key, site, structureFinding(verdict.first, element, span.member, span))
    }
}

/**
 * The class the FIRST defective index in one text draws, with that index, or null when this family may not
 * speak about it.
 *
 * A12 reports the first defect in a condition and nothing after it, so a text carrying two indices is not
 * two independent questions: a defect in the first withholds every class the second would have drawn. The
 * walk therefore stops at the first index it cannot read through — including one another family owns, which
 * is what keeps a group key in index one from letting a class out of index two.
 */
private fun TrackedFactBase.firstClassIn(
    uses: List<SemanticIndexUse>,
    declaringGroup: String,
    element: TrackedDeclaration,
    anchoring: KeyAnchoring,
): Pair<String, SemanticIndexUse>? {
    // A NESTED index is answered before the walk, because the index of `[A For B For C]` A12 judges is the
    // one written LAST, on `B` — the walk would reach the index on `A` first and stop at its
    // key, which is read from inside a repetition and so looks opaque. Its own key gate still runs ahead of
    // the nesting check, which is the source's order and the one row where the two compete.
    uses.firstOrNull { it.nested }?.let { outer ->
        return when (keyVerdict(outer, declaringGroup, element, anchoring)) {
            KeyVerdict.STARRED -> "MVK_SEMANTIC_INDEX_WITH_WILDCARD" to outer
            KeyVerdict.UNJUDGEABLE -> null
            KeyVerdict.PLAIN -> "MVK_SEMANTIC_INDEX_WITH_SEMANTIC_INDEX" to outer
        }
    }
    uses.forEach { use ->
        when (val verdict = classOf(use, declaringGroup, element, anchoring)) {
            is IndexVerdict.Reported -> return verdict.code to use
            IndexVerdict.Opaque -> return null
            IndexVerdict.Clean -> Unit
        }
    }
    return null
}

/**
 * Whether A12 admits [use] on every gate of the semantic-index chain, as far as this reader can tell.
 *
 * True only where the chain reads CLEAN: an index this family would refuse, or one it cannot judge — a group
 * key, a constant a table-backed enumeration judges, a key whose declared type is unsettled — is not admitted,
 * so a reader that honours a key on this answer honours only a key A12 does. A nested index never reaches here: [operandReferenceIn] honours a text with ONE index use.
 */
internal fun TrackedFactBase.admitsSemanticIndex(
    use: SemanticIndexUse,
    declaringGroup: String,
    element: TrackedDeclaration,
    anchoring: KeyAnchoring,
): Boolean = classOf(use, declaringGroup, element, anchoring) == IndexVerdict.Clean

/** What one index settles: a class, nothing to say, or that nothing past it may be judged either. */
private sealed interface IndexVerdict {
    data class Reported(val code: String) : IndexVerdict
    data object Clean : IndexVerdict
    data object Opaque : IndexVerdict
}

/** A12's class for this index, in the measured gate order. */
private fun TrackedFactBase.classOf(
    use: SemanticIndexUse,
    declaringGroup: String,
    element: TrackedDeclaration,
    anchoring: KeyAnchoring,
): IndexVerdict {
    val value = use.valuePath ?: return IndexVerdict.Opaque
    val indexed = resolveElement(value, declaringGroup, element) ?: return IndexVerdict.Opaque
    when (keyVerdict(use, declaringGroup, element, anchoring)) {
        KeyVerdict.UNJUDGEABLE -> return IndexVerdict.Opaque
        KeyVerdict.STARRED -> return IndexVerdict.Reported("MVK_SEMANTIC_INDEX_WITH_WILDCARD")
        KeyVerdict.PLAIN -> Unit
    }
    // A12 walks from the value up and refuses a second indexed group on the way, in the same walk that finds the
    // index group (`CheckEntityUtils.getIndexGroup`), measured on every operand gate
    // `adapter.laws.CheckerKeyedOperandAgreementTest` carries ([KERNEL-FINDINGS KF478]).
    if (indexedGroupsAbove(indexed).size > 1) {
        return IndexVerdict.Reported("MVK_MULTIPLE_INDEX_FIELDS")
    }
    val indexGroup = indexGroupOf(indexed) ?: return IndexVerdict.Reported("MVK_NO_INDEX_FIELD")
    val indexField = indexFieldOf(indexGroup) ?: return IndexVerdict.Opaque
    keyClassOf(use, indexGroup, indexField, declaringGroup, element)?.let { return it }
    val starred = starredPrefixesOf(value, declaringGroup, element)
    return when {
        use.keyCategory != null -> IndexVerdict.Reported("MVK_SEMANTIC_INDEX_WITH_CATEGORY")
        indexGroup in starred -> IndexVerdict.Reported("MVK_SEMANTIC_INDEX_AND_WILDCARD")
        starred.any { indexGroup.startsWith("$it/") } ->
            IndexVerdict.Reported("MVK_SEMANTIC_INDEX_BELOW_WILDCARD")
        else -> IndexVerdict.Clean
    }
}

/**
 * What the three gates that read the KEY against the index field settle, or null when none of them answers.
 *
 * They run as a block because A12 runs them as one: the type comparison, the containment test and the
 * value-validation check all sit ahead of every gate about a STAR, so an index tripping one of them plus a
 * star is this block's class and not the star's.
 *
 * A CONSTANT key meets the first of the three as an applicability test — A12 runs the index field's own
 * value validation over the constant — and this reader decides it where [admitsIndexConstant] can, leaving every
 * other constant-keyed index unjudged rather than naming a class A12 reaches past.
 */
private fun TrackedFactBase.keyClassOf(
    use: SemanticIndexUse,
    indexGroup: String,
    indexField: TrackedDeclaration,
    declaringGroup: String,
    element: TrackedDeclaration,
): IndexVerdict? {
    use.keyLiteral?.let { literal ->
        val admitted = literal.isNotEmpty() && (admitsIndexConstant(indexField, literal) ?: return IndexVerdict.Opaque)
        return if (admitted) null else IndexVerdict.Reported("MVK_INDEX_VALUE_INVALID")
    }
    val key = element(use.keyPath?.let { written -> resolveField(written, declaringGroup, element) })
        ?: return IndexVerdict.Opaque
    when (keyTypeVerdict(indexField, key)) {
        KeyTypeVerdict.UNSETTLED -> return IndexVerdict.Opaque
        KeyTypeVerdict.INCONSISTENT -> return IndexVerdict.Reported("MVK_INDEX_TYPE_INCONSISTENT")
        KeyTypeVerdict.CONSISTENT -> Unit
    }
    if (key.path.startsWith("$indexGroup/")) {
        return IndexVerdict.Reported("MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX")
    }
    return if (waivesValueValidation(key)) IndexVerdict.Reported("MVK_INVALID_RAW_TYPE") else null
}

/** The field a group declares as its index, or null where the declaration names none this reader can find. */
private fun TrackedFactBase.indexFieldOf(indexGroup: String): TrackedDeclaration? {
    val group = declarationAt(indexGroup) ?: return null
    val name = indexFieldNameOf(group) ?: return null
    return declarationAt("$indexGroup/$name", DeclaredKind.FIELD)
}

private fun TrackedFactBase.element(path: String?): TrackedDeclaration? =
    path?.let { resolved -> declarationAt(resolved) }

/** What the base-type comparison says about one key, or that this reader may not say. */
private enum class KeyTypeVerdict { CONSISTENT, INCONSISTENT, UNSETTLED }

private fun TrackedFactBase.keyTypeVerdict(indexField: TrackedDeclaration, key: TrackedDeclaration): KeyTypeVerdict {
    val expected = resolvedFieldTypeOf(indexField)?.type?.baseTypeClass ?: return KeyTypeVerdict.UNSETTLED
    val actual = resolvedFieldTypeOf(key)?.type?.baseTypeClass ?: return KeyTypeVerdict.UNSETTLED
    return if (expected == actual) KeyTypeVerdict.CONSISTENT else KeyTypeVerdict.INCONSISTENT
}

/** What the KEY half settles on its own: the wildcard class, nothing, or that this index is not ours. */
private enum class KeyVerdict { STARRED, PLAIN, UNJUDGEABLE }

private fun TrackedFactBase.keyVerdict(
    use: SemanticIndexUse,
    declaringGroup: String,
    element: TrackedDeclaration,
    anchoring: KeyAnchoring,
): KeyVerdict {
    val key = use.keyPath ?: return if (use.keyLiteral == null) KeyVerdict.UNJUDGEABLE else KeyVerdict.PLAIN
    // A GROUP key is the key-kind family's class and outranks every gate here; a key that does not resolve
    // is the reference family's.
    resolveField(key, declaringGroup, element) ?: return KeyVerdict.UNJUDGEABLE
    if (key.contains('*')) {
        return KeyVerdict.STARRED
    }
    // An unstarred key reaching into a repetition nothing binds makes the read iterate, and A12 renames every
    // class of this chain to `MVK_SEMANTIC_INDEX_INVALID_ITER` when it does. A key inside a bound repetition
    // reads that row and iterates nothing, which is the only way the containment class is reachable at all.
    val resolved = resolveEntity(declaringGroup, key, element.origin) as? Reference.Resolved
        ?: return KeyVerdict.UNJUDGEABLE
    if (!crossesARepetitionOutside(declaringGroup, resolved.path)) return KeyVerdict.PLAIN
    return when (anchoring) {
        KeyAnchoring.FILTER_BODY -> KeyVerdict.PLAIN
        KeyAnchoring.ERROR_TARGET -> {
            val targetGroup = iterationScopeOf(element.declaredReference(), declaringGroup) ?: return KeyVerdict.UNJUDGEABLE
            if (readsOneRow(resolved.path, targetGroup)) KeyVerdict.PLAIN else KeyVerdict.UNJUDGEABLE
        }
    }
}

/**
 * The path [written] resolves to, a field or a group, or null where it resolves to neither.
 *
 * A group value is an index's value as much as a field is: A12 finds its index group by walking from the value
 * entity itself, whichever kind it is (`CheckEntityUtils.getIndexGroup`), and a keyed group reads as the row
 * its key selects ([KERNEL-FINDINGS KF475]).
 */
private fun TrackedFactBase.resolveElement(
    written: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): String? {
    val resolved = resolveEntity(declaringGroup, written.replace("*", ""), element.origin)
        as? Reference.Resolved ?: return null
    return resolved.path.takeIf { path -> declarationAt(path) != null }
}

/** The FIELD [written] resolves to, or null — a key is one ([KERNEL-FINDINGS KF476]), never a group. */
private fun TrackedFactBase.resolveField(
    written: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): String? = resolveElement(written, declaringGroup, element)?.takeIf { declarationAt(it, DeclaredKind.FIELD) != null }

/** The innermost indexed group at or above [path] — the one A12 indexes on. */
internal fun TrackedFactBase.indexGroupOf(path: String): String? =
    indexedGroupsAbove(path).maxByOrNull { it.length }

/** Every group that declares an index field on A12's walk from [path] up: the element itself, then its ancestors. */
private fun TrackedFactBase.indexedGroupsAbove(path: String): List<String> = allDeclarations()
    .filter {
        it.declaredKind == DeclaredKind.GROUP && (it.path == path || path.startsWith("${it.path}/")) &&
            indexFieldNameOf(it) != null
    }
    .map { it.path }

/** The resolved paths of the levels [written] stars, so a star's POSITION can be compared against a group. */
private fun TrackedFactBase.starredPrefixesOf(
    written: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): Set<String> {
    val segments = written.split('/')
    return segments.indices
        .filter { segments[it].endsWith("*") }
        .mapNotNull { last ->
            val prefix = segments.take(last + 1).joinToString("/").replace("*", "")
            (resolveEntity(declaringGroup, prefix, element.origin) as? Reference.Resolved)?.path
        }
        .toSet()
}

private fun structureFinding(code: String, element: TrackedDeclaration, member: String, span: TextSpan) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    "in `$member`: this semantic index ${ADVICE.getValue(code)}",
    element.path,
    span,
)

/** What is wrong, per class — the same index shape reaches eleven different repairs. */
private val ADVICE = mapOf(
    "MVK_SEMANTIC_INDEX_WITH_WILDCARD" to
        "keys on a starred reference; the key names ONE value to match, so it may not stand for a list",
    "MVK_SEMANTIC_INDEX_WITH_SEMANTIC_INDEX" to
        "indexes a reference that is itself indexed; one index selects one repetition, and they do not chain",
    "MVK_NO_INDEX_FIELD" to
        "reads a group that declares no index field, so there is nothing for the key to be compared against",
    "MVK_SEMANTIC_INDEX_BELOW_WILDCARD" to
        "stars a level ABOVE the indexed group, which leaves the index selecting inside a list of lists",
    "MVK_INDEX_VALUE_INVALID" to
        "carries a constant key its index field refuses — an empty one matches no repetition, and an " +
            "enumeration index admits exactly its value tokens, in their case and never a label",
    "MVK_MULTIPLE_INDEX_FIELDS" to
        "reads a reference with a second indexed group on its path, and one key cannot say which of the two " +
            "it selects — drop the index field from one of them, or read the field from a single indexed level",
    "MVK_INDEX_TYPE_INCONSISTENT" to
        "keys on a field whose declared type the index field's cannot be compared against; the two are " +
            "matched by value, so they have to share a base type",
    "MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX" to
        "reads its key from inside the very group it indexes, so the key would have to be known before the " +
            "repetition it selects is",
    "MVK_INVALID_RAW_TYPE" to
        "keys on a field that waives value validation, whose content A12 cannot compare against the index " +
            "field's",
    "MVK_SEMANTIC_INDEX_WITH_CATEGORY" to
        "keys on a category projection; the key is compared against the index field's own value",
    "MVK_SEMANTIC_INDEX_AND_WILDCARD" to
        "stars the very group it indexes — the index already selects one repetition of it",
)
