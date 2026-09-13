package io.github.mbackschat.a12.dm.consistency

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

/** Whether this condition reference resolves to [target] or to a group containing it. */
private fun ConditionReference.reaches(target: String): Boolean {
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
