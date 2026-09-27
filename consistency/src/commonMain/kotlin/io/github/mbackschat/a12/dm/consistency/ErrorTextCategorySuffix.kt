package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.categoryAccessesIn
import io.github.mbackschat.a12.dm.consistency.syntax.messageParameterBodies
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The enum CATEGORY an error-message parameter projects its field through.
 *
 * A12 checks the `$Field -> Band$` suffix in two steps, both in `FullChecks_ErrorTextParameterParser`:
 * `checkAndGetCategoryName` answers `NO_CATEGORY` when the arrow carries no name, and
 * `checkAccessToCategoryValue` then answers `FIELD_NO_ENUM` for a field that is not an enumeration and
 * `INVALID_CATEGORY` for one whose type declares no such category
 * ([KERNEL-FINDINGS KF413], `rulekit.validate.laws.ErrorTextCategorySuffixLawsTest`).
 *
 * **An enum declaring NO categories draws `INVALID_CATEGORY`, not a class of its own** — measured, and the
 * row a reader would most likely split, since "has no categories" and "has other categories" feel like
 * different failures and A12 answers both with the containment class.
 *
 * **MEMBERSHIP is asked before any of this and is deliberately left to its own family.** `checkFieldRef`
 * resolves the field against the CONDITION to get the id it then hands to the category check, so a parameter
 * naming a field the condition never reads draws `INVALID_FIELD` whatever its suffix says. This family stays
 * silent there rather than naming a category class A12 never reached — and [messageParameterFindings], which
 * owns `INVALID_FIELD`, reads only the PLAIN form, so that row is an under-report both families disclose
 * rather than a class either of them reports.
 */
internal fun TrackedFactBase.errorTextCategorySuffixFindings(): List<Finding> = declarations().flatMap { element ->
    messageContributions(element) { errorTextCategorySuffixFindings(element, it) }.map { it.finding }
}

internal fun TrackedFactBase.errorTextCategorySuffixFindings(element: TrackedDeclaration, message: AuthoredMessage): List<Finding> {
    val membership = entitiesReadBy(element)
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    if (message.text.carriesAnOffendingCharacter()) {
        return emptyList()
    }
    return messageParameterBodies(message.text).mapNotNull { parameter ->
        suffixDefect(parameter, group, element, membership)?.let { finding(it, element) }
    }
}

private const val ARROW = "->"

/**
 * A12's `$index(Field).value$` terminal, which carries an arrow but is a DIFFERENT parameter form.
 *
 * Measured 2026-09-15: this family used to answer `FIELD_NO_ENUM` for `$index(Note)->Band$`, and that is the
 * class A12 draws for a STRING index field — by luck rather than by reading the right gate.
 * `checkIndexFieldRef` owns this form and has its own eleven classes for it, several of them about an ENUM
 * index field, so the shape where the two families disagree is exactly the one where this one is confident.
 * The terminal is a lexer token spelled lowercase (`$Index(…)$` draws `MVK_UNEXPECTED_TOKEN`,
 * `rulekit.validate.laws.RuleMessageIndexTerminalLawsTest`), so matching it literally is the whole test.
 */
private const val INDEX_TERMINAL = "index("

/** The class this parameter's suffix draws, or null where it carries none and where this may not judge. */
private fun TrackedFactBase.suffixDefect(
    parameter: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    membership: Membership,
): String? {
    if (!parameter.contains(ARROW) || parameter.trimStart().startsWith(INDEX_TERMINAL)) {
        return null
    }
    val access = categoryAccessesIn(parameter, conditionLanguage).singleOrNull()
    val written = access?.path ?: parameter.substringBefore(ARROW).trim().takeIf { it.isNotEmpty() }
        ?: return null
    val field = (resolveEntity(declaringGroup, written, element.origin) as? Reference.Resolved)
        ?.let { resolved -> declarationAt(resolved.path) }
        ?: return null
    if (field.path !in membership) {
        return null
    }
    if (access == null) {
        // The arrow with nothing after it, which is the one defect A12 answers before it looks at the field.
        return if (parameter.trimEnd().endsWith(ARROW)) "NO_CATEGORY" else null
    }
    if (field.declaredFieldType() != FieldType.ENUMERATION) {
        return "FIELD_NO_ENUM"
    }
    val declared = field.enumerationBody()?.categoryNames() ?: return null
    return if (access.category in declared) null else "INVALID_CATEGORY"
}

/** The category names this enumeration declares — empty where it declares none, which is not a failure to read. */
private fun JsonObject.categoryNames(): Set<String> =
    (this["categories"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        .mapNotNullTo(mutableSetOf()) { (it["name"] as? JsonPrimitive)?.takeIf { n -> n.isString }?.content }

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

private fun finding(code: String, element: TrackedDeclaration): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    when (code) {
        "NO_CATEGORY" -> "in `errorMessage`: a parameter's `->` names no category, and A12 requires one " +
            "after the arrow"
        "FIELD_NO_ENUM" -> "in `errorMessage`: a parameter projects a field through `->` that is not an " +
            "enumeration, so it has no categories to project through"
        else -> "in `errorMessage`: a parameter projects a field through a category its enumeration does " +
            "not declare — A12 takes only a category named in the field's own type"
    },
    element.path,
)
