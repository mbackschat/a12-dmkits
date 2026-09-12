package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The declaration SLOTS of a stored rule or computation — its `name`, a rule's `errorCode`, and its
 * `errorMessage` texts — together with the declared locale set those texts answer to.
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
 * **A message is collected ONLY for a DECLARED locale, and the last entry for one wins.** Both halves are
 * measured and both are the direction a naive reader gets wrong: a text for an undeclared locale is never
 * collected at all — so it cannot cover anything, and a model declaring no locale has no message whatever
 * its rules carry — and a later blank entry replaces an earlier filled one rather than losing to it
 * (`MessageSlotRawShapeLawsTest`). Locales are matched as parsed locale KEYS, never as text, so `en-US` and
 * `en_US` are one locale on both sides.
 *
 * **A shape A12's own reader refuses is not a verdict about the model, so it never draws an `MVK_*` code.**
 * An entry without a string `locale` and a string `text`, or with a locale spelling A12's parser rejects,
 * makes the source UNREADABLE: no rule is examined and no consistency verdict exists. Reporting
 * `MVK_ERROR_MESSAGE_MISSING` there would present this checker's own reading as A12's about a model A12
 * never judged, so those declarations draw [CK_MODEL_NOT_BUILDABLE] and their message slots go unjudged.
 *
 * **Two of the Java builders' refusals are deliberately NOT rejections here.** A message for an undeclared
 * locale, and a duplicate locale, are both admitted by A12 at this tier; `LocalizedText.checkDeclared`
 * refuses them because each usually hides a typo or dead text, and `MVK_LANGUAGE_DUPLICATE` belongs to the
 * model-as-document tier this checker's subject matter never reaches. They ride as
 * [FindingCategory.AUTHORING_POLICY], which cannot make a model invalid — presenting either as A12's verdict
 * would refuse a model the Kernel accepts, in the direction no consumer can detect.
 */
fun PreparedModel.declarationSlotFindings(): List<Finding> =
    declaredLocaleFindings() +
        elements.filter { it.kind in EXECUTABLE_KINDS }.flatMap { element ->
            reservedNameFinding(element) + errorCodeFindings(element) +
                messageFindings(element, declaredLocales)
        }

/** The two element kinds that carry these slots: A12's executables, the checker's whole subject matter. */
private val EXECUTABLE_KINDS = setOf("Rule", "Computation")

/** The namespace A12 reserves for itself, matched case-INSENSITIVELY as the Kernel matches it. */
private const val RESERVED_NAME_PREFIX = "vk_vk"

/**
 * What the model's own `header.locales` costs it, before any declaration is read.
 *
 * Both rows are model-level and neither belongs to a rule, but both decide what every message slot below
 * answers to — a model with no usable declared locale collects no message at all, so its rules would
 * otherwise each report a missing message with nothing saying why.
 */
private fun PreparedModel.declaredLocaleFindings(): List<Finding> = when {
    unreadableLocaleCodes.isNotEmpty() -> unreadableLocaleCodes.map { code ->
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            CK_MODEL_NOT_BUILDABLE,
            "`header.locales` declares `$code`, which A12 cannot use as a locale, so the model never " +
                "loads — nothing else in this report is A12's verdict about it",
        )
    }
    // Only when nothing above fired: an unusable code leaves the key set empty too, and reporting both would
    // name one defect twice with the second naming the wrong member.
    declaredLocales.isEmpty() -> listOf(
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_SUPP_LANGUAGES_MISSING",
            "the model declares no locale in `header.locales`, so A12 refuses it — and collects no error " +
                "message text at all, whatever its rules carry",
        ),
    )
    else -> emptyList()
}

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
 * A RULE's `errorCode`, which A12 requires non-blank — and the two shapes around it that are not verdicts.
 *
 * **Nothing is reported for a computation**, and that is a shape fact rather than an omission: a computation
 * carries no `errorCode` member at all, and one carrying none is admitted ([KERNEL-FINDINGS KF291]). It is
 * stated because the sibling name law went the other way — `MVK_RULE_NAME_INVALID` reads as a rule's law and
 * fires for computations too, so "rule-only" is a claim that needs its own basis here.
 *
 * **A `severity` slot is deliberately NOT checked.** An ABSENT `severity` is accepted by A12, and every
 * spelling outside its enum makes the source unreadable rather than invalid — so there is no verdict for this
 * family to report, and `MVK_SEVERITY_MISSING` (which `Rule.Builder` raises) fires for none of the measured
 * shapes. Reproducing that builder refusal here would reject a model the Kernel accepts.
 */
