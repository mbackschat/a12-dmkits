package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — **index-field uniqueness** (`uniqueIndex`, KERNEL-FINDINGS). The kernel auto-checks a
 * repeatable group's `indexFieldName`: every row whose (non-empty) index value DUPLICATES another row's in the
 * same parent scope is flagged (every participant, not all-but-first); an empty/absent index value is excluded
 * (→ `mandatoryField` instead). Scoped per parent — a nested group's uniqueness is within ONE parent row, so
 * the same `PartId` under two different Lines does NOT clash. Compared on the (code, field-pointer) pairs for
 * `uniqueIndex` + `mandatoryField`, so both the firing AND the per-row attribution must match.
 */
class UniquenessDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val def = ModelDef(
        listOf(FieldDef(ITEM, Kind.STRING), FieldDef(PART, Kind.STRING)),
        emptyList(),
        repeatableGroups = setOf("/Cart/Lines", "/Cart/Lines/Parts"),
        declaredReps = mapOf("/Cart/Lines" to 1000, "/Cart/Lines/Parts" to 1000),
        indexFields = setOf(ITEM, PART),
    )

    /** Builds the SAME instance into both engines; a null id models an absent (unfilled) index field. */
    private inner class Build {
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats)
        fun line(i: Int, item: String?) {
            kDoc.group("/Cart/Lines", 1, i); iDoc.group("/Cart/Lines", 1, i)
            if (item != null) { kDoc.field(ITEM, item, 1, i, 1); iDoc.field(ITEM, item, 1, i, 1) }
        }
        fun part(li: Int, pi: Int, id: String?) {
            kDoc.group("/Cart/Lines/Parts", 1, li, pi); iDoc.group("/Cart/Lines/Parts", 1, li, pi)
            if (id != null) { kDoc.field(PART, id, 1, li, pi, 1); iDoc.field(PART, id, 1, li, pi, 1) }
        }
    }

    private fun compare(label: String, build: Build.() -> Unit) {
        val b = Build().apply(build)
        val codes = setOf("uniqueIndex", "mandatoryField")
        fun pairs(cs: List<Pair<String, String>>) = cs.map { "${it.first}@${it.second}" }.sorted()
        val kernel = pairs(RuntimeLaws.messages(fuzz, b.kDoc).filter { it.code in codes }.map { it.code to it.field })
        val interp = pairs(DmInterpreter(def).validate(b.iDoc).messages.filter { it.code in codes }.map { it.code to it.errorPath })
        assertEquals(kernel, interp, "$label  kernel=$kernel interp=$interp")
    }

    @Test fun allUniqueFiresNothing() = compare("A,B,C") { line(1, "A"); line(2, "B"); line(3, "C") }
    @Test fun bothDuplicatesFlagged() = compare("A,A,B") { line(1, "A"); line(2, "A"); line(3, "B") }
    @Test fun allThreeFlagged() = compare("X,X,X") { line(1, "X"); line(2, "X"); line(3, "X") }
    @Test fun absentIndexIsMandatoryNotDuplicate() = compare("A,absent,A") { line(1, "A"); line(2, null); line(3, "A") }

    @Test fun nestedDuplicateWithinOneLine() = compare("parts P,P in line 1") {
        line(1, "L1"); part(1, 1, "P"); part(1, 2, "P")
    }

    @Test fun samePartIdAcrossDifferentLinesDoesNotClash() = compare("P under L1 and L2") {
        line(1, "L1"); part(1, 1, "P")
        line(2, "L2"); part(2, 1, "P")
    }

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val PART = "/Cart/Lines/Parts/PartId"
    }
}
