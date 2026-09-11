package io.github.mbackschat.a12.dm.consistency

/**
 * The three kinds of check the checker may report, kept apart because only one of them speaks for A12.
 *
 * The separation is the point: presenting a dmkits policy or an evaluator limitation as an A12 rejection
 * would make the portable checker disagree with the Kernel it claims to reproduce, in the direction no
 * consumer can detect — a refusal the Kernel never issues is invisible precisely because the Kernel is
 * never asked (CLAUDE.md, "A client-side refusal needs a measured kernel basis").
 */
enum class FindingCategory {
    /** A measured A12 acceptance constraint. The only category that may reject a model. */
    A12_CONSTRAINT,

    /** A deliberate dmkits authoring policy — advisory, and never asserted as a Kernel rejection. */
    AUTHORING_POLICY,

    /**
     * A runtime support limitation. It never affects validity; it limits *completeness*, and only when it
     * actually prevents an authoring check from running.
     */
    EVALUATOR_SUPPORT,
}

/**
 * A finding's severity — the Kernel's own three levels, mirrored rather than imported so this module keeps
 * no project dependency (the same treatment [ModelSource] gets). `dm.core.Severity` is the Java-side peer.
 */
enum class FindingSeverity {
    INFO,
    WARNING,
    ERROR,
}

/**
 * One thing the checker found.
 *
 * @param code the classification in its owner's vocabulary — an `MVK_*` key, a `FehlertextErrorType` name,
 *     an `RK_*` code — carried as declared rather than re-derived from a localized message.
 * @param at the declared path this concerns, when the finding has one.
 */
data class Finding(
    val category: FindingCategory,
    val severity: FindingSeverity,
    val code: String,
    val explain: String,
    val at: String? = null,
) {
    /**
     * Whether this finding alone makes the model invalid: a measured A12 constraint, violated at ERROR.
     * Both halves are load-bearing — the Kernel's consistency check rejects on ERROR notifications alone,
     * and the other two categories are ours rather than A12's.
     */
    val justifiesRejection: Boolean
        get() = category == FindingCategory.A12_CONSTRAINT && severity == FindingSeverity.ERROR
}

/**
 * A check's summary verdict.
 *
 * **There is deliberately no `Valid`.** No required check family is implemented, so nothing can establish
 * that the applicable checks for the pinned profile actually ran; a result type able to say so would be an
 * unbacked claim rather than an unfinished one. `Valid` arrives with the internal coverage evidence that
 * earns it, and [complete] becomes derived at the same time.
 */
sealed interface ConsistencyStatus {
    /** Whether every required check for this input and profile completed. */
    val complete: Boolean

    /** At least one measured A12 acceptance constraint is violated; other portions may be unexamined. */
    data object Invalid : ConsistencyStatus {
        override val complete: Boolean get() = false
    }

    /** No definite violation was established, and coverage is incomplete. */
    data object Inconclusive : ConsistencyStatus {
        override val complete: Boolean get() = false
    }
}

/** Summarize [findings]. The summary never replaces them: a consumer reads the findings, not the verdict. */
fun statusOf(findings: List<Finding>): ConsistencyStatus =
    if (findings.any { it.justifiesRejection }) ConsistencyStatus.Invalid else ConsistencyStatus.Inconclusive
