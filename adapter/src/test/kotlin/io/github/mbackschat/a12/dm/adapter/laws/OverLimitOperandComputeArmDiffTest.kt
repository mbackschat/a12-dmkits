package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine differential — **the COMPUTATION arm of the over-limit operand extent.** Its sibling
 * [GroupOperandOverLimitExtentDiffTest] drives every row through `RuntimeLaws.withRule`, i.e. the VALIDATION arm
 * only, and shipped with a limit paragraph that never said so; a12-kernel-lean's `SPEC-2026-08-31-11` named the
 * gap on both sides at once. So this file is the other arm, and it is not a transcription of the first: under
 * `compute` the kernel answers with an exact value rather than a fired/silent bit, which makes a ladder
 * unnecessary and — the point a12-kernel-lean flagged as the reason to bother — separates the mechanism on a
 * **well-formed** document.
 *
 * <p>**Why that matters.** On the validation arm only the malformed pair distinguishes *removed from the domain*
 * from *classified unavailable*: both readings answer `0`. Under `compute` the over-limit cell's formal error is
 * visible in its own channel (`formalErrorsInOperands`) while the computation still returns a definite value, so
 * "the formal error is present in the operand and does not reach the result" is one observation on one document
 * with no bad cell in it at all.
 *
 * <p>**One document spelling, three engines.** Each case is a single A12 Document JSON string: both kernel
 * strategies deserialize it (`KernelEngine.instance(String)` / `JavaKernelEngine.instance(String)`) and the
 * interpreter reads the same text (`ModelDef.readDocumentJson`, and the public façade for the diagnostic
 * channel). Two hand-mirrored builders would let a divergence in the fixture pass as a divergence in the kernel.
 *
 * <p>**Why the operations are raw strings.** One of the two operands under test is the UNSTARRED group form,
 * which the typed surface cannot express at all (`DEV-API-GAPS` DG42 — the aggregates' group arm is the starred
 * `GroupListRef`), and the kernel-side model builder every differential in this package shares takes the
 * operation as text precisely so the tier can author shapes the typed builders refuse. The fixture itself is
 * built through `DocumentModelDraft`, and the documents are A12's own canonical Document JSON.
 *
 * <p>Own domain (insurance claim payouts). The repeatable group carries **two** number fields on purpose — see
 * [theGroupOperandNamesTheGroupsWholeSubtreeNotTheOneFieldAOneFieldFixtureCannotSeparate].
 */
class OverLimitOperandComputeArmDiffTest {

    private companion object {
        const val CLAIM = "/Claim"
        const val TOTAL = "/Claim/Total"
        const val AMOUNTS = "Payouts/Items*/Amount"
        const val PARTS = "Payouts/Items*/Splits*/Part"
        const val OVER_REPETITION_ROW = "zuGrosseZeile"
        const val OVER_REPETITION_CONTEXT = "zuGrosseKontextnummer"

        /** Both codes an over-limit operand row draws: the row itself, and the operand cell whose
         *  ancestry the row supplies. Sorted, because both channels are compared as sorted lists. */
        val OVER_LIMIT_OPERAND = listOf(OVER_REPETITION_CONTEXT, OVER_REPETITION_ROW)
        const val MALFORMED_NUMBER = "zahlHatUngueltigeZeichen"

        /** No computed outcome at all — the state an unavailable computation reaches, as distinct from a zero. */
        const val ABSENT = "no outcome for the computed field"

        val COORDINATE = Regex("""\[\d+]""")
    }

