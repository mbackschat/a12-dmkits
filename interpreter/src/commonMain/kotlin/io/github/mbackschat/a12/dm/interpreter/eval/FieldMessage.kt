package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage

/**
 * Render String-pattern and enum invalid-value text in one pass; inserted values remain opaque.
 * FieldConstraintFormalDiffTest's dual-strategy grid separates condition-language tokens from display locale
 * and locks literal terminal dollar pairs (KF42/KF490). Requiredness follows a different law, below.
 */
internal fun renderPatternFieldMessage(
    template: String,
    language: ConditionLanguage,
    fieldLabel: String,
    storedValue: String,
): String = renderFixedFieldTokens(
    template = template,
    producer = "invalid-value",
    replacements = when (language) {
        ConditionLanguage.EN -> mapOf("\$field.value\$" to storedValue, "\$field\$" to fieldLabel)
        ConditionLanguage.DE -> mapOf("\$Feld.Wert\$" to storedValue, "\$Feld\$" to fieldLabel)
    },
)

/**
 * Render a requiredness message: four literal replace-all passes over the whole text, `$Feld$` then `$field$` by
 * the field's label or name, then `$Feld.Wert$` then `$field.value$` by its value, which is empty because only an
 * empty field draws the message. Both languages' tokens apply whatever the condition language, and every other
 * dollar sequence stays verbatim. Each pass reads what the previous one produced, so the order is observable
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

private fun renderFixedFieldTokens(
    template: String,
    producer: String,
    replacements: Map<String, String>,
): String {
    val out = StringBuilder(template.length)
    var index = 0
    while (index < template.length) {
        val dollar = template.indexOf('$', index)
        if (dollar < 0) {
            out.append(template, index, template.length)
            break
        }
        out.append(template, index, dollar)
        val token = replacements.keys.firstOrNull { template.startsWith(it, dollar) }
        when {
            token != null -> {
                out.append(replacements.getValue(token))
                index = dollar + token.length
            }
            template.startsWith("\$\$", dollar) && (template.length - dollar) % 2 == 0 &&
                (dollar until template.length).all { template[it] == '$' } -> {
                out.append(template, dollar, template.length)
                index = template.length
            }
            else -> {
                val close = template.indexOf('$', dollar + 1)
                val form = if (close < 0) template.substring(dollar) else template.substring(dollar, close + 1)
                throw UnsupportedConstructException(
                    "$producer field-owned error template contains unsupported form '$form'",
                )
            }
        }
    }
    return out.toString()
}
