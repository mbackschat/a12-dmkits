package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.CallShape
import io.github.mbackschat.a12.dm.consistency.syntax.CategoryAccess
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionComparison
import io.github.mbackschat.a12.dm.consistency.syntax.SemanticIndexUse
import io.github.mbackschat.a12.dm.consistency.syntax.Token

/**
 * Editor extent of the construct whose check failed, independent of its reached execution site (R5).
 *
 * ConditionClassWithholdingLawsTest distinguishes argument completion from enclosing-call completion;
 * ConditionExecution carries that order, while these projections keep the useful text underlined.
 */
internal fun ConditionCall.spanIn(member: String) = TextSpan(member, at, end)

/** An argument whose own production owns the gate; see [ConditionCall.spanIn]. */
internal fun CallOperand.spanIn(member: String) = TextSpan(member, at, end)

/** A comparison, whose gates `checkVergleichsBedingung` raises once its right side is read. */
internal fun ConditionComparison.spanIn(member: String) = TextSpan(member, start, end)

/** The index's editor extent includes its keyword and key; execution order belongs to its reached site. */
internal fun SemanticIndexUse.spanIn(member: String) = TextSpan(member, keywordAt, end)

/** A recognized call retains its full editor extent. */
internal fun CallShape.spanIn(member: String) = TextSpan(member, at, end)

/** A `->` category access, whose classes the entity spec's check raises once the category name is read. */
internal fun CategoryAccess.spanIn(member: String) = TextSpan(member, start, end)

/** A production that is one token, which its check raises once that token is read. */
internal fun Token.spanIn(member: String) = TextSpan(member, start, end)

/** A blank text, whose class A12's parser raises where it runs out of input: at the text's end. */
internal fun endOfBlankText(member: String, text: String) = TextSpan(member, text.length, text.length)
