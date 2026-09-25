package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.FORM_CONSTRUCTS
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

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
 * **One measured shape is not reported, and is published as a [CoverageGap] instead.** The count operation
 * inside a rule CONDITION — `AnzahlAngegebenerVordrucke(X) > 0` — is invisible to the shared shape reader:
 * `conditionPart` treats any UNCATALOGED call as a complete condition, so the comparison after it is leftover
 * and the whole text yields no calls at all. Widening that predicate would change what every call-reading
 * family sees and is its own slice; naming the shape here costs nothing and keeps the silence disclosed.
 */
internal fun TrackedFactBase.hModelFormConstructFindings(): List<Finding> {
    if (conditionLanguage != ConditionLanguage.DE) {
        return emptyList()
    }
    return declarations().mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .firstOrNull { it.operator in FORM_CONSTRUCTS }
                ?.let { call ->
                    Finding(
                        FindingCategory.A12_CONSTRAINT,
                        FindingSeverity.ERROR,
                        "MVK_INVALID_ROOT_GROUP_CONSTR",
                        "in `$member`: `${call.operator}` is an H-model form construct and A12 refuses it by " +
                            "name in an A12 model — use the corresponding group construct instead",
                        element.path,
                    )
                }
        }
    }
}

/**
 * The declarations whose text names a form construct this reader could not parse into calls.
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
            if (callsIn(text, conditionLanguage, syntaxEntryOf(member)).isNotEmpty()) {
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
