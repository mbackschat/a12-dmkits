package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — IG40(c)/IF64: `RepetitionNotUnique` over a key whose cell is FORMALLY INVALID. The kernel builds its
 * duplicate cache from **check-relevant** cells only (`befuelleCacheMitRelevantenFeldern`); a formally-invalid
 * key cell — a malformed value, a required-empty cell, or a **duplicate-index (`uniqueIndex`) cell** (IF15/IF59:
 * "a formally invalid KEY field → UNKNOWN, not check-relevant") — is EXCLUDED from the cache, so it is not a
 * duplicate participant and RNU does not fire on it. The interpreter's RNU built its key from the raw display
 * value (no formal-validity gate), so it counted a formally-invalid cell as a duplicate and fired where the kernel
 * suppressed (the "RepetitionNotUnique × uniqueIndex" divergence: interp fires, kernel does not).
 *
 * Own-domain e-commerce; the model is loaded into BOTH engines via [ModelLoader]; conditions are stored-form
 * (RepetitionNotUnique is not typed-DSL-authorable in these shapes). Filtered to the authored RNU rule's code, so
 * the auto `uniqueIndex` message (a separate code) never confuses the comparison.
 */
class RepetitionNotUniqueFormalKeyDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    private class Scenario(val kModel: String, val kDoc: RuntimeLaws.Doc, val interp: DmInterpreter, val iDoc: Document)

    /** Inject `RepetitionNotUnique(Items/<key>)` on `/Order` (the kernel requires the RNU rule to sit ABOVE the
     *  repeatable group — `MVK_REPEATABLE_GROUP_MISSING`); build `Items` rows = [rows] (`(sku, count)`, null =
     *  empty). Equal `sku` values are duplicate INDEXES (Sku is the model's index field). */
    private fun scenario(key: String, errorRel: String, rows: List<Pair<String?, String?>>): Scenario {
        val cond = "RepetitionNotUnique(Items/$key)"
        val kModel = RuntimeLaws.withRule(order, "/Order", "R", errorRel, CODE, cond)
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = def.newDocument()
        rows.forEachIndexed { i, (sku, count) ->
            val r = i + 1
            kDoc.group(ITEMS, 1, r); iDoc.group(ITEMS, 1, r)
            if (sku != null) { kDoc.field("$ITEMS/Sku", sku, 1, r, 1); iDoc.field("$ITEMS/Sku", sku, 1, r, 1) }
            if (count != null) { kDoc.field("$ITEMS/Count", count, 1, r, 1); iDoc.field("$ITEMS/Count", count, 1, r, 1) }
        }
        return Scenario(kModel, kDoc, DmInterpreter(def), iDoc)
    }

    private fun interpSigs(s: Scenario) = s.interp.validateFull(s.iDoc).filter { it.code == CODE }
        .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()

    private fun groovySigs(s: Scenario) = RuntimeLaws.messages(s.kModel, s.kDoc).filter { it.code() == CODE }
        .map { "${it.field()}|${it.type().name}" }.sorted()

    private fun agree(s: Scenario, msg: String) =
        assertEquals(groovySigs(s), interpSigs(s), "$msg\n  kernel=${groovySigs(s)}\n  interp=${interpSigs(s)}")

    // The flagship: RNU over the INDEX field, rows sharing an index value. The uniqueIndex auto-check invalidates
    // both cells → excluded from the RNU cache → RNU does NOT fire (only uniqueIndex does).
    @Test fun rnuOverDuplicateIndexKeyDoesNotFire() =
        agree(scenario("Sku", "../Items/Sku", listOf("DUP" to "1", "DUP" to "2")),
            "RNU(index) on a duplicated index: kernel excludes the invalid cells → RNU does not fire")

    // Control: RNU over the index with DISTINCT values → no uniqueIndex, no duplicate → RNU does not fire either
    // (and the whole thing is clean).
    @Test fun rnuOverDistinctIndexKeyDoesNotFire() =
        agree(scenario("Sku", "../Items/Sku", listOf("A" to "1", "B" to "2")),
            "RNU(index) with distinct indexes: no duplicate, no fire")

    // Generalization (second instance, same root — a formally-invalid key cell is excluded regardless of CAUSE):
    // RNU over a non-index key (Count) where the duplicate rows ALSO have a duplicate INDEX (Sku). The rows are
    // formally invalid via uniqueIndex on Sku, but the RNU key is Count. Does the kernel still fire RNU(Count)?
    @Test fun rnuOverCleanKeyOnFormallyInvalidRows() =
        agree(scenario("Count", "../Items/Count", listOf("DUP" to "7", "DUP" to "7")),
            "RNU(Count) on rows with a duplicate INDEX: does the row's formal-invalidity suppress RNU(Count)?")

    // A mix: two rows share the index (invalid) + a third clean row; the RNU is over the index. Only the invalid
    // rows would be duplicates, but they are excluded → RNU fires on none.
    @Test fun rnuOverIndexKeyWithMixedRows() =
        agree(scenario("Sku", "../Items/Sku", listOf("DUP" to "1", "DUP" to "2", "U" to "3")),
            "RNU(index) with two duplicate-index rows + one unique: RNU fires on none")

    // Generalization to a DIFFERENT UNKNOWN cause (proves the root is formal-invalidity, not dup-index): both
    // rows' Count is MALFORMED (a format error; distinct Sku so no uniqueIndex noise). The kernel excludes the
    // malformed cells from the cache → RNU does not fire; the interpreter used to treat the raw "1x" text as a
    // duplicate value and fire on both.
    @Test fun rnuOverMalformedKeyCellsDoesNotFire() =
        agree(scenario("Count", "../Items/Count", listOf("A" to "1x", "B" to "1x")),
            "RNU(Count) over malformed key cells: excluded → RNU does not fire")

    // Both strategies: codegen's `EntityListCondition.st` calls the SAME runtime `wiederholungNichtEindeutig` /
    // `ermittleWiederholungenMitDuplikaten` (IF62), so the check-relevant-cells-only exclusion holds on the
    // compiled path too — interp == groovy-dynamic == static-JAVA on the flagship duplicate-index shape.
    @Test fun codegenExcludesFormallyInvalidKeysIdentically() {
        val s = scenario("Sku", "../Items/Sku", listOf("DUP" to "1", "DUP" to "2"))
        val groovy = groovySigs(s)
        val engine = RuntimeLaws.kernelJavaEngine(s.kModel)
        val javaStatic = engine.messages(engine.instance(s.kDoc)).filter { it.code() == CODE }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        assertEquals(groovy, interpSigs(s), "codegen (interp≠groovy)\n  kernel=$groovy\n  interp=${interpSigs(s)}")
        assertEquals(groovy, javaStatic, "codegen (java-static≠groovy)\n  groovy=$groovy\n  java=$javaStatic")
    }
}

private const val CODE = "RNUF"
private const val ITEMS = "/Order/Items"
