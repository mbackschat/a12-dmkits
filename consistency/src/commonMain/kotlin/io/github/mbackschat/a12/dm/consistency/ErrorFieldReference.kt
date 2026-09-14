package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.namesRuleContext

/**
 * That a rule's condition REFERENCES the field its error is attached to.
 *
 * A resolvable `errorEntityRelPath` is necessary and not sufficient: A12 also requires the condition to name
 * the target, and refuses the rule with `MVK_ERROR_FIELD_NOT_REFERENCED` when it does not
 * ([KERNEL-FINDINGS KF324], `rulekit.validate.laws.ErrorFieldReferenceReachLawsTest`). It is one of the most
 * common authoring mistakes, and it is the one law here whose risk is entirely on the RECALL side: every
 * spelling this family fails to recognise as a reference becomes a refusal of a model A12 admits.
 *
 * **The relation is "the reference IS the target, or an ancestor of it", and it runs one way.** Naming the
 * group a target field sits in reaches it — so comparing the target's own path is too strict — while naming a
 * field inside a target group does not, so treating containment as symmetric is too loose. Both directions
 * are measured; either row alone licenses the wrong implementation.
 *
 * **And the RULE-CONTEXT entity satisfies the gate outright** — it names the rule's own group, which every
 * possible target lies under. It carries no path, so it is a separate question from the reference list rather
 * than a member of it; [namesTheRulesOwnGroup] says why that distinction was invisible until a real model
 * paired the spelling with an ordinary operand.
 *
 * **Two deliberate under-reports, each stated rather than discovered later.** A reference inside a `Having`
 * filter does NOT satisfy A12's gate, but the names a filter contributes are indistinguishable from an
 * operand's once condition text is scanned, so they are treated as reaching and this family stays silent.
 * And it reports nothing unless the target's own NAME is absent from the condition text, so a spelling the
 * reference extractor does not recognise can never become a refusal here — the recall risk above is bounded
 * by a test the extractor is not involved in.
 */
fun PreparedModel.errorFieldReferenceFindings(): List<Finding> {
    val named = conditionReferences().groupBy { it.declaredAt }
    return declaredReferences()
        .filter { it.role == ReferenceRole.ERROR_TARGET }
        .mapNotNull { declared ->
            val target = (declared.target as? Reference.Resolved)?.path ?: return@mapNotNull null
            val references = named[declared.declaredAt].orEmpty()
            // Three ways this rule is not this family's to judge. A rule with no condition at all belongs to
            // `MVK_CONDITION_MISSING`. An UNRESOLVABLE operand PRE-EMPTS the gate — A12 reports the entity
            // and says nothing about the reference, measured — so reporting here would name a second fix for
            // one mistake. And a condition that mentions the target's name in some spelling is left alone.
            if (references.isEmpty() ||
                references.any { it.target !is Reference.Resolved } ||
                references.any { it.reaches(target) } ||
                references.any { iteratesPastTheTarget(it, target) } ||
                namesTheRulesOwnGroup(declared) ||
                mentions(declared, target)
            ) {
                return@mapNotNull null
            }
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_ERROR_FIELD_NOT_REFERENCED",
                "this rule attaches its error to `$target`, which its condition never names — A12 requires " +
                    "the condition to reference the field it reports on, and naming a group the target " +
                    "sits in counts",
                declared.declaredAt,
            )
        }
}

