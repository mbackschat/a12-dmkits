package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — `uniqueIndex` under a SUBSET of relevant rows (the IG9-sibling probe). Two `Lines` rows share
 * the index value `A`; partial validation marks ONLY row 1 relevant (its duplicate partner, row 2, is NOT). The
 * interpreter detects duplicates by scanning ALL rows, then emits only for the relevant cell — so it flags row 1.
 * The question this settles against the kernel: does the kernel flag row 1's `uniqueIndex` when the partner row
 * is not relevant? KERNEL-FINDINGS "Partial validation".
 */
class PartialValidationUniqueIndexDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val def = ModelDef(
        listOf(FieldDef(ITEM, Kind.STRING)),
        emptyList(),
        repeatableGroups = setOf("/Cart/Lines"),
        declaredReps = mapOf("/Cart/Lines" to 1000),
        indexFields = setOf(ITEM),
    )

    // Two rows, same index value A → a duplicate pair.
    private fun kDoc() = RuntimeLaws.doc().group("/Cart", 1)
        .group("/Cart/Lines", 1, 1).field(ITEM, "A", 1, 1, 1)
        .group("/Cart/Lines", 1, 2).field(ITEM, "A", 1, 2, 1)
    private fun iDoc() = Document(def.kinds, def.declaredReps).group("/Cart", 1)
        .group("/Cart/Lines", 1, 1).field(ITEM, "A", 1, 1, 1)
        .group("/Cart/Lines", 1, 2).field(ITEM, "A", 1, 2, 1)

    private fun kUnique(rel: Set<Relevant>) =
        RuntimeLaws.messagesPart(fuzz, kDoc(), rel).count { it.code == "uniqueIndex" }
    private fun iUnique(rel: Set<RelevantEntity>) =
        DmInterpreter(def).validatePart(iDoc(), rel).messages.count { it.code == "uniqueIndex" }

    /** Sanity: BOTH rows relevant → both engines flag both duplicates (count 2). */
    @Test fun bothRowsRelevantFlagsBoth() {
        val kRel = setOf(Relevant.of(ITEM, 0, 0, 0))
        val iRel = setOf(RelevantEntity.anyRep(ITEM))
        assertEquals(2, kUnique(kRel), "kernel flags both duplicate rows")
        assertEquals(kUnique(kRel), iUnique(iRel), "both-relevant — kernel=${kUnique(kRel)} interp=${iUnique(iRel)}")
    }

    /** The probe: only ROW 1 relevant; row 2 (its duplicate partner) is NOT. Does the kernel still flag row 1? */
    @Test fun onlyOneRowRelevantAgreesWithKernel() {
        val kRel = setOf(Relevant.of(ITEM, 0, 1, 0))                    // Lines row 1 only
        val iRel = setOf(RelevantEntity(ITEM, listOf(0, 1, 0)))
        val k = kUnique(kRel)
        val i = iUnique(iRel)
        assertEquals(k, i, "row-1-only uniqueIndex — kernel=$k interp=$i")
    }

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
    }
}
