package io.github.mbackschat.a12.dm.consistency


/**
 * A declared string type's `pattern` and its own `errorMessage`, which A12 requires of each other.
 *
 * The two members look like independent slots and are one law with two directions
 * (`rulekit.validate.laws.StringTypeMessageAndPatternLawsTest`, kernel 30.8.1, 2026-09-13): a pattern with
 * no message draws `MVK_ERROR_MESSAGE_AND_PATTERN_INVALID`, a message with no pattern draws
 * `MVK_ERROR_MESSAGE_AND_NO_PATTERN`. The code names are near-anagrams and neither says which direction it
 * carries, so the grid is the only statement of that.
 *
 * **"Carries a message" is not "declares the member", and both arms read it the same way** — measured, and
 * the reason this family needs no branch for the two. A message counts only when it gives A12 a non-blank
 * text for a locale `header.locales` DECLARES: an empty list, a blank text and a text for an undeclared
 * locale each leave the member present and the model message-less, so each refuses a pattern beside it and
 * each is ADMITTED without one.
 *
 * **The locale tier is a THIRD code, and it belongs to the message alone.** Once some declared locale is
 * answered and another is not, A12 reports `MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING` — the same code a
 * rule's own `errorMessage` draws — and it reports it whether or not a pattern is there, so a model with a
 * half-translated message and no pattern draws both codes at once. A consumer keying on the pattern codes
 * alone would read a bilingual gap as clean.
 *
 * Only the ENTRIES have a shape A12's reader refuses; a scalar at the member binds and collects nothing.
 * That boundary is [authoredMessages]' and is measured on this carrier too, which is what lets one reader
 * serve both.
 */
internal fun TrackedFactBase.stringTypeMessageFindings(field: TrackedDeclaration): List<Finding> {
    if (field.declaredKind != DeclaredKind.FIELD) {
        return emptyList()
    }
    val stringType = field.stringTypeBody() ?: return emptyList()
    return when (val read = stringType.authoredMessages()) {
        is AuthoredMessages.Refused -> listOf(
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                CK_MODEL_NOT_BUILDABLE,
                "${read.explain}, so A12's own reader refuses the source and nothing in this model is " +
                    "examined — this field's pattern message is not judged here",
                field.path,
            ),
        )
        is AuthoredMessages.Read -> if (field.body.deletesAtDefaultVariant()) emptyList() else patternMessageFindings(
            field,
            stringType.string("pattern") != null,
            read.entries.coveredLocales(declaredLocales),
        )
    }
}

private fun TrackedFactBase.patternMessageFindings(
    field: TrackedDeclaration,
    declaresPattern: Boolean,
    covered: Set<String>,
): List<Finding> {
    val pairing = when {
        declaresPattern && covered.isEmpty() -> Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_ERROR_MESSAGE_AND_PATTERN_INVALID",
            "this field declares a `pattern` and gives A12 no error message text for any declared locale, " +
                "which A12 refuses — a blank text, and a text for a locale `header.locales` does not " +
                "declare, are both no message at all",
            field.path,
        )
        !declaresPattern && covered.isNotEmpty() -> Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_ERROR_MESSAGE_AND_NO_PATTERN",
            "this field's string type carries an error message and declares no `pattern`; A12 admits a " +
                "type-level message only as the text for a pattern's own failure",
            field.path,
        )
        else -> null
    }
    // Reported independently of the pairing above, because A12 reports it independently: the two arrive
    // together on a half-translated message with no pattern.
    val untranslated = if (covered.isEmpty()) {
        emptyList()
    } else {
        declaredLocales.filterNot { it in covered }.map { uncovered ->
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING",
                "the model declares `$uncovered` and this field's string type has no error message text " +
                    "for it",
                field.path,
            )
        }
    }
    return listOfNotNull(pairing) + untranslated
}
