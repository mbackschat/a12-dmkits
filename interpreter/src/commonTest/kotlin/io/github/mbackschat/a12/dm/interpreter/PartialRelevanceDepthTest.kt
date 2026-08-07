package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * **The relevance index must answer exactly what a linear scan of the relevant set would, at every LEGAL model
 * depth.** `validatePart`'s relevance query is an optimization of `relevant.any { matches(it, path, reps) }`, so
 * the only thing that makes it correct is agreeing with that predicate — and the first indexed implementation
 * silently disagreed past a depth nobody had measured.
 *
 * Kernel-free on purpose: this is an internal refactoring invariant, not a semantic question about A12, so the
 * oracle is the scan the index replaces rather than the kernel. The semantics of partial validation are pinned
 * separately by `adapter/.../laws/PartialValidation*DiffTest`.
 *
 * Two distinct defects live at this depth axis and both are guarded here, because they fail in opposite ways —
 * one silently wrong, one merely ruinous:
 *
 * - **Correctness.** A bitmask over `1 shl depth` overflows `Int` at depth 31 (`Int.MIN_VALUE`, so the probe
 *   loop never runs and every query answers "not relevant" — messages vanish), and past depth 32 Kotlin's
 *   `shl` masks the shift to its low five bits, so a handful of wrong combinations are tried instead.
 * - **Cost.** Enumerating `2^depth` wildcard/concrete combinations per query is exponential in a quantity the
 *   model contract does not bound at single digits: `ModelPreparation` admits depth 64 by default and 128 as
 *   its hard limit. The number of probes must be bounded by the relevant set the caller actually supplied.
 *
 * The bound that was asserted rather than measured ("depth is the model's nesting depth (single digits)") is
 * the reason both shipped, which is why the guard is written against the contract's limit and not against a
 * shape that happens to occur in the fixtures.
 */
class PartialRelevanceDepthTest {

    @Test
    fun aWildcardedDeepFieldIsRelevantPastTheIntegerShiftBoundary() {
        // 3 is the sanity case. The rest sit at or past the shift boundary, where the query's shallowest
        // matching bucket — the innermost GROUP, one level above the field — lands at 31 or beyond: 32 puts
        // it at 31 (bound overflows negative), 33 at 32 (shift wraps to zero), 36 at 35 (wraps to a small
        // nonzero). Depths 30 and 31 are deliberately absent even though they are equally wrong-adjacent:
        // their group bucket lands at 29/30, which the defective code enumerates with ~10^9 probes, so
        // including them made this test hang for ten minutes rather than fail in seconds. Cost is measured
        // separately below, at a depth that still returns.
        for (depth in listOf(3, 32, 33, 36)) {
            val def = nestedModel(depth)
            val field = fieldPath(depth)
            val doc = instance(def, depth)

            // Positive control: without it a malformed fixture reads exactly like the defect — both produce
            // no message, and the test would "pass" after a fix that changed nothing.
            assertEquals(
                setOf(CODE), DmInterpreter(def).validateFull(doc).map { it.code }.toSet(),
                "fixture control at depth $depth: the rule must fire under FULL validation, or this case " +
                    "cannot say anything about partial relevance",
            )

            val fired = DmInterpreter(def)
                .validatePart(doc, deepOnlyRelevant(depth))
                .messages.map { it.code }.toSet()

            assertEquals(
                setOf(CODE), fired,
                "at depth $depth a fully wildcarded relevant entity must make its own field relevant; the " +
                    "linear scan it replaces admits it at every depth, so the index must too",
            )
        }
    }

    @Test
    fun theRelevanceQueryDoesNotEnumerateCombinationsOfTheModelDepth() {
        // 2^24 probes per query is already ~17M; the contract admits 64, where an exponential never returns.
        // A wall-clock bound is crude but it is the only thing that separates "bounded by the relevant set"
        // from "bounded by the depth" without reaching into the private index.
        val depth = 24
        val def = nestedModel(depth)
        val doc = instance(def, depth)
        val relevant = deepOnlyRelevant(depth)
        val interpreter = DmInterpreter(def)

        interpreter.validatePart(doc, relevant)   // prime: parse + plan, outside the measured call
        val start = TimeSource.Monotonic.markNow()
        repeat(REPEATS) { interpreter.validatePart(doc, relevant) }
        val elapsed = start.elapsedNow().inWholeMilliseconds

        assertTrue(
            elapsed < 300,
            "$REPEATS partial validations of a depth-$depth model took ${elapsed}ms. The relevance query is " +
                "enumerating combinations of the DEPTH rather than probing the patterns the caller's relevant " +
                "set actually contains, so cost doubles per nesting level while the model contract admits 64.",
        )
    }

    private companion object {
        const val CODE = "DEEP"

        /** Few, because the failing shape costs seconds per call — the guard must fail fast, not hang. */
        const val REPEATS = 3

        /** `/G1/G2/.../G{depth-1}/F` — [depth] path parts, the field's own included. */
        fun fieldPath(depth: Int): String =
            (1 until depth).joinToString("", prefix = "") { "/G$it" } + "/F"

        fun groupPath(depth: Int): String = (1 until depth).joinToString("") { "/G$it" }

        /**
         * The INNERMOST group plus the field, wildcarded — deliberately not every ancestor.
         *
         * Two things force this shape, and both were learned by running the case rather than reasoning about
         * it. The field **alone** is not enough: the rule is gated on its group as well as its error cell, and
         * the linear scan this index replaced refuses a field-only set at every depth too, so that would test
         * nothing. And relevance at **every** ancestor is equally useless here — the query walks prefixes from
         * the root and stops at its first match, so a wildcard at depth 1 answers immediately and no deep
         * coordinate arithmetic ever runs. Only a set whose shallowest match is itself deep reaches it.
         */
        fun deepOnlyRelevant(depth: Int): Set<RelevantEntity> = setOf(
            RelevantEntity.anyRep(groupPath(depth)),
            RelevantEntity.anyRep(fieldPath(depth)),
        )

        /** Non-repeatable nesting: the defect is in the coordinate arithmetic, which every path part feeds. */
        fun nestedModel(depth: Int): ModelDef {
            val field = fieldPath(depth)
            val group = groupPath(depth)
            return ModelDef(
                listOf(FieldDef(field, Kind.STRING)),
                listOf(RuleDef("$group/R", group, "FieldFilled(F)", CODE, "F")),
                groupPaths = (1 until depth).map { d -> (1..d).joinToString("") { "/G$it" } }.toSet(),
            )
        }

        fun instance(def: ModelDef, depth: Int) = def.newDocument().also { doc ->
            for (d in 1 until depth) {
                doc.group((1..d).joinToString("") { "/G$it" }, *IntArray(d) { 1 })
            }
            doc.field(fieldPath(depth), "x", *IntArray(depth) { 1 })
        }
    }
}
