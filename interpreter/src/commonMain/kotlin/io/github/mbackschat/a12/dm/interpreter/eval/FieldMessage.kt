package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage

/**
 * Render String-pattern and enum invalid-value text in one pass; inserted values remain opaque.
 * FieldConstraintFormalDiffTest's dual-strategy grid separates condition-language tokens from display locale
 * and locks literal terminal dollar pairs (KF42/KF490). Requiredness has its own producer contract below.
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
    preserveDollarPair = false,
)

/**
 * Render the measured `en_US` requiredness message grammar. Its fixed `$field$` replacement is distinct from
 * the String-pattern producer and preserves every `$$` pair verbatim.
 */
internal fun renderRequiredFieldMessage(
    template: String,
    locale: String,
    fieldLabel: String,
): String {
    requireEnglishFieldTemplate(locale, "requiredness")
    return renderFixedFieldTokens(
        template = template,
        producer = "requiredness",
        replacements = mapOf("\$field\$" to fieldLabel),
        preserveDollarPair = true,
    )
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
    preserveDollarPair: Boolean,
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
            template.startsWith("\$\$", dollar) &&
                (preserveDollarPair || ((template.length - dollar) % 2 == 0 &&
                    (dollar until template.length).all { template[it] == '$' })) -> {
                val end = if (preserveDollarPair) dollar + 2 else template.length
                out.append(template, dollar, end)
                index = end
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
