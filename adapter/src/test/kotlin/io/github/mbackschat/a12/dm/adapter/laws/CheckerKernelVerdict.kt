package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic

/**
 * How the checker's classes on one input stand against A12's, in the words the disagreement ledgers use; null
 * where the two agree.
 *
 * Shared by every sweep that files checker-against-Kernel disagreements into a ledger, so one input gets one
 * verdict whichever sweep produced it. The words are the tiers of
 * [CONFORMANCE-PLAN](../../../../../../../../../../docs/CONFORMANCE-PLAN.md): `OVER-REJECT` and `WRONG-CLASS`
 * are correctness, `SILENT` is coverage, and `EXTRA` is order, the classes A12's early stop never reached.
 *
 * One superset counts as agreement because the checker draws it by design: where A12 names only
 * `MVK_POINT_IN_A12_MODEL`, the checker adds `MVK_INVALID_WILDCARD` beside it (the path grid's ledger header).
 */
internal fun checkerVerdict(a12: Set<String>, ours: Set<String>): String? = when {
    a12 == ours -> null
    a12 == setOf(POINT_IN_A12_MODEL) && ours == setOf(POINT_IN_A12_MODEL, "MVK_INVALID_WILDCARD") -> null
    a12.isEmpty() -> "OVER-REJECT"
    ours.isEmpty() -> "SILENT"
    ours.containsAll(a12) -> "EXTRA"
    else -> "WRONG-CLASS"
}

/**
 * The class a ledger records for one A12 diagnostic: its key, or for a codeless one the checker's name for it.
 *
 * Only one codeless refusal has a name here: the charset sentence, which the checker reports as its own
 * `RK_UNSUPPORTED_CHARACTER` because A12 gives it no class ([KERNEL-FINDINGS KF489]). Any other codeless
 * diagnostic stays [UNKEYED_A12_CLASS], so it shows as a disagreement rather than matching by accident.
 */
internal fun Diagnostic.ledgerClass(): String =
    code().orElse(if (A12_REPERTOIRE_REFUSAL in summary()) RK_UNSUPPORTED_CHARACTER else UNKEYED_A12_CLASS)

/** How a ledger row spells a diagnostic A12 raises without a class key. */
internal const val UNKEYED_A12_CLASS = "(no code)"

private const val A12_REPERTOIRE_REFUSAL = "The field contains one or several unsupported signs."
private const val RK_UNSUPPORTED_CHARACTER = "RK_UNSUPPORTED_CHARACTER"

private const val POINT_IN_A12_MODEL = "MVK_POINT_IN_A12_MODEL"
