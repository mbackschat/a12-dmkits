package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Computation's INDEX-DEFAULT staging and preliminary index poison (SPEC-2026-07-23-15) on the PURE engine,
 * JVM AND JS/Node — the cross-platform twin of the `adapter.laws.ComputationEagerPrepassLawsTest` SPEC-15
 * arms (whose expectations are the real kernel's, dual-strategy). Compute applies the SAME full-call
 * S-value staging as full validation (SPEC-2026-07-23-14): an eligible clean-empty enum index reads its
 * declaration-owned token, so a consumer computes a VALUE. An INELIGIBLE bad index (multi-row empty, or
 * duplicated) is preliminarily invalid — a computation READING it is poisoned (CLEARED on the report-all
 * projection), never computed from an empty-as-"" or duplicated read; an ordinary required-empty operand
 * stays empty-as-0. E-commerce domain (own fixtures).
 */
class ComputationIndexDefaultMultiplatformTest {

    private companion object {
        const val ITEMS = "/Inventory/Items"
        const val SKU = "/Inventory/Items/Sku"
        const val ECHO = "/Inventory/Items/SkuEcho"
    }

    private fun def(sValue: String? = "STD") = ModelDef(
        listOf(
            FieldDef(SKU, Kind.STRING, enumValues = listOf("STD", "EXPRESS"), enumSValue = sValue),
            FieldDef(ECHO, Kind.STRING),
        ),
        emptyList(),
        repeatableGroups = setOf(ITEMS),
        declaredReps = mapOf(ITEMS to 20),
        indexFields = setOf(SKU),
        computations = listOf(ComputationDef(ECHO, ITEMS, listOf(ComputationAlternative(null, "[Sku]")))),
    )

    private fun outcomes(def: ModelDef, doc: io.github.mbackschat.a12.dm.interpreter.eval.Document) =
        DmInterpreter(def).compute(doc).filter { it.path == ECHO }
            .map { when (it.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${it.value}" } }
            .sorted()

    @Test
    fun anEligibleAbsentIndexComputesOnItsStagedDefault() {
        val m = def()
        val doc = m.newDocument().group("/Inventory", 1).group(ITEMS, 1, 1)
        assertEquals(listOf("VALUE|STD"), outcomes(m, doc), "the consumer reads the staged S-value")
    }

    @Test
    fun anEligiblePresentEmptyIndexComputesOnItsStagedDefaultToo() {
        val m = def()
        val doc = m.newDocument().group("/Inventory", 1).group(ITEMS, 1, 1).emptyField(SKU, 1, 1, 1)
        assertEquals(listOf("VALUE|STD"), outcomes(m, doc), "a present-empty cell is clean-empty too")
    }

    @Test
    fun anAdmittedExplicitIndexIsNotDefaulted() {
        val m = def()
        val doc = m.newDocument().group("/Inventory", 1)
            .group(ITEMS, 1, 1).field(SKU, "EXPRESS", 1, 1, 1)
        assertEquals(listOf("VALUE|EXPRESS"), outcomes(m, doc), "the admitted value is read, never overridden")
    }

    @Test
    fun anIneligibleMultiRowEmptyIndexPoisonsItsReaders() {
        val m = def()
        val doc = m.newDocument().group("/Inventory", 1).group(ITEMS, 1, 1).group(ITEMS, 1, 2)
        assertEquals(listOf("CLEARED", "CLEARED"), outcomes(m, doc),
            "two clean-empty rows get no default; the preliminarily invalid empty index poisons each reader")
    }

    @Test
    fun aDuplicatedIndexPoisonsItsReaders() {
        val m = def()
        val doc = m.newDocument().group("/Inventory", 1)
            .group(ITEMS, 1, 1).field(SKU, "STD", 1, 1, 1)
            .group(ITEMS, 1, 2).field(SKU, "STD", 1, 2, 1)
        assertEquals(listOf("CLEARED", "CLEARED"), outcomes(m, doc),
            "the uniqueIndex-invalid duplicate cells poison their readers — never VALUE|STD twice")
    }

    /** Without an S-value the single empty index cell stays preliminarily invalid — the -14 default is
     *  the ONLY thing that rescues it (the control for the two staging tests above). */
    @Test
    fun withoutAnSValueTheSingleEmptyIndexStillPoisons() {
        val m = def(sValue = null)
        val doc = m.newDocument().group("/Inventory", 1).group(ITEMS, 1, 1)
        assertEquals(listOf("CLEARED"), outcomes(m, doc), "no default declared → the empty index poisons its reader")
    }
}
