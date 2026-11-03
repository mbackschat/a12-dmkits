package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * How A12 reads an `errorMessage` member, wherever one is declared — one statement for every carrier.
 *
 * Two carriers own such a member and they are checked by different families: a rule's or computation's own
 * messages ([declarationSlotFindings]) and a string type's pattern message ([stringTypeMessageFindings]).
 * The reader is shared because the two were MEASURED to agree, not because the member names match — the
 * boundary between "A12 binds this and judges it" and "A12's reader refuses the source" is the part a
 * checker gets wrong, and it does not follow the shape a reader would guess: a scalar where a list belongs
 * BINDS, collecting nothing, while a non-object ENTRY inside the list makes the whole model unloadable.
 * Both arms are locked on the rule carrier by `rulekit.validate.laws.MessageSlotRawShapeLawsTest` and on the
 * string-type carrier by `rulekit.validate.laws.StringTypeMessageAndPatternLawsTest`.
 */
internal data class AuthoredMessage(val localeKey: String?, val text: String)

/** The carrier's `errorMessage`, either as A12 collects it or as the reason A12 refuses to read the source. */
internal sealed interface AuthoredMessages {
    data class Read(val entries: List<AuthoredMessage>) : AuthoredMessages

    /** @param explain what A12's reader refused, in its own terms. */
    data class Refused(val explain: String) : AuthoredMessages
}

/**
 * This object's `errorMessage` member, read the way A12's own reader does — the text-map law [localizedTextRefusal]
 * states once for every localized-text member, including that a `null` member refuses the source.
 */
internal fun JsonObject.authoredMessages(): AuthoredMessages {
    val member = this["errorMessage"]
    localizedTextRefusal(member)?.let { return AuthoredMessages.Refused("the `errorMessage` here $it") }
    return AuthoredMessages.Read(
        localizedTextEntries(member).map { AuthoredMessage(localeKeyOrNull(checkNotNull(it.string("locale"))), checkNotNull(it.string("text"))) },
    )
}

/**
 * Which of [declaredLocales] these entries actually give A12 a message for.
 *
 * Three measured rules ride here and each is the direction a naive reader gets wrong: an entry for a locale
 * the model does not declare is never collected for the checker (code generation reads it on kernel 31.1.1,
 * [undeclaredLocaleMessageFindings]), a LATER entry for one locale replaces an earlier one
 * whether or not it has a text, and a BLANK text is no message rather than an empty one.
 */
internal fun List<AuthoredMessage>.coveredLocales(declaredLocales: List<String>): Set<String> =
    collectedTexts(declaredLocales).filterValues { !it.isA12Blank() }.keys

/**
 * The text A12 ends up holding for each declared locale — the last entry for one wins, undeclared ones drop.
 *
 * Split out of [coveredLocales] so the last-wins rule has ONE statement: a second caller needs the texts
 * themselves rather than which locales are covered, and restating the collection there would put a measured
 * rule in two places.
 */
internal fun List<AuthoredMessage>.collectedTexts(declaredLocales: List<String>): Map<String, String> =
    textsCollectedFor { it in declaredLocales }

/**
 * The last text for each locale [kept] accepts, in the order the locales first appear — the one statement of the
 * last-wins rule, shared by the declared locales' view above and [undeclaredLocaleMessageFindings]' undeclared one.
 */
internal fun List<AuthoredMessage>.textsCollectedFor(kept: (String) -> Boolean): Map<String, String> {
    val collected = mutableMapOf<String, String>()
    for (entry in this) {
        val key = entry.localeKey?.takeIf(kept) ?: continue
        collected[key] = entry.text
    }
    return collected
}

/**
 * The entries A12's error-text checker examines: each declared locale's LAST entry, and none for a locale the
 * model does not declare (`rulekit.validate.laws.ErrorTextLocaleScopeLawsTest.whichEntriesTheErrorTextCheckerExamines`,
 * [KERNEL-FINDINGS KF197]).
 *
 * Every family that judges a message's TEXT reads these. Judging every authored entry instead rejects a model
 * A12 admits, since a text A12 never examines cannot draw its class; the slot families that judge presence and
 * duplicates read [AuthoredMessages.Read.entries] on purpose.
 */
internal fun AuthoredMessages.Read.examined(declaredLocales: List<String>): List<AuthoredMessage> =
    entries.collectedTexts(declaredLocales).map { (locale, text) -> AuthoredMessage(locale, text) }

/** A member A12 binds to a `String`, absent when it is missing or is not a JSON string. */
internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Locale occurrence order is retained before separate message checks are combined (R5-E0). */
internal fun TrackedFactBase.messageContributions(
    element: TrackedDeclaration,
    check: (AuthoredMessage) -> List<Finding>,
): List<MessageFinding> {
    if (element.declaredKind != DeclaredKind.RULE && element.declaredKind != DeclaredKind.COMPUTATION) return emptyList()
    val messages = element.body?.authoredMessages() as? AuthoredMessages.Read ?: return emptyList()
    return messages.examined(declaredLocales).flatMapIndexed { ordinal, message ->
        check(message).map { MessageFinding(element.key, ordinal, it) }
    }
}
