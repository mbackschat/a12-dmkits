package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — under PARTIAL validation a starred value-list entry has a separate COMPLETE-EXTENT fact
 * (SPEC-2026-07-22-09). The extent is established only by WILDCARD coverage of the starred level (or a relevant
 * ancestor supplying it) — enumerating every currently-instantiated row CONCRETELY does not. An extent-unknown
 * entry follows the side-specific UNKNOWN table: `AtLeastOne` ignores it on either side; `No` is suppressed on
 * either side; `NotAll` ignores it on the fields side but is suppressed on the values side.
 *
 * Each relevant set is compared kernel-vs-interpreter (not a hardcoded expectation), and the decisive cases also
 * assert that the kernel fires under wildcard extent but is suppressed under concrete-only relevance — so the
 * distinction is real, not vacuously both-suppressed. `/Order { Ack, Items[50] { Tag } }`; the always-relevant
 * `Ack` is the error field. KERNEL-FINDINGS "Partial validation"; the aggregate analog is
 * `PartialValidationAggregateDiffTest` (IF42).
 */
class PartialValidationValueListDiffTest {

    private val json = DocumentModelDraft.named("catalog").locale("en_US")
        .rootGroup("Order") { g ->
            g.stringField("Ack") { }
                .group("Items") { it.repeatable(50).stringField("Tag") { } }
        }.build().toJson()

    private val ackK = Relevant.of(ACK, 0, 0)
    private val ackI = RelevantEntity.anyRep(ACK)
    private val wildK = setOf(ackK, Relevant.of(ITEMS, 0, 0))
    private val wildI = setOf(ackI, RelevantEntity.anyRep(ITEMS))
    private val concreteK = setOf(ackK, Relevant.of(ITEMS, 0, 1), Relevant.of(ITEMS, 0, 2))
    private val concreteI = setOf(ackI, RelevantEntity(ITEMS, listOf(0, 1)), RelevantEntity(ITEMS, listOf(0, 2)))
    private val ancestorK = setOf(Relevant.of("/Order", 0))
    private val ancestorI = setOf(RelevantEntity.anyRep("/Order"))
    private val staticEngines = mutableMapOf<String, RuntimeLaws.JavaKernelEngine>()

    /** Fire set of the VLQ rule, asserted equal kernel-vs-interpreter; returns that set. The error field
     *  `Ack` is guarded in so it is referenced (MVK_ERROR_FIELD_NOT_REFERENCED otherwise); the `FieldFilled`
     *  guard is a separate conjunct that does not touch the value-list entry's extent. */
    private fun agree(rawCond: String, ack: String, tags: List<String>, kRel: Set<Relevant>, iRel: Set<RelevantEntity>, label: String): Set<String> {
        val cond = "FieldFilled(/Order/Ack) And ($rawCond)"
        val model = RuntimeLaws.withRule(json, "/Order", "VLQ", "../Ack", "VLQ", cond)
        val def = ModelLoader.load(model)
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(ACK, ack, 1, 1)
        val iDoc = def.newDocument().group("/Order", 1).field(ACK, ack, 1, 1)
        tags.forEachIndexed { idx, t ->
            val r = idx + 1
            kDoc.group(ITEMS, 1, r).field(TAG, t, 1, r, 1)
            iDoc.group(ITEMS, 1, r).field(TAG, t, 1, r, 1)
        }
        val dynamic = RuntimeLaws.messagesPart(model, kDoc, kRel).map { it.code() }.filter { it == "VLQ" }.toSortedSet()
        val static = staticEngines.getOrPut(model) { RuntimeLaws.kernelJavaEngine(model) }
            .messagesPart(kDoc, kRel).map { it.code() }.filter { it == "VLQ" }.toSortedSet()
        val i = DmInterpreter(def).validatePart(iDoc, iRel).messages.map { it.code }.filter { it == "VLQ" }.toSortedSet()
        assertEquals(dynamic, static, "$label — dynamic-Groovy=$dynamic static-Java=$static")
        assertEquals(dynamic, i, "$label — dynamic-Groovy=$dynamic interp=$i")
        return dynamic
    }

