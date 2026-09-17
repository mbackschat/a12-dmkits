package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.SemanticIndexUse
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexUsesIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What a semantic index may CARRY, and where the indexed group has to be.
 *
 * `[Coverages/Limit For key]` addresses one repetition, and its own structure carries nine rejection classes
 * decided by one chain ([KERNEL-FINDINGS KF371], `rulekit.validate.laws.SemanticIndexStructureLawsTest`).
 * [semanticIndexKeyFindings] owns the first of them — the key must be a field — and this family owns the
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
 * whatever else is wrong with the index, and [semanticIndexKeyFindings] owns that class. What binds a level
 * is the rule's own anchoring, so a key inside a repetition is judged here whenever the rule is declared in
 * that repetition; treating every such key as opaque would lose `MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX`
 * entirely, since a key inside the indexed group is anchored by definition. Silent too on a GROUP key, which
 * is the same family's, and on a value spec or key that does not resolve.
 *
 * **Silent where the key's DECLARED type is one A12's base-type comparison leaves unsettled here.** The
 * comparison is [IndexKeyClass], not name equality, and a declaration outside that table — a type definition
 * or an unspecified field — leaves the whole index unjudged rather than guessing whether A12 calls it
 * consistent.
 *
 * **Silent on a NON-EMPTY literal key A12 refuses on applicability.** `isValueApplicable` runs the index
 * field's own value validation over the constant, so `[Endorsements/Amount For "not-a-grade"]` is
 * `MVK_INDEX_VALUE_INVALID` on an enumeration-keyed group; this family reads only the empty case, and a
 * later class it names on such an index would be the wrong class on a correct rejection.
 */
internal fun TrackedFactBase.semanticIndexStructureFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val keyword = keywords[SEMANTIC_INDEX] ?: return emptyList()
    return declarations().flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).mapNotNull { (member, text) ->
            firstClassIn(semanticIndexUsesIn(text, conditionLanguage, keyword), group, element)
                ?.let { code -> structureFinding(code, element, member) }
        }
    }
}

private const val SEMANTIC_INDEX = "SemanticIndex"

/**
 * The class the FIRST defective index in one text draws, or null when this family may not speak about it.
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
): String? {
    // A NESTED index is answered before the walk, because the outer index of `[A For B For C]` is the one
    // A12 judges and it stands LAST in the text — the walk would reach the inner one first and stop at its
    // key, which is read from inside a repetition and so looks opaque. Its own key gate still runs ahead of
    // the nesting check, which is the source's order and the one row where the two compete.
    uses.firstOrNull { it.nested }?.let { outer ->
        return when (keyVerdict(outer, declaringGroup, element)) {
            KeyVerdict.STARRED -> "MVK_SEMANTIC_INDEX_WITH_WILDCARD"
            KeyVerdict.UNJUDGEABLE -> null
            KeyVerdict.PLAIN -> "MVK_SEMANTIC_INDEX_WITH_SEMANTIC_INDEX"
        }
    }
    uses.forEach { use ->
        when (val verdict = classOf(use, declaringGroup, element)) {
            is IndexVerdict.Reported -> return verdict.code
            IndexVerdict.Opaque -> return null
            IndexVerdict.Clean -> Unit
        }
    }
    return null
}

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
): IndexVerdict {
    val value = use.valuePath ?: return IndexVerdict.Opaque
    val indexed = resolveField(value, declaringGroup, element) ?: return IndexVerdict.Opaque
    when (keyVerdict(use, declaringGroup, element)) {
        KeyVerdict.UNJUDGEABLE -> return IndexVerdict.Opaque
        KeyVerdict.STARRED -> return IndexVerdict.Reported("MVK_SEMANTIC_INDEX_WITH_WILDCARD")
        KeyVerdict.PLAIN -> Unit
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
 * value validation over the constant — and this reader decides that only where the index field constrains
 * nothing, leaving every other constant-keyed index unjudged rather than naming a class A12 reaches past.
 */
