package io.github.mbackschat.a12.dm.consistency

/**
 * A fact base behind the read barrier, for a test that exercises ONE family directly.
 *
 * Most family tests go through `check(ModelSource(…))` and need nothing; these call a family function on a
 * prepared model, so they are first-class consumers of the receiver the barrier changes. The transcript is
 * discarded here on purpose — what these tests assert is the family's VERDICT, and the reads themselves have
 * their own oracle in [ReadTranscriptTest], where a fixture can be built to make one read distinguishable.
 */
internal fun PreparedModel.tracked(): TrackedFactBase = TrackedFactBase(this, ReadTranscript())

/**
 * One PER-DECLARATION family run over every declaration, which is exactly what the dispatcher does for it.
 *
 * It lives in the test source set deliberately, and not as a convenience on the family itself. A family
 * registered as [CheckProducer.PerDeclaration] is asked once per declaration precisely so its cell keys on
 * that declaration's body alone; a zero-argument form would have to walk the set, charge
 * [FactRead.DeclarationSetShape], and undo the key it was migrated for — so the shape must not exist in
 * production, while a test that asserts a verdict over a whole fixture still wants it.
 */
internal fun TrackedFactBase.everyDeclaration(
    family: TrackedFactBase.(TrackedDeclaration) -> List<Finding>,
): List<Finding> = declarations().flatMap { this.family(it) }
