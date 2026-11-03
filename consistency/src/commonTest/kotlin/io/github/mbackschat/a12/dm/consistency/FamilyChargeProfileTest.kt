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
 * without anyone remembering to add a row. Since the walk moved into the dispatcher a row is one CELL
 * rather than one family, so a per-declaration family contributes one row per declaration.
 *
 * **Its fixture is deliberately the whole shape rather than a minimal one.** A model with one field of each
 * kind makes every family charge one body and hides the distribution; this one carries four kinds of
 * declaration so a family that only wants fields demonstrably leaves the rest alone.
 */
class FamilyChargeProfileTest {

    private val source = ModelSource(
        """
        {"header":{"modelVersion":"29.4.0","id":"profile","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
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
    private fun profile(): Map<CellKey, Set<FactRead>> {
        val tracked = TrackedFactBase(prepared(), ReadTranscript())
        val scheduled = schedule(tracked)
        // Through `whileProducing`, because `ScheduledCell.produce` is the raw lambda: the bucketing lives on
        // the store's side, so calling it directly would charge everything to the composition and measure a
        // profile of nothing.
        scheduled.forEach { cell -> tracked.whileProducing(cell.key) { cell.produce() } }
        return scheduled.associate { it.key to tracked.observedWhileProducing(it.key) }
    }

    /** How a failing row names itself, there being no name in the registry to name it by. */
    private fun CellKey.label(): String =
        (producer.completes.joinToString(", ").ifEmpty { "the type-definition disclosure" }) +
            (at?.let { " @${it.path}" } ?: "")

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
    fun aModelWideFamilyChargesTheSetItFoundEveryBodyIn() {
        val profile = profile().filterKeys { it.producer is CheckProducer.ModelWide }
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
            "no model-wide family charged any body on this fixture, so the case above is vacuous",
        )
    }

    @Test
    fun aPerDeclarationFamilyChargesTheDeclarationItAnsweredAbout() {
        val profile = profile().filterKeys { it.producer !is CheckProducer.ModelWide }
        val unkeyed = profile.filterKeys { key ->
            FactRead.DeclarationRow(key.at!!.path, key.at.amongSamePath) !in profile.getValue(key)
        }.keys.map { it.label() }

        // The soundness half of the finer key, and the only half there is. A cursor's name, kind, id and
        // origin are free reads — that is what charging the set once buys — so a cell that did not record
        // WHICH declaration it answered about would be reused for a declaration whose kind had changed at
        // that path. `CheckCellKeyTest` holds the pair that proves it; this holds the denominator.
        assertEquals(emptyList(), unkeyed,
            "a per-declaration cell must charge its own declaration's row, or it is keyed on less than it read")

        // **The claim this row does NOT make, corrected 2026-09-18.** It used to refuse a per-declaration
        // cell that charged [FactRead.DeclarationSetShape] at all, on the reasoning that such a cell falls
        // whenever any declaration is added or removed. That is a REUSE property and was mistaken for a
        // soundness condition: a cell keyed on the shape, its own row and every body it opened is a
        // function of everything it read, which is the store's whole argument. The refusal would have
        // blocked every family that asks the set what an operand resolved to, and those are most of them.
        assertTrue(profile.isNotEmpty(), "no producer is registered per declaration, so this row is vacuous")
        assertTrue(
            profile.values.any { reads -> reads.any { it is FactRead.DeclarationContent } },
            "no per-declaration family charged a body, so it cannot have looked at its declaration",
        )
    }

    @Test
    fun aFieldScopedFamilyLeavesTheRuleAndComputationBodiesAlone() {
        val charged = profile()
            .entries
            .single { CheckFamily.ENUM_CATEGORY_ALIGNMENT in it.key.producer.completes }
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
