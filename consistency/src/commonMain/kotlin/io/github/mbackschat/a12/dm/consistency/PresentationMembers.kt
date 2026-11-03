package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * A rule's map-valued PRESENTATION member, `guiPatterns`.
 *
 * **It carries no verdict, and that is this family's main result.** `Rule.guiPatterns` documents a closed
 * vocabulary in its own javadoc — "only following patterns are possible: key=`dynamicEnumeration`,
 * value=`true`|`false`" — and A12 enforces none of it: an undocumented key and an undocumented value are both
 * ADMITTED. The dynamic-enumeration codegen takes its information from a codegen parameter rather than from
 * this member, and the only reader of the accessor in the whole Kernel is the serializer. Translating that
 * javadoc into a refusal — the obvious reading — would reject models the Kernel accepts, which is the one
 * direction this checker may not take.
 *
 * **What IS checkable is whether A12's reader can BIND it**, and that tier is this checker's own
 * ([CK_MODEL_NOT_BUILDABLE], as for `severity` and an explicit-null `errorCode`). A model whose reader
 * refuses never loads, so a consumer handed silence would read a clean report for a model that can never be
 * used.
 *
 * The member takes an object of scalars and refuses a container where a scalar belongs; a `null` value is
 * admitted. Whole grid: `rulekit.validate.laws.RuleGuiPatternsLawsTest`. Its sibling `variantErrorMessages` is
 * retired on kernel 31.1.1 and read past like any member A12 does not declare
 * (`rulekit.validate.laws.RetiredVariantMembersLawsTest`).
 *
 * Nothing is reported for a computation: the member does not exist on that carrier.
 */
internal fun TrackedFactBase.presentationMemberFindings(): List<Finding> =
    declarations().filter { it.declaredKind == DeclaredKind.RULE }.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        guiPatternFindings(element, body)
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

private fun unbindable(element: TrackedDeclaration, member: String, complaint: String) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "`$member` $complaint — its reader refuses the source outright, so no consistency verdict about this " +
        "model exists. A12 checks nothing about what the member CONTAINS, so the fix is the shape rather " +
        "than the vocabulary.",
    element.path,
)

/** How to name a JSON shape in a message, in the reader's terms rather than the serializer's. */
private fun shapeOf(element: JsonElement): String = when {
    element is JsonNull -> "null"
    element is JsonArray -> "a list"
    element is JsonObject -> "an object"
    else -> "text"
}
