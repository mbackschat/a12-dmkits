package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A repetition wildcard on something that has no repetitions — a field, a fixed group, the model root.
 *
 * A12 answers every one of them with `MVK_INVALID_WILDCARD` ([KERNEL-FINDINGS KF328],
 * `rulekit.validate.laws.StarredNonRepeatableReachLawsTest`), and the family is unusual in judging a
 * REFERENCE rather than an operand: the class is the same in every position measured, so no operand identity
 * is needed and none is used.
 *
 * **The law PRE-EMPTS the slot's own wildcard gate, which is what makes the reference-level reading safe.**
 * The same star on a REPEATABLE group draws three different answers depending on where it stands —
 * `MVK_NO_WILDCARDS_ALLOWED` in a scalar slot, `MVK_NO_WILDCARDS_G_ALLOWED` in a quantifier's operand list,
 * admitted in a count or an aggregate — and on a non-repeatable one every position collapses to this single
 * class. Measured across six positions on the rule carrier and two on the computation carrier, each beside
 * its repeatable control, because a per-position claim is exactly what a reference-level family assumes.
 *
 * **It outruns the entity gate too**, which is the opposite order from every other pair measured here: a
 * starred fixed group followed by a leaf that resolves to nothing draws this class and nothing about the
 * leaf. So this family does not wait for the whole reference to resolve — it resolves each starred SEGMENT's
 * own prefix and judges that.
 *
 * **Two shapes are left alone, each because A12's answer for them is unmeasured rather than absent.** A
 * starred segment whose own prefix does not resolve is left to the reference family; and a star on an
 * up-step (`..*`) has a class of its own in A12's ledger, so judging it here would risk naming the wrong one.
 */
fun PreparedModel.repetitionWildcardFindings(): List<Finding> = elements.flatMap { element ->
    val body = element.body ?: return@flatMap emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    conditionTexts(element.kind, body).flatMap { (member, text) ->
        referencesIn(text, conditionLanguage).mapNotNull { written ->
            val segments = written.split('/')
            val offending = segments.indices.firstNotNullOfOrNull { at ->
                starredPrefix(segments, at)
                    ?.takeIf { prefix -> namesNoRepetition(group, prefix, element.origin) }
            } ?: return@mapNotNull null
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_INVALID_WILDCARD",
                "this $member writes `$written` with a repetition wildcard on `$offending`, which has no " +
                    "repetitions — a `*` belongs only on a repeatable group",
                element.path,
            )
        }
    }
}

/**
 * The path up to and including the segment at [at], when that segment carries a wildcard and is a NAME.
 *
 * A `..*` is excluded: A12 gives a starred up-step its own class (`MVK_INVALID_WILDCARD_REL`), so resolving
 * the step and judging what it lands on would name this class for a shape A12 answers with another.
 */
private fun starredPrefix(segments: List<String>, at: Int): String? {
    val segment = segments[at]
    if (!segment.endsWith("*")) {
        return null
    }
    val name = segment.removeSuffix("*").removeSuffix(".")
    return if (name.isEmpty() || name == "..") null else segments.take(at + 1).joinToString("/")
}

/** Whether [prefix] resolves to something other than a repeatable group — unresolvable counting as neither. */
private fun PreparedModel.namesNoRepetition(
    declaringGroup: String,
    prefix: String,
    origin: DeclarationOrigin,
): Boolean {
    val path = (resolveEntity(declaringGroup, prefix, origin) as? Reference.Resolved)?.path ?: return false
    val element = elements.firstOrNull { it.path == path } ?: return false
    return element.declaredKind != DeclaredKind.GROUP || !element.isRepeatable
}

/**
 * Whether A12 will give this group repetitions: `Group.repeatability` above 1.
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
