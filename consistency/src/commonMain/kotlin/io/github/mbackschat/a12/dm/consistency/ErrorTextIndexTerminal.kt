package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.OPERATOR_SPELLINGS
import io.github.mbackschat.a12.dm.consistency.syntax.MessageParameter
import io.github.mbackschat.a12.dm.consistency.syntax.MessageSuffix
import io.github.mbackschat.a12.dm.consistency.syntax.MessageCompletion
import io.github.mbackschat.a12.dm.consistency.syntax.OperatorId
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexUsesIn

/**
 * The argument A12's `$index(…)$` message terminal takes: an index field on the ERROR ENTITY's own path.
 *
 * The terminal pins a message to the firing ROW's declared index-field value, so its argument has to name an
 * index field the error entity is actually reached through. `checkIndexFieldRef` collects
 * `AnalyseService.getIndexFields(errorEntity, ec)`, filters them by SHORT NAME, and answers
 * `INVALID_INDEX_FIELD` when nothing matches
 * ([KERNEL-FINDINGS KF198], `rulekit.validate.laws.RuleMessageIndexTerminalLawsTest`).
 *
 * **Five gates run in this terminal, in one order, and this family walks all five.** The argument gate is
 * first, so nothing pre-empts it. Ambiguity is second — `INVALID_INDEX_FIELD_AMBIGUOUS` when the short name
 * matches the index field of more than one group on the path, measured on a `/Policy` shape whose `Claims`
 * and `Items` both declare `Ref`. Third is a COMPOSED index, which no A12 document model can express
 * ([KF280] attacked every link), so nothing here can reach it. Fourth is repeatability, and fifth the
 * missing suffix — and the order between those two is measured rather than read: a starred reference with no
 * suffix draws the repeatability class, so the last gate is only safe to report from behind a reproduced
 * fourth one (`rulekit.validate.laws.MessageIndexTerminalRepeatabilityLawsTest`).
 *
 * **The fourth gate splits by the indexed group's own repeatability, and the two arms read DIFFERENT things.**
 * A non-repeatable group's arm reads the DECLARATION the parameter named — an Enumeration with exactly one
 * value and a non-empty `sValue` is admitted and anything else is `INVALID_INDEX_FIELD_NONREP_ENUM`, whether
 * or not the condition references it at all, which is the row that separates this arm from the other. A
 * repeatable group's arm reads the CONDITION: a reference carrying a repetition wildcard or a semantic-index
 * key is `INVALID_INDEX_FIELD_REP_ITER`, while a plain one iterates and is admitted.
 *
 * **A short name that more than one GROUP in the model declares as its index field is left alone**, because
 * A12 then walks a list this family does not reproduce and can answer `INVALID_INDEX_FIELD_REP` from it —
 * measured on a mixed shape where the error entity's own index field is admitted and a repeatable group's
 * namesake is not. Naming either arm's class there would be the wrong class rather than an under-report, so
 * the uniqueness question is asked before both arms.
 *
 * **The terminal's four CROSS-FIELD classes are behind that same question, which is why they are not a
 * worklist item.** `checkIndexFieldRefValue` and `checkIndexFieldRefCategory` compare each member of A12's
 * list against the FIRST — the XML values, the display locales, the category mapping, the types — so every
 * one of `INVALID_INDEX_FIELD_ENUM_XML_VALUES`, `_ENUM_XML_TO_CAT`, `_ENUM_XML_TO_DISPLAY` and
 * `_INCONSISTENT_TYPES` needs a list of at least two, which is the shape declined above (source-read
 * 30.8.1). Their two per-member siblings, `FIELD_NO_ENUM` and `INVALID_CATEGORY`, fire on a single member
 * are measured on plain fields. That is not a scope-transfer oracle for this terminal; its category arm
 * remains deliberately declined.
 */
internal fun TrackedFactBase.completedIndexDefect(terminal: MessageParameter.Index, element: TrackedDeclaration): MessageCompletion {
    val errorTarget = (element.declaredReference()?.takeIf { it.role == ReferenceRole.ERROR_TARGET }?.target
        as? Reference.Resolved)?.path ?: return MessageCompletion.Declined
    val matched = indexFieldsOnThePathTo(errorTarget).filter { it.name == terminal.entity.written.replace("'", "") }
    when (matched.size) {
        0 -> return MessageCompletion.Refused("INVALID_INDEX_FIELD")
        1 -> Unit
        else -> return MessageCompletion.Refused("INVALID_INDEX_FIELD_AMBIGUOUS")
    }
    val field = matched.single()
    if (groupsDeclaringIndexFieldNamed(field.name) > 1) return MessageCompletion.Declined
    repeatabilityDefect(field, element)?.let { return MessageCompletion.Refused(it) }
    return when (terminal.suffix) {
        MessageSuffix.NONE -> MessageCompletion.Refused(MISSING_SUFFIX)
        MessageSuffix.VALUE -> MessageCompletion.Accepted
        // The ordinary field category gate is not evidence for this terminal's cross-field visitor (KF275).
        else -> MessageCompletion.Declined
    }
}

