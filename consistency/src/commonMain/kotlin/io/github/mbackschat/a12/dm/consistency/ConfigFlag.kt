package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * How A12 reads a JSON value bound to a `boolean` member of its model configuration.
 *
 * **Three outcomes, because folding the third into [OFF] analyses a model that never loads.** Jackson
 * refuses the whole `DocumentModelConfig` binding for a value it cannot take, so A12 never judges the model
 * at all — the same tier distinction the message slots have ([KERNEL-FINDINGS KF289]).
 */
internal enum class ConfigFlag {
    ON,
    OFF,

    /** A12's reader refuses the value, so the model does not deserialize and there is no verdict to mirror. */
    UNREADABLE,
}

/** The spellings Jackson's `boolean` binding takes as true, and the ones it takes as false. */
private val TRUE_SPELLINGS = setOf("true", "True", "TRUE")
private val FALSE_SPELLINGS = setOf("false", "False", "FALSE")

/**
 * Read [value] the way A12's own POJO binding reads it — measured over twenty spellings by
 * `rulekit.validate.laws.ShortNameFlagSpellingLawsTest`, which asks the Kernel rather than any accessor of
 * ours.
 *
 * **This is NOT `JsonNode.asBoolean`, and the gap runs in the dangerous direction.** `asBoolean` takes only
 * the lowercase `"true"`, so it reads `"True"` as OFF where A12 reads it as ON — and for
 * `fieldRefByShortNameAllowed` that turns the model-wide short-name tier off, reporting `MVK_INVALID_ENTITY`
 * for a reference A12 resolves. It also truncates a fractional number, inventing a verdict (`1.5` → true)
 * for a model A12 will not load at all.
 *
 * **Nor is it the rule A12 uses for `excludeRules`/`excludeComputations`**, which reach a custom
 * deserializer's `JsonNode.booleanValue()` and are false for every non-Boolean ([KERNEL-FINDINGS KF3]). A12
 * has two boolean-reading paths and which one a member takes is per-member, so this function is applied only
 * where the POJO path is measured.
 */
internal fun configFlag(value: JsonElement?): ConfigFlag {
    if (value == null || value is JsonNull) {
        return ConfigFlag.OFF
    }
    val primitive = value as? JsonPrimitive ?: return ConfigFlag.UNREADABLE
    val text = primitive.content
    if (primitive.isString) {
        return when {
            text in TRUE_SPELLINGS -> ConfigFlag.ON
            text in FALSE_SPELLINGS -> ConfigFlag.OFF
            // Jackson's empty-string coercion supplies the type's default BEFORE the recognized-values check,
            // so the empty string is not an unrecognized spelling — it is `false`.
            text.isEmpty() -> ConfigFlag.OFF
            else -> ConfigFlag.UNREADABLE
        }
    }
    return when {
        text in TRUE_SPELLINGS -> ConfigFlag.ON
        text in FALSE_SPELLINGS -> ConfigFlag.OFF
        // A numeric literal is read as an int: non-zero is true. Tested on the TEXT rather than through a
        // numeric conversion so an integer too large for any width still answers, and a fractional value is
        // recognized by its own spelling rather than by surviving a parse.
        isIntegerLiteral(text) -> if (isZeroLiteral(text)) ConfigFlag.OFF else ConfigFlag.ON
        else -> ConfigFlag.UNREADABLE
    }
}

private fun isIntegerLiteral(text: String): Boolean {
    val digits = text.removePrefix("-").removePrefix("+")
    return digits.isNotEmpty() && digits.all { it in '0'..'9' }
}

private fun isZeroLiteral(text: String): Boolean = text.removePrefix("-").removePrefix("+").all { it == '0' }
