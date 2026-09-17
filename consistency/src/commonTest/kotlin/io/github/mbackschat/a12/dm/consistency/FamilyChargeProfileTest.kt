package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What each family CHARGES, which is the sizing input for per-family reuse.
 *
 * **The claim this exists to settle, and the one a file-level grep got wrong.** A family takes no argument,
 * so it has no path to start from and must walk the declaration set — that much is structural, and it is
 * why almost every row below charges [FactRead.DeclarationSetShape]. What a grep for `declarations()` cannot
 * see is that walking the set and opening every BODY are different reads: a family that filters by
 * `declaredKind`, which the cursor gives for free, opens only the bodies of the declarations it kept.
 * Per-family reuse rests entirely on that difference, so it is measured here rather than argued.
 *
 * **The measurement runs the PRODUCTION dispatch, which is the half an earlier version of this file could
 * not reach.** It used to build a transcript per family from a hand-written list of a hundred and five
 * calls — a second copy of `checkPrepared`'s dispatch, kept by care. Now [CHECK_PRODUCERS] runs every
 * producer against ONE fact base, each in its own bucket, exactly as a check does; so the thing measured is
 * the attribution the report is actually built on, and a family added to the dispatch is measured here
 * without anyone remembering to add a row.
 *
 * **Its fixture is deliberately the whole shape rather than a minimal one.** A model with one field of each
 * kind makes every family charge one body and hides the distribution; this one carries four kinds of
 * declaration so a family that only wants fields demonstrably leaves the rest alone.
 */
class FamilyChargeProfileTest {

    private val source = ModelSource(
        """
        {"header":{"id":"profile","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Tier","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[{"value":"BASIC"}]}}}},
            {"type":"Rule","id":"r0","name":"CodeRequired","Rule":{
              "errorEntityRelPath":"../Code","errorCondition":"FieldFilled(Code)",
              "errorCode":"code.required"}},
            {"type":"Computation","id":"c0","name":"Doubled","Computation":{
              "computedFieldRelPath":"../Total","computationAlternatives":[{"operation":"{1 + 1}"}]}},
            {"type":"Field","id":"f2","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":9,"elements":[
              {"type":"Field","id":"f3","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
            ]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    private fun prepared(): PreparedModel {
        val admitted = admit(source)
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible")
        return prepare(admitted)
    }

    /**
     * Every producer's bucket after one full dispatch — the two passes in the order `checkPrepared` runs
     * them, so a producer that reads different facts on its two channels is charged for both, as it is
     * there.
     */
    private fun profile(): Map<CheckProducer, Set<FactRead>> {
        val tracked = TrackedFactBase(prepared(), ReadTranscript())
        CHECK_PRODUCERS.forEach { it.findingsIn(tracked) }
        CHECK_PRODUCERS.forEach { it.gapsIn(tracked) }
        return CHECK_PRODUCERS.associateWith { tracked.observedWhileProducing(it) }
    }

    /** How a failing row names itself, there being no name in the registry to name it by. */
    private fun CheckProducer.label(): String =
        completes.joinToString(", ").ifEmpty { "the type-definition disclosure" }

    @Test
    fun everyProducerChargesSomething() {
        val silent = profile().filterValues { it.isEmpty() }.keys.map { it.label() }

        // A producer that charges nothing would be keyed on the empty transcript, and so would every other
        // producer that charges nothing — one cell shared by families that have nothing to do with each
        // other. It is also the shape an accidental constant return takes, which is why the emptiness is
        // worth refusing outright rather than special-casing in the store L-b3b builds.
        assertEquals(emptyList(), silent, "a producer read no fact at all, so its bucket cannot key a cell")
    }

    @Test
    fun noFamilyChargesABodyWithoutHavingChargedTheSetShape() {
        val profile = profile()
        val bodyWithoutShape = profile.filterValues { reads ->
            reads.any { it is FactRead.DeclarationContent } && FactRead.DeclarationSetShape !in reads
        }.keys.map { it.label() }

        // **The structural claim, corrected by this very measurement.** It first read "every family charges
        // the set shape, because a zero-argument family has no path to start from" — and four producers
        // charged no shape at all: `supportedCharacterFindings` and `hModelFormConstructGaps` read one
        // model-wide member, find it empty and return before any walk. A family reaching a DECLARATION is
        // the real invariant, since the only two routes to one (the walk and `declarationAt`) both charge
        // the shape, so a body charged without it would mean a third route the barrier cannot see.
        assertEquals(
            emptyList(),
            bodyWithoutShape,
            "a family charged a declaration's body without charging the set it found the declaration in",
        )

        // The positive control: an assertion over families that charge no body at all would hold vacuously.
        assertTrue(
            profile.values.any { reads -> reads.any { it is FactRead.DeclarationContent } },
            "no family charged any body on this fixture, so the case above is vacuous",
        )
    }

    @Test
    fun aFieldScopedFamilyLeavesTheRuleAndComputationBodiesAlone() {
        val charged = profile()
            .entries
            .single { CheckFamily.ENUM_CATEGORY_ALIGNMENT in it.key.completes }
            .value
            .filterIsInstance<FactRead.DeclarationContent>()
            .map { it.path }
            .toSet()

        // The property per-family reuse rests on, stated at the family whose scope is least arguable: the
        // enum pairing law reads a field's own `fieldType` and has no question about a rule at all. So an
        // edit to the rule's condition cannot invalidate this family's answer.
        assertEquals(
            setOf("/Order/Code", "/Order/Tier", "/Order/Total", "/Order/Amount", "/Order/Lines/Amount")
                .intersect(charged),
            charged,
            "an enum family must charge only field bodies, and it charged $charged",
        )
    }

    @Test
    fun theChargeProfileIsNotUniform() {
        val profile = profile()
        val bodies = profile.mapValues { (_, reads) -> reads.count { it is FactRead.DeclarationContent } }
        val declarations = prepared().elements.size

        // The whole sizing question in one assertion: if every family charged every body, a keystroke would
        // fell every cell and per-family reuse would buy nothing. At least one family must charge strictly
        // fewer, or L-b3b has no premise.
        assertTrue(
            bodies.values.any { it < declarations },
            "every one of ${bodies.size} producers charged all $declarations bodies, so per-family reuse " +
                "buys nothing — the narrowest are " +
                bodies.entries.sortedBy { it.value }.take(5).map { it.key.label() to it.value },
        )
    }
}
