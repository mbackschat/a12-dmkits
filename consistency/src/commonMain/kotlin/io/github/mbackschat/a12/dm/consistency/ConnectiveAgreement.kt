package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.connectiveDisagreement

/**
 * `And` and `Or` mixed inside one bracket pair — A12's `MVK_BRACKET_MISSING`.
 *
 * The third and last grammar-free syntax family, and the only one of the three whose verdict does not depend
 * on where A12's parser stands when it fails: the refusal is a semantic action on the `bedingung` context, and
 * the token stream already delimits that context ([KERNEL-FINDINGS KF301]). The two families beside it were
 * each narrowed or withdrawn on exactly that dependence.
 *
 * **What it does not claim.** It says nothing about which of the two connectives the author meant — A12 does
 * not guess either, which is why the code exists — and nothing about text where a connective is spelled in a
 * case the keyword table does not carry, which it cannot see. Both are silence rather than a wrong verdict.
 */
internal fun TrackedFactBase.connectiveAgreementFindings(element: TrackedDeclaration): List<Finding> {
    val body = element.body ?: return emptyList()
    return conditionTexts(element.kind, body).mapNotNull { (member, text) ->
        connectiveDisagreement(text, conditionLanguage)?.let { mixed ->
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_BRACKET_MISSING",
                "`$member` joins parts with `${mixed.first}` and then with `${mixed.second}` at offset " +
                    "${mixed.at} inside the same brackets. A12 gives the two no precedence and refuses to " +
                    "guess one, so the grouping has to be written: bracket whichever pair belongs together",
                element.path,
                TextSpan(member, mixed.at, mixed.end),
            )
        }
    }
}
