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
 * **An arrow on a GROUP is refused ahead of both**: `CheckEntitySpecImpl.checkCategory` asks `checkIsField`
 * first (source-read), so a group draws `MVK_NO_GROUPS_ALLOWED` wherever the spec stands — a bracket, a
 * membership subject, an entity list, where it answers ahead of the list's own duplicate walk (measured in
 * `adapter.laws.CheckerEntityListCategoryAgreementTest` and `…CheckerFieldValueReadCarrierAgreementTest`).
 * That arm judges only a group the reference reader addresses, because an unstarred repeatable one is the
 * reader's `MVK_NO_WILDCARD` first. An arrow on a name nothing declares is the reference family's
 * `MVK_INVALID_ENTITY`.
 *
 * **And the category's VALUE domain is a different law with a different class.** `[Tier -> Band] == "NOPE"`
 * draws `MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON`, measured in the same grid; it is about the
 * comparison's constant rather than about the arrow, so this family says nothing about it.
 */
internal fun TrackedFactBase.categoryReferenceFindings(): List<Finding> = declarations().flatMap { element ->
    val body = element.body ?: return@flatMap emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    conditionTexts(element.kind, body).flatMap { (member, text) ->
        // Both classes are the entity spec's, which A12 checks once the name behind the arrow is read.
        categoryAccessesIn(text, conditionLanguage).mapNotNull { access ->
            categoryFinding(access.path, access.category, group, element, member, access.spanIn(member))
        }
    }
}

private fun TrackedFactBase.categoryFinding(
    path: String,
    category: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
    span: TextSpan,
): Finding? {
    val resolved = resolveEntity(declaringGroup, path.replace("*", ""), element.origin)
    if (resolved !is Reference.Resolved) {
        return null
    }
    val named = declarationAt(resolved.path) ?: return null
    if (named.declaredKind == DeclaredKind.GROUP) {
        addressedTargetOf(path, declaringGroup, element, indexSelected = false, everyLevelAddressed = false)
            ?: return null
        return finding(
            "MVK_NO_GROUPS_ALLOWED",
            "in `$member`: `->` reads a category attribute, and a category belongs to an Enumeration rather " +
                "than to a group, so `${named.path}` cannot carry one — name the enumerated field inside it, or " +
                "drop the access",
            element.path,
            span,
        )
    }
    val target = named.takeIf { it.declaredKind == DeclaredKind.FIELD } ?: return null
    val declaredType = target.declaredFieldType() ?: return null
    // A field typed by REFERENCE to a type definition, whose body this checker never reads. Preparation keeps
    // type definitions' IDS alone, which `Check.kt` already declares as a coverage gap — so such a field may
    // well BE an Enumeration with the category named, and judging it from the reference would refuse what A12
    // admits. It did: the reference corpus reports a date `format` field typed through `DATE_FORMAT_1`, and
    // `[format->containsYear]` is legal there (caught by `adapter.laws.CheckerSilenceOnCleanModelsTest`,
    // 2026-09-14, before this family landed).
    if (declaredType == FieldType.TYPE_DEF) {
        return null
    }
    val enumeration = target.enumerationBody()
        ?: return if (declaredType == FieldType.ENUMERATION) {
            null
        } else {
            finding(
                "MVK_NO_ENUMERATION",
                "in `$member`: `->` reads a category attribute of an Enumeration, and `${target.path}` is " +
                    "not one — only an Enumeration declares categories",
                element.path,
                span,
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
        span,
    )
}

/**
 * The category names this Enumeration declares, or null when its `categories` is a shape no row covers.
 *
 * A malformed `categories` is [enumCategoryFindings]' subject and A12 answers it with a class of its own, so
 * a nameless or non-object entry makes the whole list unreadable here rather than silently shortening the
 * declared set — which would turn a neighbouring defect into a wrong `MVK_CATEGORY_UNKOWN`.
 */
internal fun JsonObject.declaredCategoryNames(): Set<String>? {
    val categories = this["categories"] ?: return emptySet()
    val entries = categories as? JsonArray ?: return null
    return entries.map { entry ->
        val declared = entry as? JsonObject ?: return null
        (declared["name"] as? JsonPrimitive)?.contentOrNull ?: return null
    }.toSet()
}

private fun finding(code: String, explain: String, at: String, span: TextSpan): Finding =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at, span)
