package io.github.mbackschat.a12.dm.consistency

/**
 * What a report comes to for a consumer that has to ACT on it — three states, because there are three actions.
 *
 * **It exists because [ConsistencyStatus] answers a different question.** The status says what the checker
 * concluded about the model; this says what a caller can do about it, and the two part company on the case
 * that matters: `Inconclusive` covers both "every family ran and none rejected this" and "nothing ran at all",
 * which are the same verdict and opposite instructions. A gate that conflates them either fails every accepted
 * model or passes every unreadable one.
 *
 * **None of the three is a pass**, and no fourth value is coming until the coverage registry does. [NO_REJECTION]
 * is the closest, and it says only what its name says.
 */
enum class CheckOutcome {
    /** A12 rejects a declaration. The verdict is established, and it is a no. */
    REJECTED,

    /** Families ran and none rejected the model. **Not a pass** — nothing was validated, only not refused. */
    NO_REJECTION,

    /** Nothing was checked: the source did not reach the checker, or the checker could not admit it. */
    NOT_ESTABLISHED,
}

/**
 * Classify this report — the rule a consumer would otherwise write, stated once.
 *
 * **The emptiness of [ConsistencyReport.checksCompleted] is the operational test, not the presence of a
 * diagnostic**, and that is the load-bearing choice. An empty set means no family ran; a diagnostic may
 * perfectly well ride along with a complete run, since `DECLARATION_NOT_WELL_FORMED` reports one unreadable
 * declaration while the other families still answer about its siblings. Keying on "any diagnostic" turns every
 * such model into a tool failure — an over-rejection in the consumer rather than in the checker, which lands
 * on the author just the same.
 *
 * The wire-level twin of this rule lives in [`ci-gate.mjs`](../../../../../../../../consumer-tests/node/ci-gate.mjs),
 * which classifies a JSON payload rather than a typed report and so cannot call this. It is a deliberate mirror
 * with this function as the owner; the durable fix is to publish the outcome ON the wire shape, which is a
 * report-shape change with its own guards (`PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md`, the F3-c record).
 */
val ConsistencyReport.outcome: CheckOutcome
    get() = when {
        status is ConsistencyStatus.Invalid -> CheckOutcome.REJECTED
        checksCompleted.isEmpty() -> CheckOutcome.NOT_ESTABLISHED
        else -> CheckOutcome.NO_REJECTION
    }
