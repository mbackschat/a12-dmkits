package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
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
internal fun TrackedFactBase.conditionGrammarFindings(element: TrackedDeclaration): List<Finding> {
    val body = element.body ?: return emptyList()
    return conditionTexts(element.kind, body).mapNotNull { (member, text) ->
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
                return@mapNotNull blankSlotFinding(element, member, text)
            }
            val failure = shapeFailure(text, conditionLanguage, entry) ?: return@mapNotNull null
            if (failure.found.isEmpty() && followedInGeneratedText(element.kind, body, member) &&
                !takesTheAddedParenthesis(text, entry)
            ) {
                return@mapNotNull ranIntoTheAddedParenthesis(element, member, text)
            }
            failure
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
            // A parser that ran out with a delimiter open has nothing left to point AT, so the span is the
            // position it stopped rather than a run of characters — an editor places a caret there.
            TextSpan(member, failure.at, failure.at + if (failure.ranOutWithDelimiterOpen) 0 else failure.found.length),
        )
    }
}

/**
 * Whether the `)` A12 adds after a member is a token the parser can take where [text] ran out.
 *
 * It can when the text stopped inside a parenthesis that is ready to close — `FieldFilled(Tier` — and A12
 * then reads on past the member, so the text's own verdict stands. Anywhere else the added `)` is the token
 * A12 cannot use (`FieldFilled([Tier`, `{42.00`, a clause keyword with no operand yet). The appended `\n` is
 * A12's own: the joiner writes one before every `)`, which is what ends a trailing `;;` comment.
 */
private fun TrackedFactBase.takesTheAddedParenthesis(text: String, entry: SyntaxEntry): Boolean {
    val continued = shapeFailure("$text\n)", conditionLanguage, entry) ?: return true
    return continued.at != text.length + 1
}

/** The token class for a member that ran out where A12 reads its added `)`, raised past the member's text. */
private fun ranIntoTheAddedParenthesis(element: TrackedDeclaration, member: String, text: String) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_UNEXPECTED_TOKEN",
    "`$member` ends before it is complete, and A12 parses it inside the one condition it generates for the " +
        "computation — so the next token it meets is the `)` it adds after the member, which it cannot use " +
        "there",
    element.path,
    TextSpan(member, text.length, text.length),
)

/**
 * What A12 says about a DSL slot that is PRESENT and blank — three different answers on four members.
 *
 * A blank slot reaches A12's parser like any other text and fails there, so this is a parse verdict and not
 * an "empty member" one; the families that own presence deliberately stay out of it
 * (`ComputationAlternativeTest.aBlankPreconditionIsLeftToTheSyntaxFamilyRatherThanCalledEmpty`). What no
 * family could report until now is the parse verdict itself, because a blank text has no token to point at
 * and every shape check returned nothing.
 *
 * **The code follows what comes AFTER the member in the text A12 generates, not the member itself.**
 * Measured (`rulekit.validate.laws.ComputationAlternativeSlotLawsTest.aBlankSlotAnswersDifferentlyOnEachMemberOfThisCarrier`,
 * `…ComputationGeneratedTextLawsTest` and `RuleConditionAndTargetSlotLawsTest`): a blank `commonPrecondition`
 * and a blank alternative `precondition` draw `MVK_UNEXPECTED_TOKEN`, because A12 adds a `)` after each
 * ([followedInGeneratedText]); a blank `operation` draws `MVK_INCOMPLETE_INPUT` while it ends the text and the
 * token class beside a sibling alternative; a blank `errorCondition` draws `MVK_CONDITION_MISSING` AND
 * `MVK_INCOMPLETE_INPUT`.
 *
 * **Two members are deliberately not reported here.** `errorCondition`'s own family already reports
 * `MVK_CONDITION_MISSING` for the same declaration, and an `operation`'s missing work — blank, `null` or
 * absent alike — is [computationAlternativeFindings]'. Adding either would hand a consumer two findings for
 * one defect — a report-shape choice, not a coverage one: the defect is surfaced either way.
 */
private fun blankSlotFinding(element: TrackedDeclaration, member: String, text: String): Finding? {
    if (member == "errorCondition" || syntaxEntryOf(member) == SyntaxEntry.OPERATION) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_UNEXPECTED_TOKEN",
        "`$member` is present but blank, and A12 parses it like any other text rather than treating it as " +
            "unset — so the slot fails in the parser instead of being skipped",
        element.path,
        endOfBlankText(member, text),
    )
}