/** The fourth gate, whose two arms read different things — the declaration, or the condition. */
private fun TrackedFactBase.repeatabilityDefect(matched: IndexFieldOnPath, element: TrackedDeclaration): String? =
    if (matched.repeatable) {
        if (conditionRidesItWithoutIterating(matched.path, element)) REP_ITER else null
    } else {
        if (declaresASingleValuedEnumeration(matched.path)) null else NONREP_ENUM
    }

/**
 * Whether the declaration is the ONE shape A12 admits as a non-repeatable group's index field.
 *
 * An Enumeration carrying exactly one value and a non-empty `sValue` — `checkNonRepIndexFieldType` reads both
 * halves, and each was moved on its own: a second value refuses, and so does dropping the special value.
 */
private fun TrackedFactBase.declaresASingleValuedEnumeration(fieldPath: String): Boolean {
    val body = declarationAt(fieldPath)?.let { enumerationBodyOf(it) } ?: return false
    return body.declaredEnumValues()?.size == 1 && !body.string("sValue").isNullOrEmpty()
}

/** Authored ancestor coordinates and expanded descendants retain separate iteration modes (SPEC11-01). */
private fun TrackedFactBase.conditionRidesItWithoutIterating(
    fieldPath: String,
    element: TrackedDeclaration,
): Boolean {
    val body = element.body ?: return false
    val declaring = element.path.substringBeforeLast('/')
    val owner = fieldPath.substringBeforeLast('/')
    fun resolve(written: String): String? =
        resolvedPathOf(written, declaring, element)
    if (messageConditionReferences(element).any { written ->
        val path = resolve(written) ?: return@any false
        declarationAt(path)?.declaredKind == DeclaredKind.GROUP && owner.startsWith("$path/")
    }) return true
    val keyword = OPERATOR_SPELLINGS[conditionLanguage]?.get(OperatorId.SEMANTIC_INDEX)
    return conditionTexts(element.kind, body).any { (_, text) ->
        val starred = referencesIn(text, conditionLanguage).any { written ->
            val path = resolve(written) ?: return@any false
            if (path != owner && !path.startsWith("$owner/")) return@any false
            val segments = written.split('/')
            segments.indices.any { at -> '*' in segments[at] &&
                resolve(segments.take(at + 1).joinToString("/")) == owner }
        }
        val keyed = keyword != null && semanticIndexUsesIn(text, conditionLanguage, keyword).any { use ->
            use.valuePath?.let(::resolve)?.let(::indexGroupOf) == owner
        }
        starred || keyed
    }
}

/** How many GROUPS declare an index field of this short name — A12's list is not scoped to the path. */
private fun TrackedFactBase.groupsDeclaringIndexFieldNamed(name: String): Int =
    allDeclarations().count { it.declaredKind == DeclaredKind.GROUP && indexFieldNameOf(it) == name }

/** One index field the error entity is reached through, with what the repeatability gate asks of its group. */
private class IndexFieldOnPath(val name: String, val path: String, val repeatable: Boolean)

/**
 * Every index field declared by a group on the path to [fieldPath].
 *
 * Every ancestor group counts, repeatable or not: a non-repeatable indexed group's field is on the path and
 * passes the argument gate, which is why the class it can draw belongs to the repeatability gate instead.
 */
private fun TrackedFactBase.indexFieldsOnThePathTo(fieldPath: String): List<IndexFieldOnPath> {
    val found = mutableListOf<IndexFieldOnPath>()
    var group = fieldPath.substringBeforeLast('/', missingDelimiterValue = "")
    while (group.isNotEmpty()) {
        val declared = declarationAt(group)
        val name = declared?.let { indexFieldNameOf(it) }
        if (declared != null && name != null) {
            found += IndexFieldOnPath(name, "$group/$name", declared.isRepeatable)
        }
        group = group.substringBeforeLast('/', missingDelimiterValue = "")
    }
    return found
}

private const val REP_ITER = "INVALID_INDEX_FIELD_REP_ITER"

private const val NONREP_ENUM = "INVALID_INDEX_FIELD_NONREP_ENUM"

private const val MISSING_SUFFIX = "INVALID_INDEX_FIELD_PARAMETER"


/** The corrective uses the same error-target path as the argument gate (KF275). */
internal fun TrackedFactBase.availableMessageIndexNames(element: TrackedDeclaration): List<String> {
    val target = (element.declaredReference()?.takeIf { it.role == ReferenceRole.ERROR_TARGET }?.target
        as? Reference.Resolved)?.path ?: return emptyList()
    return indexFieldsOnThePathTo(target).map { it.name }.distinct().sorted()
}
