package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import java.util.Locale
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
 * **Every operand here is authored through the typed surface, and the SPELLING is chosen by the renderer.**
 * An earlier version of this file built its conditions as raw strings and said the typed surface could not
 * express an unstarred group operand on four of these six carriers. That was wrong, and the error is worth
 * keeping because it produced a gap row that survived for days: `GroupListRef`'s star lives in its `flattened`
 * list, which `BoundModel.flattenGroup` leaves EMPTY for a bare path, so the unstarred operand was always
 * authorable. Every fixture that could have caught it used a REPEATABLE group, where the kernel refuses the
 * unstarred spelling anyway — so "the surface cannot author it" and "the kernel refuses it" produced the same
 * red, and only a non-repeatable shell separates them
 * (`rulekit.validate.laws.AggregateGroupOperandLawsTest.anUnstarredGroupOperandIsAuthorableOnEveryEntityListCarrier`).
 *
 * <p>The two spellings this file deliberately contrasts — `Sum(Amounts)` against `Sum(/Return/Amounts)` — are
 * ONE typed expression rendered two ways: [read] renders relative to the rule group and [readAbsolute] with no
 * base group. That is the property that made the raw form look necessary and is exactly what the renderer owns.
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

        const val NOTES = "/Return/Notes"
        const val FLAGS = "/Return/Flags"
        const val AMOUNTS = "/Return/Amounts"

        /** The starred FIELD list over the Number shell — the spelling the aggregate carriers are typed for. */
        const val FEES = "/Return/Amounts/Charges*/Fee"
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

    private val bound = BoundModel.ofJson(base)

    /** The three fixed (unstarred) shells and the starred field list, as typed operands. */
    private val notes = bound.flattenGroup(NOTES)
    private val flags = bound.flattenGroup(FLAGS)
    private val amounts = bound.flattenGroup(AMOUNTS)
    private val parcelsStar = bound.flattenGroup("$NOTES/Parcels*")
    private val chargesStar = bound.flattenGroup("$AMOUNTS/Charges*")
    private val feeList = bound.flatten(FEES)

    /** Both documents, holding the root row every fixture writes beneath. */
    private fun rooted(model: String) = Both(RuntimeLaws.doc(), ModelLoader.load(model).newDocument()).group(RETURN, 1)

    private fun modelWith(condition: String) =
        RuntimeLaws.withRule(base, RETURN, "OverLimitProbe", "../Reviewer", CODE,
            "FieldFilled(Reviewer) And $condition")

    /**
     * **The instrument's own admission control, and the ladder cannot be read without it.** `withRule` is a raw-JSON
     * splice landing downstream of every gate, so a condition the kernel would REFUSE produces a model whose probe
     * rule simply never runs — and a rule that never ran is silent, which on this ladder is indistinguishable from a
     * carrier that evaluated and disagreed. Every [UNAVAILABLE] row would then be unfalsifiable.
     *
     * <p>a12-kernel-lean measured exactly this failure on their own instrument: one of twenty `rule add`s was lost
     * and the missing member read as a kernel answer. Ours had the same hole, so admission is asserted per probe
     * model rather than assumed — [theLadderRefusesAModelTheKernelWouldNotAdmit] is its positive control.
     */
    private fun requireAdmitted(model: String, condition: String) {
        val refusals = RuntimeLaws.consistencyMessages(model)
        if (refusals.isNotEmpty()) {
            throw IllegalStateException(
                "the kernel refused the probe model, so a silent rule cannot be read as a carrier's answer: " +
                    "$condition — $refusals",
            )
        }
    }

    /**
     * Runs one condition on one document through all three engines and returns whether the probe rule fired,
     * failing if they disagree — on the COMPLETE (code @ pointer) message set, not merely on this rule's presence,
     * so a divergence in the accompanying formal messages cannot pass as agreement.
     */
    private fun fires(condition: String, build: (Both) -> Unit): Boolean {
        val model = modelWith(condition)
        requireAdmitted(model, condition)
        val def = ModelLoader.load(model)
        val both = rooted(model).also(build)
        val dynamic = RuntimeLaws.messages(model, both.kDoc).map { "${it.code()}@${it.field()}" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val static = engine.messagesModern(rooted(model).also(build).kDoc, null, Locale.US)
            .map { "${it.code()}@${it.field()}" }.sorted()
        val interp = DmInterpreter(def).validateFull(both.iDoc).map { "${it.code}@${it.errorPath}" }.sorted()
        assertEquals(dynamic, static, "$condition — static-Java vs dynamic-Groovy")
        assertEquals(dynamic, interp, "$condition — INTERPRETER vs dynamic-Groovy")
        return dynamic.any { it.startsWith("$CODE@") }
    }

    /**
     * The exact value the carrier answers, read off a ladder of candidate equalities — or [UNAVAILABLE] when none
     * fires. Two firings would mean the ladder itself is broken, so it is reported rather than silently reduced.
     *
     * <p>**Every ladder must carry the identity member, and the check is per CARRIER rather than per row.** a12-
     * kernel-lean published an extrema/distinct-count split against `Sum` on an emptied domain — extrema
     * `UNAVAILABLE`, `Sum` a definite `0` — and retracted it: their `Sum` ladders carried a `== 0` member and
     * their extrema ladders ran `5`/`7`, so the "split" was their own instrument's silence on the only value that
     * could have been the answer. Two of the ladders in this file had the same shortfall
     * ([theSameExclusionHoldsForTheValueCountBooleanAndAggregateCarriers]' extrema at `5`/`7`, its distinct count
     * at `1`/`2`), and they read a value rather than a silence purely because those documents were non-empty.
     *
     * <p>So the coverage is enforced here rather than left to whoever picks the next ladder: a carrier that
     * cannot express `0` cannot distinguish "the domain was empty" from "the carrier could not evaluate", which
     * is precisely the distinction every [UNAVAILABLE] row in this file claims to have made. Every carrier here is
     * numeric or a count; a future non-numeric one will trip this and must state its own identity value.
     */
    private fun read(expr: String, candidates: List<String>, build: (Both) -> Unit): String {
        check("0" in candidates) {
            "the ladder for '$expr' cannot express 0, so a silence could not be told from an empty domain: " +
                candidates
        }
        val fired = candidates.filter { fires("$expr == $it", build) }
        return when (fired.size) {
            0 -> UNAVAILABLE
            1 -> fired.single()
            else -> "AMBIGUOUS $fired"
        }
    }

    /** The rule-group-RELATIVE spelling — `Sum(Amounts)`, which is what an author writing in `/Return` gets. */
    private fun read(expr: NumberExpr, candidates: List<String>, build: (Both) -> Unit): String =
        read(RuntimeLaws.render(RETURN, expr.expression()), candidates, build)

    /**
     * The ABSOLUTE spelling of the same typed expression — `Sum(/Return/Amounts)`. The two differ only in the
     * renderer's base group, which is the whole point of the spelling rows below: one operand, two texts, so a
     * difference in outcome cannot be a difference in what was referenced.
     */
    private fun readAbsolute(expr: NumberExpr, candidates: List<String>, build: (Both) -> Unit): String =
        read(RuntimeLaws.render(expr.expression()), candidates, build)

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
    private fun noChargeRow(b: Both) {
        b.field(REVIEWER, "yes", 1, 1)
    }

    // ---- the measurements ----

    /**
     * The carrier a12-kernel-lean measured, replicated on our own fixture at both repetition depths — the
     * baseline the other carriers are compared against, and our first measurement of this shape at all.
     */
    @Test
    fun theFillCountOverAFixedGroupOperandExcludesAnOverLimitCellAtEitherDepth() {
        val counts = listOf("0", "1")
        val actual = linkedMapOf(
            "outer — only content over-limit" to read(Dsl.numberOfFilledFields(notes), counts, ::onlyOverLimitOuter),
            "outer — in-capacity control" to read(Dsl.numberOfFilledFields(notes), counts, ::onlyInCapOuter),
            "inner — only content over-limit" to read(Dsl.numberOfFilledFields(notes), counts, ::onlyOverLimitInner),
            "inner — in-capacity control" to read(Dsl.numberOfFilledFields(notes), counts, ::onlyInCapInner),
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
                to read(Dsl.numberOfValueInFields("KEEP", notes), counts, ::onlyOverLimitOuter),
            "value count, String — in-capacity control"
                to read(Dsl.numberOfValueInFields("KEEP", notes), counts, ::onlyInCapOuter),
            "value count, Boolean — only content over-limit"
                to read(Dsl.numberOfValueInFields(true, flags), counts, ::boolOverLimit),
            "value count, Boolean — in-capacity control"
                to read(Dsl.numberOfValueInFields(true, flags), counts, ::boolInCap),
            "Sum — in-capacity 5, over-limit 7"
                to read(Dsl.sum(amounts), listOf("0", "5", "7", "12"), ::feeInCapFiveOverLimitSeven),
            "MaxValue — in-capacity 5, over-limit 7"
                to read(Dsl.maxValue(amounts), listOf("0", "5", "7"), ::feeInCapFiveOverLimitSeven),
            "MinValue — in-capacity 7, over-limit 5"
                to read(Dsl.minValue(amounts), listOf("0", "5", "7"), ::feeInCapSevenOverLimitFive),
            "NumberOfDifferentValues — in-capacity 5, over-limit 7"
                to read(Dsl.numberOfDifferentValues(amounts), listOf("0", "1", "2"), ::feeInCapFiveOverLimitSeven),
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
            "malformed IN CAPACITY" to read(Dsl.sum(amounts), candidates, ::malformedInCap),
            "the same cell OVER LIMIT" to read(Dsl.sum(amounts), candidates, ::malformedOverLimit),
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
            label to Pair(readAbsolute(Dsl.numberOfFilledFields(notes), ladder, doc),
                readAbsolute(Dsl.numberOfFilledFields(parcelsStar), ladder, doc))
        } + listOf(
            Triple("Sum — in-capacity 5, over-limit 7", ::feeInCapFiveOverLimitSeven, sums),
            Triple("Sum — both in capacity, control", ::feeBothInCapacity, sums),
            Triple("Sum — malformed in capacity", ::malformedInCap, sums),
            Triple("Sum — malformed over limit", ::malformedOverLimit, sums),
        ).map { (label, doc, ladder) ->
            label to Pair(readAbsolute(Dsl.sum(amounts), ladder, doc),
                readAbsolute(Dsl.sum(chargesStar), ladder, doc))
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
     * **Over an EMPTY in-capacity domain every aggregate carrier is a definite zero, not unavailable** — the limit
     * both projects stated, and the one place a plausible reading survived longest on both sides. Our earlier
     * ladder deliberately avoided the empty domain by mixing values, and a12-kernel-lean's reproduction carried no
     * `== 0` member, so `Sum` read unavailable in exactly the way a definite `0` also would.
     *
     * <p>The extrema are the sharper half and the reason this covers the whole family rather than `Sum` alone.
     * a12-kernel-lean first reported an observed SPLIT here — `Sum` a definite `0`, the extrema and the distinct
     * count `UNAVAILABLE` — then retracted it: the six extrema/distinct ladders had no zero member at all, so the
     * split was one instrument's silence. That reading is also the *plausible* one, since an extremum over an
     * empty set is undefined in most languages, which is why it drew no scrutiny on either side. It is measured
     * here rather than adopted from their report, on our own fixture and through our own three engines.
     *
     * <p>The only-over-limit document is the row that matters: it is empty *after* the capacity projection while
     * carrying content in the document, so it separates "nothing to add" from "something I refuse to add".
     */
    @Test
    fun everyAggregateOverAnEmptyInCapacityDomainIsADefiniteZero() {
        val sums = listOf("0", "5", "7", "12")
        val counts = listOf("0", "1", "2")
        val carriers = linkedMapOf(
            "Sum" to Pair(Dsl.sum(feeList), sums),
            "MaxValue" to Pair(Dsl.maxValue(feeList), sums),
            "MinValue" to Pair(Dsl.minValue(feeList), sums),
            "NumberOfDifferentValues" to Pair(Dsl.numberOfDifferentValues(feeList), counts),
            "NumberOfFilledFields" to Pair(Dsl.numberOfFilledFields(feeList), counts),
            "NumberOfValueInFields" to Pair(Dsl.numberOfValueInFields(5L, feeList), counts),
        )
        val documents = linkedMapOf<String, (Both) -> Unit>(
            "no repeatable row at all" to ::noChargeRow,
            "only content OVER LIMIT" to ::onlyFeeOverLimit,
        )
        val actual = LinkedHashMap<String, String>()
        carriers.forEach { (carrier, probe) ->
            documents.forEach { (label, doc) ->
                actual["$carrier — $label"] = read(probe.first, probe.second, doc)
            }
        }
        assertEquals(
            LinkedHashMap(carriers.keys.flatMap { c -> documents.keys.map { d -> "$c — $d" to "0" } }.toMap()),
            actual,
            "an emptied domain answers zero on every carrier — so the over-limit row is excluded AND definite",
        )
        assertEquals(
            linkedMapOf(
                "Sum — fixed group spelling" to "0",
                "Sum — starred group spelling" to "0",
            ),
            linkedMapOf(
                "Sum — fixed group spelling" to readAbsolute(Dsl.sum(amounts), sums, ::onlyFeeOverLimit),
                "Sum — starred group spelling" to readAbsolute(Dsl.sum(chargesStar), sums, ::onlyFeeOverLimit),
            ),
            "and the group spellings answer as the field one, on the document the capacity projection empties",
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
                    to readAbsolute(Dsl.numberOfFilledFields(amounts), ladder, ::malformedInCap),
                "malformed IN CAPACITY — starred"
                    to readAbsolute(Dsl.numberOfFilledFields(chargesStar), ladder, ::malformedInCap),
                "the same cell OVER LIMIT — fixed"
                    to readAbsolute(Dsl.numberOfFilledFields(amounts), ladder, ::malformedOverLimit),
                "the same cell OVER LIMIT — starred"
                    to readAbsolute(Dsl.numberOfFilledFields(chargesStar), ladder, ::malformedOverLimit),
            ),
            "the count carrier splits where Sum splits — measured, not carried across from Sum",
        )
    }

    /**
     * **The starred FIELD operand — the form these carriers are actually designed for, and the one every row above
     * skipped.** This file's rows are group operands, fixed and starred, and "group operand" was allowed to stand
     * for "operand". The substitution is not a near miss: the catalog types all five carriers over a *field* list
     * (`Sum` takes a `number-field-list`, the extrema a `sortable-field-list`, `NumberOfDifferentValues` a
     * `homogeneous-field-list`), and the typed surface's `AggregateOperand` reaches a group only via the starred
     * `GroupListRef` — so `Charges*` + `Fee` is the primary spelling and the group operand is the variant.
     *
     * <p>Each row's ladder is chosen so the two readings differ. The value count needs the REVERSED document
     * (in-capacity 7, over-limit 5) because counting `5` over the forward one answers `1` whether or not the
     * over-limit cell is in the extent, and a row that cannot distinguish them measures nothing.
     */
    @Test
    fun theSTARREDFIELDOperandExcludesTheOverLimitCellOnEveryAggregateCarrier() {
        val sums = listOf("0", "5", "7", "12")
        val counts = listOf("0", "1", "2")
        assertEquals(
            linkedMapOf(
                "Sum — in-capacity 5, over-limit 7" to "5",
                "Sum — both in capacity, control" to "12",
                "MaxValue — in-capacity 5, over-limit 7" to "5",
                "MaxValue — both in capacity, control" to "7",
                "MinValue — in-capacity 7, over-limit 5" to "7",
                "MinValue — both in capacity, control" to "5",
                "NumberOfDifferentValues — in-capacity 5, over-limit 7" to "1",
                "NumberOfDifferentValues — both in capacity, control" to "2",
                "NumberOfValueInFields 5 — in-capacity 7, over-limit 5" to "0",
                "NumberOfValueInFields 5 — both in capacity, control" to "1",
                "Sum — malformed in capacity" to UNAVAILABLE,
                "Sum — the same cell over limit" to "5",
            ),
            linkedMapOf(
                "Sum — in-capacity 5, over-limit 7" to read(Dsl.sum(feeList), sums, ::feeInCapFiveOverLimitSeven),
                "Sum — both in capacity, control" to read(Dsl.sum(feeList), sums, ::feeBothInCapacity),
                "MaxValue — in-capacity 5, over-limit 7"
                    to read(Dsl.maxValue(feeList), sums, ::feeInCapFiveOverLimitSeven),
                "MaxValue — both in capacity, control" to read(Dsl.maxValue(feeList), sums, ::feeBothInCapacity),
                "MinValue — in-capacity 7, over-limit 5"
                    to read(Dsl.minValue(feeList), sums, ::feeInCapSevenOverLimitFive),
                "MinValue — both in capacity, control" to read(Dsl.minValue(feeList), sums, ::feeBothInCapacity),
                "NumberOfDifferentValues — in-capacity 5, over-limit 7"
                    to read(Dsl.numberOfDifferentValues(feeList), counts, ::feeInCapFiveOverLimitSeven),
                "NumberOfDifferentValues — both in capacity, control"
                    to read(Dsl.numberOfDifferentValues(feeList), counts, ::feeBothInCapacity),
                "NumberOfValueInFields 5 — in-capacity 7, over-limit 5"
                    to read(Dsl.numberOfValueInFields(5L, feeList), counts, ::feeInCapSevenOverLimitFive),
                "NumberOfValueInFields 5 — both in capacity, control"
                    to read(Dsl.numberOfValueInFields(5L, feeList), counts, ::feeBothInCapacity),
                "Sum — malformed in capacity" to read(Dsl.sum(feeList), sums, ::malformedInCap),
                "Sum — the same cell over limit" to read(Dsl.sum(feeList), sums, ::malformedOverLimit),
            ),
            "the extent belongs to the operand, and the field spelling answers exactly as the group spelling did",
        )
    }

    /**
     * **The positive control for [requireAdmitted]** — without it every [UNAVAILABLE] row above is unfalsifiable,
     * so the guard needs a case proving it actually fires. `Sum` over the all-String group is refused by the
     * kernel's model gate, and it is refused in exactly the shape the ladder walks: a group operand under an
     * aggregate carrier, which is why a silent rule here would have read as "the carrier could not evaluate".
     */
    @Test
    fun theLadderRefusesAModelTheKernelWouldNotAdmit() {
        val sumOverStrings = RuntimeLaws.render(RETURN, Dsl.sum(notes).expression())
        val refused = assertThrows(IllegalStateException::class.java) {
            fires("$sumOverStrings == 0", ::feeBothInCapacity)
        }
        assertTrue(
            refused.message!!.contains(sumOverStrings),
            "the refusal must name the condition it refused, or the ladder cannot say which row is unmeasured",
        )
    }

    /** The only-over-limit Number document: one value, above capacity, nothing in capacity. */
    private fun onlyFeeOverLimit(b: Both) {
        b.field(REVIEWER, "yes", 1, 1)
        b.group(CHARGES, 1, 1, 1); b.group(CHARGES, 1, 1, 2)
        b.field(FEE, "7", 1, 1, 3, 1)
    }
}
