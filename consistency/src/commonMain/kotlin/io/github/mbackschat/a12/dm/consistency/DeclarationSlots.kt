package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.predicates.isReservedElementName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The declaration SLOTS of a stored rule or computation — its `name`, a rule's `errorCode` and `severity`,
 * and its `errorMessage` texts — together with the declared locale set those texts answer to.
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
internal fun TrackedFactBase.declarationSlotFindings(): List<Finding> =
    unbindableBooleanFindings() + declaredLocaleFindings() +
        declarations().filter { it.declaredKind in EXECUTABLE_KINDS }.flatMap { element ->
            alphabetFindings(element) + errorCodeFindings(element) + severityFindings(element) +
                messageFindings(element, declaredLocales)
        }

/** The two element kinds that carry these slots: A12's executables, the checker's whole subject matter. */
private val EXECUTABLE_KINDS = setOf(DeclaredKind.RULE, DeclaredKind.COMPUTATION)

/**
 * The model's own `boolean` members A12's reader refuses ([PreparedModel.unbindableBooleans]): the same
 * source-level refusal as an unusable locale code below, so the same class, and model-level because none
 * belongs to a declaration.
 */
private fun TrackedFactBase.unbindableBooleanFindings(): List<Finding> = unbindableBooleans.map { member ->
    Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        CK_MODEL_NOT_BUILDABLE,
        "$member, which A12 cannot bind as a boolean — write `true` or `false`. A12's reader refuses the " +
            "source, so the model never loads and nothing else in this report is A12's verdict about it",
    )
}

/**
 * What the model's own `header.locales` costs it, before any declaration is read.
 *
 * Both rows are model-level and neither belongs to a rule, but both decide what every message slot below
 * answers to — a model with no usable declared locale collects no message at all, so its rules would
 * otherwise each report a missing message with nothing saying why.
 */
private fun TrackedFactBase.declaredLocaleFindings(): List<Finding> = when {
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

/**
 * The two alphabets A12 asks of an executable before its reserved-name law, each answered by a sentence with no
 * class ([KERNEL-FINDINGS KF291]).
 *
 * **The name takes ASCII only**: a letter, then letters, digits and underscores, so `1Probe`, `_Probe`, `Pro.be`
 * and `Prämie` are all refused, and `vk_vk.x` draws the sentence without `MVK_RULE_NAME_INVALID`. **The error code takes a wider set**: ASCII letters and digits, the German umlauts and `ß`, a space and
 * the underscore, and nothing else, so `C.1`, `C-1`, `Cé` and a tab are refused. A computation carries no
 * `errorCode`, and A12 asks the code alphabet of its NAME instead, which is why `Ca.lc` draws both sentences and
 * `Ca lc` only the name's. An empty code is [errorCodeFindings]' business, never this alphabet's.
 */
private fun alphabetFindings(element: TrackedDeclaration): List<Finding> {
    val code = if (element.kind == "Rule") {
        (element.body?.get("errorCode") as? JsonPrimitive)?.takeIf { it.isString }?.content
    } else {
        element.name
    }
    val codeFinding = code?.takeIf { text -> text.any { it !in ERROR_CODE_CHARACTERS } }?.let { text ->
        slotFinding(element, "RK_ERROR_CODE_ALPHABET",
            "the error code `$text` carries a character outside letters, digits, a space and the underscore, " +
                "which A12 refuses in a sentence with no class; the letters include the German umlauts and `ß`")
    }
    val nameAdmitted = element.name.firstOrNull()?.isAsciiLetter() != false &&
        element.name.all { it.isAsciiLetter() || it in '0'..'9' || it == '_' }
    val nameFinding = if (nameAdmitted) null else slotFinding(element, "RK_RULE_NAME_ALPHABET",
        "the name `${element.name}` does not start with an ASCII letter followed by ASCII letters, digits and " +
            "underscores, which A12 refuses in a sentence with no class")
    // The reserved-name predicate admits only names this alphabet admits, so it is silent beside the sentence.
    return listOfNotNull(codeFinding, nameFinding) + reservedNameFinding(element)
}

private val ERROR_CODE_CHARACTERS: Set<Char> =
    (('A'..'Z') + ('a'..'z') + ('0'..'9') + "äöüÄÖÜß _".toList()).toSet()

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun slotFinding(element: TrackedDeclaration, code: String, explain: String) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, element.path)

