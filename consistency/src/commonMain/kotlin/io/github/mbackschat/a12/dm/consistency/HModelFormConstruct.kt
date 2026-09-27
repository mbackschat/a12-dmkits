package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.FORM_CONSTRUCTS
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite

/** KF282 distinguishes the form-name action from the count's completed-call action, in German only. */
internal fun TrackedFactBase.hModelFormConstructFindings(): List<Finding> =
    declarations().flatMap { hModelFormContributions(it).map { contribution -> contribution.finding } }

internal fun TrackedFactBase.hModelFormContributions(element: TrackedDeclaration): List<ConditionFinding> {
    if (conditionLanguage != ConditionLanguage.DE) return emptyList()
    val execution = conditionExecution(element) ?: return emptyList()
    val calls = execution.recognition.calls.associateBy { it.at }
    return execution.recognition.sites.filterIsInstance<SyntaxSite.Completion>().mapNotNull { site ->
        val construct = when (site.kind) {
            SyntaxSite.Kind.FORM_NAME -> execution.carrier.text.substring(site.start, site.end)
            SyntaxSite.Kind.CALL -> calls[site.start]?.operator?.takeIf { it == COUNT_CONSTRUCT }
            else -> null
        } ?: return@mapNotNull null
        val span = execution.carrier.semanticSpan(site.start, site.end) ?: return@mapNotNull null
        ConditionFinding(element.key, site, Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_INVALID_ROOT_GROUP_CONSTR",
            "in `${span.member}`: `$construct` is an H-model form construct and A12 refuses it by name in an " +
                "A12 model — use the corresponding group construct instead",
            element.path,
            span,
        ))
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
        val reachedMembers = hModelFormContributions(element).mapNotNull { it.finding.span?.member }.toSet()
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            val named = FORM_CONSTRUCTS.firstOrNull { text.namesWholeWord(it) } ?: return@firstNotNullOfOrNull null
            if (member in reachedMembers) {
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
