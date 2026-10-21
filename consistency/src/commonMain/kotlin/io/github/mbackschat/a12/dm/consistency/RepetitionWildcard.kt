package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** KF356/R5-PE: the segment's resolution and suffix gates precede its wildcard gate. */
internal fun TrackedFactBase.repetitionWildcardFindings(): List<Finding> = declarations().flatMap { element ->
    pathContributions(element).filter { it.family == CheckFamily.REPETITION_WILDCARD_TARGET }
        .map { it.contribution.finding }
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
internal val DeclaredElement.isRepeatable: Boolean
    get() = isRepeatableIn(body)

/** A repeatable GROUP, which is what every wildcard and scope question actually asks about. */
internal val DeclaredElement.isRepeatableGroup: Boolean
    get() = declaredKind == DeclaredKind.GROUP && isRepeatable

/** The same question through the read barrier. */
internal val TrackedDeclaration.isRepeatableGroup: Boolean
    get() = declaredKind == DeclaredKind.GROUP && isRepeatable

/**
 * The same reading through the read barrier, charged as this declaration's CONTENT.
 *
 * Repeatability is a body member, so it is charged per declaration rather than folded into the set's shape.
 * Folding it in would be defensible — it is structural, like the variant filter — and it would be less
 * precise and easy to get wrong: the shape would have to be redefined to include it in the same change, and
 * a charge narrower than what was read is the one direction that makes reuse UNSOUND rather than coarse.
 */
internal val TrackedDeclaration.isRepeatable: Boolean
    get() = isRepeatableIn(body)

// The count A12's reader binds (KF590), so `3.7`, `"\u0663"` and `"\uFF13"` are repeatable; a value it cannot bind
// leaves no model, and reads here as the default.
private fun isRepeatableIn(body: JsonObject?): Boolean =
    ((body?.get("repeatability") as? JsonPrimitive)?.boundRepetitionCount() ?: 1) > 1
