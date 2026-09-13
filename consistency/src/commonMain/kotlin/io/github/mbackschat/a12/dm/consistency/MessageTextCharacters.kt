package io.github.mbackschat.a12.dm.consistency

/**
 * That an error message's TEXT carries no control character.
 *
 * The cheapest check on this surface — no model resolution at all, only the text — and one of the few whose
 * whole law is a character predicate. A12 splits it in two: a line break draws `INVALID_LINEBREAK` and every
 * other control character draws `INVALID_CHARACTERS` ([KERNEL-FINDINGS KF337],
 * `rulekit.validate.laws.MessageTextControlCharacterLawsTest`).
 *
 * **The two gates are ORDERED, not independent, and that is the whole implementation.** A text carrying both
 * a line break and a tab draws the line-break class ALONE — the tab beside it draws nothing — so a reader
 * reporting each kind it finds would name a verdict A12 did not give, on exactly the text an author is most
 * likely to produce by pasting. One finding per message, line breaks first, is what matches.
 *
 * **The set is the ISO control blocks**, measured at both ends: U+001F and U+007F–U+009F are refused, the
 * space U+0020 and the non-breaking space U+00A0 admitted. A repertoire restriction is a different law with
 * a different carrier — the charset refusal for a supplementary-plane code point carries no class at all
 * (`RuleMessageAdmissionGateLawsTest`) — so this family stays inside the control blocks.
 *
 * **A broken CONDITION withholds this verdict too**, which is A12 declining to examine the message member
 * at all rather than a rule about parameters ([declarationsWithUnsoundDsl], [KF338]).
 */
fun PreparedModel.messageTextCharacterFindings(): List<Finding> {
    val unsound = declarationsWithUnsoundDsl()
    return elements.filter { it.kind == "Rule" || it.kind == "Computation" }.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        if (element.path in unsound) {
            return@flatMap emptyList()
        }
        val messages = body.authoredMessages() as? AuthoredMessages.Read ?: return@flatMap emptyList()
        messages.entries.mapNotNull { message -> element.messageTextFinding(message) }
    }
}

/** Both carriers are measured: a computation's message meets the same two classes a rule's does. */
private fun DeclaredElement.messageTextFinding(message: AuthoredMessage): Finding? {
    val offending = message.text.firstOrNull { it.isLineBreak() }
        ?: message.text.firstOrNull { it.isIsoControl() }
        ?: return null
    val lineBreak = offending.isLineBreak()
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        if (lineBreak) "INVALID_LINEBREAK" else "INVALID_CHARACTERS",
        "in `errorMessage`${message.localeKey?.let { " for `" + it + "`" } ?: ""}: the text carries " +
            (if (lineBreak) "a line break" else "the control character U+${offending.code.hex()}") +
            ", which A12 refuses — an error message is one line of printable text",
        path,
    )
}

/**
 * Whether A12's character gate refuses this text, which withholds every other class read out of it.
 *
 * Exposed because the gate is not this family's business alone: a message carrying a control character draws
 * that class and NOTHING about its parameters, wherever in the text the character sits ([KF343],
 * `rulekit.validate.laws.MessageMultipleDefectLawsTest`). It reads the same two predicates the finding above
 * does, so the gate and the class it stands for cannot drift apart.
 *
 * **It answers about one MESSAGE, not one declaration**, which is measured rather than assumed: a rule whose
 * English text carries a tab and whose German text names an unread field draws BOTH classes, so a gate at
 * declaration scope would delete a finding A12 gives. That is the opposite of the condition member's gate,
 * where a defect in one text withholds the class of another ([KF340]) — which is exactly why neither one is
 * carried over to the other.
 */
internal fun String.carriesAnOffendingCharacter(): Boolean = any { it.isLineBreak() || it.isIsoControl() }

private fun Char.isLineBreak(): Boolean = code == 0x0A || code == 0x0D

/** The ISO control blocks, both ends measured: U+0020 and the non-breaking space U+00A0 are outside them. */
private fun Char.isIsoControl(): Boolean = code <= 0x1F || code in 0x7F..0x9F

private fun Int.hex(): String = toString(16).uppercase().padStart(4, '0')
