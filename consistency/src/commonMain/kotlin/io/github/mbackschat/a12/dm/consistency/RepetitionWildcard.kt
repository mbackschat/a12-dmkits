package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.starsRuleContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A repetition wildcard on something that cannot repeat — a field, a fixed group, the model root, a step.
 *
 * A star belongs only on a repeatable group, and A12 answers every other landing site with a refusal — but
 * with THREE different codes, chosen by what the starred segment is rather than by where the reference stands
 * ([KERNEL-FINDINGS KF328], `rulekit.validate.laws.StarredNonRepeatableReachLawsTest`). A NAME that names no
 * repetition draws `MVK_INVALID_WILDCARD`; the rule-context entity draws `MVK_INVALID_WILDCARD_FOR_RULEGROUP`;
 * a relative up-step draws `MVK_INVALID_WILDCARD_REL`. Naming the class this family knows for a segment A12
 * answers with another would be a wrong finding on a correct rejection, which is why the split is measured
 * rather than collapsed.
 *
 * **The law PRE-EMPTS the slot's own wildcard gate, which is what makes the reference-level reading safe.**
 * The same star on a REPEATABLE group draws three different answers depending on where it stands —
 * `MVK_NO_WILDCARDS_ALLOWED` in a scalar slot, `MVK_NO_WILDCARDS_G_ALLOWED` in a quantifier's operand list,
 * admitted in a count or an aggregate — and on a non-repeatable one every position collapses to one class.
 * Measured across six positions on the rule carrier and two on the computation carrier, each beside its
 * repeatable control, because a per-position claim is exactly what a reference-level family assumes.
 *
 * **It outruns the entity gate too**, which is the opposite order from every other pair measured here: a
 * starred fixed group followed by a leaf that resolves to nothing draws this class and nothing about the
 * leaf. So this family does not wait for the whole reference to resolve — it resolves each starred SEGMENT's
 * own prefix and judges that.
 *
 * **Two shapes are left alone, each because A12's answer for them is unmeasured or is another family's.** A
 * starred segment whose own prefix does not resolve is left to the reference family — including an up-step
 * that leaves the model, where A12 reports the DEPTH overflow and never reaches the wildcard gate. And a
 * star on a named turning point (`..Ancestor*`) is unmeasured, so silence is the safe answer.
 *
 * **One measured refusal is deliberately not reported**: a reference that is nothing but starred up-steps
 * (`..*`). A run naming no entity is not a reference at all to the reader this family uses, so the shape never
 * arrives here. A12 does refuse it, so this under-reports — narrowly, since any name after the step brings the
 * whole reference back into view, and in the direction this project requires when a reading is incomplete.
 */
fun PreparedModel.repetitionWildcardFindings(): List<Finding> = elements.flatMap { element ->
    val body = element.body ?: return@flatMap emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    conditionTexts(element.kind, body).flatMap { (member, text) ->
        // The rule-context entity is a KEYWORD, so a starred one never reaches the reference list at all and
        // needs its own question — the same reason its reach needs one in the error-field family.
        val onTheRulesOwnGroup = if (starsRuleContext(text, conditionLanguage)) {
            listOf(
                Finding(
                    FindingCategory.A12_CONSTRAINT,
                    FindingSeverity.ERROR,
                    "MVK_INVALID_WILDCARD_FOR_RULEGROUP",
                    "this $member puts a repetition wildcard on the rule's own group — a `*` belongs only " +
                        "on a repeatable group, and the rule context is not one",
                    element.path,
                ),
            )
        } else {
            emptyList()
        }
        onTheRulesOwnGroup + referencesIn(text, conditionLanguage).mapNotNull { written ->
            val segments = written.split('/')
            val offence = segments.indices
                .firstNotNullOfOrNull { at -> offenceAt(segments, at, group, element.origin) }
                ?: return@mapNotNull null
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                offence.code,
                "this $member writes `$written` with a repetition wildcard on ${offence.why} — a `*` belongs " +
                    "only on a repeatable group",
                element.path,
            )
        }
    }
}

