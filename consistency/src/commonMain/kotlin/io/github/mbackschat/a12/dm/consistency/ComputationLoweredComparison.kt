package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionComparison
import io.github.mbackschat.a12.dm.consistency.syntax.FieldValueRead
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.comparisonsIn
import io.github.mbackschat.a12.dm.consistency.syntax.fieldValueReadsIn
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize
import kotlinx.serialization.json.JsonObject

/**
 * The comparison A12 ends every computation alternative with, `[<target>] != <operation>`.
 *
 * `ComputationAlternativesJoiner` writes it after the alternative's precondition, naming the target relative to
 * the computation's group where the target lies below it and by its absolute path otherwise, and putting the
 * alternative's tolerance operator in place of `!=` where it names a band (source-read). A12 then parses it like
 * any other comparison: the target's `[<target>]` passes the field-value read's gates first, the operation's own
 * reads next, and once the operation is read the comparison walk's kind arms judge the pair. So a copy of one
 * declared kind into another draws the class that pair draws in a rule, a partially known or raw target draws
 * its read class, and a copy between two fields of one kind is admitted on every kind
 * (`adapter.laws.CheckerComputationJoinedConditionAgreementTest`, [KERNEL-FINDINGS KF483]).
 *
 * [targetRead] is spanned empty at the operation's first token and [comparison] over the whole operation, which
 * is where each is raised: the target is read before the operation is, and the comparison once it has been.
 *
 * **[comparison] is absent where the operation is ONE field-value read its gates refuse.** In A12's text the
 * comparison opens at `[<target>]`, ahead of the operation, so that read is the inner production and answers
 * first — but within the operation's own text the two spans coincide, and report assembly keeps both of a
 * coinciding pair. Every other class of a typed side is spanned strictly inside its brackets, and a side the
 * comparison families do not type draws nothing from them, so this is the one pair to settle here
 * (`adapter.laws.CheckerComputationJoinedConditionAgreementTest`, a Number from a partially known Date).
 */
internal class LoweredComparison(val targetRead: FieldValueRead, val comparison: ConditionComparison?)

/**
 * The comparison [member] of [element] is lowered to, or null where it lowers to none this reader can spell.
 *
 * Null for every member but an operation, for an operation with no token, for a target that does not resolve,
 * and for a band A12 cannot bind — in the last two A12 never judges the model, and the families that say so
 * answer instead.
 */
internal fun TrackedFactBase.loweredComparisonOf(
    element: TrackedDeclaration,
    body: JsonObject,
    member: String,
    text: String,
): LoweredComparison? {
    if (element.declaredKind != DeclaredKind.COMPUTATION || !member.endsWith("operation")) {
        return null
    }
    val tokens = tokenize(text).filter { it.kind != TokenKind.COMMENT }
    val first = tokens.firstOrNull() ?: return null
    val target = computedTargetOf(element) ?: return null
    val operator = alternativeOf(body, member)?.loweredComparisonOperator(conditionLanguage) ?: return null
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val written = target.removePrefix("$group/")
    val start = first.start
    val end = tokens.last().end
    val wholeRead = fieldValueReadsIn(text, conditionLanguage).singleOrNull { it.at == start && it.end == end }
    val answeredByTheRead = wholeRead != null && fieldValueReadIsRefused(wholeRead, group, element)
    return LoweredComparison(
        FieldValueRead(written, start, start, inAFilterBody = false),
        if (answeredByTheRead) null else comparisonOfTheJoinedText(written, operator, text, start, end),
    )
}

/**
 * The comparison A12 reads from `[<target>] <operator> <operation>`, placed in the operation's own text, or null
 * where that text does not read as one comparison spanning the whole operation.
 *
 * Read through the same shape parser as an authored comparison rather than assembled from the parts, so the
 * operation must parse as that comparison's right side: an operation left with a `[` open never completes the
 * comparison, and A12 answers it with the token class it meets past the member instead
 * (`consistency.ComputationGeneratedTextTest`).
 */
private fun TrackedFactBase.comparisonOfTheJoinedText(
    written: String,
    operator: String,
    text: String,
    start: Int,
    end: Int,
): ConditionComparison? {
    val left = "[$written]"
    val prefix = "$left $operator "
    val shift = prefix.length - start
    val joined = comparisonsIn(prefix + text.substring(start), conditionLanguage, SyntaxEntry.CONDITION)
        .firstOrNull { it.start == 0 && it.left == left } ?: return null
    if (joined.end - shift != end) {
        return null
    }
    return ConditionComparison(left, joined.operator, start, joined.right, start, end)
}

/** Every comparison A12 judges in [member] of [element]: those written there, then the one it is lowered to. */
internal fun TrackedFactBase.comparisonsOf(
    element: TrackedDeclaration,
    body: JsonObject,
    member: String,
    text: String,
): List<ConditionComparison> = comparisonsIn(text, conditionLanguage, syntaxEntryOf(member)) +
    listOfNotNull(loweredComparisonOf(element, body, member, text)?.comparison)

/**
 * The absolute path [element] computes into, absent when it is no computation or its member does not resolve.
 *
 * Read through the declaration's own reference, which charges this declaration alone: every family judging a
 * computation's lowered comparison asks it, and the whole-model list would make each of their cells depend on
 * every declaration's body.
 */
internal fun computedTargetOf(element: TrackedDeclaration): String? = element.declaredReference()
    ?.takeIf { it.role == ReferenceRole.COMPUTED_TARGET }
    ?.let { (it.target as? Reference.Resolved)?.path }
