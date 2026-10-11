package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.OVER_REPETITION_CONTEXT
import io.github.mbackschat.a12.dm.interpreter.eval.OVER_REPETITION_ROW
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.regex.PatternRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The document-level precondition that lets the per-cell over-repetition gate be skipped** (IG127.1). The gate is
 * a `HashMap<String, PathInfo>` probe plus a bound walk that [ValueAdmissionCache.resultAt] runs BEFORE consulting
 * its value-shaped memo, so it cannot be memoized on the cell without reintroducing the key IF284 removed; it was
 * measured at 10.33 % of a full validation.
 *
 * [AutoChecks.overRepetitionGateNeeded] answers the one question that makes skipping sound, once per call. Two
 * groups of test live here, and the split matters because they fail for different reasons:
 *
 * - **the predicate** — documents it must classify correctly, each chosen so that a plausible cheaper formulation
 *   gets it wrong;
 * - **the wiring** — that a `false` answer actually skips each of the three consultation sites. Nothing end-to-end
 *   can cover these: deleting a guard restores the OLD behaviour, which is correct, so every behavioural test stays
 *   green. They are written as direct calls with the answer supplied, which is the only way the skip is falsifiable.
 */
class OverLimitAddressPresenceMultiplatformTest {

    // ── the predicate ────────────────────────────────────────────────────────────────────────────────

    /** The fast-path control: an ordinary in-cap document must need NO gate, or the skip never happens and the
     *  optimization is inert. This is the only test here that fails if the predicate always answers `true`. */
    @Test
    fun aWellFormedDocumentNeedsNoGate() {
        val doc = policyModel().newDocument().group(POLICY, 1)
            .group(RIDERS, 1, 1).field(RIDER_CODE, "RC-1", 1, 1, 1)
            .group(RIDERS, 1, 2).field(RIDER_CODE, "RC-2", 1, 2, 1)

        assertFalse(checksFor(policyModel()).overRepetitionGateNeeded(doc))
    }

    @Test
    fun anOverLimitRepeatableRowNeedsTheGate() {
        val doc = policyModel().newDocument().group(POLICY, 1)
            .group(RIDERS, 1, 1).group(RIDERS, 1, 2)
            .group(RIDERS, 1, 3).field(RIDER_CODE, "RC-3", 1, 3, 1)   // cap is 2

        assertTrue(checksFor(policyModel()).overRepetitionGateNeeded(doc))
    }

    /** A non-repeatable group carries an implicit maximum of 1, so a coordinate above 1 on one is over-limit too.
     *  No field is placed, so only the ROW scan can reach this. */
    @Test
    fun anOverLimitNonRepeatableCoordinateNeedsTheGate() {
        val doc = policyModel().newDocument().group(POLICY, 2).group(RIDERS, 2, 1)

        assertTrue(checksFor(policyModel()).overRepetitionGateNeeded(doc))
    }

    /**
     * `/Policy/Notes` is repeatable and UNBOUNDED, so it has no entry in
     * [ModelDef.effectiveGroupRepetitionMaximums] at all — yet its rows are over-limit here, at the `/Policy`
     * ancestor level. A predicate scanning only the bound map's own keys would have to reach this through the
     * ancestor's row rather than directly.
     *
     * **No field is placed in the row**, deliberately: with one, the CELL scan answers first and the case stops
     * discriminating the row scan at all. (It did, before a cold review pointed it out.)
     */
    @Test
    fun anUnboundedRepeatableRowUnderAnOverLimitAncestorNeedsTheGate() {
        val doc = policyModel().newDocument().group(POLICY, 2).group(NOTES, 2, 1)

        assertTrue(checksFor(policyModel()).overRepetitionGateNeeded(doc))
    }

    /**
     * **A maximum below 1, on an EMPTY document.** Neither scan has a single address to look at, so every
     * formulation that only inspects what the document CONTAINS answers "no gate needed" — and the all-1s address
     * [AutoChecks.mandatoryErrors] synthesises for an absolutely-required field would then skip a gate that a
     * maximum below 1 can still fail.
     *
     * The refusal is conservative rather than demonstrative, and the honest limit is worth stating: because
     * `declaredReps` makes a group repeatable, a field under a max-0 group always HAS a repeatable ancestor, so the
     * all-1s branch is not the one it takes. Whether a kernel model can declare `maxRepetitions = 0` at all is
     * unmeasured. The branch buys a soundness argument that needs no case analysis, at one comparison per call.
     */
    @Test
    fun aMaximumBelowOneNeedsTheGateEvenOnAnEmptyDocument() {
        val def = ModelDef(
            listOf(FieldDef(RIDER_CODE, Kind.STRING)),
            emptyList(),
            repeatableGroups = setOf(RIDERS),
            declaredReps = mapOf(RIDERS to 0),
            groupPaths = setOf(POLICY, RIDERS),
        )

        assertTrue(checksFor(def).overRepetitionGateNeeded(def.newDocument()))
    }

