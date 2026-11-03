package io.github.mbackschat.a12.dm.consistency

/**
 * That an error message's TEXT carries no control character.
 *
 * The cheapest check on this surface — no model resolution at all, only the text — and one of the few whose
 * whole law is a character predicate. A12 asks three questions: a line break draws `INVALID_LINEBREAK`, a lone
 * surrogate `WRONG_ENCODING`, and every other control character `INVALID_CHARACTERS` ([KERNEL-FINDINGS KF337],
 * `rulekit.validate.laws.MessageTextControlCharacterLawsTest`).
 *
 * **The gates are ORDERED, not independent, and that is the whole implementation.** A text carrying both
 * a line break and a tab draws the line-break class ALONE — the tab beside it draws nothing — so a reader
 * reporting each kind it finds would name a verdict A12 did not give, on exactly the text an author is most
 * likely to produce by pasting. One finding per message, in A12's order, is what matches.
 *
 * **The sets are A12's own predicates, not the ISO control blocks.** A line break is LF, CR, U+2028 or
 * U+2029; the encoding question refuses a lone surrogate, which UTF-16 cannot encode; every other character is
 * judged by `\p{C}` ([isUnicodeOther]), which also takes format characters, private use and unassigned code
 * points (`rulekit.validate.laws.ErrorTextCharacterGateLawsTest`). A lone surrogate is in `\p{C}` too, so the
 * ORDER is what names it: kernel 31.1.1 asks the encoding before `\p{C}` on every carrier, where 30.8.1's rule
 * checker asked `\p{C}` first ([KERNEL-FINDINGS KF657]). The line separators are not `\p{C}` and NEL
 * U+0085 is not a line break, so a checker merging the sets names the wrong class on both. A repertoire
 * restriction is a different law with a different carrier: the charset refusal of a supplementary character
 * carries no class at all ([modelTextRepertoireFindings]), and `\p{C}` judges that character as ONE code
 * point, so a pair outside the category draws nothing here.
 *
 * **A refused condition or operation withholds this verdict too**, which is A12 declining to examine the message
 * member at all rather than a rule about parameters. Report assembly applies that, not this family ([KF338]).
 */
internal fun TrackedFactBase.messageTextCharacterFindings(): List<Finding> = declarations().flatMap { element ->
    messageContributions(element) { messageTextCharacterFindings(element, it) }.map { it.finding }
}

internal fun TrackedFactBase.messageTextCharacterFindings(element: TrackedDeclaration, message: AuthoredMessage): List<Finding> =
    listOfNotNull(element.messageTextFinding(message))

/** Both carriers are measured: a computation's message meets the same two classes a rule's does. */
private fun TrackedDeclaration.messageTextFinding(message: AuthoredMessage): Finding? {
    val refusal = message.text.characterRefusal() ?: return null
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        refusal.code,
        "in `errorMessage`${message.localeKey?.let { " for `" + it + "`" } ?: ""}: the text carries " +
            refusal.describe() + ", which A12 refuses — an error message is one line of printable text",
        path,
    )
}

/** A12's three character gates, in the order it asks them. */
internal enum class CharacterGate(val code: String) {
    LINE_BREAK("INVALID_LINEBREAK"),
    ENCODING("WRONG_ENCODING"),
    CONTROL("INVALID_CHARACTERS"),
}

/** Which of A12's ordered character gates refuses a text, and the code point that decided it. */
internal data class CharacterRefusal(val gate: CharacterGate, val codePoint: Int) {
    val code: String get() = gate.code

    /** What the text carries, for a finding's explanation. */
    fun describe(): String = when (gate) {
        CharacterGate.LINE_BREAK -> "the line break U+${codePoint.hex()}"
        CharacterGate.ENCODING -> "the lone surrogate U+${codePoint.hex()}, which UTF-16 cannot encode"
        CharacterGate.CONTROL -> "the control or format character U+${codePoint.hex()}"
    }
}

/**
 * A12's three character gates asked in order, or null when the text passes all three.
 *
 * Shared by every carrier that meets them — a rule's and a computation's message here, a field type's own
 * message in [fieldTypeMessageTextFindings] — because kernel 31.1.1 asks both through one helper,
 * `FehlertextUtil.checkErrorTextAndExtractParameters` (source-read at `eec9134`), so the order and the sets have
 * one statement. Each gate is keyed on the KIND present, not on the first offending character, which is why
 * each is asked of the whole text before the next.
 */
internal fun String.characterRefusal(): CharacterRefusal? {
    firstOrNull { it.isA12LineBreak() }?.let { return CharacterRefusal(CharacterGate.LINE_BREAK, it.code) }
    javaCodePoints().firstOrNull { it in LONE_SURROGATES }?.let { return CharacterRefusal(CharacterGate.ENCODING, it) }
    return javaCodePoints().firstOrNull(::isUnicodeOther)?.let { CharacterRefusal(CharacterGate.CONTROL, it) }
}

/** A code point `javaCodePoints` yields only for a surrogate no partner completes. */
private val LONE_SURROGATES = 0xD800..0xDFFF

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
internal fun String.carriesAnOffendingCharacter(): Boolean = characterRefusal() != null

/** `FehlertextUtil.checkLineSeparators`' four characters, every one of them in the Basic Multilingual Plane. */
private fun Char.isA12LineBreak(): Boolean = code == 0x0A || code == 0x0D || code == 0x2028 || code == 0x2029

/** A code point as `U+` spells it: upper-case hex, at least four digits. */
internal fun Int.hex(): String = toString(16).asciiUppercase().padStart(4, '0')
