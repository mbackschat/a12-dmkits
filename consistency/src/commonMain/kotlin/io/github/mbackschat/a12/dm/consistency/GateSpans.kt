package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.CallShape
import io.github.mbackschat.a12.dm.consistency.syntax.CategoryAccess
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionComparison
import io.github.mbackschat.a12.dm.consistency.syntax.SemanticIndexUse
import io.github.mbackschat.a12.dm.consistency.syntax.Token

/**
 * The span of an operator's gate: the characters of the grammar production whose action raises it.
 *
 * A12's condition grammar runs each check as an action at the END of its production, so a gate is raised once
 * that production has been read, and report assembly orders it at its span's end against the other classes
 * of the text ([withoutDefectsBehindAnEarlierOne]). WHICH production owns a gate is the kernel's, read per code
 * in its `kernel-core-parser`: an operator's `Check…Impl` runs at the call's closing parenthesis, a
 * comparison's at the end of its right side, and a date slot's `checkDatumAngabe` or an entity spec's
 * `checkEntitySpec` at the end of that one argument. The two ends are measured apart: a date slot's gate is
 * raised ahead of a bad name in the next argument, an arity gate behind it
 * (`rulekit.validate.laws.ConditionClassWithholdingLawsTest.aGateIsRaisedWhereItsOwnProductionEnds`).
 */
internal fun ConditionCall.spanIn(member: String) = TextSpan(member, at, end)

/** An argument whose own production owns the gate; see [ConditionCall.spanIn]. */
internal fun CallOperand.spanIn(member: String) = TextSpan(member, at, end)

/** A comparison, whose gates `checkVergleichsBedingung` raises once its right side is read. */
internal fun ConditionComparison.spanIn(member: String) = TextSpan(member, start, end)

/**
 * A semantic index, whose classes `checkSemanticIndex` raises once its key's production is read — spanned
 * from the keyword, so the span encloses the key's own and a gate of the key's spec is placed first where
 * the two end together ([SemanticIndexUse.keywordAt]).
 */
internal fun SemanticIndexUse.spanIn(member: String) = TextSpan(member, keywordAt, end)

/** A call read by the shape parser, whose gates its own production raises at the call's end. */
internal fun CallShape.spanIn(member: String) = TextSpan(member, at, end)

/** A `->` category access, whose classes the entity spec's check raises once the category name is read. */
internal fun CategoryAccess.spanIn(member: String) = TextSpan(member, start, end)

/** A production that is one token, which its check raises once that token is read. */
internal fun Token.spanIn(member: String) = TextSpan(member, start, end)

/** A blank text, whose class A12's parser raises where it runs out of input: at the text's end. */
internal fun endOfBlankText(member: String, text: String) = TextSpan(member, text.length, text.length)