private fun errorCodeFindings(element: DeclaredElement): List<Finding> {
    if (element.kind != "Rule") {
        return emptyList()
    }
    val declared = (element.declaration[element.kind] as? JsonObject)?.get("errorCode")
    return when {
        declared == null || (declared is JsonPrimitive && declared.isString && declared.content.isBlank()) ->
            listOf(
                Finding(
                    FindingCategory.A12_CONSTRAINT,
                    FindingSeverity.ERROR,
                    "MVK_ERROR_CODE_MISSING",
                    "this rule declares no error code — A12 refuses it, and a blank `errorCode` counts as " +
                        "none at all",
                    element.path,
                ),
            )
        // An explicit `null` refuses A12's own reader on every rule member measured, which is a tier ABOVE a
        // verdict: no rule is examined, so reporting `MVK_ERROR_CODE_MISSING` here would present this
        // checker's reading as A12's about a model A12 never judged.
        declared is JsonNull -> listOf(
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                CK_MODEL_NOT_BUILDABLE,
                "`errorCode` is an explicit `null`, which A12's own reader refuses — the source never loads, " +
                    "and an OMITTED member is what draws a verdict instead",
                element.path,
            ),
        )
        // A scalar is COERCED to the String member — `7` is the error code "7" — so a non-string primitive is
        // neither missing nor unreadable. A CONTAINER is neither measured nor reported: under-reporting is
        // the safe direction, and inventing a verdict for it would be a refusal with no Kernel basis.
        else -> emptyList()
    }
}

/**
 * One authored error-message entry, reduced to what A12 collects from it.
 *
 * @param localeKey the entry's `locale` as a parsed locale key, absent when it is the empty "any locale"
 *     code — which reads, and then covers no declared language.
 * @param text the entry's `text`, blank included. A BLANK text is not a message: A12 reads it as absent
 *     rather than as an empty one, so it draws the same refusal an omitted entry does — measured on both
 *     carriers, and separated from "this locale has no entry" by the row that leaves the other locale's
 *     text intact.
 */
private data class AuthoredMessage(val localeKey: String?, val text: String)

/**
 * The element's `errorMessage`, either as A12 collects it or as the reason A12 refuses to read the source.
 */
private sealed interface AuthoredMessages {
    data class Read(val entries: List<AuthoredMessage>) : AuthoredMessages

    /** @param explain what A12's reader refused, in its own terms. */
    data class Refused(val explain: String) : AuthoredMessages
}

private fun messageFindings(element: DeclaredElement, declaredLocales: List<String>): List<Finding> {
    val authored = when (val read = element.authoredMessages()) {
        is AuthoredMessages.Refused -> return listOf(
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                CK_MODEL_NOT_BUILDABLE,
                "${read.explain}, so A12's own reader refuses the source and no rule in it is ever " +
                    "examined — this declaration's error messages are not judged here",
                element.path,
            ),
        )
        is AuthoredMessages.Read -> read.entries
    }
    // What A12 ends up holding: only DECLARED locales are collected, and a later entry replaces an earlier
    // one for the same locale whether or not it has a text. A reader that kept the first usable entry would
    // see a message where A12 finds none.
    val collected = mutableMapOf<String, String>()
    for (entry in authored) {
        val key = entry.localeKey?.takeIf { it in declaredLocales } ?: continue
        collected[key] = entry.text
    }
    val covered = collected.filterValues { it.isNotBlank() }.keys
    val missing = if (covered.isEmpty()) {
        listOf(
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_ERROR_MESSAGE_MISSING",
                "this declaration carries no error message text for any declared locale — A12 refuses it, " +
                    "and a blank `text` counts as no message at all",
                element.path,
            ),
        )
    } else {
        declaredLocales.filterNot { it in covered }.map { uncovered ->
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING",
                "the model declares `$uncovered` and this declaration has no error message text for it",
                element.path,
            )
        }
    }
    return missing + authoredLocalePolicyFindings(element, authored, declaredLocales)
}