/**
 * Whether this reference ITERATES a repetition the target does not sit in, which A12 answers with a class of
 * its own.
 *
 * An UNSTARRED read into a repeatable group iterates it, so A12 cannot say which repetition an error attached
 * to a field OUTSIDE that group belongs to, and it reports `MVK_NO_WILDCARD` — whether or not the condition
 * also names the target (`rulekit.validate.laws.ErrorFieldReferenceReachLawsTest`). A STAR on the same list
 * collapses it to one value and no iteration remains, which is why this family's own class answers there
 * instead. So the two conditions are independent gates and the iteration one reports first.
 *
 * **Found by a differential, not by reading the grid.** The mutation sweep repointed each committed model's
 * first rule at an unreferenced field; seven models answered `MVK_NO_WILDCARD` while this family said its own
 * class. Every row of its grid held the condition flat, so "the target is unreferenced" and "nothing
 * iterates" were one thing across all of them.
 *
 * **One residue, stated rather than left to be discovered.** The star is tested on the WHOLE written
 * reference, so a single reference that stars an outer level while crossing an inner repeatable group
 * unstarred is not pre-empted here. A condition mixing a starred and an unstarred read is handled, because
 * the test is per reference; the nested single-reference case has no measured row and would name this
 * family's class where A12 may name the iteration one — a wrong class on a correct rejection, never an
 * over-rejection.
 */
private fun PreparedModel.iteratesPastTheTarget(reference: ConditionReference, target: String): Boolean {
    if ('*' in reference.written) {
        return false
    }
    val resolved = (reference.target as? Reference.Resolved)?.path ?: return false
    return elements.any { candidate ->
        candidate.declaredKind == DeclaredKind.GROUP && candidate.isRepeatable &&
            resolved.startsWith("${candidate.path}/") &&
            !(target == candidate.path || target.startsWith("${candidate.path}/"))
    }
}

/**
 * Whether the condition names the RULE-CONTEXT entity, which satisfies the gate on its own.
 *
 * The error field must lie under the rule's own group, so naming that group reaches every possible target —
 * and A12 admits the rule whatever else the condition names and however deep the target sits
 * (`rulekit.validate.laws.ErrorFieldReferenceReachLawsTest`, measured 2026-09-13).
 *
 * **It is a separate question from [reaches] because the spelling has no path.** `referencesIn` drops it with
 * the other keywords, so an implementation reading only the reference list sees a condition that either names
 * nothing — silent for the wrong reason — or names an ordinary operand beside it and reports. Every row that
 * had the self-reference here carried it ALONE, so the two readings agreed on all of them; the reference
 * corpus supplied the row on the other side of that axis, on two models the Kernel builds.
 */
internal fun PreparedModel.namesTheRulesOwnGroup(declared: DeclaredReference): Boolean {
    val element = elements.firstOrNull { it.path == declared.declaredAt } ?: return false
    val body = element.body ?: return false
    return conditionTexts(element.kind, body).any { (_, text) -> namesRuleContext(text, conditionLanguage) }
}

/** Whether this condition reference resolves to [target] or to a group containing it. */
internal fun ConditionReference.reaches(target: String): Boolean {
    val resolved = (this.target as? Reference.Resolved)?.path ?: return false
    return resolved == target || target.startsWith("$resolved/")
}

/**
 * Whether the target's own NAME appears in this rule's condition text other than as a path PREFIX.
 *
 * The backstop that keeps a gap in the reference extractor from producing a refusal: a condition mentioning
 * the name in a spelling nothing here parsed is left to A12 rather than judged on a reading this checker
 * could not complete. It costs a real violation only when the name occurs for another reason, which is the
 * safe direction.
 *
 * The prefix exclusion is what keeps it from swallowing the measured descendant row — `Parcels/Weight`
 * mentions `Parcels`, and naming a field INSIDE a target group is exactly the shape A12 refuses, so an
 * occurrence followed by `/` names something under the target rather than the target.
 */
private fun PreparedModel.mentions(declared: DeclaredReference, target: String): Boolean {
    val name = target.substringAfterLast('/')
    val element = elements.firstOrNull { it.path == declared.declaredAt } ?: return true
    val body = element.body ?: return true
    return conditionTexts(element.kind, body).any { (_, text) -> text.namesOtherThanAsAPrefix(name) }
}

private fun String.namesOtherThanAsAPrefix(name: String): Boolean {
    var at = indexOf(name)
    while (at >= 0) {
        if (getOrNull(at + name.length) != '/') {
            return true
        }
        at = indexOf(name, at + 1)
    }
    return false
}
