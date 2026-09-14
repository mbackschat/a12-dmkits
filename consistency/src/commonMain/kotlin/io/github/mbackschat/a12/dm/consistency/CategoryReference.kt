package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.categoryAccessesIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A `->` category access, against the Enumeration it hangs off.
 *
 * `CheckEntityUtils.checkCategory` runs two gates in source order: the referenced entity must be an
 * Enumeration (`MVK_NO_ENUMERATION`), then the name behind the arrow must be one that Enumeration DECLARES
 * (`MVK_CATEGORY_UNKOWN`) — both measured, neither previously measured anywhere in this estate
 * ([KERNEL-FINDINGS KF359], `rulekit.validate.laws.CategoryReferenceGateOrderLawsTest`).
 *
 * **The checker was blind to this by construction, not by omission.** Its reference scan steps over the name
 * after `->` on purpose, a category being an attribute rather than an entity — so resolving it would report
 * every categorized reference as undeclared. What the law needs is the PAIRING, which is why the reader
 * yields both halves together.
 *
 * **Two shapes are left to the families that own them**, each measured rather than assumed: an arrow on a
 * GROUP answers `MVK_NO_GROUPS_ALLOWED` and an arrow on a name nothing declares answers `MVK_INVALID_ENTITY`,
 * so resolution and kind both run ahead of these two gates.
 *
 * **And the category's VALUE domain is a different law with a different class.** `[Tier -> Band] == "NOPE"`
 * draws `MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON`, measured in the same grid; it is about the
 * comparison's constant rather than about the arrow, so this family says nothing about it.
 */
fun PreparedModel.categoryReferenceFindings(): List<Finding> = elements.flatMap { element ->
    val body = element.body ?: return@flatMap emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    conditionTexts(element.kind, body).flatMap { (member, text) ->
        categoryAccessesIn(text, conditionLanguage).mapNotNull { (path, category) ->
            categoryFinding(path, category, group, element, member)
        }
    }
}

private fun PreparedModel.categoryFinding(
    path: String,
    category: String,
    declaringGroup: String,
    element: DeclaredElement,
    member: String,
): Finding? {
    val resolved = resolveEntity(declaringGroup, path.replace("*", ""), element.origin)
    if (resolved !is Reference.Resolved) {
        return null
    }
    val target = elements.firstOrNull { it.path == resolved.path && it.declaredKind == DeclaredKind.FIELD }
        ?: return null
    val declaredType = ((target.body?.get("fieldType") as? JsonObject)?.get("type") as? JsonPrimitive)
        ?.contentOrNull ?: return null
    if (declaredType == TYPE_DEF) {
        return null
    }
    val enumeration = target.enumerationBody()
        ?: return if (declaredType == ENUMERATION) {
            null
        } else {
            finding(
                "MVK_NO_ENUMERATION",
                "in `$member`: `->` reads a category attribute of an Enumeration, and `${target.path}` is " +
                    "not one — only an Enumeration declares categories",
                element.path,
            )
        }
    val declared = enumeration.declaredCategoryNames() ?: return null
    if (category in declared) {
        return null
    }
    return finding(
        "MVK_CATEGORY_UNKOWN",
        "in `$member`: `${target.path}` declares no category `$category`" +
            if (declared.isEmpty()) "" else " — it declares ${declared.joinToString { "`$it`" }}",
        element.path,
    )
}

/**
 * The category names this Enumeration declares, or null when its `categories` is a shape no row covers.
 *
 * A malformed `categories` is [enumCategoryFindings]' subject and A12 answers it with a class of its own, so
 * a nameless or non-object entry makes the whole list unreadable here rather than silently shortening the
 * declared set — which would turn a neighbouring defect into a wrong `MVK_CATEGORY_UNKOWN`.
 */
private fun JsonObject.declaredCategoryNames(): Set<String>? {
    val categories = this["categories"] ?: return emptySet()
    val entries = categories as? JsonArray ?: return null
    return entries.map { entry ->
        val declared = entry as? JsonObject ?: return null
        (declared["name"] as? JsonPrimitive)?.contentOrNull ?: return null
    }.toSet()
}

/**
 * A field typed by REFERENCE to a type definition, whose body this checker never reads.
 *
 * Preparation keeps type definitions' IDS alone, which `Check.kt` already declares as a coverage gap — so a
 * `TypeDefType` field may well BE an Enumeration with the category named, and judging it from the reference
 * would refuse what A12 admits. It did: the reference corpus reports a date `format` field typed through
 * `DATE_FORMAT_1`, and `[format->containsYear]` is legal there (caught by
 * `adapter.laws.CheckerSilenceOnCleanModelsTest`, 2026-09-14, before this family landed).
 */
private const val TYPE_DEF = "TypeDefType"

private const val ENUMERATION = "EnumerationType"

private fun finding(code: String, explain: String, at: String): Finding =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at)
