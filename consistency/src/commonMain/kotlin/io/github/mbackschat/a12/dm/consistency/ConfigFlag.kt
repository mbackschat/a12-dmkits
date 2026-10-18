package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.predicates.A12BooleanBinding
import io.github.mbackschat.a12.dm.predicates.a12BooleanOfNumber
import io.github.mbackschat.a12.dm.predicates.a12BooleanOfString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * How A12 reads a JSON value bound to a primitive `boolean` member — a model-configuration flag or one of a
 * field type's own.
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

/**
 * Read [value] the way A12's own POJO binding reads it: the shared law [a12BooleanOfString] /
 * [a12BooleanOfNumber], measured on this member by `rulekit.validate.laws.ShortNameFlagSpellingLawsTest`, which
 * asks the Kernel rather than any accessor of ours.
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
    val binding = when {
        primitive.isString -> a12BooleanOfString(primitive.content)
        primitive.content == "true" -> A12BooleanBinding.TRUE
        primitive.content == "false" -> A12BooleanBinding.FALSE
        else -> a12BooleanOfNumber(primitive.content)
    }
    return when (binding) {
        A12BooleanBinding.TRUE -> ConfigFlag.ON
        A12BooleanBinding.FALSE -> ConfigFlag.OFF
        A12BooleanBinding.UNREADABLE -> ConfigFlag.UNREADABLE
    }
}