    // ── No / NotAll(values) / No(values): extent-sensitive → suppressed under concrete-only relevance ─────

    @Test
    fun noFieldsSideStarNeedsWildcardExtent() {
        val cond = "NoFieldValueIncludedInValueList(/Order/Items*/Tag In \"A\")"     // Tags C,D ∉ {A} → No holds
        val wild = agree(cond, "x", listOf("C", "D"), wildK, wildI, "No fields-side, wildcard extent")
        val concrete = agree(cond, "x", listOf("C", "D"), concreteK, concreteI, "No fields-side, concrete rows")
        assertEquals(setOf("VLQ"), wild, "wildcard coverage establishes extent → No fires")
        assertEquals(emptySet<String>(), concrete, "concrete rows do NOT establish extent → No is UNKNOWN")
    }

    @Test
    fun noValuesSideStarNeedsWildcardExtent() {
        val cond = "NoFieldValueIncludedInValueList(/Order/Ack In /Order/Items*/Tag)"   // Ack X ∉ {C,D} → No holds
        val wild = agree(cond, "X", listOf("C", "D"), wildK, wildI, "No values-side, wildcard extent")
        val concrete = agree(cond, "X", listOf("C", "D"), concreteK, concreteI, "No values-side, concrete rows")
        assertEquals(setOf("VLQ"), wild, "wildcard coverage establishes extent → No fires")
        assertEquals(emptySet<String>(), concrete, "concrete rows do NOT establish extent → No is UNKNOWN")
    }

    @Test
    fun notAllValuesSideStarNeedsWildcardExtent() {
        val cond = "NotAllFieldValuesIncludedInValueList(/Order/Ack In /Order/Items*/Tag)"  // Ack X ∉ {C,D} → NotAll holds
        val wild = agree(cond, "X", listOf("C", "D"), wildK, wildI, "NotAll values-side, wildcard extent")
        val concrete = agree(cond, "X", listOf("C", "D"), concreteK, concreteI, "NotAll values-side, concrete rows")
        assertEquals(setOf("VLQ"), wild, "wildcard coverage establishes extent → NotAll fires")
        assertEquals(emptySet<String>(), concrete, "concrete rows do NOT establish extent → NotAll(values) is UNKNOWN")
    }

    // ── controls: fields-side NotAll and AtLeastOne ignore extent; a non-star entry is trivially complete ───

    @Test
    fun notAllFieldsSideIgnoresExtent() {
        val cond = "NotAllFieldValuesIncludedInValueList(/Order/Items*/Tag In \"C\")"   // C in, D out → NotAll holds
        val wild = agree(cond, "x", listOf("C", "D"), wildK, wildI, "NotAll fields-side, wildcard")
        val concrete = agree(cond, "x", listOf("C", "D"), concreteK, concreteI, "NotAll fields-side, concrete")
        assertEquals(setOf("VLQ"), wild, "NotAll fires")
        assertEquals(wild, concrete, "NotAll ignores extent on the fields side — concrete matches wildcard")
    }

    @Test
    fun atLeastOneIgnoresExtent() {
        val cond = "AtLeastOneFieldValueIncludedInValueList(/Order/Items*/Tag In \"C\")"  // C ∈ {C} → holds
        val wild = agree(cond, "x", listOf("C", "D"), wildK, wildI, "AtLeastOne, wildcard")
        val concrete = agree(cond, "x", listOf("C", "D"), concreteK, concreteI, "AtLeastOne, concrete")
        assertEquals(setOf("VLQ"), wild, "AtLeastOne fires")
        assertEquals(wild, concrete, "AtLeastOne ignores fields-side extent — concrete matches wildcard")

        val valuesCond = "AtLeastOneFieldValueIncludedInValueList(/Order/Ack In /Order/Items*/Tag)"
        val valuesWild = agree(valuesCond, "C", listOf("C", "D"), wildK, wildI, "AtLeastOne values-side, wildcard")
        val valuesConcrete = agree(valuesCond, "C", listOf("C", "D"), concreteK, concreteI,
            "AtLeastOne values-side, concrete")
        assertEquals(setOf("VLQ"), valuesWild, "AtLeastOne(values) fires")
        assertEquals(valuesWild, valuesConcrete, "AtLeastOne ignores values-side extent — concrete matches wildcard")
    }

