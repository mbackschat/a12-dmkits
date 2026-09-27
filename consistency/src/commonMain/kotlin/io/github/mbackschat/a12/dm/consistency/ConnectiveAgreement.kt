package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite
import io.github.mbackschat.a12.dm.consistency.syntax.disagreement

/** KF301 judges each recognized condition context; sibling filters never share a connective. */
internal fun TrackedFactBase.connectiveAgreementFindings(element: TrackedDeclaration): List<Finding> =
    connectiveContributions(element).map { it.finding }

internal fun TrackedFactBase.connectiveContributions(element: TrackedDeclaration): List<ConditionFinding> {
    val execution = conditionExecution(element) ?: return emptyList()
    return execution.recognition.sites.filterIsInstance<SyntaxSite.Connective>().mapNotNull { site ->
        val mixed = site.disagreement() ?: return@mapNotNull null
        val span = execution.carrier.semanticSpan(mixed.at, mixed.end) ?: return@mapNotNull null
        ConditionFinding(element.key, site, Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_BRACKET_MISSING",
            "`${span.member}` joins parts with `${mixed.first}` and then with `${mixed.second}` at offset " +
                "${span.start} inside the same brackets. A12 gives the two no precedence and refuses to " +
                "guess one, so the grouping has to be written: bracket whichever pair belongs together",
            element.path,
            span,
        ))
    }
}
