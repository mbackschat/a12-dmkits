package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import java.util.Locale
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine differential — **does a FIXED (unstarred) group operand's extent exclude an over-limit row, on every
 * carrier that admits such an operand?** a12-kernel-lean's `EXP-2026-08-31-02`; our own over-limit locks
 * ([OverRepetitionFormalGateDiffTest]) cover only the STARRED carriers, so this shape was unmeasured here.
 *
 * **The instrument is an exact-value read, not a fired/silent read.** A count that is *unavailable* leaves both
 * `> 0` and `< 1` silent, so a single positive rule cannot separate `unknown` from `0` — the point a12-kernel-lean
 * made, and the reason this file probes a candidate LADDER (`== 0`, `== 1`, `== 2`) per carrier and reports which
 * member fires. Exactly one firing means the value is definite and equal to it; none firing means the carrier
 * could not evaluate. A boolean "did it fire" would have conflated the two on every row.
 *
 * **Every row carries its in-capacity control**, the identical cell one index lower, because a carrier that
 * simply cannot see this subtree would also answer `0` on the over-limit document.
 *
 * **Why the conditions are raw strings here.** The typed surface offers no unstarred-`GroupRef` factory for four
 * of these six carriers (`numberOfValueInFields`, `sum`, `minValue`/`maxValue`, `numberOfDifferentValues` all take
 * an `AggregateOperand`, whose group arm is the STARRED `GroupListRef`), while the kernel admits all of them —
 * measured in this file's own admission ladder. That gap is recorded in `DEV-API-GAPS` and is a separate slice;
 * the shape under test is precisely one the typed surface cannot express, which is when the laws tiers use the
 * raw form.
 *
 * Own domain (e-commerce returns processing).
 */
class GroupOperandOverLimitExtentDiffTest {

    private companion object {
        const val RETURN = "/Return"
        const val REVIEWER = "/Return/Reviewer"
        const val PARCELS = "/Return/Notes/Parcels"
        const val TAG = "/Return/Notes/Parcels/Tag"
        const val CARTONS = "/Return/Notes/Parcels/Cartons"
        const val LABEL = "/Return/Notes/Parcels/Cartons/Label"
        const val CHECKS = "/Return/Flags/Checks"
        const val VERIFIED = "/Return/Flags/Checks/Verified"
        const val CHARGES = "/Return/Amounts/Charges"
        const val FEE = "/Return/Amounts/Charges/Fee"
        const val CODE = "GOL"
        const val UNAVAILABLE = "no candidate fired — the carrier could not evaluate"
    }

    /**
     * Three sibling shells under one root, one per operand kind the request names: an all-String one with two
     * repetition levels, an all-Boolean one, and an all-Number one. Each shell is itself NONREPEATABLE — it is the
     * fixed group operand under test — and every repeatable level below it caps at 2, so index 3 is over-limit.
     */
    private val base = DocumentModelDraft.named("returns").locale("en_US")
        .rootGroup("Return", Consumer { r ->
            r.stringField("Reviewer", Consumer { })
                .group("Notes", Consumer<GroupBuilder> { n ->
                    n.group("Parcels", Consumer<GroupBuilder> { p ->
                        p.repeatable(2).stringField("Tag", Consumer { })
                            .group("Cartons", Consumer<GroupBuilder> { c ->
                                c.repeatable(2).stringField("Label", Consumer { })
                            })
                    })
                })
                .group("Flags", Consumer<GroupBuilder> { f ->
                    f.group("Checks", Consumer<GroupBuilder> { c ->
                        c.repeatable(2).field("Verified", FieldKind.BOOLEAN)
                    })
                })
                .group("Amounts", Consumer<GroupBuilder> { a ->
                    a.group("Charges", Consumer<GroupBuilder> { c ->
                        c.repeatable(2).field("Fee", FieldKind.NUMBER)
                    })
                })
        }).build().toJson()

    /** Mirrors one document build across the kernel doc and the interpreter document from one call site. */
    private class Both(model: String) {
        val kDoc = RuntimeLaws.doc().group(RETURN, 1)
        val iDoc: Document = ModelLoader.load(model).newDocument().group(RETURN, 1)

        fun field(path: String, v: String, vararg reps: Int) {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps)
        }

