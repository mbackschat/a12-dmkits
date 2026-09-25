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
 *
 * ## …and a ROOT group where the carrier does not admit one
 *
 * The same operand list carries a second law with two more codes, and it is evaluated here because the two
 * are ORDERED: a root beside its own descendant is an overlap, and A12 reports the overlap
 * ([KERNEL-FINDINGS KF326], `rulekit.validate.laws.GroupListRootOperandLawsTest`).
 *
 * **The carriers split, and not the way their names suggest.** `AllGroupsFilled`, `NotAllGroupsFilled` and
 * `GroupsNotCollectivelyFilled` refuse a root operand outright with `MVK_ROOT_GROUP_REFERENCED`, even as the
 * sole operand; `AtLeastOneGroupFilled`, `NoGroupFilled` and `NumberOfFilledGroups` admit a SOLE root and
 * refuse one beside any other operand with `MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS`.
 *
 * **A STARRED root is NOT exempt**, which is the asymmetry against the duplicate gate above: the wildcard
 * takes an operand out of the duplicate comparison and leaves it fully inside this one.
 *
 * **An operand A12 refuses while reading it silences every gate here**, the duplicate and kind gates as well
 * as the root one. An unstarred repeatable co-operand read from outside its group draws its wildcard or
 * containment class ALONE, and the root class appears only once the read passes, as it does from inside
 * ([anOperandIsRefusedOnRead]). *Corrected 2026-09-25:* this said the root violation "holds in both" and
 * that the family needed no iteration analysis, so it named the root class beside the one A12 gives.
 *
 * ## …and a FIELD on the three carriers that refuse one
 *
 * A third law, on a different and SMALLER carrier set, which is the whole of its content: the five group-list
 * QUANTIFIERS admit a field operand, and only `NumberOfFilledGroups` and the two scalars `GroupFilled` and
 * `GroupNotFilled` refuse one with `MVK_NO_FIELDS_ALLOWED` ([KERNEL-FINDINGS KF327],
 * `rulekit.validate.laws.GroupListOperandKindLawsTest`). A family assembled from the code's NAME — "group
 * predicates take groups only" — would refuse five legal shapes, which is why the carrier set is measured
 * rather than derived from what the operators are called.
 *
 * On those three carriers the kind gate runs LAST of the gates this file judges and outruns only the arity
 * class, which is A12's order and is not reproduced by accident: the checks below are written in it.
 */
internal fun TrackedFactBase.groupListOperandFindings(): List<Finding> {
    val judged = spelled(GROUP_LIST_OPERATORS) + spelled(FIELD_REFUSING_OPERATORS)
    return declarations().flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator in judged }
                .mapNotNull { call -> operandFinding(call, group, element, member) }
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

/** …and the three that refuse a FIELD operand, which is not the same set and not a subset of one law's. */
private val FIELD_REFUSING_OPERATORS = setOf("NumberOfFilledGroups", "GroupFilled", "GroupNotFilled")

/** The three carriers that admit a root as the list's SOLE operand. */
private val SOLE_ROOT_CARRIERS = setOf("AtLeastOneGroupFilled", "NoGroupFilled", "NumberOfFilledGroups")

/** How this model's language spells each of [ids]; an id the catalog does not carry contributes nothing. */
private fun TrackedFactBase.spelled(ids: Set<String>): Set<String> =
    ids.mapNotNull { CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(it) }.toSet()

private fun TrackedFactBase.operandFinding(
    call: ConditionCall,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    val bound = call.operands.map { it to it.resolvedBy(this, declaringGroup, element.origin) }
    // A12 reports the entity and says nothing about the duplicate, so this family has nothing to add.
    if (bound.any { (operand, reference) -> operand.path != null && reference !is Reference.Resolved }) {
        return null
    }
    // Every gate below is the operator's own, and A12 asks none of them once reading an operand has failed.
    if (anOperandIsRefusedOnRead(call, element)) {
        return null
    }
    val resolved = bound.mapNotNull { (_, reference) -> (reference as? Reference.Resolved)?.path }
    if (call.operator !in spelled(GROUP_LIST_OPERATORS)) {
        return kindFinding(call, resolved, member, element.path)
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
            call.spanIn(member),
        )
    }
    overlap(comparable)?.let { (outer, inner) ->
        return finding(
            "MVK_DUPLICATE_PARAM2",
            "this $member names `$outer` and `$inner` in one `${call.operator}`, and the second lies under " +
                "the first — A12 refuses a group list whose operands contain one another",
            element.path,
            call.spanIn(member),
        )
    }
    // The root law runs after both duplicate classes because they pre-empt it, measured on a root beside its
    // own descendant. A starred root still counts here, unlike in the comparisons above.
    return rootFinding(call, resolved, member, element.path) ?: kindFinding(call, resolved, member, element.path)
}

/**
 * The field-operand finding for this call, or null when the carrier takes a field.
 *
 * The carrier set is the whole law: five group-list quantifiers ADMIT a field operand and only these three
 * refuse one, so widening it by analogy with the operators' names would refuse five shapes A12 takes.
 */
private fun TrackedFactBase.kindFinding(
    call: ConditionCall,
    resolved: List<String>,
    member: String,
    at: String,
): Finding? {
    if (call.operator !in spelled(FIELD_REFUSING_OPERATORS)) {
        return null
    }
    val field = resolved.firstOrNull { isField(it) } ?: return null
    return finding(
        "MVK_NO_FIELDS_ALLOWED",
        "this $member hands the field `$field` to `${call.operator}`, which takes groups — name the group " +
            "it sits in, or use the field-shaped operator instead",
        at,
        call.spanIn(member),
    )
}

/**
 * The root-operand finding for this call, or null when the carrier admits the root it names.
 *
 * The arity half counts the AUTHORED operands rather than the resolved ones: "a root beside another operand"
 * is a fact about the list, and an operand this reader could not reduce to a path is still an operand.
 */
private fun TrackedFactBase.rootFinding(
    call: ConditionCall,
    resolved: List<String>,
    member: String,
    at: String,
): Finding? {
    val root = resolved.firstOrNull { path -> path.isRootGroup() && isGroup(path) } ?: return null
    return when {
        call.operator in soleRootCarriers() && call.operands.size < 2 -> null
        call.operator in soleRootCarriers() -> finding(
            "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS",
            "this $member names the root group `$root` beside another operand in one `${call.operator}` — " +
                "A12 admits a root there only as the list's sole operand",
            at,
            call.spanIn(member),
        )
        else -> finding(
            "MVK_ROOT_GROUP_REFERENCED",
            "this $member names the root group `$root` in one `${call.operator}`, which takes no root " +
                "operand at all — name the groups beneath it instead",
            at,
            call.spanIn(member),
        )
    }
}

private fun TrackedFactBase.soleRootCarriers(): Set<String> = spelled(SOLE_ROOT_CARRIERS)

private fun TrackedFactBase.isGroup(path: String): Boolean = declares(path, DeclaredKind.GROUP)

private fun TrackedFactBase.isField(path: String): Boolean = declares(path, DeclaredKind.FIELD)

private fun TrackedFactBase.declares(path: String, kind: DeclaredKind): Boolean =
    declarationAt(path, kind) != null

private fun CallOperand.resolvedBy(
    model: TrackedFactBase,
    declaringGroup: String,
    origin: DeclarationOrigin,
): Reference? = path?.let { model.resolveEntity(declaringGroup, it, origin) }

/** Every gate here is the operator's own, raised once its call is read, so each spans the call. */
private fun finding(code: String, explain: String, at: String, span: TextSpan) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at, span)

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