private fun TrackedFactBase.keyClassOf(
    use: SemanticIndexUse,
    indexGroup: String,
    indexField: TrackedDeclaration,
    declaringGroup: String,
    element: TrackedDeclaration,
): IndexVerdict? {
    use.keyLiteral?.let { literal ->
        return when {
            literal.isEmpty() -> IndexVerdict.Reported("MVK_INDEX_VALUE_INVALID")
            indexField.admitsEveryConstant() -> null
            else -> IndexVerdict.Opaque
        }
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
    return if (key.waivesValueValidation()) IndexVerdict.Reported("MVK_INVALID_RAW_TYPE") else null
}

/**
 * Whether this declaration's own value validation admits every non-empty constant.
 *
 * Only a string declaration can: `isValueApplicable` is a per-type member, and every other type reads the
 * constant as one of its own values. A string that declares no length and no pattern constrains nothing, and
 * a declaration carrying no type body at all declares none of the three.
 */
private fun TrackedDeclaration.admitsEveryConstant(): Boolean {
    if (declaredFieldType() != "StringType") {
        return false
    }
    val string = (body?.get("fieldType") as? JsonObject)?.get("StringType") as? JsonObject ?: return true
    return LENGTH_MEMBERS.none { member -> ((string[member] as? JsonPrimitive)?.intOrNull ?: 0) > 0 } &&
        (string["pattern"] as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()
}

private val LENGTH_MEMBERS = listOf("minLength", "maxLength")

/** The field a group declares as its index, or null where the declaration names none this reader can find. */
private fun TrackedFactBase.indexFieldOf(indexGroup: String): TrackedDeclaration? {
    val group = declarations().firstOrNull { it.path == indexGroup } ?: return null
    val name = indexFieldNameOf(group) ?: return null
    return declarations().firstOrNull { it.path == "$indexGroup/$name" && it.declaredKind == DeclaredKind.FIELD }
}

private fun TrackedFactBase.element(path: String?): TrackedDeclaration? =
    path?.let { resolved -> declarations().firstOrNull { it.path == resolved } }

/** What the base-type comparison says about one key, or that this reader may not say. */
private enum class KeyTypeVerdict { CONSISTENT, INCONSISTENT, UNSETTLED }

private fun keyTypeVerdict(indexField: TrackedDeclaration, key: TrackedDeclaration): KeyTypeVerdict {
    val expected = INDEX_KEY_CLASS_OF[indexField.declaredFieldType()] ?: return KeyTypeVerdict.UNSETTLED
    val actual = INDEX_KEY_CLASS_OF[key.declaredFieldType()] ?: return KeyTypeVerdict.UNSETTLED
    return if (expected == actual) KeyTypeVerdict.CONSISTENT else KeyTypeVerdict.INCONSISTENT
}

/**
 * The classes A12's `IFieldType.matchBaseType` sorts a declaration into, as THIS gate reads them.
 *
 * Deliberately not [BaseTypeClass], which splits the temporal declarations: A12's model layer declares one
 * date interface, so a time, date-time, date-fragment or date-range declaration is compared against a date
 * one by an `instanceof` that cannot tell them apart — and calling such a pair inconsistent would refuse a
 * key A12 admits. They share a class here, so the family stays silent inside it and speaks only across
 * classes, where every pair is measured. Text and custom share one for the reason [BaseTypeClass] records,
 * measured on this carrier from both sides: a string-keyed group admits a custom key, an enumeration-keyed
 * one does not.
 */
private enum class IndexKeyClass { TEXT, ENUMERATION, NUMBER, TEMPORAL, BOOLEAN, CONFIRM }

private val INDEX_KEY_CLASS_OF = mapOf(
    "StringType" to IndexKeyClass.TEXT,
    "CustomFieldType" to IndexKeyClass.TEXT,
    "EnumerationType" to IndexKeyClass.ENUMERATION,
    "NumberType" to IndexKeyClass.NUMBER,
    "DateType" to IndexKeyClass.TEMPORAL,
    "TimeType" to IndexKeyClass.TEMPORAL,
    "DateTimeType" to IndexKeyClass.TEMPORAL,
    "DateFragmentType" to IndexKeyClass.TEMPORAL,
    "DateRangeType" to IndexKeyClass.TEMPORAL,
    "BooleanType" to IndexKeyClass.BOOLEAN,
    "ConfirmType" to IndexKeyClass.CONFIRM,
)

/** What the KEY half settles on its own: the wildcard class, nothing, or that this index is not ours. */
private enum class KeyVerdict { STARRED, PLAIN, UNJUDGEABLE }

private fun TrackedFactBase.keyVerdict(
    use: SemanticIndexUse,
    declaringGroup: String,
    element: TrackedDeclaration,
): KeyVerdict {
    val key = use.keyPath ?: return if (use.keyLiteral == null) KeyVerdict.UNJUDGEABLE else KeyVerdict.PLAIN
    // A GROUP key is the key-kind family's class and outranks every gate here; a key that does not resolve
    // is the reference family's.
    resolveField(key, declaringGroup, element) ?: return KeyVerdict.UNJUDGEABLE
    if (key.contains('*')) {
        return KeyVerdict.STARRED
    }
    // An unstarred key reaching into a repetition the RULE does not sit in makes the read iterate, and A12
    // renames every class of this chain to `MVK_SEMANTIC_INDEX_INVALID_ITER` when it does. A key inside the
    // rule's own repetition reads that row and iterates nothing, which is the only way the containment class
    // is reachable at all.
    val resolved = resolveEntity(declaringGroup, key, element.origin) as? Reference.Resolved
        ?: return KeyVerdict.UNJUDGEABLE
    return if (crossesARepetitionOutside(declaringGroup, resolved.path)) {
        KeyVerdict.UNJUDGEABLE
    } else {
        KeyVerdict.PLAIN
    }
}

/** The resolved path of [written] when it names a declared FIELD, stars ignored; null otherwise. */
private fun TrackedFactBase.resolveField(
    written: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): String? {
    val resolved = resolveEntity(declaringGroup, written.replace("*", ""), element.origin)
        as? Reference.Resolved ?: return null
    return resolved.path.takeIf { path ->
        declarations().any { it.path == path && it.declaredKind == DeclaredKind.FIELD }
    }
}

/** The innermost group above [fieldPath] that declares an index field — the one A12 indexes on. */
private fun TrackedFactBase.indexGroupOf(fieldPath: String): String? = declarations()
    .filter {
        it.declaredKind == DeclaredKind.GROUP && fieldPath.startsWith("${it.path}/") &&
            indexFieldNameOf(it) != null
    }
    .maxByOrNull { it.path.length }
    ?.path

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

private fun structureFinding(code: String, element: TrackedDeclaration, member: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    "in `$member`: this semantic index ${ADVICE.getValue(code)}",
    element.path,
)

/** What is wrong, per class — the same index shape reaches nine different repairs. */
private val ADVICE = mapOf(
    "MVK_SEMANTIC_INDEX_WITH_WILDCARD" to
        "keys on a starred reference; the key names ONE value to match, so it may not stand for a list",
    "MVK_SEMANTIC_INDEX_WITH_SEMANTIC_INDEX" to
        "indexes a reference that is itself indexed; one index selects one repetition, and they do not chain",
    "MVK_NO_INDEX_FIELD" to
        "reads a group that declares no index field, so there is nothing for the key to be compared against",
    "MVK_SEMANTIC_INDEX_BELOW_WILDCARD" to
        "stars a level ABOVE the indexed group, which leaves the index selecting inside a list of lists",
    "MVK_INDEX_VALUE_INVALID" to "carries an empty key, which matches no repetition",
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