    /**
     * A staged document ([Document.withComputed], the index-default staging a FULL call performs) is a separate
     * [Document] with its own index, and `validate` computes the predicate on THAT one.
     *
     * **What this can and cannot lock**, because the distinction was overstated once: staging only ever COPIES
     * placements and adds index-default cells, so it cannot turn a `true` into a `false`, and no document can
     * therefore discriminate "computed on the staged copy" from "computed on the caller's". This is a regression
     * pin on the staged copy being scanned at all, not evidence for the choice of document.
     */
    @Test
    fun aStagedCopyOfAnOverLimitDocumentStillNeedsTheGate() {
        val doc = policyModel().newDocument().group(POLICY, 1)
            .group(RIDERS, 1, 1).group(RIDERS, 1, 2).group(RIDERS, 1, 3)

        val staged = doc.withComputed(mapOf((RIDER_CODE to listOf(1, 1, 1)) to "staged"))

        assertTrue(checksFor(policyModel()).overRepetitionGateNeeded(staged))
    }

    /**
     * **The invariant that makes the skip sound by construction.** The bound walk now exists twice — per-coordinate
     * in [ModelPathIndex.exceededBound] and hoisted per-path in `anyExceedsBound` — and a divergence would be
     * silently unsound in the dangerous direction: the predicate answers "no gate needed" while the gate, had it
     * run, would have fired.
     */
    @Test
    fun theHoistedBoundWalkAgreesWithThePerCoordinateOneOnEveryCoordinate() {
        val def = policyModel()
        val paths = ModelPathIndex(def)
        val coordinates = listOf(
            listOf(1, 1, 1), listOf(1, 2, 1), listOf(1, 3, 1),      // in cap, in cap, over cap
            listOf(2, 1, 1), listOf(2, 3, 1),                        // over-limit ancestor
        )
        for (path in listOf(RIDER_CODE, NOTE_TEXT)) {
            for (reps in coordinates) {
                assertEquals(
                    paths.exceededBound(path, reps) != null,
                    paths.anyExceedsBound(path, listOf(reps)),
                    "the two bound walks must agree at $path $reps",
                )
            }
            assertEquals(
                coordinates.any { paths.exceededBound(path, it) != null },
                paths.anyExceedsBound(path, coordinates),
                "and the hoisted walk must be the ANY of the per-coordinate one over $path",
            )
        }
    }

    // ── the fail-fast the skip must not swallow ──────────────────────────────────────────────────────

    /**
     * `ModelPathIndex.info` asserts that a consulted path is declared. Skipping the per-cell gate skips that
     * assertion too, which would turn a malformed placement into a silent pass on a clean document while still
     * throwing on an over-limit one — a nondeterministic-looking failure. The predicate's CELL scan resolves the
     * same `info`, so the assertion keeps firing, once per document instead of once per consultation.
     */
    @Test
    fun anUndeclaredValuedPathStillFailsFast() {
        val def = policyModel()
        val doc = def.newDocument().group(POLICY, 1).field(UNDECLARED, "x", 1, 1)

        val thrown = runCatching { DmInterpreter(def).validate(doc) }.exceptionOrNull()

        assertTrue(
            thrown != null && thrown.message?.contains(UNDECLARED) == true,
            "an undeclared path must still fail fast, got: $thrown",
        )
    }

    /**
     * **The regression a cold review caught.** A `maximums.isEmpty()` early return in the predicate looked free and
     * was not: it returns BEFORE either scan, so no path is resolved and the assertion above silently disappears —
     * for any model that declares no bounds at all, which the public [ModelDef] produces by default because
     * `groupPaths` defaults to empty. The document below then validated CLEANLY with a placement at an undeclared
     * path, which is exactly the silent pass the predicate's KDoc claims it prevents.
     */
    @Test
    fun anUndeclaredPathFailsFastEvenWhenTheModelDeclaresNoMaximums() {
        val def = ModelDef(listOf(FieldDef(RIDER_CODE, Kind.STRING)), emptyList())
        assertTrue(def.effectiveGroupRepetitionMaximums.isEmpty(), "the fixture's premise: no bound is declared")
        val doc = def.newDocument().field(UNDECLARED, "x", 1, 1)

        val thrown = runCatching { DmInterpreter(def).validate(doc) }.exceptionOrNull()

        assertTrue(
            thrown != null && thrown.message?.contains(UNDECLARED) == true,
            "no declared bound must not mean no declared-path check, got: $thrown",
        )
    }

