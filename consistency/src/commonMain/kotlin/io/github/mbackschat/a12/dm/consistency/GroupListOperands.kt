package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

/**
 * A group-list quantifier that names the same group twice, or names a group and something under it.
 *
 * A12 refuses both, with two different codes: `MVK_DUPLICATE_PARAM1` for the same group twice and
 * `MVK_DUPLICATE_PARAM2` when one operand CONTAINS another ([KERNEL-FINDINGS KF325],
 * `rulekit.validate.laws.GroupListDuplicateReachLawsTest`). Both classes hold on all six carriers that take
 * a group list, and neither depends on where the error field sits.
 *
 * **The gate reads ONE CALL's operand list, which is why this family parses rather than scans.** The same
 * group named once in each of two calls is admitted, and no reading of a condition's flat set of names can
 * tell that from a group named twice in one. That is the capability [callsIn] exists for.
 *
 * **Identity is the RESOLVED element, and containment is by path STEP.** A relative spelling beside the
 * absolute spelling of the same group is an exact duplicate, so comparing authored text would miss it; and
 * `Garage` does not contain `GarageBay`, so comparing text prefixes would refuse a model A12 admits. Both
 * rows are measured.
 *
 * **Three shapes leave it silent, each measured rather than assumed.** A STARRED operand is skipped by the
 * gate — one repeatable group named twice with its wildcard is ADMITTED, so comparing every operand would be
 * an over-rejection. An operand that does not RESOLVE pre-empts the gate, A12 reporting the entity and
 * nothing about the duplicate, so reporting here would name two fixes for one typo. And an operand this
 * reader cannot reduce to one path — a nested call, an expression, a filtered reference — is compared
 * against nothing.
 *
 * **One more silence, and it is the price of parsing.** A12's duplicate gate runs before BOTH syntax phases:
 * a duplicate inside an ungrammatical or unlexable condition draws the duplicate class where the same text
 * without the duplicate draws the parse or lexer code. [callsIn] yields nothing for text it cannot parse, so
 * this family says nothing there — an under-report, bounded by the two families that already report the
 * syntax defect, and the alternative is reading an argument list out of tokens A12's own lexer never saw.
 *
 * **The boundary: only the group list.** A12 draws the same two codes on field lists and value lists, and
 * that is a different measurement on a different carrier; nothing here is claimed about them.
 */
fun PreparedModel.groupListOperandFindings(): List<Finding> {
    val carriers = GROUP_LIST_OPERATORS.mapNotNull { CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(it) }.toSet()
    return elements.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator in carriers }
                .mapNotNull { call -> duplicateFinding(call, group, element, member) }
        }
    }
}

/** The six operators whose argument list is a list of GROUPS, named by their catalog ids. */
private val GROUP_LIST_OPERATORS = setOf(
    "AllGroupsFilled",
    "NotAllGroupsFilled",
    "GroupsNotCollectivelyFilled",
    "AtLeastOneGroupFilled",
    "NoGroupFilled",
    "NumberOfFilledGroups",
)

private fun PreparedModel.duplicateFinding(
    call: ConditionCall,
    declaringGroup: String,
    element: DeclaredElement,
    member: String,
): Finding? {
    val bound = call.operands.map { it to it.resolvedBy(this, declaringGroup, element.origin) }
    // A12 reports the entity and says nothing about the duplicate, so this family has nothing to add.
    if (bound.any { (operand, reference) -> operand.path != null && reference !is Reference.Resolved }) {
        return null
    }
    val comparable = bound.mapNotNull { (operand, reference) ->
        val path = (reference as? Reference.Resolved)?.path
        // A starred operand is outside the gate — the measured row is `NumberOfFilledGroups(G*, G*)`, which
        // A12 ADMITS. Comparing it would refuse that model.
        if (path == null || operand.path?.contains('*') == true) null else path
    }
    // The exact duplicate wins over an overlap in the same call whatever the authored order, as A12 does.
    exactDuplicate(comparable)?.let { repeated ->
        return finding(
            "MVK_DUPLICATE_PARAM1",
            "this $member names `$repeated` twice in one `${call.operator}` — A12 refuses a group list that " +
                "repeats an operand, whichever way each occurrence is spelled",
            element.path,
        )
    }
    overlap(comparable)?.let { (outer, inner) ->
        return finding(
            "MVK_DUPLICATE_PARAM2",
            "this $member names `$outer` and `$inner` in one `${call.operator}`, and the second lies under " +
                "the first — A12 refuses a group list whose operands contain one another",
            element.path,
        )
    }
    return null
}

private fun CallOperand.resolvedBy(
    model: PreparedModel,
    declaringGroup: String,
    origin: DeclarationOrigin,
): Reference? = path?.let { model.resolveEntity(declaringGroup, it, origin) }

private fun finding(code: String, explain: String, at: String) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at)

/** The element the list names twice, in authored order — one name, since both occurrences resolve to it. */
private fun exactDuplicate(paths: List<String>): String? = paths.firstPair { a, b -> a == b }?.first

/** The first pair where one operand lies under the other, outer first — by path STEP, never by text. */
private fun overlap(paths: List<String>): Pair<String, String>? =
    paths.firstPair { a, b -> b.startsWith("$a/") || a.startsWith("$b/") }
        ?.let { (a, b) -> if (b.startsWith("$a/")) a to b else b to a }

private inline fun List<String>.firstPair(related: (String, String) -> Boolean): Pair<String, String>? {
    indices.forEach { i ->
        (i + 1..lastIndex).forEach { j ->
            if (related(this[i], this[j])) {
                return this[i] to this[j]
            }
        }
    }
    return null
}
