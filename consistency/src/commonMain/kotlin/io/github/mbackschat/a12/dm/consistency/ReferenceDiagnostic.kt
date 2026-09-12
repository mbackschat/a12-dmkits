package io.github.mbackschat.a12.dm.consistency

/**
 * The A12 rejection a reference outcome corresponds to, or `null` when it resolved.
 *
 * Both codes are measured Kernel constraints rather than checker policy — the four-row grid is locked by
 * `ShortNameTierLawsTest` and `ShortNameUniqueTierLawsTest` — so both carry [FindingCategory.A12_CONSTRAINT]
 * and may reject a model.
 *
 * The [Finding.explain] states what was observed and stops there. The *corrective* for each code is owned by
 * `rulekit.validate.StructuralKernelFix`, which this Kernel-free module cannot read; giving the portable
 * report the same guidance without minting a second live copy of it is C2's remaining piece, and F's report
 * is where it lands. Restating the prose here is the one thing that must not happen meanwhile.
 *
 * @param at the declaration the reference was written in.
 */
fun Reference.asFinding(at: String): Finding? = when (this) {
    is Reference.Resolved -> null
    is Reference.Undeclared -> a12Error(
        code = "MVK_INVALID_ENTITY",
        at = at,
        explain = "no declared element matches `$candidate`, and no model-wide field short name resolved it",
    )
    is Reference.Ambiguous -> a12Error(
        code = "MVK_FIELDNAME_NOT_UNIQUE",
        at = at,
        explain = "the short name `$name` is carried by ${candidates.size} fields — " +
            "${candidates.joinToString(", ")} — so the model-wide fallback cannot choose between them",
    )
}

private fun a12Error(code: String, at: String, explain: String) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at)
