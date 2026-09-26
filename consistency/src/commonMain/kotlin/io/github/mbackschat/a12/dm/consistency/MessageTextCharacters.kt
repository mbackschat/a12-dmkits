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
 * **The two sets are A12's own predicates, not the ISO control blocks.** A line break is LF, CR, U+2028 or
 * U+2029 (`FehlertextUtil.checkLineSeparators`); every other character is judged by `\p{C}` ([isUnicodeOther]),
 * which also takes format characters, private use and unassigned code points, and a lone surrogate
 * (`rulekit.validate.laws.ErrorTextCharacterGateLawsTest`). The line separators are not `\p{C}` and NEL
 * U+0085 is not a line break, so a checker merging the two sets names the wrong class on both. A repertoire
 * restriction is a different law with a different carrier: the charset refusal of a supplementary character
 * carries no class at all ([modelTextRepertoireFindings]), and `\p{C}` judges that character as ONE code
 * point, so a pair outside the category draws nothing here.
 *
 * **A refused condition or operation withholds this verdict too**, which is A12 declining to examine the message
 * member at all rather than a rule about parameters. Report assembly applies that, not this family ([KF338]).
 */
internal fun TrackedFactBase.messageTextCharacterFindings(): List<Finding> {
    return declarations().filter { it.kind == "Rule" || it.kind == "Computation" }.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val messages = body.authoredMessages() as? AuthoredMessages.Read ?: return@flatMap emptyList()
        messages.examined(declaredLocales).mapNotNull { message -> element.messageTextFinding(message) }
    }
}

/** Both carriers are measured: a computation's message meets the same two classes a rule's does. */
private fun TrackedDeclaration.messageTextFinding(message: AuthoredMessage): Finding? {
    val lineBreak = message.text.firstOrNull { it.isA12LineBreak() }
    val offending = lineBreak?.code ?: message.text.javaCodePoints().firstOrNull(::isUnicodeOther) ?: return null
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        if (lineBreak != null) "INVALID_LINEBREAK" else "INVALID_CHARACTERS",
        "in `errorMessage`${message.localeKey?.let { " for `" + it + "`" } ?: ""}: the text carries " +
            (if (lineBreak != null) "the line break" else "the control or format character") +
            " U+${offending.hex()}, which A12 refuses — an error message is one line of printable text",
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
internal fun String.carriesAnOffendingCharacter(): Boolean =
    any { it.isA12LineBreak() } || javaCodePoints().any(::isUnicodeOther)

/** `FehlertextUtil.checkLineSeparators`' four characters, every one of them in the Basic Multilingual Plane. */
private fun Char.isA12LineBreak(): Boolean = code == 0x0A || code == 0x0D || code == 0x2028 || code == 0x2029

private fun Int.hex(): String = toString(16).uppercase().padStart(4, '0')