/**
 * The two locale shapes A12 admits and the Java builders refuse — advisory here, and never a rejection.
 *
 * Both are reported off the AUTHORED entries rather than the collected ones, because that is what an author
 * wrote and would have to edit: a duplicate whose second text is blank is still two entries for one locale,
 * and an undeclared locale's entry is never collected at all.
 */
private fun authoredLocalePolicyFindings(
    element: DeclaredElement,
    authored: List<AuthoredMessage>,
    declaredLocales: List<String>,
): List<Finding> {
    val keys = authored.mapNotNull { it.localeKey }
    val duplicated = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    // "Undeclared" is a comparison against a declared set, so a model that declares NONE would make every
    // message undeclared and turn one fact about the model into one advisory per declaration. That model's
    // problem is that it declares no locale at all, which `declaredLocaleFindings` already reports.
    val undeclared = if (declaredLocales.isEmpty()) emptySet() else keys.toSet() - declaredLocales.toSet()
    return duplicated.map { locale ->
        Finding(
            FindingCategory.AUTHORING_POLICY,
            FindingSeverity.WARNING,
            "MVK_LANGUAGE_DUPLICATE",
            "this declaration has more than one error message for `$locale`; A12 keeps the LAST of them, " +
                "and refuses the pair only in its model-as-document tier",
            element.path,
        )
    } + undeclared.map { locale ->
        Finding(
            FindingCategory.AUTHORING_POLICY,
            FindingSeverity.WARNING,
            "RK_UNDECLARED_MESSAGE_LOCALE",
            "this declaration has an error message for `$locale`, which the model does not declare, so A12 " +
                "never collects the text and it can cover no locale",
            element.path,
        )
    }
}

/**
 * The element's authored messages, read exactly as far as A12's own reader gets.
 *
 * Three shapes of the `errorMessage` member itself are measured, and then source-located in
 * `LocalizedTextMapDeserializer.deserialize` ([KERNEL-FINDINGS KF290]): it loops `for (JsonNode entry :
 * parser.readValueAsTree())`, so an ARRAY yields its elements, an OBJECT its values, and a scalar or an
 * absent member yields none — leaving the declaration simply message-less. Only the ENTRIES have a shape the
 * reader refuses, and a refusal there is about the SOURCE rather than about this declaration, which is why
 * it stops the read instead of contributing an unusable entry.
 */
private fun DeclaredElement.authoredMessages(): AuthoredMessages {
    val entries = when (val member = (declaration[kind] as? JsonObject)?.get("errorMessage")) {
        is JsonArray -> member.toList()
        is JsonObject -> member.values.toList()
        else -> emptyList<JsonElement>()
    }
    val read = mutableListOf<AuthoredMessage>()
    for (entry in entries) {
        val body = entry as? JsonObject
            ?: return AuthoredMessages.Refused("an `errorMessage` entry here is not a JSON object")
        val locale = body.string("locale")
            ?: return AuthoredMessages.Refused("an `errorMessage` entry here carries no string `locale`")
        val text = body.string("text")
            ?: return AuthoredMessages.Refused("an `errorMessage` entry here carries no string `text`")
        // The empty code is the one A12 READS without producing a locale, so it is an entry that covers
        // nothing rather than a spelling the reader refuses.
        val key = localeKeyOrNull(locale)
        if (key == null && locale.isNotEmpty()) {
            return AuthoredMessages.Refused("`$locale` is not a locale code A12's parser accepts")
        }
        read += AuthoredMessage(key, text)
    }
    return AuthoredMessages.Read(read)
}

/** A member A12 binds to a `String`, absent when it is missing or is not a JSON string. */
private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
