package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage

/**
 * Render String-pattern and enum invalid-value text: two literal replace-all passes over the whole text, the label
 * tokens `$Feld$` and `$field$` first, then the value tokens `$Feld.Wert$` and `$field.value$`, each in both
 * languages whatever the condition language, and every other dollar sequence verbatim. Kernel 31.1.1 admits an
 * empty `$$` pair anywhere in such a text (KERNEL-FINDINGS KF657), so the pass order is observable: `$$field$$`
 * renders the label between two dollars, and a value is never searched for a label token
 * (`adapter.laws.FieldConstraintFormalDiffTest`'s dual-strategy grid, KF42/KF490).
 *
 * A text A12's field-message check refuses never reaches A12's runtime, so it is unsupported here rather than
 * rendered: an odd number of dollars, or a non-empty part between two dollars that is not the condition language's
 * `field`/`field.value` (`Feld`/`Feld.Wert`). The field-name token `$<fieldName>$`, which the kernel substitutes
 * before both passes, is not measured and stays unsupported too.
 */
internal fun renderPatternFieldMessage(
    template: String,
    language: ConditionLanguage,
    fieldLabel: String,
    storedValue: String,
): String {
    if ('$' !in template) return template
    refusedFieldMessageForm(template, language)?.let { form ->
        throw UnsupportedConstructException("invalid-value field-owned error template contains unsupported form '$form'")
    }
    return template
        .replace("\$Feld\$", fieldLabel)
        .replace("\$field\$", fieldLabel)
        .replace("\$Feld.Wert\$", storedValue)
        .replace("\$field.value\$", storedValue)
}

/** The first form A12's field-message check refuses in [template], spelled as written, or null when it admits it. */
private fun refusedFieldMessageForm(template: String, language: ConditionLanguage): String? {
    if (template.count { it == '$' } % 2 != 0) return "an odd number of '$'"
    val allowed = when (language) {
        ConditionLanguage.EN -> setOf("field", "field.value")
        ConditionLanguage.DE -> setOf("Feld", "Feld.Wert")
    }
    return template.split('$').filterIndexed { index, part -> index % 2 == 1 && part.isNotEmpty() }
        .firstOrNull { it !in allowed }?.let { "\$$it\$" }
}

/**
 * Render a requiredness message: five literal replace-all passes over the whole text, `$<fieldName>.Value$` by the
 * field's value, then `$Feld$` and `$field$` by its label or name, then `$Feld.Wert$` and `$field.value$` by its
 * value, which is empty because only an empty field draws the message. Both languages' tokens apply whatever the
 * condition language, and every other dollar sequence stays verbatim. Each pass reads what the previous one produced, so the order is observable
 * (`$field$Feld$` becomes `$field` plus the label) and a token inside a label is substituted again.
 * `adapter.laws.RequirednessTextSubstitutionDiffTest` holds each of these against both kernel strategies.
 */
internal fun renderRequiredFieldMessage(
    template: String,
    locale: String,
    fieldLabel: String,
): String {
    requireEnglishFieldTemplate(locale, "requiredness")
    return template
        .replace("\$<fieldName>.Value\$", "")
        .replace("\$Feld\$", fieldLabel)
        .replace("\$field\$", fieldLabel)
        .replace("\$Feld.Wert\$", "")
        .replace("\$field.value\$", "")
}

private fun requireEnglishFieldTemplate(locale: String, producer: String) {
    if (locale != "en_US") {
        throw UnsupportedConstructException(
            "$producer field-owned error templates are measured only for locale en_US; requested '$locale'",
        )
    }
}
