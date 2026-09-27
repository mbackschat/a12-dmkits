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
 * | carries a lone surrogate, which UTF-16 cannot encode | `WRONG_ENCODING` |
 * | carries a line break | `INVALID_LINEBREAK` |
 * | carries a `\p{C}` code point | `INVALID_CHARACTERS` |
 * | carries an odd number of `$` | `WRONG_NUMBER_OF_DOLLARS` |
 * | puts anything but `field` or `field.value` between two `$` | `INVALID_PARAMETER` |
 *
 * The middle two are the rule message's own predicates ([characterRefusal]); the encoding question ahead of
 * them is this check's alone, so a lone surrogate is `WRONG_ENCODING` here and `INVALID_CHARACTERS` on a rule.
 *
 * **The parameter names follow the CONDITION language, not the message's locale**: a German condition language
 * takes `Feld` and `Feld.Wert` in every locale's text and refuses `field`. And the parts between two `$` are
 * Java's `split`, which drops trailing empty parts — so an empty pair at the very END of a text names no
 * parameter at all, while the same pair anywhere else names the empty one and is refused. A reader pairing the
 * dollars itself refuses a text A12 admits.
 *
 * **One finding per FIELD, from the first DECLARED locale whose text fails.** The locales are asked in the
 * order `header.locales` lists them, whatever order the entries are written in, and the first failure ends the
 * field; so a field defective in two locales draws one class, and which one depends on the declared order. The
 * text asked for a locale is its LAST entry, and a text for a locale the model does not declare is never asked.
 *
 * **A field A12's element walk never reaches is never judged** ([reachedByTheElementWalk]). A12 checks each
 * element's path before its content and returns there, skipping what lies beneath, so a field whose own name or
 * an enclosing group's name its path alphabet refuses draws that path's class and nothing about its message.
 * Nor is a field an `ONLY` variant filter deletes: code generation checks the default-variant container, from
 * which the filter removed it ([deletesAtDefaultVariant]; KERNEL-FINDINGS KF490).
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
    return allDefaultVariantDeclarations()
        .filter { it.declaredKind == DeclaredKind.FIELD && !it.origin.isMounted }
        .filter { field -> field.path !in unreached && field.path.enclosingPaths().none { it in unreached } }
        .mapNotNull { field -> ownMessageFinding(field, language) }
}

/** The one carrier a field type owns, the type body it sits in, and what to call it in a finding. */
private enum class FieldMessageCarrier(val describe: String) {
    PATTERN_MESSAGE("this String type's pattern message"),
    INVALID_VALUE_MESSAGE("this Enumeration's invalid-value message"),
}

