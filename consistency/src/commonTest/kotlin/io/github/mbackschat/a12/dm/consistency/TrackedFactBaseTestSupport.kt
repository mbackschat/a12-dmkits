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
