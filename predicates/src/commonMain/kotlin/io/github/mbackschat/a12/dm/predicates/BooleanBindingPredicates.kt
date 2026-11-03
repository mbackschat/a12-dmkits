package io.github.mbackschat.a12.dm.predicates

/**
 * How A12's model reader binds a JSON value to a primitive `boolean` member: Jackson's standard POJO binding,
 * which is neither `JsonNode.asBoolean` nor a JSON-boolean check ([KERNEL-FINDINGS KF289], [KF569]).
 *
 * **[UNREADABLE] is not a third value of the flag**: the reader refuses the value and the model never loads,
 * so there is no verdict to reproduce. An absent member is the caller's to map to [FALSE], every such member's
 * declared default, while an explicit JSON `null` is [UNREADABLE] on kernel 31.1.1, whose Jackson 3 refuses null
 * for a primitive (30.8.1 took the default); the JSON literals `true`/`false` bind as themselves, and an array or
 * object is [UNREADABLE].
 *
 * One law, measured on two carriers that sit on different model classes — `modelConfig.fieldRefByShortNameAllowed`
 * (`rulekit.validate.laws.ShortNameFlagSpellingLawsTest`) and `StringType.noValueValidation`
 * (`adapter.laws.CheckerDeclarationAdmissionAgreementTest`). The checker and the Java kits read through it; the
 * interpreter, which may not depend on this leaf, keeps a copy held to the same rows.
 */
enum class A12BooleanBinding { TRUE, FALSE, UNREADABLE }

/**
 * A JSON STRING's decoded content. The text is trimmed as Java's `String.trim` trims (U+0000–U+0020 only)
 * before it is compared, so `" true"` and `"true\n"` set the flag while a no-break space before `true` refuses
 * the reader. The recognized spellings are exactly `true`/`True`/`TRUE` and `false`/`False`/`FALSE`; any other
 * text refuses the reader — including the numeric text `"1"`, because a string is never read as a number, and the
 * empty, blank and `null` texts, which Jackson 3 coerces to the null it refuses for a primitive (kernel 31.1.1,
 * measured 2026-11-02; on 30.8.1 they read as the default).
 */
fun a12BooleanOfString(value: String): A12BooleanBinding {
    val text = value.trim { it <= ' ' }
    return when (text) {
        "true", "True", "TRUE" -> A12BooleanBinding.TRUE
        "false", "False", "FALSE" -> A12BooleanBinding.FALSE
        else -> A12BooleanBinding.UNREADABLE
    }
}

/**
 * A JSON NUMBER token's literal text. An integer binds as "not zero" and is judged on its digits, so one wider
 * than any machine type still answers and `-0` is false; a fractional or exponent literal (`1.5`, `1.0`, `1e0`)
 * refuses the reader rather than being truncated.
 */
fun a12BooleanOfNumber(literal: String): A12BooleanBinding {
    val digits = literal.removePrefix("-")
    return when {
        digits.isEmpty() || !digits.all { it in '0'..'9' } -> A12BooleanBinding.UNREADABLE
        digits.all { it == '0' } -> A12BooleanBinding.FALSE
        else -> A12BooleanBinding.TRUE
    }
}