        fun group(path: String, vararg reps: Int) {
            kDoc.group(path, *reps); iDoc.group(path, *reps)
        }
    }

    private fun modelWith(condition: String) =
        RuntimeLaws.withRule(base, RETURN, "OverLimitProbe", "../Reviewer", CODE,
            "FieldFilled(Reviewer) And $condition")

    /**
     * Runs one condition on one document through all three engines and returns whether the probe rule fired,
     * failing if they disagree — on the COMPLETE (code @ pointer) message set, not merely on this rule's presence,
     * so a divergence in the accompanying formal messages cannot pass as agreement.
     */
    private fun fires(condition: String, build: (Both) -> Unit): Boolean {
        val model = modelWith(condition)
        val def = ModelLoader.load(model)
        val both = Both(model).also(build)
        val dynamic = RuntimeLaws.messages(model, both.kDoc).map { "${it.code()}@${it.field()}" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val static = engine.messagesModern(Both(model).also(build).kDoc, null, Locale.US)
            .map { "${it.code()}@${it.field()}" }.sorted()
        val interp = DmInterpreter(def).validateFull(both.iDoc).map { "${it.code}@${it.errorPath}" }.sorted()
        assertEquals(dynamic, static, "$condition — static-Java vs dynamic-Groovy")
        assertEquals(dynamic, interp, "$condition — INTERPRETER vs dynamic-Groovy")
        return dynamic.any { it.startsWith("$CODE@") }
    }

    /**
     * The exact value the carrier answers, read off a ladder of candidate equalities — or [UNAVAILABLE] when none
     * fires. Two firings would mean the ladder itself is broken, so it is reported rather than silently reduced.
     */
    private fun read(expr: String, candidates: List<String>, build: (Both) -> Unit): String {
        val fired = candidates.filter { fires("$expr == $it", build) }
        return when (fired.size) {
            0 -> UNAVAILABLE
            1 -> fired.single()
            else -> "AMBIGUOUS $fired"
        }
    }

    // ---- the documents ----

    /**
     * Every level takes a coordinate, the fixed ones included — `/Return/Notes/Parcels/Tag` is four, not two.
     * The kernel document builder rejects a short coordinate list outright, which is how this was caught.
     */
    /** In-capacity rows instantiated and EMPTY; the only filled cell sits in the over-limit row. */
    private fun onlyOverLimitOuter(b: Both) {
        b.field(REVIEWER, "yes", 1, 1)
        b.group(PARCELS, 1, 1, 1); b.group(PARCELS, 1, 1, 2)
        b.field(TAG, "KEEP", 1, 1, 3, 1)
    }

    /** The same cell one index lower — the in-capacity control for [onlyOverLimitOuter]. */
    private fun onlyInCapOuter(b: Both) {
        b.field(REVIEWER, "yes", 1, 1)
        b.group(PARCELS, 1, 1, 1)
        b.field(TAG, "KEEP", 1, 1, 2, 1)
    }

    /** The same separation one repetition level deeper: the only filled cell is in an over-limit INNER row. */
    private fun onlyOverLimitInner(b: Both) {
        b.field(REVIEWER, "yes", 1, 1)
        b.group(PARCELS, 1, 1, 1)
        b.group(CARTONS, 1, 1, 1, 1); b.group(CARTONS, 1, 1, 1, 2)
        b.field(LABEL, "KEEP", 1, 1, 1, 3, 1)
    }

    private fun onlyInCapInner(b: Both) {
        b.field(REVIEWER, "yes", 1, 1)
        b.group(PARCELS, 1, 1, 1)
        b.group(CARTONS, 1, 1, 1, 1)
        b.field(LABEL, "KEEP", 1, 1, 1, 2, 1)
    }

    private fun boolOverLimit(b: Both) {
        b.field(REVIEWER, "yes", 1, 1)
        b.group(CHECKS, 1, 1, 1); b.group(CHECKS, 1, 1, 2)
        b.field(VERIFIED, "true", 1, 1, 3, 1)
    }

    private fun boolInCap(b: Both) {
        b.field(REVIEWER, "yes", 1, 1)
        b.group(CHECKS, 1, 1, 1)
        b.field(VERIFIED, "true", 1, 1, 2, 1)
    }

    /**
     * The aggregates need a MIXED document rather than an only-over-limit one: on an empty domain "excluded" and
     * "unavailable" would both be candidate-free, so the discriminator is whether the over-limit value is in the
     * total. In-capacity row 1 holds 5, the over-limit row 3 holds 7 — 5 if excluded, 12 if summed in.
     */
    private fun feeInCapFiveOverLimitSeven(b: Both) {
        b.field(REVIEWER, "yes", 1, 1)
        b.field(FEE, "5", 1, 1, 1, 1)
        b.group(CHARGES, 1, 1, 2)
        b.field(FEE, "7", 1, 1, 3, 1)
    }

    /** The reverse ordering, so `MinValue` is discriminating too: in-capacity 7, over-limit 5. */
    private fun feeInCapSevenOverLimitFive(b: Both) {
        b.field(REVIEWER, "yes", 1, 1)
        b.field(FEE, "7", 1, 1, 1, 1)
        b.group(CHARGES, 1, 1, 2)
        b.field(FEE, "5", 1, 1, 3, 1)
    }

    /** A non-numeric cell in an IN-CAPACITY row: inside the extent, so it should poison the aggregate. */
    private fun malformedInCap(b: Both) {
        b.field(REVIEWER, "yes", 1, 1)
        b.field(FEE, "abc", 1, 1, 1, 1)
        b.group(CHARGES, 1, 1, 2)
    }

    /** The same non-numeric cell in the OVER-LIMIT row, with a good in-capacity value beside it. */
    private fun malformedOverLimit(b: Both) {
        b.field(REVIEWER, "yes", 1, 1)
        b.field(FEE, "5", 1, 1, 1, 1)
        b.group(CHARGES, 1, 1, 2)
        b.field(FEE, "abc", 1, 1, 3, 1)
    }

    /** Both values in capacity — the control that proves the operand sees a value it is allowed to see. */
    private fun feeBothInCapacity(b: Both) {
        b.field(REVIEWER, "yes", 1, 1)
        b.field(FEE, "5", 1, 1, 1, 1)
        b.field(FEE, "7", 1, 1, 2, 1)
    }

    /** No repeatable row at all under the Number shell — the empty-domain case. */
    private fun noChargeRow(b: Both) = b.field(REVIEWER, "yes", 1, 1)

    // ---- the measurements ----

    /**
     * The carrier a12-kernel-lean measured, replicated on our own fixture at both repetition depths — the
     * baseline the other carriers are compared against, and our first measurement of this shape at all.
     */
    @Test
    fun theFillCountOverAFixedGroupOperandExcludesAnOverLimitCellAtEitherDepth() {
        val counts = listOf("0", "1")
        val actual = linkedMapOf(
            "outer — only content over-limit" to read("NumberOfFilledFields(Notes)", counts, ::onlyOverLimitOuter),
            "outer — in-capacity control" to read("NumberOfFilledFields(Notes)", counts, ::onlyInCapOuter),
            "inner — only content over-limit" to read("NumberOfFilledFields(Notes)", counts, ::onlyOverLimitInner),
            "inner — in-capacity control" to read("NumberOfFilledFields(Notes)", counts, ::onlyInCapInner),
        )
        assertEquals(
            linkedMapOf(
                "outer — only content over-limit" to "0",
                "outer — in-capacity control" to "1",
                "inner — only content over-limit" to "0",
                "inner — in-capacity control" to "1",
            ),
            actual,
            "the over-limit cell is outside the fixed group operand's extent, and definitely so — not unavailable",
        )
    }

    /**
     * `EXP-2026-08-31-02`'s three requested observations: a value count over a String group operand, the same over
     * a Boolean one, and the value aggregates over a homogeneous Number one. Each against its own control.
     */
    @Test
    fun theSameExclusionHoldsForTheValueCountBooleanAndAggregateCarriers() {
        val counts = listOf("0", "1")
        val actual = linkedMapOf(
            "value count, String — only content over-limit"
                to read("NumberOfValueInFields(\"KEEP\" In Notes)", counts, ::onlyOverLimitOuter),
            "value count, String — in-capacity control"
                to read("NumberOfValueInFields(\"KEEP\" In Notes)", counts, ::onlyInCapOuter),
            "value count, Boolean — only content over-limit"
                to read("NumberOfValueInFields(True In Flags)", counts, ::boolOverLimit),
            "value count, Boolean — in-capacity control"
                to read("NumberOfValueInFields(True In Flags)", counts, ::boolInCap),
            "Sum — in-capacity 5, over-limit 7"
                to read("Sum(Amounts)", listOf("5", "7", "12"), ::feeInCapFiveOverLimitSeven),
            "MaxValue — in-capacity 5, over-limit 7"
                to read("MaxValue(Amounts)", listOf("5", "7"), ::feeInCapFiveOverLimitSeven),
            "MinValue — in-capacity 7, over-limit 5"
                to read("MinValue(Amounts)", listOf("5", "7"), ::feeInCapSevenOverLimitFive),
            "NumberOfDifferentValues — in-capacity 5, over-limit 7"
                to read("NumberOfDifferentValues(Amounts)", listOf("1", "2"), ::feeInCapFiveOverLimitSeven),
        )
        assertEquals(
            linkedMapOf(
                "value count, String — only content over-limit" to "0",
                "value count, String — in-capacity control" to "1",
                "value count, Boolean — only content over-limit" to "0",
                "value count, Boolean — in-capacity control" to "1",
                "Sum — in-capacity 5, over-limit 7" to "5",
                "MaxValue — in-capacity 5, over-limit 7" to "5",
                "MinValue — in-capacity 7, over-limit 5" to "7",
                "NumberOfDifferentValues — in-capacity 5, over-limit 7" to "1",
            ),
            actual,
            "the extent is the group operand's, not one carrier's — every carrier excludes the over-limit cell",
        )
    }

    /**
     * The discriminator that separates the two accounts of the rows above, and the `limit` a12-kernel-lean's
     * checkpoint names. "Definitely 0" is consistent with *removed from the extent* and also with *tolerated as
     * unavailable*, if unavailable cells simply do not count. They are not the same mechanism, and a malformed
     * cell separates them: inside the extent a bad cell must make the aggregate non-evaluable, so if the
     * over-limit position merely tolerated bad cells, both rows below would read alike.
     *
     * <p>They do not. An in-capacity malformed cell leaves every candidate silent — the aggregate is unavailable —
     * while the identical cell one index above capacity leaves the aggregate definite at the in-capacity value.
     * So the over-limit cell is not classified and excused; it is not in the domain being classified.
     */
    @Test
    fun anInCapacityMalformedCellPoisonsTheAggregateWhereAnOverLimitOneDoesNot() {
        val candidates = listOf("0", "5", "7", "12")
        val actual = linkedMapOf(
            "malformed IN CAPACITY" to read("Sum(Amounts)", candidates, ::malformedInCap),
            "the same cell OVER LIMIT" to read("Sum(Amounts)", candidates, ::malformedOverLimit),
        )
        assertEquals(
            linkedMapOf(
                "malformed IN CAPACITY" to UNAVAILABLE,
                "the same cell OVER LIMIT" to "5",
            ),
            actual,
            "an over-limit cell is outside the domain, not an excused member of it",
        )
    }

    /**
     * **The STARRED operand answers identically to the fixed one** — closing this file's own stated limit rather
     * than inheriting across it. a12-kernel-lean measured it first (`SPEC-2026-08-31-10`) and it cost them three
     * conformance cases: they had split fixed from starred to claim only what our measurement covered, and the
     * only thing supporting the starred side was a case written from their implementation.
     *
     * <p>Asserted as an EQUALITY between the two spellings in one run, not as two tables to be compared by
     * memory. Each pair holds the document fixed and varies only the operand's spelling, which is the whole
     * claim; the both-in-capacity control keeps `5` from reading as "the operand sees nothing".
     */
    @Test
    fun aSTARREDGroupOperandHasTheSameExtentAsTheFixedOne() {
        val counts = listOf("0", "1")
        val sums = listOf("0", "5", "7", "12")
        val pairs = listOf(
            Triple("fill count — only content over-limit", ::onlyOverLimitOuter, counts),
            Triple("fill count — in-capacity control", ::onlyInCapOuter, counts),
        ).map { (label, doc, ladder) ->
            label to Pair(read("NumberOfFilledFields(/Return/Notes)", ladder, doc),
                read("NumberOfFilledFields(/Return/Notes/Parcels*)", ladder, doc))
        } + listOf(
            Triple("Sum — in-capacity 5, over-limit 7", ::feeInCapFiveOverLimitSeven, sums),
            Triple("Sum — both in capacity, control", ::feeBothInCapacity, sums),
            Triple("Sum — malformed in capacity", ::malformedInCap, sums),
            Triple("Sum — malformed over limit", ::malformedOverLimit, sums),
        ).map { (label, doc, ladder) ->
            label to Pair(read("Sum(/Return/Amounts)", ladder, doc),
                read("Sum(/Return/Amounts/Charges*)", ladder, doc))
        }

        val differing = pairs.filter { it.second.first != it.second.second }
        assertEquals(emptyList<Pair<String, Pair<String, String>>>(), differing,
            "the starred spelling must answer exactly as the fixed one — (fixed, starred) shown for each row")
        assertEquals(
            linkedMapOf(
                "fill count — only content over-limit" to "0",
                "fill count — in-capacity control" to "1",
                "Sum — in-capacity 5, over-limit 7" to "5",
                "Sum — both in capacity, control" to "12",
                "Sum — malformed in capacity" to UNAVAILABLE,
                "Sum — malformed over limit" to "5",
            ),
            LinkedHashMap(pairs.associate { it.first to it.second.first }),
            "and both spellings answer what the fixed operand was already measured to answer",
        )
    }

    /**
     * **`Sum` over an EMPTY in-capacity domain is a definite zero, not unavailable** — the limit both projects
     * stated and neither closed. Our earlier ladder deliberately avoided the empty domain by mixing values, and
     * a12-kernel-lean's reproduction of the same rows carried no `== 0` member, so on three documents `Sum` read
     * unavailable in exactly the way a definite `0` also would. One ladder member settles it.
     *
     * <p>The only-over-limit document is the row that matters: it is empty *after* the capacity projection while
     * carrying content in the document, so it separates "nothing to add" from "something I refuse to add".
     */
    @Test
    fun aSumOverAnEmptyInCapacityDomainIsADefiniteZero() {
        val ladder = listOf("0", "5", "7", "12")
        assertEquals(
            linkedMapOf(
                "no repeatable row at all — fixed" to "0",
                "no repeatable row at all — starred" to "0",
                "only content OVER LIMIT — fixed" to "0",
                "only content OVER LIMIT — starred" to "0",
            ),
            linkedMapOf(
                "no repeatable row at all — fixed" to read("Sum(/Return/Amounts)", ladder, ::noChargeRow),
                "no repeatable row at all — starred"
                    to read("Sum(/Return/Amounts/Charges*)", ladder, ::noChargeRow),
                "only content OVER LIMIT — fixed" to read("Sum(/Return/Amounts)", ladder, ::onlyFeeOverLimit),
                "only content OVER LIMIT — starred"
                    to read("Sum(/Return/Amounts/Charges*)", ladder, ::onlyFeeOverLimit),
            ),
            "an empty domain sums to zero, so the over-limit row is excluded AND the result stays definite",
        )
    }

    /**
     * The malformed split on the COUNT carrier, which a12-kernel-lean asked for: its own conformance comment says
     * outright that the count's behaviour there is its account of the shared extent rather than a measurement, and
     * our row was on `Sum`. Same shape, different carrier — a claim that reuses a mechanism across carriers needs
     * its own measurement, which is the rule that produced the request.
     */
    @Test
    fun theMalformedSplitHoldsOnTheCOUNTCarrierToo() {
        val ladder = listOf("0", "1", "2")
        assertEquals(
            linkedMapOf(
                "malformed IN CAPACITY — fixed" to UNAVAILABLE,
                "malformed IN CAPACITY — starred" to UNAVAILABLE,
                "the same cell OVER LIMIT — fixed" to "1",
                "the same cell OVER LIMIT — starred" to "1",
            ),
            linkedMapOf(
                "malformed IN CAPACITY — fixed"
                    to read("NumberOfFilledFields(/Return/Amounts)", ladder, ::malformedInCap),
                "malformed IN CAPACITY — starred"
                    to read("NumberOfFilledFields(/Return/Amounts/Charges*)", ladder, ::malformedInCap),
                "the same cell OVER LIMIT — fixed"
                    to read("NumberOfFilledFields(/Return/Amounts)", ladder, ::malformedOverLimit),
                "the same cell OVER LIMIT — starred"
                    to read("NumberOfFilledFields(/Return/Amounts/Charges*)", ladder, ::malformedOverLimit),
            ),
            "the count carrier splits where Sum splits — measured, not carried across from Sum",
        )
    }

    /** The only-over-limit Number document: one value, above capacity, nothing in capacity. */
    private fun onlyFeeOverLimit(b: Both) {
        b.field(REVIEWER, "yes", 1, 1)
        b.group(CHARGES, 1, 1, 1); b.group(CHARGES, 1, 1, 2)
        b.field(FEE, "7", 1, 1, 3, 1)
    }
}
