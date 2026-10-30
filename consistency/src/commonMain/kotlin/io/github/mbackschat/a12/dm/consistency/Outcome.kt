package io.github.mbackschat.a12.dm.consistency

/**
 * What a report comes to for a consumer that has to ACT on it — one state per action a caller can take.
 *
 * **It exists because [ConsistencyStatus] answers a different question.** The status says what the checker
 * concluded about the model; this says what a caller can do about it, and the two part company on the case
 * that matters: `Inconclusive` covers both "every family ran and none rejected this" and "nothing ran at all",
 * which are the same verdict and opposite instructions. A gate that conflates them either fails every accepted
 * model or passes every unreadable one.
 *
 * **Exactly one of the four is a pass, and it never needed the coverage registry — believing it did is what
 * withheld it.** [ALL_CHECKS_PASSED] requires that every family in [CheckFamily] completed and that no
 * diagnostic rode along; [NO_REJECTION] is its weaker neighbour — something ran, nothing rejected, but the
 * run was narrower than that — and it says only what its name says. Reading the two as interchangeable is the
 * mistake this enum exists to prevent.
 *
 * **Two different things get called "the coverage registry", which is why the refusal looked sound.** THIS
 * denominator is `CheckFamily.entries` — finite, enumerable, and known at compile time, so "all of them ran
 * and none rejected" is decidable today. `ConsistencyStatus.complete`'s denominator is every code A12 could
 * report on a given model, which needs a registry that does NOT exist and holds that property constant
 * `false`. The original refusal was true of the second and applied to both, so the checker withheld a sound,
 * useful verdict because an unsound one was unavailable.
 *
 * *(Corrected 2026-09-18, twice. It first said "none of the three is a pass, and no fourth value is coming
 * until the coverage registry does"; the correction of that then said "the registry came, [ALL_CHECKS_PASSED]
 * with it", which is false — no registry was built, the owner SPLIT the clause. Checking that sentence against
 * `ConsistencyStatus.complete`, still constant `false` fifteen lines into another file, refutes it in one
 * read — and this KDoc ships to every JVM consumer's sources jar, where the plan's own record does not.)*
 */
enum class CheckOutcome {
    /** A12 rejects a declaration. The verdict is established, and it is a no. */
    REJECTED,

    /**
     * Some families ran and none rejected the model, but not every family did. **Not a pass.**
     *
     * Reached when a source declares nothing a per-declaration producer can be asked about: the model-wide
     * families still answer, so this is not [NOT_ESTABLISHED], while the run is too narrow to assert
     * [ALL_CHECKS_PASSED].
     */
    NO_REJECTION,

    /** Nothing was checked: the source did not reach the checker, or the checker could not admit it. */
    NOT_ESTABLISHED,

    /**
     * Every family this checker implements ran, and none rejected the model.
     *
     * **This is a pass, and it is the only one — scoped to what the checker verifies, never to A12.** The
     * claim is "nothing I can check is wrong", which is decidable because the families are a finite
     * enumerable set. It is deliberately NOT "A12 would accept this": that needs the coverage registry, and
     * a character restriction whose combined graphemes overlap is the measured witness that a model A12 rejects can
     * pass every family here (`adapter.laws.CheckerFrameValueAgreementTest`).
     * A consumer may act on this; a consumer may not record it as A12 validation.
     *
     * Named `ALL_CHECKS_PASSED` rather than `VALID` because `valid` already names the kernel's verdict on
     * dmtool's envelope, and the two disagree on exactly that model — one word, two contracts
     * (owner-decided 2026-09-18). Appended rather than slotted next to [NO_REJECTION] so no existing
     * constant's ordinal moves.
     */
    ALL_CHECKS_PASSED,
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
        // A rejection outranks completeness: a model may complete every family and still be refused, and
        // reading the pass first would hide that.
        status is ConsistencyStatus.Invalid -> CheckOutcome.REJECTED
        checksCompleted.isEmpty() -> CheckOutcome.NOT_ESTABLISHED
        // The denominator is the ENUM, not the schedule, and that asymmetry is deliberate: a family added to
        // `CheckFamily` but never wired into a producer fails `containsAll` and degrades this to
        // NO_REJECTION. Keying on the schedule instead would make every run complete by construction — the
        // shape that cannot fail, and the one direction that turns a pass into an over-claim.
        //
        // `diagnostics` must be EMPTY too, and that second conjunct is not belt-and-braces. Every
        // `DiagnosticCode` means the checker could not read something, and `DECLARATION_NOT_WELL_FORMED` is
        // explicitly "reported and skipped" — so every family can complete while one declaration was never
        // examined by any of them. `checksCompleted` cannot see that, because it counts families and the
        // families did run. Claiming a pass there would tell a consumer their model is clean on the strength
        // of a declaration nobody read. GAPS deliberately do NOT block it: gaps are the standing disclosure
        // of what this checker does not implement, which is what makes the pass scoped rather than absolute.
        checksCompleted.containsAll(CheckFamily.entries) && diagnostics.isEmpty() ->
            CheckOutcome.ALL_CHECKS_PASSED
        else -> CheckOutcome.NO_REJECTION
    }
