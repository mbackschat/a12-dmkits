package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.SEMANTIC_INDEX
import io.github.mbackschat.a12.dm.consistency.syntax.messageParameterBodies
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
 * and are already reported — by the category-suffix family, from the plain form.
 */
internal fun TrackedFactBase.errorTextIndexTerminalFindings(): List<Finding> {
    val targets = declaredReferences()
        .filter { it.role == ReferenceRole.ERROR_TARGET }
        .associate { it.declaredAt to (it.target as? Reference.Resolved)?.path }
    return declarations().flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val errorTarget = targets[element.path] ?: return@flatMap emptyList()
        val reachable = indexFieldsOnThePathTo(errorTarget)
        val messages = body.authoredMessages() as? AuthoredMessages.Read ?: return@flatMap emptyList()
        messages.entries.flatMap { message ->
            if (message.text.carriesAnOffendingCharacter()) {
                return@flatMap emptyList()
            }
            messageParameterBodies(message.text).mapNotNull { parameter ->
                val terminal = indexTerminalIn(parameter) ?: return@mapNotNull null
                val matched = reachable.filter { it.name == terminal.argument }
                when (matched.size) {
                    0 -> finding(terminal.argument, reachable.map { it.name }.toSet(), element)
                    1 -> gatesBehindTheArgument(matched.single(), terminal, element)
                    // The SECOND gate, and the argument gate is the only one before it — so reporting this
                    // class needs no more of the terminal's machinery than the first one did.
                    else -> ambiguousFinding(terminal.argument, element)
                }
            }
        }
    }
}

/**
 * The gates the argument's own match does not answer: repeatability, and then the missing suffix.
 *
 * The uniqueness question comes first because it decides whether A12 is walking a list of one — every other
 * member of that list can answer `INVALID_INDEX_FIELD_REP`, which neither arm below would name.
 */
private fun TrackedFactBase.gatesBehindTheArgument(
    matched: IndexFieldOnPath,
    terminal: IndexTerminal,
    element: TrackedDeclaration,
): Finding? {
    if (groupsDeclaringIndexFieldNamed(matched.name) > 1) {
        return null
    }
    repeatabilityDefect(matched, element)?.let { return finding(it, matched.name, element) }
    return if (terminal.suffix.isEmpty()) finding(MISSING_SUFFIX, matched.name, element) else null
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
    val body = declarationAt(fieldPath)?.enumerationBody() ?: return false
    return body.declaredEnumValues()?.size == 1 && !body.string("sValue").isNullOrEmpty()
}

/**
 * Whether the declaration's own condition reaches the index field by a form that is not an iteration.
 *
 * A repetition wildcard and a semantic-index key are the two measured ones; every other form this reader
 * cannot tell apart from a plain read is left to iterate, which is the direction that under-reports.
 *
 * A star ANYWHERE in the reference counts here, while A12 reads only the index field's own level
 * (`checkIndexFieldRefRepeatability4repeatableGroup`, source-read 30.8.1). The two differ only where the star
 * sits on another level or on the field, and each such reference is refused during the parse, so the report
 * withholds this verdict behind it ([KERNEL-FINDINGS KF338]).
 */
private fun TrackedFactBase.conditionRidesItWithoutIterating(
    fieldPath: String,
    element: TrackedDeclaration,
): Boolean {
    val body = element.body ?: return false
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val keyword = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(SEMANTIC_INDEX)
    fun resolves(written: String?): Boolean = written != null &&
        (resolveEntity(group, written.replace("*", ""), element.origin) as? Reference.Resolved)?.path == fieldPath
    return conditionTexts(element.kind, body).any { (_, text) ->
        val starred = referencesIn(text, conditionLanguage).any { written -> '*' in written && resolves(written) }
        val keyed = keyword != null &&
            semanticIndexUsesIn(text, conditionLanguage, keyword).any { use -> resolves(use.valuePath) }
        starred || keyed
    }
}

/** How many GROUPS declare an index field of this short name — A12's list is not scoped to the path. */
private fun TrackedFactBase.groupsDeclaringIndexFieldNamed(name: String): Int =
    allDeclarations().count { it.declaredKind == DeclaredKind.GROUP && indexFieldNameOf(it) == name }

private const val INDEX_TERMINAL = "index("

/** The terminal's two halves: what it addresses, and what it does with the value it finds. */
private class IndexTerminal(val argument: String, val suffix: String)

/**
 * The terminal this parameter spells, or null when it is not this terminal.
 *
 * The terminal is a LEXER token spelled exactly lowercase — `$Index(…)$` draws `MVK_UNEXPECTED_TOKEN` rather
 * than reaching any of these gates — so matching it literally is the whole recognition step. The SUFFIX is
 * kept unparsed on purpose: only its emptiness is a law here, and `.value`, `->Band` and every misspelling
 * belong to gates other families own.
 */
private fun indexTerminalIn(parameter: String): IndexTerminal? {
    val trimmed = parameter.trim()
    if (!trimmed.startsWith(INDEX_TERMINAL)) {
        return null
    }
    val close = trimmed.indexOf(')', INDEX_TERMINAL.length)
    if (close < 0) {
        return null
    }
    val argument = trimmed.substring(INDEX_TERMINAL.length, close).trim().takeIf { it.isNotEmpty() }
        ?: return null
    return IndexTerminal(argument, trimmed.substring(close + 1).trim())
}

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

private fun finding(code: String, argument: String, element: TrackedDeclaration): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    "in `errorMessage`: `index($argument)` " + when (code) {
        REP_ITER -> "names an index field this declaration's condition reaches through a repetition " +
            "wildcard or a semantic-index key — the terminal pins the message to the FIRING row, so the " +
            "condition has to reach that field by iterating it"
        NONREP_ENUM -> "names the index field of a group that does not repeat, and A12 admits one there " +
            "only as an Enumeration declaring a single value and an `sValue` — this one declares neither"
        else -> "carries no `.value` or `->Category` suffix, so the terminal names a row and then says " +
            "nothing about it; A12's grammar admits the bare form only to refuse it here"
    },
    element.path,
)

private fun ambiguousFinding(argument: String, element: TrackedDeclaration): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "INVALID_INDEX_FIELD_AMBIGUOUS",
    "in `errorMessage`: `index($argument)` matches the index field of more than one group on the error " +
        "entity's path — the argument is matched by SHORT NAME, so A12 cannot say which repetition to pin " +
        "the message to",
    element.path,
)

private fun finding(argument: String, reachable: Set<String>, element: TrackedDeclaration): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "INVALID_INDEX_FIELD",
    "in `errorMessage`: `index($argument)` names no index field on the error entity's own path — " +
        if (reachable.isEmpty()) {
            "no group on that path declares one, so the terminal has nothing to pin the message to"
        } else {
            "that path offers ${reachable.sorted().joinToString { "`$it`" }}, and the argument is matched " +
                "by SHORT NAME rather than by path"
        },
    element.path,
)
