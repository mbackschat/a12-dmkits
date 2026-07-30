package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException

/**
 * Render the measured `en_US` String-pattern message grammar (EXP-2026-07-30-01). The scan is deliberately
 * over the original template exactly once: stored bytes inserted for `$field.value$` are opaque output and can
 * never become a second-stage `$field$` token. Unlike a rule message, `$$` is not a literal-dollar escape here;
 * the kernel rejects it during model consistency as an empty parameter.
 */
internal fun renderPatternFieldMessage(
    template: String,
    locale: String,
    fieldLabel: String,
    storedValue: String,
): String {
    requireEnglishFieldTemplate(locale, "String-pattern")
    return renderFixedFieldTokens(
        template = template,
        producer = "String-pattern",
        replacements = mapOf(
            "\$field.value\$" to storedValue,
            "\$field\$" to fieldLabel,
        ),
        preserveDollarPair = false,
    )
}

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
            preserveDollarPair && template.startsWith("\$\$", dollar) -> {
                out.append("\$\$")
                index = dollar + 2
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
