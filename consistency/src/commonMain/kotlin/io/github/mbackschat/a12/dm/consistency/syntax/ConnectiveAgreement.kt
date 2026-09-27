package io.github.mbackschat.a12.dm.consistency.syntax

/** KF301's mixed-connective check runs after the third or later condition part completes. */
fun connectiveDisagreement(text: String, language: ConditionLanguage): ConnectiveDisagreement? =
    ConditionRecognizer(text, language).recognize(SyntaxEntry.CONDITION).sites
        .filterIsInstance<SyntaxSite.Connective>().firstNotNullOfOrNull { it.disagreement() }

internal fun SyntaxSite.Connective.disagreement(): ConnectiveDisagreement? =
    if (first.text.equals(current.text, ignoreCase = true)) null
    else ConnectiveDisagreement(first.text, current.text, current.start, end)

/** The second connective and completed following part form the editor's corrective range. */
data class ConnectiveDisagreement(val first: String, val second: String, val at: Int, val end: Int)