/**
 * The reserved-namespace law lives in the shared leaf, not here.
 *
 * `Rule.Builder` and `Computation.Builder` enforce the same law on the Java side, and before the leaf all
 * three stated it separately — which is how the computation copy came to be missing entirely until
 * 2026-09-12, the code reading as a rule's law. The finding's WORDING is this checker's own; the predicate
 * that decides it is not.
 */
private fun reservedNameFinding(element: TrackedDeclaration): List<Finding> =
    if (!isReservedElementName(element.name)) {
        emptyList()
    } else {
        listOf(
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_RULE_NAME_INVALID",
                "`vk_vk` is reserved by A12 — in any letter case — so this name is refused; " +
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
 * The `severity` slot beside it is [severityFindings].
 */
private fun errorCodeFindings(element: TrackedDeclaration): List<Finding> {
    if (element.kind != "Rule") {
        return emptyList()
    }
    val declared = (element.body ?: return emptyList())["errorCode"]
    return when {
        declared == null || (declared is JsonPrimitive && declared.isString && declared.content.isA12Blank()) ->
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

/** The three constants A12's `Severity` binding admits — exactly, and case-sensitively. */
private val SEVERITIES = setOf("ERROR", "WARNING", "INFO")

/**
 * A12's second, UNPREFIXED class for a declaration leaving a declared locale without text, which rides beside
 * the prefixed message class.
 *
 * **It is a family of its own, [CheckFamily.ERROR_TEXT_PRESENCE], because A12's error-text checker draws
 * it.** That checker judges a message only beside a sound condition or operation, while the prefixed class
 * it rides with is judged regardless: beside a refused condition A12 reports either message class and
 * withholds this one, measured over seven refused condition shapes and on a computation's operation and both
 * its preconditions (`rulekit.validate.laws.MessageParameterConditionDependencyLawsTest`, [KF338]). Report
 * assembly applies that, and it can only because the two are produced apart.
 */
internal fun TrackedFactBase.errorTextPresenceFindings(): List<Finding> =
    declarations().filter { it.declaredKind in EXECUTABLE_KINDS }.flatMap { element ->
        val authored = (element.authoredMessages() as? AuthoredMessages.Read)?.entries
            ?: return@flatMap emptyList()
        element.unprefixedErrorTextFinding(authored, declaredLocales)
    }

/**
 * Which declarations draw the unprefixed class: those leaving some declared locale with NO TEXT AT ALL.
 *
 * `RULE_HAS_NO_ERRORTEXT` rides beside both message classes — `MVK_ERROR_MESSAGE_MISSING` when no declared
 * locale is covered, `MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING` when another one is — and on both carriers. It
 * keys on whether an uncovered locale has any text: an absent entry, an empty text, a text only for an
 * undeclared locale and a filled entry replaced by an empty one all draw it, **a whitespace text does not**
 * ([KERNEL-FINDINGS KF346], `rulekit.validate.laws.RuleSlotMultipleDefectLawsTest`, and for the rule beside
 * an answered locale `…MessageSlotRawShapeLawsTest.whenTheUnprefixedNoErrorTextClassCoFiresWithAPerLocaleVerdict`).
 *
 * **The question is asked of each DECLARED locale, not of the texts present**, and the two-locale rows are
 * what settle it: whitespace beside an empty text or an absent entry draws the class, and only whitespace for
 * every uncovered locale omits it. So "every collected text is whitespace" fits every one-locale row and is
 * false on the row with an unanswered locale. *Corrected 2026-09-25:* this asked only of a declaration with
 * no covered locale at all, which dropped the class beside the per-locale one on every such shape.
 *
 * **Whitespace is stated as an observation, not explained.** "A12 trims for one check and not the other"
 * fits it, and so does "the second check asks only whether the string is empty"; the two cannot be told
 * apart, because a non-empty string that still counts as blank IS a whitespace-only one.
 *
 * **And a model with NO usable declared locale draws no class at all.** Every such shape — an empty
 * `header.locales`, a code A12's reader refuses, the empty code — leaves the model unbuildable or unreadable,
 * so A12 reaches no verdict about the message and reporting one here would state this checker's reading as
 * A12's. The split above was measured on a model declaring good locales, and that carrier is the condition
 * for applying it.
 */
private fun TrackedDeclaration.unprefixedErrorTextFinding(
    authored: List<AuthoredMessage>,
    declaredLocales: List<String>,
): List<Finding> {
    // NO MUTANT CAN KILL THIS BRANCH, and it stays because it states a different law from the one that
    // happens to cover it: `any {}` over an empty list is false, so an undeclared locale set already falls
    // out below. The two coincide only for as long as the text rule is written as an `any`, and the law
    // here — a model with no usable locale reaches no verdict, so neither class is A12's — is not that
    // rule's to carry.
    if (declaredLocales.isEmpty()) {
        return emptyList()
    }
    val collected = authored.collectedTexts(declaredLocales)
    // One per declared locale without a text, as A12 names each language (KERNEL-FINDINGS KF558).
    return declaredLocales.filter { collected[it].isNullOrEmpty() }.map { locale ->
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "RULE_HAS_NO_ERRORTEXT",
            "…and A12 says so a second time under its own error-text vocabulary, which is unprefixed: this " +
                "declaration has no text for the declared locale `$locale`, so it has no error text there at all",
            path,
        )
    }
}

/**
 * A RULE's `severity`, which is an ENUM binding and therefore behaves like none of its sibling slots.
 *
 * **An ABSENT `severity` is accepted** — A12 defaults it to `ERROR` — so nothing is reported for one, and in
 * particular never `MVK_SEVERITY_MISSING`, which `Rule.Builder` raises and the Kernel raises for no measured
 * shape at all. Reproducing that builder refusal would reject a model A12 accepts.
 *
 * **A PRESENT member outside the set makes the source UNREADABLE**, and that tier IS reported, as
 * `errorCode`'s explicit `null` is a few lines above: the model never loads, so a consumer handed silence
 * would read a clean report for a model that can never be used. This is the one place the two slots'
 * treatments could look inconsistent, and they are not — they report the same tier, reached by different
 * shapes.
 *
 * **None of the sibling slots' leniencies carry across**, which is why each was measured rather than assumed
 * (`RuleCodeAndSeverityLawsTest`): there is no coercion (`7` is not a severity, where `7` IS the error code
 * "7"), blank does not count as absent (the empty string and whitespace are refusals, not defaults), the
 * match is case-SENSITIVE (`"Error"` is refused), and a JSON container is refused too — the one member of
 * this checker's standing container gap that is decided.
 *
 * Nothing is reported for a computation, for [errorCodeFindings]'s reason: the member does not exist there.
 */
private fun severityFindings(element: TrackedDeclaration): List<Finding> {
    if (element.kind != "Rule") {
        return emptyList()
    }
    val declared = (element.body ?: return emptyList())["severity"] ?: return emptyList()
    val spelled = (declared as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (spelled in SEVERITIES) {
        return emptyList()
    }
    return listOf(
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            CK_MODEL_NOT_BUILDABLE,
            "`severity` is ${spelled?.let { "`$it`" } ?: "not a string"}, and A12 admits only " +
                "${SEVERITIES.joinToString(", ") { "`$it`" }} — its reader refuses the source outright, so " +
                "no consistency verdict about this model exists. Omitting the member is what defaults it.",
            element.path,
        ),
    )
}


private fun messageFindings(element: TrackedDeclaration, declaredLocales: List<String>): List<Finding> {
    val authored = when (val read = element.authoredMessages() ?: return emptyList()) {
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
    val covered = authored.coveredLocales(declaredLocales)
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
    element: TrackedDeclaration,
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
 * parser.readValueAsTree())`, so an ARRAY yields its declarations(), an OBJECT its values, and a scalar or an
 * absent member yields none — leaving the declaration simply message-less. Only the ENTRIES have a shape the
 * reader refuses, and a refusal there is about the SOURCE rather than about this declaration, which is why
 * it stops the read instead of contributing an unusable entry.
 */
private fun TrackedDeclaration.authoredMessages(): AuthoredMessages? = body?.authoredMessages()
