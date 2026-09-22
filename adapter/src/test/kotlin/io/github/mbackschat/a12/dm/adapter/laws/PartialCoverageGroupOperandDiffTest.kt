package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — **group operands under PARTIAL coverage**: the filled-group COUNT and the group PRESENCE
 * quantifiers read the caller's relevant set differently, and the split is invisible to `validateFull`.
 *
 * **The claim under test**, reported by a12-kernel-lean as `SPEC-2026-08-31-18` from the `validatePart`
 * observation this project built for them, and re-measured here rather than carried: under partial coverage
 * `NumberOfFilledGroups` needs **every field of every operand group** in the relevant set, while `GroupFilled`,
 * `AtLeastOneGroupFilled` and `NotAllGroupsFilled` decide from **the content inside the coverage**.
 *
 * **One coverage line separates them, and the four rules share their operands so the operator is the only
 * variable.** Over a `Billing` group holding a filled `Street` and an empty `City`, covering `Street` alone
 * fires the three presence quantifiers and leaves the count silent — while `validateFull` fires the count on
 * the *same document*. That last clause is what makes this a coverage law rather than a document law: without
 * the full-validation control the silence would be equally consistent with the count simply being false.
 *
 * **Why it matters here beyond conformance.** Both engines are held to it. A coverage rule the interpreter got
 * wrong would be invisible to every full-validation differential in the estate, which is exactly the blind spot
 * `validatePart` was added to cover.
 */
class PartialCoverageGroupOperandDiffTest {

    /**
     * The four rules under one coverage. The three presence quantifiers fire from the covered content; the
     * count does not, because one of its operand groups is only partly covered.
     */
    @Test
    fun theCountNeedsEveryOperandFieldCoveredWhileThePresenceQuantifiersReadWhatIsCovered() {
        assertEquals(
            setOf(GROUP_FILLED, AT_LEAST_ONE),
            firedUnder(coverStreetOnly),
            "covering one filled field of one operand group decides presence but not the count",
        )
    }

    /**
     * **`NotAllGroupsFilled` does NOT group with the other two presence quantifiers**, which is where our
     * measurement parts company with the account that prompted it. Under the coverage above it is silent, like
     * the count and unlike `GroupFilled`/`AtLeastOneGroupFilled`.
     *
     * <p>Two accounts fit that silence and they are not the same rule: (a) it needs every operand field
     * covered, as the count does; (b) it is an ordinary three-valued quantifier, decided only once the coverage
     * settles a member as UNFILLED — and `AtLeastOneGroupFilled` is decided by the one covered FILLED group
     * while `NotAllGroupsFilled` has no settled false member, `Shipping` lying wholly outside the coverage.
     *
     * <p>This coverage separates them: every field of `Shipping` (all empty, so it is decidably unfilled) plus
     * the one filled `Billing/Street`, leaving `Billing/City` uncovered. Account (a) predicts silence, because
     * an operand field is still uncovered. Account (b) predicts it FIRES, because a member is now known
     * unfilled. The count stays silent either way, which is what keeps it honest as a control.
     */
    @Test
    fun notAllGroupsFilledIsDecidedByASETTLEDUnfilledMemberRatherThanByTotalCoverage() {
        assertEquals(
            setOf(GROUP_FILLED, AT_LEAST_ONE, NOT_ALL),
            firedUnder(coverShippingAndOneBillingField),
            "a coverage that settles Shipping as unfilled decides NotAllGroupsFilled, though Billing/City — an "
                + "operand field — stays uncovered and the count stays silent",
        )
    }

    /**
     * The control that makes the silence a COVERAGE fact. The same document under full validation fires the
     * count as well, so the count is not merely false here — it is undecided under this coverage.
     */
    @Test
    fun fullValidationOnTheSameDocumentFiresTheCountToo() {
        assertEquals(
            setOf(GROUP_FILLED, AT_LEAST_ONE, NOT_ALL, COUNT),
            firedFull(),
            "validateFull cannot see a relevant set, so every rule the document satisfies fires",
        )
    }

    /** Covering both fields of both operand groups restores the count — the positive control on the mechanism. */
    @Test
    fun coveringEveryOperandFieldRestoresTheCount() {
        assertEquals(
            setOf(GROUP_FILLED, AT_LEAST_ONE, NOT_ALL, COUNT),
            firedUnder(coverEverything),
            "with every operand field relevant, partial validation agrees with full",
        )
    }

