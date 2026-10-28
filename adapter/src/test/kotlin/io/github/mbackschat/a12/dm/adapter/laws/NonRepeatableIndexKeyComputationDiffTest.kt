package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.fired
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.outcomes
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a computation reads from the empty nominated key of a non-repeatable group, a12-kernel-lean
 * SPEC-2026-10-28-03 and -06 ([KERNEL-FINDINGS KF638]). The key is required only while its group holds content,
 * as the generated mandatory check is ([KERNEL-FINDINGS KF638]): a holder with a name and no key clears the
 * computation reading the key, while a holder present but holding nothing, or holding only the key as `null`,
 * leaves the key an ordinary empty that `FirstFilledValue` reads past to `0`. The unnominated `Payer` is the
 * control on every row. Every row is three-engine agreed; the targets are seeded so that a clear is visible.
 *
 * Own domain (an insurance claim's holder and payer).
 */
class NonRepeatableIndexKeyComputationDiffTest {

    private val base = DocumentModelDraft.named("claim-holder-key").locale("en_US")
        .rootGroup("Claim") { c -> c.stringField("Ref") { }
            .numberField("HolderCode") { it.fractionDigits(0) }.numberField("PayerCode") { it.fractionDigits(0) }
            .group("Holder") { h -> h.numberField("HolderKey") { it.fractionDigits(0) }
                .numberField("Alt") { it.fractionDigits(0) }.stringField("Name") { }.indexField("HolderKey") }
            .group("Payer") { p -> p.numberField("PayerKey") { it.fractionDigits(0) }
                .numberField("Alt") { it.fractionDigits(0) } } }
        .build().toJson()

    private val model = admitted(listOf(
        Triple("HolderCodeComp", "../HolderCode", "FirstFilledValue(Holder/HolderKey, Holder/Alt)"),
        Triple("PayerCodeComp", "../PayerCode", "FirstFilledValue(Payer/PayerKey, Payer/Alt)"),
    ).fold(base) { json, (name, target, op) -> RuntimeLaws.withComputation(json, "/Claim", name, target, op) })

    private fun doc(holder: String?, payer: String?) = """{"Claim":{"Ref":"R","HolderCode":9,"PayerCode":9""" +
        (holder?.let { ""","Holder":$it""" } ?: "") + (payer?.let { ""","Payer":$it""" } ?: "") + "}}"

    @Test
    fun anEmptyKeyPoisonsOnlyWhileItsGroupHoldsContent() {
        val observed = linkedMapOf(
            "a named holder without a key" to doc("""{"Alt":5,"Name":"N"}""", """{"Alt":5}"""),
            "a holder holding nothing" to doc("{}", "{}"),
            "a holder holding only a null key" to doc("""{"HolderKey":null}""", """{"PayerKey":null}"""),
            "a keyed holder" to doc("""{"HolderKey":1,"Alt":5}""", """{"PayerKey":1,"Alt":5}"""),
            "no holder" to doc(null, null),
        ).mapValues { (_, document) -> outcomes(model, document) }
        val bothZero = listOf("/Claim[1]/HolderCode|VALUE|0", "/Claim[1]/PayerCode|VALUE|0")
        assertEquals(linkedMapOf(
            "a named holder without a key" to listOf("/Claim[1]/HolderCode|CLEARED", "/Claim[1]/PayerCode|VALUE|5"),
            "a holder holding nothing" to bothZero,
            "a holder holding only a null key" to bothZero,
            "a keyed holder" to listOf("/Claim[1]/HolderCode|VALUE|1", "/Claim[1]/PayerCode|VALUE|1"),
            "no holder" to bothZero,
        ), observed)
    }

    /** The validation half: the generated check of the computation that reads the empty key stays silent while its
     *  seeded unnominated twin reports the stored `9` it would overwrite, beside the key's own `mandatoryField`. */
    @Test
    fun theComputationReadingAnEmptyKeyHasNoGeneratedFinding() {
        val observed = linkedMapOf(
            "a named holder without a key" to doc("""{"Alt":5,"Name":"N"}""", """{"Alt":5}"""),
            "a keyed holder" to doc("""{"HolderKey":1,"Alt":5}""", """{"PayerKey":1,"Alt":5}"""),
        ).mapValues { (_, document) -> fired(model, document) }
        assertEquals(linkedMapOf(
            "a named holder without a key" to listOf("PayerCodeComp", "mandatoryField"),
            "a keyed holder" to listOf("HolderCodeComp", "PayerCodeComp"),
        ), observed)
    }
}
