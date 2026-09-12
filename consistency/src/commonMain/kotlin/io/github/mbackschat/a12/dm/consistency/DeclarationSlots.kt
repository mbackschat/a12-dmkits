package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The declaration SLOTS of a stored rule or computation — its `name`, and its `errorMessage` texts.
 *
 * Nothing here needs a condition parser: every row reads members A12 declares as structured JSON, which is
 * what makes this the family that can land before the checker owns a syntax layer.
 *
 * **The same laws hold on both kinds, including the one whose code name says otherwise.**
 * `MVK_RULE_NAME_INVALID` reads as a rule's law and rejects a computation too — the Kernel calls both "Rule"
 * in its diagnostics, and applying the prefix law to one kind alone would leave the other silently
 * unchecked. Every row below is measured on both carriers (`RuleSlotLawsTest`, `ComputationSlotLawsTest`,
 * kernel 30.8.1, 2026-09-12).
 *
 * **Two of the Java builders' refusals are deliberately NOT rejections here.** A message for an undeclared
 * locale, and a duplicate locale, are both admitted by A12 at this tier; `LocalizedText.checkDeclared`
 * refuses them because each usually hides a typo or dead text, and `MVK_LANGUAGE_DUPLICATE` belongs to the
 * model-as-document tier this checker's subject matter never reaches. They ride as
 * [FindingCategory.AUTHORING_POLICY], which cannot make a model invalid — presenting either as A12's verdict
 * would refuse a model the Kernel accepts, in the direction no consumer can detect.
 */
fun PreparedModel.declarationSlotFindings(): List<Finding> =
    elements.filter { it.kind in EXECUTABLE_KINDS }.flatMap { element ->
        reservedNameFinding(element) + messageFindings(element, declaredLocales)
    }

/** The two element kinds that carry these slots: A12's executables, the checker's whole subject matter. */
private val EXECUTABLE_KINDS = setOf("Rule", "Computation")

/** The namespace A12 reserves for itself, matched case-INSENSITIVELY as the Kernel matches it. */
private const val RESERVED_NAME_PREFIX = "vk_vk"

private fun reservedNameFinding(element: DeclaredElement): List<Finding> =
    if (!element.name.startsWith(RESERVED_NAME_PREFIX, ignoreCase = true)) {
        emptyList()
    } else {
        listOf(
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_RULE_NAME_INVALID",
                "`$RESERVED_NAME_PREFIX` is reserved by A12 — in any letter case — so this name is refused; " +
                    "rename the declaration",
                element.path,
            ),
        )
    }

/**
 * One authored error-message entry, as stored: `errorMessage: [{locale, text}]` on the element's envelope.
 *
 * A BLANK text is not a message. A12 reads it as absent rather than as an empty one, so it draws the same
 * refusal an omitted entry does — measured on both carriers, and separated from "this locale has no entry"
 * by the row that leaves the other locale's text intact.
 */
private data class AuthoredMessage(val locale: String?, val text: String?) {
    val isUsable: Boolean get() = !locale.isNullOrBlank() && !text.isNullOrBlank()
}

private fun messageFindings(element: DeclaredElement, declaredLocales: List<String>): List<Finding> {
    val authored = element.authoredMessages()
    val usable = authored.filter { it.isUsable }
    if (usable.isEmpty()) {
        // Reported without consulting the declared locales, because it does not depend on them: a
        // message-less declaration is refused even by a model that declares none at all (the row that puts
        // this on the other side of the axis every two-locale fixture shares).
        return listOf(
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_ERROR_MESSAGE_MISSING",
                "this declaration carries no error message text — A12 refuses it, and a blank `text` counts " +
                    "as no message at all",
                element.path,
            ),
        )
    }
    val covered = usable.mapNotNull { it.locale }.toSet()
    return declaredLocales.filterNot { it in covered }.map { missing ->
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING",
            "the model declares `$missing` and this declaration has no error message text for it",
            element.path,
        )
    } + authoredLocalePolicyFindings(element, authored, declaredLocales)
}

/**
 * The two locale shapes A12 admits and the Java builders refuse — advisory here, and never a rejection.
 *
 * Both are reported off the AUTHORED entries rather than the usable ones, because that is what an author
 * wrote and would have to edit: a duplicate whose second text is blank is still two entries for one locale.
 */
private fun authoredLocalePolicyFindings(
    element: DeclaredElement,
    authored: List<AuthoredMessage>,
    declaredLocales: List<String>,
): List<Finding> {
    val locales = authored.mapNotNull { it.locale?.takeIf(String::isNotBlank) }
    val duplicated = locales.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    // "Undeclared" is a comparison against a declared set, so a model that declares NONE would make every
    // message undeclared and turn one fact about the model into one advisory per declaration. That model's
    // problem is that it declares no locale at all, which is a different finding and not this family's.
    val undeclared =
        if (declaredLocales.isEmpty()) emptySet() else locales.toSet() - declaredLocales.toSet()
    return duplicated.map { locale ->
        Finding(
            FindingCategory.AUTHORING_POLICY,
            FindingSeverity.WARNING,
            "MVK_LANGUAGE_DUPLICATE",
            "this declaration has more than one error message for `$locale`; A12 keeps one of them without " +
                "saying which, and refuses the pair only in its model-as-document tier",
            element.path,
        )
    } + undeclared.map { locale ->
        Finding(
            FindingCategory.AUTHORING_POLICY,
            FindingSeverity.WARNING,
            "RK_UNDECLARED_MESSAGE_LOCALE",
            "this declaration has an error message for `$locale`, which the model does not declare, so A12 " +
                "stores it without ever examining it",
            element.path,
        )
    }
}

/**
 * The element's authored messages, read leniently: an entry this cannot read is an entry that covers no
 * locale, which is exactly how A12 treats it. Preparation's diagnosing accessors are deliberately not used
 * here — an unreadable message is a FINDING about the declaration, not a source the checker could not read.
 */
private fun DeclaredElement.authoredMessages(): List<AuthoredMessage> =
    ((declaration[kind] as? JsonObject)?.get("errorMessage") as? JsonArray)
        .orEmpty()
        .map { entry ->
            val body = entry as? JsonObject
            AuthoredMessage(body?.member("locale"), body?.member("text"))
        }

private fun JsonObject.member(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
