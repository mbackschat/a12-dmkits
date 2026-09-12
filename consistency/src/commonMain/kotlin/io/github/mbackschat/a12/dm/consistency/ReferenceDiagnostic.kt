package io.github.mbackschat.a12.dm.consistency

/**
 * The A12 rejection a reference outcome corresponds to, or `null` when it resolved.
 *
 * **The code depends on WHERE the reference was written, not only on how it failed**, and that is measured
 * rather than tidy: the same unresolvable path is `MVK_ERROR_FIELD_MISSING` as a rule's error target,
 * `MVK_INVALID_ENTITY` inside a condition, and — as a computation's computed field — not an `MVK_*` code at
 * all, because the Kernel cannot even build the model. An earlier version reported every unresolved
 * reference as `MVK_INVALID_ENTITY`, which was right for one of the three.
 *
 * All of them carry [FindingCategory.A12_CONSTRAINT] and may reject a model: each is a measured Kernel
 * verdict on a whole model through `ModelLoader.loadAndCheck`, not checker policy.
 *
 * The [Finding.explain] states what was observed and stops there. The *corrective* for each code is owned by
 * `rulekit.validate.StructuralKernelFix`, which this Kernel-free module cannot read; giving the portable
 * report the same guidance without minting a second live copy of it is C2's remaining piece, and F's report
 * is where it lands. Restating the prose here is the one thing that must not happen meanwhile.
 *
 * @param at the declaration the reference was written in.
 */
fun Reference.asFinding(role: ReferenceRole, at: String): Finding? = when (this) {
    is Reference.Resolved -> null
    is Reference.Undeclared -> a12Error(
        code = role.codeFor(this),
        at = at,
        explain = "no declared element matches `$candidate`, and no model-wide field short name resolved it",
    )
    is Reference.Ambiguous -> a12Error(
        code = role.codeFor(this),
        at = at,
        explain = "the short name `$name` is carried by ${candidates.size} fields — " +
            "${candidates.joinToString(", ")} — so the model-wide fallback cannot choose between them",
    )
    is Reference.AboveRoot -> a12Error(
        code = role.codeFor(this),
        at = at,
        explain = "`$written` climbs above the model root, so it names no position in this model",
    )
}

/**
 * A rejection A12 issues with no code of its own: the Kernel refuses to BUILD a model whose computed field
 * is missing ("The computed field \"…\" was not found."), so no `MVK_*` key exists to carry. This is the one
 * code the checker owns, minted only after establishing that A12 defines none — and it is deliberately not
 * an `MVK_*` or `RK_*` spelling, so a consumer can never mistake it for one.
 */
const val CK_COMPUTED_FIELD_NOT_FOUND = "CK_COMPUTED_FIELD_NOT_FOUND"

private fun ReferenceRole.codeFor(outcome: Reference): String = when (this) {
    // Both an unresolvable and an above-root error target report this pair; the checker reports the first,
    // since `MVK_MISSING_ERROR_FIELD` is the metamodel law restating it rather than a second defect.
    ReferenceRole.ERROR_TARGET -> "MVK_ERROR_FIELD_MISSING"
    ReferenceRole.COMPUTED_TARGET -> CK_COMPUTED_FIELD_NOT_FOUND
    ReferenceRole.CONDITION_ENTITY -> when (outcome) {
        is Reference.Ambiguous -> "MVK_FIELDNAME_NOT_UNIQUE"
        is Reference.AboveRoot -> "MVK_TOO_MANY_PARENT_GROUPS"
        else -> "MVK_INVALID_ENTITY"
    }
}

private fun a12Error(code: String, at: String, explain: String) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at)
