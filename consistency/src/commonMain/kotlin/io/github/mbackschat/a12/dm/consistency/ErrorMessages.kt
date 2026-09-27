package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * This object's `errorMessage` member, read the way A12's own reader does.
 *
 * A member that is neither a list nor an object carries no entries rather than refusing the source — the
 * measured boundary this file's header describes.
 */
internal fun JsonObject.authoredMessages(): AuthoredMessages {
    val entries = when (val member = this["errorMessage"]) {
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

/**
 * Which of [declaredLocales] these entries actually give A12 a message for.
 *
 * Three measured rules ride here and each is the direction a naive reader gets wrong: an entry for a locale
 * the model does not declare is never collected at all, a LATER entry for one locale replaces an earlier one
 * whether or not it has a text, and a BLANK text is no message rather than an empty one.
 */
internal fun List<AuthoredMessage>.coveredLocales(declaredLocales: List<String>): Set<String> =
    collectedTexts(declaredLocales).filterValues { it.isNotBlank() }.keys

/**
 * The text A12 ends up holding for each declared locale — the last entry for one wins, undeclared ones drop.
 *
 * Split out of [coveredLocales] so the last-wins rule has ONE statement: a second caller needs the texts
 * themselves rather than which locales are covered, and restating the collection there would put a measured
 * rule in two places.
 */
internal fun List<AuthoredMessage>.collectedTexts(declaredLocales: List<String>): Map<String, String> {
    val collected = mutableMapOf<String, String>()
    for (entry in this) {
        val key = entry.localeKey?.takeIf { it in declaredLocales } ?: continue
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
