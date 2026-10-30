package io.github.mbackschat.a12.dm.interpreter.load

import io.github.mbackschat.a12.dm.interpreter.bindJavaInt
import io.github.mbackschat.a12.dm.interpreter.JavaIntBinding
import io.github.mbackschat.a12.dm.interpreter.asciiIntOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The prepared source's JSON read the way the Jackson accessors of A12's reader read it, shared by
 * [ModelLoader]'s tree walk and [fieldDef].
 */

internal fun invalidElement(path: String, message: String): InvalidPreparedModelSourceException =
    InvalidPreparedModelSourceException(path, message)

/**
 * Whether an element (Field/Rule/Computation, given its type sub-object) is accepted under the interpreter's
 * NULL variant. The kernel's `VariantFiltering` drops a `variantFilter: {filterType: "ONLY"}` element when the
 * variant is null and keeps `ALL_EXCEPT`; the interpreter has no variant, so it mirrors the null-variant
 * decision (IF36). No filter → kept.
 */
internal fun acceptedByVariantFilter(typeSub: JsonElement?): Boolean {
    val vf = typeSub.path("variantFilter")
    return vf == null || vf is JsonNull || vf.path("filterType").asText() != "ONLY"
}

/** Read a `[{locale, text}]` array into a locale→text map (a rule's `errorMessage` / a field's `labels`). */
internal fun localizedTexts(arr: JsonElement?): Map<String, String> {
    val m = linkedMapOf<String, String>()
    for (e in arr.elements()) m[e.path("locale").asText()] = e.path("text").asText()
    return m
}

// ── JsonElement helpers — mirror the Jackson JsonNode accessors the port relies on. ─────────────
//   `path` chains through a missing key as null (like Jackson's MissingNode); the typed readers give the
//   Jackson defaults ("" / the supplied default / null) so the walk stays faithful even on sparse nodes.

internal fun JsonElement?.path(key: String): JsonElement? = (this as? JsonObject)?.get(key)

internal fun JsonElement?.elements(): List<JsonElement> = this as? JsonArray ?: emptyList()

internal fun JsonElement?.hasNonNull(key: String): Boolean {
    val child = (this as? JsonObject)?.get(key) ?: return false
    return child !is JsonNull
}

/** The scalar's text, or "" for a missing / null / non-scalar node (Jackson `asText()`). */
internal fun JsonElement?.asText(): String = asTextOrNull() ?: ""

/** The scalar's text, or [default] for a missing / null node (Jackson `asText(default)`). */
internal fun JsonElement?.asText(default: String): String = asTextOrNull() ?: default

/** The scalar's text, or null for a missing / null / non-scalar node (Jackson `asText(null)`). */
internal fun JsonElement?.asTextOrNull(): String? {
    val p = this as? JsonPrimitive ?: return null
    return if (p is JsonNull) null else p.content
}

/**
 * A model member A12's reader binds to a Java `int` or `Integer` ([JavaIntBinding]); null where it binds no value,
 * and a refusal naming [member] where A12's reader leaves no model.
 */
internal fun boundJavaInt(value: JsonElement?, member: String): Int? = when (val binding = value.bindJavaInt()) {
    is JavaIntBinding.Bound -> binding.value
    JavaIntBinding.NoValue -> null
    JavaIntBinding.Unbindable -> throw invalidElement(
        member, "$member ${(value as? JsonPrimitive)?.content} is not a whole number A12 can bind, so A12 does not load the model")
}

/** Jackson `isInt()`'s value — a plain integer literal that fits an `Int`; null for a string, a decimal or an oversized one. */
internal fun JsonElement?.intNodeOrNull(): Int? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    return p.content.asciiIntOrNull()
}

/** Jackson `isNumber()` — a numeric literal: an unquoted primitive that is neither null nor a boolean. */
internal fun JsonElement?.isNumberNode(): Boolean {
    val p = this as? JsonPrimitive ?: return false
    if (p is JsonNull || p.isString) return false
    return p.content != "true" && p.content != "false"
}

/** Jackson `isTextual()` — a JSON string. */
internal fun JsonElement?.isTextual(): Boolean {
    val p = this as? JsonPrimitive ?: return false
    return p !is JsonNull && p.isString
}
