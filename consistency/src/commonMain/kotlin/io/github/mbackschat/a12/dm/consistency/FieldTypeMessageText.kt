package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage

/**
 * That a field type's OWN error message is text A12's field-message check admits.
 *
 * A String type's pattern message and an Enumeration's invalid-value message are judged by a check of their
 * own, not the rule message's ([KERNEL-FINDINGS KF490], `rulekit.validate.laws.FieldTypeMessageTextLawsTest`).
 * It asks five questions in order and answers only the first that fails:
 *
 * | the text | A12 |
 * |---|---|
 * | carries a line break | `INVALID_LINEBREAK` |
 * | carries a lone surrogate, which UTF-16 cannot encode | `WRONG_ENCODING` |
 * | carries a `\p{C}` code point | `INVALID_CHARACTERS` |
 * | carries an odd number of `$` | `WRONG_NUMBER_OF_DOLLARS` |
 * | puts anything but `field` or `field.value` between two `$` | `INVALID_PARAMETER` |
 *
 * The first three are the rule message's own gates ([characterRefusal]): kernel 31.1.1 asks both carriers through
 * one helper, where 30.8.1's field check asked the encoding before the line break ([KERNEL-FINDINGS KF657]).
 *
 * **The parameter names follow the CONDITION language, not the message's locale**: a German condition language
 * takes `Feld` and `Feld.Wert` in every locale's text and refuses `field`. An empty pair `$$` names no parameter
 * wherever it stands: the helper skips an empty part between two `$` (`StringUtils.isEmpty`), which 30.8.1's field
 * check did not ([KF657]).
 *
 * **One finding per declared locale whose text fails, in DECLARED order.** The locales are asked in the order
 * `header.locales` lists them, whatever order the entries are written in, and each is judged on its own, so a field
 * defective in two locales draws both classes (`NoMetaModelChecks.checkFieldErrorText`, which 30.8.1 left at the
 * first failure, [KF657]). The text asked for a locale is its LAST entry, and a text for a locale the model does
 * not declare is never asked.
 *
 * **A field A12's element walk never reaches is never judged** ([reachedByTheElementWalk]). A12 checks each
 * element's path before its content and returns there, skipping what lies beneath, so a field whose own name or
 * an enclosing group's name its path alphabet refuses draws that path's class and nothing about its message.
 *
 * **What it is not.** A required field's message is not reached at all. The pattern-and-message pairing is
 * [stringTypeMessageFindings]', and both are reported on one field: a message with no pattern beside it is
 * judged here all the same. The codeless line-break sentence A12 adds beside `INVALID_LINEBREAK` is not
 * reported, as on a rule's message, since it names no class. Left to A12, all of it under-reporting: a field
 * type declared in `content.typeDefinitions`, and a mounted provider's fields, whose reach was not measured.
 */
internal fun TrackedFactBase.fieldTypeMessageTextFindings(): List<Finding> {
    val unreached = declarations().filterNot { it.reachedByTheElementWalk() }.map { it.path }.toSet()
    val language = conditionLanguage
    return allDeclarations()
        .filter { it.declaredKind == DeclaredKind.FIELD && !it.origin.isMounted }
        .filter { field -> field.path !in unreached && field.path.enclosingPaths().none { it in unreached } }
        .flatMap { field -> ownMessageFindings(field, language) }
}

/** The one carrier a field type owns, the type body it sits in, and what to call it in a finding. */
private enum class FieldMessageCarrier(val describe: String) {
    PATTERN_MESSAGE("this String type's pattern message"),
    INVALID_VALUE_MESSAGE("this Enumeration's invalid-value message"),
}

private fun TrackedFactBase.ownMessageFindings(field: TrackedDeclaration, language: ConditionLanguage): List<Finding> {
    val string = field.stringTypeBody()
    val (carrier, type) = when {
        string != null -> FieldMessageCarrier.PATTERN_MESSAGE to string
        else -> FieldMessageCarrier.INVALID_VALUE_MESSAGE to (field.ownEnumerationBody() ?: return emptyList())
    }
    // A source A12's reader refuses is never examined here. STRING_PATTERN_MESSAGE reports that refusal for a String
    // type; for an Enumeration nothing does, and it is left to A12.
    val read = type.authoredMessages() as? AuthoredMessages.Read ?: return emptyList()
    val texts = read.entries.collectedTexts(declaredLocales)
    return declaredLocales.mapNotNull { locale ->
        texts[locale]?.fieldMessageRefusal(language)?.let { (code, explain) ->
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                code,
                "${carrier.describe} for `$locale` $explain",
                field.path,
            )
        }
    }
}

/** The class A12's field-message check gives this text and why, or null when every question passes. */
private fun String.fieldMessageRefusal(language: ConditionLanguage): Pair<String, String>? {
    if (isEmpty()) {
        return null
    }
    characterRefusal()?.let { refusal ->
        return refusal.code to "carries " + refusal.describe() + ", which A12 refuses — a field's message is one line " +
            "of printable text"
    }
    if (count { it == '$' } % 2 != 0) {
        return "WRONG_NUMBER_OF_DOLLARS" to "carries an odd number of `$`, so a parameter is opened and never closed"
    }
    val allowed = language.fieldMessageParameters()
    val parameter = parameterParts().firstOrNull { it !in allowed } ?: return null
    return "INVALID_PARAMETER" to "writes `$$parameter$`, and a field's own message takes only " +
        allowed.joinToString(" and ") { "`$$it$`" } + ", which stand for the field being validated — the " +
        "names follow the model's condition language, not the message's locale"
}

/** The two parameters a field's own message may carry, spelled in the model's condition language. */
private fun ConditionLanguage.fieldMessageParameters(): List<String> = when (this) {
    ConditionLanguage.EN -> listOf("field", "field.value")
    ConditionLanguage.DE -> listOf("Feld", "Feld.Wert")
}

/** Every group path enclosing this one, outermost first: `/A/B/C` gives `/A` and `/A/B`. */
private fun String.enclosingPaths(): List<String> =
    indices.filter { it > 0 && this[it] == '/' }.map { substring(0, it) }
