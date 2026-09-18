package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

/**
 * `SumOfProducts`' operand PAIR, whose four laws are one kernel method each and run in a fixed order.
 *
 * The operator multiplies two parallel lists element-wise, so A12 demands that both operands' fields share
 * one OWNING group and that each reference star exactly the lowest repeatable level on its path
 * ([KERNEL-FINDINGS KF370], `rulekit.validate.laws.SumOfProductsLawsTest`). The Java builder has refused
 * these shapes since it was written; a stored model reaches none of that, which is what this family is for.
 *
 * **Four classes, and which one an offending pair draws is the whole difficulty.** They are ordered here as
 * `CheckFeldListenOpImpl.check` calls them, because three of the four cells that break two laws at once were
 * predicted wrongly from the laws alone:
 *
 * 1. the fields' owning groups differ — `MVK_DIFFERENT_GROUPS`, and it outranks everything below it;
 * 2. an operand carries a `Having` filter — `MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED`, because
 *    `isWildcardRestricted` reads a filter as restricting EVERY level, so the lowest one fails first;
 * 3. an operand stars two levels — `MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED`;
 * 4. neither operand has a repeatable level at all — `MVK_REPEATABLE_LEVEL_REQUIRED`.
 *
 * **The filter is the only way to reach the second class**, which is why it is not the obvious "a star above
 * the lowest level" shape its name suggests: a plainly missing star is answered by the reference reader with
 * `MVK_NO_WILDCARD` long before this operator's own gates run.
 *
 * **Silent unless the reference reader is demonstrably silent.** Each operand must resolve to a FIELD inside
 * the declaring group and star exactly the repeatable levels between the two, or A12's answer is
 * `MVK_NO_WILDCARD` / `MVK_INVALID_WILDCARD` / `MVK_INVALID_ENTITY` and belongs to another family. Silent
 * too on an identical starless pair and on a starred filter, which A12 answers with `MVK_DUPLICATE_PARAM1`
 * and `MVK_NO_WILDCARDS_ALLOWED` — measured, both ahead of every gate here.
 */
internal fun TrackedFactBase.sumOfProductsOperandFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val operator = keywords[SUM_OF_PRODUCTS] ?: return emptyList()
    val filterKeyword = keywords[HAVING] ?: return emptyList()
    return declarations().flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator == operator }
                .mapNotNull { call ->
                    val pair = call.operands.map { readOperand(it, group, element, filterKeyword) }
                    if (pair.size != 2 || pair.any { it == null }) {
                        return@mapNotNull null
                    }
                    classOf(pair[0]!!, pair[1]!!)?.let { finding(it, element, member) }
                }
        }
    }
}

private const val SUM_OF_PRODUCTS = "SumOfProducts"
private const val HAVING = "Having"

/** One operand reduced to what the four laws read, or null when this family may not judge the call. */
private class ParallelOperand(val owningGroup: String, val stars: Int, val filtered: Boolean, val at: String)

/**
 * Reads one operand, returning null on every shape whose verdict is another gate's.
 *
 * The star COUNT has to match the repeatable levels between the declaring group and the field exactly: one
 * too few and A12 answers `MVK_NO_WILDCARD`, one too many and it answers `MVK_INVALID_WILDCARD`, and both
 * run in the reference reader ahead of this operator. Levels are counted from the declaring group rather
 * than from the model root, because the rule's own context binds everything at or above it — the fixture
 * that made this concrete has a REPEATABLE root group, where root-relative counting refuses a legal pair.
 */
private fun TrackedFactBase.readOperand(
    operand: CallOperand,
    declaringGroup: String,
    element: TrackedDeclaration,
    filterKeyword: String,
): ParallelOperand? {
    val written = operand.base ?: return null
    val filters = operand.clauses.filter { it.keyword == filterKeyword }
    if (filters.size != operand.clauses.size || filters.any { it.text.contains('*') }) {
        return null
    }
    val resolved = resolveEntity(declaringGroup, written.replace("*", ""), element.origin)
        as? Reference.Resolved ?: return null
    val field = declarationAt(resolved.path, DeclaredKind.FIELD)
        ?: return null
    if (!field.path.startsWith("$declaringGroup/")) {
        return null
    }
    val stars = written.count { it == '*' }
    if (stars != repeatableLevelsBetween(declaringGroup, field.path)) {
        return null
    }
    return ParallelOperand(
        field.path.substringBeforeLast('/'),
        stars,
        filters.isNotEmpty(),
        field.path,
    )
}

/** The repeatable groups strictly between [declaringGroup] and [fieldPath] — one star each is what A12 wants. */
private fun TrackedFactBase.repeatableLevelsBetween(declaringGroup: String, fieldPath: String): Int =
    repeatableGroups().count {
        it.path.startsWith("$declaringGroup/") && fieldPath.startsWith("${it.path}/")
    }

/** A12's class for this pair, in its own gate order, or null when the pair is legal. */
private fun classOf(left: ParallelOperand, right: ParallelOperand): String? = when {
    left.owningGroup != right.owningGroup -> "MVK_DIFFERENT_GROUPS"
    left.filtered || right.filtered -> "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED"
    left.stars > 1 || right.stars > 1 -> "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED"
    // An identical starless pair is A12's `MVK_DUPLICATE_PARAM1`, which runs ahead of this gate.
    left.stars == 0 && right.stars == 0 && left.at != right.at -> "MVK_REPEATABLE_LEVEL_REQUIRED"
    else -> null
}

private fun finding(code: String, element: TrackedDeclaration, member: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    "in `$member`: `$SUM_OF_PRODUCTS` multiplies two parallel repetition lists, so both operands must name " +
        "fields of ONE group and each must star that group's lowest repeatable level — " +
        ADVICE.getValue(code),
    element.path,
)

/** What the author has to change, which differs per class though the shape is one pair. */
private val ADVICE = mapOf(
    "MVK_DIFFERENT_GROUPS" to "these two name fields of different groups",
    "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED" to
        "a `$HAVING` filter restricts the star, so the lowest level is left unquantified",
    "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED" to "one of them stars more than the lowest level",
    "MVK_REPEATABLE_LEVEL_REQUIRED" to "neither of them has a repeatable level to iterate",
)