    /**
     * `Items` caps at 2, so array index 3 is over-limit. `Total` is the computation target and is never present in
     * any document below, which is what keeps every reported outcome a genuine change rather than a re-store.
     */
    private val base = DocumentModelDraft.named("claims").locale("en_US")
        .rootGroup("Claim", Consumer { c ->
            c.stringField("Adjuster", Consumer { })
                .field("Total", FieldKind.NUMBER)
                .group("Payouts", Consumer<GroupBuilder> { p ->
                    p.group("Items", Consumer<GroupBuilder> { i ->
                        i.repeatable(2).field("Amount", FieldKind.NUMBER)
                            .field("Surcharge", FieldKind.NUMBER)
                            .group("Splits", Consumer<GroupBuilder> { s ->
                                s.repeatable(2).field("Part", FieldKind.NUMBER)
                            })
                    })
                })
                .group("Notes", Consumer<GroupBuilder> { n ->
                    n.repeatable(2).stringField("Memo", Consumer { })
                })
        }).build().toJson()

    private fun modelSumming(operand: String): String {
        val model = RuntimeLaws.withComputation(base, CLAIM, "TotalComp", "../Total", "Sum($operand)")
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model),
            "the kernel must admit the model, or an absent outcome is the gate's silence and not a compute answer")
        return model
    }

    // ---- the documents, one canonical A12 Document JSON each ----

    /** Row 1 in capacity and well-formed, row 2 instantiated empty, row 3 above capacity and well-formed. */
    private val overLimitWellFormed =
        """{"Claim":{"Adjuster":"AJ","Payouts":{"Items":[{"Amount":5},{},{"Amount":7}]}}}"""

    /** Both values in capacity — the control that proves the operand can see a value at all. */
    private val bothInCapacity =
        """{"Claim":{"Adjuster":"AJ","Payouts":{"Items":[{"Amount":5},{"Amount":7}]}}}"""

    /** The same two rows plus a surcharge, so a group operand's extent is separable from its one field. */
    private val bothInCapacityWithSurcharge =
        """{"Claim":{"Adjuster":"AJ","Payouts":{"Items":[{"Amount":5,"Surcharge":3},{"Amount":7}]}}}"""

    /** A non-numeric cell IN CAPACITY — inside the extent, so the aggregate cannot be evaluated. */
    private val malformedInCapacity =
        """{"Claim":{"Adjuster":"AJ","Payouts":{"Items":[{"Amount":"abc"},{}]}}}"""

    /** The identical cell ABOVE capacity, with a good in-capacity value beside it. */
    private val malformedOverLimit =
        """{"Claim":{"Adjuster":"AJ","Payouts":{"Items":[{"Amount":5},{},{"Amount":"abc"}]}}}"""

    /** Content present in the document but entirely above capacity — empty only AFTER the projection. */
    private val emptiedByCapacity =
        """{"Claim":{"Adjuster":"AJ","Payouts":{"Items":[{},{},{"Amount":7}]}}}"""

    /** No repeatable row instantiated at all — the never-populated domain. */
    private val noRow = """{"Claim":{"Adjuster":"AJ","Payouts":{}}}"""

    /**
     * A clean operand beside an over-limit row in a group NO computation reads, and that row is EMPTY so only the
     * group walk can speak about it. The discriminator for what the operand channel's scope actually is.
     */
    private val unrelatedOverLimitRow =
        """{"Claim":{"Adjuster":"AJ","Payouts":{"Items":[{"Amount":5}]},"Notes":[{},{},{}]}}"""

    /**
     * The operand is two repetition levels down and the over-limit row is the OUTER one, which is in capacity at
     * the inner level. The discriminator for whether the channel's scope reaches an operand's whole ancestry or
     * only its immediate parent group.
     */
    private val deepOperandUnderOverLimitAncestor =
        """{"Claim":{"Adjuster":"AJ","Payouts":{"Items":[{},{},{"Splits":[{"Part":9}]}]}}}"""

    /** The same cell with the outer row in capacity — the control for the deep case. */
    private val deepOperandInCapacity =
        """{"Claim":{"Adjuster":"AJ","Payouts":{"Items":[{"Splits":[{"Part":9}]}]}}}"""

    // ---- the instrument ----

    /**
     * The computed outcome for `Total`, agreed by all three engines — `"VALUE|5"`, `"CLEARED"`, `"ERRORED|…"`, or
     * [ABSENT]. Dynamic Groovy is normative; static Java must agree independently before the interpreter is
     * compared, and the interpreter's report-all result is projected onto the kernel's change granularity first
     * (the standing compute-granularity ruling, not a divergence).
     */
    private fun outcome(model: String, documentJson: String): String {
        val dynamic = RuntimeLaws.kernelEngine(model)
        val static = RuntimeLaws.kernelJavaEngine(model)
        val fromDynamic = totalOf(dynamic.computationSignaturesSorted(dynamic.instance(documentJson)))
        assertEquals(fromDynamic, totalOf(static.computationSignaturesSorted(static.instance(documentJson))),
            "static-Java vs dynamic-Groovy — $documentJson")

        val def = ModelLoader.load(model)
        val doc = def.readDocumentJson(documentJson)
        val signatures = DmInterpreter(def).compute(doc).map {
            val pointer = formatPointer(it.path, it.indices)
            when (it.outcome) {
                ComputeOutcome.VALUE -> "$pointer|VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "$pointer|CLEARED"
                ComputeOutcome.ERRORED -> "$pointer|ERRORED|${it.value ?: ""}"
            }
        }
        assertEquals(fromDynamic, totalOf(kernelComputeGranularity(signatures, doc, def)),
            "INTERPRETER vs dynamic-Groovy — $documentJson")
        return fromDynamic
    }

    /**
     * Both engines address the target with its ancestry coordinates; the outcome is per computed FIELD here, so
     * the coordinates carry no information and are dropped rather than pinned in two dialects.
     *
     * <p>`singleOrNull` is deliberately not used: it would fold "two outcomes for one target" into [ABSENT],
     * which is the same silence-reads-as-an-answer defect the whole slice is about. `Total` sits in the root
     * group so exactly one instance is expected, and more than one is an instrument failure, not a measurement.
     */
    private fun totalOf(signatures: List<String>): String {
        val matches = signatures.filter { COORDINATE.replace(it.substringBefore('|'), "") == TOTAL }
        check(matches.size <= 1) { "expected at most one outcome for $TOTAL, got $matches" }
        return matches.firstOrNull()?.substringAfter('|') ?: ABSENT
    }

    /**
     * The codes the operand channel carries — agreed by both kernel strategies and by the interpreter's public
     * report at `code@pointer`, then reduced to codes for the tables. `formalErrorsInOperands` is the kernel's
     * own separate channel: a code here is a defect the computation READ, which is exactly why it can coexist
     * with a definite result.
     */
    private fun operandErrorCodes(model: String, documentJson: String): List<String> =
        operandErrorSignatures(model, documentJson).map { it.substringBefore('@') }

    /** The same reading with the SITE retained — what the tables reduce, and what one test pins directly. */
    private fun operandErrorSignatures(model: String, documentJson: String): List<String> {
        val dynamic = RuntimeLaws.kernelEngine(model)
        val static = RuntimeLaws.kernelJavaEngine(model)
        val fromDynamic = dynamic.resultChannels(dynamic.instance(documentJson))
            .formalErrorsInOperands().map { "${it.code()}@${it.field()}" }.sorted()
        assertEquals(fromDynamic, static.resultChannels(static.instance(documentJson))
            .formalErrorsInOperands().map { "${it.code()}@${it.field()}" }.sorted(),
            "static-Java vs dynamic-Groovy operand channel — $documentJson")
        assertEquals(fromDynamic, interpreterOperandSignatures(model, documentJson),
            "INTERPRETER operand channel vs dynamic-Groovy — $documentJson")
        // The three engines are held to the SITE as well as the code; the tables below then read the codes,
        // because the ancestry case draws one code at two different sites and a code-only agreement would let a
        // message attached to the wrong cell pass. Multiset, not set, for the same reason.
        //
        // Both sides project through the HOUSE pointer form, which drops a repeatable group node's own row index
        // (IG145) — and `zuGrosseZeile` sits on exactly such a node. So the site agreement is real but coarser
        // than it reads: it would not separate two over-limit rows of one group. It is not widened to the
        // faithful form here because the kernel's `Msg.field()` is already house-projected, so a faithful
        // comparison would need the typed pointer on both sides, which is IG145's own fix and not this slice.
        return fromDynamic
    }

    /**
     * The same channel on our own PUBLIC report. This comparison is the one that found IG146: the compute path
     * filtered the over-repetition walk with a predicate holding operand FIELD paths, so its group walk — the one
     * that reports the over-limit ROW — could not fire for any model, and every over-limit operand reached the
     * channel one code short. The validation arm was unaffected and green throughout, which is why nothing else
     * in the estate could see it.
     */
    private fun interpreterOperandSignatures(model: String, documentJson: String): List<String> {
        val prepared = PreparedModel.loadExpandedJson(model)
        val report = prepared.createInterpreter().compute(prepared.documents.readDocument(documentJson))
        return report.formalErrorsInOperands
            .map { "${it.code}@${formatPointer(it.errorPath.fullName, it.errorPath.repetitionIndexes)}" }
            .sorted()
    }

    // ---- the measurements ----

    /**
     * **The pair a12-kernel-lean named as the stronger lock: the operand's formal error is PRESENT and the
     * computed value is DEFINITE, on a document with no malformed cell in it.** The over-limit row is
     * well-formed, so nothing here is invalid in the ordinary sense; the only defect is the address, and it stays
     * in the operand channel while `Total` computes to the in-capacity total.
     *
     * <p>The control holds the operand fixed and moves the same value one index down. That matters more than it
     * looks: it is what separates "the over-limit cell is excluded" from "this operand cannot see this subtree",
     * and it is the reason both rows are asserted together rather than the first alone.
     */
    @Test
    fun theOverLimitOperandsFormalErrorRidesItsOwnChannelWhileTheComputationStaysDefinite() {
        val model = modelSumming(AMOUNTS)
        assertEquals(
            linkedMapOf(
                "over-limit row present — computed" to "VALUE|5",
                "over-limit row present — operand channel" to OVER_LIMIT_OPERAND,
                "both rows in capacity — computed" to "VALUE|12",
                "both rows in capacity — operand channel" to emptyList<String>(),
            ),
            linkedMapOf(
                "over-limit row present — computed" to outcome(model, overLimitWellFormed),
                "over-limit row present — operand channel" to operandErrorCodes(model, overLimitWellFormed),
                "both rows in capacity — computed" to outcome(model, bothInCapacity),
                "both rows in capacity — operand channel" to operandErrorCodes(model, bothInCapacity),
            ),
            "the formal error is present in the operand and does not reach the result",
        )
    }

    /**
     * **An unavailable computation is ABSENT, never a rendered zero** — which is what makes the zeros in
     * [aComputationOverAnEmptiedDomainIsADefiniteZeroAndTheAbsentCaseProvesIt] real rather than a projection of
     * unavailability onto the identity value. a12-kernel-lean measured `outcomes: []` on their estate and said so
     * explicitly rather than claiming it for ours; this is the same observation taken here, on our fixture,
     * through our three engines.
     *
     * <p>The pair is the discriminator: the same non-numeric cell one index apart. In capacity it is inside the
     * extent, the aggregate cannot be evaluated, and `Total` gets no outcome at all while
     * `zahlHatUngueltigeZeichen` rides the operand channel. Above capacity it is outside the extent, so the
     * operand channel reports the ADDRESS defect instead of the value defect and the computation stays definite.
     */
    @Test
    fun anUnavailableComputationIsAbsentRatherThanZero() {
        val model = modelSumming(AMOUNTS)
        assertEquals(
            linkedMapOf(
                "malformed IN CAPACITY — computed" to ABSENT,
                "malformed IN CAPACITY — operand channel" to listOf(MALFORMED_NUMBER),
                "the same cell OVER LIMIT — computed" to "VALUE|5",
                "the same cell OVER LIMIT — operand channel" to OVER_LIMIT_OPERAND,
            ),
            linkedMapOf(
                "malformed IN CAPACITY — computed" to outcome(model, malformedInCapacity),
                "malformed IN CAPACITY — operand channel" to operandErrorCodes(model, malformedInCapacity),
                "the same cell OVER LIMIT — computed" to outcome(model, malformedOverLimit),
                "the same cell OVER LIMIT — operand channel" to operandErrorCodes(model, malformedOverLimit),
            ),
            "an unavailable computation produces no outcome, so an emitted zero is always a real zero",
        )
    }

    /**
     * **The emptied domain computes a definite zero on the compute arm too, and the previous test is why that
     * reading is safe.** The validation arm could only say "the ladder's `== 0` member fired"; here the zero is
     * the returned value, and because an unavailable computation is absent rather than zero, a zero cannot be
     * unavailability wearing the identity value.
     *
     * <p>Both documents are needed. The never-populated one is the ordinary empty domain; the over-limit one
     * carries content in the document and is empty only *after* the capacity projection, which is the row that
     * says the exclusion does not make the aggregate non-evaluable.
     */
    @Test
    fun aComputationOverAnEmptiedDomainIsADefiniteZeroAndTheAbsentCaseProvesIt() {
        val model = modelSumming(AMOUNTS)
        assertEquals(
            linkedMapOf(
                "no repeatable row at all — computed" to "VALUE|0",
                "no repeatable row at all — operand channel" to emptyList<String>(),
                "content present but all OVER LIMIT — computed" to "VALUE|0",
                "content present but all OVER LIMIT — operand channel" to OVER_LIMIT_OPERAND,
            ),
            linkedMapOf(
                "no repeatable row at all — computed" to outcome(model, noRow),
                "no repeatable row at all — operand channel" to operandErrorCodes(model, noRow),
                "content present but all OVER LIMIT — computed" to outcome(model, emptiedByCapacity),
                "content present but all OVER LIMIT — operand channel" to operandErrorCodes(model, emptiedByCapacity),
            ),
            "an emptied domain computes zero, and the absent case above is what makes that zero readable",
        )
    }

    /**
     * **What the operand channel's over-repetition SCOPE actually is — measured, because two rules fit every row
     * above.** Every document so far puts the over-limit row inside the group the computation reads, so
     * "the channel reports over-repetition wherever an operand is affected" and "the channel reports every
     * over-repetition in the document" are indistinguishable across the whole set. That is the confound shape
     * that is constant across a row set rather than visible inside any one row.
     *
     * <p>The discriminating document keeps the operand clean and puts an over-limit row in `Notes`, which no
     * computation reads, and leaves that row EMPTY so no valued-field walk can speak for it. An unfiltered
     * channel reports `zuGrosseZeile` there; an operand-scoped one reports nothing.
     */
    @Test
    fun theOperandChannelReportsOnlyOverRepetitionThatReachesAnOperand() {
        val model = modelSumming(AMOUNTS)
        assertEquals(
            emptyList<String>(),
            operandErrorCodes(model, unrelatedOverLimitRow),
            "an over-limit row in a group no computation reads is not an error in the OPERANDS",
        )
        assertEquals("VALUE|5", outcome(model, unrelatedOverLimitRow),
            "and it does not disturb the computation either")
    }

    /**
     * **…and the scope reaches an operand's whole ANCESTRY, not just its parent group** — the second rule that
     * fits every row above, since in all of them the over-limit row IS the operand's parent. Here the operand
     * sits two repetition levels down and the outer row is the one above capacity, in capacity at the inner
     * level. Asserted against the in-capacity control so a silent channel cannot pass for a scoped one.
     *
     * <p>The ancestry walk shows itself in the count: **three** sites, two sharing a code — `zuGrosseZeile` at
     * `Items` (its own level is the one over its maximum), and `zuGrosseKontextnummer` twice, once for the
     * `Splits` row whose exceeded level is an ancestor's rather than its own, and once for the `Part` cell.
     */
    @Test
    fun theOperandChannelsScopeReachesAnOperandsWholeAncestryNotOnlyItsParentGroup() {
        val model = modelSumming(PARTS)
        assertEquals(
            linkedMapOf(
                "outer row OVER LIMIT — computed" to "VALUE|0",
                "outer row OVER LIMIT — operand channel"
                    to listOf(OVER_REPETITION_CONTEXT, OVER_REPETITION_CONTEXT, OVER_REPETITION_ROW),
                "outer row in capacity — computed" to "VALUE|9",
                "outer row in capacity — operand channel" to emptyList<String>(),
            ),
            linkedMapOf(
                "outer row OVER LIMIT — computed" to outcome(model, deepOperandUnderOverLimitAncestor),
                "outer row OVER LIMIT — operand channel"
                    to operandErrorCodes(model, deepOperandUnderOverLimitAncestor),
                "outer row in capacity — computed" to outcome(model, deepOperandInCapacity),
                "outer row in capacity — operand channel" to operandErrorCodes(model, deepOperandInCapacity),
            ),
            "an over-limit ANCESTOR of an operand reaches the operand channel; the operand's parent is not the bound",
        )
        // Which code lands on which site is READ here rather than inferred from the multiset above: the same
        // code appears twice, so "the row draws one and the cells the other" is an account the code list cannot
        // settle, and the finding this locks states the sites.
        //
        // The two GROUP sites show IG145 in the open: `Items` is really row 3 and `Splits` really row 1, and the
        // house form drops a group node's own row index while the FIELD site keeps `Splits[1]`. Pinned as the
        // engines actually project it, so the lock stays honest about which address dialect it compares.
        assertEquals(
            listOf(
                "zuGrosseKontextnummer@/Claim[1]/Payouts[1]/Items[3]/Splits",
                "zuGrosseKontextnummer@/Claim[1]/Payouts[1]/Items[3]/Splits[1]/Part",
                "zuGrosseZeile@/Claim[1]/Payouts[1]/Items",
            ),
            operandErrorSignatures(model, deepOperandUnderOverLimitAncestor),
            "the site of each code, agreed by all three engines",
        )
    }

    /**
     * **A GROUP operand names its group's whole subtree, and a one-field fixture cannot tell that from "it names
     * the one field the group has".** a12-kernel-lean hit exactly this on their estate: every group in their
     * fixtures owned a single field, so the two readings were indistinguishable across every row they held and
     * the over-general clause was one carrier away from shipping. Our sibling file is no better placed —
     * its `Charges` group has one number field, `Fee` — so the distinction is unmeasured there by luck rather
     * than by design, which is why this fixture gives `Items` two number fields.
     *
     * <p>`Surcharge` 3 sits beside `Amount` 5 in row 1, and `Amount` 7 in row 2. The field operand must answer
     * `12` and the group operand `15`; equal answers would mean the group form silently narrows to one field.
     */
    @Test
    fun theGroupOperandNamesTheGroupsWholeSubtreeNotTheOneFieldAOneFieldFixtureCannotSeparate() {
        assertEquals(
            linkedMapOf(
                "the starred FIELD operand — Amount only" to "VALUE|12",
                "the GROUP operand — every number field beneath it" to "VALUE|15",
            ),
            linkedMapOf(
                "the starred FIELD operand — Amount only"
                    to outcome(modelSumming(AMOUNTS), bothInCapacityWithSurcharge),
                "the GROUP operand — every number field beneath it"
                    to outcome(modelSumming("Payouts"), bothInCapacityWithSurcharge),
            ),
            "the two spellings are not interchangeable, and a single-field group hides it",
        )
    }
}