    /** Both engines agree on every coverage, so this is a shared law and not a kernel quirk we tolerate. */
    @Test
    fun theInterpreterAgreesWithTheKernelOnEveryCoverage() {
        listOf(
            "street only" to (coverStreetOnly to iCoverStreetOnly),
            "shipping settled, one billing field" to
                (coverShippingAndOneBillingField to iCoverShippingAndOneBillingField),
            "everything" to (coverEverything to iCoverEverything),
        ).forEach { (name, sets) ->
            val (kRel, iRel) = sets
            assertEquals(firedUnder(kRel), interpreterFiredUnder(iRel), "coverage '$name' — kernel vs interpreter")
        }
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun firedUnder(relevant: Set<Relevant>): Set<String> =
        RuntimeLaws.firedCodesPart(model, document(), relevant).filterTo(sortedSetOf()) { it in CODES }

    private fun firedFull(): Set<String> =
        RuntimeLaws.firedCodes(model, document()).filterTo(sortedSetOf()) { it in CODES }

    private fun interpreterFiredUnder(relevant: Set<RelevantEntity>): Set<String> {
        val def = ModelLoader.load(model)
        val doc = def.newDocument()
        doc.group(BILLING, 1, 1)
        doc.field(REF, "R-1", 1, 1)
        doc.field(STREET, "12 Alder Way", 1, 1, 1)
        return DmInterpreter(def).validatePart(doc, relevant).messages
            .map { it.code }.filterTo(sortedSetOf()) { it in CODES }
    }

    /** `Billing/Street` filled, `Billing/City` empty, `Shipping` untouched — one partly-filled operand group. */
    private fun document() = RuntimeLaws.doc()
        .group(ORDER, 1)
        .group(BILLING, 1, 1)
        .field(REF, "R-1", 1, 1)
        .field(STREET, "12 Alder Way", 1, 1, 1)

    private companion object {

        const val ORDER = "/Order"
        const val BILLING = "/Order/Billing"
        const val SHIPPING = "/Order/Shipping"
        const val REF = "/Order/Reference"
        const val STREET = "/Order/Billing/Street"
        const val CITY = "/Order/Billing/City"

        const val COUNT = "COUNT"
        const val GROUP_FILLED = "GROUP_FILLED"
        const val AT_LEAST_ONE = "AT_LEAST_ONE"
        const val NOT_ALL = "NOT_ALL"
        val CODES = setOf(COUNT, GROUP_FILLED, AT_LEAST_ONE, NOT_ALL)

        /**
         * Two address groups of two fields each. Both are NON-repeatable, so the coverage is the only thing
         * varying — a repeatable operand would add the capacity dimension this measurement is not about.
         */
        private val baseModel: String = DocumentModelDraft.named("coverage").locale("en_US")
            .rootGroup("Order") { order ->
                order.field("Reference", FieldKind.STRING)
                    .group("Billing") { g -> g.field("Street", FieldKind.STRING).field("City", FieldKind.STRING) }
                    .group("Shipping") { g -> g.field("Street", FieldKind.STRING).field("City", FieldKind.STRING) }
            }.build().toJson()

        /**
         * All four rules over the SAME two group operands (`GroupFilled` excepted, which takes one by
         * definition), so the operator is the only variable across the rows. Every condition is guarded by the
         * error field's own presence, which the kernel requires the condition to reference
         * (`MVK_ERROR_FIELD_NOT_REFERENCED`) and which is TRUE throughout, so it shifts nothing.
         */
        val model: String = run {
            val base = BoundModel.ofJson(baseModel)
            val ref = base.field(REF)
            val billing = base.group(BILLING)
            val shipping = base.group(SHIPPING)
            val guard = Dsl.fieldFilled(ref)
            fun render(c: Condition) = RuntimeLaws.render(c)
            var json = baseModel
            listOf(
                COUNT to render(guard.and(Dsl.numberOfFilledGroups(billing, shipping).gt(0))),
                GROUP_FILLED to render(guard.and(Dsl.groupFilled(billing))),
                AT_LEAST_ONE to render(guard.and(Dsl.atLeastOneGroupFilled(listOf(billing, shipping)))),
                NOT_ALL to render(guard.and(Dsl.notAllGroupsFilled(listOf(billing, shipping)))),
            ).forEach { (code, condition) ->
                json = RuntimeLaws.withRule(json, ORDER, code, "../Reference", code, condition)
            }
            json
        }

        /** The discriminating coverage: the error field, plus ONE filled field of ONE operand group. */
        val coverStreetOnly = setOf(Relevant.of(REF, 0, 0), Relevant.of(STREET, 0, 0, 0))
        val iCoverStreetOnly = setOf(RelevantEntity.anyRep(REF), RelevantEntity.anyRep(STREET))

        /**
         * The discriminating coverage: all of `Shipping` (decidably unfilled) plus one filled `Billing` field,
         * with `Billing/City` deliberately left out so total coverage and a settled member come apart.
         */
        val coverShippingAndOneBillingField = setOf(
            Relevant.of(REF, 0, 0),
            Relevant.of(STREET, 0, 0, 0),
            Relevant.of("$SHIPPING/Street", 0, 0, 0), Relevant.of("$SHIPPING/City", 0, 0, 0),
        )
        val iCoverShippingAndOneBillingField = setOf(
            RelevantEntity.anyRep(REF),
            RelevantEntity.anyRep(STREET),
            RelevantEntity.anyRep("$SHIPPING/Street"), RelevantEntity.anyRep("$SHIPPING/City"),
        )

        /** Every operand field of both groups, plus the error field. */
        val coverEverything = setOf(
            Relevant.of(REF, 0, 0),
            Relevant.of(STREET, 0, 0, 0), Relevant.of(CITY, 0, 0, 0),
            Relevant.of("$SHIPPING/Street", 0, 0, 0), Relevant.of("$SHIPPING/City", 0, 0, 0),
        )
        val iCoverEverything = setOf(
            RelevantEntity.anyRep(REF),
            RelevantEntity.anyRep(STREET), RelevantEntity.anyRep(CITY),
            RelevantEntity.anyRep("$SHIPPING/Street"), RelevantEntity.anyRep("$SHIPPING/City"),
        )
    }
}
