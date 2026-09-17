package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.namesKeyword

/**
 * The predicates a COMPUTATION's precondition may not use.
 *
 * A12's calculation module refuses three language features in a calculation rule's condition — the legacy
 * count-given op, an application condition (`CustomCondition`), and `RepetitionNotUnique` — reporting
 * `MVK_ERROR_INVALID_LANGUAGE_FEATURE` with the offending predicate's own spelling
 * ([KERNEL-FINDINGS KF333], `rulekit.validate.laws.ComputationPreconditionLanguageFeatureLawsTest`).
 *
 * **The RULE slot is the control that makes this a computation law.** Both predicates are ordinary in a rule
 * condition, measured — so a checker refusing them wherever it met them would refuse a legal model.
 *
 * **It is the PRECONDITIONS, not the operation — and that is measured, not obvious.** The precondition and
 * the operation are two strings on the same alternative, read by the same code; A12 answers the same
 * predicate in the operation with `MVK_UNEXPECTED_TOKEN` instead, because an operation is not a condition.
 * A family judging both slots alike would therefore answer with the wrong vocabulary, not merely too often.
 *
 * **Two of A12's three features are reported, and the third is a stated boundary rather than an oversight.**
 * The count-given op has no English spelling — it is a DE-only uncataloged keyword — so this family has no
 * measurement for it and does not report it. Silence there under-reports, which is safe; reporting a refusal
 * with no measured basis is the one thing this project forbids.
 */
internal fun TrackedFactBase.computationPreconditionFeatureFindings(element: TrackedDeclaration): List<Finding> {
    if (element.kind != "Computation") {
        return emptyList()
    }
    val spellings = CONDITION_OPERATOR_KEYWORDS.getValue(conditionLanguage)
    val prohibited = PROHIBITED_IN_A_PRECONDITION.map { spellings.getValue(it) }.toSet()
    val body = element.body ?: return emptyList()
    return conditionTexts(element.kind, body)
        .filter { (member, _) -> syntaxEntryOf(member) == SyntaxEntry.CONDITION }
        .mapNotNull { (member, text) ->
            val used = namesKeyword(text, prohibited) ?: return@mapNotNull null
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_ERROR_INVALID_LANGUAGE_FEATURE",
                "in `$member`: `$used` is prohibited in a computation's precondition — A12 normalizes a " +
                    "calculation rule's condition and refuses this predicate there. The same predicate " +
                    "in a RULE condition is ordinary",
                element.path,
            )
        }
}

/**
 * The catalog ids this family reports, which is TWO of A12's three prohibited features.
 *
 * The third — the legacy count-given op — is absent deliberately: it has no English spelling and no catalog
 * id, so there is no measurement behind it here. See this file's KDoc.
 */
private val PROHIBITED_IN_A_PRECONDITION = setOf("CustomCondition", "RepetitionNotUnique")
