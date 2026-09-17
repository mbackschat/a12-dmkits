package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A rule's two map-valued PRESENTATION members — `guiPatterns` and `variantErrorMessages`.
 *
 * **Neither carries a verdict, and that is this family's main result.** `Rule.guiPatterns` documents a closed
 * vocabulary in its own javadoc — "only following patterns are possible: key=`dynamicEnumeration`,
 * value=`true`|`false`" — and A12 enforces none of it: an undocumented key and an undocumented value are both
 * ADMITTED. The dynamic-enumeration codegen takes its information from a codegen parameter rather than from
 * this member, and the only reader of the accessor in the whole Kernel is the serializer. Translating that
 * javadoc into a refusal — the obvious reading — would reject models the Kernel accepts, which is the one
 * direction this checker may not take. `variantErrorMessages` is the same: any variant name is admitted,
 * including one no `variantFilter` anywhere names, and including the default variant's empty string.
 *
 * **What IS checkable is whether A12's reader can BIND them**, and that tier is this checker's own
 * ([CK_MODEL_NOT_BUILDABLE], as for `severity` and an explicit-null `errorCode`). A model whose reader
 * refuses never loads, so a consumer handed silence would read a clean report for a model that can never be
 * used.
 *
 * **The two shape laws are NOT the same law, and measuring one member would have shipped the other's wrong.**
 * Both take an object of scalars and refuse a container where a scalar belongs. They part on a NULL value:
 * `Map<String, String>` admits one, while `Map<String, ILocalizedTextMap>` raises past every typed Kernel
 * channel because the value type is `@NonNull`-bound. And `variantErrorMessages` alone has an inner entry
 * shape — an array of `{locale, text}` pairs, each key required, the array required even to hold a single
 * entry — where a bare scalar in that same slot is nonetheless admitted. Whole grid, both members and every
 * tier: `rulekit.validate.laws.RuleVariantPresentationLawsTest`.
 *
 * Nothing is reported for a computation: neither member exists on that carrier.
 */
internal fun TrackedFactBase.presentationMemberFindings(): List<Finding> =
    declarations().filter { it.declaredKind == DeclaredKind.RULE }.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        guiPatternFindings(element, body) + variantErrorMessageFindings(element, body)
    }

/**
 * `guiPatterns`: an object whose values are scalars.
 *
 * A scalar of any JSON type binds, because Jackson coerces it into the `String` value — a boolean is the
 * measured case — so the law is about the CONTAINER at both levels and never about the value's JSON type.
 */
private fun guiPatternFindings(element: TrackedDeclaration, body: JsonObject): List<Finding> {
    val declared = body["guiPatterns"] ?: return emptyList()
    val patterns = declared as? JsonObject
        ?: return listOf(unbindable(element, "guiPatterns", "is ${shapeOf(declared)} where A12 binds a map"))
    val container = patterns.entries.firstOrNull { (_, value) -> value is JsonObject || value is JsonArray }
        ?: return emptyList()
    return listOf(
        unbindable(
            element,
            "guiPatterns",
            "maps `${container.key}` to ${shapeOf(container.value)}, and A12 binds each value as text",
        ),
    )
}

/**
 * `variantErrorMessages`: an object whose values are scalars, or arrays of `{locale, text}` entries.
 *
 * An explicit `null` VALUE gets its own branch because its tier differs from every other refusal here — it
 * escapes A12's typed channels rather than refusing its reader — and the sibling member admits the identical
 * shape. The finding is the same either way, since both leave a model that cannot be used, but the two are
 * separated so the next reader does not take one member's null row as the other's.
 */
private fun variantErrorMessageFindings(element: TrackedDeclaration, body: JsonObject): List<Finding> {
    val declared = body["variantErrorMessages"] ?: return emptyList()
    val byVariant = declared as? JsonObject
        ?: return listOf(
            unbindable(element, "variantErrorMessages", "is ${shapeOf(declared)} where A12 binds a map"),
        )
    return byVariant.entries.mapNotNull { (variant, texts) ->
        when {
            texts is JsonNull ->
                unbindable(element, "variantErrorMessages", "maps `$variant` to null, which A12's text map cannot be")
            texts is JsonObject ->
                unbindable(
                    element,
                    "variantErrorMessages",
                    "maps `$variant` to an object, and A12 binds a LIST of locale/text entries even for one",
                )
            texts is JsonArray && texts.any { !isTextEntry(it) } ->
                unbindable(
                    element,
                    "variantErrorMessages",
                    "gives `$variant` an entry without both a `locale` and a `text`",
                )
            else -> null
        }
    }
}

/** One well-formed `ILocalizedTextMap` entry: an object carrying both keys, neither of them a container. */
private fun isTextEntry(entry: JsonElement): Boolean {
    val fields = entry as? JsonObject ?: return false
    return fields["locale"] is JsonPrimitive && fields["text"] is JsonPrimitive
}

private fun unbindable(element: TrackedDeclaration, member: String, complaint: String) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "`$member` $complaint — its reader refuses the source outright, so no consistency verdict about this " +
        "model exists. A12 checks nothing about what either presentation member CONTAINS, so the fix is " +
        "the shape rather than the vocabulary.",
    element.path,
)

/** How to name a JSON shape in a message, in the reader's terms rather than the serializer's. */
private fun shapeOf(element: JsonElement): String = when {
    element is JsonNull -> "null"
    element is JsonArray -> "a list"
    element is JsonObject -> "an object"
    else -> "text"
}
