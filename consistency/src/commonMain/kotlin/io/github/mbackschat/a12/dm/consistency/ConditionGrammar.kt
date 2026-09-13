package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.incompleteInput
import io.github.mbackschat.a12.dm.consistency.syntax.lexicalFailure
import io.github.mbackschat.a12.dm.consistency.syntax.shapeFailure
import io.github.mbackschat.a12.dm.consistency.syntax.shapeFailureAhead

/**
 * DSL text that does not FORM a condition — A12's last two parse codes.
 *
 * The syntax layer's phases run in A12's own order and the first one to speak owns the text: a member that
 * cannot be tokenized is the lexical family's, one that ends on a dangling operator is the completeness
 * family's, and only what survives both reaches the grammar. Without that ordering the same defect would draw
 * two findings with two codes, and an author would have to guess which one names the fix.
 *
 * **"First" is measured by POSITION, not by phase**, which is why this family also owns text the lexer chokes
 * on: where a token the grammar cannot use sits EARLIER than the bad character, A12's demand-driven lexer
 * never reaches that character and answers `MVK_UNEXPECTED_TOKEN` instead ([KERNEL-FINDINGS KF311]).
 * [shapeFailureAhead] is the arbiter, and it can only move a text between two families that both report it —
 * so a wrong answer there costs a wrong code and never a missed or invented finding.
 *
 * **The parser is deliberately looser than A12's grammar** and the reason is in `shapeFailure`: it
 * checks the shape the productions share rather than each operator's own argument arity, because a
 * per-operator reproduction would be a second catalog and every disagreement would refuse a condition A12
 * admits. So this family is silent about a call with the wrong number of arguments, and says so in the
 * report's gap.
 *
 * **A computation's `operation` is parsed with the expression entry rather than the condition one**, because
 * A12 parses it with `operationSnippet` rather than `bedingungSnippet` — a bare `42.00` is a legal operation
 * and not a legal condition, so running one text through the other's entry would report a defect that is not
 * there.
 */
fun PreparedModel.conditionGrammarFindings(): List<Finding> = elements.flatMap { element ->
    val body = element.body ?: return@flatMap emptyList()
    conditionTexts(element.kind, body).mapNotNull { (member, text) ->
        val entry = syntaxEntryOf(member)
        val lexical = lexicalFailure(text)
        val failure = if (lexical != null) {
            // The lexer stopped somewhere. This family speaks only where the parser stopped EARLIER.
            shapeFailureAhead(text, conditionLanguage, entry, lexical) ?: return@mapNotNull null
        } else {
            // The completeness phase already speaks for a text that ends unfinished.
            if (incompleteInput(text, conditionLanguage) != null) {
                return@mapNotNull null
            }
            if (text.isBlank()) {
                return@mapNotNull blankSlotFinding(element, member)
            }
            shapeFailure(text, conditionLanguage, entry) ?: return@mapNotNull null
        }
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            if (failure.ranOutWithDelimiterOpen) "MVK_EXPECTED_TOKEN_NOT_FOUND" else "MVK_UNEXPECTED_TOKEN",
            if (failure.ranOutWithDelimiterOpen) {
                "`$member` ends at offset ${failure.at} with a bracket still open, so A12's parser reaches " +
                    "the end of the text still waiting for what would close it"
            } else {
                "`$member` carries `${failure.found}` at offset ${failure.at}, which A12's grammar cannot " +
                    "use where it stands"
            },
            element.path,
        )
    }
}

/**
 * What A12 says about a DSL slot that is PRESENT and blank — three different answers on four members.
 *
 * A blank slot reaches A12's parser like any other text and fails there, so this is a parse verdict and not
 * an "empty member" one; the families that own presence deliberately stay out of it
 * (`ComputationAlternativeTest.aBlankPreconditionIsLeftToTheSyntaxFamilyRatherThanCalledEmpty`). What no
 * family could report until now is the parse verdict itself, because a blank text has no token to point at
 * and every shape check returned nothing.
 *
 * **The code is per MEMBER and could not be generalised.** Measured
 * (`rulekit.validate.laws.ComputationAlternativeSlotLawsTest.aBlankSlotAnswersDifferentlyOnEachMemberOfThisCarrier`
 * and `RuleConditionAndTargetSlotLawsTest`): a blank `commonPrecondition` and a blank alternative
 * `precondition` draw `MVK_UNEXPECTED_TOKEN`, a blank `operation` draws `MVK_INCOMPLETE_INPUT`, and a blank
 * `errorCondition` draws `MVK_CONDITION_MISSING` AND `MVK_INCOMPLETE_INPUT`. One rule for "a blank does not
 * parse" would be right about one member and wrong about the others.
 *
 * **`errorCondition` is deliberately not reported here.** Its own family already reports
 * `MVK_CONDITION_MISSING` for the same declaration, so adding the second code A12 gives would hand a consumer
 * two findings for one defect — a report-shape choice, not a coverage one: the defect is surfaced either way.
 */
private fun blankSlotFinding(element: DeclaredElement, member: String): Finding? {
    val code = when {
        member == "errorCondition" -> return null
        member.endsWith("operation") -> "MVK_INCOMPLETE_INPUT"
        else -> "MVK_UNEXPECTED_TOKEN"
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        code,
        "`$member` is present but blank, and A12 parses it like any other text rather than treating it as " +
            "unset — so the slot fails in the parser instead of being skipped",
        element.path,
    )
}