private fun TrackedFactBase.ownMessageFinding(field: TrackedDeclaration, language: ConditionLanguage): Finding? {
    val string = field.stringTypeBody()
    val (carrier, type) = when {
        string != null -> FieldMessageCarrier.PATTERN_MESSAGE to string
        else -> FieldMessageCarrier.INVALID_VALUE_MESSAGE to (field.enumerationBody() ?: return null)
    }
    // A source A12's reader refuses is never examined here. STRING_PATTERN_MESSAGE reports that refusal for a String
    // type; for an Enumeration nothing does, and it is left to A12.
    val read = type.authoredMessages() as? AuthoredMessages.Read ?: return null
    val texts = read.entries.collectedTexts(declaredLocales)
    return declaredLocales.firstNotNullOfOrNull { locale ->
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
    javaCodePoints().firstOrNull { it in SURROGATES }?.let { lone ->
        return "WRONG_ENCODING" to "carries the lone surrogate U+${lone.hex()}, which UTF-16 cannot encode — " +
            "A12 asks the encoding before any other question about a field's message"
    }
    characterRefusal()?.let { refusal ->
        return refusal.code to "carries " + (if (refusal.isLineBreak) "the line break" else "the control or format character") +
            " U+${refusal.codePoint.hex()}, which A12 refuses — a field's message is one line of printable text"
    }
    if (count { it == '$' } % 2 != 0) {
        return "WRONG_NUMBER_OF_DOLLARS" to "carries an odd number of `$`, so a parameter is opened and never closed"
    }
    val allowed = language.fieldMessageParameters()
    // Java's split drops TRAILING empty parts, which is why an empty pair at the very end names no parameter.
    val parameter = split('$').dropLastWhile { it.isEmpty() }
        .filterIndexed { index, _ -> index % 2 == 1 }
        .firstOrNull { it !in allowed } ?: return null
    return "INVALID_PARAMETER" to "writes `$$parameter$`, and a field's own message takes only " +
        allowed.joinToString(" and ") { "`$$it$`" } + ", which stand for the field being validated — the " +
        "names follow the model's condition language, not the message's locale"
}

/** The two parameters a field's own message may carry, spelled in the model's condition language. */
private fun ConditionLanguage.fieldMessageParameters(): List<String> = when (this) {
    ConditionLanguage.EN -> listOf("field", "field.value")
    ConditionLanguage.DE -> listOf("Feld", "Feld.Wert")
}

private val SURROGATES = 0xD800..0xDFFF

/**
 * Whether A12's per-element walk reaches this declaration on its own account.
 *
 * A12 checks each GROUP and FIELD's path before its content and returns there, beneath it included
 * (`NoMetaModelChecks.checkEntity`, source-read in kernel `30.8.1`; every arm below measured by
 * `rulekit.validate.laws.FieldTypeMessageTextLawsTest.anInvalidPathStopsTheCheck`). The path alphabet is its
 * own, and it is neither ASCII nor the name families' laws:
 *
 * - the ROOT group's segment is letters, digits, `_` and the German umlauts and `ß`, a leading digit included;
 * - a group segment below it is the same set, beginning with a letter or `_`;
 * - a field's segment is ASCII letters, digits, `_` and `:` in pieces joined by `.`, each piece after a dot
 *   beginning with a letter — and not beginning with a digit, which A12 waives only for an H-model this checker
 *   does not recognise, so there it withholds and under-reports.
 *
 * A name carrying `/` stops the walk too: its path then disagrees with the tree ([ElementNameAlphabet]'s
 * `MVK_PATHS_INCONSISTENT`). Only a group's or a field's reach is asked here; other kinds are always reached.
 */
internal fun TrackedDeclaration.reachedByTheElementWalk(): Boolean {
    val kind = declaredKind
    if (kind != DeclaredKind.GROUP && kind != DeclaredKind.FIELD) {
        return true
    }
    if ('/' in name) {
        return false
    }
    val segments = path.split('/').drop(1)
    return segments.withIndex().all { (index, segment) ->
        when {
            index == 0 -> segment.isNotEmpty() && segment.all { it.isGroupPathCharacter() }
            index == segments.lastIndex && kind == DeclaredKind.FIELD -> segment.isFieldPathSegment()
            else -> segment.firstOrNull()?.let { it.isAsciiLetter() || it == '_' } == true &&
                segment.all { it.isGroupPathCharacter() }
        }
    }
}

private fun String.isFieldPathSegment(): Boolean {
    if (isEmpty() || first().isAsciiDigit()) {
        return false
    }
    return split('.').withIndex().all { (index, piece) ->
        piece.isNotEmpty() && (index == 0 || piece.first().isAsciiLetter()) &&
            piece.all { it.isAsciiLetter() || it.isAsciiDigit() || it == '_' || it == ':' }
    }
}

private fun Char.isGroupPathCharacter(): Boolean =
    isAsciiLetter() || isAsciiDigit() || this == '_' || this in "äöüÄÖÜß"

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/** Every group path enclosing this one, outermost first: `/A/B/C` gives `/A` and `/A/B`. */
private fun String.enclosingPaths(): List<String> =
    indices.filter { it > 0 && this[it] == '/' }.map { substring(0, it) }