    // ── the wiring: that a `false` answer actually skips each consultation site ──────────────────────

    /**
     * Site 1 — [ValueAdmissionCache.resultAt], the filled-cell gate. Told the gate is not needed, it must NOT
     * report over-repetition even for a cell that genuinely is over-limit. Deleting the guard makes this fail;
     * no behavioural test can, because deleting it restores the correct answer.
     */
    @Test
    fun theFilledCellGateIsSkippedWhenTheCallSaysItIsNotNeeded() {
        val checks = checksFor(policyModel())
        val overLimit = listOf(1, 3, 1)

        val skipped = ValueAdmissionCache(checks, "en_US", policyModel().newDocument(), overRepetitionGateNeeded = false)
            .resultAt(RIDER_CODE, overLimit, "RC-3")
        val run = ValueAdmissionCache(checks, "en_US", policyModel().newDocument(), overRepetitionGateNeeded = true)
            .resultAt(RIDER_CODE, overLimit, "RC-3")

        assertEquals(FormalResult.Valid, skipped, "gate not needed → the coordinate is never consulted")
        assertEquals(
            OVER_REPETITION_CONTEXT,
            ((run as? FormalResult.Invalid)?.failure as? FormalFailure.Generated)?.code,
            "and the same cell WITH the gate is over-repetition — so the skip is what the flag controls",
        )
    }

    /**
     * Site 2 — [AutoChecks.overRepetitionMessages], the emitter. Both of its loops compute `exceededBound` before
     * consulting relevance, so on an ordinary document it is two full scans that emit nothing.
     */
    @Test
    fun theEmitterIsSkippedWhenTheCallSaysTheGateIsNotNeeded() {
        val checks = checksFor(policyModel())
        val doc = policyModel().newDocument().group(POLICY, 1)
            .group(RIDERS, 1, 1).group(RIDERS, 1, 2).group(RIDERS, 1, 3)

        val skipped = checks.overRepetitionMessages(doc, "en_US", ALL_RELEVANT, gateNeeded = false)
        val run = checks.overRepetitionMessages(doc, "en_US", ALL_RELEVANT, gateNeeded = true)

        assertTrue(skipped.isEmpty(), "gate not needed → the emitter does not walk the document")
        assertTrue(run.any { it.code == OVER_REPETITION_ROW }, "and with the gate the same document reports its row")
    }

    // ── end to end ───────────────────────────────────────────────────────────────────────────────────

    /** The behaviour the whole change must not move: an over-limit document still reports its row. */
    @Test
    fun anOverLimitDocumentStillReportsItsRow() {
        val def = policyModel()
        val doc = def.newDocument().group(POLICY, 1)
            .group(RIDERS, 1, 1).group(RIDERS, 1, 2).group(RIDERS, 1, 3)

        val messages = DmInterpreter(def).validate(doc).messages

        assertEquals(1, messages.count { it.code == OVER_REPETITION_ROW }, "the over-limit row still emits zuGrosseZeile")
    }

    private fun policyModel() = ModelDef(
        listOf(FieldDef(RIDER_CODE, Kind.STRING), FieldDef(NOTE_TEXT, Kind.STRING)),
        emptyList(),
        repeatableGroups = setOf(RIDERS, NOTES),
        declaredReps = mapOf(RIDERS to 2),                 // NOTES is repeatable but UNBOUNDED
        groupPaths = setOf(POLICY, RIDERS, NOTES),
    )

    private fun checksFor(def: ModelDef) = AutoChecks(
        def, LegalCharset.BMP, emptyMap(), emptyMap(), PatternRegistry(), ModelPathIndex(def),
    ) { _, _, _ -> "" }

    private companion object {
        const val POLICY = "/Policy"
        const val RIDERS = "/Policy/Riders"
        const val RIDER_CODE = "/Policy/Riders/Code"
        const val NOTES = "/Policy/Notes"
        const val NOTE_TEXT = "/Policy/Notes/Text"
        const val UNDECLARED = "/Policy/Premium"
    }
}
