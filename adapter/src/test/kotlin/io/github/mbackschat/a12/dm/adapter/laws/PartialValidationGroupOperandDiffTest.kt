package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — **PARTIAL validation over a value-list quantifier whose operand is a GROUP**,
 * [IG136]'s last unmeasured extent.
 *
 * **Why the existing partial-validation lock cannot reach this.** `PartialValidationValueListDiffTest` measures
 * the extent gate over a starred FIELD operand, where the relevant set names the same path the operand does.
 * A GROUP operand is expanded BEFORE the per-operand pass, so `starExtentKnown` and `cellRelevant` are consulted
 * with the EXPANDED field paths and the per-side operand COUNT changes — while `omittedTail`, `extentUnknown` and
 * the filter-polarity effect are all per-OPERAND facts. Every value-list differential in the estate is FULL
 * validation, so nothing has ever checked whether the expansion changes either gate's verdict.
 *
 * **The two relevance shapes are what separate the readings.** A WILDCARD relevant entry on the group covers
 * every row of it; a CONCRETE-only entry names individual rows and therefore leaves the starred extent unknown.
 * If the expansion changed the extent verdict, the two would disagree between the group operand and its
 * equivalent explicit field list — which every row here asserts they do not.
 */
class PartialValidationGroupOperandDiffTest {

    private companion object {
        const val ACK = "/Order/Ack"
        const val ITEMS = "/Order/Items"
        const val TAG = "/Order/Items/Tag"
        const val NOTE = "/Order/Items/Note"
        const val TAG_STAR = "/Order/Items*/Tag"
        const val NOTE_STAR = "/Order/Items*/Note"
        const val CODE = "PVG"
    }

    /** `Items` holds TWO strings, so the group's expansion is a two-operand list rather than a relabelled star. */
    private val json = DocumentModelDraft.named("order").locale("en_US")
        .rootGroup("Order") { g ->
            g.stringField("Ack") { }
            g.group("Items") { it.repeatable(50).stringField("Tag") { }.stringField("Note") { } }
        }.build().toJson()

    private val ackK = Relevant.of(ACK, 0, 0)
    private val ackI = RelevantEntity.anyRep(ACK)

    /** Wildcard relevance on the group — complete extent for every row. */
    private val wildK = setOf(ackK, Relevant.of(ITEMS, 0, 0))
    private val wildI = setOf(ackI, RelevantEntity.anyRep(ITEMS))

    /** Concrete-only relevance — two named rows, so a starred extent stays unknown. */
    private val concreteK = setOf(ackK, Relevant.of(ITEMS, 0, 1), Relevant.of(ITEMS, 0, 2))
    private val concreteI = setOf(ackI, RelevantEntity(ITEMS, listOf(0, 1)), RelevantEntity(ITEMS, listOf(0, 2)))

    private val staticEngines = mutableMapOf<String, RuntimeLaws.JavaKernelEngine>()

    /** The fire set under PARTIAL validation, asserted equal across both kernel strategies and the interpreter. */
    private fun agree(
        rawCond: String,
        rows: List<Pair<String, String>>,
        kRel: Set<Relevant>,
        iRel: Set<RelevantEntity>,
        label: String,
    ): Set<String> {
        val cond = "FieldFilled($ACK) And ($rawCond)"
        val model = RuntimeLaws.withRule(json, "/Order", "PVGRule", "../Ack", CODE, cond)
        val def = ModelLoader.load(model)
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(ACK, "x", 1, 1)
        val iDoc = def.newDocument().group("/Order", 1).field(ACK, "x", 1, 1)
        rows.forEachIndexed { idx, (tag, note) ->
            val r = idx + 1
            kDoc.group(ITEMS, 1, r).field(TAG, tag, 1, r, 1).field(NOTE, note, 1, r, 1)
            iDoc.group(ITEMS, 1, r).field(TAG, tag, 1, r, 1).field(NOTE, note, 1, r, 1)
        }
        val dynamic = RuntimeLaws.messagesPart(model, kDoc, kRel).map { it.code() }
            .filter { it == CODE }.toSortedSet()
        val static = staticEngines.getOrPut(model) { RuntimeLaws.kernelJavaEngine(model) }
            .messagesPart(kDoc, kRel).map { it.code() }.filter { it == CODE }.toSortedSet()
        val interp = DmInterpreter(def).validatePart(iDoc, iRel).messages.map { it.code }
            .filter { it == CODE }.toSortedSet()
        assertEquals(dynamic, static, "$label — dynamic-Groovy=$dynamic static-Java=$static")
        assertEquals(dynamic, interp, "$label — dynamic-Groovy=$dynamic interp=$interp")
        return dynamic
    }

    /**
     * **THE DISCRIMINATOR.** The GROUP operand and its equivalent EXPLICIT field list must agree under BOTH
     * relevance shapes. Pairing them is what makes this a statement about the expansion rather than about the
     * operator: if expanding before the per-operand pass changed the extent verdict, exactly this pair would
     * come apart.
     */
    @Test
    fun theGroupOperandMatchesItsExplicitListUnderBothRelevanceShapes() {
        val rows = listOf("C" to "P", "D" to "Q")
        val group = "NoFieldValueIncludedInValueList($ITEMS* In \"A\")"
        val explicit = "NoFieldValueIncludedInValueList($TAG_STAR, $NOTE_STAR In \"A\")"

        val groupWild = agree(group, rows, wildK, wildI, "group operand, wildcard relevance")
        val explicitWild = agree(explicit, rows, wildK, wildI, "explicit list, wildcard relevance")
        assertEquals(explicitWild, groupWild,
            "a group operand's expansion must reach the same extent verdict as the list it expands to")

        val groupConcrete = agree(group, rows, concreteK, concreteI, "group operand, concrete-only relevance")
        val explicitConcrete = agree(explicit, rows, concreteK, concreteI, "explicit list, concrete relevance")
        assertEquals(explicitConcrete, groupConcrete,
            "…and the same under concrete-only relevance, where the starred extent is unknown")
    }

    /**
     * **THE NON-VACUITY CONTROL.** The two relevance shapes must actually produce DIFFERENT verdicts on this
     * condition — otherwise the row above compares two identical answers and would pass against an engine that
     * ignored relevance entirely, which is the one thing partial validation must not do.
     */
    @Test
    fun theTwoRelevanceShapesDoDisagreeOnThisCondition() {
        val rows = listOf("C" to "P", "D" to "Q")
        val group = "NoFieldValueIncludedInValueList($ITEMS* In \"A\")"
        val wild = agree(group, rows, wildK, wildI, "control: wildcard")
        val concrete = agree(group, rows, concreteK, concreteI, "control: concrete-only")
        assertTrue(wild != concrete,
            "the extent gate must separate wildcard from concrete-only relevance on this condition, or the " +
                "discriminator above is comparing two identical answers: wildcard=$wild concrete=$concrete")
    }
}