/** One starred segment A12 refuses, with the class it refuses it under. */
private class Offence(val code: String, val why: String)

/**
 * What A12 answers for the segment at [at], or null when this family has nothing to say about it.
 *
 * The order of the arms is the order the segment's own SPELLING decides — nothing here depends on position,
 * because the measured grid shows the class does not either.
 */
private fun PreparedModel.offenceAt(
    segments: List<String>,
    at: Int,
    declaringGroup: String,
    origin: DeclarationOrigin,
): Offence? {
    val segment = segments[at]
    if (!segment.endsWith("*")) {
        return null
    }
    val stepped = segment.removeSuffix("*")
    return when {
        // A bare `*` names no segment at all; whether such a text is a condition is the grammar family's.
        stepped.isEmpty() -> null
        stepped == ".." -> upStepOffence(segments, at, declaringGroup, origin)
        // A `.` self-step, and the named turning point `..Ancestor`: A12's class for a star on either is
        // unmeasured, and naming the wrong one is worse than saying nothing.
        stepped.startsWith(".") -> null
        namesNoRepetition(declaringGroup, segments.take(at + 1).joinToString("/"), origin) ->
            Offence("MVK_INVALID_WILDCARD", "`${segments.take(at + 1).joinToString("/")}`, which has no repetitions")
        else -> null
    }
}

/**
 * A starred up-step, but only where A12 actually reaches its wildcard gate.
 *
 * Two shapes look like this one and are not it. A `..` that is not part of the LEADING run is not valid A12
 * at all — `Guest/../Extras` is refused by the parser, starred or not — so there is no wildcard verdict to
 * report and the grammar family owns the text. And an up-run that walks off the top of the model is answered
 * with the DEPTH class before any star is considered, which is why the resolve has to succeed here rather
 * than merely be attempted.
 */
private fun PreparedModel.upStepOffence(
    segments: List<String>,
    at: Int,
    declaringGroup: String,
    origin: DeclarationOrigin,
): Offence? {
    if (segments.take(at).any { it != ".." }) {
        return null
    }
    val prefix = segments.take(at + 1).joinToString("/")
    if (resolveEntity(declaringGroup, prefix, origin) !is Reference.Resolved) {
        return null
    }
    return Offence("MVK_INVALID_WILDCARD_REL", "a relative up-step")
}

/** Whether [prefix] resolves to something other than a repeatable group — unresolvable counting as neither. */
private fun PreparedModel.namesNoRepetition(
    declaringGroup: String,
    prefix: String,
    origin: DeclarationOrigin,
): Boolean {
    val path = (resolveEntity(declaringGroup, prefix, origin) as? Reference.Resolved)?.path ?: return false
    val element = elements.firstOrNull { it.path == path } ?: return false
    return !element.isRepeatable
}

/**
 * Whether A12 will give this element repetitions: `Group.repeatability` above 1.
 *
 * **No kind test guards this read, because only a group can carry the member.** `getRepeatability()` is
 * declared on `IGroup` alone in the kernel's model API — `IField`, `IRule` and `IComputation` declare none —
 * so a field, a rule or a computation reads as the default and is answered correctly by that default. A kind
 * test here would be a branch no model can take, and an invented fixture is the only thing that could cover
 * it, which is the defect below wearing a different hat.
 *
 * **The member is `repeatability`, an INTEGER whose default is 1 — there is no boolean `repeatable`.** That
 * is `ModelDocument.isRepeatable`'s own test and `AuthoredStateReader`'s own default, and no model under
 * `cli/src/test/resources/models` carries the boolean spelling. Reading a boolean here made every
 * legitimately starred group in a real model look fixed, so the family reported `MVK_INVALID_WILDCARD` for
 * every correct star — an over-rejection that passed its own tests, because the fixtures written beside the
 * implementation carried the same invented member.
 *
 * A non-numeric or absent member reads as 1, which is A12's default, so the absence is a positive answer
 * here rather than an unknown.
 */
private val DeclaredElement.isRepeatable: Boolean
    get() = ((body?.get("repeatability") as? JsonPrimitive)?.intOrNull ?: 1) > 1
