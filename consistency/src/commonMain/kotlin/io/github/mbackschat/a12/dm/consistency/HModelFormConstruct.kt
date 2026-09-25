package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.FORM_CONSTRUCTS
import io.github.mbackschat.a12.dm.consistency.syntax.HAVING
import io.github.mbackschat.a12.dm.consistency.syntax.Token
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.connectiveSpellingsOf
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize

/**
 * The legacy FORM constructs, which an A12 model reaches and is refused by name for using.
 *
 * A12 rejects all seven with `MVK_INVALID_ROOT_GROUP_CONSTR` — "only allowed to be used in H models" — and
 * the rejection is the first statement of the checks that own them: `CheckFormularBedingungImpl`'s
 * `checkFormularBedingungName` and `CheckAnzahlAngegebenOpHModelImpl.check` each open with an
 * `isA12Model()` throw, so nothing else about the construct is ever asked ([KERNEL-FINDINGS
 * KF282](../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf282), `rulekit.validate.laws.HModelFormConditionReachabilityLawsTest`).
 *
 * **Only a GERMAN-language model reaches it, and that is the whole subtlety.** These seven spellings exist in
 * `LexerTerminals_de` and in no English bundle at all, so in an `en_US` model they are ordinary names and the
 * PARSER refuses the text with `MVK_UNEXPECTED_TOKEN` before any checker runs — a different class, owned by
 * the grammar family. Measuring this law on an English model cannot tell "refused at the parser" from
 * "reached the checker", which is how it was first recorded the wrong way round and corrected.
 *
 * **The seven are the measured denominator, not every German-only terminal.** The German bundle carries
 * thirteen spellings the English one lacks, and the ROW constructs among them behave differently:
 * `einfacheBedingung` predicate-gates `zeilenBedingung` on `isHModel()`, so `ZeilenLueckenlos(…)` is refused
 * at the parser even in German. Listing those here would name this class on a refusal that is not it.
 * `AktuelleVordruckNummer`, `AnzahlAngegebeneUsb` and `MaximalAngegebenerZeilenindex` are unmeasured on this
 * axis and are left out for the same reason — under-reporting, which is the safe direction.
 *
 * **Both carriers.** A computation's operation parses through a different entry rule than a rule's condition
 * and agrees, measured; this family reads whichever member its element declares.
 *
 * **Where the class is raised.** A form condition's is raised by `checkFormularBedingungName`, the action on
 * the construct's NAME, so ahead of anything in its argument list; the count operation's by its own check,
 * once its call is read. Each finding spans exactly that much, which is what orders it against the rest of
 * the text.
 *
 * **The count operation inside a rule CONDITION is read, but not as A12 reads it.** `conditionPart` treats
 * an UNCATALOGED call as a complete condition, so the `> 0` after `AnzahlAngegebenerVordrucke(X)` is a
 * token the shared shape reader cannot use. The call is still read to its end first, so this class is
 * reported and the grammar class behind it withheld.
 *
 * **A form CONDITION needs no call at all**, because its class is raised before its argument list is read:
 * with no parentheses, unterminated, or cut short by a grammar error inside them, A12 names the construct
 * (`rulekit.validate.laws.HModelFormConditionReachabilityLawsTest`
 * `.aFormConditionIsRaisedOnItsNameBeforeItsArgumentsAreRead`).
 * So its name is read off the tokens, where it opens a condition — the text's start, after a connective or
 * a filter keyword, or inside a grouping parenthesis; elsewhere the parser refuses the keyword itself. Only
 * the count operation, whose class needs its call, is published as a [CoverageGap] when a parse failure cuts
 * that call short.
 */
internal fun TrackedFactBase.hModelFormConstructFindings(): List<Finding> {
    if (conditionLanguage != ConditionLanguage.DE) {
        return emptyList()
    }
    return declarations().mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            val counted = callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator == COUNT_CONSTRUCT }
                .map { call -> call.operator to call.spanIn(member) }
            val named = formConditionNamesIn(text).map { name -> name.text to name.spanIn(member) }
            (counted + named).minByOrNull { (_, span) -> span.start }?.let { (construct, span) ->
                Finding(
                    FindingCategory.A12_CONSTRAINT,
                    FindingSeverity.ERROR,
                    "MVK_INVALID_ROOT_GROUP_CONSTR",
                    "in `$member`: `$construct` is an H-model form construct and A12 refuses it by name in an " +
                        "A12 model — use the corresponding group construct instead",
                    element.path,
                    span,
                )
            }
        }
    }
}

/**
 * The form-condition keywords in [text] that open a condition, in text order: A12's action on the name raises
 * the class there, before the argument list is read, so no call has to be.
 */
private fun TrackedFactBase.formConditionNamesIn(text: String): List<Token> {
    val openers = connectiveSpellingsOf(conditionLanguage) +
        setOfNotNull(CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(HAVING))
    val tokens = tokenize(text).filter { it.kind != TokenKind.COMMENT }
    return tokens.filterIndexed { at, token ->
        token.kind == TokenKind.NAME && token.text in FORM_CONSTRUCTS && token.text != COUNT_CONSTRUCT &&
            tokens.opensACondition(at, openers)
    }
}

/** Whether the token at [at] stands where a condition begins, which is where the grammar reads the construct. */
private fun List<Token>.opensACondition(at: Int, openers: Set<String>): Boolean {
    val before = getOrNull(at - 1) ?: return true
    return when {
        before.kind == TokenKind.NAME -> before.text in openers
        // A grouping parenthesis, and not a call's: a call's opens behind the name it calls, while a connective
        // or a filter keyword is spelled as a name too and opens a condition, a grouping one included.
        before.text == "(" -> getOrNull(at - 2)?.let { it.kind == TokenKind.NAME && it.text !in openers } != true
        else -> false
    }
}

/** The one construct that is an operation, whose class its own check raises once the call is read. */
private const val COUNT_CONSTRUCT = "AnzahlAngegebenerVordrucke"

/**
 * The declarations whose text names a form construct this reader could not parse into a call.
 *
 * Per DECLARATION, because that is what a consumer needs. The text test is a whole-word scan and would be
 * the wrong instrument for a FINDING — it cannot tell a keyword from the same letters inside a string — but
 * it is the right one for a disclosure, where naming a declaration A12 might refuse costs only an extra line
 * of honesty.
 */
internal fun TrackedFactBase.hModelFormConstructGaps(): List<CoverageGap> {
    if (conditionLanguage != ConditionLanguage.DE) {
        return emptyList()
    }
    return declarations().mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            val named = FORM_CONSTRUCTS.firstOrNull { text.namesWholeWord(it) } ?: return@firstNotNullOfOrNull null
            if (callsIn(text, conditionLanguage, syntaxEntryOf(member)).any { it.operator in FORM_CONSTRUCTS } ||
                formConditionNamesIn(text).isNotEmpty()
            ) {
                return@firstNotNullOfOrNull null
            }
            CoverageGap(
                element.path,
                "this declaration's `$member` names the H-model form construct `$named` in a shape this " +
                    "reader cannot parse into calls, so A12 alone judges it",
            )
        }
    }
}

/** Whether the text carries [word] with no identifier character on either side. */
private fun String.namesWholeWord(word: String): Boolean {
    var from = indexOf(word)
    while (from >= 0) {
        val before = getOrNull(from - 1)
        val after = getOrNull(from + word.length)
        if (!before.isIdentifierPart() && !after.isIdentifierPart()) {
            return true
        }
        from = indexOf(word, from + 1)
    }
    return false
}

private fun Char?.isIdentifierPart(): Boolean = this != null && (isLetterOrDigit() || this == '_')
