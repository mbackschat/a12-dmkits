package io.github.mbackschat.a12.dm.interpreter

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * How the kernel reads a JSON value bound to a primitive `boolean` member of a model POJO — of `content.modelConfig`,
 * of `Field`, or of a field type.
 *
 * **Three outcomes, because folding the third into [OFF] evaluates a model the kernel never loads.** Jackson
 * refuses the whole `DocumentModelConfig` binding for a value it cannot take, so no evaluation of such a
 * model can be compared against anything — there is no kernel run to match.
 */
internal enum class ConfigFlag {
    ON,
    OFF,

    /** The kernel's reader refuses the value, so the model does not deserialize at all. */
    UNREADABLE,
}

/** The spellings Jackson's `boolean` binding takes as true, and the ones it takes as false. */
private val TRUE_SPELLINGS = setOf("true", "True", "TRUE")
private val FALSE_SPELLINGS = setOf("false", "False", "FALSE")

/**
 * Read [value] the way the kernel's own POJO binding reads it — measured by
 * `rulekit.validate.laws.ShortNameFlagSpellingLawsTest`, which asks the kernel rather than any accessor of
 * ours (KERNEL-FINDINGS KF289, KF569).
 *
 * **This is NOT `asBoolean`, and the gap runs in the dangerous direction.** `asBoolean` takes only the
 * lowercase `"true"`, so it reads `"True"` as OFF where the kernel reads it as ON — and for
 * `fieldRefByShortNameAllowed` that turns off the model-wide short-name tier, so a bare reference the kernel
 * resolves goes unresolved here. It also truncates a fractional number, evaluating a model the kernel will
 * not load, affirmatively for `1.5`.
 *
 * It is measured on `fieldRefByShortNameAllowed` (`ShortNameFlagSpellingLawsTest`), on `Field.global` plus the field
 * types' `boolean` members (`adapter.laws.BooleanMemberBindingDiffTest`, IF404), and on DM 29.4 on a mount's
 * `includeConfig.excludeRules`/`excludeComputations` (KF651), whose DM 28 custom reader (KF3) is gone.
 *
 * An absent member is the default, OFF; an explicit JSON `null`, like the empty, blank and `null` texts, is
 * UNREADABLE on kernel 31.1.1, whose Jackson 3 refuses null for a primitive (measured 2026-11-02; 30.8.1 read
 * them as OFF).
 */
internal fun configFlag(value: JsonElement?): ConfigFlag {
    if (value == null) {
        return ConfigFlag.OFF
    }
    if (value is JsonNull) {
        return ConfigFlag.UNREADABLE
    }
    val primitive = value as? JsonPrimitive ?: return ConfigFlag.UNREADABLE
    val text = primitive.content
    if (primitive.isString) {
        // Trimmed as Java's `String.trim` trims (U+0000–U+0020 only) (KF569, measured on this member and on
        // `StringType.noValueValidation`). This copy is held to the same rows as `:predicates`'
        // `a12BooleanOfString`, which the interpreter may not depend on.
        val trimmed = text.javaTrimmed()
        return when {
            trimmed in TRUE_SPELLINGS -> ConfigFlag.ON
            trimmed in FALSE_SPELLINGS -> ConfigFlag.OFF
            else -> ConfigFlag.UNREADABLE
        }
    }
    return when {
        text in TRUE_SPELLINGS -> ConfigFlag.ON
        text in FALSE_SPELLINGS -> ConfigFlag.OFF
        // A numeric literal is read as an int: non-zero is true. Decided on the literal's TEXT rather than
        // through a conversion, so an integer too large for any width still answers ON rather than falling
        // through to the unreadable branch for a model the kernel loads.
        isIntegerLiteral(text) -> if (isZeroLiteral(text)) ConfigFlag.OFF else ConfigFlag.ON
        else -> ConfigFlag.UNREADABLE
    }
}

private fun isIntegerLiteral(text: String): Boolean {
    val digits = text.removePrefix("-").removePrefix("+")
    return digits.isNotEmpty() && digits.all { it in '0'..'9' }
}

private fun isZeroLiteral(text: String): Boolean = text.removePrefix("-").removePrefix("+").all { it == '0' }

/**
 * [ConfigFlag.ON] as true and [ConfigFlag.OFF] as false. An [ConfigFlag.UNREADABLE] value goes to [refuse]: A12's
 * serializer does not read such a model at all, on every `boolean` member the interpreter reads (KF577), so
 * reading it as off would evaluate a model A12 never loads.
 */
internal inline fun ConfigFlag.orRefuse(refuse: () -> Nothing): Boolean = when (this) {
    ConfigFlag.ON -> true
    ConfigFlag.OFF -> false
    ConfigFlag.UNREADABLE -> refuse()
}
