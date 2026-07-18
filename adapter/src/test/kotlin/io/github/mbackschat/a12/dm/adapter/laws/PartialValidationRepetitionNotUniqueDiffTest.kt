package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — `RepetitionNotUnique` under a SUBSET of relevant rows (the IG9-sibling of the uniqueIndex probe).
 * Two `Items` rows share `Count = 5`; partial validation marks ONLY row 1 relevant. Like `uniqueIndex`, the
 * kernel builds its duplicate cache from the relevant fields only, so a relevant row whose duplicate partner is
 * not relevant is NOT flagged — the interpreter (which scanned all rows then gated emission) must match.
 * KERNEL-FINDINGS "Partial validation". (Distinct `Sku` per row keeps the `uniqueIndex` auto-check silent.)
 */
class PartialValidationRepetitionNotUniqueDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)
    private val cond = DslRenderer().render(Dsl.repetitionNotUnique(m.field(COUNT)))
    private val kModel = RuntimeLaws.withRule(order, "/Order", "RU", "../Items/Count", "RU", cond)
    private val def = ModelDef(
        listOf(FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER)),
        listOf(RuleDef("/Order/RU", "/Order", cond, "RU", "../Items/Count")),
        repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 50), indexFields = setOf(SKU),
    )

    // Two rows, Count 5,5 (a duplicate pair); distinct Sku so uniqueIndex stays silent.
    private fun kDoc() = RuntimeLaws.doc().group("/Order", 1)
        .group("/Order/Items", 1, 1).field(SKU, "K1", 1, 1, 1).field(COUNT, "5", 1, 1, 1)
        .group("/Order/Items", 1, 2).field(SKU, "K2", 1, 2, 1).field(COUNT, "5", 1, 2, 1)
    private fun iDoc() = def.newDocument().group("/Order", 1)
        .group("/Order/Items", 1, 1).field(SKU, "K1", 1, 1, 1).field(COUNT, "5", 1, 1, 1)
        .group("/Order/Items", 1, 2).field(SKU, "K2", 1, 2, 1).field(COUNT, "5", 1, 2, 1)

    private fun kRu(rel: Set<Relevant>) = RuntimeLaws.messagesPart(kModel, kDoc(), rel).count { it.code == "RU" }
    private fun iRu(rel: Set<RelevantEntity>) = DmInterpreter(def).validatePart(iDoc(), rel).messages.count { it.code == "RU" }

    /** Sanity: BOTH rows relevant → both flagged (count 2). */
    @Test fun bothRowsRelevantFlagsBoth() {
        val kRel = setOf(Relevant.of(COUNT, 0, 0, 0))
        val iRel = setOf(RelevantEntity.anyRep(COUNT))
        assertEquals(2, kRu(kRel), "kernel flags both duplicate rows")
        assertEquals(kRu(kRel), iRu(iRel), "both-relevant — kernel=${kRu(kRel)} interp=${iRu(iRel)}")
    }

    /** The probe: only ROW 1 relevant; its duplicate partner (row 2) is NOT. */
    @Test fun onlyOneRowRelevantAgreesWithKernel() {
        val kRel = setOf(Relevant.of(COUNT, 0, 1, 0))                  // Items row 1's Count only
        val iRel = setOf(RelevantEntity(COUNT, listOf(0, 1, 0)))
        val k = kRu(kRel)
        val i = iRu(iRel)
        assertEquals(k, i, "row-1-only RepetitionNotUnique — kernel=$k interp=$i")
    }

    private companion object {
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
    }
}