    @Test
    fun relevantAncestorEstablishesWildcardExtent() {
        val cond = "NoFieldValueIncludedInValueList(/Order/Items*/Tag In \"A\")"
        val ancestor = agree(cond, "x", listOf("C", "D"), ancestorK, ancestorI, "No fields-side, relevant ancestor")
        assertEquals(setOf("VLQ"), ancestor, "a relevant ancestor supplies complete extent to its descendant star")
    }

    /**
     * The value-list extent gate is EXISTENTIAL: **one** covering relevant identifier that wildcards the starred
     * level establishes the extent, and concrete siblings beside it do not take it away.
     *
     * This is the gate's own measurement rather than an inference from the all-rows aggregate path it briefly
     * shared an implementation with. On the middle row the two kernel-DIVERGE — a starred `No` fires while `Sum`
     * over the same rows stays UNKNOWN — which is why the shared predicate had to be split ([IF264], the full
     * seven-shape table in `PartialExtentGateShapeDiffTest`).
     */
    @Test
    fun oneWildcardEstablishesTheExtentEvenBesideConcreteRows() {
        val cond = "NoFieldValueIncludedInValueList(/Order/Items*/Tag In \"A\")"
        val rows = listOf("C", "D")
        val tagWildcard = Relevant.of(TAG, 1, 0, 1)
        val tagWildcardI = RelevantEntity(TAG, listOf(1, 0, 1))
        val concreteRows = listOf(Relevant.of(ITEMS, 0, 1), Relevant.of(ITEMS, 0, 2))
        val concreteRowsI = listOf(RelevantEntity(ITEMS, listOf(0, 1)), RelevantEntity(ITEMS, listOf(0, 2)))

        val fieldWildcardOnly = agree(cond, "x", rows,
            setOf(ackK, tagWildcard), setOf(ackI, tagWildcardI), "field wildcard alone")
        val besideConcreteRows = agree(cond, "x", rows,
            setOf(ackK, tagWildcard) + concreteRows, setOf(ackI, tagWildcardI) + concreteRowsI,
            "field wildcard beside concrete group rows")
        val concreteRowsOnly = agree(cond, "x", rows,
            setOf(ackK) + concreteRows, setOf(ackI) + concreteRowsI, "concrete group rows only")

        assertEquals(setOf("VLQ"), fieldWildcardOnly, "a lone field wildcard establishes the extent")
        assertEquals(
            setOf("VLQ"), besideConcreteRows,
            "adding concrete group rows beside that wildcard does not take the extent away — existential, not " +
                "universal, and the opposite of what the all-rows aggregate gate does with the same set",
        )
        assertEquals(
            emptySet<String>(), concreteRowsOnly,
            "with the wildcard removed there is nothing left to establish the extent — the positive control " +
                "that keeps the row above from passing vacuously",
        )
    }

    @Test
    fun nonRepeatableEntryIsTriviallyComplete() {
        val cond = "NoFieldValueIncludedInValueList(/Order/Ack In \"A\")"   // Ack X ∉ {A} → No holds; no star
        val only = setOf(ackK)
        val onlyI = setOf(ackI)
        val fired = agree(cond, "X", listOf("C", "D"), only, onlyI, "non-repeatable No, error field relevant")
        assertEquals(setOf("VLQ"), fired, "a plain (non-star) entry has no extent question → No fires")
    }

    private companion object {
        const val ACK = "/Order/Ack"
        const val ITEMS = "/Order/Items"
        const val TAG = "/Order/Items/Tag"
    }
}
